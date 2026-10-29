# 听屿 · Android

从哔哩哔哩视频、分享文本或收藏夹链接获取音轨，在线播放、保存 M4A，并在应用内离线收听。

**当前源码：0.1.1（versionCode 2）。** 根据 2026-10-28 的早期备份与会话中的已确认需求恢复，补回桌面音频 UA、统一链接、脱敏日志、隐藏开发者工具和登录成功反馈；0.1.1 进一步修复 AAC 元数据及媒体地址被误拒绝的问题，详见 `docs/AAC-COMPATIBILITY.md`。它是重建版本，不是原电脑最后一次编辑的逐字备份；恢复范围与验证状态见 `docs/RESTORATION.md`。

用户已在此前版本真机验证：**解析使用桌面或移动 UA 均可，音频下载需使用桌面 UA**。该结论用于本版本默认配置；它不代表本次重新生成的源码已经过真机验收。编译和测试的最新证据见 `docs/verification.json`。0.1.1 独立编译覆盖全部 53 个 Kotlin 源文件（含测试），103 项 JVM 单测、75 项结构检查通过；6 项工具链下载恢复测试已在前一轮通过，相关脚本未改动。没有产出 APK，15 项设备测试尚未执行。

## 已实现的源码范围

- Kotlin + Jetpack Compose 原生界面：解析、音乐库、下载、我的。
- 视频 BV/av、短链接、分享文本、分 P、收藏夹链接与显式分页。
- 匿名及登录访问模式；官方网页登录，验证有效会话后用 Android Keystore AES-GCM 加密存储。
- Media3 在线/本地播放、常驻迷你条、完整播放页、播放队列、顺序/循环/随机、进度及前后 10 秒、通知栏和耳机控制。
- 顺序下载队列、取消、重试；可靠时使用 ETag + Range 续传，否则重新下载。
- AAC 音轨完整性检查和 M4A 重封装，MediaStore 保存到 `Music/听屿/`。
- Room 本地库及任务状态、发布恢复日志、进程中断后手动恢复；文件删除确认。
- 分享入口、错误和空态、中文辅助功能标签。未接入广告或统计 SDK。

“提取音乐”指保留视频原有音轨，包括其中的人声和背景声音。当前输出 AAC/M4A；没有 MP3 编码、音源分离、歌词服务或视频下载功能。

## 环境与构建

最低 Android 10（API 29），compile/target SDK 35。工具版本固定：JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20。普通播放和下载不需要服务端。

可以在 Android Studio 中打开项目，选择兼容的 JDK 17 和 SDK 35。命令行也可以使用项目内工具链脚本：

```bash
# 仅写项目 .tooling/，不修改系统 Java 或 shell 配置。
# 默认交互展示 SDK 许可证；确认已接受时可加 --accept-licenses。
bash scripts/bootstrap-android.sh
source .tooling/env.sh
bash scripts/check.sh
```

脚本从官方元数据获取 JDK/SDK/Gradle 并校验下载包。网络必须能访问 Adoptium、Google、Gradle、Maven Central 及所需下载重定向地址。本项目没有伪造 Gradle Wrapper；使用脚本安装的固定版本 Gradle，或已有 Gradle 8.11.1。

JDK、Android 命令行工具和 Gradle 压缩包支持断点续传；每次超时后按当前分片大小继续，下一次执行也保留进度。服务器明确拒绝 Range 时重新完整下载，成功后仍须通过官方校验和。元数据每次重新获取。若旧安装进程尚在运行，先等它结束再重跑脚本，避免两个进程同时写入 `.tooling/`；旧进程不会自动加载本轮修复。

