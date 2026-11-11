package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
)

func TestMETNorwayWeatherProxyTruncatesAndCaches(t *testing.T) {
	var requests atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Header.Get("User-Agent") != "TN Assistant/1.0 (support@tnfastvn.com)" {
			t.Errorf("missing identifying User-Agent: %q", r.Header.Get("User-Agent"))
		}
		if r.URL.Query().Get("lat") != "10.1234" || r.URL.Query().Get("lon") != "106.9876" {
			t.Errorf("coordinates were not truncated to 4 decimals: %s", r.URL.RawQuery)
		}
		w.Header().Set("Cache-Control", "max-age=60")
		w.Header().Set("ETag", `"forecast-1"`)
		w.Write([]byte(`{"properties":{"timeseries":[{"data":{"instant":{"details":{"air_temperature":26.5,"wind_speed":2.4}}}}]}}`))
	}))
	defer upstream.Close()

	s := testServer(t)
	s.weatherBase = upstream.URL
	token := str(guest(t, s), "access_token")
	first := url.QueryEscape("10.12349")
	lon := url.QueryEscape("106.98769")
	status, response := call(t, s, http.MethodGet, "/v1/weather/current?lat="+first+"&lon="+lon, token, nil)
	requireStatus(t, 200, status, response)
	if response["provider"] != "MET Norway" || response["license"] != "CC BY 4.0 / NLOD 2.0" || response["cached"] != false {
		t.Fatalf("unexpected forecast metadata: %#v", response)
	}
	secondLat := url.QueryEscape("10.12341")
	secondLon := url.QueryEscape("106.98761")
	status, response = call(t, s, http.MethodGet, "/v1/weather/current?lat="+secondLat+"&lon="+secondLon, token, nil)
	requireStatus(t, 200, status, response)
	if response["cached"] != true || requests.Load() != 1 {
		t.Fatalf("same truncated coordinates should use one upstream request; requests=%d response=%#v", requests.Load(), response)
	}
	utterance, err := weatherUtterance(response)
	if err != nil || !strings.Contains(utterance, "26.5 độ C") || !strings.Contains(utterance, "2.4 mét mỗi giây") {
		t.Fatalf("unexpected real forecast speech summary %q err=%v", utterance, err)
	}
}

func TestIndependentVoiceWeatherUsesMETProxyAndUserLocation(t *testing.T) {
	s := independentTestServer(t)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("lat") != "21.0250" || r.URL.Query().Get("lon") != "105.8100" {
			t.Errorf("incorrect location sent to weather source: %s", r.URL.RawQuery)
		}
		w.Header().Set("Cache-Control", "max-age=60")
		w.Write([]byte(`{"properties":{"timeseries":[{"data":{"instant":{"details":{"air_temperature":26.5,"wind_speed":2.4}}}}]}}`))
	}))
	defer upstream.Close()
	s.weatherBase = upstream.URL
	token := str(guest(t, s), "access_token")
	status, response := call(t, s, http.MethodPost, "/v1/assistant/text", token, Object{
		"text": "Thời tiết hôm nay thế nào?", "mode": "commands", "request_id": "weather-voice",
		"lat": 21.02509, "lon": 105.81009,
	})
	requireStatus(t, 200, status, response)
	if !strings.Contains(str(response, "utterance"), "26.5 độ C") || str(response, "source") != "tn_command_router" {
		t.Fatalf("voice weather did not return the proxied observation: %#v", response)
	}
}

func TestMETNorwayWeatherRejectsOutOfRangeAndDuplicateCoordinates(t *testing.T) {
	s := testServer(t)
	token := str(guest(t, s), "access_token")
	for _, target := range []string{
		"/v1/weather/current?lat=91&lon=106",
		"/v1/weather/current?lat=10&lat=11&lon=106",
		"/v1/weather/current?lat=10&lon=106&altitude=10",
	} {
		status, response := call(t, s, http.MethodGet, target, token, nil)
		if status != 400 {
			t.Fatalf("%s: expected 400, got %d %#v", target, status, response)
		}
	}
}
