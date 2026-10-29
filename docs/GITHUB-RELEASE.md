# GitHub 正式签名构建与发布

仓库：`JackkLau/mobile-music-dl`。首次计划发布 `v0.1.2`，对应 `versionName=0.1.2`、`versionCode=3`。工作流读取 `app/build.gradle.kts` 的真实版本，标签必须与其一致。

## 无需上传私钥的发布路径

`Android release build`（`.github/workflows/release-build.yml`）是默认推荐入口：在 GitHub Actions 手动选择 `master`，执行 release 单元测试、lint、APK/AAB 编译及 PC 共用代码检查，不读取任何签名 Secrets。输出 `tingyu-版本-cloud-unsigned` artifact，其中 `cloud-build.json` 记录提交、工作流地址和每个原始文件的 SHA-256；这些文件尚未签名，不能直接作为正式安装包发布。

下载该运行的 artifact，核对提交及哈希后，在本机用已有正式证书执行 APK 的 `zipalign` / `apksigner` 和 AAB 的 `jarsigner`，再用 `scripts/verify-release.py --apk ... --aab ...` 校验。对照签名前后 ZIP 载荷，确保签名过程中没有替换云端编译内容。最终 APK/AAB、SHA256SUMS、release.json 及 cloud-build.json 上传到该构建提交对应的 GitHub Release。私钥和密码始终留在本机。

这种路径的准确表述是“GitHub 编译，本机签名，GitHub 发布”；它不是全程云端签名。此前上传私钥的自动审批被拒绝，本次改用这条路径，不再以私钥外传授权作为发布前提。

## 可选：云端签名触发与产物

`.github/workflows/android.yml` 保留原有 push、PR、手动验证；不读取发布签名 Secrets。

`.github/workflows/release.yml` 提供两个入口：

- **Actions → Android release → Run workflow**：只能选择 `master` 或匹配当前版本的 `v*` 标签，构建并上传 Actions artifact，不创建公开 Release。
- **推送 `v0.1.2` 等版本标签**：仅当仓库变量 `TINGYU_CLOUD_SIGNING=true` 时，完成同样的检查和构建，然后创建该标签的 GitHub Release。默认不启用，避免本机签名发布时重复触发。标签提交必须已在 `origin/master` 历史中；错误版本或未合并分支会在读取 Secrets 前失败。

工作流仅在 `JackkLau/mobile-music-dl` 仓库执行，PR 和其他分支不能触发带签名的构建。构建任务只有仓库读取权限；单独的发布任务在成功后获得 `contents: write`。同一 ref 的发布串行执行，不会中断正在签名的任务。

产物命名为 `tingyu-0.1.2-release.apk`、`tingyu-0.1.2-release.aab`、`SHA256SUMS`、`release.json`。Actions artifact 保存 30 天，测试和 lint 报告保存 14 天；tag 触发的四个发布文件还会成为 GitHub Release 附件。APK 可以安装，AAB 用于商店提交；本流程不上传任何应用商店。

已有同名 Release 时，`gh release create` 会失败，不自动覆盖已发布的文件。修复发布问题后，应先检查已有 Release 的实际状态，再决定后续操作。

## 配置现有证书的 Secrets

必须先明确授权将现有私钥和凭据存入 GitHub 仓库的 Actions Secrets，再进行配置；仅写入源码和工作流不等于已配置或授权上传私钥。本次工作流实现没有生成新密钥。

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 添加：

| Secret | 内容来源 |
|---|---|
| `TINGYU_KEYSTORE_BASE64` | 已有 `.tooling/signing/tingyu-release.keystore` 文件的 Base64 内容 |
| `TINGYU_STORE_PASSWORD` | 本机 `signing.properties` 中的 `storePassword` 原值 |
| `TINGYU_KEY_ALIAS` | 同文件中的 `keyAlias` 原值 |
| `TINGYU_KEY_PASSWORD` | 同文件中的 `keyPassword` 原值 |

