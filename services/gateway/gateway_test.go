package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestIsSingleConversationID(t *testing.T) {
	if !isSingleConversationID("alice", "bob", "si_alice_bob") {
		t.Fatal("expected sorted si_ id to match")
	}
	if !isSingleConversationID("bob", "alice", "si_alice_bob") {
		t.Fatal("order of me/peer must not matter")
	}
	if isSingleConversationID("alice", "bob", "si_alice_eve") {
		t.Fatal("foreign conversation must be rejected")
	}
	if isSingleConversationID("alice", "alice", "si_alice_alice") {
		t.Fatal("self conversation must be rejected")
	}
}

func TestIsBlockedIMPath(t *testing.T) {
	blocked := []string{
		"/auth/get_admin_token",
		"/auth/get_user_token",
		"/friend/import_friend",
		"/user/get_users",
		"/user/get_all_users_uid",
		"/msg/send_msg",
		"/msg/batch_send_msg",
		"/msg/send_business_notification",
	}
	for _, p := range blocked {
		if !isBlockedIMPath(p) {
			t.Errorf("expected blocked: %s", p)
		}
	}
	allowed := []string{
		"/friend/get_friend_list",
		"/friend/add_friend",
		"/conversation/get_sorted_conversation_list",
		"/msg/get_conversations_has_read_and_max_seq",
		"/user/get_users_info",
		"/auth/parse_token",
	}
	for _, p := range allowed {
		if isBlockedIMPath(p) {
			t.Errorf("should allow user-scoped path: %s", p)
		}
	}
}

func TestBearerOrToken(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/", nil)
	r.Header.Set("Authorization", "Bearer abc.def")
	if got := bearerOrToken(r); got != "abc.def" {
		t.Fatalf("bearer: %q", got)
	}
	r = httptest.NewRequest(http.MethodPost, "/", nil)
	r.Header.Set("token", "from-token-header")
	if got := bearerOrToken(r); got != "from-token-header" {
		t.Fatalf("token header: %q", got)
	}
}

func newTestGateway(t *testing.T, openim http.Handler) *httptest.Server {
	t.Helper()
	up := httptest.NewServer(openim)
	t.Cleanup(up.Close)
	cfg := Config{
		OpenIMAPIURL:    up.URL,
		OpenIMChatURL:   up.URL,
		OpenIMWSURL:     "ws" + strings.TrimPrefix(up.URL, "http"),
		OpenIMMinioURL:  up.URL,
		OpenIMSecret:    "unit-test-secret",
		OpenIMAdminUser: "imAdmin",
	}
	s, err := newServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	hs := httptest.NewServer(s.routes())
	t.Cleanup(hs.Close)
	return hs
}

func fakeOpenIM(t *testing.T) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/auth/parse_token":
			var body struct {
				Token string `json:"token"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			switch body.Token {
			case "user-alice":
				_, _ = w.Write([]byte(`{"errCode":0,"data":{"userID":"alice","platformID":2}}`))
			case "user-bob":
				_, _ = w.Write([]byte(`{"errCode":0,"data":{"userID":"bob","platformID":2}}`))
			default:
				_, _ = w.Write([]byte(`{"errCode":1501,"errMsg":"invalid token"}`))
			}
		case "/auth/get_admin_token":
			var body struct {
				Secret string `json:"secret"`
				UserID string `json:"userID"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body.Secret != "unit-test-secret" {
				_, _ = w.Write([]byte(`{"errCode":1001,"errMsg":"bad secret"}`))
				return
			}
			_, _ = w.Write([]byte(`{"errCode":0,"data":{"token":"admin-token"}}`))
		case "/msg/send_msg":
			if r.Header.Get("token") != "admin-token" {
				_, _ = w.Write([]byte(`{"errCode":403,"errMsg":"only app manager can send message"}`))
				return
			}
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			b, _ := json.Marshal(map[string]any{"errCode": 0, "data": body})
			_, _ = w.Write(b)
		case "/friend/add_friend":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			b, _ := json.Marshal(map[string]any{"errCode": 0, "data": body})
			_, _ = w.Write(b)
		case "/msg/get_conversations_has_read_and_max_seq":
			_, _ = w.Write([]byte(`{"errCode":0,"data":{"seqs":{"si_alice_bob":{"hasReadSeq":9,"maxSeq":10}}}}`))
		default:
			http.NotFound(w, r)
		}
	})
}

func postJSON(t *testing.T, url, token, body string) (int, openIMEnvelope) {
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
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("decode %s: %v", raw, err)
	}
	return resp.StatusCode, env
}

func TestSendTextForcesAuthenticatedSendID(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	code, env := postJSON(t, gw.URL+"/v1/messages/send", "user-alice",
		`{"sendID":"bob","recvID":"carol","text":"hi","clientMsgID":"c1"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("unexpected response %d %+v", code, env)
	}
	var data map[string]any
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data["sendID"] != "alice" {
		t.Fatalf("sendID must be forced to token subject, got %#v", data["sendID"])
	}
	if data["recvID"] != "carol" {
		t.Fatalf("recvID: %#v", data["recvID"])
	}
}

func TestSendTextRequiresToken(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	code, env := postJSON(t, gw.URL+"/v1/messages/send", "", `{"recvID":"bob","text":"hi"}`)
	if code != http.StatusUnauthorized || env.ErrCode != 401 {
		t.Fatalf("expected 401, got %d %+v", code, env)
	}
}

func TestFriendRequestForcesFromUser(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	code, env := postJSON(t, gw.URL+"/v1/friends/request", "user-alice",
		`{"fromUserID":"mallory","toUserID":"bob","reqMsg":"hi"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("unexpected %d %+v", code, env)
	}
	var data map[string]any
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatal(err)
	}
	if data["fromUserID"] != "alice" {
		t.Fatalf("fromUserID must be token subject, got %#v", data["fromUserID"])
	}
}

func TestPeerReadRejectsForeignConversation(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	code, env := postJSON(t, gw.URL+"/v1/conversations/peer-read", "user-alice",
		`{"peerUserID":"bob","conversationID":"si_bob_carol"}`)
	if code != http.StatusForbidden || env.ErrCode != 403 {
		t.Fatalf("expected forbid, got %d %+v", code, env)
	}
}

func TestPeerReadAllowsParticipant(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	code, env := postJSON(t, gw.URL+"/v1/conversations/peer-read", "user-alice",
		`{"peerUserID":"bob","conversationID":"si_alice_bob"}`)
	if code != 200 || env.ErrCode != 0 {
		t.Fatalf("unexpected %d %+v", code, env)
	}
}

func TestIMProxyBlocksAdminAPIs(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	for _, path := range []string{
		"/im/auth/get_admin_token",
		"/im/friend/import_friend",
		"/im/user/get_users",
		"/im/msg/send_msg",
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

func TestWSRejectsTokenInQuery(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	resp, err := http.Post(gw.URL+"/v1/ws?token=user-alice&sendID=alice", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for token in URL, got %d", resp.StatusCode)
	}
}

func TestHealth(t *testing.T) {
	gw := newTestGateway(t, fakeOpenIM(t))
	resp, err := http.Get(gw.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("health %d", resp.StatusCode)
	}
}
