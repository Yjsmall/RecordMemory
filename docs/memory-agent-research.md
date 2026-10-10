# 私人助手记忆 agent：开源借鉴与改进方向

调研日期：2026-10-10。读取 GitHub 仓库 README、文件目录和所列关键源码／文档；固定本次提交快照。未安装第三方运行时、未执行上游代码、未上传私人数据、未复现上游准确率或成本基准。本次是设计调研，不改变 0.4.0 APK 的功能。

## 结论

优先借鉴 **Memobase 的用户画像与时间线、LangMem 的结构化记忆整理和工具接口**，补充 Letta 的常驻记忆块、主动回忆和定期整理思想。Mem0 用于参考事实提取、去重关联和日期处理，但其当前默认向量检索及追加策略不直接作为本项目后端。

实现继续采用 Kotlin、Room、本机文本检索与现有模型 provider。模型提出结构化变更，应用验证来源、版本、确认状态及删除规则，然后通过事件写入。没有找到可以直接加入现有 Android 工程、同时满足全部约束的现成 Kotlin 库；下列项目适合借鉴机制和测试场景，直接运行通常需要 Python／TypeScript 及额外服务。

SOUL.md 身份、应用内置且可启用／禁用的 skills、主动回忆工具与整套记忆生命周期的进一步设计见 [私人助手架构](architecture/personal-agent.md)，并附 [内置资源模板](examples/agent-workspace/README.md)。这些属于下一版方案，尚未加入 0.4.0。

## 选型比较

