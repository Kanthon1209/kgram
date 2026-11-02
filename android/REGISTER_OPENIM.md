# Wire kgram registration UI to OpenIM Chat (ECS)

**Backend (live):** `http://8.136.150.149:10008`

Stock Telegram `LoginActivity` is still MTProto. OpenIM register needs an adapter — **not** `BuildVars.APP_ID` / DC addresses.

## Config constants to add

```java
public final class KgramServerConfig {
    public static final String CHAT_API = "http://8.136.150.149:10008";
    public static final String IM_API   = "http://8.136.150.149:10002";
    public static final String IM_WS    = "ws://8.136.150.149:10001";
}
```

## Register request (verified on ECS)

`POST {CHAT_API}/account/register`  
Headers: `Content-Type: application/json`, `operationID: <any unique string>`

```json
{
  "verifyCode": "666666",
  "platform": 1,
  "autoLogin": true,
  "user": {
    "nickname": "demo",
    "areaCode": "+86",
    "phoneNumber": "13800138000",
    "password": "test123456"
  }
}
```

Success `errCode: 0` returns `data.userID`, `data.imToken`, `data.chatToken`.  
Then init OpenIM Android SDK with `IM_API` + `IM_WS` and `imToken` / `userID`.

Login: `POST {CHAT_API}/account/login` with `platform`, `areaCode`, `phoneNumber`, `password`.

## Telegram shell touch points

| File / area | Change |
|-------------|--------|
| New register/login form (or branch in `LoginActivity`) | Call Chat `10008` instead of SMS/MTProto |
| New `OpenIMBridge` / SDK module | Use `10002` + `10001`; do **not** remap `ConnectionsManager` DCs to OpenIM |
| `network_security_config` / cleartext | Allow `http://8.136.150.149` for demo |

Optional `buildConfigField` from `gradle.properties`:

```
KGRAM_CHAT_API=http://8.136.150.149:10008
KGRAM_IM_API=http://8.136.150.149:10002
KGRAM_IM_WS=ws://8.136.150.149:10001
```
