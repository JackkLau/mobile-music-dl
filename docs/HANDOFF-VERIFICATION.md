# 听屿 0.1.1：本机整合与逐项验收

工作区：`/Users/tobin/Documents/projects/mobile-music-dl`。开始于 2026-10-28，验收于 2026-10-29（Asia/Hong_Kong）。本记录仅描述本轮源码与执行证据。

## 来源与工程入口

根工程原本实际编译 0.1.0 的 `app/src/`，恢复版 0.1.1 位于嵌套的 `app/app/src/`。本轮先比较原始源码包及两份目录，确认根应用源码与原始归档相同，再备份并合入恢复改动。实际应用源码现在是 `app/src/`，版本 0.1.1 / code 2；嵌套恢复目录保留但不参与根工程构建。没有清理 Git 暂存区或覆盖未识别的独有改动。

交接中的 ChatGPT Library ZIP 链接要求登录，本轮没有读取到该 ZIP。此交付是本机恢复版加交接清单的补齐，不宣称与远端压缩包逐字一致。原目录备份：`artifacts/before-handoff-merge-20261028.zip`。

## 逐项代码与验证映射

以下路径均相对项目根目录。**“已验证”限于列出的执行层级；模拟响应、样本对照和模拟器不能替代手机完整下载验收。**

| 清单项 | 对应代码与本轮处理 | 新验证证据与边界 |
|---|---|---|
| 1. 共用链接解析 | `data/bili/BiliLink.kt`、`BilibiliRepository.kt`、`BiliTransport.kt`：补齐 Markdown/引号/中文括号、NBSP、零宽字符和有效查询标点处理；安全短链跳转；HTTP/业务码及阶段保留；共用 BV/分 P、取流与 Referer | `BiliLinkParserTest`、`BilibiliRepositoryTest` 通过；三种原始链接均在 PC 真实解析和取流成功，短链返回 302 后进入共用流程。平台限制/权限失败使用确定性响应验证 |
| 2. AAC 兼容 | `BiliResponseParser.kt`、`MediaUrlPolicy.kt`：恢复并保留 MIME/codec 变体、元数据缺失候选、安全备用地址、MCDN 4483 和筛选日志 | `BiliResponseParserTest`、`MediaUrlPolicyTest` 通过；真实样本日志选择 AAC。特定 AAC profile 的 Android 解码/封装、MCDN 4483 实际网络可达性待手机验收；没有增加转码 |
| 3. 下载请求头 | `MediaRequestPolicy.kt`、`DownloadRules.kt`、`DownloadCoordinator.kt`：视频 Referer、严格来源校验/Origin、首次 Range、签名 URL、Cookie 隔离、强 ETag 续传；补首次内容检测 | `MediaRequestPolicyTest`、`DownloadRulesTest`、`MediaResponsePolicyTest` 通过；MockWebServer 检查真实发出的请求头与续传规则。真实桌面 UA 样本 206 不等于整首保存成功 |
| 4. UA 默认及动态设置 | `NetworkProfile.kt`、`debug/DebugSettings.kt`、`DebugSettingsStore.kt`、`DownloadCoordinator.kt`：默认桌面；任务开始取得不可变配置，地址刷新沿用；选择立即持久化，release 忽略覆盖 | `DebugSettingsTest`、仓库固定 profile 测试及设备 `DebugSettingsStoreTest` 通过；设置重读后保留且已有快照不变。真实手机运行中切换、刷新与续传组合仍待验收 |
| 5. 调试页及隐藏入口 | `ui/LibraryScreens.kt`、`DeveloperToolsScreen.kt`、`AppViewModel.kt`、`debug/UaComparison.kt`：恢复七连点/两秒门禁；补“我的 → 网络调试”；两入口复用同一页；样本最多 1 KB、可取消、离页取消 | `UaComparisonTest` 验证只取流一次、同地址两 UA、读取上限、鉴权停止、取消；`TingYuUiTest` 验证入口/空态/取消/release 隐藏。完整界面手工视觉、TalkBack 及字体缩放矩阵未执行 |
| 6. 错误和脱敏日志 | `NetworkTrace`、连接监听、仓库、`MediaResponsePolicy.kt`、下载器：区分 401/403/404/410/412/429；保留 request/url_id/host/UA/偏移/刷新/重定向/协议族/代理/网络阶段；增加内容/读取/封装/保存失败日志 | 对应 JVM 测试验证状态分类、连接错误脱敏、伪装 HTML 200/206 被拒绝；真实日志有 206/403、IPv4/direct、地址摘要且不含签名 URL。`transfer_complete`、`saved`、`local_hit` 代码已区分；本轮未触发真实整首下载及保存 |
| 7. PC/手机诊断脚本 | `scripts/probe-bili-download.sh`、`debug/NetworkProbe.kt`、`probe/`、`app/src/debug/`：共用实际 Kotlin 源码；先检查设备、install -r、唯一 run 日志过滤、取消、超时、失败退出；debug shell 入口有 DUMP 权限保护 | `NetworkProbeTest`、10 项脚本分支测试通过；PC 工具编译通过，真实三类链接诊断完成；指定手机缺席时以 2 退出且不构建/安装。模拟器 phone 分支结果见下方执行记录 |
| 8. 文档与交接 | `README.md`、本文件、`docs/verification.json`、`UA-VALIDATION.md`、`ACCEPTANCE.md`：更新入口/命令/范围、日志含义、403 结论边界及待验收项 | 文档命令对应现有脚本；严格静态 UI 契约审计零错误，DESIGN lint 零错误、7 个未引用 token 提示。历史云端记录明确标为历史 |

