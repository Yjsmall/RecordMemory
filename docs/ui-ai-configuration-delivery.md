# UI 与 AI 配置交付 · 0.2.0

日期：2026-10-09。此阶段实现原生设置与 AI 配置，保留 M4A 录音闭环。实际转写、智能标题、总结及记忆任务留在下一阶段；不将配置成功表述为这些业务功能已经完成。

## 可操作的界面

- 首页按录音日期分组；首页和详情提供设置入口。录音库沿用 Navigation 3 Adaptive Scenes，宽窄窗口的选中记录和播放状态保持。
- 设置包含多个 provider、五项独立能力绑定、模型名、语言、提示词、Responses 推理强度、外观和配置迁移。宽窗限制表单阅读宽度，窄窗使用可滚动表单，父 Scaffold 处理系统栏／IME。
- 编辑草稿放在 Activity ViewModel 中，折叠、Activity 重建不会丢失。密钥不放在 rememberSaveable／SavedStateHandle；进程被杀后未保存草稿可能丢失。返回提供放弃或继续编辑。
- 设置与编辑页保留真实的录音状态、暂停／继续、停止保存按钮。不会因页面切换重启录音。浅／深色、系统跟随和 Android 12+ 动态配色可保存。

## Provider 与协议

| 配置 | 默认地址与协议 | 模型与凭据 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` + `/responses` | 官方中文文档当前列出 `deepseek-flash`、`deepseek-v4-pro`；Bearer API Key；可配置推理强度 |
| 自定义 Responses | 自填 HTTPS Base URL、`/responses` 和 `/models` 路径 | 精确模型 ID；Bearer 或无鉴权；不推断所有兼容服务都支持相同参数 |
| 自定义 OpenAI 兼容 | 自填 HTTPS Base URL、`/chat/completions` | 精确文本模型 ID；服务确实提供同步转写时可启用独立 `/audio/transcriptions`，ASR 模型另配 |
| 豆包语音 | `https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash` | 语音 `model_name=bigmodel`、`Resource ID=volc.bigasr.auc_turbo`；仅新版 API Key；旧配置保留可读，编辑迁移需重填新版密钥 |

DeepSeek 文本预设不默认开放语音转写；豆包语音不作为文本生成连接。默认添加 DeepSeek Responses，可改用其他预设。更换预设或鉴权类型会要求重填凭据，避免将旧凭据沿用到不同服务。保存配置本身不联网。

用户所说的 “web search” 已澄清为查阅 DeepSeek 官方文档；没有据此增加应用内联网搜索。原计划中的文本搜索仍在本机执行，不使用 Embedding。

连接检查分三种，不混淆结果：

1. 文本 provider 查询模型列表，验证地址／鉴权／响应格式；模型出现在列表里不证明支持转写或文本生成。
2. 文本能力的“测试文本模型”在页面说明并确认后调用所选协议，只发送固定合成检查文字，不发送录音、原文或用户提示词。Responses 使用 `input`／`instructions`，Chat Completions 使用 `messages`；都不跟随重定向，设置 token 上限，Responses 明确 `store=false`。只接受完成的可用文本，忽略 reasoning 和工具项，未完成／失败不报告成功。此测试可能消耗服务 token。
3. 豆包不显示连接／配置测试入口；保存时仅校验本地格式，不伪造模型列表请求或以非识别请求声称语音鉴权成功。真实音频上传、识别与结果事件入库尚未实现。

目前读取的豆包极速识别官方文档列出 WAV／MP3／OGG OPUS，没有列出 M4A。后续可用临时上传音频适配格式；原始归档仍保持 M4A，本阶段没有声称已经完成转换或豆包真实识别。标准版 submit/query 与其他新语音协议不在此配置适配器的已实现范围中。

## 持久化与迁移

DataStore 1.2.0 的一次 updateData 原子保存普通设置与凭据。整个私有文件用 Android Keystore AES 密钥进行 AES-GCM 加密，保存在 noBackupFilesDir。损坏或解密失败时显示读取失败，不静默清空；录音库继续独立工作。

录音事件仍由 Room 保存；普通配置及诊断不写成 AI 业务事件。后续真实 AI 任务仍需事件／outbox 同事务、保存实际输出和来源版本、重放无外部副作用。

JSON schemaVersion=1 导出只序列化非密钥配置；API Key、Access Token、验证断言及音频不导出。通过系统文件选择器导入，限制 1 MB，拒绝不兼容格式、重复 ID／能力和不存在的引用。合并增加新 ID，保留同 ID 的本地连接与密钥、已编辑能力／模板及外观；未知协议保持待配置，不静默切换。旧配置缺少新增字段时采用兼容默认值。

仅支持 HTTPS、正常证书校验及配置内固定路径；不支持全局明文 HTTP、任意私有证书跳过、自定义敏感请求头或签名协议。未知服务需相应适配器，不能仅凭 Base URL 宣称通用兼容。

## 文档核对

本轮实际获取并读取以下官方页面，DeepSeek 以用户提供的中文文档为准：

- [DeepSeek 首次调用 API](https://api-docs.deepseek.com/zh-cn/)：Base URL、当前模型名、Bearer 鉴权。
- [DeepSeek 使用 Responses API](https://api-docs.deepseek.com/zh-cn/guides/responses_api) 与 [接口参考](https://api-docs.deepseek.com/zh-cn/api/create-response)：`/responses`、input／instructions、无状态、推理强度和输出 items；服务不支持 previous_response_id／持久服务端会话，不能把完整 OpenAI 功能都当作支持。
- [OpenAI Responses 迁移文档](https://developers.openai.com/api/docs/guides/migrate-to-responses)：两种协议的请求与响应结构、默认存储行为。
- [豆包录音文件极速识别 HTTP](https://www.volcengine.com/docs/6561/1631584)：Flash 路径、两种鉴权、Resource ID、模型及格式限制；页面位于官方历史文档栏目。
- [豆包录音文件识别标准版 HTTP](https://www.volcengine.com/docs/6561/1354868)：submit/query 与语音模型资源区分，作为后续异步流程参考。

模拟器／本机假服务通过不等于真实 DeepSeek 或豆包账号已经开通相应权限。没有使用真实 API Key，也没有向云端上传个人录音。

## 验证与 APK

已执行结果：

- 16 项单元测试通过（7 项录音业务、9 项配置／协议），报告 `app/build/reports/tests/testDebugUnitTest`。
- 手机 API 36 模拟器完整执行 26 项仪器测试通过；覆盖原录音／Room／UI 及加密配置、HTTPS 模型列表、Responses／Chat 诊断、provider／ASR 绑定、Activity 重建。完整日志 `artifacts/providers-verification.log`，Gradle connected 报告含这一轮完整结果。
- 最终 APK 在 Pixel Fold API 36.1 模拟器执行 16 项相关仪器测试通过（3 项录音 UI、6 项设置 UI、1 项真实 Activity 重建、4 项连接检查、2 项文本模型检查），日志 `artifacts/providers-fold-final.log`。
- 最终 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`、`ktlint`、`detekt` 通过，日志 `artifacts/providers-final-build.log`。Lint 无错误，仍有原有兼容／依赖更新类警告。最终协议调整后没有使用真实云服务测试。
- 实际折叠模拟器在设置草稿中输入合成密钥，折叠／展开保留页面与输入；外屏软键盘下输入框和顶部保存可见。设置页中启动录音、再次折叠后从设置页停止保存，同一 PID 7399、一个新增文件、录音库增加一条。生成的 63.978667 秒 M4A，经 ffprobe 确认为 AAC／48 kHz／单声道，ffmpeg 全段解码成功；旧两条音频保留。此手动路径发生在添加 provider 预设之前，最终相关 UI 用上述 16 项测试复核。
- UI 尺寸／大字体截图和实际内外屏／IME／深色截图位于忽略的 `artifacts/`。ForcedSize 截图可能受测试 Activity／键盘的实际窗口约束，不能代替实际外屏截图或 vivo 验收；尚未建立像素回归基线。

APK：`D:/code/record/app/build/outputs/apk/debug/app-debug.apk`。版本 `0.2.0`／versionCode=2，保持 `dev.local.record` 和此前本机 debug 签名，可覆盖安装保留数据。

SHA-256：`6c21dd8484a8f6c130bf1419e670cf9d6b2b6d70004f7107fcd618798fc8086b`。

未连接 vivo 手机、未使用真实云端账号，因此 OriginOS 键盘／内外屏／后台策略和服务商账号权限仍待实机验证。vivo 的操作清单见 [测试与验收](testing.md)。
