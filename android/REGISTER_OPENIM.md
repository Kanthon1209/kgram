# Wire kgram (Telegram-Android fork) registration to ECS OpenIM

Current `/workspace/Telegram-Android` is still stock Telegram MTProto login (`LoginActivity` → DC / `ConnectionsManager`).  
There is **no OpenIM adapter yet**. Registration against OpenIM Chat will **not** work by only changing Telegram `APP_ID`/`BuildVars`.

## What to add (adapter), not Telegram DCs

Introduce a small config (suggested) used by the future OpenIM login/register UI:

```java
// e.g. org.telegram.messenger.kgram.KgramServerConfig
public final class KgramServerConfig {
    public static final String CHAT_API = "http://8.136.150.149:10008";  // register/login
    public static final String IM_API   = "http://8.136.150.149:10002";  // OpenIM REST
    public static final String IM_WS    = "ws://8.136.150.149:10001";    // OpenIM gateway
    public static final String MINIO    = "http://8.136.150.149:10005";
}
```

### Registration page flow

1. UI collect userID + password (+ nickname).
2. `POST {CHAT_API}/account/register` with OpenIM Chat JSON body.
3. On success, `POST {CHAT_API}/account/login` (or use register response).
4. Init OpenIM Android SDK with `IM_API` + `IM_WS` and the user token/`userID`.

### Where in the Telegram shell

| Stock Telegram | kgram change |
|----------------|--------------|
| `LoginActivity` phone/SMS → MTProto | Replace or branch to OpenIM register/login form that calls `CHAT_API` |
| `ConnectionsManager` / native TGNET | Do not point at OpenIM; keep for later removal. New `OpenIMBridge` / SDK module |
| `BuildVars.APP_ID/APP_HASH` | Irrelevant for OpenIM Chat register |

### cleartext HTTP (Android 9+)

Demo uses `http://` — add network security config allowing cleartext to `8.136.150.149`, or put Caddy/HTTPS later.

`AndroidManifest.xml`: `android:usesCleartextTraffic="true"` (debug only) **or** `res/xml/network_security_config.xml` domain-config for the ECS IP.

### Env / gradle (optional)

```properties
# gradle.properties or local.properties
KGRAM_CHAT_API=http://8.136.150.149:10008
KGRAM_IM_API=http://8.136.150.149:10002
KGRAM_IM_WS=ws://8.136.150.149:10001
```

Wire via `buildConfigField` in `TMessagesProj`/`TMessagesProj_App`.

## Until adapter exists

Use curl / OpenIM web (profile `ui`) / Postman against `10008` to verify register.  
Android APK currently still talks to Telegram infrastructure for login UI.
