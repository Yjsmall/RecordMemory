# 私人助手工作区示例

**这是下一版应用运行时的配置模板，0.4.0 尚不能加载。**不是开发 Codex 的全局技能包，也不会自动安装到 `.agents/skills`。模板由本项目编写，没有真实个人数据。

未来可将此目录中的 `SOUL.md`、`AGENTS.md` 和 `skills/` 复制到手机文档目录，通过「设置 → 助手 → 工作区」选择该目录。也可添加独立技能目录，例如选择包含 `weekly-review/` 的 `skills/`。使用 SAF 授权，无需输入手机绝对路径。

- `SOUL.md`：编辑助手身份和交流风格。
- `AGENTS.md`：编辑工作方法；不授予额外设备或网络权限。
- `skills/weekly-review/SKILL.md`：示例周回顾，按需引用 `references/review-outline.md`。

用户画像和记忆保存在应用私有数据库，模板不提供可编辑的第二份 USER／MEMORY 真相。API Key 不写入这些文件。尚未注册的工具不会因为 Markdown 出现其名字就可用。

详细格式、权限、自动记忆与实现顺序见 [架构设计](../../architecture/personal-agent.md)。
