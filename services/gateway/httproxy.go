package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
)

// Paths the client must never reach on OpenIM, even via the user-facing /im/ proxy.
var blockedIMPaths = []string{
	"/auth/get_admin_token",
	"/auth/get_user_token",
	"/friend/import_friend",
	"/user/get_users",
	"/user/get_all_users_uid",
	"/msg/send_msg",
	"/msg/batch_send_msg",
	"/msg/send_business_notification",
}

func isBlockedIMPath(path string) bool {
	p := strings.TrimSuffix(path, "/")
	for _, b := range blockedIMPaths {
		if p == b || strings.HasPrefix(p, b+"/") {
			return true
		}
	}
	return false
}

func newPrefixProxy(targetBase, stripPrefix, publicGateway, minioPublic string) (*httputil.ReverseProxy, error) {
	target, err := url.Parse(targetBase)
	if err != nil {
		return nil, err
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	origDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		origDirector(req)
		req.Host = target.Host
		path := req.URL.Path
		if stripPrefix != "" && (path == stripPrefix || strings.HasPrefix(path, stripPrefix+"/")) {
			path = strings.TrimPrefix(path, stripPrefix)
			if path == "" {
				path = "/"
			}
			req.URL.Path = path
		}
		req.URL.Host = target.Host
		req.URL.Scheme = target.Scheme
		// Do not forward hop-by-hop leftovers; preserve token / operationID.
	}
	if publicGateway != "" && minioPublic != "" {
		proxy.ModifyResponse = func(resp *http.Response) error {
			ct := resp.Header.Get("Content-Type")
			if !strings.Contains(ct, "json") && !strings.Contains(ct, "text") {
				return nil
			}
			b, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
			resp.Body.Close()
			if err != nil {
				return err
			}
			replaced := bytes.ReplaceAll(b, []byte(strings.TrimRight(minioPublic, "/")), []byte(strings.TrimRight(publicGateway, "/")+"/objects"))
			resp.Body = io.NopCloser(bytes.NewReader(replaced))
			resp.ContentLength = int64(len(replaced))
			resp.Header.Set("Content-Length", int64String(int64(len(replaced))))
			return nil
		}
	}
	return proxy, nil
}

func int64String(n int64) string {
	return strconv.FormatInt(n, 10)
}

func (s *server) handleIMProxy(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/im")
	if path == "" {
		path = "/"
	}
	if isBlockedIMPath(path) {
		writeErr(w, http.StatusForbidden, 403, "this OpenIM API is not available to clients")
		return
	}
	s.imProxy.ServeHTTP(w, r)
}

func (s *server) handleChatProxy(w http.ResponseWriter, r *http.Request) {
	s.chatProxy.ServeHTTP(w, r)
}

func (s *server) handleObjectsProxy(w http.ResponseWriter, r *http.Request) {
	s.minioProxy.ServeHTTP(w, r)
}
