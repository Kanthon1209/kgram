package main

import (
	"errors"
	"testing"
)

// Contract encoded from local OpenIM Server v3.8.3-patch.16
// (the same check appears in the verified v3.8.3-patch.18 tree):
//
//   internal/rpc/msg/verify.go messageVerification, SingleChatType:
//     skip friend/black checks only if MsgData.SendID is in IMAdminUserID
//     otherwise, if FriendVerify, IsFriend(SendID, RecvID); if not, ErrNotPeersFriend (1303)
//   internal/api/msg.go SendMessage:
//     HTTP /msg/send_msg requires an app-manager token, then still calls SendMsg -> messageVerification
//   internal/rpc/relation/friend.go RespondFriendApply:
//     rejects handleResult other than 1 / -1 with "req.HandleResult != -1/1"
//
// Official default friendVerify is false (config/openim-rpc-msg.yml). kgram requires it on.
// These tests do not start or call a live OpenIM process.

var errNotPeersFriend = errors.New("NotPeersFriend")

func openIMSingleChatFriendVerify(friendVerify bool, adminUser, sendID, recvID string, isFriend func(sendID, recvID string) bool) error {
	if sendID == "" || recvID == "" {
		return errors.New("missing sendID or recvID")
	}
	if adminUser != "" && sendID == adminUser {
		return nil
	}
	if !friendVerify {
		return nil
	}
	if isFriend != nil && isFriend(sendID, recvID) {
		return nil
	}
	return errNotPeersFriend
}

func TestOpenIMContractFriendVerifyUsesMessageSendID(t *testing.T) {
	friends := func(a, b string) bool { return a == "alice" && b == "bob" }

	// Admin caller token is not an argument. Only SendID == admin user skips the check.
	if err := openIMSingleChatFriendVerify(true, "imAdmin", "alice", "carol", friends); err != errNotPeersFriend {
		t.Fatalf("non-friend SendID must be rejected even if the HTTP caller is admin, got %v", err)
	}
	if err := openIMSingleChatFriendVerify(true, "imAdmin", "alice", "bob", friends); err != nil {
		t.Fatalf("friends must pass: %v", err)
	}
	if err := openIMSingleChatFriendVerify(true, "imAdmin", "imAdmin", "carol", friends); err != nil {
		t.Fatalf("SendID=imAdmin skips friendVerify: %v", err)
	}
	if err := openIMSingleChatFriendVerify(false, "imAdmin", "alice", "carol", friends); err != nil {
		t.Fatalf("friendVerify off skips the check: %v", err)
	}
}

func TestOpenIMContractHandleResultValues(t *testing.T) {
	if !validFriendHandleResult(openIMFriendResponseAgree) || !validFriendHandleResult(1) {
		t.Fatal("agree=1 must be valid")
	}
	if !validFriendHandleResult(openIMFriendResponseRefuse) || !validFriendHandleResult(-1) {
		t.Fatal("refuse=-1 must be valid")
	}
	for _, v := range []int{0, 2, 99, -2} {
		if validFriendHandleResult(v) {
			t.Errorf("handleResult %d must be invalid", v)
		}
	}
}
