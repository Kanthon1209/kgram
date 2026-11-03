# Auth UX + Telegram DC block (2026-11-03)

Source of truth: `/workspace/Telegram-Android`.

## UX
- `LoginActivity` PhoneView (USE_OPENIM_AUTH): after valid phone, opens `KgramLoginActivity` directly (no 登录/注册 dialog).
- Subtitle:「确认手机号后输入密码登录；没有账号可在下一步注册。」
- `KgramLoginActivity`: password login; accountNotFound → 去注册; nick from API when present.
- `KgramRegisterActivity`: soft demo code hint; alreadyRegistered+bad password → 去登录.

## Self-hosted / no Telegram DC
`ConnectionsManager` when `KgramServerConfig.USE_OPENIM_AUTH`:
- `native_init(..., hasNetwork=false)` then `native_pauseNetwork`
- `checkConnection` marks network unavailable + pause
- `sendRequestInternal` drops all MTProto requests with `KGRAM_OPENIM_ONLY`
- `setAppPaused` / `resumeNetworkMaybe` will not resume TG network

Login path already skips `auth.sendCode`. APP_ID=4 is fine because TG DCs are not dialed.
