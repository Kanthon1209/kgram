# OpenIM connection-state UI (v0.1.15)

Telegram paints 「等待网络连接」 from:

1. `ChatAvatarContainer.updateCurrentConnectionState` — chat ActionBar **subtitle** (top-left under name)
2. `LaunchActivity.updateCurrentConnectionState` → `ActionBarLayout.setTitleOverlayText` — Dialogs/Chat **title overlay** (above)
3. `ProfileActivity` online line override

`ConnectionsManager.getConnectionState()` already returned `Connected` under `USE_OPENIM_AUTH`, but native `setNetworkAvailable(false)` kept emitting WaitingForNetwork and some UI paths cached overlays.

v0.1.15 hard-gates those UI sites when `KgramServerConfig.USE_OPENIM_AUTH` and stops marking native network unavailable (pause + drop sends only).
