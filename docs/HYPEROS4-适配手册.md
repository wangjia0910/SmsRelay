# 懒人短信转发 · 澎湃OS 4（HyperOS 4）适配手册

> 适用对象：本项目 `SmsRelay` 的开发者 / 自行编译打包的使用者
> 系统基线：Xiaomi HyperOS 4（公开信息为基于 Android 17 / API 37，最终以小米官方发布说明为准）
> 工程配置：`compileSdk 37` / `targetSdk 36` / `minSdk 26`

---

## 零、三条结论先说在前面

| # | 结论 | 不这么做的后果 |
|---|------|----------------|
| 1 | **`targetSdk` 必须锁在 36，不要升到 37** | Android 17 起，非默认短信应用读取含验证码的短信会被**延迟 3 小时**，验证码转发场景直接失效 |
| 2 | **通知监听只能当备份，不能当主力** | Android 15+ 未授信的监听器读到的 OTP 通知会被替换成 "sensitive notification content hidden" |
| 3 | **`com.miui.*` 跳转一律视为「可能不存在」** | 澎湃OS 4 定位是「零 MIUI 残留」版本，MIUI 时代的设置页 Activity 大概率已被移除，硬跳必崩或无响应 |

---

## 一、Android 侧的限制全景（决定架构的地基）

### 1.1 短信获取：三条链路的可用性

| 链路 | 机制 | 各版本可用性 | 本项目定位 |
|------|------|-------------|-----------|
| **A. 短信广播** | `SMS_RECEIVED_ACTION` 静态注册 + 前台服务内动态注册 | Android 8+ 属于**隐式广播豁免名单**，静态注册长期有效；**但**被强制停止后不再触发，直到用户手动打开一次 app | **主力** |
| **B. 通知监听** | `NotificationListenerService` | Android 15+ 对未授信监听器**屏蔽 OTP 内容**；需用户手动开「通知使用权」 | **补偿**（非验证码类有效） |
| **C. 收件箱兜底扫描** | `content://sms/inbox` 周期查询 | 需要 `READ_SMS`；Android 17 / target 37 起**含 OTP 的记录被过滤 3 小时** | **兜底**（锁 target 36 才有效） |

> 关于 A 的一个常见误解：网上有种说法是「Android 4.4 之后只有默认短信应用能收到短信广播」。
> 这是错的。区分如下：
> - `SMS_DELIVER_ACTION`：仅默认短信应用（用于写入收件箱）
> - `SMS_RECEIVED_ACTION`：所有被授予 `RECEIVE_SMS` 的应用都能收到（自 Android 4.4 起**不能拦截/中止**广播，但能读取）
>
> 本项目只接收、不拦截，也不声明 `android:priority`（Android 16 起跨进程广播优先级不再保证，声明了也没用，反而容易被判定为拦截行为）。

### 1.2 后台保活：能用的和不能用的

