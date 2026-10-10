# 随声记

纯原生 Android 本地录音原型：Kotlin、Jetpack Compose、Material 3 Expressive、Room Event Sourcing。

支持桌面小组件自动开始、麦克风前台服务、暂停／继续、M4A 保存、按日期分组的录音库、播放和进度拖动。按实际窗口与折叠特征展示单栏／列表详情双栏；Activity 重建不会结束服务录音。

0.4.3 增加结构化主体、事实范围、日期与多来源；记忆整理可提出新增、补充依据、替代旧事实和澄清问题，全部经审核提交。忘记结构化事实会阻止同范围的新候选，并提供明确的重新学习入口。删除部分来源保留仍有有效证据的事实，过期事实退出当前画像。详见 [结构化记忆交付](docs/structured-memory-delivery.md)。私人助手使用独立的「记忆提取」模型；聊天不顺带保存记忆，未配置记忆模型仍可聊天和手动记住。历史版本见 [记忆系统第一版](docs/memory-system-delivery.md)、[复审与加固](docs/memory-system-review.md) 和 [私人助手交付](docs/personal-assistant-delivery.md)。

0.4.4 接入应用内身份编辑、三项内置技能与开关、经过真实工具探测的主动回忆，以及默认关闭的自动候选整理。设置 → 助手提供身份、技能、记忆方式三个入口；对话可输入 `/weekly-review`、`/project-review`、`/personal-planning` 明确选用技能。自动整理使用独立 MEMORY 模型，结果仍需审核。实际范围与验证见 [助手功能接入](docs/personal-agent-integration-delivery.md)；目标设计见 [私人助手架构](docs/architecture/personal-agent.md)。

0.4.5 增加无 Embedding 的聊天／录音转写检索、日期／项目筛选、最近记忆变更、持久化授权历史批次和可选自动确认。来源版本变化及忘记会屏蔽检索与迟到结果；历史整理需另行授权上传，自动整理每天最多 20 次尝试，未知结果不自动重试。自动确认默认关闭，仅允许合成评测覆盖的有限普通单句，敏感信息与冲突仍审核。实际边界、费用说明及测试见 [历史检索与可靠学习](docs/history-learning-delivery.md)、[质量评估](docs/memory-quality-evaluation.md)。

现有功能的界面打磨包括统一首页入口、可展开检索筛选、固定整理按钮、清晰的记忆审核／生效状态，以及实际内容尺寸和大字体下的分栏修正。设计取舍、截图与验证见 [界面打磨记录](docs/ui-workflows-polish.md)。

保存录音后可转写、生成标题／总结和记忆候选；开启自动处理且配置有效服务后会上传音频与相关文本。关闭自动处理时只在详情中手动触发。对话只在主动发送或重试时上传所选上下文，不自动重试。详见 [录音 AI 功能](docs/feature-review-delivery.md)。

0.3.2 按 vivo 官方文档调整原子通知并增加可复制诊断。官方接入要求应用上架、申请场景准入及平台开通权限；当前不能保证 OriginOS 7 胶囊出现。来源、改动、接入材料草稿与真机步骤见 [原子通知接入核对](docs/vivo-atomic-notification.md)。

0.4.6 按真机系统录音机参考补齐原子卡片的暂停／继续、保存图标操作，收起时请求图标与计时、点击展开卡片。使用官方基础模板的多图片操作字段；原子岛场景开关／准入及实际外观仍待 vivo 确认，不能仅靠更新 APK 保证出现。范围与验证见上述原子通知接入核对。

0.4.7 补齐 Android 标准 Live Updates 提升请求与权限，录音用系统计时器、暂停用冻结的短计时文本；诊断区分请求、格式资格、系统允许与实际提升。vivo 是否将该通知显示为原子岛仍待真机验证。[网页检索与开发路径](docs/island-development-research.md) 比较原生原子岛、标准 Live Updates 与悬浮窗方案。

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
