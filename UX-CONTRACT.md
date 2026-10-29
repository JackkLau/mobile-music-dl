# 听屿 UX Contract

## Product context

用户任务：从哔哩哔哩链接解析音轨，在线播放、下载到手机并离线播放；匿名和登录模式并存，登录后通过收藏夹链接读取列表。Android 首发，后续 iOS 原生。

当前 locale 为 zh-CN；时间长度以分钟/秒展示，超过一小时展示小时；不涉及日期时区。可访问性目标为 WCAG 2.2 AA 对应的原生触控、TalkBack 和字体缩放要求。当前尚未完成真机可访问性验收。

## Business-context sources

| Domain / scope | Authoritative source | Source type | Reviewed date |
|---|---|---|---|
| 功能与匿名/登录边界 | 本任务用户需求，`model/Models.kt` 与 `model/AppUiState.kt` | 用户要求及界面契约 | 2026-10-28 |
| B 站权限 | 服务器返回的当前账号访问权限；`data/bili/BilibiliRepository.kt` | 运行时授权结果 | 2026-10-28 |
| 本地文件 | `data/local/LibraryStore.kt`、下载模块 MediaStore 行为 | 领域实现 | 2026-10-28 |
| 删除 | 用户在音乐库确认后调用 `AppActions.deleteLocal` | 显式用户操作 | 2026-10-28 |
| 视觉/功能参考 | 用户指定成熟音乐播放器的播放功能；`DESIGN.md` | 用户要求 | 2026-10-28 |

无支付、计费、广告或自动对外发布功能。未添加未经确认的法律承诺。

## Visual contract

`DESIGN.md` 记录视觉意图。`ui/Theme.kt` 是运行时 token 唯一所有者，主屏浅色、播放页暗色。所有页面使用同一组共享组件。此应用不含 HTML、CSS、Web forms，网页 scrollbar / ARIA / noValidate 规则由 Compose 原生语义、Material 输入和原生滚动机制替代。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Table Selection | ParseScreen + Material Checkbox | AppUiState.result / 本契约 | 当前已加载结果，多选后确认 | UI instrumentation selection test |
| Select/Listbox | 共享 ModeRow + selectableGroup | SessionState | 原生 radio 互斥模式；开发者 UA 选择 | UI semantics + device TalkBack |
| Form | ParseScreen OutlinedTextField | AppActions.input / parse | 链接输入、本地查询 | UI instrumentation empty/clear |
| Scrollbar | Compose LazyColumn | Native Android | 页面列表、队列独立滚动 | 小屏与大字体设备检查 |
| Toast | TingYuApp SnackbarHost | AppUiState.message | 短暂操作反馈 | UI fixture / native semantics |
| CRUD | LibraryScreen + AppActions | local LibraryStore | 本地删除确认、失败保留 | UI confirmation + storage tests |
| Song details | SongDetailsDialog + SongDetailsStore | Track 来源字段与本地歌曲信息 | 歌名/歌手修正、系统封面选择 | store + UI tests |
| Lyrics | LyricsPanel + LyricsLookup + MusicCatalogRepository + LyricsRepository + SongDetailsStore | 本地 LRC/TXT、网易公开响应、LRCLIB | 自动精确匹配／手选版本、偏移、滚动、纯文本、纯音乐、空/错/等待 | parser/client/store + UI tests |
| Artwork lookup | SongDetailsDialog + MusicCatalogRepository + SongFileImporter | 网易／QQ 公开目录与用户明确选择 | 专辑封面、歌手照片；本地缓存 | catalog + store + device tests |
| Player transport | PlayerControls | PlaybackState / AppActions | 固定底部；短屏紧凑 | PlayerBottomControlsTest |

## Component behavior

所有可操作图标使用 48dp IconButton。播放主按钮 76dp。按钮状态由真实 pending/state 决定；加载期间解析按钮保持布局尺寸。表单输入保留文本，非空显示清空按钮；应用不在后台偷偷读取剪贴板，仅响应“粘贴”。

TrackRow 点击播放；Checkbox 仅选择；下载图标仅触发下载。已下载状态与正在播放状态分别显示。曲目名称截断时，在完整播放器页可阅读全文。

## Dataset navigation

收藏夹显式“加载更多”，每次仅操作已加载集合。全选标记为“全选已加载”，批量下载显示准确所选数量并确认。多页结果由 ViewModel 合并去重；UI 不假设收藏夹条目都可播放。

音乐库搜索为本地即时过滤，支持标题/作者，非空显示清除。各 tab 用 SaveableStateHolder 保留本地交互状态；无网页 URL 状态，敏感收藏夹和凭据不放进可分享 URL。

