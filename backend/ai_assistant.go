package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// Request reservation is durable and scoped to the authenticated device. A
// disconnected client cannot turn a retry into a second device action.
func (s *Server) assistant(w http.ResponseWriter, r *http.Request, p *Principal) error {
	m, e := body(r)
	if e != nil {
		return e
	}
	text := strings.TrimSpace(str(m, "text"))
	if text == "" || len(text) > 16000 {
		return problem(400, "INVALID_TEXT", "text must contain 1..16000 bytes")
	}
	uid, did := str(p.User, "id"), str(p.Device, "id")
	// This is server-owned state; a request body cannot turn privacy off.
	requestPrivate := s.aiSettings(uid)["private_mode"] == true
	m["_tn_private_mode"] = requestPrivate
	if did == "" {
		did = "web"
	}
	mode := str(m, "mode")
	if mode == "" {
		mode = str(s.aiSettings(uid), "ai_mode")
		if !s.aiPremium(uid) {
			mode = "commands"
		}
	}
	if mode == "" {
		mode = s.defaultAIMode(uid)
	}
	if mode != "commands" && mode != "qa" && mode != "tools" {
		return problem(400, "INVALID_AI_MODE", "Unknown assistant mode")
	}
	if mode != "commands" && !s.aiPremium(uid) {
		return problem(403, "FEATURE_NOT_AVAILABLE", "Premium required for AI")
	}
	if mode == "tools" && s.premiumFeatures(uid)["ai_tools"] != true {
		return problem(403, "FEATURE_NOT_AVAILABLE", "AI tools are not included in this account")
	}
	requestID := str(m, "request_id")
	if requestID == "" {
		requestID = ident()
	}
	if !aiIdentifier.MatchString(requestID) {
		return problem(400, "INVALID_REQUEST_ID", "Invalid request id")
	}
	conversation := str(m, "conversation_id")
	if conversation == "" {
		conversation = str(m, "session_id")
	}
	if conversation == "" {
		conversation = hash(uid + "\x00" + did + "\x00" + requestID)[:24]
	}
	if !aiIdentifier.MatchString(conversation) {
		return problem(400, "INVALID_CONVERSATION_ID", "Invalid conversation id")
	}
	// Never trust a request body's device identifier for context isolation.
	scope := hash(uid + "\x00" + did)
	key := scope + ":" + requestID
	fingerprintInput := Object{"text": text, "mode": mode, "conversation_id": conversation, "confirmation_token": str(m, "confirmation_token")}
	if timezone, provided := m["timezone"]; provided {
		fingerprintInput["timezone"] = timezone
	}
	if capabilities, provided := m["client_capabilities"]; provided {
		fingerprintInput["client_capabilities"] = capabilities
	}
	fingerprint := hash(jsonText(fingerprintInput))
	previous, e := s.record("assistant_request", key)
	if e == nil {
		if str(previous, "fingerprint") != fingerprint {
			return problem(409, "REQUEST_ID_CONFLICT", "Request id already used for different input")
		}
		if result, ok := previous["result"].(map[string]any); ok {
			result["replayed"] = true
			send(w, 200, result)
			return nil
		}
		return problem(409, "REQUEST_IN_PROGRESS", "Request is pending or interrupted; do not repeat its actions")
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	reservation := Object{"fingerprint": fingerprint, "created_at": now(), "private_mode": requestPrivate}
	inserted, e := s.db.Exec("INSERT OR IGNORE INTO records(kind,id,owner,data,created_at) VALUES('assistant_request',?,?,?,?)", key, uid, jsonText(reservation), now())
	if e != nil {
		return e
	}
	n, _ := inserted.RowsAffected()
	if n != 1 {
		return problem(409, "REQUEST_IN_PROGRESS", "Request already reserved")
	}
	// Bounded processing includes the command engine and all cloud attempts.
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	r = r.WithContext(ctx)
	m["session_id"] = conversation
	out, e := s.assistantResult(r, p, m, mode, scope, conversation)
	if e != nil {
		// Keep a terminal reservation after ambiguous upstream failure. A replay
		// must never run the same possibly actionable request a second time.
		reservation["status"] = "failed"
		s.put("assistant_request", key, uid, reservation)
		return e
	}
	out["request_id"] = requestID
	out["conversation_id"] = conversation
	out["session_id"] = conversation
	out["text"] = text
	out["replayed"] = false
	if s.independentRuntime() {
		cfg, err := s.runtimeConfig()
		if err != nil {
			return err
		}
		reservation["runtime_generation"] = cfg.Generation
		issued := []string{}
		for _, directive := range objectDirectives(out) {
			actionID := ident()
			issued = append(issued, actionID)
			directive["action_id"] = actionID
			if payload, ok := directive["payload"].(map[string]any); ok {
				payload["action_id"] = actionID
			}
		}
		// Retain opaque authorization IDs independently from answer bodies. This
		// supports private-mode and late receipts without retaining action data.
		reservation["issued_action_ids"] = issued
	}
	providerBinding := str(out, "_nlu_provider_binding")
	delete(out, "_nlu_provider_binding")
	context, _ := out["_tn_staged_context"].(map[string]any)
	delete(out, "_tn_staged_context")
	if providerBinding != "" {
		reservation["nlu_provider_binding"] = providerBinding
	}
	reservation["result"] = out
	private := requestPrivate || s.aiSettings(uid)["private_mode"] == true
	if private {
		reservation["private_mode"] = true
		reservation["result"] = Object{"request_id": requestID, "conversation_id": conversation, "session_id": conversation, "text": "", "utterance": "", "directives": []Object{}, "provider_latency_ms": 0}
	}
	reservation["status"] = "complete"
	if e = s.completeAssistantRequest(key, uid, reservation, out, private, scope+":"+conversation, context); e != nil {
		return e
	}
	private = reservation["private_mode"] == true
	if private && providerBinding != "" {
		s.rememberPrivateLocationRequest(key, out)
	}
	// Bound retained answer bodies while keeping fingerprint tombstones. An
	// old request ID stays non-executable even after its answer is evicted.
	s.db.Exec(`UPDATE records SET data=json_remove(data,'$.result') WHERE kind='assistant_request' AND owner=? AND id NOT IN (SELECT id FROM records WHERE kind='assistant_request' AND owner=? ORDER BY created_at DESC LIMIT 200) AND json_extract(data,'$.status')='complete'`, uid, uid)

	send(w, 200, out)
	return nil
}

// Save the answer that Android actually receives, after command handling or AI
// fallback. History and the replay body commit together, so a retry cannot add a
// second row or leave the preliminary (possibly empty) command response behind.
func (s *Server) completeAssistantRequest(key, uid string, reservation, out Object, private bool, contextKey string, context Object) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var pendingRaw, settingsRaw string
	if err = tx.QueryRow("SELECT data FROM records WHERE kind='assistant_request' AND id=? AND owner=?", key, uid).Scan(&pendingRaw); errors.Is(err, sql.ErrNoRows) {
		return problem(409, "REQUEST_RESERVATION_LOST", "Assistant request was removed before completion")
	} else if err != nil {
		return err
	}
	var pending Object
	if err = json.Unmarshal([]byte(pendingRaw), &pending); err != nil {
		return err
	}
	private = private || pending["private_mode"] == true
	err = tx.QueryRow("SELECT data FROM settings WHERE user_id=?", uid).Scan(&settingsRaw)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err == nil {
		var settings Object
		if err = json.Unmarshal([]byte(settingsRaw), &settings); err != nil {
			return err
		}
		private = private || settings["private_mode"] == true
	}
	if private {
		reservation["private_mode"] = true
		reservation["result"] = Object{"request_id": str(out, "request_id"), "conversation_id": str(out, "conversation_id"),
			"session_id": str(out, "session_id"), "text": "", "utterance": "", "directives": []Object{}, "provider_latency_ms": 0}
	}
	updated, err := tx.Exec("UPDATE records SET data=? WHERE kind='assistant_request' AND id=? AND owner=?", jsonText(reservation), key, uid)
	if err != nil {
		return err
	}
	count, err := updated.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return problem(409, "REQUEST_RESERVATION_LOST", "Assistant request was removed before completion")
	}
	if !private {
		if context != nil {
			context["id"] = contextKey
			if _, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('ai_conversation',?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET owner=excluded.owner,data=excluded.data", contextKey, uid, jsonText(context), now()); err != nil {
				return err
			}
			if _, err = tx.Exec(`DELETE FROM records WHERE kind IN ('ai_conversation','ai_confirmation') AND (json_extract(data,'$.expires_at')<? OR (owner=? AND kind='ai_conversation' AND id NOT IN (SELECT id FROM records WHERE kind='ai_conversation' AND owner=? ORDER BY created_at DESC LIMIT 20)))`, now(), uid, uid); err != nil {
				return err
			}
		}
		id := hash("assistant-history\x00" + key)
		history := Object{"id": id, "kind": "assistant", "text": str(out, "text"), "result": out, "created_at": now()}
		if _, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('history',?,?,?,?)", id, uid, jsonText(history), history["created_at"]); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func commandHandled(out Object) bool {
	if str(out, "source") == "tn_command_router_fallback" {
		return false
	}
	raw, _ := out["directives"].([]Object)
	if len(raw) == 0 {
		values, _ := out["directives"].([]any)
		for _, v := range values {
			if m, ok := v.(map[string]any); ok {
				raw = append(raw, m)
			}
		}
	}
	if len(raw) == 0 {
		return false
	}
	for _, d := range raw {
		switch str(d, "type") {
		case "Unknown", "Unsupported", "NotSupported", "Fallback", "UnknownIntent":
			return false
		}
	}
	lower := strings.ToLower(str(out, "utterance"))
	for _, phrase := range []string{"không hiểu", "chưa hiểu", "chưa hỗ trợ", "không hỗ trợ"} {
		if strings.Contains(lower, phrase) {
			return false
		}
	}
	return true
}

