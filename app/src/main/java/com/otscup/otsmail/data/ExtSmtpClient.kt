package com.otscup.otsmail.data

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** SMTP 发送失败分类，沿用绑定探测那套枚举，界面文案统一。 */
class ExtSmtpException(val kind: ExtProbeFailure, message: String) : Exception(message)

data class SmtpAttachment(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class SmtpMessage(
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val text: String,
    val html: String,
    val attachments: List<SmtpAttachment> = emptyList(),
    /** 调用方预生成的 Message-ID（含域名部分），空则按边界种子生成 */
    val messageId: String = "",
)

/**
 * 一行一条命令的 SMTP 收发抽象。
 * 单测注入脚本化响应用它验证 EHLO / STARTTLS / AUTH / MAIL / RCPT / DATA 的命令序列，
 * 不真的连邮箱服务器。
 */
interface SmtpChannel : Closeable {
    fun readLine(): String
    fun writeLine(line: String)
    /** 明文端口（587）STARTTLS 升级：在已连接 socket 上重新包装 TLS。 */
    fun upgradeTls(host: String)
}

/** 真实 socket 实现；465 在连接时直接 TLS，587 先明文再 STARTTLS。 */
class SocketSmtpChannel private constructor(private var socket: Socket) : SmtpChannel {

    private var input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private var output = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    override fun readLine(): String = input.readLine() ?: throw ExtSmtpException(
        ExtProbeFailure.HOST_UNREACHABLE, "发件服务器关闭了连接"
    )

    override fun writeLine(line: String) {
        output.write(line)
        output.write("\r\n")
        output.flush()
    }

    override fun upgradeTls(host: String) {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.soTimeout = READ_TIMEOUT_MS
        ssl.useClientMode = true
        ssl.startHandshake()
        socket = ssl
        input = BufferedReader(InputStreamReader(ssl.getInputStream(), Charsets.UTF_8))
        output = OutputStreamWriter(ssl.getOutputStream(), Charsets.UTF_8)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 90_000

        fun connect(host: String, port: Int, secure: Boolean): SocketSmtpChannel {
            val raw = try {
                Socket().apply {
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
            } catch (e: Throwable) {
                throw mapConnectError(e)
            }
            if (!secure) return SocketSmtpChannel(raw)
            val ssl = try {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
            } catch (e: Throwable) {
                runCatching { raw.close() }
                throw mapConnectError(e)
            }
            return try {
                ssl.soTimeout = READ_TIMEOUT_MS
                ssl.useClientMode = true
                ssl.startHandshake()
                SocketSmtpChannel(ssl)
            } catch (e: Throwable) {
                runCatching { ssl.close() }
                throw mapConnectError(e)
            }
        }

        private fun mapConnectError(e: Throwable): ExtSmtpException = when (e) {
            is UnknownHostException -> ExtSmtpException(ExtProbeFailure.HOST_UNREACHABLE, "无法解析发件服务器地址")
            is java.net.ConnectException -> ExtSmtpException(ExtProbeFailure.HOST_UNREACHABLE, "连接不上发件服务器")
            is SocketTimeoutException -> ExtSmtpException(ExtProbeFailure.TIMEOUT, "连接发件服务器超时")
            is SSLException -> ExtSmtpException(ExtProbeFailure.TLS_FAILED, "TLS 握手失败：${e.message.orEmpty().take(120)}")
            else -> ExtSmtpException(ExtProbeFailure.UNKNOWN, e.message.orEmpty().take(120).ifEmpty { "连接失败" })
        }
    }
}

/**
 * 第三方邮箱直连 SMTP 发信（不经 otsmail 后端代发）。
 *
 * 端口规则与绑定探测一致：465 = 隐式 TLS；其它端口（587 等）必须 STARTTLS，
 * 服务器没提供 STARTTLS 时直接失败，绝不降级成明文传输。
 * AUTH 优先 PLAIN（服务器声明支持时），否则 LOGIN；口令只在内存里用于这一次连接。
 */
class ExtSmtpClient(
    private val host: String,
    private val port: Int,
    private val address: String,
    private val secret: String,
    private val secure: Boolean = port == 465,
    private val channelFactory: (String, Int, Boolean) -> SmtpChannel = { h, p, s ->
        SocketSmtpChannel.connect(h, p, s)
    },
) : Closeable {

    private var channel: SmtpChannel? = null

    fun send(message: SmtpMessage) {
        val ch = channelFactory(host, port, secure).also { channel = it }
        try {
            val greeting = readResponse()
            expect(greeting, 220, "服务器问候")

            var caps = ehlo(ch)

            if (!secure) {
                val startTls = execute(ch, "STARTTLS")
                if (startTls.code != 220) {
                    throw ExtSmtpException(
                        ExtProbeFailure.TLS_FAILED,
                        "服务器不支持 STARTTLS（${startTls.text.trim().take(120)}），已中止发送",
                    )
                }
                ch.upgradeTls(host)
                caps = ehlo(ch)
            }

            authenticate(ch, caps)

            command(ch, "MAIL FROM:<${message.from}>", setOf(250), "发件人被拒绝")

            val recipients = MailAddress.envelope(message.to, message.cc, message.bcc)
            for (rcpt in recipients) {
                command(ch, "RCPT TO:<$rcpt>", setOf(250, 251, 252), "收件人被拒绝：$rcpt")
            }

            val data = execute(ch, "DATA")
            if (data.code != 354) throw failureFor(data, "服务器不接受邮件内容")

            val mime = SmtpMime.build(message, System.currentTimeMillis())
            writeData(ch, mime)

            val submitted = readResponse()
            expect(submitted, 250, "邮件提交")

            runCatching {
                ch.writeLine("QUIT")
                readResponse()
            }
        } finally {
            channel = null
            runCatching { ch.close() }
        }
    }

    override fun close() {
        channel?.let { runCatching { it.close() } }
        channel = null
    }

    // ---- 协议细节 ----

    private fun ehlo(ch: SmtpChannel): String {
        // EHLO 的响应是多行能力列表，按整段返回给 AUTH 能力判断
        ch.writeLine("EHLO OTSMail")
        val resp = readResponse()
        if (resp.code != 250) throw failureFor(resp, "服务器不接受 EHLO")
        return resp.text
    }

    private fun authenticate(ch: SmtpChannel, caps: String) {
        val upper = caps.uppercase()
        if (!upper.contains("AUTH")) {
            throw ExtSmtpException(ExtProbeFailure.SMTP_DISABLED, "服务器没有提供 AUTH 能力")
        }
        val preferPlain = Regex("AUTH[^\\r\\n]*\\bPLAIN\\b").containsMatchIn(upper)
        if (preferPlain) {
            val token = Base64.getEncoder()
                .encodeToString("\u0000$address\u0000$secret".toByteArray(Charsets.UTF_8))
            val resp = execute(ch, "AUTH PLAIN $token")
            if (resp.code != 235) throw authFailure(resp)
            return
        }

        var resp = execute(ch, "AUTH LOGIN")
        if (resp.code != 334) throw authFailure(resp)
        resp = execute(ch, Base64.getEncoder().encodeToString(address.toByteArray(Charsets.UTF_8)))
        if (resp.code != 334) throw authFailure(resp)
        resp = execute(ch, Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8)))
        if (resp.code != 235) throw authFailure(resp)
    }

    private fun command(ch: SmtpChannel, line: String, ok: Set<Int>, what: String) {
        val resp = execute(ch, line)
        if (resp.code !in ok) throw failureFor(resp, what)
    }

    private fun execute(ch: SmtpChannel, line: String): Response {
        ch.writeLine(line)
        return readResponse()
    }

    private fun readResponse(): Response {
        val ch = channel ?: throw ExtSmtpException(ExtProbeFailure.UNKNOWN, "连接未建立")
        val lines = mutableListOf<String>()
        var code = 0
        while (true) {
            val line = try {
                ch.readLine()
            } catch (e: ExtSmtpException) {
                throw e
            } catch (e: Throwable) {
                throw mapReadError(e)
            }
            lines += line
            if (line.length < 4) break
            if (code == 0) code = line.substring(0, 3).toIntOrNull() ?: 0
            if (line[3] == ' ') break
        }
        return Response(code, lines.joinToString("\n"))
    }

    /** DATA 阶段：逐行补齐 CRLF，并做 SMTP 点填充（行首单独的 '.' 再加一个 '.'）。 */
    private fun writeData(ch: SmtpChannel, mime: String) {
        val normalized = mime.replace("\r\n", "\n").split("\n")
        for (line in normalized) {
            ch.writeLine(if (line.startsWith(".")) ".$line" else line)
        }
        ch.writeLine(".")
    }

    private fun expect(resp: Response, code: Int, what: String) {
        if (resp.code != code) throw failureFor(resp, what)
    }

    private fun failureFor(resp: Response, what: String): ExtSmtpException {
        val detail = resp.text.lineSequence()
            .firstOrNull { it.length >= 4 }
            ?.substring(4)
            ?.trim()
            .orEmpty()
            .take(160)
        val kind = when (resp.code) {
            421, 450, 451, 452 -> ExtProbeFailure.TIMEOUT
            530, 534, 535 -> ExtProbeFailure.AUTH_FAILED
            550, 551, 552, 553, 554 -> ExtProbeFailure.SMTP_DISABLED
            else -> ExtProbeFailure.UNKNOWN
        }
        val fallback = when (kind) {
            ExtProbeFailure.AUTH_FAILED -> "授权码被拒绝或账号不允许 SMTP 发信"
            ExtProbeFailure.TIMEOUT -> "服务器限流或临时不可用，请稍后重试"
            ExtProbeFailure.SMTP_DISABLED -> "服务器拒绝了这次投递"
            else -> what
        }
        return ExtSmtpException(kind, "$what（${resp.code} ${detail.ifEmpty { fallback }}）")
    }

    private fun authFailure(resp: Response): ExtSmtpException =
        failureFor(resp, "SMTP 认证失败")

    private fun mapReadError(e: Throwable): ExtSmtpException = when (e) {
        is SocketTimeoutException -> ExtSmtpException(ExtProbeFailure.TIMEOUT, "等待发件服务器响应超时")
        is SSLException -> ExtSmtpException(ExtProbeFailure.TLS_FAILED, "TLS 连接中断：${e.message.orEmpty().take(120)}")
        else -> ExtSmtpException(ExtProbeFailure.UNKNOWN, e.message.orEmpty().take(120).ifEmpty { "网络中断" })
    }

    private data class Response(val code: Int, val text: String)
}

