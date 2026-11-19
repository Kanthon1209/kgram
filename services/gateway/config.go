package main

import (
	"os"
	"strings"
)

type Config struct {
	Addr string

	TLSAddr     string
	TLSCertFile string
	TLSKeyFile  string

	OpenIMAPIURL   string
	OpenIMChatURL  string
	OpenIMWSURL    string
	OpenIMMinioURL string

	OpenIMSecret    string
	OpenIMAdminUser string

	// Optional public base of this gateway, used to rewrite MinIO object URLs
	// in proxied OpenIM JSON (e.g. https://203.0.113.10:8443).
	GatewayPublicURL string
}

func loadConfig() Config {
	return Config{
		Addr:             envOr("GATEWAY_ADDR", ":8080"),
		TLSAddr:          os.Getenv("GATEWAY_TLS_ADDR"),
		TLSCertFile:      os.Getenv("GATEWAY_TLS_CERT_FILE"),
		TLSKeyFile:       os.Getenv("GATEWAY_TLS_KEY_FILE"),
		OpenIMAPIURL:     strings.TrimRight(envOr("OPENIM_API_URL", "http://127.0.0.1:10002"), "/"),
		OpenIMChatURL:    strings.TrimRight(envOr("OPENIM_CHAT_URL", "http://127.0.0.1:10008"), "/"),
		OpenIMWSURL:      strings.TrimRight(envOr("OPENIM_WS_URL", "ws://127.0.0.1:10001"), "/"),
		OpenIMMinioURL:   strings.TrimRight(envOr("OPENIM_MINIO_URL", "http://127.0.0.1:10005"), "/"),
		OpenIMSecret:     os.Getenv("OPENIM_SECRET"),
		OpenIMAdminUser:  envOr("OPENIM_ADMIN_USER", "imAdmin"),
		GatewayPublicURL: strings.TrimRight(os.Getenv("GATEWAY_PUBLIC_URL"), "/"),
	}
}

func (c Config) TLSEnabled() bool {
	return c.TLSCertFile != "" && c.TLSKeyFile != ""
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
