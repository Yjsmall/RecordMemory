# 历史检索与持续学习 Implementation Plan

**Goal:** 接通无 Embedding 的历史与转写查询、最近学习反馈、持久化授权整理与保守自动确认。
**Architecture:** 沿用 Room 事件与内容版本；查询不复制正文，任务投影保留持久意图，WorkManager 只唤醒已有授权任务。
**Tech Stack:** Kotlin 2.2.21、Room 2.8.4、Compose、现有 WorkManager；不升级依赖。
**Spec:** docs/superpowers/specs/2026-10-10-history-learning-design.md

## Global Constraints

- 纯原生 Android；不使用 Embedding；固定现有依赖。
- 使用 jj，不用 Git commit/rebase，不移动 main bookmark。
- 所有业务写入先追加事件，同事务更新投影；重放不能联网或调度。
- 忘记、删除、修订立即使来源读取及迟到结果失效。
- 真实私人内容、密钥、备份不得入库。

## Task 1: 历史检索

Files: 新增 data/HistorySearchRepository.kt 与 domain/HistorySearch.kt；修改 ai/AgentTools.kt、data/ConversationRepository.kt、domain/Conversation.kt；新增对应 unit/Room 测试。

接口：`HistoryQuery(query: String = "", fromDate: String = "", throughDate: String = "", project: String = "")`；`HistoryHit(contentId: String, ownerId: String, origin: String, text: String, observedAt: Long, zoneId: String, project: String = "")`；`HistorySearchRepository(db).search(query): List<HistoryHit>`；`read(contentId): HistoryHit?`。contentId 是不可变版本，查询返回有限片段。

- [x] 先测试日期／项目／关键词、旧转写版本不可读、忘记后整条来源屏蔽。
- [x] 实现读取来源与工具，校验来源用于回执和提交的路径。
- [x] 执行对应测试并报告实际结果。

## Task 2: 持久整理队列

Files: data/MemoryPlanningRepository.kt、ai/MemoryPlanner.kt、ai/AutomaticMemoryLearning.kt、AppGraph.kt、新增 ai/MemoryPlanningScheduler.kt；测试相关仓库／执行器。

接口：`MemoryPlanner.requestHistory(turnIds: List<String>): Int` 显式授权有限历史批次；`drain()` 处理持久 REQUESTED；`MemoryPlanner` 提供提交后 wake 回调，图中使用 WorkManager；MemoryPlanningInput 添加历史授权标志。root UI 调用 requestHistory。保持旧 public request 返回 Job? 兼容。`complete(..., autoConfirm: Boolean = false)` 使用 Task 3 的 `AutomaticMemoryPolicy.eligible(draft, sourceText)` 决定 ADD 候选资格。

- [x] 测试 REQUESTED 恢复而 RUNNING 中断、重复批次不重复、日预算和撤权。
- [x] 已有任务投影作为 outbox；入队后 WorkManager 持久唤醒，单执行器避免并发重复。
- [x] 历史批次最多 10 个已完成聊天来源；记录固定 MEMORY 模型、来源及调用限制。录音继续复用已有 AI 队列。
- [x] 自动每日最多 20 次；无自动重试 uncertain；单次源字符不超过 4000。
- [x] 集成 `autoConfirm` 偏好（Task 3/root 在 settings 添加默认 false），执行器提交前读取最新授权。
- [x] 执行对应测试并报告。

## Task 3: 合成评估与自动确认规则

Files: 新增 ai/AutomaticMemoryPolicy.kt、测试合成质量集及评估文档。不编辑 Task 1/2 或 UI/settings 文件。

接口：`object AutomaticMemoryPolicy { fun eligible(draft: MemoryDraft, sourceText: String): Boolean }`。资格只包含 ADD、self、直接完整普通偏好／项目事实，无时间／敏感／引用／假设／临时／多主体／冲突；完整性与敏感检查包括整条来源。规则与模型无关，根代理负责设置和提交接线。

- [x] 编写合成样例并先验证缺少规则时失败。
- [x] 实现极保守白名单，项目 scope 必须逐字；所有替代与澄清返回 false。
- [x] 报告误记、漏记、错误覆盖与可复现评估命令；不声称 mock 为真实模型质量。

## Task 4: 界面、反馈与交付

Files: AssistantViewModel、AssistantScreen、新增 HistoryLearningScreen／LearningFeedbackRepository；SettingsRepository、AiConfiguration、SettingsViewModel、AgentSettingsScreen；测试／交付文档。

- [x] UI 增加历史与录音检索及最近学习入口；输入保留在 ViewModel，不把私密文本写系统 saved state。
- [x] 日期 ISO 筛选，显示来源发生时间／来源类别并导航。
- [x] 明确选择历史消息和有限批次，确认上传所选文本及调用上限，取消可见。
- [x] 默认关闭自动确认，设置说明只保存通过严格规则的普通直接陈述；反馈区分待审核与已保存。
- [x] 检查联合变更并进行独立代码审查，修复重要问题。
- [x] 执行构建、单元、仪器和静态检查；更新交付文档。

## 实际验收

0.4.5 已实现；`assembleDebug`、75 项普通单元测试、Android Lint、ktlint、Detekt 通过，2 个可选质量评测路径另有专项成功记录。独立模拟器完整 AndroidJUnitRunner 135/135 通过。审查发现的录音删除与跨对话正文依赖传播均经过新增 3 项 RED→GREEN 和复核。合成规则评估 45 样例误记 0／漏记 3／错误覆盖 0，召回 8/11；没有真实 provider 质量或费用测量。详细命令、共享设备干扰及实际边界见 docs/history-learning-delivery.md。
