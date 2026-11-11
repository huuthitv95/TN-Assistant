package main

import "testing"

func TestConfigEnvReadsTNVariables(t *testing.T) {
	values := map[string]string{
		"TN_DB":             "test.db",
		"TN_ADDR":           "127.0.0.1:8094",
		"TN_ADMIN_USER":     "admin-test",
		"TN_ADMIN_PASSWORD": "test-password",
		"TN_PROVIDER_FILE":  "providers.test.json",
		"TN_WEBHOOK_SECRET": "webhook-test-secret",
		"TN_PUBLIC_URL":     "https://assistant.example.test",
		"TN_STATIC_DIR":     "static-test",
		"TN_DOWNLOAD_DIR":   "downloads-test",
	}
	for key, value := range values {
		t.Setenv(key, value)
	}

	got := configEnv()
	want := Config{
		DBPath:        values["TN_DB"],
		Addr:          values["TN_ADDR"],
		AdminUser:     values["TN_ADMIN_USER"],
		AdminPassword: values["TN_ADMIN_PASSWORD"],
		ProviderFile:  values["TN_PROVIDER_FILE"],
		WebhookSecret: values["TN_WEBHOOK_SECRET"],
		PublicURL:     values["TN_PUBLIC_URL"],
		StaticDir:     values["TN_STATIC_DIR"],
		DownloadDir:   values["TN_DOWNLOAD_DIR"],
		RuntimeMode:   "independent",
	}
	if got != want {
		t.Fatalf("configEnv() = %#v, want %#v", got, want)
	}
}
