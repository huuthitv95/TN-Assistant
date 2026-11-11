package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func managedFixtureRoad(id string, speed any) Object {
	return Object{"id": id, "kind": "road", "latitude": 10.7769, "longitude": 106.7009, "bearing": 0.0, "name": "Contract test road", "max_speed": speed, "source": "contract-test—not actual coverage", "license": "test-owned", "source_updated_at": "2026-10-09T00:00:00Z"}
}

func TestPublisherNewsSourcesRequirePremiumAndContainSafeLinks(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	status, m := call(t, s, "GET", "/v1/news/sources", token, nil)
	requireStatus(t, 403, status, m)

	uid := str(g["user"].(map[string]any), "id")
	status, m = call(t, s, "PUT", "/v1/admin/premium-local/"+uid, adminToken(t, s), Object{"duration_days": 1})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/news/sources", token, nil)
	requireStatus(t, 200, status, m)
	items, ok := m["items"].([]any)
	if !ok || len(items) != 47 || int(m["count"].(float64)) != 47 {
		t.Fatalf("expected all 47 requested publishers, got count=%v items=%d", m["count"], len(items))
	}
	ids, urls := map[string]bool{}, map[string]bool{}
	for _, raw := range items {
		item := raw.(map[string]any)
		id, _ := item["id"].(string)
		link, _ := item["url"].(string)
		if id == "" || ids[id] || link == "" || urls[link] {
			t.Fatalf("duplicate or empty publisher identity/link: %#v", item)
		}
		u, err := url.Parse(link)
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
			t.Fatalf("unsafe publisher URL %q: %v", link, err)
		}
		ids[id], urls[link] = true, true
		if _, hasArticleText := item["text"]; hasArticleText {
			t.Fatal("source directory must not expose article text")
		}
	}
	status, m = call(t, s, "POST", "/v1/news/sources", token, Object{})
	requireStatus(t, 405, status, m)
}

