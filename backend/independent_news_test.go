package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func independentNewsTestServer(t *testing.T) *Server {
	t.Helper()
	s := testServer(t)
	s.cfg.RuntimeMode = "independent"
	if err := s.initializeIndependentRuntime(); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestIndependentNewsJobRecoversAndUsesLocalWAVWorker(t *testing.T) {
	s := independentNewsTestServer(t)
	var requests atomic.Int32
	wav := managedFixtureWAV()
	worker := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/synthesize" || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		var input Object
		if json.NewDecoder(r.Body).Decode(&input) != nil {
			t.Error("worker request was not valid JSON")
		}
		if str(input, "voice") != "Thùy Dung" || !strings.Contains(str(input, "text"), "Ti En") {
			t.Errorf("expected TN-normalized text and pinned voice, got %#v", input)
		}
		requests.Add(1)
		w.Header().Set("Content-Type", "audio/wav")
		w.Write(wav)
	}))
	defer worker.Close()

	newsID, jobID := ident(), ident()
	news := Object{"id": newsID, "title": "TN owned news", "text": "TN chào bạn.", "source": "owner-authored", "license": "owner permission", "source_updated_at": now(), "rights_confirmed": true, "published": true, "revision": 1, "job_id": jobID}
	job := Object{"id": jobID, "news_id": newsID, "revision": 1, "status": "running", "completed_chunks": 0, "total_chunks": 1}
	if err := s.put("managed_news", newsID, "", news); err != nil {
		t.Fatal(err)
	}
	if err := s.put("managed_news_job", jobID, "", job); err != nil {
		t.Fatal(err)
	}
	if err := s.resumeManagedNewsJobsWithConfig(RuntimeConfig{TTSURL: worker.URL, TTSVoice: "Thùy Dung"}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		latest, err := s.record("managed_news_job", jobID)
		if err != nil {
			t.Fatal(err)
		}
		if str(latest, "status") == "ready" {
			break
		}
		if str(latest, "status") == "failed" {
			t.Fatalf("recovered job failed: %#v", latest)
		}
		time.Sleep(10 * time.Millisecond)
	}
	latest, err := s.record("managed_news_job", jobID)
	if err != nil || str(latest, "status") != "ready" || requests.Load() != 1 {
		t.Fatalf("job did not recover: %#v, err=%v, requests=%d", latest, err, requests.Load())
	}
	item, err := s.record("managed_news", newsID)
	if err != nil || str(item, "audio_id") != jobID {
		t.Fatalf("recovered audio was not published: %#v, err=%v", item, err)
	}
	path := filepath.Join(filepath.Dir(s.cfg.DBPath), "managed-audio", jobID+".wav")
	if info, statErr := os.Stat(path); statErr != nil || info.Size() == 0 {
		t.Fatalf("recovered audio file missing or empty: info=%v err=%v", info, statErr)
	}
}

func TestIndependentNewsIsDisabledUntilAdminEnablesIt(t *testing.T) {
	s := independentNewsTestServer(t)
	_, err := s.managedNewsDirective(&Principal{User: Object{"id": ident()}})
	if tnAIErrorCode(err) != "MANAGED_NEWS_DISABLED" {
		t.Fatalf("expected independent runtime gate before premium checks, got %v", err)
	}
}