代码路径缩写中 `data/`、`debug/`、`download/`、`ui/` 均在 `app/src/main/java/app/tingyu/` 下。对应 JVM 测试位于 `app/src/test/java/app/tingyu/`，设备测试位于 `app/src/androidTest/java/app/tingyu/`。

## 新执行结果

- `source .tooling/env.sh; bash scripts/check.sh`：退出 0；135 项应用 JVM、112 项 PC 共用网络 JVM、16 项脚本测试通过；Android lint、APK、设备测试 APK、PC installDist 构建通过。两套 JVM 套件有重叠，不能相加。
- Android lint：0 errors；依赖版本提示和既有本地化等 warnings 保留，未做无关生产依赖升级。
- 初次设备启动曾遇 Maven TLS 下载失败，重试后运行到测试。旧 Espresso 3.5.0 在 API 37 上对已移除 `InputManager.getInstance` 的反射调用导致 15 项界面测试启动失败；设置持久化和真实 WAV 本地播放两项当时通过。修复只更新 Android 测试依赖到 JUnit 1.3.0、runner 1.7.0、Espresso 3.7.0；[官方发行说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0) 记录该反射调用替换。
- 更新测试依赖后，`ANDROID_SERIAL=emulator-5554 gradle --no-daemon :app:connectedDebugAndroidTest :app:lintDebug` 退出 0：**17 项设备测试通过，0 跳过**，包括 15 项界面测试、设置持久化及真实 WAV 播放/暂停/跳转/切歌。
- 实际执行 PC 脚本及 `ANDROID_SERIAL=emulator-5554` 的 phone 脚本：构建、覆盖安装、分 run 日志提取和样本请求成功；桌面下载 UA 206、移动 UA 403，脚本按设计退出 1。
- `:app:processReleaseMainManifest :app:generateReleaseBuildConfig` 退出 0；清单不含 ProbeActivity，release DEBUG=false。当时没有生成正式签名发布包；随后按 GitHub 推送与正式包要求完成构建，见 [正式包记录](RELEASE.md)。

详细构建日志在 `artifacts/`，保留有失败与后续验证记录；最终稳定记录另存至 `docs/verification-logs/mac-20261029/`。

## 真实 PC 样本结果

匿名模式，三类原始链接；每种解析 UA 都成功取得独立 AAC 音轨。各组内部使用同一 `request`、`url_id` 和 CDN host，只更换下载 UA：

| 输入 | 桌面解析：桌面/移动下载 UA | 移动解析：桌面/移动下载 UA |
|---|---|---|
| 桌面视频链接 | 206 / 403 | 206 / 403 |
| 移动视频链接 | 206 / 403 | 206 / 403 |
| b23.tv 短链 | 206 / 403 | 206 / 403 |

**短链身份注意：** 本轮 `https://b23.tv/ZhRmMnf` 实际跳转到 `BV1d3Ye6BEPF`，不是桌面/移动示例的 `BV1sVtw6JEeq`；公开跳转目标与 API 请求摘要 `44fbccb6e4c68060` 一致。它可用于入口验收，但不能与另外两条直接当作同一视频比取流参数。共用参数一致性由使用相同 BV/分 P 的仓库回归测试验证。

所有 206 读取 1024 字节并通过 MP4 文件头检查。对照发现 403，故 CLI 正确以 1 退出；这不是构建失败或把 403 视为成功。来源：`artifacts/pc-live-desktop.log`、`pc-live-mobile.log`、`pc-live-short.log`。

本轮同地址结果是 UA 与这些请求的访问差异有关的直接样本证据，不能回推历史所有 403 的唯一原因，也不证明其他网络、账号、节点必然相同。保留交接边界：过去补 Referer/Origin/Range 后仍失败，后续桌面 UA 成功时 CDN 也变化；206 只是请求获接受。没有视频下载功能，不能据这些音频日志判断视频流。

## 尚待手机验收

指定手机 `7c1213a1` 本轮没有连接，未向该手机安装 APK。必须继续确认：

1. 手机使用三类原始链接解析同一分 P；匿名/登录条件分别记录。
2. 默认桌面 UA 完整读取一首未命中本地库的音频，出现 `transfer_complete` 后，再确认 `saved`、实际 M4A 文件和离线播放；已有文件的 `local_hit` 不算网络成功。
3. 进行中切换 UA、一次地址刷新、ETag 续传、取消和进程中断恢复。
4. 官方登录页自动成功反馈、手动重试和退出；本轮保留恢复版的 `LoginVerificationGate` 与 LoginActivity，不凭交接文字添加新的登录需求。
5. 不同 Android 版本、后台/锁屏、TalkBack、大字体、小屏及真实 MCDN 4483 可达性。

安装命令：

```bash
source .tooling/env.sh
adb -s 7c1213a1 install -r app/build/outputs/apk/debug/app-debug.apk
```

## 交付产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`（69357779 字节）。
- SHA-256：`f77180f29014e53d404ffec6bff01e9fa897cae9d2e1d2609f9d8939caf7a7a7`。
- PC 启动器：`probe/build/install/probe/bin/probe`。
- 本机完整日志：`docs/verification-logs/mac-20261029/`。
- `git diff --check` 通过；源码指纹及完整计数见 `verification.json`。
