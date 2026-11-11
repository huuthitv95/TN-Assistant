package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

func speechWorkerRequest(ctx context.Context, endpoint, path, contentType string, payload []byte, limit int64) ([]byte, string, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint+path, bytes.NewReader(payload))
	if err != nil {
		return nil, "", problem(503, "SPEECH_CONFIG_INVALID", "Speech worker endpoint is invalid")
	}
	request.Header.Set("Content-Type", contentType)
	request.Header.Set("Accept", "application/json, audio/wav")
	client := &http.Client{Timeout: 45 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, "", problem(503, "SPEECH_WORKER_UNAVAILABLE", "Local speech worker did not complete the request")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		switch response.StatusCode {
		case http.StatusTooManyRequests:
			return nil, "", problem(503, "SPEECH_BUSY", "Local speech capacity is busy; retry shortly")
		case http.StatusGatewayTimeout:
			return nil, "", problem(504, "SPEECH_TIMEOUT", "Local speech request exceeded its time limit")
		case http.StatusBadRequest, http.StatusUnsupportedMediaType, http.StatusRequestEntityTooLarge:
			return nil, "", problem(400, "SPEECH_INVALID_INPUT", "Speech input does not match the supported format")
		default:
			return nil, "", problem(503, "SPEECH_WORKER_UNAVAILABLE", "Local speech worker is not ready")
		}
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
	if err != nil || int64(len(raw)) > limit {
		return nil, "", problem(502, "SPEECH_RESPONSE_INVALID", "Local speech worker returned an invalid response")
	}
	return raw, response.Header.Get("Content-Type"), nil
}

func localSpeechDecode(ctx context.Context, endpoint, contentType string, audio []byte) (Object, error) {
	raw, _, err := speechWorkerRequest(ctx, endpoint, "/recognize", contentType, audio, 1<<20)
	if err != nil {
		return nil, err
	}
	var result Object
	if json.Unmarshal(raw, &result) != nil {
		return nil, problem(502, "SPEECH_RESPONSE_INVALID", "ASR worker returned invalid JSON")
	}
	if _, ok := result["text"].(string); !ok {
		return nil, problem(502, "SPEECH_RESPONSE_INVALID", "ASR worker response omitted recognized text")
	}
	return result, nil
}
