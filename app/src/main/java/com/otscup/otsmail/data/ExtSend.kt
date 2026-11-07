package com.otscup.otsmail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * 外部邮箱发信：取服务端加密保存的授权码 → 客户端直连该 provider 的 SMTP 发信。
 *
 * 约束：
 * - 授权码只在内存里用于这一次连接，不落盘、不写日志、不回传服务端；
 * - 服务端只负责保存密文与下发，参与投递的只有手机；
 * - 成功后写一份本地「已发送」副本（ext_sent），换机不会同步这份本地副本，
 *   成就说明见 docs/路线图-2026-11.md。
 */
object ExtSend {

    data class SendOutcome(val sent: ExtSentMail, val messageId: String)

    suspend fun send(
        context: Context,
        extId: Long,
        from: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        text: String,
        html: String,
        attachments: List<JSONObject>,
    ): SendOutcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val cred = Api.extCredential(extId)
        val secret = cred.optString("secret")
        if (secret.isEmpty()) throw IllegalStateException("服务端没有返回可用的授权码，请重新绑定这个邮箱")

        val address = cred.optString("address").ifEmpty { from }.trim()
        if (address.isEmpty()) throw IllegalStateException("外部邮箱地址为空，请重新绑定")
        val providerId = cred.optString("provider")
        val provider = ExtProviders.byId(providerId)
        val host = cred.optString("smtpHost").ifEmpty { provider?.smtp?.host.orEmpty() }
        val port = cred.optInt("smtpPort", 0).takeIf { it > 0 } ?: provider?.smtp?.port ?: 465
        if (host.isEmpty()) throw IllegalStateException("没有可用的 SMTP 服务器，请在外部邮箱设置里手动填写")

        val atts = parseAttachments(attachments)
        val messageId = UUID.randomUUID().toString() + "@" + address.substringAfter('@', "otsmail.local")
        val message = SmtpMessage(
            from = address,
            to = to.map { MailAddress.parseOne(it).address },
            cc = cc.map { MailAddress.parseOne(it).address },
            bcc = bcc.map { MailAddress.parseOne(it).address },
            subject = subject.ifBlank { "（无主题）" },
            text = htmlToPlain(text, html),
            html = if (html.isBlank()) plainToHtml(text) else html,
            attachments = atts,
            messageId = messageId,
        )

        try {
            ExtSmtpClient(host, port, address, secret).use { client ->
                client.send(message)
            }
        } catch (e: ExtSmtpException) {
            Log.w("ExtSend", "发送失败 ext=$extId code=${e.kind} msg=${e.message.orEmpty().take(160)}")
            throw e
        } finally {
            // 授权码不再被引用后尽早丢掉引用（GC 不可控，但至少不缓存到对象字段）
            Log.i("ExtSend", "SMTP 会话结束 ext=$extId host=$host:$port 耗时=${System.currentTimeMillis() - started}ms")
        }

        val now = System.currentTimeMillis()
        val sent = ExtSentMail(
            extId = extId,
            messageId = messageId,
            subject = message.subject,
            fromAddr = address,
            toAddr = to.joinToString(", "),
            cc = cc.joinToString(", "),
            dateMs = now,
            snippet = plainSnippet(message.text),
            bodyText = message.text,
            bodyHtml = message.html,
            attachmentsJson = attachmentMeta(attachments),
        )
        val localId = ExtMailStore.saveSent(sent)
        ExtSync.changed()
        Log.i(
            "ExtSend",
            "发送成功 ext=$extId 收件人=${to.size} 抄送=${cc.size} 密送=${bcc.size} 附件=${atts.size} " +
                "messageId=$messageId localId=$localId",
        )
        SendOutcome(sent.copy(localId = localId), messageId)
    }

    private fun parseAttachments(list: List<JSONObject>): List<SmtpAttachment> {
        if (list.isEmpty()) return emptyList()
        val total = list.sumOf { it.optLong("size", 0L) }
        if (total > 15L * 1024 * 1024) {
            throw IllegalStateException("附件累计超过 15MB，超出常见 SMTP 单封上限，请删减后再发")
        }
        return list.map { o ->
            val name = o.optString("filename").ifBlank { "attachment" }
            val bytes = try {
                Base64.getDecoder().decode(o.optString("content"))
            } catch (e: Throwable) {
                throw IllegalStateException("附件 $name 读取失败，请重新添加")
            }
            SmtpAttachment(name, o.optString("contentType").ifBlank { "application/octet-stream" }, bytes)
        }
    }

    /** 已发送列表只显示元信息，不把附件字节塞进本地库 */
    private fun attachmentMeta(list: List<JSONObject>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        for (o in list) {
            arr.put(
                JSONObject()
                    .put("filename", o.optString("filename"))
                    .put("contentType", o.optString("contentType"))
                    .put("size", o.optLong("size", 0)),
            )
        }
        return arr.toString()
    }

    private fun plainSnippet(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(120)

    /** 纯文本优先用用户输入的正文；只填 HTML 时给一个粗略的纯文本替代 */
    private fun htmlToPlain(text: String, html: String): String {
        if (text.isNotBlank()) return text
        return html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun plainToHtml(text: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><p>" +
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>") +
            "</p></body></html>"
}