func isManagedNewsRequest(text string) bool {
	value := strings.ToLower(strings.TrimSpace(text))
	for _, phrase := range []string{"đọc bản tin đã lưu", "đọc bản tin tn", "điểm báo tn"} {
		if value == phrase || strings.HasPrefix(value, phrase+" ") || strings.HasPrefix(value, phrase+"?") {
			return true
		}
	}
	return false
}

func (s *Server) assistantResult(r *http.Request, p *Principal, m Object, mode, scope, conversation string) (Object, error) {
	if token := str(m, "confirmation_token"); token != "" {
		pending, e := s.record("ai_confirmation", hash(scope+":"+token))
		expires, expiryErr := time.Parse(time.RFC3339Nano, str(pending, "expires_at"))
		if e != nil || expiryErr != nil || str(pending, "conversation_id") != conversation || !time.Now().Before(expires) {
			return nil, problem(409, "CONFIRMATION_EXPIRED", "Confirmation unavailable or expired")
		}
		confirmationMode := str(pending, "mode")
		if confirmationMode == "" { // Already-issued AI proposals predate mode metadata.
			confirmationMode = "tools"
		}
		if mode != confirmationMode || (mode == "commands" && str(pending, "origin") != "tn-command") {
			return nil, problem(400, "INVALID_CONFIRMATION", "Confirmation mode does not match the issued proposal")
		}
		if s.independentRuntime() {
			cfg, err := s.runtimeConfig()
			if err != nil {
				return nil, err
			}
			if integer(pending, "runtime_generation", 0) != cfg.Generation {
				return nil, problem(409, "CONFIRMATION_RETIRED", "Confirmation belongs to a retired runtime")
			}
		}
		// Atomic consume before returning the directive, including concurrent
		// confirmations carrying different request IDs.
		result, e := s.db.Exec("DELETE FROM records WHERE kind='ai_confirmation' AND id=?", hash(scope+":"+token))
		if e != nil {
			return nil, e
		}
		n, _ := result.RowsAffected()
		if n != 1 {
			return nil, problem(409, "CONFIRMATION_USED", "Confirmation already consumed")
		}
		directive, _ := pending["directive"].(map[string]any)
		source := "ai-confirmed"
		if mode == "commands" {
			source = "tn-command-confirmed"
		}
		return Object{"utterance": "Đang chuyển yêu cầu đã xác nhận tới thiết bị.", "directives": []Object{directive}, "provider_latency_ms": 0, "source": source}, nil
	}
	start := time.Now()
	if s.independentRuntime() && basicAlertRequest(str(m, "text")) {
		clock, err := basicAlertClock(m, start)
		if err != nil {
			return nil, err
		}
		if directive, explanation := basicAlertDirective(str(m, "text"), clock); directive != nil {
			payload, _ := directive["payload"].(map[string]any)
			if str(payload, "type") == "ShowAlarms" {
				return tnSpeechReply("", directive), nil
			}
			capabilities, _ := m["client_capabilities"].(map[string]any)
			if capabilities["basic_alerts"] != true {
				return tnSpeechReply("Vui lòng cập nhật TN Assistant để xác nhận báo thức, hẹn giờ và nhắc việc trên thiết bị.", nil), nil
			}
			return s.assistantConfirmation(p, directive, "commands", scope, conversation)
		} else if explanation != "" {
			return tnSpeechReply(explanation, nil), nil
		}
	}
	if s.independentRuntime() && isTNWeatherRequest(str(m, "text")) {
		lat, latOK := m["lat"].(float64)
		lon, lonOK := m["lon"].(float64)
		if !latOK || !lonOK {
			return tnSpeechReply("Để xem thời tiết tại vị trí hiện tại, vui lòng bật quyền vị trí rồi thử lại.", nil), nil
		}
		forecast, err := s.weatherForecast(r.Context(), lat, lon)
		if err != nil {
			return nil, err
		}
		utterance, err := weatherUtterance(forecast)
		if err != nil {
			return nil, problem(502, "WEATHER_RESPONSE_INVALID", "MET Norway forecast has no current conditions")
		}
		return tnSpeechReply(utterance, nil), nil
	}
	if saved, handled, err := s.savedDestinationCommand(str(p.User, "id"), str(m, "text")); handled {
		if err != nil {
			return nil, err
		}
		saved["provider_latency_ms"] = time.Since(start).Milliseconds()
		return saved, nil
	} else if err != nil {
		return nil, err
	}
	if command := basicYouTubeCommand(str(m, "text")); command != nil {
		return command, nil
	}
	if command := basicVolumeCommand(str(m, "text")); command != nil {
		return command, nil
	}
	if command := basicMediaCommand(str(m, "text")); command != nil {
		return command, nil
	}
	if directive := basicPhoneDirective(str(m, "text")); directive != nil {
		return s.assistantConfirmation(p, directive, "commands", scope, conversation)
	}
	if answer, ok := tnCalculatorAnswer(str(m, "text")); ok {
		return Object{"source": "local-calculator", "utterance": answer, "provider_latency_ms": 0,
			"directives": []Object{{"type": "SpeechSynthesizer", "payload": Object{"utter_text": answer}}}}, nil
	}
	var managedNewsError error
	lower := strings.ToLower(strings.TrimSpace(str(m, "text")))
	if isManagedNewsRequest(lower) {
		managed, err := s.managedNewsDirective(p)
		if err == nil {
			managed["provider_latency_ms"] = time.Since(start).Milliseconds()
			return managed, nil
		}
		managedNewsError = err
	}
	command, e := s.commandAssistant(r, p, m)
	if e != nil && managedNewsError != nil {
		return nil, managedNewsError
	}
	if mode == "commands" {
		return command, e
	}
	if e == nil && commandHandled(command) {
		return command, nil
	}
	settings := s.aiSettings(str(p.User, "id"))
	private := m["_tn_private_mode"] == true || settings["private_mode"] == true
	messages := []aiMessage{}
	if !private {
		if saved, err := s.record("ai_conversation", scope+":"+conversation); err == nil && str(saved, "expires_at") > now() {
			json.Unmarshal([]byte(jsonText(saved["messages"])), &messages)
		}
	}
	messages = append(messages, aiMessage{"user", str(m, "text")})
	answer, sources, e := s.aiAnswerWithSources(r.Context(), messages, mode == "tools")
	if e != nil {
		return nil, e
	}
	utterance := answer
	directives := []Object{}
	out := Object{"source": "ai", "provider_latency_ms": time.Since(start).Milliseconds()}
	if mode == "tools" {
		var tool Object
		answer = strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(strings.TrimSpace(answer), "```json"), "```"), "```"))
		if json.Unmarshal([]byte(answer), &tool) != nil {
			return nil, problem(502, "AI_INVALID_TOOL", "AI tool result must be JSON")
		}
		utterance = str(tool, "utterance")
		directive, sensitive, e := validatedAITool(tool)
		if e != nil {
			return nil, e
		}
		if directive != nil {
			if sensitive {
				proposal, err := s.assistantConfirmation(p, directive, "tools", scope, conversation)
				if err != nil {
					return nil, err
				}
				out["confirmation_required"], out["confirmation_token"], out["confirmation"] = proposal["confirmation_required"], proposal["confirmation_token"], proposal["confirmation"]
				utterance = str(proposal, "utterance")
			} else {
				directives = append(directives, directive)
			}
		}
	}
	if strings.TrimSpace(utterance) == "" {
		utterance = "TN đã chuẩn bị yêu cầu."
	}
	if !private {
		messages = append(messages, aiMessage{"assistant", utterance})
		for len(messages) > 12 {
			messages = messages[2:]
		}
		for len(jsonText(messages)) > 24000 && len(messages) > 2 {
			messages = messages[2:]
		}
		// Stage in memory. Privacy, context, replay body and final history are
		// committed together, after the provider returns and privacy is rechecked.
		out["_tn_staged_context"] = Object{"messages": messages, "expires_at": time.Now().UTC().Add(30 * time.Minute).Format(time.RFC3339Nano)}
	}
	// Existing Android TTS pipeline accepts the normalized directive, retaining
	// a single playback owner and its completion callback.
	directives = append(directives, Object{"type": "SpeechSynthesizer", "payload": Object{"utter_text": utterance}})
	out["utterance"] = utterance
	out["directives"] = directives
	if len(sources) > 0 {
		out["sources"] = sources
	}
	return out, nil
}

