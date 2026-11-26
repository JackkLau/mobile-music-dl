---
version: alpha
name: "微乐"
description: "以松石绿唱片意象承载链接解析、本地保存和持续聆听的 Android 原生播放器。"
colors:
  primary: "#145B52"
  background: "#F3F7F4"
  surface: "#FFFFFF"
  ink: "#132E2A"
  muted: "#48645D"
  mint: "#DAEEE6"
  outline: "#C5D6CE"
  error: "#A32F38"
  player-surface: "#20463F"
  player-muted: "#B3CDC2"
  player-accent: "#B5EFD6"
  player-error: "#FFB4AB"
typography:
  display:
    fontFamily: "sans-serif"
    fontSize: "32px"
    lineHeight: "40px"
  body:
    fontFamily: "sans-serif"
    fontSize: "16px"
    lineHeight: "25px"
  utility:
    fontFamily: "monospace"
    fontSize: "11px"
    lineHeight: "16px"
rounded:
  DEFAULT: "16px"
  small: "12px"
  panel: "24px"
spacing:
  page: "20px"
  player-page: "24px"
  item-gap: "12px"
  touch-target: "48px"
components:
  primary-button:
    backgroundColor: "#145B52"
    textColor: "#FFFFFF"
  card:
    backgroundColor: "#FFFFFF"
    rounded: "24px"
  player:
    backgroundColor: "#132E2A"
    textColor: "#FFFFFF"
---

# 微乐 Design System

## Overview

产品面向需要将 B 站视频声音加入日常聆听的安卓用户。当前界面为简体中文，使用中文标签、错误信息与无障碍名称；面向市场和正式发行地区尚未决定。应用原生实现，不是网页外壳。产品任务优先于品牌表达。

视觉参考是夜间桌上的唱片封套和安静的随身播放器；行为参考用户指定的网易云音乐等成熟播放器：迷你播放条、完整播放页、播放队列、进度拖动、循环模式与本地音乐库。只采用这些熟悉的功能模式，不复制其品牌、图标资产或专辑内容。

微乐的标识是一张米白唱片和一支从右上落下的唱针。唱片上的弧线是刻槽，中心圆孔与刻槽留空，缩小后仍能辨认。桌面、启动画面、应用内品牌区和默认歌曲封面共用这个图形。通知只取剪影，Android 主题图标的颜色由系统决定。播放、暂停等操作保留各自的功能图标。

运行时 token 由 `app/src/main/java/app/tingyu/ui/Theme.kt` 中 `TingYuColors` 和 `AppTypography` 统一拥有。本文件镜像其已接受值并解释意图。Compose 组件通过 `MaterialTheme.colorScheme` 和 `MaterialTheme.typography` 使用它们；播放器背景和唱片环直接引用同一 token 对象。前置 YAML 中的 px 表示逻辑设计单位：尺寸在 Android 映射 dp，文字映射 sp，允许系统字体缩放。没有独立 CSS 或重复的 Web 主题。桌面图标不能读取 Compose token，因此 `res/values/brand_colors.xml` 的 `brand_background` 是 `TingYuColors.Pine` 的 Android 资源适配值；两者与本文 `colors.primary` 保持一致。品牌主形由 `res/drawable/ic_brand_mark.xml` 唯一拥有，`BrandIcon`、普通／圆形桌面图标及 Android 13 单色图标共享该路径。`docs/assets/weile-icon.svg` 由同一矢量、品牌色和应用名导出。修改后运行 `python3 scripts/export-brand.py` 更新文档图标，源码检查会验证两者一致。

## Colors

主色松石绿承载解析、选择和播放操作。`ink` 与 `muted` 区分标题与辅助信息；错误同时提供图标和文字。`outline` 只用于非关键分隔，不能承担唯一交互提示。

浅色主界面使用 `background` 与 `surface`。播放器使用固定暗色配色：`ink` 背景、`player-surface` 层、白色正文、`player-muted` 辅助文字、`player-accent` 主操作。两种配色是产品场景变体，并非系统深色模式承诺。输入框、滑块、按钮由 Material3 渲染焦点、按下、不可用等状态。

## Typography

使用 Android 系统 SansSerif 及其中文 fallback，避免在线字体造成闪动或字体下载。标题 32/40sp、播放标题 26/34sp、区块标题 22/30sp、曲目标题 16/24sp、正文 16/25sp；时间和文件大小使用紧凑数字样式。封面标题和作者优先阅读完整内容，列表可截断，打开播放页可看完整标题。

## Layout

