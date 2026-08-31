package com.lazy.smsrelay.net

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.lazy.smsrelay.core.Otp
import com.lazy.smsrelay.core.Template
import com.lazy.smsrelay.data.ChannelConfig
import com.lazy.smsrelay.data.ChannelType
import com.lazy.smsrelay.data.OutboxItem
import org.json.JSONObject
import java.io.IOException

/**
 * 各转发通道的具体实现。
 *
 * 约定：成功即正常返回，失败一律抛异常 —— 由 Engine 统一做重试与退避。
 * 新增通道只需在 when 里加一个分支，并在 ChannelType 里注册。
 */
object Sender {

    @Throws(Exception::class)
    fun send(ctx: Context, ch: ChannelConfig, item: OutboxItem) {
        when (ch.type) {
            ChannelType.WEBHOOK -> webhook(ch, item)
            ChannelType.BARK -> bark(ch, item)
            ChannelType.SERVERCHAN -> serverchan(ch, item)
            ChannelType.TELEGRAM -> telegram(ch, item)
            ChannelType.WECOM -> wecom(ch, item)
            ChannelType.DINGTALK -> dingtalk(ch, item)
            ChannelType.FEISHU -> feishu(ch, item)
            ChannelType.SMS_OUT -> smsOut(ctx, ch, item)
        }
    }

    /** 直接发一条文本（UI 里的「测试通道」用） */
    @Throws(Exception::class)
    fun test(ctx: Context, ch: ChannelConfig, text: String) {
        val item = OutboxItem(
            id = -1, ts = System.currentTimeMillis(), channelId = ch.id, text = text,
            sender = "10000", body = text, simSlot = -1, source = "test", rule = "测试",
            tries = 0, nextAt = 0, lastError = ""
        )
        send(ctx, ch, item)
    }

    /* ------------------------------ 通用 Webhook ------------------------------ */

