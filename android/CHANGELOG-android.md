## v0.1.14-sysnotify-fgs-debug
- Root-cause fix: markDialogAsRead no longer re-arms watchedDialogs after Home (was suppressing shade)
- KgramPushService FGS (dataSync) keeps OpenIM WS alive in background; quiet keepalive notification
- Request POST_NOTIFICATIONS on OpenIM session activate; messages channel `kgram_messages_v2` HIGH
- Background ForegroundDetector bypass so Home'd chat still notifies
- Verify: Home app → peer sends → system shade; reopen chat cancels

APK: https://github.com/Kanthon1209/kgram/releases/tag/kgram-apk-v0.1.14-sysnotify-fgs-debug


## v0.1.11-auth-ux-debug
- Phone → password login directly (no 登录/注册 dialog)
- Register secondary; clearer CTAs for accountNotFound / alreadyRegistered
- ConnectionsManager blocks Telegram official DC traffic when USE_OPENIM_AUTH
- Parse nickname from OpenIM login/register when present
# Android adapter changelog

## v0.1.10
- Send dedupe by clientMsgID / serverMsgID / seq (optimistic + WS)
- Outbox double-check only from peer hasReadSeq receipts
- ChatActivity watch/unwatch so unread badges work when peer not in chat
- Dialog unread_count from OpenIM conversation list

APK: https://github.com/Kanthon1209/kgram/releases/tag/kgram-apk-v0.1.10-dedupe-unread-debug

## v0.1.13-sysnotify-online-debug
- System notification shade for inbound OpenIM WS messages (channel `kgram_messages`, tap opens chat)
- Suppress notify when that chat is open / message is outbound; cancel on open
- OpenIM mode: ConnectionsManager reports Connected so UI no longer shows「等待连接网络」while MTProto stays blocked

## v0.1.15 — kill false 「等待网络连接」 on chat UI (2026-11-03)

Root causes:
- ChatAvatarContainer subtitle (`updateCurrentConnectionState`) and LaunchActivity/DialogsActionBar title overlay still painted Waiting/Connecting while native MTProto was paused.
- Marking native network unavailable (`native_setNetworkAvailable(false)`) flooded WaitingForNetwork callbacks.

Fix:
- OpenIM mode: never show Waiting/Connecting/Updating overlays (ChatAvatarContainer, LaunchActivity, DialogsActivity, ProfileActivity, ChatActivity).
- ConnectionsManager: pause MTProto only; force Java `connectionState=Connected`; `forceOpenImUiConnected()`; ignore `setIsUpdating`.
- MTProto `sendRequest` still dropped (`KGRAM_OPENIM_ONLY`).

## v0.1.16 (sysnotify-fix2)
- Fix shade notifications: permission retry, bg watch clear, WS worker thread, channel v3, BOOT FGS
- APK: kgram-beta-sysnotify-fix2-debug.apk

## v0.1.18-read-cache-debug (2026-11-03 14:10 CST)
- Fix OpenIM MsgData protobuf field map (seq was off-by-one) so WS payloads decode correctly.
- Harden hasReadSeq receipt parse (n_/si_, detail/jsonDetail, markAsReadUserID peer fallback).
- Apply peer outbox ticks immediately via WS + one-shot server refresh on receipt/chat visible (no polling).
- Lightweight local message cache (last ~120/conv) so reopen chat is not empty.


## v0.1.19 — history shrink / pull-to-cloud (2026-11-03 Asia/Shanghai)
- Root cause: cache restore stole ChatActivity `waitingForLoad` so OpenIM REST `processLoadedMessages` was dropped; `KgramMessageCache.save` overwrote fuller cache with a shorter latest window.
- Fix: mergeAndSave only; restore only on initial fromCache; paginated `searchHistory(beforeSeq,limit)`; ChatActivity accepts cloud history after cache steal; isEnd only at seq<=1.
