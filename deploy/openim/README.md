# OpenIM 部署接入

kgram **不在此仓库复制 OpenIM Server 源码**；本地与测试环境应使用 **OpenIM 官方 Docker Compose** 或 Helm 文档部署。

## 官方资源（链接占位，请以官网最新为准）

- 项目主页：<https://github.com/openimsdk/open-im-server>
- Docker 部署文档：<https://docs.openim.io/guides/gettingStarted/dockerCompose>
- Docker Compose 示例仓库：<https://github.com/openimsdk/openim-docker>

## 推荐接入步骤

1. 克隆或引用 `openim-docker` 中的 compose 文件，按文档配置 `.env`（数据库、密码等 **仅本地保存**，勿提交 kgram 仓库）。
2. 启动 OpenIM 栈，确认 API / WebSocket 端口与账号注册流程可用。
3. 与本仓库根目录 `deploy/docker-compose.yml` **合并方式二选一：**
   - **多文件：** `docker compose -f docker-compose.yml -f /path/to/openim/docker-compose.yaml up -d`
   - **单文件：** 将 OpenIM 服务块复制进 kgram 的 compose，并统一 `networks` 命名（便于 gateway 与 LiveKit 同网访问）。
4. 在 `docs/roadmap.md` P0 验收项打勾，并在团队 wiki 记录实际使用的端口与环境变量名。

## 客户端对接

Android fork 就绪后，使用 **OpenIM Client SDK** 连接上述服务；网关暂不替代 OpenIM 长连接，仅负责 LiveKit 等横切能力。

## Demo ECS (current)

Public demo host (Aliyun Hangzhou): **`8.136.150.149`**

See [ENDPOINTS.md](./ENDPOINTS.md) and [openim.env.example](./openim.env.example).

| Service | URL |
|---------|-----|
| Chat / Register | `http://8.136.150.149:10008` |
| OpenIM API | `http://8.136.150.149:10002` |
| OpenIM WS | `ws://8.136.150.149:10001` |

Android register wiring notes: [android/REGISTER_OPENIM.md](../../android/REGISTER_OPENIM.md).

Server files live under `/opt/kgram/openim-docker` on that ECS (not copied into this repo).