// Local Basic commands and Premium AI tools share owner/device binding and
// one-use consent, but local commands never grant access to AI generation.
func (s *Server) assistantConfirmation(p *Principal, directive Object, mode, scope, conversation string) (Object, error) {
	payload, _ := directive["payload"].(map[string]any)
	localAlert := str(directive, "type") == "Alerts" && (str(payload, "type") == "Alarm" || str(payload, "type") == "Timer" || str(payload, "type") == "Reminder" || str(payload, "type") == "DismissAlarm")
	if mode != "tools" && (mode != "commands" || (str(directive, "type") != "PhoneCall" && !localAlert)) {
		return nil, problem(500, "INVALID_CONFIRMATION", "Unsupported confirmation origin")
	}
	token := ident()
	origin := "ai"
	if mode == "commands" {
		origin = "tn-command"
	}
	pending := Object{"conversation_id": conversation, "directive": directive, "mode": mode, "origin": origin,
		"expires_at": time.Now().UTC().Add(2 * time.Minute).Format(time.RFC3339Nano)}
	if s.independentRuntime() {
		cfg, err := s.runtimeConfig()
		if err != nil {
			return nil, err
		}
		pending["runtime_generation"] = cfg.Generation
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	// Local-only users may never create an AI conversation. Expired pending
	// actions therefore cannot depend on AI-context writes for cleanup.
	if _, err = tx.Exec("DELETE FROM records WHERE kind='ai_confirmation' AND json_extract(data,'$.expires_at')<?", time.Now().UTC().Format(time.RFC3339Nano)); err != nil {
		return nil, err
	}
	if _, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('ai_confirmation',?,?,?,?)",
		hash(scope+":"+token), str(p.User, "id"), jsonText(pending), now()); err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	summary := tnConfirmationSummary(directive)
	utterance := "Xác nhận thao tác: " + summary + " Vui lòng xác nhận trên ứng dụng. TN chỉ thực hiện sau khi bạn đồng ý."
	return Object{"source": origin + "-confirmation", "provider_latency_ms": 0, "confirmation_required": true, "confirmation_token": token,
		"confirmation": Object{"type": str(directive, "type"), "summary": summary, "expires_in_ms": 120000, "mode": mode},
		"utterance":    utterance, "directives": []Object{{"type": "SpeechSynthesizer", "payload": Object{"utter_text": utterance}}}}, nil
}

