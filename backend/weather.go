package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type weatherCacheEntry struct {
	Data         json.RawMessage
	RetrievedAt  string
	ExpiresAt    time.Time
	ETag         string
	LastModified string
	Deprecated   bool
}

var weatherRate = struct {
	sync.Mutex
	next time.Time
}{}

func waitWeatherRate(ctx context.Context) error {
	weatherRate.Lock()
	now := time.Now()
	wait := time.Duration(0)
	if weatherRate.next.After(now) {
		wait = weatherRate.next.Sub(now)
		weatherRate.next = weatherRate.next.Add(80 * time.Millisecond)
	} else {
		weatherRate.next = now.Add(80 * time.Millisecond)
	}
	weatherRate.Unlock()
	if wait == 0 {
		return nil
	}
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func coordinate(value string, min, max float64) (float64, error) {
	parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) || parsed < min || parsed > max {
		return 0, fmt.Errorf("invalid coordinate")
	}
	// MET Norway requires no more than four decimal places for coordinates.
	return math.Trunc(parsed*10000) / 10000, nil
}

func weatherExpiry(header http.Header, now time.Time) time.Time {
	if value := header.Get("Cache-Control"); value != "" {
		for _, directive := range strings.Split(value, ",") {
			parts := strings.SplitN(strings.TrimSpace(directive), "=", 2)
			if len(parts) == 2 && strings.EqualFold(parts[0], "max-age") {
				if seconds, err := strconv.Atoi(strings.Trim(parts[1], `"`)); err == nil && seconds >= 0 {
					return now.Add(time.Duration(seconds) * time.Second)
				}
			}
		}
	}
	if value := header.Get("Expires"); value != "" {
		if expires, err := http.ParseTime(value); err == nil {
			return expires
		}
	}
	return now.Add(5 * time.Minute)
}

func (s *Server) currentWeather(w http.ResponseWriter, r *http.Request) error {
	query := r.URL.Query()
	if len(query["lat"]) != 1 || len(query["lon"]) != 1 || len(query) != 2 {
		return problem(400, "INVALID_LOCATION", "Exactly one lat and lon coordinate are required")
	}
	lat, err := coordinate(query.Get("lat"), -90, 90)
	if err != nil {
		return problem(400, "INVALID_LOCATION", "Valid latitude and longitude are required")
	}
	lon, err := coordinate(query.Get("lon"), -180, 180)
	if err != nil {
		return problem(400, "INVALID_LOCATION", "Valid latitude and longitude are required")
	}
	result, err := s.weatherForecast(r.Context(), lat, lon)
	if err != nil {
		return err
	}
	send(w, http.StatusOK, result)
	return nil
}

func (s *Server) weatherForecast(ctx context.Context, lat, lon float64) (Object, error) {
	if math.IsNaN(lat) || math.IsInf(lat, 0) || lat < -90 || lat > 90 || math.IsNaN(lon) || math.IsInf(lon, 0) || lon < -180 || lon > 180 {
		return nil, problem(400, "INVALID_LOCATION", "Valid latitude and longitude are required")
	}
	lat = math.Trunc(lat*10000) / 10000
	lon = math.Trunc(lon*10000) / 10000
	key := fmt.Sprintf("%.4f,%.4f", lat, lon)
	now := time.Now()
	s.weatherMu.Lock()
	entry, found := s.weatherCache[key]
	if found && now.Before(entry.ExpiresAt) {
		s.weatherMu.Unlock()
		return weatherResponse(entry, true), nil
	}
	s.weatherMu.Unlock()

	base, err := url.Parse(s.weatherBase)
	if err != nil || base.User != nil || base.RawQuery != "" || base.Fragment != "" ||
		(base.Scheme != "https" && !(base.Scheme == "http" && (base.Hostname() == "127.0.0.1" || base.Hostname() == "localhost" || base.Hostname() == "::1"))) {
		return nil, problem(503, "WEATHER_CONFIG_INVALID", "MET Norway proxy requires the fixed HTTPS endpoint")
	}
	base.RawQuery = url.Values{"lat": {keyFloat(lat)}, "lon": {keyFloat(lon)}}.Encode()
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	if err = waitWeatherRate(ctx); err != nil {
		return nil, problem(503, "WEATHER_UPSTREAM_UNAVAILABLE", "Weather service is busy; retry shortly")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, base.String(), nil)
	if err != nil {
		return nil, problem(503, "WEATHER_CONFIG_INVALID", "Unable to prepare weather request")
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "TN Assistant/1.0 (support@tnfastvn.com)")
	if found {
		if entry.ETag != "" {
			request.Header.Set("If-None-Match", entry.ETag)
		}
		if entry.LastModified != "" {
			request.Header.Set("If-Modified-Since", entry.LastModified)
		}
	}
	client := &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 2 || req.URL.Scheme != base.Scheme || req.URL.Host != base.Host {
			return http.ErrUseLastResponse
		}
		return nil
	}}
	response, err := client.Do(request)
	if err != nil {
		return nil, problem(503, "WEATHER_UPSTREAM_UNAVAILABLE", "MET Norway weather service is unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotModified && found {
		entry.ExpiresAt = weatherExpiry(response.Header, time.Now())
		if value := response.Header.Get("ETag"); value != "" {
			entry.ETag = value
		}
		if value := response.Header.Get("Last-Modified"); value != "" {
			entry.LastModified = value
		}
		s.cacheWeather(key, entry)
		return weatherResponse(entry, false), nil
	}
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusNonAuthoritativeInfo {
		return nil, problem(503, "WEATHER_UPSTREAM_UNAVAILABLE", "MET Norway did not return a current forecast")
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, (2<<20)+1))
	if err != nil || len(raw) > 2<<20 || !json.Valid(raw) {
		return nil, problem(502, "WEATHER_RESPONSE_INVALID", "MET Norway returned an invalid forecast")
	}
	entry = weatherCacheEntry{Data: append(json.RawMessage(nil), raw...), RetrievedAt: time.Now().UTC().Format(time.RFC3339), ExpiresAt: weatherExpiry(response.Header, time.Now()), ETag: response.Header.Get("ETag"), LastModified: response.Header.Get("Last-Modified"), Deprecated: response.StatusCode == http.StatusNonAuthoritativeInfo}
	s.cacheWeather(key, entry)
	return weatherResponse(entry, false), nil
}

