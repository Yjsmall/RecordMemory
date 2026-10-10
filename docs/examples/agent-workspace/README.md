# 私人助手内置资源示例

**这是下一版应用内置资源的开发模板，0.4.0 尚不能加载。**不是让用户指定的手机文件夹，也不是开发 Codex 的全局技能包；不会自动安装到 `.agents/skills`。模板由本项目编写，没有真实个人数据。目录名 `agent-workspace` 仅保留为内部示例名称。

开发阶段将 `SOUL.md`、`AGENTS.md` 和 `skills/` 纳入 `app/src/main/assets/agent/`，补充内置清单并进行构建校验，随 APK 发布。用户在「设置 → 助手 → 技能」启用或禁用技能；新增技能由开发者加入软件资源，不需要用户管理文件。

- `SOUL.md`：默认助手身份和交流风格；用户的个性化修改在软件中完成并私有保存。
- `AGENTS.md`：开发者维护的运行约定；不授予额外设备或网络权限。
- `skills/weekly-review/SKILL.md`：示例周回顾，按需引用 `references/review-outline.md`。

用户画像和记忆保存在应用私有数据库，模板不提供可编辑的第二份 USER／MEMORY 真相。API Key 不写入这些文件。尚未注册的工具不会因为 Markdown 出现其名字就可用。

详细格式、权限、自动记忆与实现顺序见 [架构设计](../../architecture/personal-agent.md)。
