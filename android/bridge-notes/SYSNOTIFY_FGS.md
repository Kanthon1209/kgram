# System notifications + FGS keep-alive (v0.1.14)

## Root causes (why v0.1.13 never showed shade alerts)
1. `KgramTgBridge.markDialogAsReadOpenIM` always called `watchDialogForReadReceipts(dialogId, true)`.
   `ChatActivity.onPause` sets watch=false then immediately `markDialogAsReadNow` → re-arms watch=true,
   so after Home the chat stays "open" and `notifyIncoming` is skipped (`KgramTgBridge` inject ~1107).
2. No process keep-alive: OpenIM WS lived only in the UI process. Background/Doze killed or froze WS,
   so `handleWsPush` never ran → `notifyIncoming` never called.
3. API 33+: `canPost()` required `POST_NOTIFICATIONS`, but OpenIM activate path never requested it
   (DialogsActivity ask can be deferred / askNotificationsAfter=-2).

## Fixes
- Remove watch=true from markDialogAsRead / send / loadMessages; only ChatActivity onResume/onPause owns watch.
- Inject path: if app backgrounded (`ForegroundDetector`), treat as not-open and notify anyway.
- `KgramPushService` foreground dataSync service keeps WS alive; low-importance persistent channel `kgram_push_keepalive`.
- `KgramNotifications.requestPermissionIfNeeded()` on session activate; channel bumped to `kgram_messages_v2` HIGH.
- Manifest registers `org.telegram.messenger.kgram.KgramPushService`.

## Verify
1. Install APK, login, accept notification permission prompt.
2. Confirm status bar shows quiet "消息连接运行中" (keep-alive).
3. Open a chat, press Home (leave app). From another account send a text.
4. Expect heads-up / shade notification with peer name + text; tap returns to app.
5. Re-open that chat → notification cancels; while chat visible no new shade for that peer.
