# superx_demo 真机对照测试

状态：历史对照测试记录。当前「知伴」已移除原子岛、胶囊测试和诊断功能，不再推进下述 SDK 接入调查。

测试日期：2026-10-12。设备 vivo V2545A，Android API 37，系统版本 `PD2545_A_17.1.12.5.W10`。

## 已完成

- 原版 `superx_demo` 未修改源码，使用其 Gradle 8.11.1 / AGP 8.9.1 构建。设置当前进程 `ANDROID_HOME`，通过本机已有代理下载依赖后，`:app:assembleDebug` 成功（33 项任务，4 分 18 秒）。
- 包名 `com.vivo.systemuitesttophone`，版本 1.0；`adb install -r` 返回 Success，Activity 冷启动返回 Status: ok。这是独立 demo，未覆盖随声记。
- 授予 demo 通知权限，点击强调信息模板，返回桌面。捕获的状态栏未见 demo 原子胶囊；用户手动测试也报告不显示。
- 随后尝试测试进度可视化模板时无线 ADB 已离线；两个已发现端口均重连超时。未把该模板记为自动化测试完成。

APK：`superx_demo/app/build/outputs/apk/debug/app-debug.apk`。启动截图与强调模板桌面截图在本机忽略目录 `artifacts/superx-device-test/`；桌面截图仅用于本地检查，不加入版本库。

## 接入差异与 SDK 核对

demo 的 `getPermissionGrant()` 反射调用 `setSuperXInfosSceneList`；日志显示本机允许反射访问。调用配置包名是 `com.example.testxnotification`、场景是 `TRAIN`，而实际应用包名是 `com.vivo.systemuitesttophone`、两个原子模板发送场景是 `TAKEOUT`。访问方法成功不能证明已为实际包名和场景开通权限。未修改该配置，也未把它作为随声记的解决方案。

demo 附带 `docs/原子通知接入文档-release1.5.pdf` 第 4 页描述 VPush SDK 取得 regId，再由应用服务器通过 VPush 更新／结束通知的流程。原版 demo 的 Gradle 依赖不包含 VPush。该流程不能单独证明本地录音通知必须接入 VPush；当前公开技术规范的本地通知路径与该旧版文档中的云端路径需区分。

根目录 `VSM-1.1.7.jar` 的 `META-INF/plugin.xml` 明确声明它是 Android Studio IDE 插件（vivo SDK Manager，插件 ID `com.vivo.mobile.vsm.id`），依赖 IntelliJ platform 和 Android Studio。它提供 SDK 下载等工具，不能直接作为 Android 应用的原子胶囊运行库。JAR 成员名称检查未找到 SuperX / capsule 类或内嵌 AAR；这只是该 JAR 内的检查，不代表平台不存在另行提供的 SDK。

用户指出需要原子胶囊 SDK，已请求具体 SDK 名称、链接或本机路径，以确定下一步接入对象。当前没有证据足以将胶囊不显示的根因确定为缺 SDK 或场景准入，不能报告为修复成功。
