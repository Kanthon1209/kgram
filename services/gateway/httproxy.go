package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"path"
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

// Admin or server-to-server Chat routes that must not be reachable through /chat,
// including if OPENIM_CHAT_URL is pointed at the admin API port by mistake.
// User login/register and /client_config/get stay allowed.
var blockedChatPaths = []string{
	"/callback",
	"/account/update",
	"/account/info",
	"/account/change_password",
	"/account/add_admin",
	"/account/add_user",
	"/account/del_admin",
	"/account/search",
	"/user/import",
	"/user/allow_register",
	"/user/password/reset",
	"/default",
	"/invitation_code",
	"/forbidden",
	"/block",
	"/statistic",
	"/config",
	"/restart",
	"/applet/add",
	"/applet/del",
	"/applet/update",
	"/applet/search",
	"/client_config/set",
	"/client_config/del",
	"/application/add_version",
	"/application/update_version",
	"/application/delete_version",
}

func isBlockedPath(path string, blocked []string) bool {
	p := strings.TrimSuffix(path, "/")
	if p == "" {
		p = "/"
	}
	for _, b := range blocked {
		if p == b || strings.HasPrefix(p, b+"/") {
			return true
		}
	}
	return false
}

func isBlockedIMPath(path string) bool {
	return isBlockedPath(path, blockedIMPaths)
}

func isBlockedChatPath(path string) bool {
	return isBlockedPath(path, blockedChatPaths)
}

func pathEncodingForbidden(escaped string) bool {
	e := strings.ToLower(escaped)
	return strings.Contains(e, "%2f") || strings.Contains(e, "%5c") || strings.Contains(e, "%2e")
}

func cleanRequestPath(p string) string {
	p = strings.TrimSpace(p)
	p = strings.ReplaceAll(p, "\\", "/")
	if p == "" {
		p = "/"
	}
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return path.Clean(p)
}

// apiPathAfterPrefix returns the cleaned upstream API path (prefix stripped
// when present). If cleaning escapes the prefix (e.g. /im/../auth/...), the
// cleaned absolute path is returned so block lists still apply.
func apiPathAfterPrefix(fullPath, stripPrefix string) string {
	cleaned := cleanRequestPath(fullPath)
	if stripPrefix != "" && (cleaned == stripPrefix || strings.HasPrefix(cleaned, stripPrefix+"/")) {
		p := strings.TrimPrefix(cleaned, stripPrefix)
		if p == "" {
			return "/"
		}
		return p
	}
	return cleaned
}

func rejectUnsafeProxyRequest(w http.ResponseWriter, r *http.Request, stripPrefix string, blocked func(string) bool) bool {
	if pathEncodingForbidden(r.URL.EscapedPath()) {
		writeErr(w, http.StatusForbidden, 403, "invalid path")
		return true
	}
	cleaned := cleanRequestPath(r.URL.Path)
	r.URL.Path = cleaned
	r.URL.RawPath = ""
	if blocked(apiPathAfterPrefix(cleaned, stripPrefix)) {
		writeErr(w, http.StatusForbidden, 403, "this API is not available to clients")
		return true
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
		p := cleanRequestPath(req.URL.Path)
		if stripPrefix != "" && (p == stripPrefix || strings.HasPrefix(p, stripPrefix+"/")) {
			p = strings.TrimPrefix(p, stripPrefix)
			if p == "" {
				p = "/"
			}
		}
		req.URL.Path = p
		req.URL.RawPath = ""
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
	if rejectUnsafeProxyRequest(w, r, "/im", isBlockedIMPath) {
		return
	}
	s.imProxy.ServeHTTP(w, r)
}

func (s *server) handleChatProxy(w http.ResponseWriter, r *http.Request) {
	if rejectUnsafeProxyRequest(w, r, "/chat", isBlockedChatPath) {
		return
	}
	s.chatProxy.ServeHTTP(w, r)
}

func (s *server) handleObjectsProxy(w http.ResponseWriter, r *http.Request) {
	s.minioProxy.ServeHTTP(w, r)
}
