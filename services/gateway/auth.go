package main

import (
	"net/http"
	"strings"
)

func bearerOrToken(r *http.Request) string {
	if t := strings.TrimSpace(r.Header.Get("token")); t != "" {
		return t
	}
	if t := strings.TrimSpace(r.Header.Get("X-Token")); t != "" {
		return t
	}
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if auth == "" {
		return ""
	}
	if len(auth) >= 7 && strings.EqualFold(auth[:7], "Bearer ") {
		return strings.TrimSpace(auth[7:])
	}
	if !strings.Contains(auth, " ") {
		return auth
	}
	return ""
}

func (s *server) requireUser(w http.ResponseWriter, r *http.Request) *parsedToken {
	token := bearerOrToken(r)
	if token == "" {
		writeErr(w, http.StatusUnauthorized, 401, "missing user token")
		return nil
	}
	claims, err := s.openim.parseUserToken(token)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, 401, "invalid user token")
		return nil
	}
	return claims
}

func isSingleConversationID(me, peer, conversationID string) bool {
	if me == "" || peer == "" || conversationID == "" || me == peer {
		return false
	}
	a, b := me, peer
	if a > b {
		a, b = b, a
	}
	return conversationID == "si_"+a+"_"+b
}
