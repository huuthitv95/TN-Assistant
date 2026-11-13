package main

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Original n1/z protocol. Kept separate from the plate-check provider: a
// sandbox entitlement cannot replace the provider's own paid account check.
func (s *Server) drivingArea(w http.ResponseWriter, r *http.Request, principal *Principal, area string) error {
	if area != "speedlimit" && area != "speedcam" {
		return problem(404, "NOT_FOUND", "Unknown driving data area")
	}
	m, e := body(r)
	if e != nil {
		return e
	}
	coordinates := map[string]float64{}
	for key, limit := range map[string]float64{"lat": 90, "lon": 180, "bearing": 360} {
		value, ok := m[key].(float64)
		if key == "bearing" && !ok && m[key] == nil {
			value = 0
			ok = true
		}
		if !ok || math.IsNaN(value) || math.IsInf(value, 0) || (key != "bearing" && math.Abs(value) > limit) || (key == "bearing" && (value < 0 || value >= 360)) {
			return problem(400, "INVALID_LOCATION", "Valid numeric lat/lon and bearing required")
		}
		coordinates[key] = value
	}
	// A foreground segment matcher needs a small local window in dense cities.
	// Older clients retain the default 2.5 km query.
	if raw, exists := m["radius_m"]; exists {
		value, ok := raw.(float64)
		if !ok || math.IsNaN(value) || math.IsInf(value, 0) || value < 100 || value > 2500 {
			return problem(400, "INVALID_RADIUS", "radius_m must be numeric between 100 and 2500")
		}
		coordinates["radius_m"] = value
	}
	if s.premiumFeatures(str(principal.User, "id"))["driving_info"] != true {
		return problem(403, "FEATURE_NOT_AVAILABLE", "Gói hiện tại chưa có Cảnh báo giao thông")
	}
	if s.independentRuntime() {
		cfg, err := s.runtimeConfig()
		if err != nil {
			return err
		}
		if !cfg.DrivingEnabled {
			return problem(503, "DRIVING_DISABLED", "TN managed driving data is disabled")
		}
		return s.managedDrivingArea(w, r, principal, area, coordinates)
	}
	if s.managedDrivingEnabled() {
		return s.managedDrivingArea(w, r, principal, area, coordinates)
	}
	p, e := s.requireProvider("driving")
	if e != nil {
		return e
	}
	if p.Mode != "legacy_tn" {
		return problem(503, "PROVIDER_CONFIG_INVALID", "Driving provider requires the Original area protocol")
	}
	endpoint, e := url.Parse(p.URL)
	if e != nil || endpoint.Host == "" {
		return problem(503, "PROVIDER_CONFIG_INVALID", "Invalid driving endpoint")
	}
	endpoint.Path = strings.TrimRight(endpoint.Path, "/") + "/" + area
	p.URL = endpoint.String()
	stamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
	md5text := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
	form := url.Values{"client_app_type": {p.ClientAppType}, "version_code": {p.Version}, "timestamp": {stamp}, "hash": {md5text(os.Getenv(p.SecretEnv) + md5text(p.ClientAppType+stamp))},
		"device_id": {os.Getenv(str(p.Options, "device_id_env"))}, "os_version": {"28"}, "device_type": {"ANDROID_CAR_HEAD"}}
	for key, value := range coordinates {
		if key == "radius_m" {
			continue
		} // Managed-only extension; not an upstream protocol field.
		form.Set(key, strconv.FormatFloat(value, 'f', -1, 64))
	}
	if extra, ok := p.Options["form_fields"].(map[string]any); ok {
		for _, key := range []string{"os_version", "device_type"} {
			if value, exists := extra[key]; exists {
				form.Set(key, fmt.Sprint(value))
			}
		}
	}
	start := time.Now()
	raw, _, e := providerRequest(r.Context(), p, "application/x-www-form-urlencoded", []byte(form.Encode()))
	if e != nil {
		return e
	}
	var response struct {
		Code *int            `json:"code"`
		Data json.RawMessage `json:"data"`
	}
	if json.Unmarshal(raw, &response) != nil || response.Code == nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Driving reply missing code")
	}
	if *response.Code == -110001 {
		return problem(403, "DRIVING_PREMIUM_REQUIRED", "Tài khoản dịch vụ dữ liệu đường chưa có Premium")
	}
	if *response.Code != 0 {
		return problem(502, "DRIVING_PROVIDER_ERROR", "Dịch vụ dữ liệu đường từ chối yêu cầu")
	}
	var records []Object
	if len(response.Data) == 0 || string(response.Data) == "null" || json.Unmarshal(response.Data, &records) != nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Driving data must be an array")
	}
	// Do not synthesize limits or convert a malformed/denied reply to empty
	// success. Array items retain the Original schema for the road matcher.
	for _, record := range records {
		if record == nil {
			return problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid driving record")
		}
	}
	send(w, 200, Object{"area": area, "data": records, "provider_latency_ms": time.Since(start).Milliseconds()})
	return nil
}
