# 微乐

<img src="docs/assets/weile-icon.svg" alt="微乐图标" width="80" height="80">

面向 Android 的音乐播放器，原名听屿。

[![GitHub Release](https://img.shields.io/github/v/release/JackkLau/mobile-music-dl)](https://github.com/JackkLau/mobile-music-dl/releases/latest)
[![Build](https://github.com/JackkLau/mobile-music-dl/actions/workflows/android.yml/badge.svg?branch=master&event=push)](https://github.com/JackkLau/mobile-music-dl/actions/workflows/android.yml)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](docs/DEVELOPMENT.md)

[下载](https://github.com/JackkLau/mobile-music-dl/releases/latest) · [功能介绍](docs/FEATURES.md) · [文档](docs/README.md) · [问题反馈](https://github.com/JackkLau/mobile-music-dl/issues)

## 下载与安装

从 [GitHub Releases](https://github.com/JackkLau/mobile-music-dl/releases/latest) 下载最新版本的 `.apk` 文件，支持 **Android 10 及以上**。版本页面同时提供更新说明和 SHA-256 校验文件；`.aab` 用于商店提交，不能直接安装。

升级已有正式版时请覆盖安装，不要卸载。正式版与调试版使用不同签名，不能直接互相覆盖。签名与校验方式见 [发布文档](docs/GITHUB-RELEASE.md)。

## 快速开始

在 macOS／Linux 上从源码构建：

```bash
git clone https://github.com/JackkLau/mobile-music-dl.git
cd mobile-music-dl
bash scripts/bootstrap-android.sh
source .tooling/env.sh
gradle :app:assembleDebug
```

安装工具链时需检查并接受 Android SDK 许可证。生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

环境要求、Windows 配置、测试和签名构建见 [开发指南](docs/DEVELOPMENT.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [功能与使用](docs/FEATURES.md) | 支持范围与操作说明 |
| [开发与构建](docs/DEVELOPMENT.md) | 本地开发、构建安装与测试 |
| [网络调试](docs/NETWORK-DEBUG.md) | 下载排查、日志导出与诊断工具 |
| [GitHub 发布](docs/GITHUB-RELEASE.md) | 正式签名、自动构建与发布 |
| [文档索引](docs/README.md) | 设计规范、版本验收与历史记录 |

## 反馈与贡献

使用前可先查看 [已有 Issues](https://github.com/JackkLau/mobile-music-dl/issues)。报告问题时，请提供应用版本、Android 版本、复现步骤及预期结果；网络问题可附 [脱敏日志](docs/NETWORK-DEBUG.md#日志与-403-的证据边界)，不要提交 Cookie 或完整签名音频地址。

欢迎提交 [问题与建议](https://github.com/JackkLau/mobile-music-dl/issues/new)，或通过 [Pull Request](https://github.com/JackkLau/mobile-music-dl/pulls) 改进代码和文档。开发检查方式见 [开发指南](docs/DEVELOPMENT.md)。

## 许可证

当前仓库尚未提供 `LICENSE` 文件。
