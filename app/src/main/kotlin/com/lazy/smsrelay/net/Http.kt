package com.lazy.smsrelay.net

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全局 OkHttp 单例。
 *
 * 要点：
 *  - 超时一定要短（10s）。转发动作可能发生在 BroadcastReceiver 的 goAsync 窗口内，
 *    一个挂住的请求会把整条广播链路拖到 ANR。
 *  - 不跟随系统代理之外的任何配置，避免在国产 ROM 上被诡异的网络策略拖慢。
 */
object Http {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** POST JSON，非 2xx 直接抛 IOException */
    @Throws(IOException::class)
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        return exec(req)
    }

    /** POST 表单 */
    @Throws(IOException::class)
    fun postForm(url: String, params: Map<String, String>): String {
        val fb = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(fb).build()
        return exec(req)
    }

    private fun exec(req: Request): String {
        val call: Call = client.newCall(req)
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} ${resp.message.take(80)} | ${text.take(200)}")
            }
            return text
        }
    }
}

/* ------------------------------ 签名工具 ------------------------------ */

object Sign {

    fun hmacSha256Base64(key: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return android.util.Base64.encodeToString(
            mac.doFinal(data.toByteArray()),
            android.util.Base64.NO_WRAP
        )
    }

    fun hmacSha256Hex(key: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
