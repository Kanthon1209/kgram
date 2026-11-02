# kgram Android ↔ OpenIM register

Phone login screen (Telegram `LoginActivity` / `PhoneView`) is branched when `KgramServerConfig.USE_OPENIM_AUTH=true`:

1. Confirm phone number (TG UI).
2. Dialog: nickname + password → **Register** or **Login**.
3. `POST http://8.136.150.149:10008/account/register` with `verifyCode=666666` (super code).
4. On success tokens saved in SharedPreferences `kgram_openim`.
5. Telegram MTProto `auth.sendCode` is **not** called for MODE_LOGIN.

Files:
- `org.telegram.messenger.kgram.KgramServerConfig`
- `org.telegram.messenger.kgram.OpenIMChatClient`
- `LoginActivity.PhoneView` OpenIM branch + dialog helpers

Open Aliyun SG: TCP 10008 (and 10001/10002 for later SDK). cleartext already allowed in debug manifest.