func TestManagedRoadRBACAtomicImportAndMissingCoverage(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	admin := adminToken(t, s)
	status, m := call(t, s, "POST", "/v1/admin/managed/roads", token, managedFixtureRoad("no", 50.0))
	requireStatus(t, 403, status, m)
	config := Object{"driving_enabled": true, "local_tts_url": "http://127.0.0.1:8092", "tts_voice": "Mai Anh"}
	status, m = call(t, s, "PUT", "/v1/admin/managed/config", admin, config)
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, Object{"lat": 10.7769, "lon": 106.7009})
	requireStatus(t, 403, status, m)
	grantDrivingFixture(t, s, g)
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, Object{"lat": 10.7769, "lon": 106.7009})
	requireStatus(t, 200, status, m)
	if str(m, "coverage") != "missing" || len(m["data"].([]any)) != 0 {
		t.Fatal("empty coverage became guessed limits")
	}
	invalid := managedFixtureRoad("invalid", 50.0)
	invalid["latitude"] = 200.0
	status, m = call(t, s, "POST", "/v1/admin/managed/roads/import", admin, Object{"items": []Object{managedFixtureRoad("rollback", 50.0), invalid}})
	requireStatus(t, 400, status, m)
	var count int
	s.db.QueryRow("SELECT count(*) FROM managed_roads").Scan(&count)
	if count != 0 {
		t.Fatal("partially committed invalid import")
	}
	camera := managedFixtureRoad("camera", nil)
	camera["kind"] = "camera"
	camera["type"] = "701"
	status, m = call(t, s, "POST", "/v1/admin/managed/roads/import", admin, Object{"items": []Object{managedFixtureRoad("known", 50.0), managedFixtureRoad("unknown", nil), camera}})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, Object{"lat": 10.7769, "lon": 106.7009})
	requireStatus(t, 200, status, m)
	if str(m, "coverage") != "partial" || len(m["data"].([]any)) != 1 || m["data"].([]any)[0].(map[string]any)["max_speed"] != 50.0 {
		t.Fatal("unknown speed was asserted", m)
	}
	status, m = call(t, s, "POST", "/v1/driving/area/speedcam", token, Object{"lat": 10.7769, "lon": 106.7009})
	requireStatus(t, 200, status, m)
	if m["data"].([]any)[0].(map[string]any)["speed_known"] != false {
		t.Fatal("unknown camera speed falsely known")
	}
	if attribution, _ := m["attribution"].(string); !strings.Contains(attribution, "OpenStreetMap contributors") || !strings.Contains(attribution, "https://www.openstreetmap.org/copyright") {
		t.Fatalf("managed OSM results are missing attribution and license link: %q", attribution)
	}
	status, m = call(t, s, "POST", "/v1/driving/feedback", token, Object{"road_id": "known", "message": "Please verify speed against signed source"})
	requireStatus(t, 201, status, m)
	id := str(m, "id")
	status, m = call(t, s, "PATCH", "/v1/admin/managed/feedback/"+id, admin, Object{"status": "accepted", "review_note": "Reviewed; no unsourced speed mutation"})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/admin/managed/roads", admin, nil)
	requireStatus(t, 200, status, m)
	if len(m["items"].([]any)) != 3 {
		t.Fatal("feedback unexpectedly changed rows")
	}
}
func managedFixtureWAV() []byte {
	b := new(bytes.Buffer)
	b.WriteString("RIFF")
	binary.Write(b, binary.LittleEndian, uint32(36+480))
	b.WriteString("WAVEfmt ")
	binary.Write(b, binary.LittleEndian, uint32(16))
	for _, v := range []uint16{1, 1} {
		binary.Write(b, binary.LittleEndian, v)
	}
	binary.Write(b, binary.LittleEndian, uint32(24000))
	binary.Write(b, binary.LittleEndian, uint32(48000))
	binary.Write(b, binary.LittleEndian, uint16(2))
	binary.Write(b, binary.LittleEndian, uint16(16))
	b.WriteString("data")
	binary.Write(b, binary.LittleEndian, uint32(480))
	b.Write(make([]byte, 480))
	return b.Bytes()
}
func TestManagedNewsRightsAsyncAudioDedupAndPremiumPlayback(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	admin := adminToken(t, s)
	token := str(g, "access_token")
	var requests atomic.Int32
	release := make(chan struct{})
	started := make(chan struct{})
	wav := managedFixtureWAV()
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/synthesize" {
			requestNumber := requests.Add(1)
			var m Object
			json.NewDecoder(r.Body).Decode(&m)
			if strings.Contains(str(m, "text"), "TN") {
				t.Error("TN was not normalized")
			}
			if requestNumber == 1 {
				close(started)
				<-release
			}
			send(w, 200, Object{"url": "/audio/fixture.wav"})
			return
		}
		w.Write(wav)
	}))
	defer fixture.Close()
	status, m := call(t, s, "PUT", "/v1/admin/managed/config", admin, Object{"driving_enabled": false, "local_tts_url": fixture.URL, "tts_voice": "Mai Anh"})
	requireStatus(t, 200, status, m)
	news := Object{"title": "Owned contract test", "text": "TN chào bạn. " + strings.Repeat("Bản tin kiểm tra hợp đồng. ", 30), "source": "owner-created contract text", "license": "owner permission", "source_updated_at": "2026-10-09T00:00:00Z", "rights_confirmed": false, "published": true}
	status, m = call(t, s, "POST", "/v1/admin/managed/news", admin, news)
	requireStatus(t, 400, status, m)
	news["rights_confirmed"] = true
	status, m = call(t, s, "POST", "/v1/admin/managed/news", admin, news)
	requireStatus(t, 200, status, m)
	id := str(m, "id")
	status, m = call(t, s, "POST", "/v1/admin/managed/news/"+id+"/audio", admin, Object{})
	requireStatus(t, 202, status, m)
	jobID := str(m, "id")
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("worker did not start")
	}
	for i := 0; i < 20; i++ {
		status, m = call(t, s, "POST", "/v1/admin/managed/news/"+id+"/audio", admin, Object{})
		requireStatus(t, 202, status, m)
		if str(m, "id") != jobID {
			t.Fatal("duplicate audio job")
		}
	}
	close(release)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		status, m = call(t, s, "GET", "/v1/admin/managed/news/"+id+"/job", admin, nil)
		requireStatus(t, 200, status, m)
		if str(m, "status") == "ready" {
			break
		}
		if str(m, "status") == "failed" {
			t.Fatal(m)
		}
		time.Sleep(10 * time.Millisecond)
	}
	if str(m, "status") != "ready" || requests.Load() < 2 {
		t.Fatal("long text not chunked/generated", m)
	}
	status, m = call(t, s, "POST", "/v1/news/managed/"+id+"/play", token, Object{})
	requireStatus(t, 403, status, m)
	uid := str(g["user"].(map[string]any), "id")
	s.put("products", "news-test", "", Object{"features": []any{"ai_news"}})
	s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", ident(), uid, "news-test", "fixture", time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano), time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano))
	status, m = call(t, s, "POST", "/v1/news/managed/"+id+"/play", token, Object{})
	requireStatus(t, 200, status, m)
	directive := m["directives"].([]any)[0].(map[string]any)
	if str(directive, "type") != "PlayerPodcast" || directive["payload"] == nil {
		t.Fatal("invalid podcast contract")
	}
	request := httptest.NewRequest("GET", "/v1/news/managed/"+id+"/audio", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Range", "bytes=0-43")
	response := httptest.NewRecorder()
	s.Handler().ServeHTTP(response, request)
	if response.Code != 206 || len(response.Body.Bytes()) != 44 {
		t.Fatal("Android range playback unsupported", response.Code)
	}
	news["published"] = false
	status, m = call(t, s, "PUT", "/v1/admin/managed/news/"+id, admin, news)
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "POST", "/v1/news/managed/"+id+"/play", token, Object{})
	requireStatus(t, 404, status, m)
}