// Consent text comes from validated arguments, never the model's claim that an
// operation already happened. The same immutable directive is redeemed later.
func tnConfirmationSummary(directive Object) string {
	payload, _ := directive["payload"].(map[string]any)
	if str(directive, "type") == "Alerts" && str(payload, "type") == "Timer" {
		return basicTimerSummary(integer(payload, "duration", 0))
	}
	if str(directive, "type") == "PhoneCall" {
		if contact := strings.TrimSpace(str(payload, "contact_name")); contact != "" {
			return "Gọi cho " + contact + "?"
		}
		return "Gọi số " + str(payload, "phone_number") + "?"
	}
	if str(payload, "type") == "DismissAlarm" {
		if str(payload, "searchMode") == "time" {
			return fmt.Sprintf("Mở Đồng hồ để chọn và xác nhận tắt báo thức lúc %02d:%02d?", integer(payload, "hour", 0), integer(payload, "minute", 0))
		}
		return "Mở Đồng hồ để chọn và xác nhận tắt báo thức tiếp theo hoặc đang reo?"
	}
	label := "Đặt báo thức"
	if str(payload, "type") == "Reminder" {
		label = "Tạo nhắc việc: " + str(payload, "task")
	}
	repeat := map[string]string{"None": "Không lặp", "Daily": "Hằng ngày", "Weekly": "Hằng tuần", "Weekdays": "Ngày trong tuần", "Weekend": "Cuối tuần"}[str(payload, "repeatType")]
	summary := fmt.Sprintf("%s lúc %02d:%02d ngày %02d/%02d/%d. Lặp lại: %s", label, integer(payload, "hour", 0), integer(payload, "minute", 0),
		integer(payload, "day", 0), integer(payload, "month", 0), integer(payload, "year", 0), repeat)
	if zone := str(payload, "timezone"); zone != "" {
		summary += ". Múi giờ thiết bị: " + zone
	}
	return summary + "?"
}

