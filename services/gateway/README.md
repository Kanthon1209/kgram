# Gateway（Go）

kgram 边缘网关，职责随阶段扩展：

| 阶段 | 职责 |
|------|------|
| 当前 | 占位服务；后续实现 **LiveKit Access Token（JWT）** 签发 |
| 后续 | 统一鉴权（对接 OpenIM 用户身份）、OpenIM ↔ LiveKit 房间/信令桥接、速率限制等 |

## 本地运行

```bash
cd services/gateway
go run .
```

默认在 `:8080` 监听，访问 `/health` 返回 JSON 健康检查。

## 环境变量（预留）

| 变量 | 说明 |
|------|------|
| `GATEWAY_ADDR` | 监听地址，默认 `:8080` |
| `LIVEKIT_API_KEY` | LiveKit API Key（勿提交仓库） |
| `LIVEKIT_API_SECRET` | LiveKit API Secret（勿提交仓库） |

LiveKit JWT 实现将在 P3 阶段与 `deploy/livekit/` 配置联调。
