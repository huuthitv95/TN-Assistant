package main

// Owner-managed data is deliberately separate from the original paid driving
// bridge. Empty coverage is not evidence that a road has no speed restriction.
import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

func (s *Server) managedSchema() error {
	_, e := s.db.Exec(`CREATE TABLE IF NOT EXISTS managed_roads (rowid INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,data TEXT NOT NULL);
 CREATE VIRTUAL TABLE IF NOT EXISTS managed_road_index USING rtree(id,min_lat,max_lat,min_lon,max_lon);`)
	return e
}

func (s *Server) managedConfig() Object {
	m, e := s.record("config", "managed_data")
	if e != nil {
		return Object{"driving_enabled": false, "local_tts_url": "http://127.0.0.1:8092", "tts_voice": "Thùy Dung"}
	}
	return m
}
func (s *Server) managedDrivingEnabled() bool { return s.managedConfig()["driving_enabled"] == true }

func managedNumber(m Object, key string, min, max float64) (float64, error) {
	v, ok := m[key].(float64)
	if !ok || math.IsNaN(v) || math.IsInf(v, 0) || v < min || v > max {
		return 0, problem(400, "INVALID_MANAGED_DATA", "Invalid numeric "+key)
	}
	return v, nil
}
func managedSource(m Object) error {
	if len(str(m, "source")) == 0 || len(str(m, "source")) > 1000 || len(str(m, "license")) == 0 || len(str(m, "license")) > 1000 {
		return problem(400, "SOURCE_REQUIRED", "Source and license or ownership permission are required")
	}
	if _, e := time.Parse(time.RFC3339, str(m, "source_updated_at")); e != nil {
		return problem(400, "SOURCE_DATE_REQUIRED", "source_updated_at must be RFC3339")
	}
	return nil
}
func validateManagedRoad(m Object) error {
	if str(m, "kind") != "road" && str(m, "kind") != "camera" && str(m, "kind") != "sign" {
		return problem(400, "INVALID_MANAGED_DATA", "kind must be road, camera or sign")
	}
	if _, e := managedNumber(m, "latitude", -90, 90); e != nil {
		return e
	}
	if _, e := managedNumber(m, "longitude", -180, 180); e != nil {
		return e
	}
	if _, e := managedNumber(m, "bearing", 0, 359.999999); e != nil {
		return e
	}
	if m["max_speed"] != nil {
		if _, e := managedNumber(m, "max_speed", 1, 200); e != nil {
			return e
		}
	}
	if len(str(m, "id")) > 160 || strings.ContainsAny(str(m, "id"), "/\\") {
		return problem(400, "INVALID_MANAGED_DATA", "Invalid road id")
	}
	return managedSource(m)
}
func managedPutRoad(tx *sql.Tx, m Object) error {
	id := str(m, "id")
	if id == "" {
		id = ident()
		m["id"] = id
	}
	if _, e := tx.Exec(`INSERT INTO managed_roads(id,kind,latitude,longitude,data) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET kind=excluded.kind,latitude=excluded.latitude,longitude=excluded.longitude,data=excluded.data`, id, str(m, "kind"), m["latitude"], m["longitude"], jsonText(m)); e != nil {
		return e
	}
	_, e := tx.Exec(`INSERT OR REPLACE INTO managed_road_index SELECT rowid,latitude,latitude,longitude,longitude FROM managed_roads WHERE id=?`, id)
	return e
}
func (s *Server) managedAPI(w http.ResponseWriter, r *http.Request, p *Principal) (bool, error) {
	path := r.URL.Path
	adminPath := strings.HasPrefix(path, "/v1/admin/managed/")
	publicPath := path == "/v1/news/managed" || strings.HasPrefix(path, "/v1/news/managed/") || path == "/v1/news/sources" || path == "/v1/driving/feedback"
	if !adminPath && !publicPath {
		return false, nil
	}
	if adminPath && str(p.User, "role") != "admin" {
		return true, problem(403, "FORBIDDEN", "Administrator role required")
	}
	if e := s.managedSchema(); e != nil {
		return true, e
	}
	if publicPath {
		if path == "/v1/driving/feedback" {
			return true, s.managedFeedback(w, r, p, "")
		}
		if s.independentRuntime() {
			cfg, err := s.runtimeConfig()
			if err != nil {
				return true, err
			}
			if !cfg.NewsEnabled {
				return true, problem(503, "MANAGED_NEWS_DISABLED", "TN managed news is disabled")
			}
		}
		if s.premiumFeatures(str(p.User, "id"))["ai_news"] != true {
			return true, problem(403, "FEATURE_NOT_AVAILABLE", "Premium news required")
		}
		if path == "/v1/news/sources" {
			return true, s.publicNewsSources(w, r)
		}
		return true, s.managedPublicNews(w, r, p)
	}
	part := strings.TrimPrefix(path, "/v1/admin/managed/")
	pieces := strings.Split(part, "/")
	switch pieces[0] {
	case "config":
		if len(pieces) != 1 {
			return true, problem(404, "NOT_FOUND", "Unknown managed route")
		}
		if r.Method == "GET" {
			send(w, 200, s.managedConfig())
			return true, nil
		}
		if r.Method != "PUT" {
			return true, problem(405, "METHOD_NOT_ALLOWED", "GET or PUT required")
		}
		m, e := body(r)
		if e != nil {
			return true, e
		}
		if _, ok := m["driving_enabled"].(bool); !ok {
			return true, problem(400, "INVALID_CONFIG", "driving_enabled must be boolean")
		}
		u, e := url.Parse(str(m, "local_tts_url"))
		if e != nil || u.Scheme != "http" || (u.Hostname() != "127.0.0.1" && u.Hostname() != "localhost" && u.Hostname() != "::1") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" {
			return true, problem(400, "INVALID_CONFIG", "Local TTS requires an explicit loopback HTTP origin")
		}
		if len(str(m, "tts_voice")) == 0 || len(str(m, "tts_voice")) > 100 {
			return true, problem(400, "INVALID_CONFIG", "tts_voice required")
		}
		clean := Object{"driving_enabled": m["driving_enabled"], "local_tts_url": u.String(), "tts_voice": str(m, "tts_voice")}
		if e := s.put("config", "managed_data", "", clean); e != nil {
			return true, e
		}
		send(w, 200, clean)
		return true, nil
	case "roads":
		return true, s.managedRoadAdmin(w, r, p, pieces[1:])
	case "feedback":
		id := ""
		if len(pieces) == 2 {
			id = pieces[1]
		}
		return true, s.managedFeedback(w, r, p, id)
	case "news":
		return true, s.managedNewsAdmin(w, r, p, pieces[1:])
	}
	return true, problem(404, "NOT_FOUND", "Unknown managed route")
}

