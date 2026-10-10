# 自动记忆规则与质量评估

2026-10-10：新增确定性自动确认规则、45 个非私人合成判定样例与离线模型响应评分器。自动确认默认关闭；本页不代表真实 provider 质量已通过验收。

`AutomaticMemoryPolicy.eligible(draft, sourceText)` 只允许显式 ADD、self、无时间／已有来源／时区／目标版本／澄清问题的普通偏好或项目事实。候选正文和证据必须逐字一致，并与整条来源去除首尾空白后的内容一致；不能截取一句话忽略引用、否定、隐私或额外主体。规则不依赖模型置信度。

偏好采用有限的完整中文单句集合：咖啡、茶、常见食物、回答长度与交流语言。项目范围限定为录音、笔记、待办、日历、天气、相册、学习、开源工具，逐字匹配「我在开发〔范围〕项目」或「我的〔范围〕项目使用〔Kotlin／Java／Compose／Android〕」。允许完整原句末尾有句号，保留原文。任意项目名、其他食物、英文句式、多句消息、解释、敏感项目内容与不明确陈述都进入待审核。这样的有限白名单有意降低召回，不能用词表扩大来替代隐私与上下文验证。

自动资格只是事务提交之前的一个门槛。来源版本、记忆版本、冲突及遗忘抑制仍由正常提交路径校验；REINFORCE、SUPERSEDE、ASK_USER、包含目标的 ADD 都不能自动确认。规则本身没有数据库、网络、调度或通知副作用。

合成集位于 `app/src/test/java/dev/local/record/ai/SyntheticMemoryQuality.kt`，含直接偏好、项目、引用、假设、临时状态、他人／多主体、敏感来源及项目名、冲突、更正、遗忘、编造正文、证据截断、身份／predicate／scope 错配和替代。手工标注与规则实现分开；评分器另有故意错误的输出夹具，验证重复误记、身份错误及错误覆盖确实被统计。

复现纯规则评估：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests dev.local.record.ai.AutomaticMemoryPolicyTest --tests dev.local.record.ai.MemoryQualityEvaluationTest
```

实际 RED：无规则的恒 false 实现导致接受样例及召回测试失败。最终上述两个测试类共 9 个测试，专项命令成功；普通运行会跳过 2 个需要环境变量的可选路径。本轮分别设置环境变量验证了导出与捕获响应评分，两个命令均成功。45 个合成规则样例的实测结果如下：

| 指标 | 结果 | 定义 |
|---|---:|---|
| 误记 | 0 | 自动保存但没有匹配独立 gold 的条目；重复也算误记 |
| 漏记 | 3 | 合成 gold 中有价值的普通事实没有自动保存；本集为未列入白名单的偏好、项目名与英文陈述 |
| 错误覆盖 | 0 | 自动保存结果含非 ADD 操作或目标／目标版本 |
| 自动保存召回 | 8 / 11 ≈ 72.7% | 匹配 gold 的已保存事实 / gold 事实；不是模型召回 |
| 调用数 | 0 | 本次仅执行本地规则和人工输出夹具 |
| token 与货币费用 | 未测 | 没有 provider 用量／报价，不估造金额 |

这个小集合只证明列出的回归边界，不是分布式质量保证。待审核不等于丢失：这里的漏记仅指未自动保存。真实 provider 的抽取质量、真实对话分布与自动确认后最终持久化结果需要分别验证。

离线真实响应评估不读取密钥，也不会调用模型。先导出 30 条独立合成来源（损坏候选的测试复用同一来源，不能作为不同 gold 的模型输入），把文件写到仓库外：

```powershell
$env:RECORD_MEMORY_EVAL_CASES = Join-Path $env:TEMP 'record-memory-eval-cases.json'
.\gradlew.bat :app:testDebugUnitTest --tests dev.local.record.ai.MemoryQualityEvaluationTest.exportSyntheticEvaluationInputsWhenRequested --rerun
Remove-Item Env:\RECORD_MEMORY_EVAL_CASES
```

另行获得授权后，将每条 `source` 用生产 MEMORY prompt、schemaVersion 2 及相同模型参数提交给真实 provider。需要独立记录 provider、模型实际版本、prompt 修订、采样参数、日期、超时与失败；不把密钥或私人内容写进文件。每条来源保留一个记录，包括空响应、失败和未返回 items 的情况。保存实际原始响应文本，不能用参考答案替换响应。

评分器输入是 JSON 数组，每个记录格式如下（示意格式，不是测量结果）：

```json
[{"caseId":"coffee","output":"实际 schemaVersion 2 JSON 响应全文","calls":1,"inputTokens":null,"outputTokens":null}]
```

完整文件必须包含导出来源的所有 caseId，每个恰好一次。`calls` 是实际尝试次数，`inputTokens`／`outputTokens` 使用 provider 返回的用量；没有用量就保留 null。未知、重复或缺少 caseId 会失败。解析失败计入 `parseFailures`，并保留已发生调用数；任一响应缺少用量时，总 token 标为未知。

```powershell
$env:RECORD_MEMORY_EVAL_OUTPUTS = Join-Path $env:TEMP 'record-memory-eval-actual-outputs.json'
.\gradlew.bat :app:testDebugUnitTest --tests dev.local.record.ai.MemoryQualityEvaluationTest.scoreActualCapturedProviderOutputsWhenRequested --rerun
Remove-Item Env:\RECORD_MEMORY_EVAL_OUTPUTS
```

结果写在 `app/build/test-results/testDebugUnitTest/TEST-dev.local.record.ai.MemoryQualityEvaluationTest.xml` 的 stdout 中，以 `CAPTURED_PROVIDER_AFTER_POLICY` 标记。使用生产解析器和自动确认门槛后，按正文、证据、类别、主体、predicate、scope 和有效时间匹配手工 gold；它评估真实模型响应经规则筛选后的结果，不是未经筛选的模型抽取准确率，也不模拟 Room 写入或证明最终业务副作用。货币费用需另用 provider 实际价格与实际用量计算；调用数不能称作货币预算。

本轮没有执行真实 provider 调用。导出路径实际生成了 30 条独立来源，完整捕获文件评分路径用仓库外人工夹具运行成功：一条正确咖啡响应保存、一条引用响应被拒绝、一条响应解析失败，其余返回空 items。评分输出为误记 0、漏记 10、错误覆盖 0、匹配 1/11、解析失败 1；夹具人为填写的 calls 合计 30 只验证计数，不是实际模型调用或费用。真实输出可按上述流程补测；任何夹具、mock 与本地规则结果都不标为真实模型质量。
