# 原子岛与类似灵动岛效果：网页检索结果

核对日期：2026-10-10。目标：vivo V2545A / OriginOS 7 / Android 17 API 37，随声记自己的录音在桌面有收起计时胶囊，展开后暂停／继续与保存。本次是接入路径研究，没有安装第三方软件、修改手机系统权限或声称真机显示已解决。

## 当前未解决的原因与遗漏

0.4.6 实现了 vivo SuperX 基础模板的操作字段，但没有在 vivo 真机上观察到渲染。用户报告中 `getSceneStatus(dev.local.record, TIMER)` 返回 false，历史 notify 已返回，当时普通录音及原子请求都不存在。这支持继续核对 vivo 场景开关／准入，不能证明所有第三方实现路径均不可行，也不能单独证明系统拒绝原子通知。

研究确认此前遗漏 Android 标准 Live Updates 路径。项目现有 `androidx.core:core-ktx:1.17.0` 已支持 `NotificationCompat.Builder.setRequestPromotedOngoing()` 与 `setShortCriticalText()`；当前 `RecordingService.notification()` 未申请提升，Manifest 未声明 `android.permission.POST_PROMOTED_NOTIFICATIONS`。普通通知的 ongoing、计时器和 importance=3 本身不会替代这些步骤。

## 1. vivo 原生原子岛

