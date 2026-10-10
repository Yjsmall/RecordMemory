# 随声记

纯原生 Android 本地录音原型：Kotlin、Jetpack Compose、Material 3 Expressive、Room Event Sourcing。

支持桌面小组件自动开始、麦克风前台服务、暂停／继续、M4A 保存、按日期分组的录音库、播放和进度拖动。按实际窗口与折叠特征展示单栏／列表详情双栏；Activity 重建不会结束服务录音。

0.4.0 增加首页「私人助手」入口、多轮对话、历史会话与个人记忆联动。设置 → 模型与能力 → 私人助手，独立选择 provider、协议、模型和提示词。支持 Responses 与 Chat Completions；发送时使用适量的已确认记忆和近期对话。聊天提取的记忆需要确认，也可点击消息中的「记住…」主动保存。详见 [私人助手交付](docs/personal-assistant-delivery.md)。

下一版的 SOUL.md 身份、自选目录 skills、主动回忆 agent 与结构化记忆方案见 [私人助手架构](docs/architecture/personal-agent.md) 和 [工作区模板](docs/examples/agent-workspace/README.md)；目前为设计，尚未实装。

保存录音后可转写、生成标题／总结和记忆候选；开启自动处理且配置有效服务后会上传音频与相关文本。关闭自动处理时只在详情中手动触发。对话只在主动发送或重试时上传所选上下文，不自动重试。详见 [录音 AI 功能](docs/feature-review-delivery.md)。

0.3.2 按 vivo 官方文档调整原子通知并增加可复制诊断。官方接入要求应用上架、申请场景准入及平台开通权限；当前不能保证 OriginOS 7 胶囊出现。来源、改动、接入材料草稿与真机步骤见 [原子通知接入核对](docs/vivo-atomic-notification.md)。

## 构建

需要 JDK 21（编译目标 Java 17）、Android SDK Platform 36、Build Tools 36.0.0。`local.properties` 设置 `sdk.dir`，不纳入版本控制。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug ktlint detekt
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

当前 `applicationId` 为 `dev.local.record`，显示名称「随声记」，最低 Android 10（API 29）。此包使用本机标准 debug key；正式长期使用前应固定发布签名并规划迁移。本阶段不自动备份个人录音。

## 固定工具链

| 项目 | 版本 |
| --- | --- |
| Gradle Wrapper | 8.13，分发包 SHA-256 校验 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler | 2.2.21 |
| Compose BOM | 2025.12.00 |
| Material 3 | 1.5.0-alpha10，公开 Expressive API；局部 opt-in |
| Navigation 3 | 1.0.0 |
| Adaptive Navigation 3 bridge | 1.3.0-alpha02，匹配 API 36 / AGP 8.13；其 Adaptive 基础模块由固定版本 POM 解析 |
| Room / KSP | 2.8.4 / 2.2.21-2.0.4 |
| Hilt | 2.57.2 |
| Media3 | 1.9.0 |
| DataStore | 1.2.0，配置与凭据以 AES-GCM 加密同事务保存 |

Material 3 1.4 稳定版将所用 Expressive 主题 API 标为 internal，最新稳定 Adaptive 1.3 要求 API 37 / AGP 9.1。本阶段保留 API 36 工具链，固定兼容的实验库版本，不使用 Grid / FlexBox 等新的实验布局 API。实际兼容性以 APK 构建和仪器测试验证。

Windows 若仅系统代理可用，Java 不一定自动采用该设置。按本机代理地址为当前命令提供带引号的 `'-Dhttps.proxyHost=…'`、`'-Dhttps.proxyPort=…'` 等参数；不要把私人代理或凭据提交到工程。

## 代码管理

仓库采用 jj Git 后端与 colocated 工作区。`jj status`、`jj diff`、`jj log` 查看状态；`jj new` 开始下一项变更。远端尚未配置。

事件追加、唯一幂等键、聚合版本检查、投影及检查点更新在同一 Room 事务内完成。`RecordingRepository.rebuild()` 仅重建投影；纯 reducer 不持有麦克风、网络、文件或调度接口。音频放在私有文件目录，事件只引用文件名。

详见 [测试与验收](docs/testing.md)、[UI 与 AI 配置交付](docs/ui-ai-configuration-delivery.md)、[复用评估](docs/recording-reuse-evaluation.md)、[事件架构](docs/architecture/event-sourcing.md)。
