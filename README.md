# 懒人短信转发（SmsRelay）

面向 **小米澎湃OS 4 / HyperOS 4（Android 17）** 重新设计的短信转发方案。
接收短信 → 按规则过滤 → 转发到另一台手机 / 机器人 / 自建服务。

> 适配要点、保活清单、ADB 命令、排查矩阵全部在 [`docs/HYPEROS4-适配手册.md`](docs/HYPEROS4-适配手册.md)，
> 编译前请先读一遍，尤其是 **为什么 targetSdk 锁在 36**。

## 一句话架构

```
短信广播（主力） ┐
通知监听（补偿） ├→ Engine → outbox 表（先落库） → 前台服务 / Worker 消费 → 各转发通道
收件箱扫描（兜底）┘
```

三条取数链路互为冗余 + 所有内容先落本地队列，是为了对抗国产 ROM 的进程管理：
**进程可以被杀，消息不能丢**。

## 关键决策

| 决策 | 取值 | 原因 |
|------|------|------|
| `compileSdk` | 37 | 拿到 Android 17 的 API 与 lint 检查 |
| `targetSdk` | **36** | Android 17 起非默认短信应用读 OTP 短信延迟 3 小时，锁 36 规避 |
| `minSdk` | 26 | 覆盖在役的小米/红米机型 |
| FGS 类型 | `remoteMessaging` | 语义贴合「设备间消息转发」，比 `specialUse` 好过审 |
| 存储 | SQLite + SharedPreferences | 广播接收路径需要同步读写，DataStore 异步首帧不适合 |

## 已实现

- **取数**：静态 + 动态双注册短信广播、通知监听补偿、收件箱周期兜底扫描
- **规则**：号码包含、关键词、正则、卡槽、仅验证码、绑定指定通道
- **通道**：通用 Webhook（带 HMAC 签名）、Bark、Server酱³、Telegram、企业微信、钉钉、飞书、短信回发（可选卡）
- **可靠性**：先落库后发送、6 次指数退避重试、30 秒去重、Direct Boot 暂存补发
- **保活**：前台服务 + 开机/更新拉起 + WorkManager 兜底 + 9 项澎湃OS 自检与跳转引导

## 编译出 APK

本工程已配置为「克隆即编」：内置 Gradle Wrapper（Gradle 8.11.1 + `gradle-wrapper.jar`）、
签名回退逻辑（未配置签名时自动使用 Android debug 密钥，保证一定能出可安装包）。

### 方式一：GitHub Actions 云端出包（零本地环境）
> 云端出包的 workflow 文件以示例形式保存在 `docs/build-apk.yml.example`，**默认未启用**。
> 原因：GitHub 禁止没有 `workflow` scope 的 PAT 推送 workflow 文件。启用步骤：
> 1. 将 `docs/build-apk.yml.example` 复制为 `.github/workflows/build-apk.yml`
> 2. 用**带有 `workflow` scope** 的 GitHub PAT 重新推送到仓库
> 3. 进入仓库 **Actions → Build SmsRelay APK → Run workflow**
> 4. 运行结束后在 **Artifacts** 里下载 `SmsRelay-release-apk`，即为已签名的 `app-release.apk`
> 5. 手机开启「未知来源安装」后直接安装

> CI 流程会在云端自动生成临时 keystore 签名，仅供自用；若要正式分发，请改用你自己的签名密钥（见方式二）。

### 方式二：本机 Android Studio（最直观）
1. 安装支持 compileSdk 37 的 Android Studio（最新稳定版）
2. 打开 `SmsRelay` 目录，等待 Gradle 同步
3. 选 `Build → Generate Signed Bundle / APK`，按向导生成自己的签名密钥并构建
4. 产物在 `app/build/outputs/apk/release/app-release.apk`

### 方式三：命令行一键脚本（已装 JDK 17 + Android SDK 者）
- Windows：双击 `build-apk.bat`（或命令行 `build-apk.bat release`）
- macOS / Linux：`./build-apk.sh`（或 `./build-apk.sh release`）
- 默认产出 debug apk，可直接 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 到本机

### 前置环境要求
- JDK 17
- Android SDK 已安装 `platforms;android-37`（compileSdk = 37 必需）与对应 `build-tools`
- 复制 `.local.properties.example` 为 `local.properties`，填好 `sdk.dir`；release 签名可选，不填则回退 debug 密钥
- 真机调试（模拟器无短信功能，请用真机 + 另一台手机发短信验证）

## 使用

1. 打开 app → 完成《使用前须知》同意（未同意不转发任何内容）
2. 「保活」页：把 5 项必需项全部处理完
3. 「通道」页：新增通道并点「测试」
4. 「规则」页：按需收紧匹配条件
5. 「保活」页点「注入测试短信」，到「日志」页确认链路打通

## 合规

短信属于个人通信内容。转发他人短信需事先取得同意。
本项目首次启动强制知情同意，且短信内容只在本机短暂留存，不经过任何本项目方的服务器。
