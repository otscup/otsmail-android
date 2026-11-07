package com.otscup.otsmail.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码解不出来的根因是 ZXing 的 PlanarYUVLuminanceSource 不支持旋转，
 * 这里既验证自己写的旋转映射，也验证带 rowStride padding 的 Y 平面确实能解码。
 */
class QrDecoderTest {

    private val payload = """{"type":"otsmail-login","id":"0123456789abcdef0123456789abcdef"}"""

    /** 2x3 的 Y 平面，rowStride=4（行尾 2 字节 padding，故意填 0）。 */
    private fun tinyPlane(): ByteArray = byteArrayOf(
        1, 2, 0, 0,
        3, 4, 0, 0,
        5, 6, 0, 0,
    )

    @Test
    fun rotation0StripsRowPadding() {
        val gray = QrDecoder.uprightGray(tinyPlane(), 2, 3, 4, 0)
        assertEquals(2, gray.width)
        assertEquals(3, gray.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), gray.data)
    }

    @Test
    fun rotation90TurnsClockwise() {
        val gray = QrDecoder.uprightGray(tinyPlane(), 2, 3, 4, 90)
        assertEquals(3, gray.width)
        assertEquals(2, gray.height)
        // 顺时针 90°：原来最下面一行变成最左一列
        assertArrayEquals(byteArrayOf(5, 3, 1, 6, 4, 2), gray.data)
    }

    @Test
    fun rotation180ReversesBothAxes() {
        val gray = QrDecoder.uprightGray(tinyPlane(), 2, 3, 4, 180)
        assertEquals(2, gray.width)
        assertEquals(3, gray.height)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), gray.data)
    }

    @Test
    fun rotation270TurnsCounterClockwise() {
        val gray = QrDecoder.uprightGray(tinyPlane(), 2, 3, 4, 270)
        assertEquals(3, gray.width)
        assertEquals(2, gray.height)
        assertArrayEquals(byteArrayOf(2, 4, 6, 1, 3, 5), gray.data)
    }

    @Test
    fun decodesQrWithPaddedRowStride() {
        val raw = qrPlane(300, 320)
        assertEquals(payload, QrDecoder.decodeYuv(raw, 300, 300, 320, 0))
    }

    @Test
    fun decodesQrAfterRotation() {
        // 真实相机竖屏时 rotationDegrees 是 90 或 270，以前这里会直接抛异常
        val raw = qrPlane(300, 320)
        assertEquals(payload, QrDecoder.decodeYuv(raw, 300, 300, 320, 90))
        assertEquals(payload, QrDecoder.decodeYuv(raw, 300, 300, 320, 270))
    }

    @Test
    fun blankImageDecodesToNull() {
        val blank = ByteArray(320 * 300) { 0xFF.toByte() }
        assertNull(QrDecoder.decodeYuv(blank, 300, 300, 320, 0))
    }

    @Test
    fun invalidInputDoesNotThrow() {
        assertNull(QrDecoder.decodeYuv(ByteArray(0), 0, 0, 0, 90))
        assertNull(QrDecoder.decodeYuv(ByteArray(16), 10, 10, 4, 0))
    }

    /** 把二维码画成 Y 平面：黑=0，白=255，行尾补 [stride-width] 字节的 padding。 */
    private fun qrPlane(size: Int, stride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val data = ByteArray(stride * size)
        for (y in 0 until size) {
            val base = y * stride
            for (x in 0 until size) {
                data[base + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return data
    }
}
