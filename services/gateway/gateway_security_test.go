package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// These tests extend the existing gateway_test.go coverage. They evaluate
// the current security-gateway PR code with a fake OpenIM: identity
// rewrite, expired/invalid tokens, friend-respond authorization, conversation
// access, and WebSocket handshake. They do not talk to a live OpenIM or
// the demo server.

type recordedCall struct {
	Path  string
	Token string
	Body  map[string]any
}

type recordingOpenIM struct {
	mu           sync.Mutex
	calls        []recordedCall
	wsHits       int
	friendVerify bool
	adminUser    string
	friends      friendBook
}

type friendBook map[string]map[string]bool

func (b friendBook) add(a, c string) {
	if b[a] == nil {
		b[a] = map[string]bool{}
	}
	if b[c] == nil {
		b[c] = map[string]bool{}
	}
	b[a][c] = true
	b[c][a] = true
}

func (b friendBook) areFriends(sendID, recvID string) bool {
	return b[sendID][recvID]
}

func (f *recordingOpenIM) snapshot() []recordedCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]recordedCall, len(f.calls))
	copy(out, f.calls)
	return out
}

func (f *recordingOpenIM) record(path, token string, body map[string]any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, recordedCall{Path: path, Token: token, Body: body})
}

func (f *recordingOpenIM) paths() []string {
	var p []string
	for _, c := range f.snapshot() {
		p = append(p, c.Path)
	}
	return p
}

func (f *recordingOpenIM) last(path string) *recordedCall {
	calls := f.snapshot()
	for i := len(calls) - 1; i >= 0; i-- {
		if calls[i].Path == path {
			c := calls[i]
			return &c
		}
	}
	return nil
}

func (f *recordingOpenIM) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if websocket.IsWebSocketUpgrade(r) {
		f.mu.Lock()
		f.wsHits++
		f.mu.Unlock()
		q := r.URL.Query()
		f.record("WS", q.Get("token"), map[string]any{
			"sendID": q.Get("sendID"),
		})
		up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		_, _, _ = conn.ReadMessage()
		return
	}

	raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	var body map[string]any
	_ = json.Unmarshal(raw, &body)
	f.record(r.URL.Path, r.Header.Get("token"), body)

	w.Header().Set("Content-Type", "application/json")
	switch r.URL.Path {
	case "/auth/parse_token":
		token, _ := body["token"].(string)
		switch token {
		case "user-alice":
			_, _ = w.Write([]byte(`{"errCode":0,"data":{"userID":"alice","platformID":2,"expireTimeSeconds":1999999999}}`))
		case "user-bob":
			_, _ = w.Write([]byte(`{"errCode":0,"data":{"userID":"bob","platformID":2,"expireTimeSeconds":1999999999}}`))
		case "expired-alice":
			_, _ = w.Write([]byte(`{"errCode":1502,"errMsg":"token expired"}`))
		default:
			_, _ = w.Write([]byte(`{"errCode":1501,"errMsg":"invalid token"}`))
		}
	case "/auth/get_admin_token":
		secret, _ := body["secret"].(string)
		if secret != "unit-test-secret" {
			_, _ = w.Write([]byte(`{"errCode":1001,"errMsg":"bad secret"}`))
			return
		}
		_, _ = w.Write([]byte(`{"errCode":0,"data":{"token":"admin-token"}}`))
	case "/msg/send_msg":
		if r.Header.Get("token") != "admin-token" {
			_, _ = w.Write([]byte(`{"errCode":403,"errMsg":"only app manager can send message"}`))
			return
		}
		sendID, _ := body["sendID"].(string)
		recvID, _ := body["recvID"].(string)
		if err := openIMSingleChatFriendVerify(f.friendVerify, f.adminUser, sendID, recvID, f.friends.areFriends); err != nil {
			if err == errNotPeersFriend {
				_, _ = w.Write([]byte(`{"errCode":1303,"errMsg":"NotPeersFriend"}`))
				return
			}
			b, _ := json.Marshal(map[string]any{"errCode": 1001, "errMsg": err.Error()})
			_, _ = w.Write(b)
			return
		}
		b, _ := json.Marshal(map[string]any{"errCode": 0, "data": body})
		_, _ = w.Write(b)
	case "/friend/add_friend", "/friend/add_friend_response":
		b, _ := json.Marshal(map[string]any{"errCode": 0, "data": body})
		_, _ = w.Write(b)
	case "/msg/get_conversations_has_read_and_max_seq":
		_, _ = w.Write([]byte(`{"errCode":0,"data":{"seqs":{"si_alice_bob":{"hasReadSeq":9,"maxSeq":10}}}}`))
	default:
		http.NotFound(w, r)
	}
}