var aiPackage = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$`)
var aiPhone = regexp.MustCompile(`^\+?[0-9]{3,15}$`)

func validatedAITool(m Object) (Object, bool, error) {
	args, _ := m["arguments"].(map[string]any)
	switch str(m, "tool") {
	case "", "none":
		return nil, false, nil
	case "open_app":
		pkg := str(args, "package_name")
		if !aiPackage.MatchString(pkg) {
			break
		}
		return Object{"type": "OpenApp", "payload": Object{"package_name": pkg}}, false, nil
	case "navigate":
		query := strings.TrimSpace(str(args, "query"))
		if query == "" || len(query) > 1000 {
			break
		}
		return Object{"type": "MapDirection", "payload": Object{"query": query,
			"url": "https://www.google.com/maps/dir/?api=1&destination=" + url.QueryEscape(query)}}, false, nil
	case "call":
		number := str(args, "phone_number")
		if !aiPhone.MatchString(number) {
			break
		}
		return Object{"type": "PhoneCall", "payload": Object{"phone_number": number}}, true, nil
	case "play_music", "play_radio", "play_video", "play_tv":
		query := strings.TrimSpace(str(args, "query"))
		if query == "" || len([]rune(query)) > 200 {
			break
		}
		typeByTool := map[string]string{"play_music": "PlayMusicApp", "play_radio": "PlayRadioApp", "play_video": "PlayVideoApp", "play_tv": "PlayTVApp"}
		return Object{"type": typeByTool[str(m, "tool")], "payload": Object{"query": query}}, false, nil
	case "show_alarms":
		return Object{"type": "Alerts", "payload": Object{"type": "ShowAlarms"}}, false, nil
	case "dismiss_alarm":
		payload := Object{"type": "DismissAlarm"}
		switch str(args, "search_mode") {
		case "next":
			payload["searchMode"] = "next"
		case "time":
			hour, minute := integer(args, "hour", -1), integer(args, "minute", -1)
			if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
				break
			}
			payload["searchMode"], payload["hour"], payload["minute"] = "time", hour, minute
		default:
			break
		}
		if payload["searchMode"] != nil {
			return Object{"type": "Alerts", "payload": payload}, true, nil
		}
		break
	case "set_alarm", "create_reminder":
		hour, minute := integer(args, "hour", -1), integer(args, "minute", -1)
		if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
			break
		}
		clock := time.Now()
		day, month, year := integer(args, "day", clock.Day()), integer(args, "month", int(clock.Month())), integer(args, "year", clock.Year())
		date := time.Date(year, time.Month(month), day, hour, minute, 0, 0, time.Local)
		if date.Day() != day || int(date.Month()) != month || date.Year() != year || date.Before(clock.Add(-time.Minute)) {
			break
		}
		repeat := str(args, "repeat_type")
		if repeat == "" {
			repeat = "None"
		}
		if repeat != "None" && repeat != "Daily" && repeat != "Weekly" && repeat != "Weekdays" && repeat != "Weekend" {
			break
		}
		payload := Object{"day": day, "month": month, "year": year, "hour": hour, "minute": minute, "repeatType": repeat}
		if str(m, "tool") == "set_alarm" {
			payload["type"] = "Alarm"
		} else {
			task := strings.TrimSpace(str(args, "task"))
			if task == "" || len([]rune(task)) > 200 {
				break
			}
			payload["type"], payload["task"] = "Reminder", task
		}
		return Object{"type": "Alerts", "payload": payload}, true, nil
	}
	return nil, false, problem(502, "AI_INVALID_TOOL", "AI requested an unsupported tool or invalid arguments")
}
