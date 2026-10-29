# 0.1.1 AAC 兼容修复与复测

版本：0.1.1，versionCode 2。记录日期：2026-10-28（Asia/Hong_Kong）。

## 问题与证据

用户截图中的“未找到兼容的 AAC 音轨，当前版本仅支持 M4A 音频”来自旧版 `BiliResponseParser.audio` 的候选选择阶段。该阶段尚未请求音频 CDN；同一句错误此前同时覆盖无独立音轨、元数据被拒绝和所有地址被拒绝，因此不能仅凭截图断定服务器返回了非 AAC 音轨。

源码与回归样例确认存在兼容性遗漏：明确 AAC 配合通用 MIME 会被拒绝，MIME 参数里的 codec 未读取，部分 AAC 标识未支持，缺少 codec/已知 id 的音频 MP4 无法进入后续内容检测，MCDN 的 HTTPS 4483 地址也被过滤。

云端曾匿名尝试此前会话中的视频 BV1sVtw6JEeq，但视频信息接口返回 HTTP 412，未取得 cid、playurl 或媒体内容。用户此次截图未提供视频身份。这个结果不能确认截图的唯一根因，也不证明修复后真机已下载成功。

## 已实现

| 情况 | 0.1.1 行为 |
|---|---|
| 常见 MPEG-4 AAC，包括 LC/HE-AAC 标识 | 识别支持的 audio object type；允许 `mp4a.40.02` 等带前导零形式 |
| MPEG-2 AAC `mp4a.66` / `.67` / `.68`、明确 `aac` | 作为 AAC 候选，实际内容仍由 Android 媒体组件确认 |
| codec 写在 `mimeType` 的 `codecs` 参数里 | 与独立 `codecs` / `codec` 字段一起解析；字段冲突时不忽略明确非 AAC 声明 |
| 明确 AAC 配合 `audio/x-m4a`、`video/mp4`、`application/mp4` 或 `application/octet-stream` | 不因 MIME 标签不是 `audio/mp4` 而直接丢弃 |
| `audio/mp4` / `audio/x-m4a` 缺少具体 codec，且不是已知非 AAC id | 标记 `format=inspect`，仅在无可用的已确认 AAC 时选用；下载后检测真实编码 |
| 明确 FLAC、Opus、Dolby、MP3、冲突/未知 codec、其他容器或保护信息 | 保持拒绝；没有增加转码或解密 |
| 受信任 MCDN 的 HTTPS 4483 | 允许 `mcdn.bilivideo.cn` 及其子域，保留原签名地址；其他域名非标准端口保持拒绝 |
| 主地址无效但有可用备用地址 | 使用备用地址；解析、请求和重定向共用 `MediaUrlPolicy` |
| 无音轨、候选不符合导出条件、音轨地址不可用 | 分别报错，并记录固定分类原因 |

AAC 是编码，M4A 是此应用的输出容器。现有 `DownloadCoordinator.remuxAudio` 会检查实际轨道为 `audio/mp4a-latm`，拒绝加密/不完整帧并校验时长，完成后才发布到 MediaStore；本次没有把不明音频改后缀充当 M4A。平台媒体组件对不同 AAC profile 的实际支持仍需设备验证。

编码标识依据：[MP4 Registration Authority 的对象类型表](https://mp4ra.org/registered-types/object-types)。MCDN 地址形态可见 [公开 Bilibili API 样例](https://github.com/realysy/bili-apis/blob/master/docs/video/videostream_url.md)，它不是官方稳定接口承诺，也不是本次手机请求的证据。

## 验证

- 103 项 JVM 测试通过，其中 21 项为本轮新增；覆盖 MPEG-4/MPEG-2 AAC、MIME 参数、通用 MIME、缺失元数据的优先级、错误分类、敏感数据不入日志和 URL 规则一致性。
- 全部 53 个 Kotlin 源文件（主应用、JUnit、instrumentation）使用真实 Android/AndroidX 类库独立编译通过；75 项结构检查通过。
- 修复前新增回归用例曾出现 7 项失败，涉及 codec/MIME、缺失元数据、错误分类及 MCDN 端口；最终执行日志见 `verification-logs/aac-junit.log` 和 `verification-logs/aac-all-kotlin.log`。
- 没有完整 Gradle/Android SDK 构建环境；未执行 AGP、KSP、资源链接、Android lint、设备测试或 APK/AAB 打包。真实 B 站下载和 MediaExtractor/MediaMuxer 行为仍待真机验收。

## 在 Android Studio 中复测

1. 将本次源码与电脑上的项目比较后合并。这里没有直接修改 Mac 上的目录；不要覆盖本机未合并的改动。
2. 同步、构建并安装新的 debug APK，在“我的”确认版本为 **0.1.1**。若仍是 0.1.0，运行的还是旧包。已配置固定版本 Gradle 的终端可运行 `gradle :app:assembleDebug`，输出为 `app/build/outputs/apk/debug/app-debug.apk`。
3. 在“我的”连点版本号 7 次，进入“开发者工具”，打开“记录脱敏日志”。保留默认桌面音频 UA。
4. 重新解析本次报错的同一视频和分 P，再重试下载；已存在的本地文件命中不算新下载。
5. 在 Android Studio Logcat 筛选 `TingYuDownload`，或运行：

   ```bash
   adb logcat -d -v brief -s TingYuDownload:I '*:S'
   ```

6. 若失败，提供实际视频链接和同一个 `request` 的 `stage=audio_select` 行；若已选中音轨，再附后续 CDN/保存阶段日志。无需提供 Cookie 或原始服务器响应。

筛选日志的值是固定分类及数量，不包含原始 codec/MIME 字符串、完整签名 URL、Cookie 或账号信息。每次最多输出前 16 个候选详情，最终汇总仍统计全部候选。

| 日志 | 含义 |
|---|---|
| `event=selection_failed reason=no_audio` | 接口没有返回可读取的独立 DASH 音轨 |
| `event=candidate reason=port` / `host` / `missing_url` | 候选地址端口/域名被拒绝，或没有地址 |
| `event=selection_failed reason=no_usable_url` | 有格式候选，但全部没有可用地址 |
| `event=candidate reason=codec` / `container` / `protected` / `metadata_missing` | 该候选因具体格式、保护或信息不足被拒绝 |
| `event=selected format=aac` | 元数据识别为 AAC，下一步请求媒体并验证实际内容 |
| `event=selected format=inspect` | 仅确认音频 MP4 容器，尚待实际编码检测 |

当前仍不实现 MP3/FLAC 转码、从无独立音轨的合并视频流提取音频或受保护音轨导出。
