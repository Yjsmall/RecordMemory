# 录音实现复用评估

评估日期：2026-10-09。依据需求文档的候选表，并追加读取以下固定提交的源码。没有构建或真机验收候选应用，不把候选源码视为已验证组件。

| 候选与固定提交 | 检查范围 | 本阶段判断 |
| --- | --- | --- |
| Transcription `ebbc3d141353d774b88af359315a54df7804e788` | `recording/RecordingService.kt`、`widget/TranscriptionWidgetProvider.kt`、`app/build.gradle.kts` | MediaRecorder/AAC/M4A、暂停、通知、小组件链路可参考；服务同时承担转写、重试、清理、复制等职责，与自有容器和历史存储紧密耦合。直接二开仍需重写业务写入和恢复边界。构建包含 FFmpegKit，本阶段录音无需该依赖。 |
| Dimowner/AudioRecorder `28ef142524f38f66a3793f9a4d2f796c981a5067` | `TransparentRecordingActivity.kt`、`v2/audio/AudioRecordingService.kt` | `onResume` 中启动服务的可见 Activity 路径和单服务状态源值得参考。v2 服务依赖旧新容器、音频委托、偏好、数据源、分析和播放服务，直接抽取改造范围较大。 |
| Fossify Voice Recorder | 沿用已有 GPL-3.0 调研结论，本次未追加抽取源码 | 不复制 GPL 代码，避免在尚未确定分发许可时绑定整应用底座。 |

本阶段选用 Android 官方 `MediaRecorder`，自主实现小组件入口、麦克风前台服务和事件事务边界，没有复制以上候选源码，没有引入候选仓库或 FFmpeg。导航布局使用 Jetpack 官方库 API，未拷贝示例实现。

用户指定使用 M4A：AAC-LC、48 kHz、96 kbps、单声道；实际编码结果在设备上由平台编码器决定。暂停与继续调用 MediaRecorder 原生 API。成功停止后关闭封装，读取元数据确认音轨和非零时长，同步文件，再在同一文件系统重命名为 `.m4a`，最后提交 `RecordingSaved`。

文件与数据库不能共同原子提交。进程启动时检查尚未结束的历史：已发布 M4A 可以补记为中断并恢复播放；临时 M4A 只有通过音轨和时长校验才会发布。未完成封装的文件保留，标记无可播放音频，不承诺可恢复。暂不采用分段 M4A，因此强制停止或进程被杀可能丢失当前一段；已经正常保存的录音不受影响。

平台依据：

- [麦克风前台服务](https://developer.android.google.cn/develop/background-work/services/fgs/service-types)
- [前台服务启动与使用时权限限制](https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start)
- [音频输入共享及静音](https://developer.android.google.cn/media/platform/sharing-audio-input)
- [MediaRecorder](https://developer.android.google.cn/reference/android/media/MediaRecorder)

与本阶段无关的 ASR、AI outbox、文本搜索、智能标题、总结、记忆、删除和备份留到后续，录音保存不会创建网络任务。当前 APK 不声明网络权限。
