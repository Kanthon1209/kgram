# System notifications fix (v0.1.16)

## Why v0.1.14 still showed nothing
1. `KgramNotifications.permissionAsked` became sticky `true` even when the system dialog never appeared
   (`LaunchActivity.instance` null / PermissionRequest no-op). Later `notifyIncoming` hit `canPost=false`
   and `requestPermissionIfNeeded()` was a no-op forever.
2. `watchedDialogs` could stay true across Home (resume/pause races). Inject gate then skipped shade.
3. WS ping/reconnect ran on the **main looper**. Some OEMs freeze UI handlers in background even with FGS,
   so keepalive died and `handleWsPush` never fired.
4. Boot path only restarted TG `NotificationsService`, not `KgramPushService`.
5. Channel `kgram_messages_v2` may have been created muted on some devices; Android refuses to raise
   importance afterward.

## What v0.1.16 changes
- New channel `kgram_messages_v3` (IMPORTANCE_HIGH); delete v1/v2 leftovers.
- Permission ask retries every 20s until granted; posts a self-test shade on grant.
- `ForegroundDetector` listener clears **all** watches on background; ignores `watch=true` while bg.
- Inject gate: if app backgrounded → always notify. Dual-path also calls `NotificationsController.processNewMessages`.
- `OpenIMWebSocket` ping/reconnect on dedicated `HandlerThread`.
- `AppStartReceiver` BOOT_COMPLETED starts `KgramPushService` when OpenIM session exists.
- DialogsActivity.onResume re-nudges permission + FGS.
- Hard `Log.e("kgram-notify"/"kgram-ws"/"kgram-fgs")` breadcrumbs (always in logcat).

## Verify
1. Install, login, allow notifications. Expect brief "通知已开启" test shade.
2. Quiet "消息连接运行中" FGS should stay.
3. Open chat → Home. Other account sends text → shade heads-up with peer + text.
4. `adb logcat -s kgram-notify:E kgram-ws:E kgram-fgs:E`