## Flow ledger

| Operation | Trigger | Pending | Success destination | Success feedback | Failure recovery | Focus outcome | Source ref |
|---|---|---|---|---|---|---|---|
| 解析 | 解析链接 | 禁用重复解析，进度提示 | 当前解析页列表 | 列表标题和条目数 | 保留输入、明确错误、重新解析 | 列表可继续阅读 | AppActions.parse |
| 在线播放 | 点击曲目 | mini/player 准备状态 | 持续播放，播放器可收起 | 当前项、时间、媒体控制 | playback.error + 重新播放 | 原页保持 | AppActions.play |
| 批量下载 | 选择后确认 | 任务入队 | 下载 tab 可查看 | 每条真实状态 | 单项失败单项重试 | 确认框关闭 | AppActions.download |
| 删除音频 | 删除图标后确认 | 由操作层执行 | 音乐库 | 列表更新 | 错误反馈且记录保留 | 确认框关闭 | AppActions.deleteLocal |
| 退出登录 | 确认退出 | 清除会话 | 匿名模式 | 账号区域更新 | 显示真实错误 | 当前页保持 | AppActions.logout |

## Developer mode and login feedback

- 调试模式默认关闭，仅 debug 构建连续点击版本号 7 次可开启；相邻点击超过 2 秒重置计数。release 不显示解锁操作且忽略持久化测试配置。
- “我的”仅在开启调试模式后显示“开发者工具”；网络选项和对照功能只存在于该独立页。关闭时重置选项并停止日志，新请求使用桌面 UA，进行中的下载保留原请求快照。
- 设置通过 `DebugSettingsStore` 串行持久化，失败通过统一 Snackbar 提示；`ModeRow` 负责带语义的单选，整行 Switch 有明确标签。
- 对照必须有已解析音轨，可选择分 P。运行中禁用重复启动和修改 UA，显示真实等待状态并提供取消；返回/切换页/切换账号取消，迟到结果不得覆盖新结果。样本读取不代表保存成功。
- 登录页只有服务端 `/nav` 验证和本机加密保存都成功后，才显示持久“登录成功”、用户名和“返回听屿”。不凭跳转地址或头像断定成功。
- 自动验证去重；失败保留手动“完成登录”检查，暂停/退出取消请求，Cookie 改变后拒绝旧响应；凭据不进入界面或日志。原生成功提示使用 live region，布局沿用现有登录页。
- 这些行为由 `DebugSettingsTest`、`LoginVerificationGateTest`、网络单测与 `TingYuUiTest` 约束；Android 运行时交互仍需真机验收。

## Navigation and responsive behavior

四个主 tab 为解析、音乐库、下载、我的。迷你播放条常驻，但无曲目时不伪造占位播放。全屏播放页通过独立 Dialog 打开，可由下拉箭头或系统返回关闭。队列在播放器窗口内打开，避免被播放 Dialog 遮挡；其他页面在主窗口打开队列。

界面在窄屏和大字体时允许自然换行和纵向滚动，不用固定高度裁切操作。Scaffold 给底部栏保留空间；解析页响应 IME；播放页和队列处理系统安全区。

## Overlays and feedback

确认框使用 Material AlertDialog。批量下载确认显示数量，删除确认显示曲目及删除本地文件的结果，退出确认说明保留已下载内容。普通播放、单曲下载、tab 切换不弹确认。

错误按范围归属：解析错误留在解析页，播放错误在播放器页，下载错误保留在任务卡片。Snackbar 只展示操作级反馈。Dialog/Sheet 的返回、焦点和交互语义由 Material3 所有。

## Async and resilience

网络解析/翻页由 ViewModel 持有 pending，UI 不自行发送请求；旧响应失效、登录模式切换清理私密结果由应用层控制。没有 optimistic 假成功。任务恢复和重试由下载模块持有，UI 显示中断状态与明确重试入口。

播放清单存 Track 标识，不把临时播放 URL 当作永久文件。在线播放不会自动变成永久下载。播放器选本地/在线来源，UI 从 library 判断是否已下载，无法凭缓存状态显示“已下载”。

## Validation and privacy

链接验证在解析层，空输入禁用解析。输入框支持系统粘贴和输入法，保留失败文本。登录发生在独立官方网页流程；UI 不显示 Cookie、令牌或密码输入框。匿名/登录切换不改变已下载音乐库。错误消息不得包含凭据。

## Verification

