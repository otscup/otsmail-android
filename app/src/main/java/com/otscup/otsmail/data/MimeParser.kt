package com.otscup.otsmail.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/** 一个附件：字节超过上限时 [data] 为 null，只保留元信息。 */
data class MimeAttachment(
    val filename: String,
    val mimeType: String,
    val size: Int,
    val contentId: String = "",
    val data: ByteArray? = null,
)

data class ParsedMail(
    val subject: String,
    val fromName: String,
    val fromAddr: String,
    val toName: String,
    val toAddr: String,
    val cc: String,
    val dateMs: Long,
    val messageId: String,
    val text: String,
    val html: String,
    val attachments: List<MimeAttachment>,
)

/**
 * 只读 MIME 解析，跑在客户端（拉取也在客户端），所以这里不依赖任何 Android API，
 * 可以用 JVM 单测覆盖 GBK / GB18030、base64、quoted-printable、RFC 2047。
 *
 * 口径：单附件超过 [MAX_ATTACHMENT_BYTES] 只留元信息，不再保留字节（上传归档时也一样）。
 */
object MimeParser {

    const val MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024

    private const val MAX_DEPTH = 6

    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([BbQq])\\?([^?]*)\\?=")
    private val ANGLE_ADDR = Regex("<([^>]+)>")

    fun parse(raw: ByteArray, fallbackDateMs: Long = 0L): ParsedMail {
        if (raw.isEmpty()) {
            return ParsedMail(
                subject = "", fromName = "", fromAddr = "", toName = "", toAddr = "", cc = "",
                dateMs = fallbackDateMs, messageId = "", text = "", html = "", attachments = emptyList(),
            )
        }
        // ISO-8859-1 是字节的 1:1 映射，用它做结构切分不会破坏任何编码
        val whole = String(raw, Charsets.ISO_8859_1)
        val (head, body) = splitHeadBody(whole)
        val headers = parseHeaders(head)
        val out = Collector()
        walk(headers, body, 0, out)

        val subject = decodeHeader(headers["subject"].orEmpty())
        val from = parseAddress(headers["from"].orEmpty())
        val to = parseAddress(headers["to"].orEmpty())
        val date = parseDate(headers["date"].orEmpty()).takeIf { it > 0 } ?: fallbackDateMs

        return ParsedMail(
            subject = subject,
            fromName = from.first,
            fromAddr = from.second,
            toName = to.first,
            toAddr = to.second,
            cc = decodeHeader(headers["cc"].orEmpty()),
            dateMs = date,
            messageId = headers["message-id"].orEmpty().trim(),
            text = out.text,
            html = out.html,
            attachments = out.attachments,
        )
    }

    /** 列表页用的摘要：优先纯文本，没有就从 HTML 去标签。 */
    fun snippet(text: String, html: String, max: Int = 160): String {
        val source = text.ifBlank { html.replace(Regex("<[^>]+>"), " ") }
        return source.replace(Regex("\\s+"), " ").trim().take(max)
    }

    private class Collector {
        var text = ""
        var html = ""
        val attachments = mutableListOf<MimeAttachment>()
    }