构建成功后的调试包位置：`app/build/outputs/apk/debug/app-debug.apk`。调试包使用开发签名，不能当作正式发布签名管理方案。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
gradle :app:connectedDebugAndroidTest  # 需要已连接设备或模拟器
```

若未来将项目放到 GitHub，已提供 `.github/workflows/android.yml` 以运行单元检查、lint 和调试 APK 构建；本次没有创建远程仓库、推送或运行云构建。

## 使用流程

1. 打开“解析”，粘贴链接或接收 B 站分享；匿名是默认模式。
2. 点击结果播放，或选择音频加入下载队列。
3. 登录时到“我的”打开 B 站官方网页登录。应用自动检测本登录页的会话，经服务端验证并加密保存后立即显示“登录成功”和账号名称，再点击“返回听屿”。自动验证失败可点击“完成登录”重试；应用不读取外部浏览器的登录数据。
4. 将具体收藏夹链接粘贴到“解析”；按“加载更多”读取后续条目，全选只作用于已加载的条目。
5. 已下载音频在“音乐库”播放，也可由其他播放器读取。应用内删除会删除对应手机文件；卸载应用不删除公共音乐目录里的音频。

首次下载会申请通知权限；拒绝后仍可在应用中查看任务。后台下载采用用户操作启动的 `dataSync` 前台服务，支持 Android 15 超时回调；当前未接入 Android 14 UIDT。系统停止、账号切换或进程退出后的未完成任务显示中断，用户点击重试恢复。

## 已确认的 UA 行为与开发者工具

普通使用默认桌面解析 UA + 桌面音频 UA。桌面链接、移动链接、分享文本和 b23.tv 短链进入相同 BV/av、分 P 解析流程；`vd_source` 不影响视频身份。AAC 选择读取独立 codec 字段及 MIME 的 codecs 参数，允许明确 AAC 使用通用 MP4 MIME；优先选择已确认 AAC，音频 MP4 缺少 codec 时可交给实际内容检测。最终仍须 MediaExtractor 确认 AAC 才能保存；明确非 AAC 或受保护音轨不会被当成可导出文件。主地址不可用时可选择受信任的备用地址；解析和下载共用地址规则，支持 MCDN 的 HTTPS 4483 端口。

调试入口仅用于 debug 构建：

1. 打开“我的”，连续点击底部版本号 **7 次**（相邻点击间隔不超过 2 秒）。
2. 出现独立的“开发者工具”入口；后续其他调试功能也集中在此页。
3. 可单独选择解析 UA（桌面 / 移动）与音频 UA（桌面 / 移动 / 跟随解析），开关脱敏日志。
4. 先解析视频，再到开发者工具选择音轨，运行“同地址 UA 对照”。一次取流后用两种 UA 请求**同一签名 URL**，各读最多 1 KB，不保存音频；可取消，离开页面也取消。
5. 关闭调试模式后入口隐藏、日志关闭、测试配置重置。新任务恢复桌面配置；已开始的下载固定使用开始时的 UA，包括一次地址刷新。release 构建忽略任何残留调试配置。

```bash
# 开启调试模式和“记录脱敏日志”后，先重现问题，再导出日志
adb logcat -d -v brief -s TingYuDownload:I '*:S'
```

日志只包含请求关联号 `request`、地址摘要 `url_id`、CDN 域名、HTTP 状态、UA、连接协议族/代理类型和下载阶段。新增 `stage=audio_select`，记录音轨数量、固定的编码/容器分类和被拒绝原因。没有 Cookie、完整签名 URL、账号名称或原始服务器响应。

`http=206` 只说明分段请求被接受；`transfer_complete` 说明音频字节读取完成，`saved` 才表示保存已完成，`local_hit` 表示复用已有文件。同地址对照仅检查样本；不表示整首音频已完成下载或封装。

此前 403 发生于多个音频 CDN，刷新无效，补 Referer/Origin/Range 后仍失败；桌面音频 UA 恢复下载。依据是用户后续真机对照结论，不将该结论推广为所有视频、网络或账号的通用规则。详见 `docs/UA-VALIDATION.md`。

## 模块

| 目录 | 职责 |
|---|---|
| `model` | 音轨、账号、队列与界面状态契约 |
| `data/bili` | 链接验证、HTTP 边界、WBI、元数据、收藏夹、独立音轨 |
| `data/auth`、`auth` | 本机加密会话、官方网页登录及验证 |
| `data/local` | Room 音乐库、任务与播放快照 |
| `playback` | Media3 后台服务、按需解析、固定播放来源 |
| `download` | 下载队列、续传、完整性校验、重封装和 MediaStore 提交 |
| `ui` | Compose 页面与共享组件；视觉见 `DESIGN.md` |
| `debug` | 开发者模式设置、版本号解锁、同地址 UA 样本对照 |

后续 iOS 使用 Swift + SwiftUI、AVPlayer、URLSession 和 Keychain 原生实现；复用产品约定、数据模型语义和测试样例。本仓库目前只有 Android 源码。

## 访问与会话边界

匿名只能处理平台当前允许匿名访问的内容；登录使用当前账号已有权限。收藏夹能列出视频不代表音轨仍然可访问。私密收藏夹、失效登录、验证码和平台请求限制需要真实设备验证。

账户 Cookie 只发送到 B 站 API；短链接和音频 CDN 不接收登录 Cookie。退出清除登录会话、当前解析列表及播放快照，保留已下载文件。临时音频 URL 不存进音乐库。官方 OAuth 令牌没有被假定为网页会话的替代品。

仅处理有权下载和使用的内容。程序不绕过付费、DRM、地域或访问权限；遇到试看、不完整时长、无独立 AAC 音轨等情况会明确失败。网页接口并非稳定性承诺，需要维护。

## 验证

```bash
python3 scripts/verify-source.py       # 仅结构、XML、词法、文件关系检查
python3 -m unittest discover -s scripts/tests -v  # 不联网的工具链下载恢复测试
bash scripts/check.sh                 # JUnit + Android lint + 应用和设备测试 APK 编译
bash scripts/check.sh --device        # 再运行设备测试（使用干净测试安装）
```

JUnit 覆盖链接与分页、WBI 固定样例、Cookie 隔离、失效会话、权限和限流、不完整音轨、续传及文件命名，以及终止下载时数据库写入失败的降级处理。Compose 测试通过测试 fixture 检查操作回调，不访问 B 站；fixture 不出现在产品界面。

工具链下载测试通过独立的模拟 CDN/curl 进程注入超时、DNS 失败、不支持 Range 和 HTTP 416；检查最终归档字节、失败后保留分片及已有归档、元数据完整替换。它们也在标准 `check.sh` 中执行，不能证明真实网络可用或工具链已安装。

`PlaybackIntegrationTest` 在设备上生成两段短 WAV，通过真实 Room、MediaController、TrackDataSource 和 ExoPlayer 验证播放时钟、暂停、跳转和切歌。它不访问 B 站；检测到已有播放快照或正在运行的播放服务时会跳过，因此应使用干净的测试安装。该测试已编写，尚未执行。

详细真机验收与证据边界见 `docs/ACCEPTANCE.md`。

## 技术依据

- [Android Media3 后台播放](https://developer.android.com/media/media3/session/background-playback)
- [Android MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [yt-dlp 的 B 站适配器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py)
- [B 站开放平台](https://open.bilibili.com/doc)
