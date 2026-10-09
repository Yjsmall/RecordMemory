# Android 开发技能选型

实施进展：本地录音阶段已应用 kotlin-specialist、event-sourcing、adaptive、navigation-3、edge-to-edge、testing-setup 和 pwsh；结果见 [交付记录](first-recording-delivery.md)。下文保留安装与选型快照。

核查与安装日期：2026-10-09（Asia/Shanghai）。项目约束：纯原生 Android、Kotlin／Jetpack Compose、Material 3 Expressive 简洁风格、vivo 折叠屏、小组件快速录音、可配置云端／自建 AI 和两类个人记忆。首批 5 个上游技能及本地创建的 event-sourcing 技能位于本项目 `.agents/skills/`。已检查本机开发环境，尚未创建 Android 工程或运行构建验证。

## 推荐组合

现有 design-ui 负责任务流程与体验评审；官方 Android 专项技能负责平台行为和适配；Kotlin 开发技能补充语言与异步实现。具体 Material 3 Expressive 组件、版本与录音行为继续以项目需求和当前 Android 官方文档为依据。

| 技能 | 来源与当前状态 | 对本项目的作用 | 适用边界 |
| --- | --- | --- | --- |
| design-ui | 本机 C:/Users/yu/.codex/skills/design-ui/SKILL.md；已可用 | 信息层级、录音与编辑流程、折叠切换中的任务连续性、AI 反馈、可用性评审 | 通用方法来自 HarmonyOS 设计研究；本项目采用 Material 3 Expressive，不移植其他系统的视觉规范 |
| [mobile-android-design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-android-design) | 社区 wshobson/agents；未安装；已读 SKILL.md 并抽查参考材料 | Material 3 主题、Compose 布局、触控区域、动态配色和常见界面模式 | 常规 Android 设计参考，非完整 Expressive 专项；部分导航与性能建议需按当前 Compose 版本核对 |
| [kotlin-specialist](../.agents/skills/kotlin-specialist/SKILL.md) | 社区 Jeffallan/claude-skills；已安装到项目 | Kotlin 状态建模、协程、Flow、ViewModel 和可测试实现 | 同时覆盖 KMP／Ktor 等领域，本项目只使用 Android 所需部分；示例不是最终代码，取消语义与错误处理仍需检查 |
| [navigation-3](../.agents/skills/navigation-3/SKILL.md) | Google 官方 android/skills；已安装到项目 | 导航状态、返回栈、列表／详情 Scenes、ViewModel 和生命周期整合 | 在工程选定 Navigation 3 及匹配版本后使用 |
| [adaptive](../.agents/skills/adaptive/SKILL.md) | Google 官方 android/skills；已安装到项目 | 外屏单栏、内屏双栏、窗口尺寸变化、导航栏／导航轨和多形态布局检查 | 当前版本要求 Compose 与 Navigation 3；部分 Grid／FlexBox／MediaQuery 示例涉及新或实验性 API，按实际任务与项目版本选择 |
| [edge-to-edge](../.agents/skills/edge-to-edge/SKILL.md) | Google 官方 android/skills；已安装到项目 | 状态栏、手势区域、录音按钮和键盘避让，避免双重留白或输入被遮挡 | 当前技能以 Compose、target SDK 35+ 为前提；Insets 方案需避免重复消费 |
| [testing-setup](../.agents/skills/testing-setup/SKILL.md) | Google 官方 android/skills；已安装到项目 | 核心业务、Compose UI、截图与设备测试的基础设施 | 先分析工程现有依赖，按风险选测试；截图工具按 AGP 版本匹配，不因安装技能就升级为预览版构建链 |
| [event-sourcing](../.agents/skills/event-sourcing/SKILL.md) | 按用户要求使用 skill-creator 在本项目创建；依据 Martin Fowler 的 Event Sourcing 文章 | 事件建模、纯状态重建、历史纠正、外部副作用隔离，以及录音／AI／记忆的恢复评审 | 限核心业务；明确原文观点与 Room／outbox 等项目方案的区别；不代替普通 UI、SSE 或 jj 技能 |

