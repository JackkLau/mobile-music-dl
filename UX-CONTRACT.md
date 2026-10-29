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
- UI 交互：`gradle :app:connectedDebugAndroidTest`，包括空态、搜索、删除确认、重试和播放入口。
- 单元检查：`gradle :app:testDebugUnitTest`。
- 设计格式：`npx -p @google/design.md designmd lint DESIGN.md`。
- 静态项目契约：`audit_project.py . --mode strict`；该脚本只识别 Web 源后缀，因此 native Compose 的通过只证明契约文件完整，不能证明 Kotlin 可编译或运行行为。
- 真机矩阵：Android 10 与较新 Android、320dp 窄屏、字体放大、TalkBack、横竖屏、前后台、锁屏、无网络、失效登录、长标题。
- 当前验证状态以 `docs/verification.json` 为准。独立 Kotlin 编译和 JVM 测试不等于 APK 构建或真机 UI 验收；尚未运行的项必须保留为待验证。

## 本轮网络调试入口补齐

debug 构建的“我的”提供“网络调试”，首次进入沿用桌面 UA、默认关闭日志；连续点击版本号七次的“开发者工具”入口仍保留。两个入口复用 DeveloperToolsScreen、ModeRow、DebugSettingsStore 和 AppViewModel。设置实时持久化；任务在开始时固定配置，关闭日志立即生效。release 不显示入口且忽略覆盖配置。对照仅检查样本，不将 206 或样本成功展示为整首保存成功。对应设备验证：TingYuUiTest、DebugSettingsStoreTest。
