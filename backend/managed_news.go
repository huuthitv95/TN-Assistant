package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode"
)

var managedTTSLock sync.Mutex
var managedNewsLocks sync.Map
var managedActiveJobs sync.Map
var managedTN = regexp.MustCompile(`\bTN\b`)

func managedSpokenText(text string) string { return managedTN.ReplaceAllString(text, "Ti En") }
func managedChunks(text string) []string {
	runes := []rune(managedSpokenText(text))
	chunks := []string{}
	for len(runes) > 0 {
		n := len(runes)
		if n > 450 {
			n = 450
			for k := n; k > 200; k-- {
				if unicode.IsSpace(runes[k-1]) {
					n = k
					break
				}
			}
		}
		part := strings.TrimSpace(string(runes[:n]))
		if part != "" {
			chunks = append(chunks, part)
		}
		runes = runes[n:]
	}
	return chunks
}
func validateManagedNews(m Object) error {
	if len(strings.TrimSpace(str(m, "title"))) == 0 || len([]rune(str(m, "title"))) > 200 || len(strings.TrimSpace(str(m, "text"))) == 0 || len([]rune(str(m, "text"))) > 20000 {
		return problem(400, "INVALID_NEWS", "title (1-200) and text (1-20000 characters) required")
	}
	if m["rights_confirmed"] != true {
		return problem(400, "NEWS_RIGHTS_REQUIRED", "Confirm ownership or permission to publish and create speech")
	}
	if _, ok := m["published"].(bool); !ok {
		return problem(400, "INVALID_NEWS", "published must be boolean")
	}
	return managedSource(m)
}

func (s *Server) managedNewsAdmin(w http.ResponseWriter, r *http.Request, p *Principal, parts []string) error {
	if r.Method == "GET" && len(parts) == 0 {
		items, e := s.list("managed_news", "", "")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": items})
		return nil
	}
	if len(parts) == 2 && (parts[1] == "audio" || parts[1] == "job") {
		row, e := s.record("managed_news", parts[0])
		if e != nil {
			return e
		}
		if parts[1] == "job" && r.Method == "GET" {
			job, e := s.record("managed_news_job", str(row, "job_id"))
			if e != nil {
				return e
			}
			send(w, 200, job)
			return nil
		}
		if parts[1] == "audio" && r.Method == "POST" {
			lockKey := fmt.Sprintf("%p:%s", s, parts[0])
			mutex, _ := managedNewsLocks.LoadOrStore(lockKey, &sync.Mutex{})
			mutex.(*sync.Mutex).Lock()
			defer mutex.(*sync.Mutex).Unlock()
			row, e = s.record("managed_news", parts[0])
			if e != nil {
				return e
			}
			if e := validateManagedNews(row); e != nil {
				return e
			}
			if job, e := s.record("managed_news_job", str(row, "job_id")); e == nil && (str(job, "status") == "queued" || str(job, "status") == "running") {
				// Process-local ownership distinguishes queued/running work from a
				// persisted job interrupted by a previous server process.
				if _, active := managedActiveJobs.Load(str(job, "id")); active {
					send(w, 202, job)
					return nil
				}
				job["status"] = "failed"
				job["error_code"] = "WORKER_INTERRUPTED"
				s.put("managed_news_job", str(job, "id"), "", job)
			}
			id := ident()
			chunks := managedChunks(str(row, "text"))
			job := Object{"news_id": parts[0], "revision": row["revision"], "status": "queued", "completed_chunks": 0, "total_chunks": len(chunks), "created_at": now(), "updated_at": now()}
			job["id"] = id
			previous := jsonText(row)
			row["job_id"] = id
			tx, e := s.db.Begin()
			if e != nil {
				return e
			}
			defer tx.Rollback()
			updated, e := tx.Exec("UPDATE records SET data=? WHERE kind='managed_news' AND id=? AND data=?", jsonText(row), parts[0], previous)
			if e != nil {
				return e
			}
			changed, e := updated.RowsAffected()
			if e != nil {
				return e
			}
			if changed != 1 {
				return problem(409, "NEWS_CHANGED", "News changed; reload before generating audio")
			}
			if _, e = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('managed_news_job',?,'',?,?)", id, jsonText(job), now()); e != nil {
				return e
			}
			if e = tx.Commit(); e != nil {
				return e
			}
			managedActiveJobs.Store(id, true)
			jobConfig := s.managedConfig()
			if s.independentRuntime() {
				runtimeConfig, configErr := s.runtimeConfig()
				if configErr != nil {
					managedActiveJobs.Delete(id)
					job["status"] = "failed"
					job["error_code"] = "RUNTIME_CONFIG_UNAVAILABLE"
					job["updated_at"] = now()
					_ = s.put("managed_news_job", id, "", job)
					return problem(503, "RUNTIME_CONFIG_UNAVAILABLE", "Independent runtime configuration is unavailable")
				}
				jobConfig = Object{"local_tts_url": runtimeConfig.TTSURL, "tts_voice": runtimeConfig.TTSVoice}
			}
			go s.runManagedNewsJob(id, row, jobConfig, chunks)
			send(w, 202, job)
			return nil
		}
		return problem(405, "METHOD_NOT_ALLOWED", "POST audio or GET job required")
	}
	if len(parts) == 1 && r.Method == "GET" {
		row, e := s.record("managed_news", parts[0])
		if e != nil {
			return e
		}
		send(w, 200, row)
		return nil
	}
	if len(parts) == 1 && r.Method == "DELETE" {
		_, e := s.db.Exec("DELETE FROM records WHERE kind='managed_news' AND id=?", parts[0])
		if e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
		return nil
	}
	if (r.Method == "POST" && len(parts) == 0) || (r.Method == "PUT" && len(parts) == 1) {
		m, e := body(r)
		if e != nil {
			return e
		}
		if e := validateManagedNews(m); e != nil {
			return e
		}
		id := ident()
		revision := 1
		if len(parts) == 1 {
			id = parts[0]
			old, e := s.record("managed_news", id)
			if e != nil {
				return e
			}
			revision = integer(old, "revision", 0) + 1
		}
		clean := Object{"title": str(m, "title"), "text": str(m, "text"), "source": str(m, "source"), "license": str(m, "license"), "source_updated_at": str(m, "source_updated_at"), "rights_confirmed": true, "published": m["published"], "revision": revision, "updated_at": now()}
		if e := s.put("managed_news", id, "", clean); e != nil {
			return e
		}
		send(w, 200, clean)
		return nil
	}
	return problem(405, "METHOD_NOT_ALLOWED", "Unsupported news method")
}

