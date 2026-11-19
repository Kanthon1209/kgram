# Gateway（Go）

kgram 边缘网关。本里程碑（安全底座）职责：

1. **代表已登录用户调用需要管理员权限的 OpenIM 接口**（目前只有 REST `/msg/send_msg`，官方规定仅 app manager 可调用）。客户端不再持有 admin secret，也不能申请 admin token。
2. **鉴权与授权：** 所有特权接口要求用户自己的 IM token（`Authorization: Bearer` 或 `token` 头）。`sendID` / `fromUserID` 一律强制为 token 主体，用户不能冒充他人发消息或代他人申请好友。
3. **TLS 与 WebSocket 代理：** 对客户端暴露 HTTPS / WSS；把用户 token 从请求头或连接后的 auth 帧取出后，再在服务端连到 OpenIM msggateway（上游 URL 带 token，客户端 URL 不得带 token）。
4. **用户范围 API 反代：** `/chat`、`/im`、`/objects` 将 Chat / OpenIM API / MinIO 转到内网。客户端禁止的管理员接口在 `/im` 上直接拒绝。

LiveKit JWT 签发仍为后续阶段。

## 本地运行

```bash
cd services/gateway
export OPENIM_API_URL=http://127.0.0.1:10002
export OPENIM_CHAT_URL=http://127.0.0.1:10008
export OPENIM_WS_URL=ws://127.0.0.1:10001
export OPENIM_MINIO_URL=http://127.0.0.1:10005
export OPENIM_SECRET=...          # 仅服务端环境变量，切勿写入仓库或客户端
export OPENIM_ADMIN_USER=imAdmin
go test ./...
go run .
```

默认 HTTP 监听 `:8080`。访问 `/health` 返回 JSON。

启用 HTTPS（推荐在对外端口上使用）：

```bash
export GATEWAY_TLS_ADDR=:8443
export GATEWAY_TLS_CERT_FILE=/path/to/cert.pem
export GATEWAY_TLS_KEY_FILE=/path/to/key.pem
export GATEWAY_PUBLIC_URL=https://YOUR_HOST:8443
```

演示环境若还没有公网域名，可用自签证书（把 IP 放进 SAN），并把公钥放到 Android `res/raw/kgram_demo_ca.pem` 后按 [deploy/gateway/README.md](../../deploy/gateway/README.md) 配置网络安全。**不要把私钥提交进 git。**

## 环境变量

| 变量 | 说明 |
|------|------|
| `GATEWAY_ADDR` | HTTP 监听地址，默认 `:8080`。生产建议绑 `127.0.0.1:8080`，对外只开 TLS 或反代。 |
| `GATEWAY_TLS_ADDR` | HTTPS 监听地址；仅当同时提供证书和私钥时生效，默认 `:8443` |
| `GATEWAY_TLS_CERT_FILE` | TLS 证书路径（公钥，可与中间证书拼接） |
| `GATEWAY_TLS_KEY_FILE` | TLS 私钥路径（勿入库） |
| `GATEWAY_PUBLIC_URL` | 网关对外 URL，用于把 OpenIM 返回的 MinIO 明文地址改写成 `https://.../objects` |
| `OPENIM_API_URL` | 内网 OpenIM API，默认 `http://127.0.0.1:10002` |
| `OPENIM_CHAT_URL` | 内网 Chat API，默认 `http://127.0.0.1:10008` |
| `OPENIM_WS_URL` | 内网 msggateway，默认 `ws://127.0.0.1:10001` |
| `OPENIM_MINIO_URL` | 内网 MinIO，默认 `http://127.0.0.1:10005` |
| `OPENIM_SECRET` | OpenIM 管理员 secret（**只放环境 / 服务器配置，禁止提交**） |
| `OPENIM_ADMIN_USER` | 管理员 userID，默认 `imAdmin` |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | 预留，本里程碑未使用 |

## 客户端协议

鉴权：`Authorization: Bearer <imToken>` 或头 `token: <imToken>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查，无需登录 |
| POST | `/v1/messages/send` | 发单聊文本。Body：`recvID`, `text`, 可选 `senderNickname`, `clientMsgID`。忽略 body 里的 `sendID`，始终用 token 主体。网关再用管理员 token 调 OpenIM `/msg/send_msg`（REST 发送接口官方仅允许 admin）。 |
| POST | `/v1/friends/request` | 用**用户 token** 调官方 `/friend/add_friend`。Body：`toUserID`, 可选 `reqMsg`。不会双边 `import_friend`。已是好友（1304）视为成功。 |
| POST | `/v1/friends/respond` | 用用户 token 调 `/friend/add_friend_response`。`toUserID` 强制为当前用户。 |
| POST | `/v1/conversations/peer-read` | 查询对端 `hasReadSeq`。仅当 `conversationID` 等于当前用户与 `peerUserID` 的 `si_<sorted>_` 单聊 ID 时才用管理员身份代查（用户 token 不能查别人的 cursor）。 |
| WS | `/v1/ws` | WSS 代理。Token 放在握手头或连接后的 `{"type":"auth","token":"..."}` 帧。**URL query 带 token 会 400。** `sendID` 等非密钥参数可留在 query。 |

反代（转发用户 token，不注入 admin token）：

- `/chat/*` → Chat API
- `/im/*` → OpenIM API，但拒绝：`/auth/get_admin_token`、`/auth/get_user_token`、`/friend/import_friend`、`/user/get_users`、`/user/get_all_users_uid`、`/msg/send_msg`、`/msg/batch_send_msg`、`/msg/send_business_notification`
- `/objects/*` → MinIO

**不会**提供「列出服务器全部用户」的网关接口。通讯录应走当前用户的好友列表（`/im/friend/get_friend_list`）。

## 授权模型摘要

- 发消息：token 必须能通过 OpenIM `parse_token`；消息 `sendID` 只能是该 userID。
- 加好友：只发「我申请加对方」，不会用 admin `import_friend` 把我写进对方好友列表。
- 已读查询：必须是该单聊的参与者。
- 管理员凭证只留在网关进程环境里。

部署说明见 [deploy/gateway/README.md](../../deploy/gateway/README.md)。
