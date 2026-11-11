package main

import (
	"math"
	"net/http"
	"strings"
	"unicode/utf8"
)

const savedAddressLimit = 20

func (s *Server) addressBookAPI(w http.ResponseWriter, r *http.Request, p *Principal) (bool, error) {
	const base = "/v1/addresses"
	path := r.URL.Path
	if path != base && !strings.HasPrefix(path, base+"/") {
		return false, nil
	}
	uid := str(p.User, "id")
	if path == base && r.Method == http.MethodGet {
		items, err := s.list("saved_address", uid, "")
		if err != nil {
			return true, err
		}
		send(w, http.StatusOK, Object{"items": items})
		return true, nil
	}
	if path == base && r.Method == http.MethodPost {
		if s.premiumFeatures(uid)["address_book"] != true {
			return true, problem(http.StatusForbidden, "FEATURE_NOT_AVAILABLE", "Sổ địa chỉ thường đến cần Premium")
		}
		input, err := savedAddressInput(r)
		if err != nil {
			return true, err
		}
		nameKey := strings.ToLower(strings.Join(strings.Fields(str(input, "name")), " "))
		id := hash(uid + "\x00" + nameKey)[:48]
		if _, err = s.record("saved_address", id); err != nil {
			var count int
			if queryErr := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='saved_address' AND owner=?", uid).Scan(&count); queryErr != nil {
				return true, queryErr
			}
			if count >= savedAddressLimit {
				return true, problem(http.StatusConflict, "ADDRESS_BOOK_FULL", "Sổ địa chỉ đã đủ 20 mục")
			}
		}
		input["id"] = id
		input["created_at"] = now()
		if existing, err := s.record("saved_address", id); err == nil {
			var owner string
			if queryErr := s.db.QueryRow("SELECT owner FROM records WHERE kind='saved_address' AND id=?", id).Scan(&owner); queryErr != nil || owner != uid {
				return true, problem(http.StatusConflict, "ADDRESS_ID_CONFLICT", "Không thể lưu địa chỉ này")
			}
			input["created_at"] = existing["created_at"]
		}
		input["updated_at"] = now()
		if err = s.put("saved_address", id, uid, input); err != nil {
			return true, err
		}
		send(w, http.StatusOK, Object{"item": input})
		return true, nil
	}
	if strings.HasPrefix(path, base+"/") {
		id := strings.TrimPrefix(path, base+"/")
		if id == "" || strings.Contains(id, "/") || len(id) != 48 {
			return true, problem(http.StatusBadRequest, "INVALID_ADDRESS_ID", "Mã địa chỉ không hợp lệ")
		}
		var owner string
		err := s.db.QueryRow("SELECT owner FROM records WHERE kind='saved_address' AND id=?", id).Scan(&owner)
		if err != nil || owner != uid {
			return true, problem(http.StatusNotFound, "ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ đã lưu")
		}
		switch r.Method {
		case http.MethodPut:
			if s.premiumFeatures(uid)["address_book"] != true {
				return true, problem(http.StatusForbidden, "FEATURE_NOT_AVAILABLE", "Sổ địa chỉ thường đến cần Premium")
			}
			input, inputErr := savedAddressInput(r)
			if inputErr != nil {
				return true, inputErr
			}
			items, listErr := s.list("saved_address", uid, "")
			if listErr != nil {
				return true, listErr
			}
			for _, item := range items {
				if str(item, "id") != id && strings.EqualFold(str(item, "name"), str(input, "name")) {
					return true, problem(http.StatusConflict, "ADDRESS_NAME_EXISTS", "Tên địa chỉ đã được sử dụng")
				}
			}
			old, _ := s.record("saved_address", id)
			input["id"], input["created_at"], input["updated_at"] = id, old["created_at"], now()
			if err = s.put("saved_address", id, uid, input); err != nil {
				return true, err
			}
			send(w, http.StatusOK, Object{"item": input})
			return true, nil
		case http.MethodDelete:
			result, deleteErr := s.db.Exec("DELETE FROM records WHERE kind='saved_address' AND id=? AND owner=?", id, uid)
			if deleteErr != nil {
				return true, deleteErr
			}
			deleted, _ := result.RowsAffected()
			if deleted != 1 {
				return true, problem(http.StatusNotFound, "ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ đã lưu")
			}
			send(w, http.StatusOK, Object{"ok": true})
			return true, nil
		}
	}
	return true, problem(http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ")
}

