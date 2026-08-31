package com.lazy.smsrelay.service

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.data.SmsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听 —— 补偿通道，不是主力。
 *
 * 必须先讲清楚一件事：Android 15 起，未授信的 NotificationListenerService
 * 读到的 OTP 通知内容会被系统替换成「sensitive notification content hidden」。
 * 换句话说，你在 Android 15+ / 澎湃OS 3+ 上想靠读通知抓验证码，抓到的是一句废话。
 *
 * 那为什么还要这条通道？因为它能覆盖两类广播拿不到的情况：
 *   1. 厂商把短信广播拦了，但通知还在（部分 ROM 的「省电策略」会这么干）；
 *   2. 非 OTP 的普通短信/通知（快递、日程、银行动账），这些不受 OTP 屏蔽影响。
 *
 * 检测到被屏蔽的内容时，我们只记录、不转发，避免把「sensitive content hidden」
 * 这种噪音推给用户 —— 那比不转发更糟。
 */
class RelayNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 只关心系统短信应用的通知，避免把微信/广告通知也卷进来 */
    private val smsPackages = setOf(
        "com.android.mms",                       // 小米/澎湃OS 内置短信
        "com.google.android.apps.messaging",     // Google 信息
        "com.android.messaging",                 // AOSP 信息
        "com.miui.sms"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 权限被授予的瞬间同步一次状态给 UI
        Sync.mark(applicationContext, true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Sync.mark(applicationContext, false)
        runCatching { requestRebind(android.content.ComponentName(this, RelayNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        if (sbn.packageName !in smsPackages) return
        if (!com.lazy.smsrelay.data.Prefs.notifyBackupEnabled(applicationContext)) return

        val n = sbn.notification ?: return
        // 会话类通知（MESSAGING_STYLE）往往带多条历史消息，只取最新一条，避免重复转发
        val (title, text) = extract(n) ?: return

        if (text.isBlank()) return
        if (isRedacted(text)) {
            // 被系统标记为敏感内容，明文拿不到，记一笔供排查，不做转发
            com.lazy.smsrelay.data.Db.get(applicationContext).insertLog(
                SmsEvent(title, text, sbn.postTime, -1, -1, "notify"),
                "通知被脱敏",
                "系统判定为敏感内容，未转发。如需读取请见适配手册「通知 OTP 屏蔽」一节"
            )
            return
        }

        val appCtx = applicationContext
        scope.launch {
            Engine.onSms(
                appCtx,
                SmsEvent(
                    from = title,
                    body = text,
                    timestamp = sbn.postTime,
                    simSlot = -1,
                    subId = -1,
                    source = "notify"
                )
            )
            Engine.flush(appCtx, limit = 5)
        }
    }

    private fun extract(n: Notification): Pair<String, String>? {
        val extras: Bundle = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val body = big?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() } ?: return null
        return title to body
    }

    /** 系统脱敏后的占位文案（不同版本/语言略有差异，统一做包含判断） */
    private fun isRedacted(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("sensitive notification content hidden") ||
            t.contains("content hidden") ||
            t.contains("敏感内容已隐藏") ||
            t.contains("内容已隐藏")
    }

    /** 监听授权状态：Service 与 UI 之间用一个轻量标记同步 */
    object Sync {
        private const val FILE = "nl_state"
        private const val KEY = "connected"

        fun mark(ctx: Context, connected: Boolean) {
            runCatching {
                ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY, connected).apply()
            }
        }

        fun connected(ctx: Context): Boolean =
            runCatching {
                ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
            }.getOrDefault(false)

        /** 系统设置里「通知使用权」的实际授权状态 */
        fun granted(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.split(':').any { it.contains(ctx.packageName) }
        }
    }

    companion object {
        fun isPermissionGranted(ctx: Context): Boolean = Sync.granted(ctx)

        fun hasSmsPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECEIVE_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
