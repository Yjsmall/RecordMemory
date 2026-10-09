# 开工准备：环境、jj 与首个开发阶段

实施进展：jj 与 Android 工程已初始化，首个本地录音版本和实际验证结果见 [交付记录](first-recording-delivery.md)。下文保留开发前检查快照。

检查日期：2026-10-09（Asia/Shanghai）。已确定：纯原生 Android、Kotlin／Compose、Material 3 Expressive 简洁风格、jj 管理代码；核心业务采用 Event Sourcing（录音、AI 处理、记忆变更）。事件模型和边界见 [事件溯源方案](architecture/event-sourcing.md)。

本轮为环境检查与准备方案，尚未初始化版本库、建立 Android 工程、修改全局环境变量或运行应用构建。启动 adb 查询设备时，adb 服务已正常启动。

## 本机实际状态

| 项目 | 检查结果 | 开工时的处理 |
| --- | --- | --- |
| jj | 已安装，版本 0.45.1；可从 PATH 调用 | 使用现有安装 |
| Git | 已安装；全局作者姓名和邮箱已配置 | 为 jj 的 Git 后端及 Android Studio 工具兼容提供支持 |
| jj 作者身份 | jj 的 user.name／user.email 尚未配置 | 可以复用已有 Git 作者身份，在本项目作用域配置；不臆造姓名或邮箱 |
| 当前版本库 | D:/code/record 下没有 .jj 或 .git | 先建立忽略规则，再初始化 colocated 仓库 |
| Android Studio | 已安装于 C:/Program Files/Android/Android Studio，配置目录为 AndroidStudio2026.2.1 | 可直接使用 |
| Android SDK | 已安装于 D:/softwares/android_sdk；Studio 已记录该路径 | 项目 local.properties 指向这个 SDK，文件不纳入版本控制 |
| SDK Platform／Build Tools | 已发现 Android API 36.1 和 Build Tools 36.1.0 | 根据选定 AGP／Compose 稳定兼容组合确定 compileSdk 和 targetSdk，需要时补装匹配平台 |
| JDK | 命令行 OpenJDK 21.0.2；Studio 自带 JBR 25.0.3 | 明确 Gradle 使用的 JDK，并让 IDE／命令行一致；IDE 运行时与项目构建 JDK 分别管理 |
| Gradle | 系统命令存在；项目尚无 Gradle Wrapper | 工程固定 Wrapper 版本，不依赖系统 Gradle 的自动变化 |
| adb | SDK 内可运行，版本 37.0.0；未加入 PATH | 可通过完整路径使用，或按需设置当前终端环境 |
| SDK 命令行工具 | cmdline-tools/latest/bin/sdkmanager.bat 存在；未加入 PATH | 工程建立时检查所需包与许可证状态 |
| 模拟器 | 已有 Medium_Phone_API_36；未发现折叠屏 AVD | 添加一套折叠屏模拟器用于窗口变化、预览与 UI 验证 |
| 真机 | adb 当前列出 0 台设备 | vivo 手机开启开发者选项和 USB 调试，连接后授权；读取实际 Android／OriginOS 版本 |
| Skills | 项目级 5 个上游技能及本地 event-sourcing 已就绪，现有 design-ui 可用 | 按具体任务调用，见 [技能安装记录](development-skills.md) |

PATH 中缺少 adb 不代表缺少 SDK。本机已经具备大部分基础环境；目前还没有经过“构建 APK → 安装 → 启动”验证，因此不宣称构建链已通过验收。

## jj 的采用方式

采用 **jj + Git 后端 + colocated 工作区**。目录同时含 .jj 和 .git，日常变更整理用 jj，Android Studio 与远端托管继续使用 Git 兼容能力。jj 是开发时的源码管理工具；应用中的事件存储由 Room／SQLite 实现，两者独立。

建议操作顺序如下，命令本轮尚未执行：

1. 建立 .gitignore，覆盖 .jj、Gradle／Kotlin 缓存、各模块 build、local.properties、IDE 的个人工作区文件、签名密钥、私有配置、本地真实录音与备份目录。纳入项目文档、技能及安装锁记录。测试用合成音频可在专门的 fixtures 目录维护。
2. 在 D:/code/record 执行 `jj git init --colocate`。
3. 为仓库设置作者身份。可以使用以下 PowerShell 命令复用已有 Git 全局作者配置：

   ```powershell
   $recordAuthorName = git config --global --get user.name
   $recordAuthorEmail = git config --global --get user.email
   jj config set --repo user.name $recordAuthorName
   jj config set --repo user.email $recordAuthorEmail
   jj metaedit --update-author -m 'docs: record product and architecture decisions'
   ```

4. 检查 `jj status`、`jj diff`，确认初始快照只有准备纳入版本控制的文件。
5. 在已检查的准备文档变更上执行 `jj bookmark create main -r @`，然后用 `jj new` 开始下一项工作。新空变更也可以补充描述以说明正在做什么。

