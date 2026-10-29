---
version: alpha
name: "听屿"
description: "以雾蓝唱片意象承载链接解析、本地保存和持续聆听的 Android 原生播放器。"
colors:
  primary: "#3F5D96"
  background: "#F5F7FC"
  surface: "#FFFFFF"
  ink: "#17243A"
  muted: "#59677C"
  soft-blue: "#E3EAF7"
  outline: "#CED7E6"
  error: "#A32F38"
  player-surface: "#24354F"
  player-muted: "#B7C6DF"
  player-accent: "#C2D5FF"
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
    backgroundColor: "#3F5D96"
    textColor: "#FFFFFF"
  card:
    backgroundColor: "#FFFFFF"
    rounded: "24px"
  player:
    backgroundColor: "#17243A"
    textColor: "#FFFFFF"
---

# 听屿 Design System

## Overview

产品面向需要将 B 站视频声音加入日常聆听的安卓用户。当前界面为简体中文，使用中文标签、错误信息与无障碍名称；面向市场和正式发行地区尚未决定。应用原生实现，不是网页外壳。产品任务优先于品牌表达。

视觉参考是夜间桌上的唱片封套和安静的随身播放器；行为参考用户指定的网易云音乐等成熟播放器：迷你播放条、完整播放页、播放队列、进度拖动、循环模式与本地音乐库。只采用这些熟悉的功能模式，不复制其品牌、图标资产或专辑内容。

唯一表达性元素是全屏播放器中包围专辑封面的三道唱片环。工具页面保持银白背景、墨蓝文字和朴素列表；无虚构推荐歌曲、无自动轮播广告、无装饰性假波形。

运行时 token 由 `app/src/main/java/app/tingyu/ui/Theme.kt` 中 `TingYuColors` 和 `AppTypography` 统一拥有。本文件镜像其已接受值并解释意图。Compose 组件通过 `MaterialTheme.colorScheme` 和 `MaterialTheme.typography` 使用它们；播放器背景和唱片环直接引用同一 token 对象。前置 YAML 中的 px 表示逻辑设计单位：尺寸在 Android 映射 dp，文字映射 sp，允许系统字体缩放。没有独立 CSS 或重复的 Web 主题。

## Colors

主色雾蓝承载解析、选择和播放操作。`ink` 与 `muted` 区分标题与辅助信息；错误同时提供图标和文字。`outline` 只用于非关键分隔，不能承担唯一交互提示。

浅色主界面使用 `background` 与 `surface`。播放器使用固定暗色配色：`ink` 背景、`player-surface` 层、白色正文、`player-muted` 辅助文字、`player-accent` 主操作。两种配色是产品场景变体，并非系统深色模式承诺。输入框、滑块、按钮由 Material3 渲染焦点、按下、不可用等状态。

## Typography

使用 Android 系统 SansSerif 及其中文 fallback，避免在线字体造成闪动或字体下载。标题 32/40sp、播放标题 26/34sp、区块标题 22/30sp、曲目标题 16/24sp、正文 16/25sp；时间和文件大小使用紧凑数字样式。封面标题和作者优先阅读完整内容，列表可截断，打开播放页可看完整标题。

## Layout

主屏 20dp 页边距，内容使用 LazyColumn；各 tab 分别拥有滚动状态，SaveableStateHolder 保存切换时的查询、选择和位置。底部导航与迷你播放条由 Scaffold 占位，列表不会藏到它们下面。链接输入页响应 IME padding。

全屏播放器使用独立 Dialog 窗口，处理状态栏、导航栏安全区；小屏或大字体时播放页本体可以滚动。封面按可用宽度自适应并封顶 340dp。进度条和控制区使用整行宽度；普通图标按钮最少 48dp，主播放按钮 76dp。

## Elevation & Depth

普通列表采用平面色块与留白。只为常驻迷你播放器提供 4dp 层级阴影，表示它覆盖内容。播放器使用同色系纵向色阶和低透明度唱片环；不叠加模糊遮罩、浮动面板或随机光效。

## Shapes

封面缩略图 12dp 圆角，列表条目 16dp，主要内容面板和大封面 24dp；主播放按钮为圆形，其他控制遵循 Material3。专辑图片固定容器大小，加载失败时露出音符占位图，避免布局跳动。

## Components

`Cover`、`TrackRow`、`StatePanel`、`ActionIcon` 和 `PageTitle` 位于 `Components.kt`，跨页复用。`ModeRow` 统一账号模式与开发者 UA 单选行；调试页沿用原生 Material3 控件和 20dp 页面边距。

- 解析主操作为实色按钮；播放列表为 tonal 按钮；删除和退出位于明确的确认框。
- 表单使用带 label 的 OutlinedTextField。非空链接和音乐库查询有清除按钮。查询仅过滤本地数据，不发送逐字网络请求。
- 空态给出下一步操作。网络错误常驻相关页面；Snackbar 只反馈短暂结果，不是唯一错误载体。
- 已知字节总量显示确定进度；未知字节总量显示不确定进度。整理音频单独显示阶段，不能用下载字节假冒处理进度。
- 播放队列使用 Material3 ModalBottomSheet。当前项用色块和“当前曲目”语义标记，用户可直接点选。
- Material Rounded 图标，装饰性图标不重复朗读，操作图标提供中文 contentDescription。
- 不循环播放装饰动画；使用系统 Material 状态反馈。唱片环静止，不制造假的播放状态。

## Do's and Don'ts

- 保持正在播放、已下载、选中待下载三种状态相互独立。
- 所有曲目都来自解析或本地数据库；产品内不插入测试 fixture。
- 不因切换 tab、打开队列或收起播放器而中断声音。
- 不将账号头像、封面加载成功视作登录或音频解析成功。
- 不添加没有数据来源的歌词、音质标签或推荐列表。
- 不用只依赖拖动的进度交互：滑块支持平台可访问性调整，同时提供前后 10 秒按钮。
