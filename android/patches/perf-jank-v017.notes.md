# kgram v0.1.17 performance / no-poll (UI jank)

## Root causes (file:line at fix time)
1. `KgramTgBridge.sendTextOpenIM` — every send called `loadDialogsOpenIM` (full HTTP + `kgramReplaceAllDialogs` + `dialogsNeedReload`).
2. `KgramTgBridge.handleWsPush` — WS push re-pulled history via `searchHistoryAsync` after inject (double work).
3. `injectNewMessages` — posted `dialogsNeedReload` + `kgramReplaceAllDialogs` per message.
4. `DialogsActivity.onResume` — force-reloaded OpenIM dialogs on every chat↔list navigation.
5. `OpenIMWebSocket.handlePayload` — delivered push onto main thread; parse/group + inject + notify all on UI.
6. Hot-path `Log.e` / `dumpState` / dual `NotificationsController.processNewMessages`.
7. `OpenIMImClient` unbounded `newCachedThreadPool`.
8. Legacy `CHAT_POLL_MS` / `KgramChatActivity` poll Handler (disabled/removed).

## Fixes
- Realtime = OpenIM WS only. REST only: login warm, open-chat history, pull-to-refresh, send, mark-read.
- WS decode+group on worker; UI hop only for TG model apply.
- Shade notify deferred to `Utilities.globalQueue`.
- Per-message path: `didReceiveNewMessages` + `updateInterfaces` only (no full dialogs reload).
- Debounced `dialogsNeedReload` only when a brand-new dialog appears.
- Fixed thread pool (4). Log spam gated by `BuildVars.LOGS_ENABLED`.
- Kept system notify + OpenIM conn-ui (no TG DC / waiting-network overlay).