func (s *Server) managedRoadAdmin(w http.ResponseWriter, r *http.Request, p *Principal, parts []string) error {
	if r.Method == "GET" && len(parts) == 0 {
		rows, e := s.db.Query("SELECT data FROM managed_roads ORDER BY rowid DESC LIMIT 500")
		if e != nil {
			return e
		}
		defer rows.Close()
		items := []Object{}
		for rows.Next() {
			var data string
			if e := rows.Scan(&data); e != nil {
				return e
			}
			var m Object
			if json.Unmarshal([]byte(data), &m) == nil {
				items = append(items, m)
			}
		}
		send(w, 200, Object{"items": items, "limit": 500})
		return rows.Err()
	}
	if r.Method == "DELETE" && len(parts) == 1 {
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		defer tx.Rollback()
		if _, e = tx.Exec("DELETE FROM managed_road_index WHERE id IN (SELECT rowid FROM managed_roads WHERE id=?)", parts[0]); e != nil {
			return e
		}
		if _, e = tx.Exec("DELETE FROM managed_roads WHERE id=?", parts[0]); e != nil {
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
		return nil
	}
	if r.Method != "POST" && r.Method != "PUT" {
		return problem(405, "METHOD_NOT_ALLOWED", "Unsupported road method")
	}
	m, e := body(r)
	if e != nil {
		return e
	}
	items := []Object{}
	if len(parts) == 1 && parts[0] == "import" {
		raw, ok := m["items"].([]any)
		if !ok || len(raw) == 0 || len(raw) > 2000 {
			return problem(400, "INVALID_IMPORT", "items must contain 1 to 2000 rows")
		}
		for _, v := range raw {
			row, ok := v.(map[string]any)
			if !ok {
				return problem(400, "INVALID_IMPORT", "Each item must be an object")
			}
			items = append(items, row)
		}
	} else if len(parts) == 0 || (len(parts) == 1 && r.Method == "PUT") {
		if len(parts) == 1 {
			m["id"] = parts[0]
		}
		items = append(items, m)
	} else {
		return problem(404, "NOT_FOUND", "Unknown road route")
	}
	for _, row := range items {
		if e := validateManagedRoad(row); e != nil {
			return e
		}
	}
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	for _, row := range items {
		row["updated_at"] = now()
		if e := managedPutRoad(tx, row); e != nil {
			return e
		}
	}
	if e := tx.Commit(); e != nil {
		return e
	}
	send(w, 200, Object{"imported": len(items), "items": items})
	return nil
}

func managedDistance(lat1, lon1, lat2, lon2 float64) float64 {
	rad := math.Pi / 180
	dlat := (lat2 - lat1) * rad
	dlon := (lon2 - lon1) * rad
	a := math.Sin(dlat/2)*math.Sin(dlat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dlon/2)*math.Sin(dlon/2)
	return 6371000 * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(math.Max(0, 1-a)))
}
func (s *Server) managedDrivingArea(w http.ResponseWriter, r *http.Request, p *Principal, area string, coords map[string]float64) error {
	if e := s.managedSchema(); e != nil {
		return e
	}
	lat, lon := coords["lat"], coords["lon"]
	radius := 2500.0
	deltaLat := radius / 110000
	deltaLon := math.Min(180, radius/(110000*math.Max(0.01, math.Cos(lat*math.Pi/180))))
	rows, e := s.db.Query(`SELECT roads.rowid,roads.data FROM managed_road_index idx JOIN managed_roads roads ON roads.rowid=idx.id WHERE idx.min_lat<=? AND idx.max_lat>=? AND idx.min_lon<=? AND idx.max_lon>=? LIMIT 10000`, lat+deltaLat, lat-deltaLat, lon+deltaLon, lon-deltaLon)
	if e != nil {
		return e
	}
	candidates := []Object{}
	missing := 0
	known := 0
	sources := map[string]bool{}
	for rows.Next() {
		var rowid int64
		var data string
		if e := rows.Scan(&rowid, &data); e != nil {
			rows.Close()
			return e
		}
		var m Object
		if json.Unmarshal([]byte(data), &m) != nil {
			continue
		}
		distance := managedDistance(lat, lon, m["latitude"].(float64), m["longitude"].(float64))
		if distance > radius {
			continue
		}
		kind := str(m, "kind")
		if area == "speedlimit" && kind != "road" {
			continue
		}
		if area == "speedcam" && kind == "road" {
			continue
		}
		sources[str(m, "source")] = true
		if m["max_speed"] == nil {
			missing++
			if kind == "road" {
				continue
			}
		}
		known++
		speed := m["max_speed"]
		if speed == nil {
			speed = 0.0
		}
		row := Object{"bearing": m["bearing"], "latitude": m["latitude"], "longitude": m["longitude"], "name": str(m, "name"), "id_sy": rowid, "distance": distance, "source": m["source"], "source_updated_at": m["source_updated_at"], "license": m["license"], "managed_id": str(m, "id")}
		if area == "speedlimit" {
			row["max_speed"] = speed
		} else {
			row["speed_max"] = speed
			row["type"] = str(m, "type")
			if row["type"] == "" {
				row["type"] = "701"
			}
			row["direction_unknown"] = m["direction_unknown"] == true
			row["speed_known"] = m["max_speed"] != nil
		}
		candidates = append(candidates, row)
	}
	if e := rows.Err(); e != nil {
		rows.Close()
		return e
	}
	rows.Close()
	sort.Slice(candidates, func(i, j int) bool { return candidates[i]["distance"].(float64) < candidates[j]["distance"].(float64) })
	coverage := "available"
	if known == 0 {
		coverage = "missing"
	} else if missing > 0 {
		coverage = "partial"
	}
	sourceList := []string{}
	for source := range sources {
		sourceList = append(sourceList, source)
	}
	sort.Strings(sourceList)
	send(w, 200, Object{"area": area, "data": candidates, "provider": "managed", "coverage": coverage, "unknown_speed_records": missing, "sources": sourceList, "attribution": "© OpenStreetMap contributors — Open Database License (ODbL) 1.0: https://www.openstreetmap.org/copyright", "radius_m": radius})
	return nil
}

