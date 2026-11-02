# 路线图

优先级从高到低；每项完成后再进入下一阶段，避免并行堆功能。

## P0 — OpenIM + Compose 跑通

- [ ] 按官方文档在本地通过 Docker Compose 启动 OpenIM 核心组件。
- [ ] 与本仓库 `deploy/docker-compose.yml` 整合或文档化合并方式。
- [ ] `scripts/bootstrap.sh` 与 README 中的起步路径可复现（新人 30 分钟内能拉起依赖栈骨架）。

## P1 — 登录 + 单聊适配

- [ ] Telegram-Android fork 放入 `android/`，建立最小 OpenIM SDK 适配层。
- [ ] 完成登录/注册（对接 OpenIM 账号体系）。
- [ ] 单聊：发消息、收消息、会话列表（MVP）。

## P2 — 群聊 / 好友 / 推送

- [ ] 群组基础能力（创建、加入、群消息）。
- [ ] 好友/联系人同步与 OpenIM 关系链对齐。
- [ ] 移动端推送链路（FCM 等）与 OpenIM 推送配置打通。

## P3 — LiveKit 音视频

- [ ] 部署自建 LiveKit（见 `deploy/livekit/`）。
- [ ] Go 网关签发 LiveKit JWT，客户端进房通话（1v1 或小组）。
- [ ] 与 IM 信令/ UI 入口联调。

## 远期（当前不做）

- **Agent / RAG：** 不在本阶段排期；若产品后续需要，单独立项与仓库边界再议。
