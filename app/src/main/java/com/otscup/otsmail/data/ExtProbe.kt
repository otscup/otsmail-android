package com.otscup.otsmail.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 绑定前连通性验证：真的连一次 IMAP(993) 做 LOGIN，再连一次 SMTP 做 AUTH。
 * 只有两边都通过，服务端才允许绑定（POST /ext/bind 会校验 probe 结果）。
 *
 * 第一批在客户端直连邮箱服务器，凭据只在本进程内存里活一次，不落盘、不进日志。
 * 后续批次会把同样的探测挪到服务端/容器，客户端只负责收结果。
 */
enum class ExtProbeFailure {
    HOST_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    AUTH_FAILED,
    IMAP_DISABLED,
    SMTP_DISABLED,
    UNKNOWN,
}

data class ExtProbeResult(
    val imapOk: Boolean,
    val smtpOk: Boolean,
    val imapIdle: Boolean = false,
    val failure: ExtProbeFailure? = null,
    val detail: String = "",
    val hint: String = "",
    /** 实际验证通过的服务器：SMTP 465 失败后可能回退到 587，绑定要用实际端口 */
    val imapUsed: ExtServer? = null,
    val smtpUsed: ExtServer? = null,
) {
    val ok: Boolean get() = imapOk && smtpOk
}

object ExtProbe {

    private const val TIMEOUT_MS = 12_000

    private data class Step(
        val ok: Boolean,
        val idle: Boolean = false,
        val failure: ExtProbeFailure? = null,
        val detail: String = "",
    )

    suspend fun run(
        provider: ExtProvider,
        address: String,
        secret: String,
        manualImap: ExtServer? = null,
        manualSmtp: ExtServer? = null,
    ): ExtProbeResult = withContext(Dispatchers.IO) {
        val imapServer = manualImap ?: provider.imap
        val smtpServer = manualSmtp ?: provider.smtp

        val imap = try {
            imapLogin(imapServer, address, secret)
        } catch (t: Throwable) {
            Step(false, failure = classifyThrowable(t), detail = t.messageOrType())
        }
        if (!imap.ok) {
            return@withContext ExtProbeResult(
                imapOk = false,
                smtpOk = false,
                imapIdle = imap.idle,
                failure = imap.failure,
                detail = imap.detail,
                hint = hintOf(imap.failure),
            )
        }

        val smtp = smtpAuthWithFallback(smtpServer, address, secret)
        ExtProbeResult(
            imapOk = true,
            smtpOk = smtp.first.ok,
            imapIdle = imap.idle,
            failure = smtp.first.failure,
            detail = smtp.first.detail,
            hint = hintOf(smtp.first.failure),
            imapUsed = imapServer,
            smtpUsed = smtp.second,
        )
    }

    /**
     * 465 隐式 TLS 先试；握手被断开/超时时回退到 587 STARTTLS。
     * 依据：Gmail、Outlook 等同时提供 465 与 587，部分网络只放行其中一个；
     * 认证失败（密码错）不做回退，避免把「授权码错误」误报成加密方式问题。
     */
    private fun smtpAuthWithFallback(
        server: ExtServer,
        address: String,
        secret: String,
    ): Pair<Step, ExtServer> {
        val first = try {
            smtpAuth(server, address, secret)
        } catch (t: Throwable) {
            Step(false, failure = classifyThrowable(t), detail = t.messageOrType())
        }
        val canFallback = server.secure && server.port == 465 &&
            (first.failure == ExtProbeFailure.TLS_FAILED ||
                first.failure == ExtProbeFailure.TIMEOUT ||
                first.failure == ExtProbeFailure.HOST_UNREACHABLE)
        if (!canFallback) return first to server

        val alt = ExtServer(server.host, 587, false)
        val second = try {
            smtpAuth(alt, address, secret)
        } catch (t: Throwable) {
            Step(false, failure = classifyThrowable(t), detail = t.messageOrType())
        }
        return if (second.ok) second to alt else first to server
    }

