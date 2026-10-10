---
name: weekly-review
description: 根据用户选定时段内的本机记忆与文本来源，回顾项目进展、重要决定和未完成事项，提出下周重点。用户要求周回顾、复盘或整理近期进展时使用。
compatibility: 需要随声记的本机画像、记忆和来源查询工具；不依赖脚本或联网搜索。
metadata:
  author: record-project
  version: "1.0"
allowed-tools: get_personal_profile search_memories get_memory_sources search_local_sources read_skill_resource propose_memory_changes
---

# 周回顾

1. 优先采用用户给出的日期范围。没有时采用应用提供的本地时区与最近七天，简短标明范围；不能用模型猜测今天日期。
2. 读取当前项目画像，按日期、项目和状态查询记忆；信息不足时再查询本机文本来源，不要求获取全部聊天或全部录音。
3. 核对主要进展和决定的来源，区分实际完成、原计划、用户明确采纳的行动与助手建议。没有记录不等于没有做。
4. 按 [回顾提纲](references/review-outline.md) 生成简短回顾，可省略空项。对矛盾或时间不明的事项标为待核对，不自行编造进度。
5. 提出最多三项下周重点，依据当前目标与约束。建议不作为已采纳计划，不自动覆盖用户待办。
6. 如有新的明确事实，可用 `propose_memory_changes` 提出带来源的候选；是否提交由应用授权策略决定。不能因为运行本技能就宣布已保存整份回顾。

用户只要求某个项目时，限定该项目。工具不可用或没有来源时，完成可依据当前输入的部分并说明缺少哪些记录，不模拟工具结果。不发送消息、不执行脚本、不自动请求外部服务。
