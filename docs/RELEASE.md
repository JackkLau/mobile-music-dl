# 听屿 0.1.1 正式签名构建

构建及验证日期：2026-10-29，macOS arm64 / JDK 17 / Gradle 8.11.1 / Android SDK 35。

## 产物

包名 `app.tingyu`，versionName `0.1.1`，versionCode `2`，最低 Android 10（API 29）。

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `artifacts/release/tingyu-0.1.1-release.apk` | 51,128,868 字节 | `9d501da2bfc159b8177f4887885523fdd79d4bf7de268d391ff749b8085edf50` |
| `artifacts/release/tingyu-0.1.1-release.aab` | 14,675,811 字节 | `00a718488af1bbbfff2f267f119c67fe309dee42deb8db4c3da705956be188d0` |

APK 可直接安装；AAB 用于商店提交，不能直接安装。产物和本地签名秘密不提交到 Git。原始构建输出在 `app/build/outputs/apk/release/` 和 `app/build/outputs/bundle/release/`。本次完成构建，不包含商店上传或 GitHub Release 发布。

## 签名与复现

此前工程没有正式签名配置，本次创建首次 release 专用 RSA 3072 密钥（PKCS12），证书名 `CN=TingYu Release`，有效期 10,000 天。证书 SHA-256：

```text
ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911
```

私钥位于 `.tooling/signing/tingyu-release.keystore`，凭据位于根目录 `signing.properties`；文件权限均为 0600，密钥目录为 0700，两者均已确认被 Git 忽略。请将两个文件一起备份到安全位置，后续版本沿用此密钥。不要提交、上传或在日志中输出它们。见 [Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)。

其他机器先恢复上述两个文件，或参照 `signing.properties.example` 配置已有密钥，然后执行：

```bash
source .tooling/env.sh
test -f signing.properties &&
gradle --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

没有 `signing.properties` 时，普通 debug 构建不受影响；release 构建不会自动借用 debug 密钥，可能得到未签名产物，应先恢复正式签名配置。正式版签名与 debug 版不同，不能覆盖 debug 安装；不要为试装而删除含个人数据的调试应用。本轮没有执行卸载或正式包安装。

## 新验证结果

- 上述 Gradle 命令退出 0；135 项 release JVM 测试通过，0 失败、0 跳过。它们与 debug 套件重叠，不作数量相加。
- `lintRelease`：0 错误、22 条既有警告；保留报告，没有无关依赖或业务变更。
- `apksigner verify --verbose --print-certs` 退出 0：APK Signature Scheme v2 校验通过，证书与本次正式密钥一致。
- `zipalign -c -P 16 4` 退出 0：APK ZIP 对齐通过。这不等同于所有设备或商店验收。
- 检查最终 APK 的 manifest 和 badging：版本/包名正确，应用不可调试，不含 `ProbeActivity`。AndroidX 自带的 ProfileInstallReceiver 使用 DUMP 权限，不是诊断 Activity。
- AAB `jarsigner -verify` 退出 0，显示 `jar verified`；存在自签名、无时间戳及流式 JAR 清单排序提示。另用 JDK `JarFile` 完整读取并校验 227 个载荷条目的签名、证书指纹和有效期，全部通过。未进行 Play Console 校验。
- 82 项源码结构检查及 16 项离线脚本测试本轮重新通过。
- Git 暂存内容检查通过：不含真实签名密码、私钥、工具链、构建包或 IDE 缓存。源码空白检查通过；原始日志尾部空白和 Windows Wrapper 的 CRLF 保留。

构建和签名日志见 `docs/verification-logs/release-20261029/`；本机额外信息见 `artifacts/release/release.json`、`SHA256SUMS` 和原始日志。应用源码沿用已整合的 0.1.1，改动仅为发布签名配置、忽略规则和文档。

完整音频下载、M4A 保存、真实手机离线播放等验收边界仍见 [交接验收记录](HANDOFF-VERIFICATION.md)。本次构建成功不替代这些设备验收，也不改变历史 403 结论。
