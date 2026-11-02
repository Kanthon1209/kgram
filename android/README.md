# kgram Android (Telegram UI + OpenIM)

Telegram-Android fork stays **local on the build box** (full tree is too large / upstream-origin). This folder mirrors the adapter layer committed to `Kanthon1209/kgram`:

- `src/org/telegram/messenger/kgram/` — OpenIM REST/WS bridge
- `src/org/telegram/ui/kgram/` — optional demo activities (login/register helpers)
- `patches/telegram-openim-bridge.patch` — hooks into Telegram `MessagesController` / `ChatActivity` / etc.

Apply sources into a Telegram-Android checkout under `TMessagesProj/src/main/java/`, then `git apply android/patches/telegram-openim-bridge.patch`.

Backend demo: `http://8.136.150.149` (OpenIM API `10002`, WS `10001`, Chat `10008`).