| 手段 | 结论 |
|------|------|
| 常驻前台服务（`foregroundServiceType="remoteMessaging"`） | ✅ 唯一相对体面的方案，配常驻通知 |
| `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 拉起 | ✅ **官方明示的「后台启动前台服务」豁免场景**，国产 ROM 上唯一站得住脚的自动拉起入口 |
| 用户关闭本应用电池优化后从后台启动 FGS | ✅ 官方豁免条件之一，本项目把它列为必做自检项 |
| `WorkManager` 周期任务 | ✅ 兜底唤醒 + 队列重放，但不能替代 FGS |
| 监听网络变化 / 解锁 / 电量变化去拉活 | ❌ Android 7+ 静态注册基本收不到，且会被澎湃OS 的「应用行为记录」打点 |
| 双进程守护 / Native fork 保活 | ❌ Android 9+ 起无效，且属于恶意行为特征 |
| 无障碍服务抓取 | ❌ Play 与应用商店红线，且澎湃OS 会频繁自动关闭 |
| 依赖 `abortBroadcast()` 拦截短信 | ❌ Android 4.4 起对非默认应用无效 |

### 1.3 其他版本相关的坑

- **Android 8.0+**：后台启动服务受限，必须用 `startForegroundService()`，且 5 秒内要调 `startForeground()`。
- **Android 12+**：后台启动 FGS 受限，本项目所有 `startForegroundService` 调用都包了 `try/catch`（`ForegroundServiceStartNotAllowedException`）。
- **Android 13+**：`POST_NOTIFICATIONS` 是运行时权限。
- **Android 14+**：FGS 必须声明具体类型；本项目用 `remoteMessaging`（语义 = 设备间消息转发），比 `specialUse` 更容易通过商店审核。
- **Android 15+**：用户强制停止 app 后，所有 `PendingIntent` 会被取消，小组件被置灰 —— 这也是「被一键清理后什么都不灵」的官方解释。
- **Android 16+**：广播 `android:priority` 跨进程不再保证。
- **Android 17+**：锁自由 MessageQueue、应用内存限制器、默认启用证书透明度、局域网访问需新权限 —— 本项目只用到网络，影响有限。

---

## 二、澎湃OS 4 保活 9 项清单

App 内「保活」页已内置自检与跳转，跳转失败时会直接给出手动路径。下面是可以照着操作的完整版。

| # | 项目 | 是否必需 | 手动路径 |
|---|------|---------|---------|
| 1 | 短信权限 | **必需** | 设置 → 应用设置 → 应用管理 → 懒人短信转发 → 权限管理 → 短信 → 始终允许 |
| 2 | 通知权限 | **必需** | 设置 → 应用设置 → 应用管理 → 本应用 → 通知管理 → 允许通知 |
| 3 | 自启动 | **必需** | 设置 → 应用设置 → 自启动管理 → 本应用 → 打开（含「关联启动」一并打开） |
| 4 | 省电策略 = 无限制 | **必需** | 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 → 无限制 |
| 5 | 忽略电池优化 | **必需** | 设置 → 省电与电池 → 应用省电优化 → 本应用 → 不优化 |
| 6 | 多任务锁定 | 强烈建议 | 多任务界面 → 长按本应用卡片 → 点击锁定图标 |
| 7 | 通知使用权 | 可选 | 设置 → 通知与控制中心 → 通知使用权 → 勾选本应用 |
| 8 | 后台弹出界面 / 后台联网 | 可选（视系统版本） | 设置 → 应用设置 → 授权管理 → 后台弹出界面 / 后台联网 |
| 9 | 设为默认短信应用 | 可选（面向 Android 17） | 设置 → 应用设置 → 默认应用设置 → 短信 → 本应用 |

### 2.1 关于跳转失效这件事

代码里对每一项都准备了**多个候选 Intent + 系统设置页兜底**，并且用 `resolveActivity()` 逐个试：

```kotlin
// util/Guards.kt
private fun autoStartIntents(ctx: Context): List<Intent> = listOf(
    comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    act("miui.intent.action.OP_AUTO_START", "com.miui.securitycenter"),
    act(Settings.ACTION_APPLICATION_SETTINGS),
    act(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
)
```

澎湃OS 4 清掉了 MIUI 兼容层，前两个大概率解析失败，代码会自动降级到第 3 个，UI 上则把按钮文案切成「打开应用信息 + 手动路径」。

> **给二次开发者的提醒**：不要从网上复制一份 `com.miui.*` 类名就当真理。
> 要么像本项目一样做多级兜底，要么干脆只给手动路径 —— 一个静默失败的跳转比一个明确的手工步骤更让人抓狂。

---

## 三、ADB 调试命令集

```bash
# ---------- 基础信息 ----------
adb shell getprop ro.miui.ui.version.name      # 澎湃OS 版本号
adb shell getprop ro.build.version.sdk         # Android API 级别（37 = Android 17）
adb shell getprop ro.build.version.incremental # 系统构建号

# ---------- 安装与权限 ----------
adb install -r app-debug.apk
adb shell pm grant com.lazy.smsrelay android.permission.READ_SMS
adb shell pm grant com.lazy.smsrelay android.permission.SEND_SMS
adb shell pm grant com.lazy.smsrelay android.permission.RECEIVE_SMS
adb shell pm grant com.lazy.smsrelay android.permission.POST_NOTIFICATIONS

# ---------- 后台相关 ----------
adb shell dumpsys deviceidle whitelist +com.lazy.smsrelay      # 加入 Doze 白名单（重启失效）
adb shell cmd appops set com.lazy.smsrelay RUN_IN_BACKGROUND allow
adb shell cmd appops set com.lazy.smsrelay RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket com.lazy.smsrelay active        # 提高到 ACTIVE 桶（重启失效）

# ---------- 诊断 ----------
# 最重要的一条：判断 app 是否处于「已停止」状态（被强制停止后静态广播永不触发）
adb shell dumpsys package com.lazy.smsrelay | grep -E "stopped|enabled="
# 看进程在不在
adb shell ps -A | grep smsrelay
# 看前台服务是否注册成功
adb shell dumpsys activity services com.lazy.smsrelay
# 实时日志
adb logcat -s SmsReceiver:* RelayService:* Engine:* InboxScanner:*

# ---------- 通知监听（调试用，生产环境不要依赖） ----------
adb shell cmd notification allow_listener com.lazy.smsrelay/.service.RelayNotificationListener
# Android 15+ 解除 OTP 通知脱敏（仅调试，需 root 或 adb 权限，重启后可能失效）
adb shell cmd appops set --user 0 com.lazy.smsrelay RECEIVE_SENSITIVE_NOTIFICATIONS allow
```

### 3.1 怎么测「真短信」

真机上构造一条真实短信很麻烦（`adb emu sms send` 只对模拟器有效）。本项目在「保活」页提供了 **注入测试短信** 按钮，它直接调用 `Engine.onSms()`，走的是与真实短信完全相同的规则匹配 / 模板渲染 / 入队 / 发送链路，可以完整验证规则与通道配置是否正确。

要验证「短信广播本身能不能收到」，只能用另一台手机发一条真实短信，然后看日志页的来源字段是不是 `sms`。

---

## 四、故障排查矩阵

| 现象 | 最可能的原因 | 处理 |
|------|-------------|------|
| 装好后一条都不转发 | 未完成知情同意弹窗 | 打开 app 同意《使用前须知》；日志会显示「尚未完成知情同意」 |
| 只有打开 app 时才转发 | ① 自启动没开 ② app 处于 stopped 状态 | 开自启动；`adb shell dumpsys package ... \| grep stopped` 确认；手动启动一次 app 解除 stopped |
| 白天正常，息屏一晚就断 | 省电策略不是「无限制」 | 按清单第 4 项设置；确认电池优化已忽略 |
| 上滑清理后失效 | 没锁后台 | 多任务界面长按卡片锁定 |
| 重启手机后完全不工作 | 自启动未开 / 未收到 `BOOT_COMPLETED` | 开自启动；部分 ROM 需要锁屏密码解锁一次才算真正开机完成 |
| 重启后第一条验证码丢失 | Direct Boot 期间凭据加密存储不可用 | 本项目用 `PendingStore`（device-protected storage）暂存，解锁后自动补发；若仍丢，看日志是否有「设备未解锁」 |
| 验证码被转成 "sensitive notification content hidden" | Android 15+ 通知脱敏 | 正常现象，说明你依赖了通知通道；改用短信广播为主通道，或按 3.x 的 adb 命令临时解除 |
| 长短信只转发了半截 | PDU 未合并 | 本项目已按发送方合并多段 PDU，若复现请提 issue 并附上日志 |
| 转发内容重复 | 广播 + 动静态双注册 + 兜底扫描三路同时命中 | 去重窗口默认 30 秒（号码 + 正文 + 30 秒时间片）；需要更激进可在 `Prefs.dedupSeconds` 调整 |
| 双卡场景分不清是哪张卡 | ROM 的 extra key 不统一 | 已在 `SmsReceiver` 里做多 key 兜底（`android.telephony.extra.SLOT_INDEX` / `slot` / `phone` / `simId` 等） |
| 一直提示「待发 N 条」 | 通道配置错误或目标网络不通 | 到「通道」页点「测试」看具体错误；`adb logcat` 看 HTTP 状态码 |

---

## 五、数据与安全设计

- **先落库后发送**：所有短信在广播到达的第一时间写入 `outbox` 表，网络动作由前台服务/Worker 消费队列。进程被杀不会丢消息，重启后自动重放。
- **重试与退避**：最多 6 次，退避 30s → 60s → 5min → 15min → 30min，带随机抖动（避免整机组同时重打服务端）。
- **Direct Boot 暂存**：未解锁期间的事件写入 device-protected storage。
- **签名**：Webhook 通道支持 `X-Relay-Signature: sha256=<HMAC-SHA256(secret, ts.body)>`，服务端务必校验时间戳防重放。
- **本地留存**：日志默认保留 3 天，可一键清空；短信内容不上传任何本项目方的服务器。

### 合规提示（务必阅读）

短信属于个人通信内容，受《个人信息保护法》《民法典》关于隐私权的约束。使用与分发时请注意：

1. **转发他人短信需取得对方同意**。最典型的合规场景是「自己的备用机转发给自己」，或企业明确告知员工后用于业务号码。
2. **不要在未告知的情况下静默转发**。本项目在首次启动强制弹出知情同意，且同意前不转发任何内容 —— 二次分发时请保留这个行为。
3. **转发链路上的第三方服务由使用者自行负责**。Telegram、企业微信、自建 Webhook 的传输与存储安全不在本项目控制范围内。
4. **验证码属于高敏感信息**。建议给 Webhook 服务端配置 HTTPS + 签名校验 + 访问控制，并尽量缩短留存时间。

---

## 六、验收清单（上架或交付前逐项跑一遍）

- [ ] 冷启动 app → 授予短信 / 通知权限 → 完成知情同意
- [ ] 「保活」页必需项 5/5
- [ ] 注入测试短信 → 日志出现「已入队」+ 目标端收到
- [ ] 真实短信（另一台手机发送）→ 日志来源为 `sms`
- [ ] 杀掉 app 进程（adb shell am force-stop 除外，那会置 stopped）→ 发短信仍能转发
- [ ] 息屏 30 分钟 → 发短信仍能转发
- [ ] 一键清理后台 → 仍能转发（依赖多任务锁定）
- [ ] 重启手机 → 不打开 app，发短信仍能转发
- [ ] 断网发短信 → 复网后自动补发（日志出现重试记录）
- [ ] 长短信（>140 字节）→ 转发内容完整
- [ ] 双卡设备 → 日志中 SIM 卡槽正确
- [ ] 目标通道连续失败 6 次 → 日志出现「转发失败」并停止重试，不无限循环

---

## 七、将来迁移到 targetSdk 37 的路径

当 Android 18 或应用商店强制要求升级 target 时，OTP 3 小时延迟无法回避，可选路线：

| 方案 | 代价 | 适用 |
|------|------|------|
| **A. 申请成为默认短信应用**（`RoleManager.ROLE_SMS`） | 需要实现完整的短信应用能力（收件箱、发送、通知、备份恢复），商店审核严格 | 已经做成完整短信客户端的产品 |
| **B. 迁移到 SMS Retriever / User Consent API** | 只能拿到「发给本应用签名哈希」的验证码，且短信格式要配合，**不适合转发他人/任意短信** | 只为自己 App 登录验证码的场景 |
| **C. 继续留在 target 36** | 无法使用 37 的新 API；商店未来可能强制升级 | 短期内的现实选择（本项目默认） |

「保活」页第 9 项已经预留了默认短信应用的申请入口，走 A 路线时可以直接复用。

---

## 八、二次开发扩展点

| 想加什么 | 改哪里 |
|---------|-------|
| 新的转发通道（如邮件、Gotify、Ntfy） | `ChannelType` 加枚举 → `net/Channels.kt` 的 `Sender.send` 加分支 → `ChannelDialog` 加字段提示 |
| 更复杂的规则（时间段、黑名单、发送方号码段） | `RelayRule` 加字段 → `RuleMatcher.match` 加判断 → `RuleDialog` 加输入项 |
| 端到端加密转发 | `Sender.webhook()` 里对 payload 做加密后再 POST |
| 远程配置下发 | 在 `RetryWorker` 里加一次配置拉取，写回 `Prefs` |
| 多设备互转 | `SmsEvent` 里加设备标识，`Template` 里加 `{device}`（已支持） |
