package com.lazy.smsrelay.data

import android.content.Context
import android.os.UserManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 设备未首次解锁（Direct Boot）期间的暂存区。
 *
 * 场景：备用机重启后用户还没输密码，此时验证码短信已经到了。
 * 我们的 Receiver 因为 directBootAware=true 会被唤起，但凭据加密存储
 * （SharedPreferences / 普通 SQLite）此时读不了，直接处理会崩或丢。
 *
 * 解法：落到 device-protected storage，等收到 USER_UNLOCKED 或前台服务
 * 启动时再取出来补发。这一段在小米/澎湃OS 上尤其容易漏，属于「重启后
 * 第一条验证码丢失」的典型根因。
 */
object PendingStore {

    private const val FILE = "pending_locked"
    private const val KEY = "queue"
    private val gson = Gson()

    private fun sp(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isUnlocked(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        return um?.isUserUnlocked != false
    }

    fun add(ctx: Context, ev: SmsEvent) {
        val list = read(ctx).toMutableList()
        list.add(ev)
        sp(ctx).edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun size(ctx: Context) = read(ctx).size

    /** 取出并清空 */
    fun drain(ctx: Context): List<SmsEvent> {
        val list = read(ctx)
        if (list.isNotEmpty()) sp(ctx).edit().remove(KEY).apply()
        return list
    }

    private fun read(ctx: Context): List<SmsEvent> {
        val json = sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SmsEvent>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