func newRecordingGateway(t *testing.T) (*httptest.Server, *recordingOpenIM) {
	t.Helper()
	fake := &recordingOpenIM{friendVerify: true, adminUser: "imAdmin", friends: friendBook{}}
	return newTestGateway(t, fake), fake
}

func postAuth(t *testing.T, url, token, body string) (int, openIMEnvelope, []byte) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var env openIMEnvelope
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &env); err != nil {
			t.Fatalf("decode %s: %v", raw, err)
		}
	}
	return resp.StatusCode, env, raw
}

func privilegedPaths() []string {
	return []string{
		"/v1/messages/send",
		"/v1/friends/request",
		"/v1/friends/respond",
		"/v1/conversations/peer-read",
	}
}

func TestExpiredTokenRejectedOnPrivilegedHTTP(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	bodies := map[string]string{
		"/v1/messages/send":              `{"recvID":"bob","text":"hi"}`,
		"/v1/friends/request":            `{"toUserID":"bob","reqMsg":"hi"}`,
		"/v1/friends/respond":            `{"fromUserID":"bob","handleResult":1}`,
		"/v1/conversations/peer-read":    `{"peerUserID":"bob","conversationID":"si_alice_bob"}`,
	}
	for _, path := range privilegedPaths() {
		code, env, _ := postAuth(t, gw.URL+path, "expired-alice", bodies[path])
		if code != http.StatusUnauthorized || env.ErrCode != 401 {
			t.Errorf("%s expired token: got %d errCode=%d msg=%q", path, code, env.ErrCode, env.ErrMsg)
		}
	}
	for _, p := range fake.paths() {
		if p == "/msg/send_msg" || p == "/friend/add_friend" || p == "/friend/add_friend_response" || p == "/msg/get_conversations_has_read_and_max_seq" {
			t.Errorf("expired token must not reach privileged upstream %s", p)
		}
		if p == "/friend/import_friend" {
			t.Errorf("import_friend must not be called")
		}
	}
}

func TestInvalidTokenRejectedOnPrivilegedHTTP(t *testing.T) {
	gw, _ := newRecordingGateway(t)
	code, env, _ := postAuth(t, gw.URL+"/v1/messages/send", "not-a-token", `{"recvID":"bob","text":"hi"}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("invalid token send: %d %+v", code, env)
	}
	code, env, _ = postAuth(t, gw.URL+"/v1/friends/respond", "not-a-token", `{"fromUserID":"bob","handleResult":1}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("invalid token respond: %d %+v", code, env)
	}
	code, env, _ = postAuth(t, gw.URL+"/v1/conversations/peer-read", "not-a-token", `{"peerUserID":"bob","conversationID":"si_alice_bob"}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("invalid token peer-read: %d %+v", code, env)
	}
}

func TestFriendRespondRequiresToken(t *testing.T) {
	gw, _ := newRecordingGateway(t)
	code, env, _ := postAuth(t, gw.URL+"/v1/friends/respond", "", `{"fromUserID":"alice","toUserID":"bob","handleResult":1}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("expected 401, got %d %+v", code, env)
	}
}

