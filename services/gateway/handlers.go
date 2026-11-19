package main

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/google/uuid"
)

type sendTextRequest struct {
	RecvID         string `json:"recvID"`
	SenderNickname string `json:"senderNickname"`
	Text           string `json:"text"`
	ClientMsgID    string `json:"clientMsgID"`
	// SendID, if present, is ignored. The authenticated token subject is always used.
	SendID string `json:"sendID"`
}

type friendRequestBody struct {
	ToUserID   string `json:"toUserID"`
	ReqMsg     string `json:"reqMsg"`
	FromUserID string `json:"fromUserID"` // ignored; forced to token subject
}

type friendRespondBody struct {
	FromUserID   string `json:"fromUserID"`
	HandleResult int    `json:"handleResult"`
	HandleMsg    string `json:"handleMsg"`
	ToUserID     string `json:"toUserID"` // ignored; forced to token subject
}

type peerReadRequest struct {
	PeerUserID     string `json:"peerUserID"`
	ConversationID string `json:"conversationID"`
}

func (s *server) handleSendText(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, 405, "method not allowed")
		return
	}
	claims := s.requireUser(w, r)
	if claims == nil {
		return
	}
	var req sendTextRequest
	if err := ioLimit(r).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, 1001, "invalid json")
		return
	}
	req.RecvID = strings.TrimSpace(req.RecvID)
	req.Text = strings.TrimSpace(req.Text)
	if req.RecvID == "" || req.Text == "" {
		writeErr(w, http.StatusBadRequest, 1001, "recvID and text are required")
		return
	}
	if req.RecvID == claims.UserID {
		writeErr(w, http.StatusBadRequest, 1001, "cannot message yourself")
		return
	}
	if req.ClientMsgID == "" {
		req.ClientMsgID = uuid.NewString()
	}
	nick := req.SenderNickname
	if nick == "" {
		nick = claims.UserID
	}
	// sendID is always the authenticated user. A body sendID claiming another user is dropped.
	env, err := s.openim.sendTextAsUser(claims.UserID, req.RecvID, nick, req.Text, req.ClientMsgID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, -1, "upstream send failed")
		return
	}
	if env.Data == nil || string(env.Data) == "null" {
		env.Data = json.RawMessage(`{"clientMsgID":"` + req.ClientMsgID + `"}`)
	} else if !strings.Contains(string(env.Data), "clientMsgID") {
		var m map[string]any
		if json.Unmarshal(env.Data, &m) == nil {
			m["clientMsgID"] = req.ClientMsgID
			if b, err := json.Marshal(m); err == nil {
				env.Data = b
			}
		}
	}
	writeOpenIMJSON(w, env)
}

func (s *server) handleFriendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, 405, "method not allowed")
		return
	}
	token := bearerOrToken(r)
	claims := s.requireUser(w, r)
	if claims == nil {
		return
	}
	var req friendRequestBody
	if err := ioLimit(r).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, 1001, "invalid json")
		return
	}
	to := strings.TrimSpace(req.ToUserID)
	if to == "" {
		writeErr(w, http.StatusBadRequest, 1001, "toUserID is required")
		return
	}
	if to == claims.UserID {
		writeErr(w, http.StatusBadRequest, 1001, "cannot add yourself")
		return
	}
	// Official user-token API. fromUserID is forced to the token subject.
	env, err := s.openim.addFriend(token, claims.UserID, to, req.ReqMsg)
	if err != nil {
		writeErr(w, http.StatusBadGateway, -1, "upstream friend request failed")
		return
	}
	if env.ErrCode == openIMAlreadyFriends {
		env.ErrCode = 0
		env.ErrMsg = ""
		env.ErrDlt = ""
	}
	writeOpenIMJSON(w, env)
}

func (s *server) handleFriendRespond(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, 405, "method not allowed")
		return
	}
	token := bearerOrToken(r)
	claims := s.requireUser(w, r)
	if claims == nil {
		return
	}
	var req friendRespondBody
	if err := ioLimit(r).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, 1001, "invalid json")
		return
	}
	from := strings.TrimSpace(req.FromUserID)
	if from == "" {
		writeErr(w, http.StatusBadRequest, 1001, "fromUserID is required")
		return
	}
	if req.HandleResult == 0 {
		req.HandleResult = 1 // default: agree
	}
	env, err := s.openim.respondFriend(token, from, claims.UserID, req.HandleResult, req.HandleMsg)
	if err != nil {
		writeErr(w, http.StatusBadGateway, -1, "upstream friend respond failed")
		return
	}
	writeOpenIMJSON(w, env)
}

func (s *server) handlePeerRead(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, 405, "method not allowed")
		return
	}
	claims := s.requireUser(w, r)
	if claims == nil {
		return
	}
	var req peerReadRequest
	if err := ioLimit(r).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, 1001, "invalid json")
		return
	}
	peer := strings.TrimSpace(req.PeerUserID)
	cid := strings.TrimSpace(req.ConversationID)
	if !isSingleConversationID(claims.UserID, peer, cid) {
		writeErr(w, http.StatusForbidden, 403, "not a participant of this conversation")
		return
	}
	env, err := s.openim.peerHasReadAndMaxSeq(peer, cid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, -1, "upstream peer-read failed")
		return
	}
	writeOpenIMJSON(w, env)
}

func ioLimit(r *http.Request) *json.Decoder {
	return json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
}
