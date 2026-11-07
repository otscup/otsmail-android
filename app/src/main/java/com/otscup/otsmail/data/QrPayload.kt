package com.otscup.otsmail.data

import org.json.JSONObject

/**
 * 网页端扫码登录二维码的内容解析。
 *
 * 服务端只负责票据，二维码文本由网页端拼：
 * `{"type":"otsmail-login","id":"<32位小写hex>"}`。
 * 这里做严格的客户端校验，避免把网址、Wi-Fi、支付码等其它二维码误当成登录票据提交。
 */
object QrPayload {

    const val TYPE = "otsmail-login"

    /** 服务端 uuid v4 去掉横线后固定 32 位小写十六进制 */
    private val ID_RE = Regex("^[0-9a-f]{32}$")

    sealed interface Result {
        data class Ok(val id: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String?): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result.Invalid("二维码内容为空")
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Result.Invalid("不是 OTSMail 登录二维码")
        }
        if (json.optString("type") != TYPE) return Result.Invalid("不是 OTSMail 登录二维码")
        val id = json.optString("id")
        if (id.isEmpty()) return Result.Invalid("二维码缺少票据编号")
        if (!ID_RE.matches(id)) return Result.Invalid("二维码内容已损坏")
        return Result.Ok(id)
    }
}
