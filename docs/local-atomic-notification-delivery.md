# 本地原子通知测试版

状态：历史测试记录。原子岛、胶囊测试和诊断功能已于 2026-10-12 从当前应用移除；下述入口、测试命令和 APK 描述仅对应当时的测试版本。

日期：2026-10-12（Asia/Shanghai）。版本：0.4.8 / versionCode 15。

使用 Android NotificationManager 与 SuperX Bundle 实现本地协议测试，没有新增 VPush、阿里云推送或其他 SDK 依赖，没有接入 VSM JAR。测试发送合成通知，不打开麦克风，不调用 AI，不向服务端注册 token。

## 入口与范围

设置 → 录音 → 录音通知 → 本地胶囊测试。可选择 HEALTH_REGISTER、TAKEOUT、TAXI、TRAIN，以及明确标注未确认的 TIMER 候选场景。

- 创建：operation=0，同一通知 ID 104。
- 更新：operation=1，合成计时从 00:00 改为 00:15。
- 结束并归档：operation=2、keepDuration=60。
- 清除：分别取消 VIVO_SUPERX_TAG 与无 tag 的 ID 104；一条取消路径异常不阻止另一条，诊断记录异常类型。

三种发送操作都保留完整的 scene、template、changedRecord、baseInfos、infos、shortInfos、capsule 和 island。强调信息模板 1，左岛模板 1、右岛模板 4，胶囊图标与文本；showNotify=true，关闭 autoCancel。序号只在 notify 返回后递增。关闭测试页不自动清除，便于返回桌面检查；需要用户主动清除。

页面分别显示最近发送尝试、已提交操作、场景查询和系统通知收据。在页面可见时刷新状态，返回桌面时停止轮询。通知被移除后更新按钮禁用；失败尝试不会继续显示成上一次成功发送。复制报告包含录音诊断与本地测试诊断，不包含录音正文或密钥。

这些场景用于合成测试，没有把录音伪装成外卖或医疗业务。实际录音的默认 TIMER 场景和服务通知 ID 100/101 保持独立；仍需确认录音应使用的场景与准入方式。测试模式不修改系统场景列表或白名单，也不使用 demo 的 vivo 测试包名。

## 实际验证

构建、单元测试、lint、ktlint、detekt 已在独立 jj 工作区执行成功，代码经过独立只读复审。同步回主工作区后，再执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug ktlint detekt`，BUILD SUCCESSFUL；主工作区与最终已测试版本的应用源码、测试源码及构建脚本比较无差异。模拟器最终运行 LocalAtomicNotificationTest 与 NotificationDiagnosticsDialogTest：OK (5 tests)，0 失败／跳过。覆盖完整协议的 Parcel 序列化、实际 notify/readback 的系统往返、创建／更新／结束、清除不影响另一通知、通知移除后的状态和自动界面刷新、报告复制／刷新／关闭。

新协议测试先因缺少实现实际失败；补齐后又发现并验证 shortInfos 遗漏、通知移除后的状态失真，以及 UI 未自动刷新，分别修正。真实通知测试会先确认创建收据，再检查更新和结束，不能以“调用没有抛异常”代替生命周期成功。

真实手机 vivo V2545A，Android 17 / API 37，系统 PD2545_A_17.1.12.5.W10。已通过无线 ADB 保留数据安装 0.4.8。本轮三个协议专项：2 通过、1 失败。完整字段序列化与移除通知后拒绝更新通过；TAKEOUT 创建的系统收据可读，但随后的 operation=1 更新收据没有在等待时间内变为 1，因此没有通过全部生命周期验收。

另用 probeDelayMs=12000 尝试延长操作间隔；该运行未完整结束，已终止测试进程，不作为通过结果，也不能据此判断刷新频率限制是否适用于本地通知。未终止正在运行的录音服务，终止前确认本应用无录音服务。

手动进入测试页，HEALTH_REGISTER 与 TAKEOUT 场景查询均返回未开启；HEALTH_REGISTER 创建后返回桌面，没有出现计时原子岛。通知图标／普通通知收据不等同于原子胶囊。仍未确认本应用的原子通知场景准入状态，不能宣称这版已经让 vivo 原子岛显示成功，也不能把未集成 VPush SDK 定为唯一原因。

## 复现

普通协议测试不依赖 UI 自动化连接。API 33+ 运行前显式授予通知权限；测试类会检查通知总开关，授权缺失时给出明确前置条件，而不是等待超时。手机锁屏可能拒绝安装，请解锁后允许系统安装提示。

```powershell
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell pm grant dev.local.record android.permission.POST_NOTIFICATIONS
adb -s DEVICE_SERIAL shell am instrument -w -r -e class dev.local.record.audio.LocalAtomicNotificationTest dev.local.record.test/androidx.test.runner.AndroidJUnitRunner
```

APK：`app/build/outputs/apk/debug/app-debug.apk`；带版本副本：`artifacts/apk/record-0.4.8-local-atomic-debug.apk`。主工作区副本 SHA-256：`ABBA2E930E67A90A49074FF7DC8936B7833145816808B516F424FECB8280F01E`。

本机设备截图和测试输出留在临时目录 `codex-record-wireless`，未把真实录音或手机截图纳入版本库。手机已安装最终源码对应的 0.4.8；最后一次页面复核时无线调试离线，重新连接超时，未重复进行此后的手机验收。

参考：[vivo 技术规范](https://dev.vivo.com.cn/documentCenter/doc/896)、[阿里云 vivo 原子岛指南](https://help.aliyun.com/zh/document_detail/3030718.html) 与用户提供的 `superx_demo`。
