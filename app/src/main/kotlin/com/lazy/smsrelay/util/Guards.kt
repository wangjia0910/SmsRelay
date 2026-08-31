package com.lazy.smsrelay.util

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lazy.smsrelay.data.ChannelType
import com.lazy.smsrelay.data.Prefs

/**
 * 澎湃OS / HyperOS 4 保活自检清单。
 *
 * 设计上的两个原则：
 *
 * 1) **绝不硬信 com.miui.* 跳转**。
 *    澎湃OS 4 官方定位是「零 MIUI 残留」版本 —— 底层用 Rust 重写、清掉了
 *    MIUI 时代的兼容层与冗余服务。这意味着老教程里的
 *    com.miui.securitycenter / com.miui.powerkeeper 页面在这个版本上大概率
 *    **直接不存在**。所以每一项都准备 N 个候选 + 兜底系统设置页，
 *    并用 resolveActivity 逐个试，全部失败时老老实实给出手动路径。
 *
 * 2) **能查的查，查不了的就承认查不了**。
 *    自启动、多任务锁定这类开关没有任何公开 API 可以读取状态，
 *    与其用「是否装了安全中心」之类的玄学启发式去猜，不如标成「待确认」，
 *    让用户自己确认一次。假绿比不显示更害人。
 */

enum class GuardState { OK, TODO, UNKNOWN }

data class GuardItem(
    val key: String,
    val title: String,
    val desc: String,
    val manualPath: String,
    /** 必需项为 false 时，转发大概率不工作 */
    val critical: Boolean,
    val state: GuardState,
    /** 是否存在可跳转的设置页（决定按钮是「去设置」还是「查看手动路径」） */
    val jumpable: Boolean
)

object Guards {

