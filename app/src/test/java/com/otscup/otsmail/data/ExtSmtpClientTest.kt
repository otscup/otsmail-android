package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * 覆盖 SMTP 命令序列（465 隐式 TLS / 587 STARTTLS）、AUTH 方式、信封收件人、
 * MIME 头与附件组装，以及地址解析。
 */
class ExtSmtpClientTest {

    private class FakeChannel(private val script: MutableList<String>) : SmtpChannel {
        val commands = mutableListOf<String>()
        var tlsUpgraded = false
        var closed = false

        override fun readLine(): String =
            if (script.isEmpty()) "250 OK" else script.removeAt(0)

        override fun writeLine(line: String) {
            commands += line
        }

        override fun upgradeTls(host: String) {
            tlsUpgraded = true
        }

        override fun close() {
            closed = true
        }
    }

    private fun client(channel: FakeChannel, secure: Boolean, port: Int = if (secure) 465 else 587) =
        ExtSmtpClient(
            host = "smtp.example.com",
            port = port,
            address = "me@example.com",
            secret = "SECRET-CODE",
            secure = secure,
            channelFactory = { _, _, _ -> channel },
        )

    private fun message(html: String = "<p>hi</p>", atts: List<SmtpAttachment> = emptyList()) =
        SmtpMessage(
            from = "me@example.com",
            to = listOf("a@example.org"),
            cc = listOf("c@example.org"),
            bcc = listOf("b@example.org"),
            subject = "测试主题",
            text = "hello",
            html = html,
            attachments = atts,
        )

    @Test
    fun `465 隐式 TLS 按 EHLO AUTH PLAIN MAIL RCPT DATA QUIT 顺序执行`() {
        val channel = FakeChannel(
            mutableListOf(
                "220 mx.example.com ready",
                "250-mx.example.com",
                "250-AUTH PLAIN LOGIN",
                "250 OK",
                "235 2.7.0 Authentication successful",
                "250 2.1.0 Sender OK",
                "250 2.1.5 Recipient OK",
                "250 2.1.5 Recipient OK",
                "250 2.1.5 Recipient OK",
                "354 End data with <CR><LF>.<CR><LF>",
                "250 2.0.0 queued",
                "221 Bye",
            ),
        )
        client(channel, secure = true).use { it.send(message()) }

        val cmds = channel.commands.filter { it != "." }
        assertEquals("EHLO OTSMail", cmds[0])
        assertTrue(cmds[1].startsWith("AUTH PLAIN "))
        assertEquals("MAIL FROM:<me@example.com>", cmds[2])
        assertEquals("RCPT TO:<a@example.org>", cmds[3])
        assertEquals("RCPT TO:<c@example.org>", cmds[4])
        assertEquals("RCPT TO:<b@example.org>", cmds[5])
        assertEquals("DATA", cmds[6])
        assertEquals("QUIT", cmds.last())
        assertFalse(channel.tlsUpgraded)
        assertTrue(channel.closed)
    }

    @Test
    fun `587 明文端口先 STARTTLS 再重新 EHLO 用 AUTH LOGIN`() {
        val channel = FakeChannel(
            mutableListOf(
                "220 mx.example.com ready",
                "250-mx.example.com",
                "250-STARTTLS",
                "250 OK",
                "220 Ready to start TLS",
                "250-mx.example.com",
                "250-AUTH LOGIN",
                "250 OK",
                "334 VXNlcm5hbWU6",
                "334 UGFzc3dvcmQ6",
                "235 2.7.0 Authentication successful",
                "250 Sender OK",
                "250 Recipient OK",
                "250 Recipient OK",
                "250 Recipient OK",
                "354 End data",
                "250 queued",
                "221 Bye",
            ),
        )
        client(channel, secure = false).use { it.send(message()) }

        val cmds = channel.commands.filter { it != "." }
        assertEquals("EHLO OTSMail", cmds[0])
        assertEquals("STARTTLS", cmds[1])
        assertEquals("EHLO OTSMail", cmds[2])
        assertEquals("AUTH LOGIN", cmds[3])
        assertEquals(Base64.getEncoder().encodeToString("me@example.com".toByteArray()), cmds[4])
        assertEquals(Base64.getEncoder().encodeToString("SECRET-CODE".toByteArray()), cmds[5])
        assertTrue(channel.tlsUpgraded)
        // 凭据绝不能出现在其他命令行里
        assertFalse(cmds.any { it.contains("SECRET-CODE") })
    }