    private fun walk(headers: Map<String, String>, body: String, depth: Int, out: Collector) {
        val (rawMime, params) = parseContentType(headers["content-type"].orEmpty())
        // RFC 2045：缺省 Content-Type 就是 text/plain
        val mime = rawMime.ifEmpty { "text/plain" }
        val disposition = headers["content-disposition"].orEmpty()
        val dispositionType = disposition.substringBefore(';').trim().lowercase()
        val boundary = params["boundary"]

        val isAttachment = dispositionType == "attachment"
        val filename = filenameOf(params["filename"], disposition)

        if (mime.startsWith("multipart/") && boundary != null && depth < MAX_DEPTH) {
            for (part in splitMultipart(body, boundary)) {
                val (pHead, pBody) = splitHeadBody(part)
                walk(parseHeaders(pHead), pBody, depth + 1, out)
            }
            return
        }

        val bytes = decodeTransfer(body, headers["content-transfer-encoding"])

        when {
            mime == "text/plain" && !isAttachment -> {
                if (out.text.isEmpty()) out.text = decodeCharset(bytes, params["charset"])
            }

            mime == "text/html" && !isAttachment -> {
                if (out.html.isEmpty()) out.html = decodeCharset(bytes, params["charset"])
            }

            mime == "message/rfc822" -> {
                // 嵌套邮件不展开，只当成一个 .eml 附件登记，避免无限递归吃内存
                out.attachments += MimeAttachment(
                    filename = filename.ifEmpty { "邮件.eml" },
                    mimeType = "message/rfc822",
                    size = bytes.size,
                    contentId = headers["content-id"].orEmpty().trim(),
                )
            }

            filename.isNotEmpty() || isAttachment -> {
                out.attachments += MimeAttachment(
                    filename = filename.ifEmpty { "附件${out.attachments.size + 1}" },
                    mimeType = mime.ifEmpty { "application/octet-stream" },
                    size = bytes.size,
                    contentId = headers["content-id"].orEmpty().trim(),
                    data = bytes.takeIf { it.size <= MAX_ATTACHMENT_BYTES },
                )
            }

            mime.startsWith("text/") -> {
                // 其它 text/*（如 text/calendar）没有文件名，按转成纯文本补充正文，避免丢内容
                if (out.text.isEmpty()) {
                    out.text = decodeCharset(bytes, params["charset"])
                }
            }
        }
    }

    private fun splitHeadBody(text: String): Pair<String, String> {
        val crlf = text.indexOf("\r\n\r\n")
        if (crlf >= 0) return text.substring(0, crlf) to text.substring(crlf + 4)
        val lf = text.indexOf("\n\n")
        if (lf >= 0) return text.substring(0, lf) to text.substring(lf + 2)
        return text to ""
    }

    private fun parseHeaders(head: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (head.isEmpty()) return out
        var current: String? = null
        for (rawLine in head.split("\r\n", "\n")) {
            val line = rawLine
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // 折行：续到上一个头
                current?.let { out[it] = (out[it].orEmpty() + " " + line.trim()) }
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            out[name] = line.substring(colon + 1).trim()
            current = name
        }
        return out
    }

    private fun parseContentType(value: String): Pair<String, Map<String, String>> {
        val parts = splitParams(value)
        val mime = (parts.firstOrNull() ?: "").trim().lowercase()
        val params = HashMap<String, String>()
        for (part in parts.drop(1)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            var v = part.substring(eq + 1).trim()
            if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
            params[key] = v
        }
        return mime to params
    }

    private fun filenameOf(filename: String?, disposition: String): String {
        // RFC 2231：filename*=UTF-8''%E4%B8%AD.pdf
        val star = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
        val extended = star?.groupValues?.get(1)?.trim()?.trim('"')
        if (!extended.isNullOrEmpty()) {
            val charset = extended.substringBefore("'", "").ifEmpty { "utf-8" }
            val rest = extended.substringAfter("'", extended)
            val encoded = rest.substringAfter("'", rest)
            return decodeCharset(percentDecode(encoded), charset)
        }
        val plain = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.let { if (it[2].isNotEmpty()) it[2] else it[1].trim() }
        val picked = filename ?: plain
        return if (picked.isNullOrEmpty()) "" else decodeHeader(picked)
    }

