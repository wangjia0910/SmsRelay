package com.lazy.smsrelay.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.lazy.smsrelay.data.Db
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.data.SmsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 收件箱兜底扫描。
 *
 * 存在意义：国产 ROM（尤其是小米系）在进程不存活时会直接拦掉广播，
 * 静态 Receiver 一次都不触发。这时候「已入库的短信」是唯一的事实来源。
 *
 * 注意：Android 17 / target 37 起，非默认短信应用查询短信库时，含 OTP 的
 * 记录会被过滤掉三小时 —— 这也是本项目 targetSdk 锁在 36 的第二个理由。
 * 一旦升到 37，这条兜底链路同样会失效。
 */
object InboxScanner {

    @SuppressLint("MissingPermission")
    suspend fun scan(ctx: Context, windowMinutes: Int): Int = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext 0
        if (!Prefs.userConsent(appCtx)) return@withContext 0

        val cutoff = System.currentTimeMillis() - windowMinutes * 60_000L
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        var found = 0
        runCatching {
            appCtx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(cutoff.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                val db = Db.get(appCtx)
                val window = Prefs.dedupSeconds(appCtx) * 1000L

                while (c.moveToNext()) {
                    val ev = SmsEvent(
                        from = c.getString(iAddr) ?: "",
                        body = c.getString(iBody) ?: "",
                        timestamp = c.getLong(iDate),
                        simSlot = if (iSub >= 0) c.getInt(iSub) else -1,
                        subId = if (iSub >= 0) c.getInt(iSub) else -1,
                        source = "inbox"
                    )
                    // 去重命中说明广播/通知链路已经处理过，无需重复入队
                    val hash = Template.hashOf(ev)
                    if (db.isDuplicate(hash, window)) continue
                    found++
                    // 这里只做入库标记，真正的规则匹配与入队交给 handle，
                    // 保持「所有来源走同一条处理管线」
                    Engine.onSms(appCtx, ev)
                }
            }
        }
        found
    }
}
