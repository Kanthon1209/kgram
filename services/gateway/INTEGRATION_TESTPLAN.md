# OpenIM compatibility tests

## Ran this round

- Gateway unit/contract tests with a **fake** OpenIM that encodes
  `messageVerification` / `RespondFriendApply` from local OpenIM Server
  **v3.8.3-patch.16** (same `SendID`-based friendVerify check is in the
  verified v3.8.3-patch.18 tree).
- These tests do **not** start a live OpenIM process. Docker was unavailable
  on the box, and the demo host `8.136.150.149` must not be used.

## Not run this round

- Real OpenIM Server + Chat in Docker or any other isolated compose stack.
- Any request to the demo ECS or production data.

## Must hold when a real isolated stack is available

- Server `friendVerify` is **on**. Official OpenIM default in
  `config/openim-rpc-msg.yml` is `false`; kgram requires it enabled.
- Admin-token `POST /msg/send_msg` still friend-checks `MsgData.SendID`
  against `RecvID` (not the admin caller). Skip only if `SendID` is an
  IM admin user ID. Expect `errCode` 1303 `NotPeersFriend` otherwise.
- `handleResult` is only `1` (agree) or `-1` (refuse). `0` / omitted / other
  values must fail, not default to agree.
- No silent `/friend/import_friend`.
- Message, read-receipt, and conversation updates are event/WS driven, not polling.
- The Android client does not connect to official Telegram DCs (client work, later).