    @Test
    fun `服务器不支持 STARTTLS 时直接失败不降级明文发送`() {
        val channel = FakeChannel(
            mutableListOf(
                "220 ready",
                "250-mx",
                "250 AUTH LOGIN",
            ),
        )
        try {
            client(channel, secure = false).use { it.send(message()) }
            fail("应当抛 STARTTLS 失败")
        } catch (e: ExtSmtpException) {
            assertEquals(ExtProbeFailure.TLS_FAILED, e.kind)
        }
        assertFalse(channel.commands.any { it.startsWith("MAIL FROM") })
    }

    @Test
    fun `认证失败映射为 AUTH_FAILED`() {
        val channel = FakeChannel(
            mutableListOf(
                "220 ready",
                "250-mx",
                "250-AUTH PLAIN",
                "250 OK",
                "535 5.7.8 Username and Password not accepted",
            ),
        )
        try {
            client(channel, secure = true).use { it.send(message()) }
            fail("应当抛认证失败")
        } catch (e: ExtSmtpException) {
            assertEquals(ExtProbeFailure.AUTH_FAILED, e.kind)
        }
    }

    @Test
    fun `MIME 组装包含编码主题 纯文本 HTML 与附件且不含密送头`() {
        val att = SmtpAttachment("中文 报告.pdf", "application/pdf", byteArrayOf(1, 2, 3, 4))
        val mime = SmtpMime.build(message(atts = listOf(att)), nowMs = 1_700_000_000_000L, boundarySeed = "fixed")

        assertTrue(mime.contains("Subject: =?UTF-8?B?"))
        assertTrue(mime.contains("Content-Type: multipart/mixed; boundary=\"OTSMail-fixed\""))
        assertTrue(mime.contains("Content-Type: multipart/alternative; boundary=\"OTSMail-alt-fixed\""))
        assertTrue(mime.contains("Content-Type: application/pdf"))
        assertTrue(mime.contains("filename=\"中文 报告.pdf\"") || mime.contains("filename*=UTF-8''"))
        assertTrue(mime.contains("Content-Transfer-Encoding: base64"))
        assertFalse(mime.contains("Bcc:"))
        assertFalse(mime.contains("b@example.org"))
        // 正文 base64 能被还原
        val plainB64 = mime.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")
            .substringBefore("\r\n--")
        assertEquals("hello", String(Base64.getMimeDecoder().decode(plainB64), Charsets.UTF_8))
        // 附件字节也按 base64 落进去
        assertTrue(mime.contains(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `地址解析支持显示名 引号 逗号列表`() {
        val p = MailAddress.parse("张三 <a@b.com>")
        assertEquals("张三", p?.name)
        assertEquals("a@b.com", p?.address)

        val q = MailAddress.parse("\"Li, Si\" <li@qq.com>")
        assertEquals("Li, Si", q?.name)
        assertEquals("li@qq.com", q?.address)

        assertEquals(null, MailAddress.parse("not-an-email"))
        assertEquals(null, MailAddress.parse("@x.com"))

        val list = MailAddress.list("\"Li, Si\" <li@qq.com>, z@163.com; c@outlook.com")
        assertEquals(listOf("li@qq.com", "z@163.com", "c@outlook.com"), list.map { it.address })
    }

    @Test
    fun `信封收件人合并 To Cc Bcc 并去重校验`() {
        val env = MailAddress.envelope(
            to = listOf("A <a@x.com>", "B <b@x.com>"),
            cc = listOf("a@x.com"),
            bcc = listOf("b@x.com", "c@x.com"),
        )
        assertEquals(listOf("a@x.com", "b@x.com", "c@x.com"), env)
    }

    @Test
    fun `非法收件人明确报错不会静默丢弃`() {
        try {
            MailAddress.envelope(listOf("a@x.com", "bad"), emptyList(), emptyList())
            fail("非法地址应当报错")
        } catch (e: ExtSmtpException) {
            assertTrue(e.message.orEmpty().contains("bad"))
        }
    }
}
