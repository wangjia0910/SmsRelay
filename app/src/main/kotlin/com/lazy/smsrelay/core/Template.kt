package com.lazy.smsrelay.core

import com.lazy.smsrelay.data.RelayRule
import com.lazy.smsrelay.data.SmsEvent
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** 模板渲染 / 去重哈希 / 规则匹配 */
object Template {

    private val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /**
     * 支持的占位符：
     *  {from} 发送号码     {body} 短信全文    {time} 收到的时间
     *  {sim}  卡槽序号     {otp}  提取出的验证码   {device} 设备名
     */
    fun render(tpl: String, ev: SmsEvent, ctx: android.content.Context? = null, rule: String = ""): String {
        val device = ctx?.let { com.lazy.smsrelay.data.Prefs.deviceName(it) } ?: ""
        return tpl
            .replace("{from}", ev.from)
            .replace("{body}", ev.body)
            .replace("{time}", TIME_FMT.format(java.util.Date(ev.timestamp)))
            .replace("{sim}", if (ev.simSlot >= 0) (ev.simSlot + 1).toString() else "未知")
            .replace("{otp}", Otp.extract(ev.body) ?: "")
            .replace("{device}", device)
            .replace("{rule}", rule)
    }

    /** 内容指纹：号码 + 正文 + 30 秒时间片，用于跨通道去重 */
    fun hashOf(ev: SmsEvent): String {
        val slice = ev.timestamp / 30_000L
        val raw = "${ev.from}|${ev.body.trim()}|$slice"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}

/**
 * 验证码识别。
 *
 * 为什么不用「正则取 6 位数字」这种常见写法：
 * 短信正文里 4~8 位数字太多了 —— 金额（¥128.00）、订单号后段、快递单号、
 * 年份（2026）、银行卡后四位、日期（03-15）、甚至一句话里的门牌号。
 * 直接取第一段数字，转发过去的内容要么不是验证码，要么把用户的消费金额
 * 当成「验证码」推到另一端 —— 后者比不提取更糟。
 *
 * 所以这里用打分制：候选数字各自累加正负信号，超过阈值才认定。
 * 宁可 {otp} 留空（用户仍能看到短信全文），也不要推一个错误的码过去。
 */
object Otp {

    /** 提示词。中英文 + 常见变体，大小写不敏感 */
    private val TIP = Regex(
        """(验证码|校验码|校验码|动态码|确认码|安全码|登录码|激活码|交易码|身份码|验证代码|动态口令|口令|code|passcode|otp|one[- ]?time)""",
        RegexOption.IGNORE_CASE
    )

    /** 出现在数字前面时，几乎可以确定不是验证码 */
    private val NOISE_BEFORE = Regex(
        """(订单号|单号|快递|运单|物流|卡号|尾号|后四位|后4位|编号|流水号|账号|帐号|合同号|发票号|条形码|会员号|No\.?|NO\.?|ID)\s*[:：]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CURRENCY_BEFORE = Regex("""[¥￥$＄€￡]\s*$""")
    private val AMOUNT_AFTER = Regex("""^\s*(元|圆|块|元整|人民币|元\))""")
    /** 全文出现这些词时，短信大概率是动账通知而非验证码 */
    private val MONEY_SMS = Regex("""(余额|到账|收入|支出|消费|转账|还款|账单|支付成功|已支付|扣款)""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")

    private val NUM = Regex("""\d{4,8}""")

    /**
     * 提取验证码。无法确信时返回 null。
     * 返回的一定是原文中的数字片段（不含空格/横线），便于服务端直接比对。
     */
    fun extract(body: String): String? {
        if (body.isBlank()) return null
        val text = normalize(body)
        var best: Pair<Int, String>? = null

        for (m in NUM.findAll(text)) {
            val v = m.value
            val start = m.range.first
            val end = m.range.last + 1

            // 必须是独立的数字片段，不能是更长数字串的一部分
            if (start > 0 && text[start - 1].isDigit()) continue
            if (end < text.length && text[end].isDigit()) continue

            val before = text.substring(0, start)
            val after = text.substring(end)

            var score = 0

            // —— 最强信号：提示词紧邻 ——
            // 中文习惯「验证码123456」，英文习惯「code: 123456」或「123456 is your code」
            val tipBefore = TIP.findAll(before).lastOrNull()
            if (tipBefore != null && before.length - tipBefore.range.last <= 18) score += 100
            if (TIP.find(after)?.range?.first?.let { it <= 18 } == true) score += 80
            if (TIP.containsMatchIn(text)) score += 10

            // —— 长度偏好：6 位最常见，4/5 位次之 ——
            score += when (v.length) {
                6 -> 30
                4, 5 -> 18
                8 -> 4
                else -> 0
            }

            // —— 整条短信就是这个数字（如「123456」），强信号 ——
            if (text.trim().trim('，', ',', '。', '.', '：', ':') == v) score += 80

            // —— 负向信号 ——
            if (CURRENCY_BEFORE.containsMatchIn(before)) score -= 70          // ¥128.00
            if (AMOUNT_AFTER.containsMatchIn(after)) score -= 70              // 128元
            if (after.startsWith(".") && after.drop(1).firstOrNull()?.isDigit() == true) score -= 60
            if (NOISE_BEFORE.containsMatchIn(before)) score -= 80             // 订单号：1029384
            if (v.length == 4 && YEAR.matches(v)) score -= 50                 // 年份 2026
            if (MONEY_SMS.containsMatchIn(text) && v.length >= 5) score -= 25 // 动账短信

            if (best == null || score > best.first) best = score to v
        }

        val (score, value) = best ?: return null
        return if (score >= 60) value else null
    }

    /** 正文里是否出现了验证码提示词（用于「仅验证码」规则的快速判断） */
    fun hasTip(body: String): Boolean = TIP.containsMatchIn(normalize(body))

    /** 日志脱敏：把验证码替换成等长星号，避免明文长期留在本地 */
    fun mask(body: String, otp: String?): String {
        if (otp.isNullOrBlank()) return body
        return body.replace(otp, "*".repeat(otp.length))
    }

    /** 全角数字 / 标点归一化，某些营销号与银行短信会用全角数字 */
    private fun normalize(s: String): String {
        var needCopy = false
        for (c in s) {
            if (c in '０'..'９' || c == '：' || c == '（' || c == '）') { needCopy = true; break }
        }
        if (!needCopy) return s
        return buildString(s.length) {
            for (c in s) {
                when {
                    c in '０'..'９' -> append(('0'.code + (c.code - 0xFF10)).toChar())
                    c == '：' -> append(':')
                    c == '（' -> append('(')
                    c == '）' -> append(')')
                    else -> append(c)
                }
            }
        }
    }
}

object RuleMatcher {

    /** 任一命中即算命中：空条件视为不限制 */
    fun match(rule: RelayRule, ev: SmsEvent): Boolean {
        if (!rule.enabled) return false

        if (rule.simSlot >= 0 && ev.simSlot >= 0 && rule.simSlot != ev.simSlot) return false

        val senders = rule.senderFilter.split(',', '，', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (senders.isNotEmpty() && senders.none { ev.from.contains(it, ignoreCase = true) }) return false

        val words = rule.keyword.split(',', '，', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.none { ev.body.contains(it, ignoreCase = true) }) return false

        if (rule.regex.isNotBlank()) {
            val ok = runCatching { Regex(rule.regex).containsMatchIn(ev.body) }.getOrDefault(true)
            if (!ok) return false
        }

        if (rule.onlyOtp && Otp.extract(ev.body) == null) return false

        return true
    }
}