// resumeManagedNewsJobs restarts the latest persisted job for each news item.
// An interrupted job is regenerated from TN-owned text and is only published
// after the existing revision and job ownership checks pass.
func (s *Server) resumeManagedNewsJobs() error {
	if !s.independentRuntime() {
		return nil
	}
	cfg, err := s.runtimeConfig()
	if err != nil {
		return err
	}
	return s.resumeManagedNewsJobsWithConfig(cfg)
}

func (s *Server) resumeManagedNewsJobsWithConfig(cfg RuntimeConfig) error {
	rows, err := s.db.Query("SELECT data FROM records WHERE kind='managed_news_job'")
	if err != nil {
		return err
	}
	type pendingJob struct {
		id  string
		job Object
	}
	pending := []pendingJob{}
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			rows.Close()
			return err
		}
		var job Object
		if json.Unmarshal([]byte(raw), &job) == nil {
			status := str(job, "status")
			if status == "queued" || status == "running" {
				pending = append(pending, pendingJob{id: str(job, "id"), job: job})
			}
		}
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return err
	}
	if err = rows.Close(); err != nil {
		return err
	}
	for _, item := range pending {
		job := item.job
		news, readErr := s.record("managed_news", str(job, "news_id"))
		if readErr != nil || str(news, "job_id") != item.id || integer(news, "revision", 0) != integer(job, "revision", -1) {
			job["status"] = "superseded"
			job["error_code"] = "NEWS_CHANGED"
			job["updated_at"] = now()
			if err = s.put("managed_news_job", item.id, "", job); err != nil {
				return err
			}
			continue
		}
		attempt := integer(job, "attempt", 0) + 1
		if attempt > 3 {
			job["status"] = "failed"
			job["error_code"] = "RETRY_LIMIT"
			job["updated_at"] = now()
			if err = s.put("managed_news_job", item.id, "", job); err != nil {
				return err
			}
			continue
		}
		job["status"] = "queued"
		job["attempt"] = attempt
		job["completed_chunks"] = 0
		job["recovered_at"] = now()
		job["updated_at"] = now()
		if err = s.put("managed_news_job", item.id, "", job); err != nil {
			return err
		}
		managedActiveJobs.Store(item.id, true)
		jobConfig := Object{"local_tts_url": cfg.TTSURL, "tts_voice": cfg.TTSVoice}
		go s.runManagedNewsJob(item.id, news, jobConfig, managedChunks(str(news, "text")))
	}
	return nil
}

