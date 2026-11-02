# Android OpenIM bridge sync

Source of truth for building APKs is the local Telegram-Android tree on the bot box:
`/workspace/Telegram-Android`.

This folder mirrors `org.telegram.messenger.kgram` (+ `org.telegram.ui.kgram` if present)
for review and backup. Wire-ins also touch LaunchActivity / LoginActivity / MessagesController
in the TG tree; those full diffs are not fully mirrored here yet.

OpenIM demo backend: `8.136.150.149` (API 10002, WS 10001, Chat 10008).
