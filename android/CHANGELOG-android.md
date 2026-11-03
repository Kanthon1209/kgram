
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