func TestManagedNewsRealLocalTTS(t *testing.T) {
	if os.Getenv("TN_MANAGED_REAL_TTS") != "1" {
		t.Skip("opt-in real loopback TTS validation")
	}
	s := testServer(t)
	admin := adminToken(t, s)
	status, m := call(t, s, "PUT", "/v1/admin/managed/config", admin, Object{"driving_enabled": false, "local_tts_url": "http://127.0.0.1:8092", "tts_voice": "Mai Anh"})
	requireStatus(t, 200, status, m)
	news := Object{"title": "TN — bài đọc do chủ dự án tự tạo", "text": "Xin chào, tôi là TN. Đây là nội dung tự tạo để kiểm tra bản tin và giọng đọc tiếng Việt trên máy của bạn.", "source": "Owner-authored local integration test", "license": "Owner permits local test synthesis and playback", "source_updated_at": "2026-10-09T00:00:00Z", "rights_confirmed": true, "published": true}
	status, m = call(t, s, "POST", "/v1/admin/managed/news", admin, news)
	requireStatus(t, 200, status, m)
	id := str(m, "id")
	started := time.Now()
	status, m = call(t, s, "POST", "/v1/admin/managed/news/"+id+"/audio", admin, Object{})
	requireStatus(t, 202, status, m)
	deadline := time.Now().Add(150 * time.Second)
	for time.Now().Before(deadline) {
		status, m = call(t, s, "GET", "/v1/admin/managed/news/"+id+"/job", admin, nil)
		requireStatus(t, 200, status, m)
		if str(m, "status") == "ready" || str(m, "status") == "failed" {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if str(m, "status") != "ready" {
		t.Fatal("real local TTS did not generate audio", m)
	}
	row, e := s.record("managed_news", id)
	if e != nil {
		t.Fatal(e)
	}
	raw, e := os.ReadFile(filepath.Join(filepath.Dir(s.cfg.DBPath), "managed-audio", str(row, "audio_id")+".wav"))
	if e != nil {
		t.Fatal(e)
	}
	if len(raw) < 48000 {
		t.Fatal("real TTS audio implausibly short")
	}
	report := filepath.Join("..", "reports", "tn-managed-data-20261009")
	if e = os.MkdirAll(report, 0700); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(report, "tn-owned-news.wav"), raw, 0600); e != nil {
		t.Fatal(e)
	}
	proof := Object{"real_local_tts": true, "provider": "VieNeu-TTS-v3-Turbo", "voice": "Mai Anh", "news_source": news["source"], "license": news["license"], "input_text": news["text"], "spoken_text": managedSpokenText(str(news, "text")), "elapsed_seconds": time.Since(started).Seconds(), "audio_bytes": len(raw), "sha256": hash(string(raw)), "completed_chunks": m["completed_chunks"], "total_chunks": m["total_chunks"], "tested_at": now(), "credentials_included": false}
	if e = os.WriteFile(filepath.Join(report, "local-tts-proof.json"), []byte(jsonText(proof)), 0600); e != nil {
		t.Fatal(e)
	}
}
func TestManagedWAVAndLocalOriginGuards(t *testing.T) {
	a := managedFixtureWAV()
	merged, e := managedMergeWAV([][]byte{a, a})
	if e != nil || len(merged) != 44+960 || binary.LittleEndian.Uint32(merged[40:44]) != 960 {
		t.Fatal("invalid merged WAV", e)
	}
	if _, e := managedMergeWAV([][]byte{[]byte("not a WAV")}); e == nil {
		t.Fatal("accepted malformed audio")
	}
	if speed := managedSpokenText("TN chào bạn. BTN là mã."); speed != "Ti En chào bạn. BTN là mã." {
		t.Fatal(speed)
	}
}
