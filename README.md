# 听屿 · mobile-music-dl

Android 原生音频播放器，包名 `app.tingyu`，版本 **0.1.2（versionCode 3）**。解析哔哩哔哩链接、在线播放、下载兼容 AAC 音轨并重封装为 M4A，保存到 `Music/听屿/` 后离线收听；也可以找回、导入和管理手机中已有的音频。
正式版下载：[0.1.2 APK](https://github.com/JackkLau/mobile-music-dl/releases/download/v0.1.2/tingyu-0.1.2-release.apk) · [GitHub Release（含 AAB 和校验文件）](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.2)。该版本已由 GitHub Actions 编译、签名并发布，沿用 0.1.1 正式证书。


**实际工程入口是本目录的 `settings.gradle.kts`，应用源码在 `app/src/`。** 本轮将误放在 `app/app/src/` 的恢复版合入实际工程；嵌套目录保留为历史来源，不参与构建。合并前备份在 `artifacts/before-handoff-merge-20261028.zip`。交接的 ChatGPT Library 压缩包本轮无法读取，因此以本机恢复源码和交接清单补齐，不能声称与远端压缩包逐字相同。

本轮逐项代码与新验证证据见 [交接验收记录](docs/HANDOFF-VERIFICATION.md)。历史测试数量只作背景，不能相加或替代本轮结果。

## 链接与音频支持

- 支持 BV、av、桌面和移动视频链接、分 P、手机客户端分享文本、Markdown 链接、`b23.tv` 短链，以及具体收藏夹和显式分页。
- 解析会处理包裹标点、NBSP、零宽字符，保留有效查询值与百分号编码。短链只允许受信任 HTTPS 跳转；桌面、移动和短链最终共用 BV/分 P、取流参数及视频页 Referer，忽略 `vd_source` 等分享参数。
- AAC 识别覆盖 codec/MIME 变体和部分缺失元数据；已确认 AAC 优先，不明音频 MP4 须由实际媒体组件验证。主地址无效时可采用合规备用地址。
- 保留受信任 `mcdn.bilivideo.cn` 及子域的 HTTPS 4483；其他域名非标准端口拒绝。签名音频 URL 不重新拼装，CDN 不接收账号 Cookie。
- 最终检查真实 AAC 编码、完整长度/时长、非加密音轨，成功重封装后才发布 M4A。**没有任意格式转码、MP3/FLAC 导出、音源分离或视频下载。**

## 构建与安装

最低 Android 10（API 29），compile/target SDK 35。固定 JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20。可以用 Android Studio 打开根目录；本机已存在 `.tooling/` 工具链。

```bash
# 工具链尚未安装时运行；许可证由操作者检查接受
bash scripts/bootstrap-android.sh
source .tooling/env.sh
bash scripts/check.sh
# 手机需已连接并授权；覆盖安装保留应用数据
adb -s 7c1213a1 install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。`check.sh` 执行结构检查、离线脚本测试、Android JVM 测试、lint、应用/设备测试 APK 构建、PC 共用网络测试和工具打包。已提供官方 Gradle Wrapper；已有兼容环境也可使用 `./gradlew`。

### 正式签名包

正式构建读取根目录的 `signing.properties`，格式见 `signing.properties.example`。本机首次 release 密钥保存在 `.tooling/signing/tingyu-release.keystore`；密钥和密码配置均被 Git 忽略。请将这两个文件一起保存到安全备份中，后续升级沿用同一密钥。克隆仓库不会获得签名密钥；没有签名配置时 Gradle 只能生成未签名包。

```bash
source .tooling/env.sh
# 必须先恢复本机 signing.properties 及其引用的正式密钥
test -f signing.properties &&
gradle --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

APK：`app/build/outputs/apk/release/app-release.apk`；AAB：`app/build/outputs/bundle/release/app-release.aab`。APK 可直接安装，AAB 用于商店提交。正式签名与 debug 签名不同，不能直接覆盖已有 debug 安装；不要为安装正式包而贸然卸载含有个人数据的调试版。0.1.1 历史构建记录见 [正式包记录](docs/RELEASE.md)，0.1.2 新结果见 [本地音乐验证记录](docs/LOCAL-MUSIC-VERIFICATION.md)。

GitHub 的 **Android release build** 工作流可完成正式版编译，下载产物后在本机用原证书签名，再发布到 GitHub Release，私钥无需离开本机。另保留可选的全云端 **Android release** 流程，只有明确授权配置加密 Secrets 后使用；标签自动签名还需显式启用仓库变量。两条路径都必须验证正式签名后才发布安装包。详见 [GitHub 构建发布](docs/GITHUB-RELEASE.md)。

```bash
# 设备测试会创建并清理自己的本地音频 fixture；不会访问 B 站
# 播放测试遇到已有队列/播放服务会跳过，应使用干净的测试设备
ANDROID_SERIAL=emulator-5554 bash scripts/check.sh --device
```

首次下载可能申请通知权限；拒绝后仍可在应用内查看状态。下载队列支持取消、失败重试、可靠的强 ETag + Range 续传、进程中断后手动恢复。已下载文件命中不会重新访问 CDN。

## 使用与登录

1. 在“解析”粘贴链接或接收分享，匿名访问为默认模式。
2. 点播音轨，或选择条目加入下载队列；收藏夹“全选”只作用于已加载条目。
3. 在“我的”打开官方网页登录。已有登录反馈实现会自动验证会话，经本机加密保存后显示“登录成功”和账号名称，再点“返回听屿”；失败可手动点“完成登录”重试。本轮保留了该实现，没有补造新交互需求。
4. 下载完成后在音乐库离线播放。删除本地音频需确认；退出账号保留音频文件。

登录 Cookie 仅发送至 B 站 API，不发送给短链或音频 CDN。会话使用 Android Keystore 加密存储；退出清除会话和私密列表。临时签名 URL 不写入音乐库。内容权限、失效账号和平台限制按真实返回处理。

## 管理已有的本地音乐

- 使用同一正式签名覆盖升级到 0.1.2，原数据库、下载记录和音乐文件保留。不要卸载旧正式版来完成升级。
- 打开应用会查找当前已获准访问的 `Music/听屿/` 音频。“音乐库 → 扫描已下载音乐”可申请音频访问权限，找回旧安装或数据库中遗漏的文件；只扫描这个目录，不收集整部手机的音乐。
- “导入本地音频”打开系统文件选择器，可多选其他目录里的音频。文件继续留在原位置，不复制、不转码；需要保留系统提供的读取授权。本地格式以 Android 媒体组件实际可读取的音频为准，不改变网络下载仅支持兼容 AAC/M4A 的范围。
- 扫描和导入保留已有曲目信息并去重；支持标题/作者搜索、单曲或全部播放、播放队列和确认删除。权限被拒绝或文件暂时无法读取不会清空音乐库。
- 删除会移除手机上的实际文件；需要系统授权时再确认。取消或失败保留记录与播放队列，确认框显示错误供重试。仅本地条目不可用时提示重新导入，不向 B 站发起请求。

外部移动文件或撤回授权后，原 URI 可能失效；请重新导入可读取的文件。真机文件提供方、系统授权和升级验证范围见 [0.1.2 验证记录](docs/LOCAL-MUSIC-VERIFICATION.md)。

## 默认 UA、网络调试和隐藏入口

解析和音频下载均默认桌面 Chrome UA。debug 包提供“我的 → 网络调试”；首次打开保留桌面配置，日志默认关闭。原隐藏入口保留：在“我的”连续点版本号 **7 次**，相邻点击不超过 **2 秒**，显示“开发者工具”。两个入口使用同一页面和设置。

- 解析 UA：桌面／移动；音频 UA：跟随解析／桌面／移动。选择即时保存。
- 每个下载在开始时固定 UA 配置；进行中更改设置不影响它，地址刷新也使用相同配置。新任务使用新设置。
- “记录脱敏日志”控制 `TingYuDownload`。关闭调试模式会关闭日志并重置覆盖配置；release 忽略所有调试设置且没有诊断 Activity。
- 先解析视频，再点击“开始同地址 UA 对照”。只取流一次，用同一签名 URL 发送桌面和移动 UA，各读取最多 1024 字节，不保存音频；可“取消对照”，离页也取消。

## 日志与 403 的证据边界

```bash
# 开启日志并重现后导出，按 request 关联同一次操作
adb -s 7c1213a1 logcat -d -v brief -s TingYuDownload:I TingYuProbe:I '*:S' > tingyu-network.log
```

日志保留阶段、HTTP/业务码、`request`、`url_id` 摘要、CDN 域名、UA、范围配置、续传偏移、刷新次数、重定向次数、IPv4/IPv6 和代理类型；连接/读取失败保留 DNS、TLS、超时等类别。不输出 Cookie、账号资料、原始响应、完整签名 URL 或原始异常消息。

`audio_select` 区分没有音轨、格式不兼容与地址不可用。正式下载会在首次写入前检查 MP4 文件头，拒绝 HTTP 成功但实际返回错误页的内容；非零续传按 ETag、范围和总长判断。

| 证据 | 只说明什么 |
|---|---|
| `http=206` | CDN 接受了范围请求 |
| `sample=valid_mp4_header` | 最多 1 KB 样本具有 MP4 文件头 |
| `event=transfer_complete` | 音频字节读取及长度检查结束 |
| `event=saved` | M4A 重封装、发布和本地记录提交成功 |
| `event=local_hit` | 复用了已有本地文件，没有证明新网络请求成功 |

此前已确认链接和音轨地址能解析，403 发生在独立音频 CDN 请求。多个节点在刷新地址及补 Referer／Origin／Range 后仍失败；改桌面 UA 后用户反馈成功，并出现 `206 ua=desktop`。但成功/失败日志中的 CDN 节点也变化，**尚不能证明唯一根因就是下载 UA**。完整下载、M4A 保存和离线播放要分别验收；项目没有视频下载，不能据此判断视频流。

## PC／手机共用诊断

```bash
bash scripts/probe-bili-download.sh pc
bash scripts/probe-bili-download.sh phone
# 可指定三类原始问题链接，分享文本请整体加引号
bash scripts/probe-bili-download.sh pc 'https://m.bilibili.com/video/BV1sVtw6JEeq?vd_source=1b2cad1e91ade28f394b35922f927023'
bash scripts/probe-bili-download.sh phone 'https://b23.tv/ZhRmMnf'
ANDROID_SERIAL=emulator-5554 bash scripts/probe-bili-download.sh phone
```

工具分别用桌面／移动解析 UA 取流，每一组用同一签名地址对照两种下载 UA。PC 和 Android 直接编译并运行同一套 Kotlin 网络源码，没有另写 curl 取流逻辑。PC 匿名访问；手机使用应用当前访问模式，因此账号条件不同的结果不能直接归因为设备差异。

手机脚本先检查设备与授权，然后构建、`install -r`；不卸载、不清数据、不清除历史 logcat。每次生成唯一 run 编号，仅提取本次 `TingYuProbe` 日志。默认设备 `7c1213a1`，通过 `ANDROID_SERIAL` 改选。调试入口限定 shell 权限且不包含在 release 中。

日志写入 `artifacts/probes/`。Ctrl+C 取消；`TINGYU_PROBE_TIMEOUT_SECONDS` 设置最长诊断时间（默认 240 秒，最大 1800 秒）。退出码：0 全部样本通过；1 检测到请求/内容失败；2 工具或输入问题；124 超时；130/143 取消。对照发现 403 返回 1 是真实诊断结果。鉴权失效或限流后停止后续无意义请求。

## 验收链接与剩余验证

- 桌面：`https://www.bilibili.com/video/BV1sVtw6JEeq/?vd_source=1b2cad1e91ade28f394b35922f927023`
- 移动：`https://m.bilibili.com/video/BV1sVtw6JEeq?vd_source=1b2cad1e91ade28f394b35922f927023`
- 短链：`https://b23.tv/ZhRmMnf`

自动测试能证明确定性规则与模拟响应下的请求行为；在线探测只证明该次网络/账号/节点结果。真机完整下载→M4A保存→飞行模式播放，以及登录反馈、后台恢复、TalkBack 和不同 Android 版本的矩阵仍需分别记录。详见 [本轮逐项记录](docs/HANDOFF-VERIFICATION.md) 和 [完整设备验收清单](docs/ACCEPTANCE.md)。