- 构建：`gradle :app:assembleDebug`。
- UI 交互：先构建 debug 应用／测试 APK，再显式设置 `TINGYU_TEST_DEVICE` 和 `TINGYU_TEST_USER`，运行 `bash scripts/check-device.sh`；包括空态、搜索、删除确认、重试和播放入口。设备必须已经切换到本次测试的非零临时用户，脚本拒绝 user 0 或当前用户不符，不自动创建、切换、清除或删除用户。
- 单元检查：`gradle :app:testDebugUnitTest`。
- 设计格式：`npx -p @google/design.md designmd lint DESIGN.md`。
- 静态项目契约：`audit_project.py . --mode strict`；该脚本只识别 Web 源后缀，因此 native Compose 的通过只证明契约文件完整，不能证明 Kotlin 可编译或运行行为。
- 真机矩阵：Android 10 与较新 Android、320dp 窄屏、字体放大、TalkBack、横竖屏、前后台、锁屏、无网络、失效登录、长标题。
- 当前验证状态以 `docs/verification.json` 为准。独立 Kotlin 编译和 JVM 测试不等于 APK 构建或真机 UI 验收；尚未运行的项必须保留为待验证。

设备测试统一通过 `check-device.sh` 直接安装并运行指定用户的 instrumentation；`check.sh --device` 和 `premium-ui.json` 使用同一入口，不使用 Gradle connected 设备测试的自动安装／卸载生命周期。必须选择可丢弃的专用模拟器，并先备份或保存快照；非零用户不是完整隔离保证，Android 包代码仍由不同用户共享，覆盖安装和测试本身仍可能改变设备状态。脚本必须检查实际 instrumentation 成功结果，不能把 adb 退出码 0 或测试进程结束等同于测试通过。

