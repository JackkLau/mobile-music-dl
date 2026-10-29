# 微乐 0.1.5：歌词候选与品牌更新验证

日期：2026-10-30。活动源码为 `app/src`，包名仍为 `app.tingyu`，版本为 `0.1.5 / code 6`。历史 `app/app` 不参与构建。

## 修复与验证映射

| 用户可见结果 | 代码 | 本轮证据 |
|---|---|---|
| 自动匹配不确定时打开候选，而非直接结束 | AppViewModel.findLyrics / loadLyricCandidates | 代码检查；后端重复版本回归；界面测试另记 |
| 可只填歌名，修正搜索词不改歌曲资料 | SongDetailsUi / AppUiState / 原曲目快照保存 | 空歌手、时长差及版本后缀回归；真实两首歌查询 |
| 网易与 LRCLIB 候选合并，选定来源编号取词 | MusicCatalogRepository / LyricsRepository / LyricsLookup | ID 分发、伪造候选、身份变更、部分来源故障测试 |
| 503／429 保留来源与状态，故障不显示为无歌词 | LyricsSourceException / 来源冷却状态 | 错误脱敏、冷却回归及真实 503 响应 |
| 名称与图标统一为微乐 | app_name / BrandIcon / adaptive icon / ParseScreen / LibraryScreens / LoginActivity | APK badging 显示“微乐”；XML、矢量预览与资源编译 |
| 升级保留原音乐 | 包名、数据库、song-details、Music/听屿 与签名保持不变 | 本轮未修改这些存储实现；正式签名与真机升级单独验证 |

自动绑定仍要求唯一、精确的身份与时长；候选搜索放宽限制，但必须由用户确认具体录音版本。不会把搜索第一项视为正确答案，不实现听音识曲或逐字卡拉 OK。

## 本地检查

- `source .tooling/env.sh` 后 `bash scripts/check.sh` 退出 0；JDK 17、Gradle 8.11.1、Android SDK 35。
- 应用 JVM：242 项，0 失败／错误／跳过，其中 14 项为本轮新增回归。
- 脚本：41 项通过。PC 共享 JVM 任务通过（128 项报告，未改动部分复用了构建缓存；与应用测试有重叠，不相加）。
- Android debug lint、应用 APK、instrumentation APK 构建通过。
- 严格 UI 静态审计：0 findings；DESIGN 格式：0 错误、7 条 token 引用警告。静态工具不证明运行时 UI。
- 新 APK 包名 `app.tingyu`、versionCode 6、名称“微乐”，包含普通、自适应、圆形及 API 33 单色图标。

## 真实来源查询

使用新编译的应用仓库代码在 Mac 发起公开查询，只发送歌名；未上传音频，未保存或输出歌词正文。指定版本仅用于验证，不推断用户手机上的实际歌手。

| 歌名（歌手查询为空） | 候选结果 | 指定版本 | 结果 |
|---|---|---|---|
| 遥远的她 | 网易 20 项；LRCLIB HTTP 503 警告仍保留 | 张学友，网易 191232 | 成功取得 51 行带时间轴歌词 |
| 三国杀 | 两来源共 31 项；本次无来源警告 | 汪苏泷，网易 165367 | 成功取得 50 行带时间轴歌词 |

503 为本次真实服务故障；后续另一首查询成功不能抹去先前失败，也不能证明手机网络可用或视频歌词时间轴完全吻合。用户确认后仍可按实际音频校准。

## 设备与发布状态

- debug 与 release 各 242 项应用 JVM 测试通过，两者为同一套测试，不相加。正式 lint、APK、AAB 本机构建通过；签名、包名、版本、非调试边界、ZIP 对齐及 AAB 全内容签名校验通过，沿用原证书。正式 APK 显示名称“微乐”。
- 新建了独立的空数据 API 37 AVD，未使用旧模拟器的数据。普通／headless、显式加速及软件图形启动均遇到 HVF／mprotect 错误，最后一次退出 139；未完成启动，未安装 APK，未运行本轮 51 项 instrumentation 测试（含 2 项新增候选搜索测试）。测试 APK 已编译通过，但不等于运行通过。
- 原 `emulator-5554` 未安装、卸载、清理或切换用户。独立尝试的进程已停止，诊断保留在本地 `.tooling/weile-verification-avd/`。
- [GitHub 正式构建与发布](https://github.com/JackkLau/mobile-music-dl/actions/runs/40925577014)通过，标签 `v0.1.5` 对应提交 `a2c33be094ce780d0ba1deed4efd83356677789d`；同时源码检查工作流通过。
- 公开 APK／AAB 重新下载后，元数据与 SHA256SUMS 一致；独立复核包名、版本、原证书、APK 非调试边界／对齐及 AAB 全内容签名全部通过。APK 显示名称为“微乐”。
- 原证书 SHA-256：`ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911`。
- [下载与发布说明](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.5)。

手机 `7c1213a1` 当前未连接，真机覆盖升级、TalkBack、完整播放及具体视频时间同步仍待验收。原模拟器及其数据不用于本轮自动安装测试。

## 兼容性与边界

- 用户可见名称更换为微乐；包名、类名、日志标签、仓库地址和 `tingyu-…` 发布文件前缀为兼容保留。
- `Music/听屿/` 继续表示实际目录，不移动、删除或重命名已有音频；歌词仍私有缓存并记录来源 URL。
- 网易公开网页接口不保证长期稳定；LRCLIB 可能暂时不可用。候选搜索是明确触发的请求，遵守冷却、超时和取消，不进行后台全库抓取。
- 不接入新的 QQ／酷狗歌词服务、商业识曲服务或豆瓣抓取；QQ 继续用于封面回退，豆瓣保留外部资料入口。

## 云端公开产物

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| tingyu-0.1.5-release.apk | 51574668 | `318ff484d6738991911094efe6cc2cafa1ff1483110c5a5bb7d7f3e4f14ec70b` |
| tingyu-0.1.5-release.aab | 14837429 | `05a4956037205965e4978a48eb2a836a4f0e062da63488a564cddf421bd59a5f` |

本轮 lint 为 debug 25 条、release 26 条警告、均 0 错误；包括依赖升级建议、现有平台建议及分版本 launcher 资源警告。已检查正式 APK 中 API 33 图标实际包含 monochrome 节点，不能将该静态警告等同于缺失主题图标。
