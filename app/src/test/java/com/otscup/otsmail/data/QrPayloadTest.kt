package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {

    private val id = "0123456789abcdef0123456789abcdef"

    private fun body(type: String = QrPayload.TYPE, value: String = id): String =
        """{"type":"$type","id":"$value"}"""

    @Test
    fun `parses valid payload`() {
        val r = QrPayload.parse(body())
        assertTrue(r is QrPayload.Result.Ok)
        assertEquals(id, (r as QrPayload.Result.Ok).id)
    }

    @Test
    fun `ignores extra fields`() {
        val raw = """{"type":"otsmail-login","id":"$id","from":"web"}"""
        assertEquals(id, (QrPayload.parse(raw) as QrPayload.Result.Ok).id)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(id, (QrPayload.parse("  ${body()}\n") as QrPayload.Result.Ok).id)
    }

    @Test
    fun `rejects blank input`() {
        assertEquals(QrPayload.Result.Invalid("二维码内容为空"), QrPayload.parse("   "))
        assertEquals(QrPayload.Result.Invalid("二维码内容为空"), QrPayload.parse(null))
    }

    @Test
    fun `rejects non json`() {
        assertEquals(
            QrPayload.Result.Invalid("不是 OTSMail 登录二维码"),
            QrPayload.parse("https://mail.example.com/login"),
        )
    }

    @Test
    fun `rejects wrong type`() {
        assertEquals(
            QrPayload.Result.Invalid("不是 OTSMail 登录二维码"),
            QrPayload.parse(body(type = "otsmail-other")),
        )
    }

    @Test
    fun `rejects missing type`() {
        assertEquals(
            QrPayload.Result.Invalid("不是 OTSMail 登录二维码"),
            QrPayload.parse("""{"id":"$id"}"""),
        )
    }

    @Test
    fun `rejects missing id`() {
        assertEquals(
            QrPayload.Result.Invalid("二维码缺少票据编号"),
            QrPayload.parse("""{"type":"otsmail-login"}"""),
        )
    }

    @Test
    fun `rejects id with wrong length`() {
        assertEquals(
            QrPayload.Result.Invalid("二维码内容已损坏"),
            QrPayload.parse(body(value = id.dropLast(1))),
        )
        assertEquals(
            QrPayload.Result.Invalid("二维码内容已损坏"),
            QrPayload.parse(body(value = id + "0")),
        )
    }

    @Test
    fun `rejects non hex or uppercase id`() {
        assertEquals(
            QrPayload.Result.Invalid("二维码内容已损坏"),
            QrPayload.parse(body(value = "0123456789abcdef0123456789abcdeg")),
        )
        assertEquals(
            QrPayload.Result.Invalid("二维码内容已损坏"),
            QrPayload.parse(body(value = id.uppercase())),
        )
    }
}