2026-10-30 曾在窄屏测试结束后发生 Gradle UTP 跨用户卸载事故，原模拟器 user 0 的应用私有数据库、队列和设置被清除。共享音频仍在，已通过扫描恢复 1 首并备份；没有备份的队列和设置无法恢复。手机当时未连接、未受影响。事故不能被后续测试通过抵消，具体范围与处理见 [本轮事故记录](docs/LYRICS-PLAYER-VERIFICATION.md#设备测试事故与入口修复)。

## 本轮网络调试入口补齐

debug 构建的“我的”提供“网络调试”，首次进入沿用桌面 UA、默认关闭日志；连续点击版本号七次的“开发者工具”入口仍保留。两个入口复用 DeveloperToolsScreen、ModeRow、DebugSettingsStore 和 AppViewModel。设置实时持久化；任务在开始时固定配置，关闭日志立即生效。release 不显示入口且忽略覆盖配置。对照仅检查样本，不将 206 或样本成功展示为整首保存成功。对应设备验证：TingYuUiTest、DebugSettingsStoreTest。

## 本地音乐管理（0.1.2）

用户要求升级后能管理已经下载的音乐。`LocalMusicManager` 负责扫描、导入和真实文件删除；`LibraryStore` 负责兼容原数据库并按 URI 去重；`MainActivity` 负责系统权限、文件选择及删除授权。音乐库继续复用 `TrackRow`、`ActionIcon`、Material3 按钮与确认框，不改视觉 token。

- 启动时只扫描系统当前允许读取的 `Music/听屿/` 已完成音频；点击“扫描已下载音乐”才申请音频读取权限。拒绝权限仍保留已有库，也可选择“导入本地音频”。不申请所有文件管理权限。
- 导入通过系统文件选择器，支持多选，仅保留获得授权且可以读取的音频 URI；不复制、不移动、不转码。扫描/导入按 URI 去重，保留已有曲目信息，不因权限或文件暂不可见清库。
- 读取任务由 ViewModel 持有，重复点击禁用；显示不确定进度和新增/已有数量，部分失败留在音乐库内。页面允许离开，已有音乐仍可查看和搜索。
- 删除确认说明会删除手机上的真实文件；等待真实删除结果期间确认框保持打开并禁用重复操作。需要系统确认时通过平台授权；取消、失败均保留记录和播放队列，错误留在确认框供重试。
- 文件确实删除或明确不存在后才移除记录及播放队列。若正在下载的新文件替换了记录，旧删除操作不得清除新记录。
- 恢复/导入条目标记为仅本地。文件失去授权或被外部移除时提示重新导入，不回退到网络解析，也不允许重新下载本地条目。
- 旧版本 JSON 缺少 localOnly 字段时继续按原网络曲目读取；Room 表结构与数据库名保持不变。同签名覆盖升级不清数据库或共享音乐目录。
- 测试责任：真实 MediaStore/隔离 Room 集成测试、本地身份与旧 JSON 单测、UI 扫描/导入/删除等待失败/本地播放入口测试。手动视觉、系统授权取消和设备升级的验证结果另记，不能由静态检查推定。

## 歌曲信息、歌词与封面（0.1.3）

- B 站解析层通过 `MusicMetadata` 整理明确的“歌手《歌名》”“歌手 - 歌名”和带标签格式。多分 P 优先使用各分 P 名称；未明确的歌手留空，不把上传者当歌手。合集、串烧、多个歌曲名不强行取第一首，Live/翻唱/Remix 等版本信息保留。此功能不是听音识曲，歧义标题可手动修正。
- 原标题、上传者和视频封面独立保留；播放器、队列、音乐库、下载列表与媒体通知共用整理后的显示信息。曲目标识、取流参数、音频文件、数据库表结构不变。旧 JSON 缺失新字段时按兼容默认值读取。
- “编辑歌曲信息”固定编辑目标；自然切歌不关闭编辑器或丢失草稿。取消未保存内容需确认；校验失败/写盘失败保留内容和错误。歌名不能为空，歌名和歌手各限 200 字。改变歌名/歌手会移除本首旧歌词，避免错配，保存前明确提示。
- 封面／歌词在播放器切换，声音持续播放。封面使用共享 `Cover` 方形封套；用户可通过系统文件选择器更换歌曲封面或恢复默认。图片上限 12 MB，解码后最长边 1024，保留比例和方向，私有保存，不保留外部文件长期授权。
- 打开歌词页只读本地缓存；点击“联网查找”才把歌名、歌手和时长发送到 LRCLIB。页面先说明这一行为。请求与 B 站登录 Cookie 隔离，仅用官方公开接口；不进行全库后台扫描，不自动发布歌词。标明来源，支持取消和失败后显式重试，遵守服务端 Retry-After。
- 在线结果必须匹配歌名、歌手和已知时长（±2 秒）；没有可靠时长时只显示纯文本。未匹配不伪造歌词，也不把模糊搜索首项直接绑定。歌词来源覆盖与具体视频版本的时间轴仍需用户核对。
- 可从系统文件选择器导入 UTF-8 或带 BOM 的 UTF-16 LRC/TXT，最多 256 KB、3000 行。导入后私有保存，可离线显示。LRC 支持多时间戳、offset、翻译同行及间奏；无时间戳按纯文本展示，不假装同步。
- 切歌取消旧查询；编辑后的迟到歌词不能覆盖新信息。文件选择固定原曲目，迟到结果不串到当前曲目。局部错误按目标 id 归属，跨曲目操作反馈明确歌名。
- 元数据、歌词、封面写盘成功后才公布状态；原文件不重命名、不转码。曲目信息更新保留媒体 URI/configuration，不重新加载正在播放的音频。删除已保存歌词需确认，保留音频和原始导入文件。

## 歌词完善与固定播放区（0.1.4，覆盖上节旧交互）

- 主控区固定在底部，始终保留进度、前后 10 秒、模式、上一首、播放暂停、下一首与队列；中部滚动不能移动控件。完整标题仍在封面页可滚动查看。短高宽屏合并顶部页签并横排底控；歌词工具收拢到选项，保留正文空间。
- 明确触发后先查网易，失败或无唯一精确匹配时使用 LRCLIB；所有请求无账号 Cookie、有界读取、取消、超时与限流处理。自动查询仍要求歌名、歌手、时长匹配，搜索首项不是默认正确答案。
- “歌词选项 → 选择歌词版本”仅展示歌名和歌手精确匹配的网易版本，最多 8 项；明确标注专辑、来源和时长差，选中并确认后才取词并保存。加载可取消；无歌词、服务拒绝或写盘失败保留原歌词。切歌／编辑使旧查询失效。
- 点歌词按校准后的时间跳转。提前／延后 0.5 秒、重置提供无需拖动的操作，范围 ±30 秒；与内嵌 LRC offset 叠加，当前句高亮使用相同时间基准。偏移随歌词离线保存，不改变原歌词文件。
- 新歌词缓存记录歌名、歌手、BV、P、cid 与时长签名，已知音源或时长变化拒绝旧缓存。旧 0.1.3 缓存没有签名，只能兼容读取，重新查询／校准后补签名，不能追溯证明旧身份正确。
- 在线封面先网易、无结果用 QQ；只呈现精确名称匹配的专辑或唯一艺人图片。预览标注来源和类型，用户确认后下载受限官方 HTTPS 图片，解码缩小后保存私有目录。失败保留原图，改名清除联网匹配图，手选图保留。重新解析身份变化时，联网图签名不匹配则展示默认封套。
- 外部平台按钮打开浏览器搜索，不自动抓取豆瓣。QQ 歌词拒绝结果不绕过。外部来源可能变化，不宣称完整复制成熟平台的曲库覆盖或逐字卡拉 OK。
