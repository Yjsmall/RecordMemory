# 项目开发约束

## Local file inspection

For reading, searching, and finding local files, prefer FastCtx MCP tools
`inspect_local_file`, `grep`, and `glob`. Batch reads with `files`; follow
the exact continuation parameters in Partial results. Pass plain absolute
filesystem paths, including for URI-shaped references. Use FastCtx `replace`
for mechanical replacement; use apply_patch for generated or semantic edits.

## 工程规则

- 纯原生 Android，Kotlin、Compose、Material 3 Expressive；依赖固定版本。
- 使用 jj 与 Git colocated 工作区。用 jj 整理变更，不混用 Git commit/rebase。
- 录音服务独立于 Activity；界面重建和折叠切换不能重启录音。
- 录音、AI 任务、记忆采用事件溯源。Room 投影可以重建，业务写入必须先追加事件并同事务更新投影。
- 回放只重建投影，不能启动麦克风、联网、通知或任务入队。
- 先发布可用音频，再保存成功事件；保留文件发布与数据库提交之间的恢复路径。
- API Key、签名密钥、真实个人录音、备份不能进入版本库。
- 不使用 Embedding；后续 ASR 文本在本机搜索。
- 按实际窗口和折叠遮挡适配，使用稳定兼容 API，不为技能示例升级到预览工具链。
- 常规工程选择自主推进；以真实构建和测试结果报告完成度。

构建与测试命令见 README.md 和 docs/testing.md。
