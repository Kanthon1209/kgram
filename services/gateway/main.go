package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type server struct {
	cfg        Config
	openim     *openIMClient
	imProxy    http.Handler
	chatProxy  http.Handler
	minioProxy http.Handler
}

func main() {
	cfg := loadConfig()
	s, err := newServer(cfg)
	if err != nil {
		log.Fatal(err)
	}

	mux := s.routes()
	handler := loggingMiddleware(mux)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	httpSrv := &http.Server{Addr: cfg.Addr, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	go func() {
		log.Printf("kgram gateway listening on %s (http)", cfg.Addr)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	var tlsSrv *http.Server
	if cfg.TLSEnabled() {
		addr := cfg.TLSAddr
		if addr == "" {
			addr = ":8443"
		}
		tlsSrv = &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
		go func() {
			log.Printf("kgram gateway listening on %s (https)", addr)
			if err := tlsSrv.ListenAndServeTLS(cfg.TLSCertFile, cfg.TLSKeyFile); err != nil && err != http.ErrServerClosed {
				log.Fatal(err)
			}
		}()
	} else {
		log.Printf("TLS not enabled (set GATEWAY_TLS_CERT_FILE and GATEWAY_TLS_KEY_FILE). HTTP is for local/dev or a TLS reverse proxy.")
	}

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shutdownCtx)
	if tlsSrv != nil {
		_ = tlsSrv.Shutdown(shutdownCtx)
	}
}

func newServer(cfg Config) (*server, error) {
	s := &server{
		cfg:    cfg,
		openim: newOpenIMClient(cfg.OpenIMAPIURL, cfg.OpenIMSecret, cfg.OpenIMAdminUser),
	}
	var err error
	s.imProxy, err = newPrefixProxy(cfg.OpenIMAPIURL, "/im", cfg.GatewayPublicURL, cfg.OpenIMMinioURL)
	if err != nil {
		return nil, err
	}
	s.chatProxy, err = newPrefixProxy(cfg.OpenIMChatURL, "/chat", "", "")
	if err != nil {
		return nil, err
	}
	s.minioProxy, err = newPrefixProxy(cfg.OpenIMMinioURL, "/objects", "", "")
	if err != nil {
		return nil, err
	}
	return s, nil
}

func (s *server) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{
			"status":  "ok",
			"service": "kgram-gateway",
		})
	})
	mux.HandleFunc("/v1/messages/send", s.handleSendText)
	mux.HandleFunc("/v1/friends/request", s.handleFriendRequest)
	mux.HandleFunc("/v1/friends/respond", s.handleFriendRespond)
	mux.HandleFunc("/v1/conversations/peer-read", s.handlePeerRead)
	mux.HandleFunc("/v1/ws", s.handleWS)

	mux.HandleFunc("/im/", s.handleIMProxy)
	mux.HandleFunc("/im", s.handleIMProxy)
	mux.HandleFunc("/chat/", s.handleChatProxy)
	mux.HandleFunc("/chat", s.handleChatProxy)
	mux.HandleFunc("/objects/", s.handleObjectsProxy)
	mux.HandleFunc("/objects", s.handleObjectsProxy)

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{
			"service": "kgram-gateway",
			"docs":    "see services/gateway/README.md",
		})
	})
	return mux
}

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		// Log method and path only — never headers, query, or tokens.
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Truncate(time.Millisecond))
	})
}
