# LiveKit 自建 SFU

kgram 采用 **自建 LiveKit**，用于 1v1 / 小规模群组音视频。Token 由 `services/gateway` 使用 API Key / Secret 签发（P3 实现）。

## 配置

- 示例文件：[livekit.yaml.example](./livekit.yaml.example)
- 复制为 `livekit.yaml`（已加入 `.gitignore`），替换所有 `CHANGE_ME_*` 占位符。
- 开发模式可在 compose 中使用 `--dev`（见 `deploy/docker-compose.yml`），**生产环境关闭 dev 并配置 TURN、域名与 TLS**。

## 官方文档

- <https://docs.livekit.io/home/self-hosting/deployment/>
- <https://github.com/livekit/livekit>

## 本地试跑（可选）

```bash
cd deploy
docker compose --profile livekit up -d livekit
```

确认 `7880` 端口可访问后，再在 gateway 中配置与 Key 一致的 `LIVEKIT_API_*` 环境变量（勿提交仓库）。