func (s *Server) managedFeedback(w http.ResponseWriter, r *http.Request, p *Principal, id string) error {
	admin := str(p.User, "role") == "admin"
	if r.Method == "GET" && admin {
		items, e := s.list("managed_feedback", "", "")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": items})
		return nil
	}
	if r.Method == "POST" && id == "" {
		m, e := body(r)
		if e != nil {
			return e
		}
		if len(str(m, "message")) < 1 || len(str(m, "message")) > 4000 {
			return problem(400, "INVALID_FEEDBACK", "message required, max 4000 characters")
		}
		if str(m, "road_id") != "" {
			var exists int
			if e = s.db.QueryRow("SELECT count(*) FROM managed_roads WHERE id=?", str(m, "road_id")).Scan(&exists); e != nil {
				return e
			}
			if exists == 0 {
				return problem(404, "NOT_FOUND", "Unknown road")
			}
		}
		row := Object{"message": str(m, "message"), "road_id": str(m, "road_id"), "status": "pending", "created_at": now()}
		key := ident()
		if e = s.put("managed_feedback", key, str(p.User, "id"), row); e != nil {
			return e
		}
		send(w, 201, row)
		return nil
	}
	if r.Method == "PATCH" && admin && id != "" {
		m, e := body(r)
		if e != nil {
			return e
		}
		status := str(m, "status")
		if status != "accepted" && status != "rejected" {
			return problem(400, "INVALID_FEEDBACK", "status must be accepted or rejected")
		}
		row, e := s.record("managed_feedback", id)
		if e != nil {
			return e
		}
		row["status"] = status
		row["review_note"] = str(m, "review_note")
		row["reviewed_at"] = now()
		row["reviewed_by"] = str(p.User, "id")
		// Accepting feedback records the review; source-validated road correction is
		// an explicit edit, never an untrusted automatic speed-limit replacement.
		if e = s.put("managed_feedback", id, "", row); e != nil {
			return e
		}
		send(w, 200, row)
		return nil
	}
	return problem(405, "METHOD_NOT_ALLOWED", fmt.Sprintf("Unsupported feedback method %s", r.Method))
}