主屏 20dp 页边距，内容使用 LazyColumn；各 tab 分别拥有滚动状态，SaveableStateHolder 保存切换时的查询、选择和位置。底部导航与迷你播放条由 Scaffold 占位，列表不会藏到它们下面。链接输入页响应 IME padding。

全屏播放器使用独立 Dialog 窗口，处理状态栏、导航栏安全区。顶部标题与页签固定，底部进度和播放控制独立占位，不参与内容滚动。中部封面／完整歌曲信息使用 LazyColumn，歌词使用剩余空间内的独立列表，避免嵌套纵向滚动。封面按可用宽度封顶 300dp，短屏缩为 180dp。图标按钮最少 48dp，主播放按钮通常 76dp、短屏 64dp；短高宽屏将进度与主控制横向排列，并收拢歌词工具，保留正文可读空间。

## Elevation & Depth

普通列表采用平面色块与留白。只为常驻迷你播放器提供 4dp 层级阴影，表示它覆盖内容。播放器使用同色系纵向色阶和低透明度唱片环；不叠加模糊遮罩、浮动面板或随机光效。

## Shapes

封面缩略图 12dp 圆角，列表条目 16dp，主要内容面板和大封面 24dp；主播放按钮为圆形，其他控制遵循 Material3。封面固定为方形容器；用户选择的歌曲封面完整适配其中，加载失败或没有封面时显示本地绘制的松石绿唱片封套。大封套以歌名、歌手排版，小缩略图显示唱片标识，不再把视频横图当作专辑封面。所有色彩沿用 `TingYuColors`。

## Components

`Cover`、`TrackRow`、`StatePanel`、`ActionIcon` 和 `PageTitle` 位于 `Components.kt`，跨页复用。`BrandIcon.kt` 的 `BrandIcon` 复用桌面矢量主形；默认 48dp、版本行 36dp，置于松石绿圆角方形底板。旁边已有应用名称时图标为装饰，不重复朗读。自适应图标前景保持在中央安全区域，支持系统圆形、圆角方形与单色主题遮罩。`ModeRow` 统一账号模式与开发者 UA 单选行；调试页沿用原生 Material3 控件和 20dp 页面边距。

- 解析主操作为实色按钮；播放列表为 tonal 按钮；删除和退出位于明确的确认框。
- 解析来源使用可换行的 Material3 FilterChip；单曲卡沿用下载页卡片。重试与移除直接显示文字，大字号和窄屏时可换行。
- 表单使用带 label 的 OutlinedTextField。非空链接和音乐库查询有清除按钮。查询仅过滤本地数据，不发送逐字网络请求。
- 空态给出下一步操作。网络错误常驻相关页面；Snackbar 只反馈短暂结果，不是唯一错误载体。
- 音乐库的扫描/导入使用同组全宽 outlined 按钮；查询、曲目行与其他页面复用既有控件。读取进度和局部失败留在音乐库，删除确认保留到结果明确后关闭。
- 已知字节总量显示确定进度；未知字节总量显示不确定进度。整理音频单独显示阶段，不能用下载字节假冒处理进度。
- 播放队列使用 Material3 ModalBottomSheet。当前项用色块和“当前曲目”语义标记，用户可直接点选。
- Material Rounded 图标，装饰性图标不重复朗读，操作图标提供中文 contentDescription。
- 不循环播放装饰动画；使用系统 Material 状态反馈。唱片环静止，不制造假的播放状态。

## Do's and Don'ts

- 用户可见名称为“微乐”；`app.tingyu` 包名、原有签名、内部类名和 `Music/听屿/` 保存路径保留兼容性，不移动或重建用户音乐库。旧路径在文件管理说明中按实际名称显示。

- 保持正在播放、已下载、选中待下载三种状态相互独立。
- 所有曲目都来自解析或本地数据库；产品内不插入测试 fixture。
- 不因切换 tab、打开队列或收起播放器而中断声音。
- 不将账号头像、封面加载成功视作登录或音频解析成功。
- 不添加没有数据来源的歌词、音质标签或推荐列表。
- 歌词来自用户导入或明确触发的网易云音乐／LRCLIB 查询，标明来源及滚动／纯文本状态。当前行高亮，手动翻看暂停自动跟随；“回到当前”恢复跟随。点句子跳转和时间校准共用同一时间基准；有歧义的版本需用户核对专辑与时长后选择。在线封面需明确选择，专辑图与歌手照片按类型标注。封套上的重复歌名是装饰文字，不重复加入 TalkBack 语义。
- 不用只依赖拖动的进度交互：滑块支持平台可访问性调整，同时提供前后 10 秒按钮。
