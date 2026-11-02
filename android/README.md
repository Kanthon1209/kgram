# Android 客户端（Telegram-Android Fork）

本目录用于放置 **Telegram-Android** 的 fork，**当前为空占位**。完整源码不会提交到 kgram 主仓库的初始骨架中。

## 预期来源

- 上游参考：[DrKLO/Telegram](https://github.com/DrKLO/Telegram)（Telegram for Android 官方开源树）。

## 集成原则

1. **UI 保留：** 尽量沿用 Telegram 的界面与交互结构，降低「换后端」对用户感知的影响。
2. **网络层替换：** IM 协议与连接管理改为 **OpenIM Client SDK**，通过 **适配层** 将 Telegram 原有的消息/会话抽象映射到 OpenIM API。
3. **音视频：** 通话相关 UI 可复用或裁剪 Telegram 模块，媒体通道走 **LiveKit**（Token 由 `services/gateway` 签发）。
4. **仓库策略：** 优先在本目录直接维护 fork；**除非团队明确需要版本锁定，否则不使用 git submodule**。

## 后续步骤（fork 就绪后）

1. 将 fork 代码放入本目录（或关联远程 fork 仓库并在 CI 中拉取，按团队规范二选一）。
2. 新建 `openim` / `adapter` 模块（命名以实际工程为准），隔离 OpenIM 与 UI。
3. 在根目录 README 与 `docs/roadmap.md` 中更新构建说明（JDK/AGP 版本、签名、调试包命令）。

## 构建

待 fork 纳入后再补充 `gradlew` 与具体命令；在此之前无需在本目录执行 Android 构建。