    /** 错误分类直接决定界面文案，必须能区分「连不上 / 认证失败 / 未开 IMAP」。 */
    private fun hintOf(failure: ExtProbeFailure?): String = when (failure) {
        ExtProbeFailure.HOST_UNREACHABLE ->
            "连不上服务器：检查邮箱地址是否正确、网络能否访问该邮箱的服务器。"
        ExtProbeFailure.TIMEOUT ->
            "连接超时：网络较慢或端口被拦截，换网络后再试。"
        ExtProbeFailure.TLS_FAILED ->
            "TLS 握手失败（不是密码问题）：加密握手阶段被服务器或网络断开。" +
                "多为网络/代理拦截了该端口（993 隐式 TLS、587 STARTTLS），先换网络再试；" +
                "Gmail 还必须使用应用专用密码，不能填 Google 账号密码。"
        ExtProbeFailure.AUTH_FAILED ->
            "认证失败：授权码 / 应用密码不对，或该邮箱不允许第三方客户端登录。"
        ExtProbeFailure.IMAP_DISABLED ->
            "IMAP 未开启：请先在网页版邮箱设置里开启 IMAP/SMTP 服务，再重新生成授权码。"
        ExtProbeFailure.SMTP_DISABLED ->
            "SMTP 未开启：收信已通，但网页版里还没开启 SMTP 服务，开启后再试。"
        ExtProbeFailure.UNKNOWN, null -> ""
    }

    private fun classifyThrowable(t: Throwable): ExtProbeFailure = when (t) {
        is UnknownHostException, is ConnectException -> ExtProbeFailure.HOST_UNREACHABLE
        is SocketTimeoutException -> ExtProbeFailure.TIMEOUT
        is SSLException -> ExtProbeFailure.TLS_FAILED
        else -> ExtProbeFailure.UNKNOWN
    }

    private fun Throwable.messageOrType(): String =
        (message ?: this::class.java.simpleName).take(160)

