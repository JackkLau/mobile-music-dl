# 微乐 0.1.7 · 安装包更新与扫码登录

在「我的 → 应用更新」检查最新正式版，下载后选「安装」或「安装包另存为」。Android 会显示安装确认界面。已有 0.1.6 的用户可从 [GitHub Releases](https://github.com/JackkLau/mobile-music-dl/releases/latest) 下载本版，直接覆盖安装。

在「我的 → 登录哔哩哔哩」可用另一台设备的哔哩哔哩客户端扫码并确认。单设备可选「网页登录」。两种方式共用账号验证，验证成功并写入本机加密存储后显示账号名称。

| 项目 | 验证范围 |
|---|---|
| 版本信息与 APK | 用实际 GitHub Release 元数据选择正式 APK，校验历史公开安装包的 51,574,668 字节及 SHA-256。回归用例覆盖版本比较、异常跳转、附件不符和不完整下载。 |
| 扫码服务 | 共用客户端实测取得官方二维码，轮询返回等待扫码（86101）。回归用例覆盖已扫码、确认、过期、凭据域与路径、账号验证及错误脱敏。 |
| 界面 | GitHub 临时 Android 11 设备通过 7 项测试，覆盖真实登录页与官方 WebView 切换、二维码解码与过期刷新、成功反馈、我的更新入口，以及系统解析历史正式 APK 的签名、版本和 FileProvider 共享范围。 |
| 构建 | 本机 debug 和 release 各通过 254 项应用 JVM 测试，脚本 41 项、PC 共用 JVM 132 项通过。测试集有重叠。正式 APK/AAB 校验原发行证书、包名 app.tingyu、版本 0.1.7（code 8）与包完整性。 |

本轮未使用真实个人账号确认扫码，真机授权登录、安装权限弹窗及最终覆盖安装仍待设备验收。

[Android 设备测试](https://github.com/JackkLau/mobile-music-dl/actions/runs/45826094138) · [常规检查](https://github.com/JackkLau/mobile-music-dl/actions/runs/45826094231)

[GitHub 正式构建与发布](https://github.com/JackkLau/mobile-music-dl/actions/runs/45829636077)成功。用本版更新代码读取公开的 latest 元数据并校验下载的 APK，识别到 0.1.7，文件大小 51,689,968 字节。公开 APK、AAB 均通过 SHA-256、原证书、包名和版本复核。
