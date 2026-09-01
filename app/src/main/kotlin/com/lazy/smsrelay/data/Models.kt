package com.lazy.smsrelay.data

/* ============================ 转发通道 ============================ */

enum class ChannelType(val label: String) {
    WEBHOOK("通用 Webhook"),
    BARK("Bark"),
    SERVERCHAN("Server酱³"),
    TELEGRAM("Telegram Bot"),
    WECOM("企业微信机器人"),
    DINGTALK("钉钉机器人"),
    FEISHU("飞书机器人"),
    SMS_OUT("转发到另一个手机号"),
    EMAIL("邮箱 SMTP"),
    ;

    /** 该类型主要靠哪个字段定位目标，供 UI 提示与校验使用 */
    val primaryField: Field get() = when (this) {
        WEBHOOK, WECOM, DINGTALK, FEISHU, EMAIL -> Field.URL
        BARK, SERVERCHAN, TELEGRAM -> Field.TOKEN
        SMS_OUT -> Field.TARGET
    }

    enum class Field { URL, TOKEN, TARGET }
}

data class ChannelConfig(
    val id: String = "",
    val type: ChannelType = ChannelType.WEBHOOK,
    val name: String = "",
    val enabled: Boolean = true,
    /** Webhook 完整地址 / 机器人回调地址 */
    val url: String = "",
    /** Bark key、Server酱 SendKey、Telegram Bot Token、邮箱账号（发件人） */
    val token: String = "",
    /** 签名密钥（钉钉 / 飞书 / 自定义 Webhook 的 HMAC）；邮箱通道用作登录密码/授权码 */
    val secret: String = "",
    /** Telegram chat_id / 短信回发的目标号码 / 邮箱通道的收件人（多个用逗号分隔） */
    val target: String = "",
    /** 仅 SMS_OUT 使用：回发短信时走哪张卡，-1 = 系统默认卡 */
    val simSlot: Int = -1,
    /** 该通道自己的输出模板；留空使用全局模板 */
    val template: String = ""
)

/* ============================ 规则 ============================ */

data class RelayRule(
    val id: String = "",
    val name: String = "默认规则",
    val enabled: Boolean = true,
    /** 发送号码包含（支持多个，英文逗号分隔，任一命中即可） */
    val senderFilter: String = "",
    /** 正文关键词（英文逗号分隔，任一命中即可） */
    val keyword: String = "",
    /** 正文正则（可选，命中才转发） */
    val regex: String = "",
    /** -1 = 不区分卡槽；0/1 = 仅指定卡槽 */
    val simSlot: Int = -1,
    /** 仅转发能识别出验证码的短信 */
    val onlyOtp: Boolean = false,
    /** 命中的转发通道；空 = 使用全部已启用通道 */
    val channelIds: List<String> = emptyList()
)

/* ============================ 运行时事件 ============================ */

data class SmsEvent(
    val from: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 卡槽序号：0/1，-1 表示未知 */
    val simSlot: Int = -1,
    /** 订阅 ID，用于双卡短信回发时选卡 */
    val subId: Int = -1,
    /** 来源：sms（广播）/ notify（通知监听）/ inbox（收件箱兜底）/ test（自检） */
    val source: String = "sms"
)

/** 待发队列里的一条记录 */
data class OutboxItem(
    val id: Long,
    val ts: Long,
    val channelId: String,
    /** 已按模板渲染好的文本 */
    val text: String,
    val sender: String,
    val body: String,
    val simSlot: Int,
    val source: String,
    val rule: String,
    val tries: Int,
    val nextAt: Long,
    val lastError: String,
    /** 是否为验证码短信。验证码插队优先发送，且超时后不再补发 */
    val isOtp: Boolean = false
)

/** 转发日志里的一条记录 */
data class LogItem(
    val id: Long,
    val ts: Long,
    val sender: String,
    val body: String,
    val sim: Int,
    val source: String,
    val status: String,
    val detail: String
)

/* ============================ 默认模板 ============================ */

const val DEFAULT_TEMPLATE =
    "【短信转发】来自 {from}（SIM{sim}）\n{body}\n时间：{time}"

const val DEFAULT_CHANNEL_TEMPLATE = ""
