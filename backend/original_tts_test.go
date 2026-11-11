package main

import "testing"

func TestOriginalTTSProtocolRelativeMP3Playlist(t *testing.T) {
	chunks, e := legacyTTSChunks("https://audiostream-ailab.tts.zalo.ai/fixture", []byte("#EXTM3U\r\n#EXTINF:10.0\r\n//chunk-ailab.tts.zalo.ai/extensionless?requestId=fixture\r\n#EXT-X-ENDLIST\r\n"))
	if e != nil || len(chunks) != 1 || chunks[0] != "https://chunk-ailab.tts.zalo.ai/extensionless?requestId=fixture" {
		t.Fatal("Original protocol-relative MP3 chunk did not resolve over HTTPS")
	}
}
func TestOriginalTTSRejectsUnexpectedHostsAndEncryption(t *testing.T) {
	for _, entry := range []string{"http://chunk-ailab.tts.zalo.ai/a", "https://unrelated.invalid/a", "https://chunk-ailab.tts.zalo.ai:443/a", "#EXT-X-KEY:METHOD=AES-128\n//chunk-ailab.tts.zalo.ai/a"} {
		if _, e := legacyTTSChunks("https://audiostream-ailab.tts.zalo.ai/fixture", []byte("#EXTM3U\n"+entry)); e == nil {
			t.Fatal("Unexpected playlist accepted")
		}
	}
}
func TestOriginalUserIDEnvironmentIsRequiredWhenConfigured(t *testing.T) {
	t.Setenv("TEST_ORIGINAL_UID", "")
	t.Setenv("TEST_ORIGINAL_ASR_TOKEN", "fixture")
	p := Provider{Mode: "legacy_tn", URL: "https://fixture.invalid", UIDEnv: "TEST_ORIGINAL_UID", TokenEnv: "TEST_ORIGINAL_ASR_TOKEN"}
	if p.configured("asr") {
		t.Fatal("Missing referenced Original UID accepted")
	}
	t.Setenv("TEST_ORIGINAL_UID", "42")
	if !p.configured("asr") || p.userID() != "42" {
		t.Fatal("Referenced Original UID unavailable")
	}
}
