# 0.1.3 歌曲信息、歌词与封面验证

日期：2026-10-29 至 2026-10-30（本地跨日完成）。当前源码入口仍为根工程 `app/src`；历史嵌套工程未修改。版本 `0.1.3` / `versionCode 4`，包名 `app.tingyu`。

## 需求与实现

| 需求 | 代码 | 新验证证据 |
|---|---|---|
| 从视频标题整理歌名、歌手 | `MusicMetadata`、`BiliResponseParser`；保留 `sourceTitle/sourceArtist/videoCoverUrl` | 新增 15 项元数据/解析测试，涵盖单曲、多 P、旧 JSON、本地标签、版本与合集保留 |
| 歌词显示及同步 | `LyricsRepository`、`LrcParser`、`LyricsPanel` | 17 项歌词 JVM 测试；真实 HTTP 模拟涵盖取消、限流、错误页、时长/身份拒绝；设备验证同步/纯文本/纯音乐、取消、导入和确认移除 |
| 已有音乐可修正信息且重开保留 | `SongDetailsStore`、`SongDetailsDialog`、`AppViewModel` | 7 项持久化测试、3 项迟到反馈测试；设备验证原曲目草稿不因切歌丢失，写入后重新读取一致 |
| 手机封面 | 共享 `Cover`、`SongFileImporter` | 设备导入图片缩放至最长边 1024、保持比例，验证只删除自己的私有封面；截图核对方形封套与信息编辑页 |
| 不影响原有播放和下载 | `PlaybackService` 仅替换媒体 metadata；原 URI/configuration 不变 | 真实合成 WAV 经 Room→MediaSession→DataSource→ExoPlayer 播放、跳转、切歌、播放中改名且位置不回零；旧网络、下载与本地管理用例继续通过 |

标题整理不是听音识曲。`A - B` 默认按“歌手 - 歌名”解释，有歧义可手动修改；未识别歌手留空。没有新增联网专辑封面查找、内嵌封面提取、歌词自动生成、任意音频格式转码或视频下载。

## 本轮执行

- `bash scripts/check.sh`：102 项结构检查、29 项脚本测试、应用 JVM、lint、debug/设备测试 APK 构建、PC 共用请求测试与工具打包通过。
- 应用 debug JVM **191 项通过**；release JVM 为同一套 **191 项通过**，不能相加。
- PC 共用请求与模型测试 **128 项通过**，与应用测试有重叠，不能相加。
- Android API 37 模拟器，独立临时用户：**40 项通过，0 失败、0 跳过**。其中本地存储 11、歌曲文件导入 3、真实播放 1、设置 1、UI 24。
- 首次设备测试因临时用户自动锁屏，17 项 UI 无法找到 Compose 页面；日志明确记录此失败。确认窗口状态为锁屏后解锁并仅延长临时用户亮屏时间，重新全量运行 40 项通过。原用户的音乐库、队列与设置保留。
- 最终交互修正后追加 7 项相关设备回归通过；320dp 窄屏、1.5 倍字体下追加 4 项回归通过（与上述 40 项重叠，不能相加）。截图核对歌词高亮、可滚动编辑器和小屏播放操作可访问性。最后统一搜索/歌手文案与封面取消保护后，另有 5 项设备回归通过，同样属于重叠检查。
- LRCLIB 官方示例实际 GET：HTTP 200，歌名/歌手匹配、时长 233 秒，包含同步和纯文本歌词；仅记录元数据，不保存歌词正文。这不证明任意中文歌曲或指定视频都有可用歌词。
- 正式 APK/AAB 本机编译成功，并通过原证书、包名/版本、不可调试、无诊断入口、APK 对齐与 AAB 全部载荷签名验证。云端发布另行记录。
- lint debug 24 / release 23 个非阻断警告，0 错误；本轮新增一个 `String.toUri` 的 KTX 写法建议，其余为既有依赖更新/平台建议。设计静态审计 0 问题，DESIGN 格式检查 0 错误、7 个既有 token 引用警告。

日志目录：[本轮日志](verification-logs/song-details-20261029/)。截图在本地 `artifacts/song-details-0.1.3/`，使用合成测试曲目和歌词，不包含产品内置示例歌库。

## 设计核对

| 原规则 | 证据 | 处理 |
|---|---|---|
| 默认封面为音符占位、视频图直接裁切 | 原 `Cover` 直接使用视频 coverUrl | 按用户要求改为雾蓝方形唱片封套；可选自己的歌曲封面，完整适配 |
| 大封面区域上限 340dp | 增加封面/歌词切换及歌曲信息入口 | 改为 300dp，沿用原配色、形状与共享 Cover，主播放控制仍可访问 |
| 不添加没有来源的歌词 | 新 LRCLIB / 本地导入来源字段与空态说明 | 保留规则，只呈现真实获取/导入内容；显示纯文本、同步或纯音乐状态 |

## 待真机验收

- 手机 `7c1213a1` 本轮未连接：同证书覆盖升级、不同系统文件提供方、真实图库照片方向、后台/锁屏歌词与 TalkBack 仍待真机检查。
- 真实歌曲标题整理的覆盖率，歌曲/歌手匹配，中文歌词服务覆盖，以及具体视频前奏/剪辑造成的歌词时间偏差，需实际试听。
- 既有 403 结论边界不变：桌面 UA 成功反馈与 CDN 节点变化并存，206 不等于完整保存或播放成功。

## GitHub 正式发布

2026-10-30 00:06 HKT，`v0.1.3` 已正式发布：[版本页](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.3) · [APK](https://github.com/JackkLau/mobile-music-dl/releases/download/v0.1.3/tingyu-0.1.3-release.apk)。

- 来源提交：`fc018851dc1d65af50eeae48886d8757f256befe`。
- [正式构建与发布 40907581731](https://github.com/JackkLau/mobile-music-dl/actions/runs/40907581731)：build / publish 均成功，原签名校验通过。
- 分支与标签的 Android verification（40907571242、40907582393）均成功。
- 公开附件下载后重新计算哈希，与 GitHub asset digest、release.json 一致，并独立执行 `verify-release.py`；包名、0.1.3/code4、正式签名、无诊断入口、不可调试、ZIP 对齐及 AAB 所有载荷签名全部通过。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `tingyu-0.1.3-release.apk` | 51,358,324 | `f421cb6f5917f7cb4420dd70672dab19b28c06554c3707c640dee767c6ef9866` |
| `tingyu-0.1.3-release.aab` | 14,763,629 | `b10bc254a44cfb8d4bc7dacfc9f2f24850a3653a168a07d0dfce631dbb25aa83` |

云端文件与本机独立构建的字节哈希可能不同；上表明确对应公开发布附件。证据在 [GitHub 发布记录](verification-logs/song-details-20261029/github-release/)。模拟器临时用户 11 已清理，仅原用户 0 保留；显示密度与字体设置已恢复。
