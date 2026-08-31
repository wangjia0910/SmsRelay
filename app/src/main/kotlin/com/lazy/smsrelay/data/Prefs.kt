package com.lazy.smsrelay.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 轻量配置存储。
 *
 * 使用普通 SharedPreferences 而非 DataStore：本 app 会在 BroadcastReceiver 的
 * goAsync 窗口内同步读配置，DataStore 的异步首帧在冷启动路径上有不确定性，
 * 而 SharedPreferences 的首次 getSharedPreferences 是同步的，更适合这个场景。
 *
 * 注意：这里用的是凭据加密存储（CE），设备未首次解锁时不可读写。
 * 未解锁期间的事件由 PendingStore 落到设备加密存储（DE）。
 */
object Prefs {

    private const val FILE = "relay_prefs"
    private const val K_RULES = "rules"
    private const val K_CHANNELS = "channels"
    private const val K_SERVICE_ENABLED = "service_enabled"
    private const val K_NOTIFY_ENABLED = "notify_backup_enabled"
    private const val K_INBOX_RESCAN = "inbox_rescan"
    private const val K_RESCAN_MINUTES = "rescan_minutes"
    private const val K_DEDUP_SEC = "dedup_seconds"
    private const val K_TEMPLATE = "global_template"
    private const val K_DEVICE_NAME = "device_name"
    private const val K_CONSENT = "user_consent_v1"
    private const val K_LAST_SUCCESS = "last_success_at"
    private const val K_TOTAL = "total_forwarded"
    private const val K_MASK_OTP = "mask_otp_in_log"
    private const val K_OTP_TTL = "otp_ttl_minutes"
    private const val K_OTP_DROP = "otp_drop_expired"

    private val gson = Gson()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /* ---------------- 规则 ---------------- */

    fun loadRules(ctx: Context): MutableList<RelayRule> {
        val json = sp(ctx).getString(K_RULES, null) ?: return defaultRules().toMutableList()
        return try {
            val type = object : TypeToken<MutableList<RelayRule>>() {}.type
            val list: MutableList<RelayRule> = gson.fromJson(json, type)
            if (list.isEmpty()) defaultRules().toMutableList() else list
        } catch (t: Throwable) {
            defaultRules().toMutableList()
        }
    }

    fun saveRules(ctx: Context, rules: List<RelayRule>) {
        sp(ctx).edit().putString(K_RULES, gson.toJson(rules)).apply()
    }

    private fun defaultRules(): List<RelayRule> = listOf(
        RelayRule(
            id = "default",
            name = "全部短信",
            enabled = true,
            channelIds = emptyList()
        )
    )

    /* ---------------- 通道 ---------------- */

