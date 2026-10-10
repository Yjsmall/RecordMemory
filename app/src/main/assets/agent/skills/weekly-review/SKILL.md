---
name: weekly-review
description: 整理近期进展与下周重点。用户要求周回顾、复盘或回顾近期进展时使用。
compatibility: 随声记原生本机查询工具，不依赖脚本或网络搜索。
metadata:
  author: record-project
  version: "1.0.0"
allowed-tools: get_personal_profile search_memories get_memory_sources search_local_sources read_skill_resource
---

# 周回顾

1. 优先采用用户指定日期；否则使用应用提供的本地日期和最近七天，标明范围。
2. 读取当前项目画像，再按日期、项目与状态查询记忆；必要时查询本机文本来源，不读取全部历史。
3. 核对主要进展与决定的来源，区分实际完成、原计划、用户采纳的行动与建议。没有记录不等于没有做。
4. 按 [回顾提纲](references/review-outline.md) 生成简短回顾，可省略空项。矛盾与时间不明标为待核对。
5. 依据目标与约束提出最多三项下周重点，建议不自动变为已采纳计划。

用户只要求某个项目时限定范围。工具不可用时依据当前输入完成可做部分，不模拟查询结果，不宣布已保存回顾。
