# 架构与产品决策（已拍板）

本文档记录 kgram 项目当前已确认的方向，后续变更请在此追加条目并注明日期。

## 客户端

- **Fork Telegram-Android** 作为 UI 与交互基线，目标是「看起来像 Telegram」的体验。
- **不**在本仓库内 vendoring 完整 Telegram 源码；`android/` 在 fork 就绪后再纳入（或 submodule，仅在必要时使用）。
- **适配原则：** 保留 Telegram UI 层，**网络与 IM 能力替换为 OpenIM SDK**，通过适配层隔离差异。

## 基础设施与部署

- **开发期**统一使用 **Docker Compose** 编排本地依赖（OpenIM 官方栈、LiveKit、网关等）。
- **OpenIM** 采用官方 Docker Compose / 部署文档接入，不在本仓库重写 IM Server。
- **LiveKit** 采用 **自建 SFU**，不依赖第三方托管；JWT 由 Go 网关签发。

## 范围外（明确不做 / 延后）

- **Agent / RAG：** 当前阶段 **不做**，仓库不创建 agent、rag 等目录；列入远期规划即可。
- 首版不承诺 parity  Telegram 全量功能，按 [roadmap.md](./roadmap.md) 分阶段交付。

## 网关

- **Go 网关** 首责：**签发 LiveKit 房间 JWT**；后续扩展统一鉴权、OpenIM 与 LiveKit 之间的桥接逻辑。

## 安全底座（2026-11-19）

- 客户端 **不得** 持有 OpenIM 管理员 secret，也不得申请 admin token 或直接调用管理员接口（`send_msg`、`import_friend`、`get_users` 等）。
- 需要管理员身份的操作（当前仅 REST 发消息，以及在用户是会话参与者的前提下代查对端 hasReadSeq）只允许由 **kgram 网关** 在服务端完成；网关先用用户自己的 IM token 鉴权，并将操作主体强制为该用户。
- 加好友走官方用户接口 `/friend/add_friend`（申请），**不**再使用 admin `import_friend` 双边强制成为好友。
- 通讯录只使用当前用户的好友列表，不提供「列出服务器全部用户」的客户端或网关接口。
- 客户端默认使用 HTTPS / WSS 访问 Chat、IM、对象存储与长连接。WebSocket 的 token 放在请求头或连接后的 auth 帧，不得出现在 URL query。
- 管理员凭证只通过服务器环境变量配置，不进入 git。
