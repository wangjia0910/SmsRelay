package com.lazy.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.data.SmsEvent
import com.lazy.smsrelay.service.RelayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短信主通道。
 *
 * 这一层刻意做得很薄：只解析 + 落库 + 尝试一次快速发送，绝不做重试循环。
 * 原因：onReceive 跑在 goAsync 的时间预算里（约 10 秒），超了就是 ANR；
 * 而在澎湃OS 上 ANR 之后系统会顺手把你标记为「异常应用」，加速被杀。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()?.filterNotNull()
        if (messages.isNullOrEmpty()) return

        // 长短信会被拆成多条 PDU，必须按发送方合并，否则转发过去是半截内容
        val merged = merge(messages)
        val appCtx = context.applicationContext
        val slot = readSlot(intent)
        val subId = readSubId(intent)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ev = SmsEvent(
                    from = merged.first,
                    body = merged.second,
                    timestamp = merged.third,
                    simSlot = slot,
                    subId = subId,
                    source = "sms"
                )
                Engine.onSms(appCtx, ev)

                // 服务还活着的话就交给它排队慢慢发；不在的话这里做一次限时直发，
                // 保证「服务被杀」场景下验证码仍然能出去。
                if (!RelayService.isAlive(appCtx)) {
                    withTimeoutOrNull(8_000) { Engine.flush(appCtx, limit = 5) }
                }
                RelayService.tryStart(appCtx)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private fun merge(list: List<SmsMessage>): Triple<String, String, Long> {
        val head = list.first()
        val from = head.originatingAddress ?: ""
        val ts = head.timestampMillis
        val body = list
            .filter { (it.originatingAddress ?: "") == from }
            .joinToString("") { it.messageBody.orEmpty() }
        return Triple(from, body, ts)
    }

    /**
     * 卡槽 / 订阅 ID 的 key 各家 ROM 不统一，这里做多 key 兜底：
     *  - 标准（AOSP）：android.telephony.extra.SLOT_INDEX / SUBSCRIPTION_INDEX
     *  - 联发科/高通老实现：slot / phone / simId
     *  - 小米部分版本：subscription / sub_id
     */
    private fun readSlot(intent: Intent): Int {
        val keys = listOf(
            "android.telephony.extra.SLOT_INDEX", // SubscriptionManager.EXTRA_SLOT_INDEX
            "slot", "phone", "simId", "slotId", "slot_id"
        )
        for (k in keys) {
            val v = intent.getIntExtra(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE && v >= 0) return v
        }
        return -1
    }

    private fun readSubId(intent: Intent): Int {
        val keys = listOf(
            "android.telephony.extra.SUBSCRIPTION_INDEX", // SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
            "subscription", "subscription_id", "subId", "sub_id"
        )
        for (k in keys) {
            val v = intent.getIntExtra(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
        }
        return -1
    }
}