不得使用 debug keystore、替换证书或另行生成密码。后续升级需要沿用 0.1.1 正式证书，其公开 SHA-256 指纹固定为：

```text
ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911
```

Secret 缺失时，工作流会列出缺少的 **名称** 并失败，不会降级为 debug 签名或发布未签名 APK。`prepare-release-signing.py` 校验证书指纹后，在运行器上临时创建权限为 0600 的 `signing.properties` 与 `.tooling/signing/tingyu-release.keystore`；密码按 Java Properties 格式转义，不能注入额外配置。已有文件不覆盖，失败时清理本步骤创建的文件。

Secrets 只通过步骤环境变量传入，不作为命令行密码参数，不打印值。签名步骤后的 `always()` 清理会删除临时材料；该任务禁用 Gradle 缓存，不上传 `.tooling/`、凭据或工作区压缩包。不要开启 shell 调试输出或把这些文件作为 artifact 上传。GitHub 对 Secrets 的管理和 Base64 二进制存储方式见 [官方说明](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

## 构建与验证边界

运行器为 Ubuntu 24.04，使用 JDK 17、Gradle 8.11.1、Android SDK 35 / Build Tools 35.0.0。发布步骤执行：

```bash
python3 scripts/verify-source.py
python3 -m unittest discover -s scripts/tests -v
gradle --no-daemon --no-configuration-cache \
  :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease \
  :probe:test :probe:installDist
python3 scripts/verify-release.py
```

最终校验使用实际 APK/AAB 文件：

- APK：`apksigner verify` 成功，唯一签名证书匹配原证书；包名、版本号、versionCode 正确；不可调试、不带 `ProbeActivity`；ZIP 对齐通过。
- AAB：从真实 protobuf manifest 读取包名和版本，拒绝调试标记或诊断入口；JDK `JarFile` 完整读取全部载荷，检查每个载荷的签名、证书指纹与有效期，拒绝未签名载荷、重复条目和不同证书。
- 校验通过后才复制版本化产物并生成 SHA-256 清单。发布任务下载该 artifact，再核验 SHA256SUMS 后发布，不在发布阶段重新构建。

`release.json` 记录版本、提交、证书摘要及文件哈希。下载后可以运行 `shasum -a 256 -c SHA256SUMS`（macOS）或 `sha256sum -c SHA256SUMS`（Linux）核对字节完整性。APK 签名校验依据 [Android apksigner](https://developer.android.com/tools/apksigner)；AAB 的逐项校验依据 [JDK JarFile](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/jar/JarFile.html)，manifest 结构依据 [AOSP Resources.proto](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/tools/aapt2/Resources.proto)。Release 发布使用 [GitHub CLI 的 `--verify-tag`](https://cli.github.com/manual/gh_release_create)。

这些检查证明构建、签名及静态发布边界，不证明完整网络下载、M4A 保存、手机播放或商店验收。正式签名不能覆盖 debug 签名安装；不要为试装删除含个人数据的应用。

## 当前本地验证记录

2026-10-29：13 项发布工具单元测试通过（包含于本轮共 29 项脚本测试）；发布 YAML 可解析，8 个 shell 步骤语法检查通过。0.1.2 正式 APK/AAB 已在本机重新构建并通过同证书、真实版本、AAB 全部载荷签名和 APK 对齐校验。此项是本地结果，不是云端构建结果。

上一轮 GitHub `Android verification` 因 setup-android 默认安装已经移除的 `tools` 包失败。本次两个工作流均显式指定 `packages: platform-tools`，后续单独安装 SDK 35 和 Build Tools 35.0.0。完整本地功能验收见 [0.1.2 验证记录](LOCAL-MUSIC-VERIFICATION.md)。

远端 Secrets 配置、Actions 执行结果和实际 Release 地址应在成功后单独记录。仅提交工作流或看到 Actions 开始运行，不算正式包构建完成。
