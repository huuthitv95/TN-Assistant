package main

import (
	"net/http"
	"strconv"
	"strings"
)

// parseByteRange parses one RFC 9110 byte range. parsed is false when the
// Range header is unsupported or malformed, in which case callers serve the
// complete representation. A syntactically valid but unsatisfiable range is
// reported as parsed=true, satisfiable=false.
func parseByteRange(header string, size int64) (start, end int64, parsed, satisfiable bool) {
	header = strings.TrimSpace(header)
	if !strings.HasPrefix(header, "bytes=") {
		return 0, 0, false, false
	}
	spec := strings.TrimSpace(strings.TrimPrefix(header, "bytes="))
	if spec == "" || strings.Contains(spec, ",") {
		return 0, 0, false, false
	}
	parts := strings.Split(spec, "-")
	if len(parts) != 2 || size <= 0 {
		return 0, 0, true, false
	}
	parseDigits := func(value string) (int64, bool) {
		if value == "" {
			return 0, false
		}
		for _, digit := range value {
			if digit < '0' || digit > '9' {
				return 0, false
			}
		}
		parsed, err := strconv.ParseInt(value, 10, 64)
		return parsed, err == nil
	}

	if parts[0] == "" {
		suffix, ok := parseDigits(parts[1])
		if !ok {
			return 0, 0, false, false
		}
		if suffix == 0 {
			return 0, 0, true, false
		}
		if suffix > size {
			suffix = size
		}
		return size - suffix, size - 1, true, true
	}

	start, ok := parseDigits(parts[0])
	if !ok {
		return 0, 0, false, false
	}
	if start >= size {
		return 0, 0, true, false
	}
	end = size - 1
	if parts[1] != "" {
		end, ok = parseDigits(parts[1])
		if !ok {
			return 0, 0, false, false
		}
		if end < start {
			return 0, 0, true, false
		}
		if end >= size {
			end = size - 1
		}
	}
	return start, end, true, true
}

func writeAudioRange(w http.ResponseWriter, r *http.Request, data []byte, mime string) {
	size := int64(len(data))
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Accept-Ranges", "bytes")

	rangeHeader := r.Header.Get("Range")
	// Without a validator, If-Range cannot be checked safely; return the full
	// representation as required when a range precondition does not match.
	if r.Header.Get("If-Range") != "" {
		rangeHeader = ""
	}
	start, end, parsed, satisfiable := parseByteRange(rangeHeader, size)
	if parsed && !satisfiable {
		w.Header().Set("Content-Range", "bytes */"+strconv.FormatInt(size, 10))
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	status := http.StatusOK
	if parsed {
		status = http.StatusPartialContent
		w.Header().Set("Content-Range", "bytes "+strconv.FormatInt(start, 10)+"-"+strconv.FormatInt(end, 10)+"/"+strconv.FormatInt(size, 10))
	} else {
		start, end = 0, size-1
	}
	length := int64(0)
	if size > 0 {
		length = end - start + 1
	}
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.WriteHeader(status)
	if r.Method == http.MethodHead || length == 0 {
		return
	}
	_, _ = w.Write(data[int(start):int(end+1)])
}
