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

/** LIST 返回的信箱：name 是服务器上的原始名字（Gmail 带 [Gmail]/ 前缀），flags 含 \Trash 等特殊用途。 */
data class ExtFolder(val name: String, val flags: List<String>)

/**
 * 只读 IMAP 客户端（客户端设备直连；Workers 撑不住 IMAP 长连接，所以拉取不放在服务端）。
 *
 * 拉取：EXAMINE INBOX（只读，不会改动已读标记）、列 UID、按 UID 取整封原文。
 * IDLE：SELECT INBOX 后再挂——QQ 只在读写选中的信箱上推新邮件 EXISTS。
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

    /** CAPABILITY 结果，决定能不能用 UID EXPUNGE 这类扩展命令 */
    private var capabilities: List<String> = emptyList()

    /**
     * [readOnly] 为 true 时用 EXAMINE（拉取邮件、不改任何标记）；
     * 为 false 时用 SELECT——QQ 邮箱的 IMAP 只在 SELECT 选中的信箱上推送
     * 新邮件 EXISTS，EXAMINE 下挂 IDLE 收不到新邮件事件（真机实测 2026-11）。
     */
    fun login(readOnly: Boolean = true): ExtMailboxInfo {
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
        capabilities = caps
        val idle = caps.any { it.uppercase().contains("IDLE") }

        if (server.needsId) {
            // 163 / 126：不发 ID 直接 LOGIN 会被判 Unsafe Login
            command("ID (\"name\" \"OTSMail\" \"version\" \"1.0\" \"vendor\" \"otscup\")")
        }

        val loginLines = command("LOGIN ${quote(address)} ${quote(secret)}")
        // 有的服务器把 UIDVALIDITY 放在 LOGIN 成功响应里，这里顺带读一下
        return examine("INBOX", idle, loginLines, readOnly)
    }

    /** 列 UID：从 startUid 开始（含），只取编号，不拉正文，避免一次下载太多 */
    fun listUids(startUid: Long): List<Long> {
        val from = if (startUid < 1) 1 else startUid
        val lines = command("UID FETCH $from:* (UID)")
        return lines.mapNotNull { line ->
            Regex("UID (\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull()
        }.distinct().sorted()
    }

    /**
     * 查信箱的下一个 UID。QQ/Foxmail 这类服务器实测即使 SELECT 了也只在 IDLE 上回
     * `+ idling`、不推新邮件 EXISTS，只能靠它兜底：每轮 IDLE 结束后比一次，
     * 变大就说明有新邮件。失败直接抛，由调用方重连。
     */
    fun statusUidNext(mailbox: String = "INBOX"): Long {
        val lines = command("STATUS ${quote(mailbox)} (UIDNEXT)")
        val line = lines.firstOrNull { it.contains("UIDNEXT", ignoreCase = true) }
            ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "STATUS 没有返回 UIDNEXT")
        return Regex("UIDNEXT (\\d+)", RegexOption.IGNORE_CASE).find(line)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "无法解析 UIDNEXT：$line")
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

    /**
     * 挂一轮 IMAP IDLE 等新邮件：最多等 [timeoutMs]。
     *
     * 返回 true = 服务器推送了 EXISTS/RECENT（有新邮件）；false = 等满超时、期间没有变化。
     * 两种结果下连接都已发过 DONE 并回到可发命令状态，调用方可以直接再挂一轮 IDLE，
     * 这也是 RFC 2177 要求的「29 分钟内重新 IDLE」的实现方式（这里的周期远小于 29 分钟）。
     * 连接断开/服务器不回 DONE 确认时抛 [ExtImapException]，由调用方重连。
     */
    fun idleWait(timeoutMs: Long, onLine: (String) -> Unit = {}): Boolean {
        val tag = nextTag()
        write("$tag IDLE")
        // 服务器先回 "+ idling" 才进入等待；个别服务器会先发别的 untagged 行，跳过即可
        while (true) {
            val line = readLine()
            onLine(line)
            if (line.startsWith("+")) break
            if (line.startsWith("$tag ")) throw describe(line)
        }

        val sock = socket
        val prevTimeout = sock?.soTimeout ?: READ_TIMEOUT_MS
        var newMail = false
        try {
            // 用 socket 读超时驱动「这一轮没有新邮件」：超时后 socket 仍可继续用
            sock?.soTimeout = timeoutMs.coerceAtLeast(1_000L).toInt()
            while (!newMail) {
                val line = readLine()
                onLine(line)
                val up = line.uppercase()
                if (up.contains("EXISTS") || up.contains("RECENT")) newMail = true
            }
        } catch (e: ExtImapException) {
            // 超时是正常收尾，其余（服务器断开/TLS 中断）交给调用方重连
            if (e.kind != ExtProbeFailure.TIMEOUT) throw e
        } finally {
            sock?.soTimeout = prevTimeout
            runCatching { write("DONE") }
        }
        drainIdle(tag)
        return newMail
    }

    /** 等 DONE 的 tag OK；等不到说明连接已不可用，抛出去让上层重连。 */
    private fun drainIdle(tag: String) {
        val sock = socket ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "连接未建立")
        val prev = sock.soTimeout
        sock.soTimeout = 20_000
        try {
            while (true) {
                val line = readLine()
                if (line.startsWith("$tag ")) {
                    if (!line.uppercase().contains(" OK")) throw describe(line)
                    return
                }
            }
        } catch (e: ExtImapException) {
            if (e.kind == ExtProbeFailure.TIMEOUT) {
                throw ExtImapException(ExtProbeFailure.TIMEOUT, "IDLE 结束未收到服务器确认")
            }
            throw e
        } finally {
            sock.soTimeout = prev
        }
    }

    // ---- 写操作（调用方必须先 login(readOnly = false)） ----

    fun supports(capability: String): Boolean =
        capabilities.any { it.uppercase().contains(capability.uppercase()) }

    /** 加/删一个系统标记，例如 \Seen、\Flagged。 */
    fun storeFlag(uid: Long, flag: String, add: Boolean) {
        val verb = if (add) "+FLAGS.SILENT" else "-FLAGS.SILENT"
        command("UID STORE $uid $verb ($flag)")
    }

    /** LIST 所有信箱，拿名字与特殊用途标记（\Trash 等） */
    fun listFolders(): List<ExtFolder> =
        command("LIST \"\" \"*\"").mapNotNull { parseFolderLine(it) }

    /**
     * 删除到服务器回收站：COPY 到 Trash → 标记 \Deleted → EXPUNGE。
     * 找不到 Trash 时直接抛错，绝不退化成「永久删除」。
     * 返回实际使用的回收站名字，便于日志/界面说明。
     */
    fun moveToTrash(uid: Long): String {
        val trash = findTrash()
            ?: throw ExtImapException(ExtProbeFailure.UNKNOWN, "服务器上找不到回收站（Trash），没有删除这封邮件")
        if (trash.equals("INBOX", ignoreCase = true)) {
            throw ExtImapException(ExtProbeFailure.UNKNOWN, "服务器把收件箱当回收站返回，已中止删除")
        }
        command("UID COPY $uid ${quote(trash)}")
        command("UID STORE $uid +FLAGS.SILENT (\\Deleted)")
        // 有 UIDPLUS 就只清这一封；没有则 EXPUNGE 会清掉信箱里所有 \Deleted（通常只有这一封）
        if (supports("UIDPLUS")) {
            command("UID EXPUNGE $uid")
        } else {
            command("EXPUNGE")
        }
        return trash
    }

    private fun findTrash(): String? {
        val folders = listFolders()
        folders.firstOrNull { f -> f.flags.any { it.equals("\\Trash", ignoreCase = true) } }?.let {
            return it.name
        }
        // 部分服务器不给 \Trash 特殊用途标记，只能按常见名字兜底
        val names = setOf(
            "trash", "deleted", "deleted messages", "deleted items", "bin",
            "已删除", "已删除邮件", "废件箱", "廢件箱",
        )
        return folders.firstOrNull {
            it.name.substringAfterLast('/').substringAfterLast('.').trim().lowercase() in names
        }?.name
    }

    private fun parseFolderLine(line: String): ExtFolder? {
        if (!line.startsWith("* LIST", ignoreCase = true)) return null
        val flags = Regex("\\* LIST \\(([^)]*)\\)", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)
            ?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        // 形式：* LIST (flags) "/" "Name with space" —— 先跳过 delimiter，剩下全是名字
        val rest = line.substringAfter(") ", "").trim()
        if (rest.isEmpty()) return null
        val delimEnd = if (rest.startsWith("\"")) rest.indexOf('"', 1) + 1 else rest.indexOf(' ')
        if (delimEnd <= 0) return null
        var name = rest.substring(delimEnd).trim()
        if (name.startsWith("\"") && name.endsWith("\"") && name.length >= 2) {
            name = name.substring(1, name.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        if (name.isEmpty()) return null
        return ExtFolder(name, flags)
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

    private fun examine(mailbox: String, idle: Boolean, extra: List<String>, readOnly: Boolean): ExtMailboxInfo {
        val verb = if (readOnly) "EXAMINE" else "SELECT"
        val lines = command("$verb ${quote(mailbox)}")
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