本机 jj 0.45.1 使用 `jj metaedit --update-author` 更新初始变更作者；旧教程中的 `jj describe --reset-author` 不适用于当前版本。上述命令已核对本机帮助，但尚未初始化仓库执行。

jj 默认在运行相关命令时将工作区变动自动记录到当前变更中，不需要 Git 式的暂存操作；这并非持续监控每次文件写入。常用命令为：

| 用途 | 命令与含义 |
| --- | --- |
| 查看当前变动 | `jj status`、`jj diff` |
| 给当前变更写说明 | `jj describe -m 'feat: add recording lifecycle'` |
| 开始下一项变更 | `jj new`；原变更留在父节点 |
| 查看变更历史 | `jj log` |
| 整理局部变动 | `jj split`、`jj squash`，按需要拆分或合并 |
| 检查操作历史 | `jj op log`；需要撤销时先确认目标操作 |
| 更新主线指针 | 确认目标已完成且验证通过，再用 `jj bookmark set main -r <目标变更>` |

工作区快照不是备份，也不等于已推送到远端。等远端地址确定后再配置并发布。日常避免混用 IDE 的 Git commit/rebase 和 jj 的变更整理，以保持工作流可预测。jj 的自动快照机制使提前配置 .gitignore 尤其重要。

## 需要用户补充的少量信息

| 信息 | 最晚需要时间 | 原因 |
| --- | --- | --- |
| 应用仅自用、公开开源还是计划闭源分发 | 选择并复制开源项目代码前 | 决定是否接受 GPL 底座，以及许可与分发方式 |
| 首批 ASR、文本模型服务的名称和接口类型 | AI 适配器实现前 | 聊天接口兼容不代表转写兼容；两类能力分别明确可测试端点；转写后的文本搜索在本地完成，无需独立 AI 服务 |
| vivo 手机 Android／OriginOS 版本 | 真机验证前 | 确定权限、后台策略、小组件与折叠切换矩阵；连接后可以读取部分信息 |
| 主要语言是否包括粤语或其他方言 | 选择转写服务和验收样本前 | 影响识别模型与测试样例 |
| 稳定 applicationId 与签名保存方案 | 第一次长期使用安装包前 | 保证后续升级能够覆盖安装并保留本地记录；应用显示名称可稍后调整 |
| 是否使用远端代码仓库及地址 | 首次推送前 | 本地 jj 工作不依赖远端；需要独立安排备份 |

API Key 在应用配置页或本地私有配置中输入；项目文档记录服务类型和地址格式即可。可以先用假服务实现完整流程，再接入真实服务，因此这些信息不会阻止所有本地工作。

## 开发前固定的工程规则

把已确认的产品约束和常用命令落入项目 AGENTS.md：jj 工作流、纯 Android／Material 3 Expressive、事件溯源边界、录音服务独立于 UI、事件重放无副作用、密钥与真实录音的存储规则、可重复构建和测试命令。保留用户已给出的 FastCtx 本地文件检查规则。对具体依赖版本做一次兼容性核查后固定，不仅因某个技能示例使用新 API 就升级整个构建链。

版本组合需要同时检查 Android Gradle Plugin、Gradle Wrapper、Kotlin、Compose、Material 3 Expressive 所需 API、Navigation 3／Adaptive、Room、WorkManager、Hilt 和 Java toolchain。先保证一个最小 APK 能构建和运行，再逐步接入功能。

## 建议第一开发阶段

第一阶段完成一个可验证的闭环：**小组件启动 → 原生录音服务 → 保存音频 → 写入业务事件 → Room 投影更新 → Compose 列表显示 → 播放**。

同时打好两项基础：

- 事件存储、版本检查、投影重建和去重的最小实现；用假 AI 服务验证 outbox、重试及结果回写。
- 真机与折叠模拟器验证切屏、息屏、权限拒绝和进程异常退出。只有实时服务确实在采集时才显示“录音中”。

随后的阶段接入真实转写／标题／总结，再实现长期记忆、跨录音问答和完整备份。首版依旧包含需求文档中的完整目标，此处只是实施顺序。

开源复用仍优先考察 Transcription 与 Dimowner 的录音等能力；引入事件溯源后需重新评估其现有写入路径和后台任务改造量。可复用其已验证的能力与组件，领域数据模型按本项目已确定的事件边界实现。

## 来源与验证范围

- [jj 安装与配置](https://docs.jj-vcs.dev/latest/install-and-setup/)
- [jj 与 Git／colocated 工作区](https://docs.jj-vcs.dev/latest/git-compatibility/)
- [jj 工作区与忽略规则](https://docs.jj-vcs.dev/latest/working-copy/)

环境结论来自实际可执行文件、Studio 的 SDK 路径配置、SDK 包信息、jj 本机帮助和 adb／模拟器查询。没有创建工程或执行构建，尚未确认依赖下载、模拟器启动和真机录音质量。
