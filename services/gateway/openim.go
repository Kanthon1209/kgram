package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	openIMAlreadyFriends = 1304
	adminTokenTTL        = 50 * time.Minute
	httpTimeout          = 20 * time.Second
)

type openIMClient struct {
	apiBase    string
	httpClient *http.Client

	secret    string
	adminUser string

	mu         sync.Mutex
	adminToken string
	adminAt    time.Time
}

type openIMEnvelope struct {
	ErrCode int             `json:"errCode"`
	ErrMsg  string          `json:"errMsg"`
	ErrDlt  string          `json:"errDlt"`
	Data    json.RawMessage `json:"data"`
}

type parsedToken struct {
	UserID            string `json:"userID"`
	PlatformID        int32  `json:"platformID"`
	ExpireTimeSeconds int64  `json:"expireTimeSeconds"`
}

func newOpenIMClient(apiBase, secret, adminUser string) *openIMClient {
	return &openIMClient{
		apiBase:    strings.TrimRight(apiBase, "/"),
		httpClient: &http.Client{Timeout: httpTimeout},
		secret:     secret,
		adminUser:  adminUser,
	}
}

func (c *openIMClient) parseUserToken(token string) (*parsedToken, error) {
	body := map[string]string{"token": token}
	env, err := c.post("/auth/parse_token", body, "")
	if err != nil {
		return nil, err
	}
	if env.ErrCode != 0 {
		return nil, fmt.Errorf("parse_token: %d %s %s", env.ErrCode, env.ErrMsg, env.ErrDlt)
	}
	var claims parsedToken
	if err := json.Unmarshal(env.Data, &claims); err != nil {
		return nil, fmt.Errorf("parse_token: decode claims: %w", err)
	}
	if claims.UserID == "" {
		return nil, fmt.Errorf("parse_token: empty userID")
	}
	return &claims, nil
}

func (c *openIMClient) getAdminToken() (string, error) {
	if c.secret == "" {
		return "", fmt.Errorf("OPENIM_SECRET is not configured")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.adminToken != "" && time.Since(c.adminAt) < adminTokenTTL {
		return c.adminToken, nil
	}
	body := map[string]string{
		"secret": c.secret,
		"userID": c.adminUser,
	}
	env, err := c.post("/auth/get_admin_token", body, "")
	if err != nil {
		return "", err
	}
	if env.ErrCode != 0 {
		return "", fmt.Errorf("get_admin_token: %d %s", env.ErrCode, env.ErrMsg)
	}
	var data struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil || data.Token == "" {
		return "", fmt.Errorf("get_admin_token: empty token")
	}
	c.adminToken = data.Token
	c.adminAt = time.Now()
	return c.adminToken, nil
}

func (c *openIMClient) sendTextAsUser(sendID, recvID, senderNickname, text, clientMsgID string) (*openIMEnvelope, error) {
	admin, err := c.getAdminToken()
	if err != nil {
		return nil, err
	}
	body := map[string]any{
		"sendID":           sendID,
		"recvID":           recvID,
		"groupID":          "",
		"senderNickname":   senderNickname,
		"senderFaceURL":    "",
		"senderPlatformID": 2, // Android; gateway still forces sendID from the user token
		"content":          map[string]string{"content": text},
		"contentType":      101,
		"sessionType":      1,
		"isOnlineOnly":     false,
		"notOfflinePush":   false,
		"clientMsgID":      clientMsgID,
	}
	return c.post("/msg/send_msg", body, admin)
}

func (c *openIMClient) addFriend(userToken, fromUserID, toUserID, reqMsg string) (*openIMEnvelope, error) {
	body := map[string]string{
		"fromUserID": fromUserID,
		"toUserID":   toUserID,
		"reqMsg":     reqMsg,
	}
	return c.post("/friend/add_friend", body, userToken)
}

func (c *openIMClient) respondFriend(userToken, fromUserID, toUserID string, handleResult int, handleMsg string) (*openIMEnvelope, error) {
	body := map[string]any{
		"fromUserID":   fromUserID,
		"toUserID":     toUserID,
		"handleResult": handleResult,
		"handleMsg":    handleMsg,
	}
	return c.post("/friend/add_friend_response", body, userToken)
}

func (c *openIMClient) peerHasReadAndMaxSeq(peerUserID, conversationID string) (*openIMEnvelope, error) {
	admin, err := c.getAdminToken()
	if err != nil {
		return nil, err
	}
	body := map[string]any{
		"userID":          peerUserID,
		"conversationIDs": []string{conversationID},
	}
	return c.post("/msg/get_conversations_has_read_and_max_seq", body, admin)
}

func (c *openIMClient) post(path string, body any, token string) (*openIMEnvelope, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, c.apiBase+path, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("operationID", "kgram-gw-"+fmt.Sprint(time.Now().UnixNano()))
	if token != "" {
		req.Header.Set("token", token)
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var env openIMEnvelope
	if err := json.Unmarshal(b, &env); err != nil {
		return nil, fmt.Errorf("openim %s: HTTP %d: %s", path, resp.StatusCode, truncate(string(b), 200))
	}
	return &env, nil
}

func writeOpenIMJSON(w http.ResponseWriter, env *openIMEnvelope) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(env)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status, errCode int, msg string) {
	writeJSON(w, status, openIMEnvelope{ErrCode: errCode, ErrMsg: msg})
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
