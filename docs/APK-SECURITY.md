# 安装器报毒时如何核实

手机显示高风险时，请取消这次安装，保留下载到手机的原始 APK 和风险详情截图。微乐的官方 Android 包名是 `app.tingyu`。同一版本的 APK 也可能来自不同渠道，文件摘要能识别实际被检测的样本。

## 核对手机上的文件

将报毒的 APK 原样复制到电脑。macOS 可用以下命令，Linux 可用 `sha256sum`，Windows PowerShell 可用 `Get-FileHash -Algorithm SHA256`。

```sh
shasum -a 256 weile-0.1.8-release.apk
apksigner verify --print-certs weile-0.1.8-release.apk
```

在对应版本发布页的 `SHA256SUMS` 中查找同名文件。第二条命令来自 Android SDK Build Tools，输出的签名证书 SHA-256 应为 `ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911`。文件或签名不一致时，保留样本和来源交给厂商分析。摘要一致时也应保留告警，等待检测厂商复核。

| 官方样本 | APK 文件 SHA-256 |
|---|---|
| [微乐 0.1.8](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.8) | `c002eb82f8f58c6f4c237d94851b6a259270293788dfcaeba60b5ff06e5b216b` |
| [微乐 0.1.11](https://github.com/JackkLau/mobile-music-dl/releases/tag/v0.1.11) | `565959c3eb95ea970f32fdecb0907405206814b2c06fc6116776008b1c98111f` |

2026 年 11 月 27 日对表中两个原包复核，ClamAV 1.5.5、病毒库 28191 均未检出。手机样本与这两个官方包的对应关系，以及手机引擎的最终判定，仍待核实。[两份 APK 的扫描记录](https://github.com/JackkLau/mobile-music-dl/actions/runs/45915874715)

## 申请检测复核

在手机安全中心的检测详情中查看引擎名称。若没有显示，请从手机厂商的官方客服或开发者平台发起工单，要求确认检测引擎并转交样本。以下信息可直接填写。

| 字段 | 本次报告 |
|---|---|
| 应用、包名、版本 | 微乐，`app.tingyu`，0.1.7 升级至 0.1.8 |
| 安装器显示的来源与检出名 | Via，`a.gray.BulimiaTGen.f` |
| 报告设备 | Xiaomi 15 Pro，用户提供的系统版本为澎湃 OS 3.0.5 |
| 需补充 | 触发告警的原始 APK 或其 SHA-256、原下载地址、检测引擎和病毒库版本；提交时附上风险详情截图 |
| 复核请求 | 请按所附 SHA-256 复核原始 APK，说明命中的文件、依赖或行为。如确认误报，请修正判定并说明可复测的病毒库版本；如确认有风险，请提供整改依据。 |

如厂商需要样本，使用其官方提交入口提供 APK 即可。签名私钥、密码和音乐平台登录数据应由持有人保管。小米提供应用审核与安全厂商复核说明，腾讯也提供手机管家报毒申诉指引，可按设备确认的引擎选择渠道。[小米应用审核常见问题](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1252) · [腾讯移动应用安全常见问题](https://main.qcloudimg.com/raw/document/product/pdf/283_10719_cn.pdf)

针对本次小米设备，可从小米开放平台的「问题工单反馈平台（非游戏）」提交上表，或发给应用问题反馈邮箱 `developer@xiaomi.com`，请小米确认具体检测引擎与样本判定。[小米开发者联系方式](https://dev.mi.com/landing/bussiness-contact/index.html)

## 开发者身份提示

“开发者未验证”应通过手机所列平台完成主体认证并关联微乐的包名和现有签名。例如，小米对侧载应用提供 ADV 验证，平台明确区分开发者身份与病毒扫描。身份申请需要开发者本人或主体管理员完成。[小米开发者身份验证规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2366)

## 发布包扫描

0.1.12 支持下载和另存。0.1.13 恢复应用内安装。用户点击“安装”并完成系统要求的来源授权后，微乐重新核对 APK 摘要、正式证书、包名和版本，再向系统安装器临时授予这一份 APK 的读取权限。安装仍需由用户在系统界面确认。0.1.8 与 0.1.11 也声明了 `REQUEST_INSTALL_PACKAGES`，目前没有证据表明这项权限导致了 0.1.8 的告警。

从 0.1.12 起，GitHub 在独立的只读任务里用 ClamAV 扫描已签名 APK，再执行发布。发布附件中的 `apk-scan.txt` 包含 APK 的 SHA-256、引擎和病毒库版本、扫描时间及结论。检出、扫描失败或病毒库超过七天都会中止发布。该报告只说明这一份文件在当次 ClamAV 检测中的结果。手机厂商的原始告警需要用对应引擎和同一份 APK 复测。[GitHub 扫描工作流](https://github.com/JackkLau/mobile-music-dl/actions/workflows/apk-scan.yml)
