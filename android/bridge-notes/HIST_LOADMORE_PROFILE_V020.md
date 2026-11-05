# v0.1.20 — older history load-more + profile (nickname/avatar)

## Chat scroll-up (older messages)
- Older history is **REST only** (`/msg/pull_msg_by_seq` via `OpenIMImClient.searchHistory(beforeSeq)`), never a WS frame.
- Root cause: `ChatActivity` sealed `endReached` when `messArr.size() < count` on network loads, ignoring bridge `isEnd`. OpenIM pages can be short (text-only filter / seq holes), so pagination froze.
- Fix: `ChatActivity` OpenIM path trusts `isEnd`; `KgramTgBridge.loadMessagesOpenIM` passes synthetic `endCheckCount` so size&lt;count cannot false-seal while more older seqs exist.

## Profile
- Nickname: `ChangeNameActivity` → `KgramTgBridge.updateSelfNickname` → `POST /user/update_user_info` + `KgramSession`/`UserConfig`.
- Avatar: `ImageUpdater` skips Telegram DC upload; `ProfileActivity.didUploadPhoto` preview path → `KgramTgBridge.uploadSelfAvatar` → object form-data/multipart → `update_user_info(faceURL)`.
- Login activation refreshes self via `get_users_info`.