    fun loadChannels(ctx: Context): MutableList<ChannelConfig> {
        val json = sp(ctx).getString(K_CHANNELS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ChannelConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun saveChannels(ctx: Context, list: List<ChannelConfig>) {
        sp(ctx).edit().putString(K_CHANNELS, gson.toJson(list)).apply()
    }

    /* ---------------- 开关与参数 ---------------- */

    var Context.serviceEnabled: Boolean
        get() = sp(this).getBoolean(K_SERVICE_ENABLED, true)
        set(v) = sp(this).edit().putBoolean(K_SERVICE_ENABLED, v).apply()

    fun serviceEnabled(ctx: Context) = ctx.serviceEnabled

    var Context.notifyBackupEnabled: Boolean
        get() = sp(this).getBoolean(K_NOTIFY_ENABLED, false)
        set(v) = sp(this).edit().putBoolean(K_NOTIFY_ENABLED, v).apply()

    /** 收件箱兜底扫描：广播被厂商拦掉时的最后一道防线 */
    var Context.inboxRescan: Boolean
        get() = sp(this).getBoolean(K_INBOX_RESCAN, true)
        set(v) = sp(this).edit().putBoolean(K_INBOX_RESCAN, v).apply()

    var Context.rescanMinutes: Int
        get() = sp(this).getInt(K_RESCAN_MINUTES, 15)
        set(v) = sp(this).edit().putInt(K_RESCAN_MINUTES, v).apply()

    /** 去重窗口：同一号码同一内容的短信在此窗口内只转发一次 */
    var Context.dedupSeconds: Int
        get() = sp(this).getInt(K_DEDUP_SEC, 30)
        set(v) = sp(this).edit().putInt(K_DEDUP_SEC, v).apply()

    var Context.globalTemplate: String
        get() = sp(this).getString(K_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = sp(this).edit().putString(K_TEMPLATE, v).apply()

    var Context.deviceName: String
        get() = sp(this).getString(K_DEVICE_NAME, android.os.Build.MODEL) ?: ""
        set(v) = sp(this).edit().putString(K_DEVICE_NAME, v).apply()

    /** 首次启动的知情同意，未同意前不转发任何内容 */
    var Context.userConsent: Boolean
        get() = sp(this).getBoolean(K_CONSENT, false)
        set(v) = sp(this).edit().putBoolean(K_CONSENT, v).apply()

    /**
     * 日志脱敏：验证码属于高敏感信息，默认不在本地日志里保留明文。
     * 排查问题时可以临时关掉，查完记得开回来。
     */
    var Context.maskOtpInLog: Boolean
        get() = sp(this).getBoolean(K_MASK_OTP, true)
        set(v) = sp(this).edit().putBoolean(K_MASK_OTP, v).apply()

    /** 验证码补发的有效期。超时仍未发出的验证码直接丢弃，不做补发 */
    var Context.otpTtlMinutes: Int
        get() = sp(this).getInt(K_OTP_TTL, 10)
        set(v) = sp(this).edit().putInt(K_OTP_TTL, v).apply()

    /** 是否启用验证码过期丢弃（关掉 = 验证码也无限重试） */
    var Context.otpDropExpired: Boolean
        get() = sp(this).getBoolean(K_OTP_DROP, true)
        set(v) = sp(this).edit().putBoolean(K_OTP_DROP, v).apply()

    /* ---------- 上面的扩展属性在 Java 互操作和跨模块调用时容易写错，
       这里再提供一组显式函数入口，业务代码统一用函数形式调用 ---------- */

    fun notifyBackupEnabled(ctx: Context): Boolean = ctx.notifyBackupEnabled
    fun setNotifyBackup(ctx: Context, v: Boolean) { ctx.notifyBackupEnabled = v }
    fun maskOtpInLog(ctx: Context): Boolean = ctx.maskOtpInLog
    fun otpTtlMinutes(ctx: Context): Int = ctx.otpTtlMinutes
    fun otpDropExpired(ctx: Context): Boolean = ctx.otpDropExpired
    fun inboxRescan(ctx: Context): Boolean = ctx.inboxRescan
    fun rescanMinutes(ctx: Context): Int = ctx.rescanMinutes
    fun dedupSeconds(ctx: Context): Int = ctx.dedupSeconds
    fun globalTemplate(ctx: Context): String = ctx.globalTemplate
    fun deviceName(ctx: Context): String = ctx.deviceName
    fun userConsent(ctx: Context): Boolean = ctx.userConsent
    fun markConsent(ctx: Context, agreed: Boolean) { ctx.userConsent = agreed }

    fun noteSuccess(ctx: Context) {
        sp(ctx).edit()
            .putLong(K_LAST_SUCCESS, System.currentTimeMillis())
            .putLong(K_TOTAL, sp(ctx).getLong(K_TOTAL, 0) + 1)
            .apply()
    }

    fun lastSuccessAt(ctx: Context) = sp(ctx).getLong(K_LAST_SUCCESS, 0L)
    fun totalForwarded(ctx: Context) = sp(ctx).getLong(K_TOTAL, 0L)
}
