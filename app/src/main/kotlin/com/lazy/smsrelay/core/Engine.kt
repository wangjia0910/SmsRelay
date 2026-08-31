package com.lazy.smsrelay.core

import android.content.Context
import com.lazy.smsrelay.data.ChannelConfig
import com.lazy.smsrelay.data.Db
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.data.SmsEvent
import com.lazy.smsrelay.net.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 转发引擎。
 *
 * 设计原则（被 Android / 澎湃OS 反复教育后得出的结论）：
 *  1. 「先落库，后发送」。任何依赖内存队列的方案在国产 ROM 上都活不过一次息屏。
 *  2. Receiver 只负责把短信搬进数据库，网络动作一律交给前台服务/Worker 消费队列。
 *  3. 所有发送都要能被重放：幂等 key 用短信指纹，重复执行不会产生重复推送。
 */
object Engine {

    private const val MAX_TRIES = 6

    /** 新短信入口（广播 / 通知 / 兜底扫描 / 自检都用这一个） */
    suspend fun onSms(ctx: Context, ev: SmsEvent) = withContext(Dispatchers.IO) {
        handle(ctx, ev)
    }

    private fun handle(ctx: Context, ev: SmsEvent) {
        val appCtx = ctx.applicationContext

        // 未解锁：写 DE 存储，解锁后补发
        if (!PendingStore.isUnlocked(appCtx)) {
            PendingStore.add(appCtx, ev)
            return
        }

        val db = Db.get(appCtx)

        if (!Prefs.userConsent(appCtx)) {
            db.insertLog(ev, "未转发", "尚未完成知情同意，已丢弃")
            return
        }

        val hash = Template.hashOf(ev)
        val window = Prefs.dedupSeconds(appCtx) * 1000L
        if (db.isDuplicate(hash, window)) {
            db.insertLog(ev, "去重", "窗口内重复事件，已跳过")
            return
        }
        db.putHash(hash, System.currentTimeMillis())

        val rules = Prefs.loadRules(appCtx).filter { RuleMatcher.match(it, ev) }
        if (rules.isEmpty()) {
            db.insertLog(ev, "未匹配", "无规则命中")
            return
        }

        val channels = Prefs.loadChannels(appCtx)
        val byId = channels.associateBy { it.id }
        val globalTpl = Prefs.globalTemplate(appCtx)

        var queued = 0
        for (rule in rules) {
            val ids = rule.channelIds.ifEmpty { channels.filter { it.enabled }.map { it.id } }
            for (cid in ids.distinct()) {
                val ch = byId[cid] ?: continue
                if (!ch.enabled) continue
                val tpl = ch.template.ifBlank { globalTpl }
                val text = Template.render(tpl, ev, appCtx, rule.name)
                db.enqueue(ch.id, text, ev, rule.name)
                queued++
            }
        }

        db.insertLog(
            ev,
            if (queued > 0) "已入队" else "未匹配",
            if (queued > 0) "命中 ${rules.joinToString { it.name }}，生成 $queued 条待发"
            else "规则命中但无可用通道"
        )

        // 清理 3 天前的历史，避免长期运行把数据库撑大
        db.prune(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L)
    }

    /** 消费待发队列。返回成功条数。 */
    suspend fun flush(ctx: Context, limit: Int = 20): Int = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        if (!PendingStore.isUnlocked(appCtx)) return@withContext 0

        val db = Db.get(appCtx)
        val channels = Prefs.loadChannels(appCtx).associateBy { it.id }
        val items = db.pending(limit = limit)
        var ok = 0

        for (item in items) {
            val ch: ChannelConfig? = channels[item.channelId]
            if (ch == null) {
                // 通道被删了，队列里的残留直接丢弃
                db.remove(item.id)
                continue
            }
            if (!ch.enabled) {
                // 通道被临时停用：不删除、不发送，退避等待，用户重新打开后自然恢复
                db.markRetry(item.id, item.tries + 1, "通道已停用")
                continue
            }

            val tries = item.tries + 1
            val result = runCatching { Sender.send(appCtx, ch, item) }

            if (result.isSuccess) {
                db.remove(item.id)
                Prefs.noteSuccess(appCtx)
                ok++
            } else {
                val err = result.exceptionOrNull()?.message ?: "未知错误"
                if (tries >= MAX_TRIES) {
                    db.remove(item.id)
                    db.insertLog(
                        SmsEvent(item.sender, item.body, item.ts, item.simSlot, -1, item.source),
                        "转发失败",
                        "重试 ${MAX_TRIES} 次仍失败，已放弃：$err"
                    )
                } else {
                    db.markRetry(item.id, tries, err)
                }
            }
        }
        ok
    }
}
