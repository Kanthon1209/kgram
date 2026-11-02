# kgram

基于 Telegram-Android 外观的即时通讯客户端，底层通过适配层接入 **OpenIM**；音视频走自建的 **LiveKit**。开发期使用 **Docker Compose** 编排依赖服务。

## 技术栈

| 层级 | 选型 |
|------|------|
| 客户端 | Fork [Telegram-Android](https://github.com/DrKLO/Telegram)（UI 保留，网络层换 OpenIM SDK） |
| IM 后端 | [OpenIM](https://github.com/openimsdk/open-im-server) |
| 音视频 | 自建 LiveKit SFU |
| 网关 | Go（LiveKit JWT 签发、后续鉴权/桥接） |
| 本地环境 | Docker Compose |

> **说明：** `android/` 目录后续放入 Telegram-Android fork，**当前仓库不包含 Telegram 源码**。  
> **Agent / RAG** 能力已明确延后，本仓库不预留相关目录。

## 目录说明

```
.
├── android/          # 未来将放置 Telegram-Android fork
├── services/
│   └── gateway/      # Go 网关（LiveKit JWT 等）
├── deploy/           # Docker Compose、OpenIM / LiveKit 部署说明与示例
├── docs/             # 决策记录与路线图
└── scripts/          # 本地 bootstrap 脚本
```

## 本地起步（Docker Compose）

1. **环境要求：** Docker Engine 与 Docker Compose v2（`docker compose` 子命令）。
2. **检查环境：**
   ```bash
   chmod +x scripts/bootstrap.sh
   ./scripts/bootstrap.sh
   ```
3. **启动占位栈（LiveKit 示例等）：**
   ```bash
   cd deploy
   docker compose up -d
   ```
4. **接入 OpenIM：** 按 [deploy/openim/README.md](deploy/openim/README.md) 对接官方 Docker Compose，再与本仓库 `deploy/docker-compose.yml` 合并或扩展。
5. **LiveKit 配置：** 参考 [deploy/livekit/](deploy/livekit/) 中的 `livekit.yaml.example`，复制为实际配置并替换占位密钥。
6. **Android 客户端：** fork 完成后放入 `android/`，按该目录 README 进行 OpenIM SDK 适配。

更细的里程碑见 [docs/roadmap.md](docs/roadmap.md)，已拍板决策见 [docs/decisions.md](docs/decisions.md)。