官方 [接入指导](https://dev.vivo.com.cn/documentCenter/doc/894) 明确要求上架、提交场景申请、需求与 UI 评审、平台开通权限及联调。核对了当前公开文档目录 `/webapi/doc/tree`，原子通知栏目仍列出 894、895、896 三份文档，没有从该目录发现独立 OriginOS 7 录音开放接口。

官方 [技术规范](https://dev.vivo.com.cn/documentCenter/doc/896) 规定本地通知 Bundle、胶囊与岛字段、基础模板多图片操作。模板 4 的 `subInfo=4` 支持最多三个图标及对应 PendingIntent，这是 0.4.6 已接入的路径。[设计规范](https://dev.vivo.com.cn/documentCenter/doc/895) 提及录音计时文本／声音场景动画，但不能据此推定 `TIMER` 是本应用已获准的录音 scene。

这条路径最接近系统录音机的系统集成效果。未知：本包场景准入、正确录音 scene、系统录音机是否使用同一模板或专有协议，以及内外屏实际渲染规则。

## 2. Android 标准 Live Updates：优先补齐并验证

[创建 Live Update 通知](https://developer.android.com/develop/ui/views/notifications/live-update) 给出提升条件：标准通知样式；声明 `POST_PROMOTED_NOTIFICATIONS`；调用 `setRequestPromotedOngoing(true)`；ongoing；有标题；无自定义 RemoteViews；非分组摘要；不 colorized；渠道不是 IMPORTANCE_MIN。文档要求活动正在进行、由用户启动、具有持续关注价值；没有专门承诺本应用录音场景获准，需评估并验证。官方也明确 OEM 可以增加资格条件。

[AndroidX Builder 文档](https://developer.android.com/reference/androidx/core/app/NotificationCompat.Builder#setRequestPromotedOngoing(boolean)) 标注提升请求和短关键文本方法从 1.17.0 可用，与项目现有版本一致。平台 [Builder](https://developer.android.com/reference/android/app/Notification.Builder#setRequestPromotedOngoing(boolean)) 和 [权限](https://developer.android.com/reference/android/Manifest.permission#POST_PROMOTED_NOTIFICATIONS) 文档标注提升 API／权限从 36.1 加入；项目编译 API 36，可用现有兼容库和 Manifest 权限字符串处理，运行时能力探测必须考虑 36.1 与旧平台，不能直接无保护地调用新平台方法。

官方状态栏 chip 支持 `setWhen`、chronometer 和短文本。当前录音通知已有 chronometer；暂停时停止，普通通知提供两项 action。补齐提升请求后，还需观察 `canPostPromotedNotifications()`、`hasPromotableCharacteristics()` 和 `FLAG_PROMOTED_ONGOING`。用户关闭提升开关时可以使用官方 `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 入口。权限不是普通 runtime 弹框，不能将 POST_NOTIFICATIONS 的结果当作提升状态。

可验证目标：系统是否提升普通录音通知、是否显示计时 chip、暂停／继续／保存是否有效。不能预先承诺 vivo 将标准 Live Updates 映射为原子岛，更不能保证与系统录音机展开大卡相同。0.4.6 APK 不包含此接入；本次研究后已在 0.4.7 增加标准路径，实际验证结果见下方交付记录。

## 3. 应用悬浮窗：实现类似外观与交互

[Android 悬浮窗权限](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW) 允许应用在获得用户明确授权后，通过 `WindowManager.TYPE_APPLICATION_OVERLAY` 显示跨应用控件。[窗口类型文档](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY) 明确其位于 Activity 上方、状态栏和输入法等关键系统窗口下方，因此不能承诺替代摄像头周围的系统原子岛或盖过系统状态栏。

可自行绘制黑色圆角胶囊、计时和声波，点击展开暂停／继续／保存按钮。录音服务持有录音；悬浮窗只订阅 SessionState 并发送既有控制命令。只显示本应用录音，不需要为此读取其他应用通知或授予无障碍权限。适配窗口变化、折叠内外屏、刘海／打孔、IME；保存、服务结束、锁屏及撤回授权后按规则移除。具体位置与 ROM 限制需真机测试。悬浮窗权限是用户可选的功能授权，不能自动替用户开启。

搜索得到 [DynamicIslandMusic](https://github.com/bguerraDev/DynamicIslandMusic)，其 README 明确列出 Compose、悬浮窗、收起／展开状态机、前台服务和 WindowManager；可作为架构参考。本轮没有复制其代码，也没有验证其发布版或在 vivo 的效果。

## 社区路径的限制

- [Origin Isle](https://github.com/fvhde/origin-isle)：README 限定 vivo／iQOO OriginOS 6；[开发文档](https://github.com/fvhde/origin-isle/blob/main/docs/DEV.md) 说明借用 `com.autonavi.minimap` 包名使隐藏场景注册进入白名单，与高德安装冲突。不能当作 `dev.local.record` 在 OriginOS 7 可直接使用的库或证据。
- [vivo-islandfix](https://github.com/000896979/vivo-islandfix)：README 描述 Root／LSPosed Hook 的 MediaSession 音乐白名单解除，目标 Android 16，属于音乐卡片路径。不能证明录音原子岛接入，也不适用于普通安装包。

## 建议与验证顺序

先补齐标准 Live Updates 提升请求及可复制诊断，复用现有依赖，用运行时能力探测保持旧系统兼容。模拟器检查资格、提升 flags、实际服务控制与计时，再由目标 vivo 真机确认系统表现；同时保留官方 SuperX 场景核对。

若标准接口在目标 ROM 仍无法显示，且用户主要目标是桌面直接看计时和控制录音，则设计一个用户可选的悬浮录音胶囊。它可以实现相似的交互，但要明确悬浮窗与系统原子岛的区别。若用户必须要原生系统岛，就继续 vivo 场景接入，必要时在用户连接真机后只读对照系统录音机通知信息；不能由截图猜测内部协议。

检索覆盖：Bing 搜索、GitHub 仓库搜索及上述官方正文。Google 与 DuckDuckGo 返回挑战／脚本跳转，未作为有效搜索结果。社区 README 属于维护者声明，未当作 OriginOS 7 运行验证。

## 0.4.7 应用侧接入

- Manifest 声明 `POST_PROMOTED_NOTIFICATIONS`；使用现有 AndroidX Core 1.17.0 申请标准录音通知提升，没有升级预览工具链或依赖。
- 仅录音／暂停状态请求提升，准备／保存状态不请求。录音状态保留 chronometer，并让短关键文本为 null；暂停状态停止 chronometer，以冻结计时作为短关键文本。既有暂停／继续与保存操作不变。
- 诊断读取系统允许、活动录音通知的提升请求、格式资格与系统设置的 `FLAG_PROMOTED_ONGOING`。标志只读，不由应用强设；读自身通知，不输出正文。API < 36 明确显示不支持，API 36 的季度版本／ROM 方法差异以异常类型呈现，不使录音中断。
- 标准 Live Updates 与 vivo `TIMER` 的场景状态分别报告。某一路径返回 false 不能用于推断另一路径结果，也不能将实际提升 flag 当作 OEM 胶囊外观证据。

本版未增加悬浮窗权限和行为；原生录音旗帜标记也未加入。仍需目标 vivo 保持录音时返回桌面检验，若未显示，再复制新版本报告中的 Live Updates 字段用于判断系统允许／格式资格／实际提升的哪一步未满足。

实际验证：

- 红阶段：修改真实录音通知／诊断回归测试后，旧版两项测试分别因未申请提升、缺少提升诊断失败；补齐实现后通过。
- `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`、`ktlint`、`detekt` 成功；单元测试 85 通过、2 既有评测项跳过、0 失败／错误。
- API 36.1 折叠模拟器的 `RecordingFlowTest`、`NotificationDiagnosticsTest`、`NotificationDiagnosticsDialogTest` 共 5 项通过，0 失败／跳过。最后一轮还验证录音短文本为 null（允许计时器显示）、暂停短文本等于冻结时长、格式资格 true、暂停后保存及 M4A 播放。
- 最后一次真实服务测试从诊断读取到：`Live Updates 系统允许：true`；`Live Updates 请求=true；格式资格=true；实际提升=true`。只记录提升元数据，不输出通知正文。这是模拟器系统返回的提升标志，不是 vivo 原子岛渲染证据。
- 独立只读复审核对了 AndroidX Core 1.17.0 方法兼容性，未发现阻断问题；补齐了暂停短文本／录音计时器优先的测试缺口。
- APK：`artifacts/apk/record-0.4.7-debug.apk`，包名 `dev.local.record`，版本 0.4.7 / versionCode 14。SHA-256：`5622685402CDA74DA0F7B7CBB6CA4D1629FC2C6135D1708A958D17F49DA2551A`。

仍未验证 vivo V2545A / OriginOS 7 / API 37 对标准 Live Updates 的胶囊显示、展开布局及内外屏表现；没有把模拟器提升成功当作该机原生原子岛已修复。