    private fun comp(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun act(action: String, pkg: String? = null): Intent =
        Intent(action).apply { if (pkg != null) setPackage(pkg) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun details(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun Context.resolve(i: Intent): Boolean =
        runCatching { packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null }
            .getOrDefault(false)

    /* ============================ 清单定义 ============================ */

    fun build(ctx: Context): List<GuardItem> {
        val items = mutableListOf<GuardItem>()

        /* 1. 短信权限 —— 一切的前提 */
        items += GuardItem(
            key = "sms",
            title = "短信权限",
            desc = "接收短信是转发的基础。缺少 RECEIVE_SMS 时系统根本不会把短信广播发给你。",
            manualPath = "设置 → 应用设置 → 应用管理 → SmsRelay → 权限管理 → 短信（设为「始终允许」）",
            critical = true,
            state = if (smsPermissionOk(ctx)) GuardState.OK else GuardState.TODO,
            jumpable = ctx.resolve(details(ctx))
        )

        /* 2. 通知权限 —— 常驻通知的前置条件，被禁掉服务会异常 */
        items += GuardItem(
            key = "notify",
            title = "通知权限",
            desc = "Android 13+ 必须显式授权。前台服务依赖常驻通知，关掉通知等于关掉保活。",
            manualPath = "设置 → 应用设置 → 应用管理 → SmsRelay → 通知管理 → 允许通知（开启）",
            critical = true,
            state = if (NotificationManagerCompat.from(ctx).areNotificationsEnabled())
                GuardState.OK else GuardState.TODO,
            jumpable = ctx.resolve(notificationIntent(ctx))
        )

        /* 3. 自启动 —— 小米系最重要的一项 */
        items += GuardItem(
            key = "autostart",
            title = "自启动（关键）",
            desc = "不开启自启动，重启手机后 app 一次都不会被拉起，直到你手动打开它。",
            manualPath = "设置 → 应用设置 → 自启动管理 → 找到「SmsRelay」→ 打开开关\n" +
                "（部分版本路径为：手机管家 → 应用管理 → 自启动）",
            critical = true,
            state = GuardState.UNKNOWN,
            jumpable = autoStartIntents(ctx).any { ctx.resolve(it) }
        )

        /* 4. 省电策略 —— 决定息屏后能不能联网 */
        items += GuardItem(
            key = "battery_strategy",
            title = "省电策略设为「无限制」（关键）",
            desc = "澎湃OS 默认对第三方应用做后台限制，息屏后网络与 CPU 会被冻结，转发直接卡在队列里。",
            manualPath = "设置 → 应用设置 → 应用管理 → SmsRelay → 省电策略 → 选择「无限制」",
            critical = true,
            state = GuardState.UNKNOWN,
            jumpable = batteryStrategyIntents(ctx).any { ctx.resolve(it) }
        )

        /* 5. 电池优化白名单 —— 同时是「后台启动前台服务」的官方豁免条件 */
        items += GuardItem(
            key = "battery_opt",
            title = "忽略电池优化",
            desc = "Android 12+ 规定：用户关闭了本应用的电池优化，才允许从后台启动前台服务。这一项直接决定被杀后能否自我恢复。",
            manualPath = "设置 → 省电与电池 → 电池 → 应用省电优化 → 找到本应用 → 选择「无限制 / 不优化」",
            critical = true,
            state = if (ignoringBatteryOptimizations(ctx)) GuardState.OK else GuardState.TODO,
            jumpable = ctx.resolve(ignoreBatteryIntent(ctx))
        )

        /* 6. 多任务锁定 —— 防止一键清理 */
        items += GuardItem(
            key = "lock_recents",
            title = "多任务锁定（强烈建议）",
            desc = "上滑清理后台时，被锁定的应用会被跳过。没有系统 API 可以读这个状态，只能手动确认。",
            manualPath = "打开多任务界面（底部上滑停顿）→ 找到本应用卡片 → 长按 → 点击「锁定」图标\n" +
                "（图标变实心锁即为生效）",
            critical = false,
            state = GuardState.UNKNOWN,
            jumpable = false
        )

        /* 7. 通知使用权 —— 可选补偿通道 */
        items += GuardItem(
            key = "nl",
            title = "通知使用权（可选）",
            desc = "开启后可在短信广播被 ROM 拦截时，从通知里兜底抓取内容。注意：Android 15+ 未授信的监听器读到的验证码会被系统脱敏，因此它只能当备份，不能当主力。",
            manualPath = "设置 → 通知与控制中心 → 通知使用权（或「通知读取权限」）→ 勾选「SmsRelay」",
            critical = false,
            state = if (Prefs.notifyBackupEnabled(ctx)) {
                if (notificationListenerGranted(ctx)) GuardState.OK else GuardState.TODO
            } else GuardState.UNKNOWN,
            jumpable = notificationListenerIntents(ctx).any { ctx.resolve(it) }
        )

        /* 8. 后台弹出界面 / 后台联网 —— 部分版本存在 */
        items += GuardItem(
            key = "bg_popup",
            title = "后台弹出界面 / 后台联网（如系统存在）",
            desc = "若系统里有这两项开关，请一并放开。它们会限制应用在后台弹窗与联网，影响异常提醒与补发。",
            manualPath = "设置 → 应用设置 → 授权管理 → 后台弹出界面 / 后台联网 → 找到本应用 → 允许",
            critical = false,
            state = GuardState.UNKNOWN,
            jumpable = ctx.resolve(details(ctx))
        )

        /* 9. 默认短信应用 —— 面向未来的可选高阶方案 */
        items += GuardItem(
            key = "default_sms",
            title = "设为默认短信应用（可选，面向 Android 17）",
            desc = "Android 17 起，非默认短信应用读取含验证码的短信会被延迟 3 小时。当前 targetSdk 锁定在 36 不受影响；若将来必须升到 37，成为默认短信应用是唯一能保住实时性的路径。",
            manualPath = "设置 → 应用设置 → 默认应用设置 → 短信 → 选择「SmsRelay」",
            critical = false,
            state = GuardState.UNKNOWN,
            jumpable = defaultSmsIntents(ctx).any { ctx.resolve(it) }
        )

        return items
    }

    /* ============================ 跳转执行 ============================ */

    /** 逐个尝试候选 Intent，成功则返回 true；全部失败返回 false（调用方应展示手动路径） */
    fun open(ctx: Context, key: String): Boolean {
        val intents = when (key) {
            "sms" -> listOf(details(ctx))
            "notify" -> listOf(notificationIntent(ctx), details(ctx))
            "autostart" -> autoStartIntents(ctx) + details(ctx)
            "battery_strategy" -> batteryStrategyIntents(ctx) + details(ctx)
            "battery_opt" -> listOf(ignoreBatteryIntent(ctx), batteryOptimizationSettingsIntent(), details(ctx))
            "lock_recents" -> listOf(details(ctx))
            "nl" -> notificationListenerIntents(ctx)
            "bg_popup" -> listOf(details(ctx))
            "default_sms" -> defaultSmsIntents(ctx)
            else -> emptyList()
        }
        for (i in intents) {
            if (ctx.resolve(i)) {
                return runCatching {
                    ctx.startActivity(i)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    /* ============================ 候选 Intent ============================ */
    /*
     * 以下 com.miui.* 组件名来自 MIUI / 澎湃OS 1~3 的实测经验。
     * 澎湃OS 4 清理了 MIUI 兼容层，这些类名很可能已经不存在 ——
     * 所以每个列表都以系统设置页或应用详情页兜底，缺一个不会导致功能不可用。
     */

    private fun autoStartIntents(ctx: Context): List<Intent> = listOf(
        comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        act("miui.intent.action.OP_AUTO_START", "com.miui.securitycenter"),
        act("android.settings.AUTO_START", "com.miui.securitycenter"),
        act(Settings.ACTION_APPLICATION_SETTINGS),
        act(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
    )

    private fun batteryStrategyIntents(ctx: Context): List<Intent> = listOf(
        comp("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"),
        comp("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeActivity"),
        act("miui.intent.action.POWER_HIDE_MODE", "com.miui.powerkeeper"),
        act(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        act(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    )

    private fun ignoreBatteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun batteryOptimizationSettingsIntent(): Intent =
        act(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun notificationIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notificationListenerIntents(ctx: Context): List<Intent> {
        val list = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                    ComponentName(ctx, com.lazy.smsrelay.service.RelayNotificationListener::class.java).flattenToString()
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        list += act(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        return list
    }

    private fun defaultSmsIntents(ctx: Context): List<Intent> {
        val list = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val rm = ctx.getSystemService(RoleManager::class.java)
                list += rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        list += act("android.provider.Telephony.ACTION_CHANGE_DEFAULT")
        list += act("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
        return list
    }

    /* ============================ 状态查询 ============================ */

    fun smsPermissionOk(ctx: Context): Boolean {
        val core = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!core) return false
        if (Prefs.inboxRescan(ctx)) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
        }
        val hasSmsOut = Prefs.loadChannels(ctx).any { it.enabled && it.type == ChannelType.SMS_OUT }
        if (hasSmsOut) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return true
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)

    fun notificationListenerGranted(ctx: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        return flat.split(':').any { it.contains(ctx.packageName) }
    }

    fun requiredDone(ctx: Context): Pair<Int, Int> {
        val required = build(ctx).filter { it.critical }
        return required.count { it.state == GuardState.OK } to required.size
    }
}
