# 结构化记忆、冲突与范围遗忘

日期：2026-10-10。版本：0.4.3（versionCode 10），Room 4→5。沿用 0.4.2 的独立 MEMORY 模型、手动整理、默认候选审核和进程级运行。此版本交付阶段 C 的第一组结构化能力，完整私人 agent 仍分阶段建设。

## 使用与实际行为

- 对话完成后点「整理记忆」。新计划采用 schemaVersion 2；普通回复仍不会顺带提取、纠正或删除记忆。未配置 MEMORY 不借用 ANSWER 调用。
- 候选显示主体、事实范围、明确的生效／截止日期、逐字证据及来源。未知字段保持未知。当前已登记咖啡、饮茶、饮食、交流方式、称呼、项目、待办和约定；未能确定登记范围的内容提出澄清问题，不能假造结构。
- ADD 提出新事实；REINFORCE 提出相同事实的新依据，确认后增加来源而不重复造已确认卡片；SUPERSEDE 展示新旧差异，确认后原子替代旧事实。ASK_USER 不能直接确认，需补充说明后重新整理，或在记忆页明确纠正关联的旧事实。
- 补充依据不得更改正文和有效日期；替代／补充目标必须来自本轮有限上下文，主体、谓词、范围和 expectedVersion 都需匹配。候选形成后旧事实变化，旧确认命令整项失败，不发生部分替代。ADD 遇到已有同范围事实会降为澄清候选。
- 新事实的日期缺省为未知；日期只允许来源中明确的 ISO／中文完整日期，或根据消息原始发生时间和时区解析今天／昨天／明天。新消息在事件中记录时区；旧消息缺失时区时不解析相对日期。截止日期不含当天。未生效或已过期事实退出当前画像及对话上下文；未来的替代方案必须到生效日期后再确认，不提前移除当前事实。
- 记忆页查看每个来源的依据与日期，可打开对应原对话。不同主体的结构化事实放在「其他事实」，不会作为用户本人的默认偏好。
- 手动纠正结构化卡片是在该主体和范围内修改值，日期重置为未知；修改本身成为独立的用户依据。旧来源正文副本与受影响回复会清理，之后删除旧对话不撤销这次用户纠正。

## 来源、遗忘与重建

来源使用不可变 contentId 作为修订标识，保存片段起止位置、原始发生时间、时区和来源类型。模型不能自行提供来源记录。证据必须逐字匹配当前原文；来源被删除、修订或目标版本变化时不能提交。

每个事实最多保留 20 个独立来源。删除一个对话只撤销该来源；仍有有效证据时重写来源投影和依据，删除旧版本副本及其派生回复，全部来源撤销后事实失效。用户纠正形成的独立依据不会因旧会话删除而消失。回放只消费事件和保留正文，不重新运行模型或创建任务。

忘记结构化事实会清理同主体／谓词／范围内的候选、当前及已替代事实正文和受影响派生内容；保留最小抑制键及可查看的范围名称。普通新消息的同义候选在键一致时被拦住，不因换一种值的说法自动恢复。记忆页「防重新学习范围」提供二次确认的「允许重新学习」入口，只允许未来的新候选，绝不恢复已删除正文。原始用户消息仍按独立删除范围保留。

范围名称也含隐私，存于可删除 contents 中；事件仅引用其 ID 和抑制哈希，解除时删除名称正文。事实正文、结构、来源证据和候选方案同样存于可删除内容，Room JSON 列是可重建投影。终结事件清空这些投影；已替代事实可追溯，但不用于当前画像。重建不调用 provider、网络、文件发布或 outbox。

升级保留旧记忆 ID、来源、状态、正文和既有墓碑，新增结构为空；不会把旧候选提升为已确认，也不会凭模型补填旧日期。依赖和工具链版本没有升级。

## 验证

最终构建、36 项单元测试、Lint、ktlint 与 detekt 全部通过。当前连接的 Record Foldable API 36 / Android 16 模拟器（emulator-5556）通过 63 项专项仪器测试，零失败／跳过。早一轮在两台模拟器执行了 58 项，重建断言暴露列表顺序无保证的问题；改为按 ID 比较事实后，折叠模拟器的中间 60 项和最终 63 项均通过。最终结果不把早期双设备执行计为最终版本双设备验收。

新增验证覆盖替代前后的画像、版本竞争原子回滚、同义新消息抑制、明确解除且正文不复活、被替代历史清理、多来源部分撤销、手动纠正独立证据、主体混淆、相对日期和未来替代。Compose 测试覆盖澄清候选不能直接确认、纠正关联旧事实、遗忘范围可见及解除需二次确认；既有对话、录音仓库、HTTPS 请求、迁移和记忆界面同步回归。

实际最终命令：

```powershell
.\gradlew.bat ktlintFormat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug ktlint detekt '-Pandroid.testInstrumentationRunnerArguments.class=dev.local.record.data.MemoryKnowledgeRepositoryTest,dev.local.record.data.MemoryPlanningRepositoryTest,dev.local.record.data.ConversationMigrationTest,dev.local.record.data.ConversationRepositoryTest,dev.local.record.data.ProcessingRepositoryTest,dev.local.record.data.RecordingRepositoryTest,dev.local.record.ai.PersonalAssistantTest,dev.local.record.ui.AssistantScreenTest,dev.local.record.ui.InsightUiTest,dev.local.record.ui.MemoryKnowledgeUiTest'
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。报告：`app/build/reports/tests/testDebugUnitTest`、`app/build/reports/androidTests/connected/debug`、`app/build/reports/lint-results-debug.html`。导出的 Room schema 为 `app/schemas/dev.local.record.data.RecordDatabase/5.json`。

## 尚未完成

范围抑制依赖登记谓词及一致的主体／项目名称，并不是任意自然语言主题删除。主体别名（妈妈／母亲）、项目改名、模型选错谓词和无结构的旧记忆尚无自动归并；这些不能被声称为完全解决同义复活。防主体错误采用来源／正文校验和对明显多个主体的保守拒绝，不是完整自然语言理解。录音侧的旧提取流程仍产生文本候选，本轮结构化规划从用户对话消息开始，多来源也先限于对话与用户纠正。

未提供定时自动生效替代、自然语言授权命令、自动记忆、后台批次整理、可删会话摘要、完整经历时间线、原生工具循环、SOUL 身份编辑和内置 skills。未来替代仍须用户到期确认；事件重建不会替用户确认。真实模型长期语义质量／成本评估与 vivo 实体折叠验收仍待执行，合成 HTTPS 假服务与模拟器结果不能代替它们。