| 项目 | 最值得借鉴 | 本项目的采用方式 | 当前限制 |
| --- | --- | --- | --- |
| [Memobase](https://github.com/memodb-io/memobase) | 按主题／子主题维护用户画像，独立事件时间线，追加／更新／放弃合并，聊天缓冲后集中整理 | 将称呼、交流偏好、项目和目标组织成有来源的画像投影；临时事件另记时间 | 服务端为 FastAPI、Postgres、Redis；当前系统也有 Embedding 搜索。中文提取默认允许推断隐含个人信息，需改成有证据的候选 |
| [LangMem](https://github.com/langchain-ai/langmem) | 结构化 profile 与记忆 collection 分开，接收已有记忆进行整理，显式管理／搜索工具，前台与后台形成记忆 | Kotlin 实现变更计划和有边界的工具调用；模型与存储解耦 | Python 与 LangChain／LangGraph 生态；常见 store 示例使用向量，但核心整理接口不要求特定数据库，不能误判为必须使用 Embedding |
| [Letta Code](https://github.com/letta-ai/letta-code)（MemGPT / Letta 系列） | 常驻 memory blocks、主动消息搜索、周期性反思和记忆容量约束 | 小型个人画像常驻上下文；详细信息按需查找；整理只输出待审核变更 | TypeScript agent harness；MemFS 将记忆放入 Git，不能照搬到本项目私人数据存储；不引入自修改应用或任意脚本能力 |
| [Mem0](https://github.com/mem0ai/mem0) | 新事实与已有记忆的关联、近期去重、代词上下文、按对话发生时间解析相对日期 | 提取时提供有限相关记忆、消息和时间；模型关联已有 ID，应用复核证据 | 当前 V3 为 ADD-only，默认依赖 Embedding／向量存储；会提取 assistant 内容。不能把助手建议当成用户已采纳的事实；平台基准包含非开源优化 |

## 已核对的上游变化和源码

### Memobase

快照：`358c16bbc6d687937d79bc2f984a11c3be8da901`；提交日期 2026-01-11，GitHub 未标记归档，许可证标识 Apache-2.0。

- [README](https://github.com/memodb-io/memobase/blob/358c16bbc6d687937d79bc2f984a11c3be8da901/readme.md)：用户画像、事件、聊天缓冲、主题优先及上下文预算；这些是产品与架构描述，性能数字未在本项目复现。
- [中文画像提取](https://github.com/memodb-io/memobase/blob/358c16bbc6d687937d79bc2f984a11c3be8da901/src/server/api/memobase_server/prompts/zh_extract_profile.py)：主题／子主题、区分提及时间与事件时间。默认提示词鼓励心理推断，本项目应改写，不能原样移植。
- [中文画像合并](https://github.com/memodb-io/memobase/blob/358c16bbc6d687937d79bc2f984a11c3be8da901/src/server/api/memobase_server/prompts/zh_merge_profile.py)：`APPEND / UPDATE / ABORT`，保留日期并去除重复。这里的直接更新需改成经过应用审核的变更计划。
- [相关画像选择提示词](https://github.com/memodb-io/memobase/blob/358c16bbc6d687937d79bc2f984a11c3be8da901/src/server/api/memobase_server/prompts/pick_related_profiles.py)：按当前对话选择有限 ID。这只是一个可参考模块，不代表整个 Memobase 不依赖向量。

### LangMem

快照：`48e3c11f5bb527282c7d5339c6a87a0b35abccfc`；提交日期 2026-10-02，许可证标识 MIT。

- [用户画像指南](https://github.com/langchain-ai/langmem/blob/48e3c11f5bb527282c7d5339c6a87a0b35abccfc/docs/docs/guides/manage_user_profile.md)：通过 schema 维护单个当前画像，向整理器提供已有状态，而非每句话产生独立画像。
- [概念与核心接口](https://github.com/langchain-ai/langmem/blob/48e3c11f5bb527282c7d5339c6a87a0b35abccfc/docs/docs/concepts/conceptual_guide.md)：区分事实、经历和行为规则，说明 profile／collection 及前台／后台整理的取舍；core API 与具体存储解耦。
- [提取实现](https://github.com/langchain-ai/langmem/blob/48e3c11f5bb527282c7d5339c6a87a0b35abccfc/src/langmem/knowledge/extraction.py)：`existing`、结构化 schema、可配置的新增／更新／删除、受 `max_steps` 限制的整理步骤。模型调用不是事件 reducer，回放不能调用它。
- [工具指南](https://github.com/langchain-ai/langmem/blob/48e3c11f5bb527282c7d5339c6a87a0b35abccfc/docs/docs/guides/memory_tools.md)：按 namespace 管理和查询记忆。项目中工具的读写权限由应用决定。

### Letta / Letta Code

当前源码快照：`44d351ba06d33507756d7dd5b98fcbe1cc321565`；提交日期 2026-10-10，许可证标识 Apache-2.0。

- [Letta 原仓库说明](https://github.com/letta-ai/letta/blob/5bcdd177d70fa2b31a754cfcd801e77b2e1ab16a/README.md)：当前代码已迁移到 `letta-ai/letta-code`；原仓库 `archive` 分支是退役 V1 API server。旧教程需要先核对版本。
- [当前 README](https://github.com/letta-ai/letta-code/blob/44d351ba06d33507756d7dd5b98fcbe1cc321565/README.md)：memory blocks、消息搜索、dreaming／reflection、MemFS 与云端／本地模式。可借鉴分层和整理时机，不直接采用 Git 保存个人记忆或 agent 自修改机制。

### Mem0

快照：`b7ad69afda6b6ed030347c66d48a13e4de9dec08`；提交日期 2026-10-07，许可证标识 Apache-2.0。

- [当前 README](https://github.com/mem0ai/mem0/blob/b7ad69afda6b6ed030347c66d48a13e4de9dec08/README.md)：2026-04 算法改为单次 ADD-only 提取、实体关联及混合召回。其公开性能表描述托管平台，不能当成本地移植版的效果保证。
- [提示词](https://github.com/mem0ai/mem0/blob/b7ad69afda6b6ed030347c66d48a13e4de9dec08/mem0/configs/prompts.py)：当前 `ADDITIVE_EXTRACTION_PROMPT` 支持 `linked_memory_ids`、已有事实去重、近轮代词信息以及 Observation Date。文件仍有经典 `ADD / UPDATE / DELETE / NONE` 提示词，存在不等于当前默认主流程使用它。
- [当前执行路径](https://github.com/mem0ai/mem0/blob/b7ad69afda6b6ed030347c66d48a13e4de9dec08/mem0/memory/main.py)：默认使用 embedder／向量存储，提取调用引用新的 additive 提示词。本项目不直接引入该存储链路。

许可证标识来自本次 GitHub 元数据，许可证文件已下载。未来如移植具体代码或提示词，应复核固定快照的完整许可、保留要求及第三方依赖声明；本次没有复制上游实现到应用源码。

## 0.4.0 当前不足

目前已经具备来源、确认、纠正、忘记、事件回放和旧请求版本保护，适合作为下一版基础。但记忆 agent 仍较简单：

1. 回复与最多三条候选在一次请求中生成，缺少单独的整理流程和变更规划；模型不能主动调用记忆查询工具。
2. 去重主要依靠去空白／转小写后的正文指纹。同义表达可能重复；不同表达的矛盾偏好也不会形成明确的替换关系。
3. 记忆目前是类型加文本，没有结构化的当前画像、事实字段、有效期和项目状态。约定和已完成待办容易与长期偏好混在一起。
4. 召回主要根据当前输入的简单文本匹配，缺少按整段对话意图、项目、实体和日期过滤的查询。
5. 忘记后的保护较保守，会跳过关联整轮与依赖旧记忆的历史轮次。未来画像、摘要和合并需要完整来源依赖，才能更精确地清理派生内容。

## 建议的下一版顺序

### 1. 先让记忆正确更新

独立的 Memory Planner 接收有限消息、已有相关记忆及发生时间，提出 `ADD / REINFORCE / SUPERSEDE / IGNORE / ASK_USER`。`REINFORCE` 表示同一事实出现新的有效来源；`SUPERSEDE` 表示明确的新状态取代旧状态；`ASK_USER` 用于冲突不明，不靠模型置信度直接覆盖。

每项携带来源消息 ID、原文证据、目标记忆 ID／预期版本、主体、事实字段、作用域及时间。应用验证 ID、证据和版本，按确认策略提交；模型不能直接删库或宣布已记住。先在现有已确认记忆策略上迭代，再设计可选择的自动记忆模式。

例如：先说「我喜欢喝咖啡」，后来明确说「我现在戒咖啡了」。新状态应进入替换候选；确认后当前画像为“不喝咖啡”，旧偏好标为已替代，并保留必要的时间及来源。不能把两条互相矛盾的偏好同时当作当前事实。

### 2. 形成可查看的个人画像

以已确认事实建立三类投影：

- **关于我**：称呼、交流方式、明确的长期偏好和约束，小而稳定，常驻上下文。
- **正在做的事**：项目、目标、计划与约定，按当前话题加载，完成状态明确。
- **经历时间线**：有时间与来源的事件、偏好变化和已完成事项，需要时查找。

画像不是模型随意概括后独立保存的第二份真相。每个字段／摘要必须带源记忆与版本，来源变化后重建；不把猜测的性格、职业、位置或助手的建议自动写成个人事实。

### 3. 加入有边界的回忆工具

提供 `get_personal_profile`、`search_memories`、`get_memory_sources` 和 `propose_memory_changes`。查询用 Room 文本索引、适合中文的分词／二元字索引、类型／项目／日期过滤；必要时让模型改写查询和选择候选 ID，继续不使用 Embedding。

普通消息先使用小型画像。需要回忆时允许有限工具调用，限制轮数、结果数量、输入输出预算与取消范围。直接按 ID 访问也必须检查可见性，防止已忘记内容从工具返回。没有可用事实时自然说明并询问，不编造。

### 4. 最后加入整理与个性化评估

会话结束或用户主动要求时，在已有 AI 任务事件／outbox 架构下整理记忆，不阻塞聊天，不在投影回放中调模型。处理费用、网络恢复和重复运行规则应明确。

先用合成对话做可复现的质量集：同义去重、偏好反转、别人的事实、引用／假设、相对日期、已完成待办、跨会话代词、助手建议未采纳、忘记后重新提及、来源删除、画像重建和多 provider 格式差异。应用规则的确定性测试与模型提取质量评估分别报告；现有 mock 协议测试不等于真实模型理解能力通过。

跨来源合并要先建立多来源依赖关系，并测试删除任一来源后的重新计算；在此之前保留 0.4.0 的同来源合并限制。失效或过期不等于忘记：可以停止作为当前上下文使用，不能因此自动销毁用户原始历史。
