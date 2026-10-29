# 网络调试与故障排查

[返回项目首页](../README.md) · [文档导航](README.md)

本页面向需要排查解析、下载和网络问题的使用者。诊断功能仅在调试包中提供；构建方法见 [开发与构建](DEVELOPMENT.md)。

## 默认 UA、网络调试和隐藏入口

解析和音频下载均默认桌面 Chrome UA。debug 包提供“我的 → 网络调试”；首次打开保留桌面配置，日志默认关闭。原隐藏入口保留：在“我的”连续点版本号 **7 次**，相邻点击不超过 **2 秒**，显示“开发者工具”。两个入口使用同一页面和设置。

- 解析 UA：桌面／移动；音频 UA：跟随解析／桌面／移动。选择即时保存。
- 每个下载在开始时固定 UA 配置；进行中更改设置不影响它，地址刷新也使用相同配置。新任务使用新设置。
- “记录脱敏日志”控制 `TingYuDownload`。关闭调试模式会关闭日志并重置覆盖配置；release 忽略所有调试设置且没有诊断 Activity。
- 先解析视频，再点击“开始同地址 UA 对照”。只取流一次，用同一签名 URL 发送桌面和移动 UA，各读取最多 1024 字节，不保存音频；可“取消对照”，离页也取消。

## 日志与 403 的证据边界

```bash
# 将 YOUR_DEVICE_SERIAL 替换为已授权设备序列号
# 开启日志并重现后导出，按 request 关联同一次操作
adb -s YOUR_DEVICE_SERIAL logcat -d -v brief -s TingYuDownload:I TingYuProbe:I '*:S' > tingyu-network.log
```

日志保留阶段、HTTP/业务码、`request`、`url_id` 摘要、CDN 域名、UA、范围配置、续传偏移、刷新次数、重定向次数、IPv4/IPv6 和代理类型；连接/读取失败保留 DNS、TLS、超时等类别。不输出 Cookie、账号资料、原始响应、完整签名 URL 或原始异常消息。

`audio_select` 区分没有音轨、格式不兼容与地址不可用。正式下载会在首次写入前检查 MP4 文件头，拒绝 HTTP 成功但实际返回错误页的内容；非零续传按 ETag、范围和总长判断。

| 证据 | 只说明什么 |
|---|---|
| `http=206` | CDN 接受了范围请求 |
| `sample=valid_mp4_header` | 最多 1 KB 样本具有 MP4 文件头 |
| `event=transfer_complete` | 音频字节读取及长度检查结束 |
| `event=saved` | M4A 重封装、发布和本地记录提交成功 |
| `event=local_hit` | 复用了已有本地文件，没有证明新网络请求成功 |

此前已确认链接和音轨地址能解析，403 发生在独立音频 CDN 请求。多个节点在刷新地址及补 Referer／Origin／Range 后仍失败；改桌面 UA 后用户反馈成功，并出现 `206 ua=desktop`。但成功/失败日志中的 CDN 节点也变化，**尚不能证明唯一根因就是下载 UA**。完整下载、M4A 保存和离线播放要分别验收；项目没有视频下载，不能据此判断视频流。

## PC／手机共用诊断

```bash
bash scripts/probe-bili-download.sh pc
bash scripts/probe-bili-download.sh phone
# 可指定三类原始问题链接，分享文本请整体加引号
bash scripts/probe-bili-download.sh pc 'https://m.bilibili.com/video/BV1sVtw6JEeq?vd_source=1b2cad1e91ade28f394b35922f927023'
bash scripts/probe-bili-download.sh phone 'https://b23.tv/ZhRmMnf'
ANDROID_SERIAL=emulator-5554 bash scripts/probe-bili-download.sh phone
```

工具分别用桌面／移动解析 UA 取流，每一组用同一签名地址对照两种下载 UA。PC 和 Android 直接编译并运行同一套 Kotlin 网络源码，没有另写 curl 取流逻辑。PC 匿名访问；手机使用应用当前访问模式，因此账号条件不同的结果不能直接归因为设备差异。

手机脚本先检查设备与授权，然后构建、`install -r`；不卸载、不清数据、不清除历史 logcat。每次生成唯一 run 编号，仅提取本次 `TingYuProbe` 日志。默认设备 `7c1213a1`，通过 `ANDROID_SERIAL` 改选。调试入口限定 shell 权限且不包含在 release 中。

日志写入 `artifacts/probes/`。Ctrl+C 取消；`TINGYU_PROBE_TIMEOUT_SECONDS` 设置最长诊断时间（默认 240 秒，最大 1800 秒）。退出码：0 全部样本通过；1 检测到请求/内容失败；2 工具或输入问题；124 超时；130/143 取消。对照发现 403 返回 1 是真实诊断结果。鉴权失效或限流后停止后续无意义请求。

## 验收链接与剩余验证

- 桌面：`https://www.bilibili.com/video/BV1sVtw6JEeq/?vd_source=1b2cad1e91ade28f394b35922f927023`
- 移动：`https://m.bilibili.com/video/BV1sVtw6JEeq?vd_source=1b2cad1e91ade28f394b35922f927023`
- 短链：`https://b23.tv/ZhRmMnf`

自动测试能证明确定性规则与模拟响应下的请求行为；在线探测只证明该次网络/账号/节点结果。真机完整下载→M4A保存→飞行模式播放，以及登录反馈、后台恢复、TalkBack 和不同 Android 版本的矩阵仍需分别记录。详见 [历史逐项记录](HANDOFF-VERIFICATION.md) 和 [完整设备验收清单](ACCEPTANCE.md)。

短链的实际目标可能与桌面／移动示例不同。历史实测中，该短链跳转到了另一 BV；比较同一视频、同一分 P 的请求参数前，应先确认展开后的身份，见 [短链样本记录](HANDOFF-VERIFICATION.md#真实-pc-样本结果)。