首批已安装 kotlin-specialist、navigation-3、adaptive、edge-to-edge、testing-setup，并继续使用本机现有 design-ui。mobile-android-design 为可选补充，本次未安装。官方技能清单还提供 android-cli、android-profiler、r8-analyzer 等工具与性能专项，等实际开发环境或性能问题需要时再使用。

event-sourcing 作为第 6 个项目技能，入口保持精简；[Fowler 原则与适配说明](../.agents/skills/event-sourcing/references/fowler-principles.md) 按需读取，具体数据库与恢复方案引用现有架构文档。没有复制原文全文或引入新的运行时框架。

本地技能已通过 skill-creator 的 `quick_validate.py` 格式校验，并核对来源归纳、元数据及本地引用；这不代表应用逻辑已实现或通过行为测试。

## 设计工作的实际产出

“高级 UI”在本项目中落实为清晰、一致、可操作且可验证的界面，而不依赖技能名称。设计阶段应形成以下结果：

1. Material 3 语义颜色、排版、间距、形状与动效的统一主题；浅深色、大字体与可选动态取色的应用规则。
2. 录音首页、录音详情、记忆检索／问答、记忆候选、AI 配置及导入导出的任务流程与界面结构。
3. 外屏单栏、内屏双栏、分屏和键盘出现时的布局，以及切换过程中保留会话、选中条目和草稿的规则。
4. 录音启动中／录音中／暂停／保存失败，转写排队／执行／失败，记忆候选／确认／失效等状态的具体反馈。
5. 可运行的 Compose 页面与代表性预览；用真机或模拟器检验触控、焦点、返回、切屏和布局，数据可靠性单独验证。

这次核查的技能没有完整覆盖 Material 3 Expressive、小组件录音、vivo 后台行为与个人记忆全流程。上述应用特有约束记录在 requirements-analysis.md，后续在项目 AGENTS.md 中落实必要的开发与验证规则；通用技能提供参考，不能代替这些需求。

## 安装与使用边界

本次使用现有 skill-installer 按具体目录安装，显式指定目标目录 `D:/code/record/.agents/skills`，保留了各技能支持文件并补齐上游许可证。固定版本如下：

- `android/skills`：`42dc2270e96032bd860bb94511e440aa00a43125`，包含 navigation-3、adaptive、edge-to-edge、testing-setup。
- `Jeffallan/claude-skills`：`1be15d8064f88fc25216442406d40add8fd23b53`，包含 kotlin-specialist。

首次安装时已检查 5 个上游 SKILL.md 的 SHA-256 与固定版本一致，入口直接引用的 47 个本地参考文件均存在；这批文件共 57 个，包括 5 份许可证。新增的 event-sourcing 为本地编写，不标记为 Fowler 发布的技能或套用上游代码许可。来源、版本、路径和入口哈希保存在 [安装记录](../.agents/skills.lock.json)；此文件是项目维护记录，不是 Codex 内置依赖锁格式。全局技能目录未修改，现有 design-ui 继续从本机技能目录加载。

项目技能可按任务匹配，也可显式点名，例如 `$kotlin-specialist`、`$adaptive` 或 `$event-sourcing`。若当前会话列表尚未刷新，可以显式提供 SKILL.md 路径。后续上游升级时比较固定版本与新版本的差异；本地技能修改后更新安装记录。

OpenAI 精选技能目录已通过本机 skill-installer 查询，本次列表没有 Android／Compose 专项。Google 的 [Android skills 官方文档](https://developer.android.google.cn/tools/agents/android-skills?hl=en)明确这些技能采用开放技能格式，可用于支持该格式的代理工具；其 [仓库](https://github.com/android/skills)提供实际技能与参考资料。

本机可用技能另有 skill-creator、skill-installer、pwsh、openai-docs 和 imagegen。与本项目直接相关的是 design-ui；pwsh 可在 Windows 命令兼容问题出现时使用；skill-creator 可在需要沉淀可复用 Android 专项流程时使用。

核查边界：读取了官方 Android 技能文档、仓库目录、所列技能正文或注明的关键部分，并抽查社区技能参考材料；未执行第三方技能脚本，未将技能示例当作已通过测试的实现。OpenAI Codex 技能文档在本次环境返回 HTTP 403，因此不据该页面推断当前产品的安装发现细节。