    private fun webhook(ch: ChannelConfig, item: OutboxItem) {
        val url = ch.url.trim()
        if (!url.startsWith("http")) throw IOException("Webhook 地址未配置")

        val payload = JSONObject().apply {
            put("text", item.text)
            put("from", item.sender)
            put("body", item.body)
            put("otp", Otp.extract(item.body) ?: "")
            put("sim", item.simSlot)
            put("source", item.source)
            put("rule", item.rule)
            put("ts", item.ts)
        }.toString()

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (ch.secret.isNotBlank()) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            headers["X-Relay-Timestamp"] = ts
            headers["X-Relay-Signature"] = "sha256=" + Sign.hmacSha256Hex(ch.secret, "$ts.$payload")
        }
        val resp = Http.postJson(url, payload, headers)
        // 某些自建服务返回 200 但 body 里带 error，这里做一次轻量识别
        if (resp.contains("\"errcode\"") && !resp.contains("\"errcode\":0")) {
            throw IOException("服务端返回错误：$resp".take(200))
        }
    }

    /* --------------------------------- Bark --------------------------------- */

    private fun bark(ch: ChannelConfig, item: OutboxItem) {
        val key = ch.token.trim()
        if (key.isBlank()) throw IOException("Bark device_key 未配置")
        val base = ch.url.trim().ifBlank { "https://api.day.app" }.trimEnd('/')
        val payload = JSONObject().apply {
            put("device_key", key)
            put("title", item.sender)
            put("body", item.text)
            put("group", "短信转发")
            put("level", "timeSensitive")
            put("isArchive", 1)
        }.toString()
        Http.postJson("$base/push", payload)
    }

    /* ------------------------------ Server酱³ ------------------------------ */

    private fun serverchan(ch: ChannelConfig, item: OutboxItem) {
        val key = ch.token.trim()
        if (key.isBlank()) throw IOException("Server酱 SendKey 未配置")
        Http.postForm(
            "https://sctapi.ftqq.com/$key.send",
            mapOf("title" to item.sender, "desp" to item.text)
        )
    }

    /* ------------------------------- Telegram ------------------------------- */

    private fun telegram(ch: ChannelConfig, item: OutboxItem) {
        val token = ch.token.trim()
        val chatId = ch.target.trim()
        if (token.isBlank() || chatId.isBlank()) throw IOException("Telegram Bot Token 或 chat_id 未配置")

        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", item.text)
            put("disable_web_page_preview", true)
        }.toString()
        val resp = Http.postJson("https://api.telegram.org/bot$token/sendMessage", payload)
        if (resp.contains("\"ok\":false")) throw IOException("Telegram 返回失败：$resp".take(200))
    }

    /* ----------------------------- 企业微信机器人 ----------------------------- */

    private fun wecom(ch: ChannelConfig, item: OutboxItem) {
        val url = ch.url.trim()
        if (!url.startsWith("http")) throw IOException("企业微信机器人地址未配置")
        val payload = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().put("content", item.text))
        }.toString()
        val resp = Http.postJson(url, payload)
        if (resp.contains("\"errcode\":") && !resp.contains("\"errcode\":0")) {
            throw IOException("企业微信返回错误：$resp".take(200))
        }
    }

    /* ------------------------------- 钉钉机器人 ------------------------------- */

    private fun dingtalk(ch: ChannelConfig, item: OutboxItem) {
        val base = ch.url.trim()
        if (!base.startsWith("http")) throw IOException("钉钉机器人地址未配置")

        val url = if (ch.secret.isBlank()) {
            base
        } else {
            val ts = System.currentTimeMillis()
            val sign = Sign.urlEncode(Sign.hmacSha256Base64(ch.secret, "$ts\n${ch.secret}"))
            "$base&timestamp=$ts&sign=$sign"
        }

        val payload = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().put("content", item.text))
        }.toString()
        val resp = Http.postJson(url, payload)
        if (resp.contains("\"errcode\":") && !resp.contains("\"errcode\":0")) {
            throw IOException("钉钉返回错误：$resp".take(200))
        }
    }

    /* ------------------------------- 飞书机器人 ------------------------------- */

    private fun feishu(ch: ChannelConfig, item: OutboxItem) {
        val base = ch.url.trim()
        if (!base.startsWith("http")) throw IOException("飞书机器人地址未配置")

        val payload = JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().put("text", item.text))
            if (ch.secret.isNotBlank()) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                // 飞书签名：以 "时间戳\n密钥" 为空消息体做 HmacSHA256，再 Base64
                put("timestamp", ts)
                put("sign", Sign.hmacSha256Base64("$ts\n${ch.secret}", ""))
            }
        }.toString()
        val resp = Http.postJson(base, payload)
        if (resp.contains("\"code\":") && !resp.contains("\"code\":0")) {
            throw IOException("飞书返回错误：$resp".take(200))
        }
    }

    /* ------------------------------ 短信回发 ------------------------------ */

    @SuppressLint("MissingPermission")
    private fun smsOut(ctx: Context, ch: ChannelConfig, item: OutboxItem) {
        val target = ch.target.trim()
        if (target.isBlank()) throw IOException("目标手机号未配置")
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.SEND_SMS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw IOException("缺少 SEND_SMS 权限")

        val smsManager = resolveSmsManager(ch.simSlot)
        val parts = smsManager.divideMessage(item.text)

        val sentPi = pendingIntent(ctx, 1001, item.id, "SENT")
        val deliveredPi = pendingIntent(ctx, 1002, item.id, "DELIVERED")

        if (parts.size > 1) {
            val sent = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { add(sentPi) }
            }
            val delivered = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { add(deliveredPi) }
            }
            smsManager.sendMultipartTextMessage(target, null, parts, sent, delivered)
        } else {
            smsManager.sendTextMessage(target, null, item.text, sentPi, deliveredPi)
        }
    }

    /**
     * 选卡：优先用卡槽号换 subId；拿不到就退回默认卡。
     * 部分 ROM 上 getSubscriptionIds 会因为权限或实现差异返回 null，
     * 所以整段必须 try/catch —— 宁可用默认卡发出去，也不要抛异常导致重试循环。
     */
    private fun resolveSmsManager(simSlot: Int): SmsManager {
        if (simSlot >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val ids = SubscriptionManager.getSubscriptionIds()
                if (ids != null && simSlot in ids.indices) {
                    return SmsManager.getSmsManagerForSubscriptionId(ids[simSlot])
                }
            }
        }
        return runCatching {
            SmsManager.getSmsManagerForSubscriptionId(SubscriptionManager.getDefaultSmsSubscriptionId())
        }.getOrDefault(SmsManager.getDefault())
    }

    private fun pendingIntent(ctx: Context, reqCode: Int, id: Long, action: String): PendingIntent {
        val i = Intent("com.lazy.smsrelay.SMS_RESULT")
            .setPackage(ctx.packageName)
            .putExtra("action", action)
            .putExtra("id", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, reqCode, i, flags)
    }
}
