# 开发与构建

[返回项目首页](../README.md) · [文档导航](README.md)

## 工程入口

从根目录的 [settings.gradle.kts](../settings.gradle.kts) 打开工程。应用源码在 `app/src/`，共用诊断工具在 `probe/`；`app/app/` 是历史恢复目录，不参与当前根工程构建。

源码恢复、备份来源以及未读取到交接 ZIP 的边界保留在 [本机整合记录](HANDOFF-VERIFICATION.md#来源与工程入口) 和 [历史恢复记录](RESTORATION.md)。历史记录中的版本、环境和命令不替代本文的当前开发入口。

## 构建与安装

最低 Android 10（API 29），compile/target SDK 35。已验证工具链为 JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20。

在项目根目录执行以下命令。工具链安装脚本支持 macOS／Linux，依赖 Git、Python 3、curl、tar 和 unzip；Android SDK 许可证需由操作者检查接受。Windows 请手动配置相同版本的工具链，或使用 Android Studio 并将 Gradle 分发配置为已验证的 8.11.1。

当前 [Gradle Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) 指向 9.6.0，与 bootstrap 脚本、CI 和已验证版本不同。以下命令使用项目本地的 Gradle 8.11.1；暂不推荐用 `./gradlew` 替代。

```bash
# 工具链尚未安装时运行；许可证由操作者检查接受
bash scripts/bootstrap-android.sh
source .tooling/env.sh
bash scripts/check.sh
# 将 YOUR_DEVICE_SERIAL 替换为 adb devices 中已连接并授权的设备序列号
# 仅同签名安装可以直接覆盖升级
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。`check.sh` 执行结构检查、离线脚本测试、Android JVM 测试、lint、应用/设备测试 APK 构建、PC 共用网络测试和工具打包。

### 正式签名包

正式构建读取根目录的 `signing.properties`，格式见 [signing.properties.example](../signing.properties.example)。已有发布环境使用 `.tooling/signing/tingyu-release.keystore`；密钥和密码配置均被 Git 忽略。请将这两个文件一起保存到安全备份中，后续升级沿用同一密钥。克隆仓库不会获得签名密钥；没有签名配置时 Gradle 只能生成未签名包。

```bash
source .tooling/env.sh
# 必须先恢复本机 signing.properties 及其引用的正式密钥
test -f signing.properties &&
gradle --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

APK：`app/build/outputs/apk/release/app-release.apk`；AAB：`app/build/outputs/bundle/release/app-release.aab`。APK 可直接安装，AAB 用于商店提交。正式签名与 debug 签名不同，不能直接覆盖已有 debug 安装；不要为安装正式包而贸然卸载含有个人数据的调试版。0.1.1 历史构建记录见 [正式包记录](RELEASE.md)，0.1.2 验证见 [本地音乐记录](LOCAL-MUSIC-VERIFICATION.md)，0.1.3 见 [歌曲信息与歌词验证记录](SONG-DETAILS-VERIFICATION.md)。

GitHub 的 **Android release build** 工作流可完成正式版编译，下载产物后在本机用原证书签名，再发布到 GitHub Release，私钥无需离开本机。另保留可选的全云端 **Android release** 流程，只有明确授权配置加密 Secrets 后使用；标签自动签名还需显式启用仓库变量。两条路径都必须验证正式签名后才发布安装包。详见 [GitHub 构建发布](GITHUB-RELEASE.md)。

### 设备自动测试

使用可丢弃的专用模拟器，并在测试前保存模拟器快照或确认应用数据已有备份。先人工创建、切换到本次测试用的非零临时 Android 用户，再填写真实设备序列号和用户 ID；以下占位值未替换时脚本会拒绝执行。

```bash
source .tooling/env.sh
bash scripts/check.sh
export TINGYU_TEST_DEVICE='<测试设备序列号>'
export TINGYU_TEST_USER='<已经切换的非零临时用户ID>'
bash scripts/check-device.sh
# 也可在上述变量已设置后，重新构建并运行：
# bash scripts/check.sh --device
```

`check-device.sh` 在安装前、测试前核对当前用户，禁止 user 0；仅覆盖安装现有 debug 应用／测试 APK，并直接运行指定用户的 instrumentation。可用 `TINGYU_TEST_CLASSES` 筛选测试类，`TINGYU_TEST_SCREENSHOTS=true` 保存测试截图。脚本不执行卸载、清应用数据或删除用户；设备测试会创建和清理自己的音频 fixture。

**指定非零用户不等于完全隔离。** Android 应用代码由多个用户共享，覆盖安装仍可能更新其他用户使用的应用版本，测试代码也可能修改其可访问的数据。不要在包含唯一副本或个人使用状态的设备上直接跑自动测试，也不要改回 Gradle connected 设备测试入口。0.1.4 验证期间曾发生 Gradle UTP 在测试结束后跨用户卸载应用、清除模拟器原用户私有数据的事故；共享音频已找回，未备份的队列与设置无法恢复，详见 [事故与修复记录](LYRICS-PLAYER-VERIFICATION.md#设备测试事故与入口修复)。

## 发布与验收

云端构建、正式证书及发布流程见 [GitHub 发布指南](GITHUB-RELEASE.md)。功能验收与尚待真机验证的项目见 [设备验收清单](ACCEPTANCE.md) 和 [0.1.4 验证记录](LYRICS-PLAYER-VERIFICATION.md)。测试数量可能重叠，不应直接相加。
