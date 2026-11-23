# OpenIM compatibility tests (planned, not run this round)

These cases need a **separate** OpenIM test environment with `friendVerify` enabled.
Do not run them against the demo server or production data.

## Must hold

- `friendVerify` is on.
- The client and gateway never silently call `/friend/import_friend`.
- Message, read-receipt, and conversation updates are event/WS driven, not polling.
- The Android client does not connect to official Telegram DCs.

## First batch (after this gateway unit-test baseline)

1. Auth: user A's token cannot send, read, or answer friend requests as B; expired/invalid tokens are rejected.
2. Friends: A cannot message B before B accepts; apply / reject / accept then send; history and chat open do not auto-import a friend.
3. Send/history: the same `clientMsgID` appears once; history pagination does not drop or duplicate; messages sent while disconnected are recovered after reconnect.
4. Read cursor: hasReadSeq does not move backwards on duplicate or out-of-order notices; a user cannot read another conversation's cursor.
5. Account isolation and connection state are client concerns, covered in later Android checks (no full app build in this step).

Gateway unit tests with a fake OpenIM cannot prove friendVerify: `/v1/messages/send` currently uses the admin `/msg/send_msg` API after rewriting `sendID`. That behavior must be checked against a real OpenIM with friendVerify on.
