# 微乐 0.1.8 · 品牌与升级验证

应用在桌面、系统设置和发布页继续使用「微乐」。新版标识由松石绿底、米白唱片和唱针组成。[矢量预览](assets/weile-icon.svg) · [正式版下载](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.8)

| 检查范围 | 实现与验证方式 |
|---|---|
| 桌面与启动画面 | 标准、圆形、自适应及 Android 13 单色图标共用一个矢量前景。已检查正式 APK 中的前景和单色资源引用，模拟器测试检查唱片和唱针是否落在安全裁切区域内。 |
| 应用内和通知 | 解析首页、“我的”、默认封面、下载与播放通知使用同一图形。通知以透明剪影显示，已选歌曲封面优先显示。资源测试检查唱片中心透明、唱针与盘体可见。 |
| README 与发布页 | README 图标从 Android 矢量与颜色导出，源码检查会检测文档图标是否过期；发布标题读取应用名称。 |
| 旧版升级 | 正式包名为 `app.tingyu`，版本 0.1.8（code 9），使用原有正式证书。新版使用 `weile-0.1.8-release.apk`；0.1.7 更新器使用同一文件的 `tingyu-0.1.8-release.apk` 副本。原音乐目录为 `Music/听屿/`。 |

本机运行源码检查、41 项脚本测试、Android debug 和 release 单测（各 255 项）及 PC 工具单测（132 项）。PC 与应用测试存在重叠。两个构建变体均通过 Android lint，正式 APK 和 AAB 的签名、包名及版本通过校验。

GitHub Android 11 模拟器通过 9 项设备测试，覆盖品牌资源、账号界面与安装包更新流程。

[GitHub Android 设备检查](https://github.com/JackkLau/mobile-music-dl/actions/runs/45836171729) · [GitHub 常规检查](https://github.com/JackkLau/mobile-music-dl/actions/runs/45836171634)

各厂商桌面图标遮罩、Android 13 主题图标的实际配色、真机通知栏显示和覆盖安装后播放旧音乐仍待真机验收。