/**
 * 地址解析 / 信封收件人归一化。
 * 支持 `Name <a@b.com>`、`"Name, Jr" <a@b.com>`、裸地址，逗号分号分隔的列表。
 * 任何非法地址都明确报错，不会被静默丢掉。
 */
object MailAddress {

    data class Parsed(val name: String, val address: String)

    private val ADDR = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")

    fun parse(raw: String): Parsed? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val angle = Regex("<([^>]+)>").find(s)
        val addr = (angle?.groupValues?.get(1) ?: s).trim().trim('<', '>')
        if (!ADDR.matches(addr)) return null
        val name = if (angle != null) s.substring(0, angle.range.first).trim().trim('"', '\'') else ""
        return Parsed(name, addr)
    }

    /** 逗号 / 分号分隔，引号里的分隔符不算。 */
    fun list(raw: String): List<Parsed> {
        if (raw.isBlank()) return emptyList()
        val pieces = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in raw) {
            when {
                ch == '"' -> { quoted = !quoted; sb.append(ch) }
                (ch == ',' || ch == ';') && !quoted -> { pieces += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) pieces += sb.toString()
        return pieces.mapNotNull { parse(it) }
    }

    fun parseOne(raw: String): Parsed {
        val s = raw.trim()
        return parse(s) ?: throw ExtSmtpException(
            ExtProbeFailure.UNKNOWN, "邮箱格式不正确：$s"
        )
    }

    /** 信封收件人：去重（忽略大小写）+ 校验，To/Cc/Bcc 合并。 */
    fun envelope(to: List<String>, cc: List<String>, bcc: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<String>()
        (to + cc + bcc).forEach { raw ->
            val addr = parseOne(raw).address
            if (seen.add(addr.lowercase(Locale.US))) out += addr
        }
        if (out.isEmpty()) throw ExtSmtpException(ExtProbeFailure.UNKNOWN, "请填写收件人")
        return out
    }
}

