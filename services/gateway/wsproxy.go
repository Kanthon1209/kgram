package main

import (
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const wsAuthTimeout = 10 * time.Second

type wsAuthFrame struct {
	Type  string `json:"type"`
	Token string `json:"token"`
}

var upgrader = websocket.Upgrader{
	// Android / native clients do not send a browser Origin. Restricting
	// by Origin is not useful here; auth is the user IM token.
	CheckOrigin:       func(r *http.Request) bool { return true },
	ReadBufferSize:    32 << 10,
	WriteBufferSize:   32 << 10,
	EnableCompression: false,
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	token := bearerOrToken(r)
	// Connection metadata may stay in the query string. The user token must not.
	if r.URL.Query().Get("token") != "" {
		writeErr(w, http.StatusBadRequest, 1001, "token must not be passed in the WebSocket URL")
		return
	}

	clientWS, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("ws upgrade: %v", err)
		return
	}
	defer clientWS.Close()

	if token == "" {
		_ = clientWS.SetReadDeadline(time.Now().Add(wsAuthTimeout))
		_, data, err := clientWS.ReadMessage()
		_ = clientWS.SetReadDeadline(time.Time{})
		if err != nil {
			_ = clientWS.WriteJSON(map[string]any{"errCode": 401, "errMsg": "auth timeout"})
			return
		}
		var frame wsAuthFrame
		if json.Unmarshal(data, &frame) != nil || !strings.EqualFold(frame.Type, "auth") || strings.TrimSpace(frame.Token) == "" {
			_ = clientWS.WriteJSON(map[string]any{"errCode": 401, "errMsg": "expected auth frame"})
			return
		}
		token = strings.TrimSpace(frame.Token)
	}

	claims, err := s.openim.parseUserToken(token)
	if err != nil || claims.UserID == "" {
		_ = clientWS.WriteJSON(map[string]any{"errCode": 401, "errMsg": "invalid user token"})
		return
	}
	sendID := r.URL.Query().Get("sendID")
	if sendID == "" {
		sendID = r.Header.Get("sendID")
	}
	if sendID == "" {
		sendID = claims.UserID
	}
	if sendID != claims.UserID {
		_ = clientWS.WriteJSON(map[string]any{"errCode": 403, "errMsg": "sendID does not match token subject"})
		return
	}

	upstream, err := s.dialOpenIMWS(r, token, sendID)
	if err != nil {
		log.Printf("ws dial openim: %v", err)
		_ = clientWS.WriteJSON(map[string]any{"errCode": -1, "errMsg": "upstream websocket failed"})
		return
	}
	defer upstream.Close()

	errc := make(chan struct{}, 2)
	go proxyWS(clientWS, upstream, errc)
	go proxyWS(upstream, clientWS, errc)
	<-errc
}

func (s *server) dialOpenIMWS(clientReq *http.Request, token, sendID string) (*websocket.Conn, error) {
	u, err := url.Parse(s.cfg.OpenIMWSURL)
	if err != nil {
		return nil, err
	}
	q := clientReq.URL.Query()
	q.Del("token")
	q.Set("sendID", sendID)
	q.Set("token", token)
	if q.Get("platformID") == "" {
		if p := clientReq.Header.Get("platformID"); p != "" {
			q.Set("platformID", p)
		} else {
			q.Set("platformID", "2")
		}
	}
	if q.Get("sdkType") == "" {
		q.Set("sdkType", "js")
	}
	if q.Get("operationID") == "" {
		q.Set("operationID", "kgram-ws-"+sendID)
	}
	if q.Get("isBackground") == "" {
		q.Set("isBackground", "false")
	}
	if q.Get("isMsgResp") == "" {
		q.Set("isMsgResp", "true")
	}
	u.RawQuery = q.Encode()
	// Token is only placed on the server-side upstream URL, never on the client URL.
	dialer := websocket.Dialer{HandshakeTimeout: 12 * time.Second}
	conn, _, err := dialer.Dial(u.String(), nil)
	return conn, err
}

func proxyWS(src, dst *websocket.Conn, done chan struct{}) {
	defer func() { done <- struct{}{} }()
	for {
		typ, data, err := src.ReadMessage()
		if err != nil {
			_ = dst.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
				time.Now().Add(time.Second))
			return
		}
		_ = dst.SetWriteDeadline(time.Now().Add(15 * time.Second))
		if err := dst.WriteMessage(typ, data); err != nil {
			return
		}
	}
}