    private fun open(host: String, port: Int, secure: Boolean): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        raw.soTimeout = TIMEOUT_MS
        if (!secure) return raw
        return ExtTls.wrap(raw, host, port, TIMEOUT_MS)
    }

    // ---------------- IMAP ----------------

    private fun imapLogin(server: ExtServer, address: String, secret: String): Step {
        var socket = open(server.host, server.port, server.secure)
        try {
            var r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val greeting = r.readLine() ?: return Step(false, failure = ExtProbeFailure.UNKNOWN, detail = "服务器没有返回问候语")
            if (greeting.startsWith("* BYE", ignoreCase = true)) {
                return Step(false, failure = ExtProbeFailure.IMAP_DISABLED, detail = greeting.take(160))
            }

            // 非 993 端口（如 143）：先 STARTTLS，凭据不能发在明文连接上
            if (!server.secure) {
                send(w, "a0", "STARTTLS")
                val tls = readUntil(r, "a0")
                if (!tls.lineSequence().any { it.startsWith("a0 OK", ignoreCase = true) }) {
                    return Step(false, failure = ExtProbeFailure.TLS_FAILED, detail = tls.trim().take(220))
                }
                socket = try {
                    ExtTls.wrap(socket, server.host, server.port, TIMEOUT_MS)
                } catch (t: Throwable) {
                    return Step(false, failure = classifyThrowable(t), detail = t.messageOrType())
                }
                r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            }

            var caps = greeting
            send(w, "a1", "CAPABILITY")
            caps += "\n" + readUntil(r, "a1")

            // 163/126 要求先发 ID，否则后面的 LOGIN 会回 Unsafe Login
            if (server.needsId) {
                send(w, "a2", "ID (\"name\" \"OTSMail\" \"version\" \"1.0\")")
                readUntil(r, "a2")
            }

            send(w, "a3", "LOGIN ${quote(address)} ${quote(secret)}")
            val login = readUntil(r, "a3")
            val ok = login.lineSequence().any { it.startsWith("a3 OK", ignoreCase = true) }

            runCatching {
                send(w, "a4", "LOGOUT")
                readUntil(r, "a4")
            }

            if (!ok) {
                return Step(false, failure = classifyImap(login), detail = login.trim().take(220))
            }
            return Step(true, idle = caps.uppercase().contains("IDLE"), detail = "IMAP 登录成功")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun classifyImap(text: String): ExtProbeFailure {
        val lower = text.lowercase()
        return when {
            lower.contains("unsafe login") -> ExtProbeFailure.AUTH_FAILED
            lower.contains("disabled") || lower.contains("not open") ||
                lower.contains("未开启") || lower.contains("关闭") -> ExtProbeFailure.IMAP_DISABLED
            lower.contains("authentication") || lower.contains("login fail") ||
                lower.contains("invalid") || lower.contains("password") ||
                lower.contains("认证") || lower.contains("no ") -> ExtProbeFailure.AUTH_FAILED
            else -> ExtProbeFailure.AUTH_FAILED
        }
    }

    // ---------------- SMTP ----------------

    private fun smtpAuth(server: ExtServer, address: String, secret: String): Step {
        var socket = open(server.host, server.port, server.secure)
        try {
            var r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val (greetCode, greetText) = readSmtp(r)
            if (greetCode != 220) {
                return Step(false, failure = ExtProbeFailure.SMTP_DISABLED, detail = greetText.trim().take(160))
            }

            sendSmtp(w, "EHLO OTSMail")
            val (ehloCode, ehloText) = readSmtp(r)
            if (ehloCode != 250) {
                return Step(false, failure = ExtProbeFailure.SMTP_DISABLED, detail = ehloText.trim().take(160))
            }
            // STARTTLS 之后服务器才会把 AUTH 能力完整列出来，所以能力文本要跟着换
            var capsText = ehloText

            if (!server.secure) {
                sendSmtp(w, "STARTTLS")
                val (tlsCode, tlsText) = readSmtp(r)
                if (tlsCode != 220) {
                    return Step(false, failure = ExtProbeFailure.TLS_FAILED, detail = tlsText.trim().take(160))
                }
                socket = ExtTls.wrap(socket, server.host, server.port, TIMEOUT_MS)
                r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                sendSmtp(w, "EHLO OTSMail")
                val (_, tlsEhloText) = readSmtp(r)
                capsText = tlsEhloText
            }

            if (!capsText.uppercase().contains("AUTH")) {
                return Step(false, failure = ExtProbeFailure.SMTP_DISABLED, detail = "服务器没有提供 AUTH 能力")
            }

            sendSmtp(w, "AUTH LOGIN")
            val (a1, t1) = readSmtp(r)
            if (a1 != 334) return Step(false, failure = ExtProbeFailure.AUTH_FAILED, detail = t1.trim().take(160))

            sendSmtp(w, Base64.encodeToString(address.toByteArray(), Base64.NO_WRAP))
            val (a2, t2) = readSmtp(r)
            if (a2 != 334) return Step(false, failure = ExtProbeFailure.AUTH_FAILED, detail = t2.trim().take(160))

            sendSmtp(w, Base64.encodeToString(secret.toByteArray(), Base64.NO_WRAP))
            val (a3, t3) = readSmtp(r)
            runCatching { sendSmtp(w, "QUIT") }

            if (a3 != 235) {
                val failure = when (a3) {
                    530, 534 -> ExtProbeFailure.SMTP_DISABLED
                    else -> ExtProbeFailure.AUTH_FAILED
                }
                return Step(false, failure = failure, detail = t3.trim().take(160))
            }
            return Step(true, detail = "SMTP 认证成功")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------- 协议小工具 ----------------

    private fun send(w: BufferedWriter, tag: String, command: String) {
        w.write("$tag $command\r\n")
        w.flush()
    }

    private fun sendSmtp(w: BufferedWriter, command: String) {
        w.write("$command\r\n")
        w.flush()
    }

    private fun readUntil(r: BufferedReader, tag: String): String {
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            sb.append(line).append('\n')
            if (line.startsWith("$tag ", ignoreCase = true)) break
        }
        return sb.toString()
    }

    private fun readSmtp(r: BufferedReader): Pair<Int, String> {
        val sb = StringBuilder()
        var code = 0
        while (true) {
            val line = r.readLine() ?: break
            sb.append(line).append('\n')
            if (line.length < 4) break
            if (code == 0) code = line.substring(0, 3).toIntOrNull() ?: 0
            if (line[3] == ' ') break
        }
        return code to sb.toString()
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
