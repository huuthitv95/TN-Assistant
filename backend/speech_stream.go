package main

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/coder/websocket"
)

type localSpeechFrame struct {
	typ  websocket.MessageType
	data []byte
	err  error
}

type localSpeechResult struct {
	text string
	err  error
}

func localSpeechError(write func(Object) error, code, message string) {
	_ = write(Object{"type": "error", "error": Object{"code": code, "message": message}})
}

// localSpeechStream re-runs the pinned local recognizer on bounded cumulative
// audio windows. Every partial/final text is a model result, never a fabricated
// placeholder. Audio remains in memory and is discarded at the end of the call.
func (s *Server) localSpeechStream(ctx context.Context, down *websocket.Conn, write func(Object) error, endpoint string) error {
	typ, raw, err := down.Read(ctx)
	if err != nil {
		return nil
	}
	var start Object
	if typ != websocket.MessageText || json.Unmarshal(raw, &start) != nil || str(start, "type") != "start" || integer(start, "sample_rate", 0) != 16000 || integer(start, "channels", 0) != 1 || str(start, "encoding") != "pcm_s16le" {
		localSpeechError(write, "INVALID_START", "16000 Hz mono PCM16 start frame required")
		return nil
	}
	if err = write(Object{"type": "ready"}); err != nil {
		return nil
	}

	readCtx, stopRead := context.WithCancel(ctx)
	defer stopRead()
	frames := make(chan localSpeechFrame, 2)
	go func() {
		for {
			frameType, data, readErr := down.Read(readCtx)
			frame := localSpeechFrame{typ: frameType, data: data, err: readErr}
			select {
			case frames <- frame:
			case <-readCtx.Done():
				return
			}
			if readErr != nil {
				return
			}
		}
	}()

	const maxAudio = 480000       // 15 seconds of PCM16 mono at 16 kHz.
	const partialInterval = 64000 // Recheck after each additional 2 seconds.
	audio := make([]byte, 0, maxAudio)
	lastPartialBytes := 0
	lastPartial := ""
	var pending <-chan localSpeechResult
	var cancelPending context.CancelFunc
	pendingFinal := false
	ending := false
	requestID := ident()

	launch := func(final bool) {
		snapshot := append([]byte(nil), audio...)
		requestCtx, cancel := context.WithCancel(ctx)
		pendingFinal, cancelPending = final, cancel
		result := make(chan localSpeechResult, 1)
		pending = result
		go func() {
			recognized, requestErr := localSpeechDecode(requestCtx, endpoint, "audio/pcm", snapshot)
			text := str(recognized, "text")
			select {
			case result <- localSpeechResult{text: text, err: requestErr}:
			case <-requestCtx.Done():
			}
		}()
	}

	for {
		if pending == nil && ending {
			if len(audio) == 0 {
				localSpeechError(write, "EMPTY_AUDIO", "No audio was received")
				return nil
			}
			launch(true)
		}
		select {
		case <-ctx.Done():
			if cancelPending != nil {
				cancelPending()
			}
			return nil
		case frame := <-frames:
			if frame.err != nil {
				if !errors.Is(frame.err, context.Canceled) && ctx.Err() == nil {
					if pending != nil && cancelPending != nil {
						cancelPending()
					}
					return nil
				}
				return nil
			}
			if ending {
				if pending != nil && cancelPending != nil {
					cancelPending()
				}
				localSpeechError(write, "FRAME_AFTER_END", "Audio frames cannot follow the end frame")
				return nil
			}
			if frame.typ == websocket.MessageBinary {
				if len(frame.data) == 0 || len(frame.data)%2 != 0 || len(audio)+len(frame.data) > maxAudio {
					if pending != nil && cancelPending != nil {
						cancelPending()
					}
					localSpeechError(write, "AUDIO_LIMIT", "PCM audio must be aligned and no longer than 15 seconds")
					return nil
				}
				audio = append(audio, frame.data...)
				if pending == nil && len(audio)-lastPartialBytes >= partialInterval {
					launch(false)
				}
				continue
			}
			var command Object
			if frame.typ != websocket.MessageText || json.Unmarshal(frame.data, &command) != nil {
				localSpeechError(write, "INVALID_CONTROL", "Expected end or cancel control frame")
				return nil
			}
			switch str(command, "type") {
			case "cancel":
				if cancelPending != nil {
					cancelPending()
				}
				return nil
			case "end":
				ending = true
				if pending == nil {
					if len(audio) == 0 {
						localSpeechError(write, "EMPTY_AUDIO", "No audio was received")
						return nil
					}
					launch(true)
				}
			default:
				localSpeechError(write, "INVALID_CONTROL", "Expected end or cancel control frame")
				return nil
			}
		case result := <-pending:
			if cancelPending != nil {
				cancelPending()
			}
			pending, cancelPending = nil, nil
			if result.err != nil {
				localSpeechError(write, "ASR_UNAVAILABLE", "TN speech recognition did not complete")
				return nil
			}
			if pendingFinal {
				if result.text == "" {
					localSpeechError(write, "NO_SPEECH", "No speech was recognized")
					return nil
				}
				_ = write(Object{"type": "final", "text": result.text, "request_id": requestID, "source": "tn_asr"})
				return nil
			}
			lastPartialBytes = len(audio)
			if !ending && result.text != "" && result.text != lastPartial {
				lastPartial = result.text
				if err = write(Object{"type": "partial", "text": result.text, "request_id": requestID, "source": "tn_asr"}); err != nil {
					return nil
				}
			}
		}
	}
}
