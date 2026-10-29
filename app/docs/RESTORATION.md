# 源码恢复与接续开发

项目目录名：`mobile-music-dl`。恢复日期：2026-10-28（Asia/Hong_Kong）。

## 源码来源

当前会话只拥有 `recovered/mobile-music-dl/tingyu-android-source.zip`，其中原始顶层目录名为 `tingyu-android`，归档时间截至当日约 14:46。没有访问到原电脑最后一次编辑的源码，也没有挂载 Mac 的 `/Users/tobin/Documents/projects/mobile-music-dl`。

本版本在保留原备份功能的基础上，依据用户提供的会话记录重建下列已明确改动；不能宣称与原电脑最后版本逐字相同。

## 恢复范围

- 默认桌面音频 UA，解析/下载独立请求配置；每次任务及一次取流刷新固定使用同一配置。
- 桌面、移动和分享短链统一视频/分 P 处理；AAC 元数据兼容及安全备用地址选择。
- 视频页 Referer、受信任 Origin、初始 Range 和严格续传；保持账号 Cookie 与 CDN 隔离。
- HTTP 错误分类、关联日志、同一音频地址两种 UA 的最多 1 KB 样本对照；可取消、不保存音频。
- 调试包连续点击版本号 7 次启用调试模式，统一“开发者工具”入口，关闭后隐藏并重置测试选项。
- 官方登录页自动验证及明确成功反馈，去重、取消与过期结果保护；保留手动重试。
- 真机 UA 结论、回归测试、现有构建脚本及本次验证记录。

## 0.1.1 AAC 修复

用户反馈旧版“未找到兼容的 AAC 音轨”后，进一步修复过严的 codec/MIME 筛选、元数据缺项和 MCDN HTTPS 4483 地址误拒绝；无音轨、格式不兼容与地址不可用分别提示。增加受控的音轨筛选日志及 21 项回归测试。具体兼容边界、证据与真机复测步骤见 `AAC-COMPATIBILITY.md`。当前版本号为 0.1.1，尚未生成 APK 或完成真机复测。

本次以应用内“同地址 UA 对照”为诊断入口；旧会话里的 PC/手机 `probe-bili-download.sh` 原始脚本不在备份中，未声称恢复该脚本。没有附带历史 APK、未获取的补丁或虚构 Git 提交。

## 在另一台电脑继续

将完整源码包解压后，保留目录名 `mobile-music-dl`。若目标已有同名目录，先备份并比较差异，避免直接覆盖本机未提交修改。

```bash
cd /Users/tobin/Documents/projects/mobile-music-dl
# 已有 Android Studio 工具链可直接打开项目。
# 或安装项目固定工具链；许可证由操作者检查接受。
bash scripts/bootstrap-android.sh
source .tooling/env.sh
bash scripts/check.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试入口：我的 → 连点版本号 7 次 → 开发者工具。默认功能无需启用调试模式。账号重新在应用内登录；源码包不包含 Cookie、签名密钥、SDK/Gradle 缓存或原设备应用数据。

验证的准确范围见 `verification.json` 和 `UA-VALIDATION.md`。本次没有连接远程 Git 仓库或同步到 Mac 目录；交付的是完整可继续开发的源码包。