func keyFloat(value float64) string { return strconv.FormatFloat(value, 'f', 4, 64) }

func (s *Server) cacheWeather(key string, entry weatherCacheEntry) {
	s.weatherMu.Lock()
	defer s.weatherMu.Unlock()
	if len(s.weatherCache) >= 1000 {
		for existing, cached := range s.weatherCache {
			if time.Now().After(cached.ExpiresAt) {
				delete(s.weatherCache, existing)
			}
		}
		if len(s.weatherCache) >= 1000 {
			for existing := range s.weatherCache {
				delete(s.weatherCache, existing)
				break
			}
		}
	}
	s.weatherCache[key] = entry
}

func weatherResponse(entry weatherCacheEntry, cached bool) Object {
	return Object{
		"provider":            "MET Norway",
		"source":              "https://api.met.no/weatherapi/locationforecast/2.0/compact",
		"license":             "CC BY 4.0 / NLOD 2.0",
		"attribution":         "Weather data from MET Norway",
		"retrieved_at":        entry.RetrievedAt,
		"expires_at":          entry.ExpiresAt.UTC().Format(time.RFC3339),
		"cached":              cached,
		"deprecated_upstream": entry.Deprecated,
		"forecast":            entry.Data,
	}
}

func isTNWeatherRequest(text string) bool {
	normalized := normalizeTNCommand(text)
	for _, phrase := range []string{"thoi tiet", "nhiet do", "troi hom nay", "du bao hom nay"} {
		if strings.Contains(normalized, phrase) {
			return true
		}
	}
	return false
}

func weatherUtterance(response Object) (string, error) {
	var raw json.RawMessage
	switch forecast := response["forecast"].(type) {
	case json.RawMessage:
		raw = forecast
	case []byte:
		raw = append(json.RawMessage(nil), forecast...)
	case map[string]any:
		encoded, err := json.Marshal(forecast)
		if err != nil {
			return "", fmt.Errorf("invalid forecast object: %w", err)
		}
		raw = encoded
	default:
		return "", fmt.Errorf("missing forecast")
	}
	var decoded struct {
		Properties struct {
			Timeseries []struct {
				Data struct {
					Instant struct {
						Details map[string]any `json:"details"`
					} `json:"instant"`
				} `json:"data"`
			} `json:"timeseries"`
		} `json:"properties"`
	}
	if json.Unmarshal(raw, &decoded) != nil || len(decoded.Properties.Timeseries) == 0 {
		return "", fmt.Errorf("forecast has no current timeseries")
	}
	details := decoded.Properties.Timeseries[0].Data.Instant.Details
	temperature, ok := details["air_temperature"].(float64)
	if !ok || math.IsNaN(temperature) || math.IsInf(temperature, 0) {
		return "", fmt.Errorf("forecast has no current air temperature")
	}
	utterance := "Thời tiết hiện tại, nhiệt độ " + strconv.FormatFloat(temperature, 'f', 1, 64) + " độ C"
	if wind, exists := details["wind_speed"].(float64); exists && !math.IsNaN(wind) && !math.IsInf(wind, 0) {
		utterance += ", gió " + strconv.FormatFloat(wind, 'f', 1, 64) + " mét mỗi giây"
	}
	return utterance + ". Dữ liệu từ MET Norway.", nil
}