    /** 按引号外的分号切参数，避免 boundary 里带分号被切坏 */
    private fun splitParams(value: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in value) {
            when {
                ch == '"' -> { quoted = !quoted; sb.append(ch) }
                ch == ';' && !quoted -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<String>()
        var index = body.indexOf(delimiter)
        while (index >= 0) {
            val after = index + delimiter.length
            if (body.startsWith("--", after)) break // 结束标记
            val lineEnd = body.indexOf('\n', after).let { if (it < 0) body.length else it + 1 }
            val next = body.indexOf(delimiter, lineEnd)
            val end = if (next < 0) body.length else next
            parts.add(body.substring(lineEnd, end))
            index = next
        }
        return parts
    }

    private fun decodeTransfer(body: String, encoding: String?): ByteArray {
        return when (encoding?.trim()?.lowercase()) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(body) }
                .getOrElse { body.toByteArray(Charsets.ISO_8859_1) }
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }
    }

    private fun decodeQuotedPrintable(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '=') {
                if (i + 1 < text.length && (text[i + 1] == '\r' || text[i + 1] == '\n')) {
                    // 软换行 =CRLF / =LF
                    i += if (i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n') 3 else 2
                    continue
                }
                if (i + 2 < text.length) {
                    val hi = hex(text[i + 1])
                    val lo = hex(text[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        out.write(hi * 16 + lo)
                        i += 3
                        continue
                    }
                }
            }
            out.write(ch.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    private fun percentDecode(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = hex(value[i + 1])
                val lo = hex(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            out.write(ch.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    /** RFC 2047 头解码；顺便把未编码的 8-bit 原文（UTF-8 / GBK）也救回来 */
    fun decodeHeader(value: String): String {
        if (value.isEmpty()) return value
        val matches = ENCODED_WORD.findAll(value).toList()
        if (matches.isEmpty()) return decodeRawText(value)

        val sb = StringBuilder()
        var cursor = 0
        for ((idx, m) in matches.withIndex()) {
            val gap = value.substring(cursor, m.range.first)
            // 两个相邻编码词之间的空白按规范要丢掉
            if (gap.isNotEmpty() && !(idx > 0 && gap.trim().isEmpty())) sb.append(decodeRawText(gap))
            val encoding = m.groupValues[2].uppercase()
            val charset = m.groupValues[1]
            val bytes = when (encoding) {
                "B" -> runCatching { Base64.getMimeDecoder().decode(m.groupValues[3]) }.getOrDefault(ByteArray(0))
                else -> decodeQuotedPrintable(m.groupValues[3].replace('_', ' '))
            }
            sb.append(decodeCharset(bytes, charset))
            cursor = m.range.last + 1
        }
        sb.append(decodeRawText(value.substring(cursor)))
        return sb.toString()
    }

    private fun decodeRawText(value: String): String {
        if (value.all { it.code < 128 }) return value
        // 头里直接塞 8-bit 的写法（国内邮箱常见），按 UTF-8 → GB18030 顺序试
        return decodeCharset(value.toByteArray(Charsets.ISO_8859_1), "utf-8")
    }

    private fun parseAddress(value: String): Pair<String, String> {
        val decoded = decodeHeader(value).trim()
        if (decoded.isEmpty()) return "" to ""
        val first = splitAddressList(decoded).firstOrNull() ?: decoded
        val angle = ANGLE_ADDR.find(first)
        return if (angle != null) {
            val name = first.substring(0, angle.range.first).trim().trim('"', '\'')
            name to angle.groupValues[1].trim()
        } else {
            "" to first.trim().trim('<', '>')
        }
    }

    /** 多收件人按逗号拆，但引号里的逗号不算分隔符 */
    private fun splitAddressList(value: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in value) {
            when {
                ch == '"' -> { quoted = !quoted; sb.append(ch) }
                ch == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun decodeCharset(bytes: ByteArray, charset: String?): String {
        if (bytes.isEmpty()) return ""
        val name = charset?.trim()?.lowercase().orEmpty()
        val preferred = when (name) {
            "", "utf-8", "utf8" -> Charsets.UTF_8
            "gb2312", "gbk", "gb18030", "x-gbk", "cp936" -> CHARSET_GB18030
            "us-ascii", "ascii" -> Charsets.US_ASCII
            else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
        strictDecode(bytes, preferred)?.let { return it }
        if (preferred != CHARSET_GB18030) strictDecode(bytes, CHARSET_GB18030)?.let { return it }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private val CHARSET_GB18030: Charset = Charset.forName("GB18030")

    private val DATE_PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
    )

    /** 解析 Date 头；失败返回 0，交给 IMAP INTERNALDATE 兜底 */
    fun parseDate(value: String): Long {
        val raw = value.trim()
        if (raw.isEmpty()) return 0
        for (pattern in DATE_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val parsed = runCatching { fmt.parse(raw) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0
    }
}