/**
 * MIME 组装（RFC 5322 / 2045）。
 *
 * 结构固定为 multipart/mixed，内层 multipart/alternative（纯文本 + HTML），
 * 附件按 base64 逐块编码；所有非 ASCII 头都用 RFC 2047 编码，正文用 base64，
 * 避免收件端按旧 charset 解出乱码。
 */
object SmtpMime {

    private const val CRLF = "\r\n"
    private const val MAX_BYTES = 25L * 1024 * 1024

    fun build(message: SmtpMessage, nowMs: Long, boundarySeed: String = UUID.randomUUID().toString()): String {
        val from = MailAddress.parseOne(message.from)
        val to = MailAddress.list(message.to.joinToString(", "))
        val cc = MailAddress.list(message.cc.joinToString(", "))
        if (to.isEmpty() && cc.isEmpty()) throw ExtSmtpException(ExtProbeFailure.UNKNOWN, "请填写收件人")
        val totalAtt = message.attachments.sumOf { it.bytes.size.toLong() }
        if (totalAtt > 15L * 1024 * 1024) {
            throw ExtSmtpException(ExtProbeFailure.SMTP_DISABLED, "附件累计超过 15MB，服务器可能拒收")
        }

        val mixed = "OTSMail-$boundarySeed"
        val alt = "OTSMail-alt-$boundarySeed"
        val domain = from.address.substringAfter('@', "otsmail.local").ifEmpty { "otsmail.local" }

        val sb = StringBuilder()
        fun line(s: String = "") {
            sb.append(s).append(CRLF)
        }

        // 没有显示名时不写空引号，避免收件端显示成 "" <a@b>
        line(if (from.name.isEmpty()) "From: <${from.address}>" else "From: ${encodeHeader(from.name)} <${from.address}>")
        if (to.isNotEmpty()) line("To: " + to.joinToString(", ") { headerAddress(it) })
        if (cc.isNotEmpty()) line("Cc: " + cc.joinToString(", ") { headerAddress(it) })
        line("Subject: ${encodeHeader(sanitizeHeader(message.subject))}")
        line("Date: ${rfcDate(nowMs)}")
        line("Message-ID: <${message.messageId.ifBlank { "$boundarySeed@$domain" }}>")
        line("MIME-Version: 1.0")
        line("Content-Type: multipart/mixed; boundary=\"$mixed\"")
        line()

        line("--$mixed")
        line("Content-Type: multipart/alternative; boundary=\"$alt\"")
        line()
        line("--$alt")
        line("Content-Type: text/plain; charset=UTF-8")
        line("Content-Transfer-Encoding: base64")
        line()
        base64(message.text.ifEmpty { " " }, sb)
        line("--$alt")
        line("Content-Type: text/html; charset=UTF-8")
        line("Content-Transfer-Encoding: base64")
        line()
        base64(message.html.ifEmpty { " " }, sb)
        line("--$alt--")
        line()

        for (att in message.attachments) {
            line("--$mixed")
            line("Content-Type: ${att.mimeType.ifBlank { "application/octet-stream" }}")
            line("Content-Transfer-Encoding: base64")
            line("Content-Disposition: attachment; ${filenameParam(att.filename)}")
            line()
            base64Bytes(att.bytes, sb)
        }
        line("--$mixed--")

        if (sb.length.toLong() > MAX_BYTES) {
            throw ExtSmtpException(ExtProbeFailure.SMTP_DISABLED, "整封邮件超过 25MB，服务器会拒收")
        }
        return sb.toString()
    }

    private fun base64(text: String, sb: StringBuilder) {
        base64Bytes(text.toByteArray(Charsets.UTF_8), sb)
    }

    private fun base64Bytes(bytes: ByteArray, sb: StringBuilder) {
        val encoded = Base64.getMimeEncoder(76, CRLF.toByteArray()).encodeToString(bytes)
        sb.append(encoded).append(CRLF)
    }

    private fun headerAddress(p: MailAddress.Parsed): String =
        if (p.name.isEmpty()) "<${p.address}>" else "${encodeHeader(p.name)} <${p.address}>"

    private fun filenameParam(filename: String): String {
        val safe = sanitizeHeader(filename.ifBlank { "attachment" })
        val ascii = safe.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
        return "filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun sanitizeHeader(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    fun encodeHeader(value: String): String {
        val v = sanitizeHeader(value)
        if (v.isEmpty()) return ""
        if (v.all { it.code in 32..126 }) return v
        val bytes = v.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "=?UTF-8?B?$encoded?="
    }

    fun rfcDate(ms: Long): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return fmt.format(Date(ms))
    }
}
