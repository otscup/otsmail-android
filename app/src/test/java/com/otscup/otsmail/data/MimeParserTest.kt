package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Base64

/**
 * 编码覆盖：GBK / GB18030 正文、base64、quoted-printable 软换行、
 * RFC 2047（B/Q）、RFC 2231 文件名、嵌套 multipart。
 */
class MimeParserTest {

    private val gb18030: Charset = Charset.forName("GB18030")

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64(text: String, charset: Charset): String = b64(text.toByteArray(charset))

    @Test
    fun `解析 GBK 主题 发件人 与嵌套正文`() {
        val raw = """
            From: =?GBK?B?${b64("张三", Charset.forName("GBK"))}?= <zhangsan@163.com>
            To: "Li Si" <lisi@qq.com>
            Subject: =?GB18030?B?${b64("测试邮件", gb18030)}?=
            Date: Wed, 06 Nov 2026 10:00:00 +0800
            Message-ID: <abc123@mail.163.com>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="BOUND"

            --BOUND
            Content-Type: multipart/alternative; boundary="ALT"

            --ALT
            Content-Type: text/plain; charset=GBK
            Content-Transfer-Encoding: base64

            ${b64("正文第一行\n第二行", Charset.forName("GBK"))}
            --ALT
            Content-Type: text/html; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            <p>=E4=BD=A0=E5=A5=BD</p>
            --ALT--
            --BOUND
            Content-Type: application/pdf
            Content-Disposition: attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf
            Content-Transfer-Encoding: base64

            ${b64("%PDF-1.4\n".toByteArray())}
            --BOUND--
        """.trimIndent().replace("\n", "\r\n")

        val mail = MimeParser.parse(raw.toByteArray(Charsets.UTF_8), fallbackDateMs = 0)

        assertEquals("测试邮件", mail.subject)
        assertEquals("张三", mail.fromName)
        assertEquals("zhangsan@163.com", mail.fromAddr)
        assertEquals("Li Si", mail.toName)
        assertEquals("lisi@qq.com", mail.toAddr)
        assertEquals("<abc123@mail.163.com>", mail.messageId)
        assertTrue(mail.text.contains("正文第一行"))
        assertTrue(mail.text.contains("第二行"))
        assertTrue(mail.html.contains("你好"))
        assertEquals(1, mail.attachments.size)
        assertEquals("报告.pdf", mail.attachments[0].filename)
        assertEquals("application/pdf", mail.attachments[0].mimeType)
        assertEquals(9, mail.attachments[0].size)
        assertNotNull(mail.attachments[0].data)
    }

    @Test
    fun `RFC2047 Q 编码`() {
        assertEquals("你好", MimeParser.decodeHeader("=?utf-8?Q?=E4=BD=A0=E5=A5=BD?="))
    }

    @Test
    fun `相邻编码词之间空白被丢弃`() {
        assertEquals("你好世界", MimeParser.decodeHeader("=?utf-8?B?5L2g5aW9?= =?utf-8?B?5LiW55WM?="))
    }

    @Test
    fun `quoted-printable 软换行与转义`() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n\r\n" +
            "abc=\r\ndef=20ghi"
        val mail = MimeParser.parse(raw.toByteArray(Charsets.UTF_8))
        assertEquals("abcdef ghi", mail.text)
    }

    @Test
    fun `日期头解析成同一时刻`() {
        val utc = MimeParser.parseDate("Wed, 06 Nov 2026 02:00:00 +0000")
        val shanghai = MimeParser.parseDate("Wed, 06 Nov 2026 10:00:00 +0800")
        assertEquals(utc, shanghai)
        assertTrue(utc > 0)
    }

    @Test
    fun `纯文本兜底与摘要`() {
        val raw = "Subject: hi\r\n\r\nhello world".toByteArray()
        val mail = MimeParser.parse(raw)
        assertEquals("hello world", mail.text)
        assertEquals("hi", mail.subject)
        assertEquals("hello world", MimeParser.snippet(mail.text, mail.html))
    }

    @Test
    fun `空邮件不崩`() {
        val mail = MimeParser.parse(ByteArray(0), fallbackDateMs = 123L)
        assertEquals(123L, mail.dateMs)
        assertTrue(mail.attachments.isEmpty())
    }
}