func (s *Server) managedPublicNews(w http.ResponseWriter, r *http.Request, p *Principal) error {
	if r.URL.Path == "/v1/news/managed" && r.Method == "GET" {
		rows, e := s.list("managed_news", "", "")
		if e != nil {
			return e
		}
		items := []Object{}
		for _, row := range rows {
			if row["published"] == true {
				items = append(items, Object{"id": row["id"], "title": row["title"], "source": row["source"], "license": row["license"], "source_updated_at": row["source_updated_at"], "audio_ready": str(row, "audio_id") != ""})
			}
		}
		send(w, 200, Object{"items": items})
		return nil
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/news/managed/"), "/")
	if len(parts) != 2 {
		return problem(404, "NOT_FOUND", "Unknown news route")
	}
	row, e := s.record("managed_news", parts[0])
	if e != nil {
		return e
	}
	if row["published"] != true {
		return problem(404, "NOT_FOUND", "News is not published")
	}
	audioID := str(row, "audio_id")
	if audioID == "" {
		return problem(409, "NEWS_AUDIO_NOT_READY", "Audio has not finished generating")
	}
	if parts[1] == "play" && r.Method == "POST" {
		send(w, 200, Object{"directives": []Object{{"type": "PlayerPodcast", "payload": Object{"link": strings.TrimRight(s.cfg.PublicURL, "/") + "/v1/news/managed/" + parts[0] + "/audio", "action_id": ident(), "title": row["title"]}}}, "source": row["source"], "license": row["license"]})
		return nil
	}
	if parts[1] == "audio" && (r.Method == "GET" || r.Method == "HEAD") {
		if !regexp.MustCompile(`^[0-9a-f]{48}$`).MatchString(audioID) {
			return problem(500, "NEWS_AUDIO_INVALID", "Invalid generated audio id")
		}
		mime, extension := managedAudioType(str(row, "audio_mime_type"))
		if extension == "" {
			return problem(500, "NEWS_AUDIO_INVALID", "Unsupported generated audio format")
		}
		path := filepath.Join(filepath.Dir(s.cfg.DBPath), "managed-audio", audioID+extension)
		f, e := os.Open(path)
		if e != nil {
			return problem(410, "NEWS_AUDIO_MISSING", "Regenerate missing audio")
		}
		defer f.Close()
		st, e := f.Stat()
		if e != nil {
			return e
		}
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Cache-Control", "private, no-store")
		http.ServeContent(w, r, "news"+extension, st.ModTime(), f)
		return nil
	}
	return problem(405, "METHOD_NOT_ALLOWED", "Unsupported news method")
}

// managedNewsDirective is shared by the explicit news endpoint and the voice
// intent router. It never generates news or republishes third-party articles.
func (s *Server) managedNewsDirective(p *Principal) (Object, error) {
	if s.independentRuntime() {
		cfg, err := s.runtimeConfig()
		if err != nil {
			return nil, err
		}
		if !cfg.NewsEnabled {
			return nil, problem(503, "MANAGED_NEWS_DISABLED", "TN managed news is disabled")
		}
	}
	if s.premiumFeatures(str(p.User, "id"))["ai_news"] != true {
		return nil, problem(403, "FEATURE_NOT_AVAILABLE", "Premium news required")
	}
	rows, e := s.list("managed_news", "", "")
	if e != nil {
		return nil, e
	}
	for _, row := range rows {
		if row["published"] != true || str(row, "audio_id") == "" {
			continue
		}
		return Object{"utterance": "Phát bản tin đã được cấp phép", "directives": []Object{{"type": "PlayerPodcast", "payload": Object{"link": strings.TrimRight(s.cfg.PublicURL, "/") + "/v1/news/managed/" + str(row, "id") + "/audio", "action_id": ident(), "title": str(row, "title")}}}, "provider": "managed_news", "source": row["source"], "license": row["license"]}, nil
	}
	return nil, problem(503, "MANAGED_NEWS_NOT_READY", "Chưa có bản tin được xuất bản và tạo audio trong quản trị")
}

func managedLocalRequest(ctx context.Context, origin, method, path string, data any) ([]byte, error) {
	base, e := url.Parse(origin)
	if e != nil || base.Scheme != "http" || (base.Hostname() != "127.0.0.1" && base.Hostname() != "localhost" && base.Hostname() != "::1") || base.User != nil {
		return nil, fmt.Errorf("invalid local origin")
	}
	relative, e := url.Parse(path)
	if e != nil || relative.IsAbs() || relative.Host != "" || !strings.HasPrefix(relative.Path, "/") {
		return nil, fmt.Errorf("invalid local audio path")
	}
	endpoint := base.ResolveReference(relative)
	var body io.Reader
	if data != nil {
		body = strings.NewReader(jsonText(data))
	}
	req, e := http.NewRequestWithContext(ctx, method, endpoint.String(), body)
	if e != nil {
		return nil, e
	}
	if data != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := &http.Client{Timeout: 150 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, e := client.Do(req)
	if e != nil {
		return nil, e
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("local TTS HTTP %d", response.StatusCode)
	}
	raw, e := io.ReadAll(io.LimitReader(response.Body, (32<<20)+1))
	if e != nil || len(raw) > 32<<20 {
		return nil, fmt.Errorf("audio size exceeds limit")
	}
	return raw, nil
}

// Merge PCM WAV chunks by parsing RIFF, rather than concatenating complete WAV
// files (which silently truncates most Android players at the first chunk).
func managedMergeWAV(parts [][]byte) ([]byte, error) {
	var format, pcm []byte
	for _, raw := range parts {
		if len(raw) < 12 || string(raw[:4]) != "RIFF" || string(raw[8:12]) != "WAVE" {
			return nil, fmt.Errorf("invalid WAV")
		}
		var f, d []byte
		for at := 12; at+8 <= len(raw); {
			size := int(binary.LittleEndian.Uint32(raw[at+4 : at+8]))
			end := at + 8 + size
			if size < 0 || end > len(raw) {
				return nil, fmt.Errorf("invalid WAV chunk")
			}
			switch string(raw[at : at+4]) {
			case "fmt ":
				f = raw[at+8 : end]
			case "data":
				d = raw[at+8 : end]
			}
			at = end + size%2
		}
		if len(f) < 16 || binary.LittleEndian.Uint16(f[:2]) != 1 || len(d) == 0 {
			return nil, fmt.Errorf("PCM WAV required")
		}
		if format == nil {
			format = append([]byte{}, f...)
		} else if !bytes.Equal(format, f) {
			return nil, fmt.Errorf("WAV formats differ")
		}
		pcm = append(pcm, d...)
		if len(pcm) > 128<<20 {
			return nil, fmt.Errorf("combined WAV too large")
		}
	}
	if len(pcm) == 0 {
		return nil, fmt.Errorf("no audio chunks")
	}
	out := new(bytes.Buffer)
	out.WriteString("RIFF")
	binary.Write(out, binary.LittleEndian, uint32(4+8+len(format)+len(format)%2+8+len(pcm)))
	out.WriteString("WAVEfmt ")
	binary.Write(out, binary.LittleEndian, uint32(len(format)))
	out.Write(format)
	if len(format)%2 != 0 {
		out.WriteByte(0)
	}
	out.WriteString("data")
	binary.Write(out, binary.LittleEndian, uint32(len(pcm)))
	out.Write(pcm)
	return out.Bytes(), nil
}

// Old articles without a MIME field retain their original WAV storage path.
func managedAudioType(mime string) (string, string) {
	switch mime {
	case "", "audio/wav":
		return "audio/wav", ".wav"
	case "audio/mpeg":
		return mime, ".mp3"
	default:
		return "", ""
	}
}

func managedMergeAudio(parts [][]byte, mime string) ([]byte, error) {
	if mime == "audio/wav" {
		return managedMergeWAV(parts)
	}
	if mime != "audio/mpeg" || len(parts) == 0 {
		return nil, fmt.Errorf("unsupported generated audio format")
	}
	total := 0
	for _, part := range parts {
		total += len(part)
		if total > 64<<20 || !originalTTSValidMP3(part) {
			return nil, fmt.Errorf("invalid generated MP3 audio")
		}
	}
	return bytes.Join(parts, nil), nil
}

func (s *Server) runManagedNewsJob(id string, news, cfg Object, chunks []string) {
	defer managedActiveJobs.Delete(id)
	// Serialize editorial jobs independently of interactive speech requests.
	managedTTSLock.Lock()
	defer managedTTSLock.Unlock()
	job, e := s.record("managed_news_job", id)
	if e != nil {
		return
	}
	job["status"] = "running"
	job["updated_at"] = now()
	if s.put("managed_news_job", id, "", job) != nil {
		return
	}
	failure := func(code string) {
		job["status"] = "failed"
		job["error_code"] = code
		job["updated_at"] = now()
		s.put("managed_news_job", id, "", job)
	}
	// A bounded total deadline prevents an orphaned news job consuming resources
	// forever. Provider errors are represented by stable codes, not secret logs.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
	defer cancel()
	audio := [][]byte{}
	audioMime := "audio/wav"
	totalBytes := 0
	for index, chunk := range chunks {
		current, e := s.record("managed_news", str(news, "id"))
		if e != nil || integer(current, "revision", 0) != integer(news, "revision", 0) || str(current, "job_id") != id {
			failure("NEWS_CHANGED")
			return
		}
		var data []byte
		mime := "audio/wav"
		if s.independentRuntime() {
			voice := str(cfg, "tts_voice")
			if voice == "" {
				voice = "Thùy Dung"
			}
			data, mime, e = s.ttsSpeech(ctx, chunk, str(cfg, "local_tts_url"), voice)
		} else {
			var raw []byte
			raw, e = managedLocalRequest(ctx, str(cfg, "local_tts_url"), "POST", "/api/synthesize", Object{"text": chunk, "voice": str(cfg, "tts_voice")})
			if e == nil {
				var reply Object
				if json.Unmarshal(raw, &reply) != nil {
					e = fmt.Errorf("invalid local TTS reply")
				} else {
					data, e = managedLocalRequest(ctx, str(cfg, "local_tts_url"), "GET", str(reply, "url"), nil)
				}
			}
		}
		if e != nil {
			failure("TTS_UNAVAILABLE")
			return
		}
		if index == 0 {
			audioMime = mime
		}
		totalBytes += len(data)
		if mime != audioMime || totalBytes > 64<<20 {
			failure("TTS_INVALID_AUDIO")
			return
		}
		audio = append(audio, data)
		job["completed_chunks"] = index + 1
		job["updated_at"] = now()
		if s.put("managed_news_job", id, "", job) != nil {
			return
		}
	}
	combined, e := managedMergeAudio(audio, audioMime)
	if e != nil {
		failure("TTS_INVALID_AUDIO")
		return
	}
	dir := filepath.Join(filepath.Dir(s.cfg.DBPath), "managed-audio")
	if os.MkdirAll(dir, 0700) != nil {
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	temp := filepath.Join(dir, id+".partial")
	_, extension := managedAudioType(audioMime)
	path := filepath.Join(dir, id+extension)
	if os.WriteFile(temp, combined, 0600) != nil {
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	if os.Rename(temp, path) != nil {
		os.Remove(temp)
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	// Revision and job ownership prevent a late worker from publishing obsolete
	// content after an edit, replacement job or deletion.
	tx, e := s.db.Begin()
	if e != nil {
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	var text string
	e = tx.QueryRow("SELECT data FROM records WHERE kind='managed_news' AND id=?", str(news, "id")).Scan(&text)
	var latest Object
	if e != nil || json.Unmarshal([]byte(text), &latest) != nil || integer(latest, "revision", 0) != integer(news, "revision", 0) || str(latest, "job_id") != id {
		tx.Rollback()
		os.Remove(path)
		failure("NEWS_CHANGED")
		return
	}
	latest["audio_id"] = id
	latest["audio_mime_type"] = audioMime
	latest["audio_generated_at"] = now()
	_, e = tx.Exec("UPDATE records SET data=? WHERE kind='managed_news' AND id=?", jsonText(latest), str(news, "id"))
	if e != nil {
		tx.Rollback()
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	if tx.Commit() != nil {
		failure("AUDIO_STORAGE_FAILED")
		return
	}
	job["status"] = "ready"
	job["audio_bytes"] = len(combined)
	job["updated_at"] = now()
	s.put("managed_news_job", id, "", job)
}
