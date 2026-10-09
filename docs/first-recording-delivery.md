# 首个可运行录音版本交付

交付日期：2026-10-09（Asia/Shanghai）。版本 `0.1.0`，应用「随声记」，`applicationId=dev.local.record`，最低 Android 10。vivo 真机尚未连接。

## 已实现

- jj Git 后端、colocated 工作区、仓库级 Git 作者身份复用、忽略规则；源码、文档和技能纳入管理，缓存、APK、数据库及录音不入库。
- 固定 Gradle 8.13 Wrapper 与 SHA-256、AGP 8.13.2、Kotlin 2.2.21、API 36 构建链；依赖及实验 API 边界见 README。
- 小组件启动可见 Activity，权限处理后自动开始；页面／小组件／通知共享单前台服务，重复开始不创建第二个会话。
- 原生 MediaRecorder：M4A / AAC、48 kHz、96 kbps、单声道；暂停、继续、停止保存；麦克风前台通知和息屏期间的局部唤醒锁。
- 先完成音频封装、校验音轨与时长、同步文件并重命名，再提交保存事实；失败不反馈“已保存”。
- Room 事件信封、事件／聚合版本唯一约束、expectedVersion、幂等命令、同事务投影及检查点；纯 reducer 与无副作用的投影重建。
- 录音库、详情、Media3 播放／暂停及进度拖动；配置重建保留同一录音服务和播放 ViewModel。
- 官方 Material 3 Expressive 主题；实际窗口与折叠信息驱动 Navigation 3 列表／详情 Scenes，浅深色、窄窗和大字体；edge-to-edge 安全区域。
- 启动恢复：识别未完成会话，验证已有音频后补记中断；未知事件版本中止重建并保留现有投影。恢复不打开麦克风。

候选源码与复用结论见 [录音复用评估](recording-reuse-evaluation.md)。未复制候选应用代码，没有 FFmpegKit 或网络请求依赖；ASR、AI outbox、搜索、智能标题、总结和记忆在后续接入。

## APK

文件：`D:/code/record/app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`3fc8ee63dc2f6c4ca86ef3f6fbb0de95b2b35e8b91456309b871379655ee8ed1`。

APK 签名验证通过。使用本机 Android debug key，证书 SHA-256：`77cbdca1979fbd52906490bc4e826b35dbf469689b422369100f962821b52c6a`。后续长期使用需保持 applicationId 与签名连续，正式签名另行规划。

## 已执行验证

| 验证 | 实际结果 |
| --- | --- |
| 最小 Compose APK | 在忽略目录 `artifacts/minimal` 独立构建成功，模拟器安装并冷启动成功 |
| 完整录音 APK | `:app:assembleDebug` 成功，签名验证通过 |
| 业务单元测试 | 7/7，通过纯回放、生命周期约束、空音频拒绝、事件序列化和中断事实 |
| Room 仪器测试 | 6/6，通过幂等、并发旧版本拒绝、无效命令回滚、清空投影后重建、未知 schema 拒绝和幂等键误用拒绝 |
| 平台录音与恢复测试 | 2/2，通过自动开始、重复开始、Activity 重建、暂停／继续、后台、停止 PendingIntent、真实 M4A 保存；Media3 实际播放／暂停和进度跳转；有效已发布／临时文件恢复、损坏临时文件保留及重复扫描去重 |
| Compose 仪器测试 | 3/3，通过选择详情后的保存状态恢复、中断录音反馈与播放入口、9 种窗口尺寸及 1.5 倍字号；最终配色修改后单独重跑 3/3 |
| 完整仪器测试 | API 36 手机模拟器合计 11/11，0 失败、0 跳过 |
| Android Lint / ktlint / Detekt | 全部任务成功；Lint 无错误，仍有固定旧依赖版本提示、XML 小组件兼容属性、保守空闲空间检查和 KTX／资源抽取建议等警告；Detekt 所启用规则 0 问题 |
| 折叠屏实际服务连续性 | Pixel Fold / API 36.1 AVD：同一录音两次折叠／展开、后台与息屏，PID、文件 ID 及 microphone FGS 保持；文件持续增长，页面停止后发布 M4A |
| 折叠录音格式与解码 | 128.277 秒、AAC、48 kHz、单声道；6013 个音频包，最大媒体时间戳间隙约 0.000001 秒；FFmpeg 全段解码成功 |
| 真正强制停止与重启 | 折叠 AVD 强制停止后旧会话补记 `RecordingInterrupted`，没有重启麦克风；此前已保存录音保留。本次系统留下的 2.56 秒 M4A 可恢复并播放；损坏文件路径另由仪器测试验证 |

完整验证日志：`artifacts/verification.log`；最终 UI 重跑：`artifacts/final-ui-check.log`；折叠过程与异常恢复：`artifacts/fold-smoke.txt`、`artifacts/process-recovery.txt`。测试报告在 `app/build/reports`。这些生成物忽略版本管理，文档保留验证摘要。

Gradle 的 connected 测试报告由最后一次运行覆盖，当前展示最终的 3 项 UI 重跑；11 项全量通过结果保留在完整验证日志中。

## vivo 验收与边界

详细操作见 [测试与 vivo 验收](testing.md)。重点是添加真实桌面小组件、权限拒绝／恢复、30 分钟录音、反复折叠／展开、后台与息屏、通知控制、来电／其他录音应用／麦克风开关、保存与回放。

模拟器验证不能证明实体手机的听感、麦克风竞争、OriginOS 电池策略、30 分钟可靠性或启动 p95。桌面宿主真实小组件点击需在 vivo 验收；自动测试使用同一启动 Intent 与停止 PendingIntent。不承诺用户强制停止或系统杀进程后继续采集。

本阶段使用单个 M4A 文件，不采用分段录制。进程异常退出可能留下未封装、不可播放的文件；只有通过校验的文件才恢复为可播放录音，其余保留并明确标记。音频目前在应用私有目录，卸载或清除数据会删除录音；内容导出与可控备份尚未实现，系统云备份和设备迁移已禁用。