func savedAddressInput(r *http.Request) (Object, error) {
	input, err := body(r)
	if err != nil {
		return nil, err
	}
	name := strings.TrimSpace(str(input, "name"))
	address := strings.TrimSpace(str(input, "address"))
	if runes := utf8.RuneCountInString(name); runes < 1 || runes > 40 || strings.ContainsRune(name, '\x00') {
		return nil, problem(http.StatusBadRequest, "INVALID_ADDRESS_NAME", "Tên gợi nhớ phải có từ 1 đến 40 ký tự")
	}
	if runes := utf8.RuneCountInString(address); runes < 3 || runes > 500 || strings.ContainsRune(address, '\x00') {
		return nil, problem(http.StatusBadRequest, "INVALID_ADDRESS", "Địa chỉ phải có từ 3 đến 500 ký tự")
	}
	out := Object{"name": name, "address": address}
	lat, hasLat, err := savedCoordinate(input, "lat", -90, 90)
	if err != nil {
		return nil, err
	}
	lon, hasLon, err := savedCoordinate(input, "lon", -180, 180)
	if err != nil {
		return nil, err
	}
	if hasLat != hasLon {
		return nil, problem(http.StatusBadRequest, "INVALID_COORDINATES", "Cần nhập đồng thời vĩ độ và kinh độ")
	}
	if hasLat {
		out["lat"], out["lon"] = lat, lon
	}
	return out, nil
}

func savedCoordinate(input Object, key string, minimum, maximum float64) (float64, bool, error) {
	raw, exists := input[key]
	if !exists || raw == nil {
		return 0, false, nil
	}
	value, ok := raw.(float64)
	if !ok || math.IsNaN(value) || math.IsInf(value, 0) || value < minimum || value > maximum {
		return 0, false, problem(http.StatusBadRequest, "INVALID_COORDINATES", "Tọa độ địa chỉ không hợp lệ")
	}
	return value, true, nil
}

func (s *Server) savedDestinationCommand(uid, utterance string) (Object, bool, error) {
	text := strings.TrimSpace(strings.ToLower(utterance))
	prefixes := []string{"chỉ đường đến ", "chỉ đường tới ", "chỉ đường về ", "dẫn đường đến ", "dẫn đường tới ", "dẫn đường về ", "đưa tôi đến ", "đưa tôi tới ", "đưa tôi về ", "đi đến ", "đi tới ", "đi về "}
	label := ""
	for _, prefix := range prefixes {
		if strings.HasPrefix(text, prefix) {
			label = strings.TrimSpace(strings.TrimSuffix(strings.TrimSuffix(strings.TrimPrefix(text, prefix), "."), "?"))
			break
		}
	}
	if label == "" {
		return nil, false, nil
	}
	items, err := s.list("saved_address", uid, "")
	if err != nil {
		return nil, false, err
	}
	for _, item := range items {
		name := strings.TrimSpace(str(item, "name"))
		if !strings.EqualFold(name, label) {
			continue
		}
		if s.premiumFeatures(uid)["address_book"] != true {
			return nil, true, problem(http.StatusForbidden, "FEATURE_NOT_AVAILABLE", "Sử dụng địa chỉ thường đến cần Premium")
		}
		query := str(item, "address")
		payload := Object{"query": query, "destination": name}
		if lat, hasLat := item["lat"].(float64); hasLat {
			if lon, hasLon := item["lon"].(float64); hasLon {
				payload["address"] = Object{"lat": lat, "lon": lon, "name": name, "fullAddress": query}
			}
		}
		return Object{
			"source":              "saved_address",
			"provider_latency_ms": int64(0),
			"utterance":           "Đang chỉ đường đến " + name + ".",
			"directives":          []Object{{"type": "MapDirection", "payload": payload}},
		}, true, nil
	}
	return nil, false, nil
}