func TestFriendRespondForcesToUserIDToTokenSubject(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	// Alice tries to accept a request as Bob (toUserID=bob). Gateway must
	// force toUserID to the token subject.
	code, env, _ := postAuth(t, gw.URL+"/v1/friends/respond", "user-alice",
		`{"fromUserID":"carol","toUserID":"bob","handleResult":1,"handleMsg":"ok"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("unexpected %d %+v", code, env)
	}
	got := fake.last("/friend/add_friend_response")
	if got == nil {
		t.Fatal("expected add_friend_response to be called")
	}
	if got.Token != "user-alice" {
		t.Fatalf("must use the caller user token, got %q", got.Token)
	}
	if got.Body["toUserID"] != "alice" {
		t.Fatalf("toUserID must be forced to token subject, got %#v", got.Body["toUserID"])
	}
	if got.Body["fromUserID"] != "carol" {
		t.Fatalf("fromUserID (applicant) should be preserved, got %#v", got.Body["fromUserID"])
	}
	for _, p := range fake.paths() {
		if p == "/friend/import_friend" {
			t.Fatal("respond must not call import_friend")
		}
	}
}

func TestFriendRespondRejectsMissingZeroAndInvalidHandleResult(t *testing.T) {
	// OpenIM RespondFriendApply accepts only 1 (agree) and -1 (refuse).
	gw, fake := newRecordingGateway(t)
	cases := []string{
		`{"fromUserID":"alice"}`,
		`{"fromUserID":"alice","handleResult":0}`,
		`{"fromUserID":"alice","handleResult":2}`,
		`{"fromUserID":"alice","handleResult":99}`,
	}
	for _, body := range cases {
		code, env, _ := postAuth(t, gw.URL+"/v1/friends/respond", "user-bob", body)
		if code != http.StatusBadRequest || env.ErrCode != 1001 {
			t.Errorf("body %s: expected 400/1001, got %d %+v", body, code, env)
		}
	}
	if fake.last("/friend/add_friend_response") != nil {
		t.Fatal("invalid handleResult must not be forwarded upstream")
	}
}

func TestFriendRespondAcceptsAgreeAndRefuse(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	code, env, _ := postAuth(t, gw.URL+"/v1/friends/respond", "user-bob",
		`{"fromUserID":"alice","handleResult":1}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("agree: %d %+v", code, env)
	}
	got := fake.last("/friend/add_friend_response")
	if got == nil || got.Body["handleResult"] != float64(1) || got.Body["toUserID"] != "bob" {
		t.Fatalf("agree upstream %+v", got)
	}
	code, env, _ = postAuth(t, gw.URL+"/v1/friends/respond", "user-bob",
		`{"fromUserID":"alice","handleResult":-1}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("refuse: %d %+v", code, env)
	}
	got = fake.last("/friend/add_friend_response")
	if got == nil || got.Body["handleResult"] != float64(-1) {
		t.Fatalf("refuse upstream %+v", got)
	}
}

func TestFriendRequestNeverCallsImportFriend(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	code, env, _ := postAuth(t, gw.URL+"/v1/friends/request", "user-alice",
		`{"toUserID":"bob","reqMsg":"hi"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("unexpected %d %+v", code, env)
	}
	if fake.last("/friend/add_friend") == nil {
		t.Fatal("expected official add_friend")
	}
	for _, p := range fake.paths() {
		if p == "/friend/import_friend" {
			t.Fatal("request must not call import_friend")
		}
	}
}

func TestPeerReadRequiresToken(t *testing.T) {
	gw, _ := newRecordingGateway(t)
	code, env, _ := postAuth(t, gw.URL+"/v1/conversations/peer-read", "",
		`{"peerUserID":"bob","conversationID":"si_alice_bob"}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("expected 401, got %d %+v", code, env)
	}
}

func TestPeerReadRejectsMismatchedPeerAndConversation(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	// conversationID is a valid alice-bob id, but peerUserID is carol.
	code, env, _ := postAuth(t, gw.URL+"/v1/conversations/peer-read", "user-alice",
		`{"peerUserID":"carol","conversationID":"si_alice_bob"}`)
	if code != http.StatusForbidden || env.ErrCode != 403 {
		t.Fatalf("expected 403, got %d %+v", code, env)
	}
	if fake.last("/msg/get_conversations_has_read_and_max_seq") != nil {
		t.Fatal("foreign/mismatched peer-read must not hit admin seq API")
	}
}

func TestPeerReadRejectsUnsortedConversationID(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	// OpenIM single-chat ids are si_<sortedUserA>_<sortedUserB>.
	code, env, _ := postAuth(t, gw.URL+"/v1/conversations/peer-read", "user-alice",
		`{"peerUserID":"bob","conversationID":"si_bob_alice"}`)
	if code != http.StatusForbidden || env.ErrCode != 403 {
		t.Fatalf("expected 403 for unsorted id, got %d %+v", code, env)
	}
	if fake.last("/msg/get_conversations_has_read_and_max_seq") != nil {
		t.Fatal("unsorted conversationID must not hit admin seq API")
	}
}

func TestIMProxyBlocksRemainingAdminAPIs(t *testing.T) {
	gw, _ := newRecordingGateway(t)
	for _, path := range []string{
		"/im/auth/get_user_token",
		"/im/user/get_all_users_uid",
		"/im/msg/batch_send_msg",
		"/im/msg/send_business_notification",
	} {
		req, _ := http.NewRequest(http.MethodPost, gw.URL+path, strings.NewReader(`{}`))
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s: expected 403, got %d %s", path, resp.StatusCode, raw)
		}
	}
}

func wsURL(httpURL, query string) string {
	u := "ws" + strings.TrimPrefix(httpURL, "http") + "/v1/ws"
	if query != "" {
		u += "?" + query
	}
	return u
}

func readWSJSON(t *testing.T, c *websocket.Conn) map[string]any {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, data, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("ws read: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("ws json %s: %v", data, err)
	}
	return m
}

func TestWSExpiredTokenRejectedAfterUpgrade(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	h := http.Header{}
	h.Set("Authorization", "Bearer expired-alice")
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, ""), h)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	m := readWSJSON(t, c)
	if m["errCode"] != float64(401) {
		t.Fatalf("expected errCode 401, got %#v", m)
	}
	if fake.wsHits != 0 {
		t.Fatal("expired token must not be forwarded to OpenIM WS")
	}
}

func TestWSInvalidTokenRejectedAfterUpgrade(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	h := http.Header{}
	h.Set("token", "not-a-token")
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, ""), h)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	m := readWSJSON(t, c)
	if m["errCode"] != float64(401) {
		t.Fatalf("expected errCode 401, got %#v", m)
	}
	if fake.wsHits != 0 {
		t.Fatal("invalid token must not be forwarded to OpenIM WS")
	}
}

func TestWSRejectsSendIDNotMatchingToken(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	h := http.Header{}
	h.Set("Authorization", "Bearer user-alice")
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, "sendID=bob"), h)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	m := readWSJSON(t, c)
	if m["errCode"] != float64(403) {
		t.Fatalf("expected errCode 403, got %#v", m)
	}
	if fake.wsHits != 0 {
		t.Fatal("mismatched sendID must not be forwarded to OpenIM WS")
	}
}

func TestWSHeaderTokenHandshakeForwardsMatchingSendID(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	h := http.Header{}
	h.Set("Authorization", "Bearer user-alice")
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, "sendID=alice"), h)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		fake.mu.Lock()
		hits := fake.wsHits
		fake.mu.Unlock()
		if hits > 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	fake.mu.Lock()
	hits := fake.wsHits
	fake.mu.Unlock()
	if hits == 0 {
		t.Fatal("expected upstream OpenIM WS dial")
	}
	got := fake.last("WS")
	if got == nil {
		t.Fatal("expected recorded upstream WS handshake")
	}
	if got.Token != "user-alice" {
		t.Fatalf("upstream token=%q", got.Token)
	}
	if got.Body["sendID"] != "alice" {
		t.Fatalf("upstream sendID=%#v", got.Body["sendID"])
	}
	_ = c.Close()
}

func TestWSAuthFrameHandshake(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, ""), nil)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	if err := c.WriteJSON(map[string]string{"type": "auth", "token": "user-bob"}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		fake.mu.Lock()
		hits := fake.wsHits
		fake.mu.Unlock()
		if hits > 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	got := fake.last("WS")
	if got == nil || got.Token != "user-bob" || got.Body["sendID"] != "bob" {
		t.Fatalf("expected upstream WS as bob, got %+v", got)
	}
}

func TestWSAuthFrameRejectsNonAuthMessage(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	c, resp, err := websocket.DefaultDialer.Dial(wsURL(gw.URL, ""), nil)
	if err != nil {
		t.Fatalf("dial: %v resp=%v", err, resp)
	}
	defer c.Close()
	if err := c.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatal(err)
	}
	m := readWSJSON(t, c)
	if m["errCode"] != float64(401) {
		t.Fatalf("expected errCode 401, got %#v", m)
	}
	if fake.wsHits != 0 {
		t.Fatal("non-auth first frame must not reach OpenIM WS")
	}
}


func TestSendForwardsNotPeersFriendWhenFriendVerifyOn(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	// alice and carol are not friends. Admin token must not skip this check:
	// OpenIM messageVerification uses MsgData.SendID, not the caller identity.
	code, env, _ := postAuth(t, gw.URL+"/v1/messages/send", "user-alice",
		`{"recvID":"carol","text":"hi","clientMsgID":"c-nf"}`)
	if code != 200 || env.ErrCode != openIMNotPeersFriend {
		t.Fatalf("expected HTTP 200 with errCode 1303, got %d %+v", code, env)
	}
	got := fake.last("/msg/send_msg")
	if got == nil {
		t.Fatal("send should still reach OpenIM send_msg so friendVerify can run")
	}
	if got.Token != "admin-token" {
		t.Fatalf("gateway uses admin token, got %q", got.Token)
	}
	if got.Body["sendID"] != "alice" {
		t.Fatalf("friendVerify identity must be token subject sendID, got %#v", got.Body["sendID"])
	}
}

func TestSendSucceedsWhenFriendVerifyFindsFriendship(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	fake.friends.add("alice", "bob")
	code, env, _ := postAuth(t, gw.URL+"/v1/messages/send", "user-alice",
		`{"recvID":"bob","text":"hi","clientMsgID":"c-ok"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("friends should be able to send, got %d %+v", code, env)
	}
	got := fake.last("/msg/send_msg")
	if got == nil || got.Body["sendID"] != "alice" || got.Body["recvID"] != "bob" {
		t.Fatalf("upstream send %+v", got)
	}
}

func TestSendDoesNotTreatAdminTokenAsFriendVerifySkip(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	// Body tries to claim sendID=imAdmin, which would skip friendVerify in OpenIM.
	// Gateway must force sendID to alice, so the skip must not apply.
	code, env, _ := postAuth(t, gw.URL+"/v1/messages/send", "user-alice",
		`{"sendID":"imAdmin","recvID":"carol","text":"hi"}`)
	if env.ErrCode != openIMNotPeersFriend {
		t.Fatalf("expected 1303 after sendID rewrite, got %d %+v", code, env)
	}
	got := fake.last("/msg/send_msg")
	if got == nil || got.Body["sendID"] != "alice" {
		t.Fatalf("sendID must not remain imAdmin, got %+v", got)
	}
}

func TestIMProxyBlocksNormalizedBypassPaths(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	paths := []string{
		"/im/friend/import_friend",
		"/im/friend/import_friend/",
		"/im//friend/import_friend",
		"/im/./friend/import_friend",
		"/im/foo/../friend/import_friend",
		"/im/../friend/import_friend",
		"/im/friend/import_friend%2fextra",
		"/im/friend/%2e%2e/import_friend",
	}
	for _, path := range paths {
		req, err := http.NewRequest(http.MethodPost, gw.URL+path, strings.NewReader(`{}`))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		// Requests that still match /im/* must be 403. A path like
		// /im/../friend/import_friend may be cleaned by ServeMux to a
		// non-proxy route and 404; that is acceptable only if it never
		// reaches OpenIM (checked below).
		if resp.StatusCode != http.StatusForbidden && resp.StatusCode != http.StatusNotFound {
			t.Errorf("%s: expected 403 or 404, got %d %s", path, resp.StatusCode, raw)
		}
		if strings.Contains(path, "/im/../") && resp.StatusCode == http.StatusNotFound {
			continue
		}
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s: expected 403, got %d %s", path, resp.StatusCode, raw)
		}
	}
	for _, p := range fake.paths() {
		if p == "/friend/import_friend" || strings.HasSuffix(p, "import_friend") {
			t.Errorf("blocked import_friend reached upstream as %s", p)
		}
	}
}

func TestChatProxyBlocksAdminAndCallbackPaths(t *testing.T) {
	gw, fake := newRecordingGateway(t)
	paths := []string{
		"/chat/callback/open_im",
		"/chat/account/add_admin",
		"/chat/account/add_user",
		"/chat/user/import/json",
		"/chat/user/password/reset",
		"/chat/config/get_config",
		"/chat/restart",
		"/chat/block/add",
		"/chat//account/add_admin",
		"/chat/foo/../account/add_admin",
	}
	for _, path := range paths {
		req, _ := http.NewRequest(http.MethodPost, gw.URL+path, strings.NewReader(`{}`))
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s: expected 403, got %d %s", path, resp.StatusCode, raw)
		}
	}
	// User login on the Chat API must remain reachable.
	req, _ := http.NewRequest(http.MethodPost, gw.URL+"/chat/account/login", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusForbidden {
		t.Fatal("user /chat/account/login must not be blocked")
	}
	_ = fake
}

func TestChatProxyAllowsClientConfigGetButBlocksSet(t *testing.T) {
	gw, _ := newRecordingGateway(t)
	req, _ := http.NewRequest(http.MethodPost, gw.URL+"/chat/client_config/set", strings.NewReader(`{}`))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("set should be forbidden, got %d", resp.StatusCode)
	}
	req, _ = http.NewRequest(http.MethodPost, gw.URL+"/chat/client_config/get", strings.NewReader(`{}`))
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusForbidden {
		t.Fatal("user client_config/get must not be blocked")
	}
}
