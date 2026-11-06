package com.otscup.otsmail.data

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** IMAP 层的失败分类，复用第一批探测用的枚举，界面/日志文案共用一套。 */
class ExtImapException(val kind: ExtProbeFailure, message: String) : Exception(message)

data class ExtMailboxInfo(
    val uidValidity: Long,
    val uidNext: Long,
    val exists: Int,
    val idle: Boolean,
)

data class ExtFetchedMail(
    val uid: Long,
    val seen: Boolean,
    val internalDateMs: Long,
    val raw: ByteArray,
)

/**
 * 只读 IMAP 客户端（客户端设备直连；Workers 撑不住 IMAP 长连接，所以拉取不放在服务端）。
 *
 * 只做拉取需要的三件事：EXAMINE INBOX（只读，不会改动已读标记）、列 UID、按 UID 取整封原文。
 * BODY.PEEK[] 保证不把未读改已读；163/126 登录前先发 ID，否则服务器回 Unsafe Login。
 *
 * 连接是阻塞 IO，调用方必须放在 Dispatchers.IO 上。
 */
class ExtImapClient(
    private val server: ExtServer,
    private val address: String,
    private val secret: String,
) : Closeable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var tagSeq = 0

    fun login(): ExtMailboxInfo {
        open()
        val greeting = readLine()
        if (!greeting.uppercase().contains("OK")) {
            throw ExtImapException(ExtProbeFailure.IMAP_DISABLED, greeting)
        }

        val caps = try {
            command("CAPABILITY")
        } catch (e: ExtImapException) {
            emptyList()
        }
        val idle = caps.any { it.uppercase().contains("IDLE") }

        if (server.needsId) {
            // 163 / 126：不发 ID 直接 LOGIN 会被判 Unsafe Login
            command("ID (\"name\" \"OTSMail\" \"version\" \"1.0\" \"vendor\" \"otscup\")")
        }

        val loginLines = command("LOGIN ${quote(address)} ${quote(secret)}")
        // 有的服务器把 UIDVALIDITY 放在 LOGIN 成功响应里，这里顺带读一下
        return examine("INBOX", idle, loginLines)
    }

    /** 列 UID：从 startUid 开始（含），只取编号，不拉正文，避免一次下载太多 */
    fun listUids(startUid: Long): List<Long> {
        val from = if (startUid < 1) 1 else startUid
        val lines = command("UID FETCH $from:* (UID)")
        return lines.mapNotNull { line ->
            Regex("UID (\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull()
        }.distinct().sorted()
    }

    fun fetch(uid: Long): ExtFetchedMail {
        val tag = nextTag()
        write("$tag UID FETCH $uid (UID FLAGS INTERNALDATE BODY.PEEK[])")

        var metaSeen = false
        var flags = ""
        var internalDate = ""
        var literal: ByteArray? = null

        while (true) {
            val line = readLine()
            if (line.startsWith("$tag ")) {
                if (!line.uppercase().contains(" OK")) throw describe(line)
                break
            }
            if (!metaSeen && line.contains("FETCH", ignoreCase = true)) metaSeen = true
            if (flags.isEmpty()) {
                Regex("FLAGS \\(([^)]*)\\)", RegexOption.IGNORE_CASE).find(line)?.let {
                    flags = it.groupValues[1]
                }
            }
            if (internalDate.isEmpty()) {
                Regex("INTERNALDATE \"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)?.let {
                    internalDate = it.groupValues[1]
                }
            }
            val literalSize = Regex("\\{(\\d+)\\}$").find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (literalSize != null && literal == null) {
                literal = readBytes(literalSize)
            }
        }

        val raw = literal ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "服务器没有返回邮件内容（UID $uid）")
        return ExtFetchedMail(
            uid = uid,
            seen = flags.contains("\\Seen", ignoreCase = true),
            internalDateMs = parseInternalDate(internalDate),
            raw = raw,
        )
    }

    override fun close() {
        runCatching {
            if (output != null && input != null) {
                write("${nextTag()} LOGOUT")
                // 不等响应，直接断开即可；这里不再读，避免被服务器拖着
            }
        }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ---- 内部 ----

    private fun open() {
        val s: Socket = try {
            val raw = Socket()
            raw.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            if (server.secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, server.host, server.port, true) as SSLSocket
                ssl.soTimeout = READ_TIMEOUT_MS
                ssl.startHandshake()
                ssl
            } else {
                raw
            }
        } catch (e: Throwable) {
            throw mapConnectError(e)
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    private fun examine(mailbox: String, idle: Boolean, extra: List<String>): ExtMailboxInfo {
        val lines = command("EXAMINE ${quote(mailbox)}")
        val all = extra + lines
        var validity = 0L
        var next = 0L
        var exists = 0
        for (line in all) {
            Regex("UIDVALIDITY (\\d+)", RegexOption.IGNORE_CASE).find(line)?.let {
                validity = it.groupValues[1].toLongOrNull() ?: validity
            }
            Regex("UIDNEXT (\\d+)", RegexOption.IGNORE_CASE).find(line)?.let {
                next = it.groupValues[1].toLongOrNull() ?: next
            }
            Regex("(\\d+) EXISTS", RegexOption.IGNORE_CASE).find(line)?.let {
                exists = it.groupValues[1].toIntOrNull() ?: exists
            }
        }
        return ExtMailboxInfo(validity, next, exists, idle)
    }

    private fun command(cmd: String): List<String> {
        val tag = nextTag()
        write("$tag $cmd")
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine()
            if (line.startsWith("$tag ")) {
                if (!line.uppercase().contains(" OK")) throw describe(line)
                return lines
            }
            lines.add(line)
        }
    }

    private fun nextTag(): String = "A${++tagSeq}"

    private fun write(line: String) {
        val out = output ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "连接未建立")
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun readLine(): String {
        val input = input ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "连接未建立")
        val sb = StringBuilder()
        while (true) {
            val b = try {
                input.read()
            } catch (e: Throwable) {
                throw mapReadError(e)
            }
            if (b < 0) throw ExtImapException(ExtProbeFailure.HOST_UNREACHABLE, "服务器关闭了连接")
            val ch = b.toChar()
            if (ch == '\n') break
            if (ch == '\r') continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun readBytes(size: Int): ByteArray {
        val input = input ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "连接未建立")
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = try {
                input.read(buf, offset, size - offset)
            } catch (e: Throwable) {
                throw mapReadError(e)
            }
            if (read < 0) throw ExtImapException(ExtProbeFailure.HOST_UNREACHABLE, "读取邮件内容时连接中断")
            offset += read
        }
        return buf
    }

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun describe(line: String): ExtImapException {
        val upper = line.uppercase()
        val kind = when {
            upper.contains("UNSAFE LOGIN") -> ExtProbeFailure.AUTH_FAILED
            upper.contains("DISABLED") || upper.contains("NOT ENABLED") -> ExtProbeFailure.IMAP_DISABLED
            upper.contains("AUTHENTICAT") || upper.contains("LOGIN") || upper.contains("PASSWORD") ||
                upper.contains("INVALID") || upper.contains("DENIED") -> ExtProbeFailure.AUTH_FAILED
            else -> ExtProbeFailure.UNKNOWN
        }
        return ExtImapException(kind, line.trim().take(180))
    }

    private fun mapConnectError(e: Throwable): ExtImapException = when (e) {
        is java.net.UnknownHostException -> ExtImapException(ExtProbeFailure.HOST_UNREACHABLE, "无法解析收件服务器地址")
        is java.net.ConnectException -> ExtImapException(ExtProbeFailure.HOST_UNREACHABLE, "连接不上收件服务器")
        is java.net.SocketTimeoutException -> ExtImapException(ExtProbeFailure.TIMEOUT, "连接收件服务器超时")
        is SSLException -> ExtImapException(ExtProbeFailure.TLS_FAILED, "TLS 握手失败：${e.message.orEmpty().take(120)}")
        else -> ExtImapException(ExtProbeFailure.UNKNOWN, e.message.orEmpty().take(120).ifEmpty { "连接失败" })
    }

    private fun mapReadError(e: Throwable): ExtImapException = when (e) {
        is java.net.SocketTimeoutException -> ExtImapException(ExtProbeFailure.TIMEOUT, "等待服务器响应超时")
        is SSLException -> ExtImapException(ExtProbeFailure.TLS_FAILED, "TLS 连接中断：${e.message.orEmpty().take(120)}")
        else -> ExtImapException(ExtProbeFailure.UNKNOWN, e.message.orEmpty().take(120).ifEmpty { "网络中断" })
    }

    private fun parseInternalDate(value: String): Long {
        if (value.isEmpty()) return 0
        val fmt = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return runCatching { fmt.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 90_000
    }
}
