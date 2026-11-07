package com.otscup.otsmail.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码灰度解码，纯 JVM 实现，便于单测直接喂 YUV 数据。
 *
 * 注意：ZXing 的 [PlanarYUVLuminanceSource] **不支持旋转**（`rotateCounterClockwise()`
 * 会抛 UnsupportedOperationException），所以这里先自己把 Y 平面旋正，再交给 ZXing。
 */
object QrDecoder {

    data class Gray(val data: ByteArray, val width: Int, val height: Int)

    /**
     * 把相机 Y 平面（可能带行尾 padding）按 [rotationDegrees] 旋正成紧凑灰度图。
     * [rotationDegrees] 是 CameraX 给的「顺时针旋转多少度后画面才是正的」。
     */
    fun uprightGray(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
    ): Gray {
        require(width > 0 && height > 0) { "图像尺寸非法：${width}x$height" }
        require(rowStride >= width) { "rowStride($rowStride) 小于 width($width)" }
        val stride = rowStride
        return when (rotationDegrees % 360) {
            90 -> {
                val out = ByteArray(width * height)
                for (dy in 0 until width) {
                    val outBase = dy * height
                    for (dx in 0 until height) {
                        out[outBase + dx] = y[(height - 1 - dx) * stride + dy]
                    }
                }
                Gray(out, height, width)
            }

            180 -> {
                val out = ByteArray(width * height)
                var i = 0
                for (dy in 0 until height) {
                    val rowBase = (height - 1 - dy) * stride
                    for (dx in 0 until width) {
                        out[i++] = y[rowBase + width - 1 - dx]
                    }
                }
                Gray(out, width, height)
            }

            270 -> {
                val out = ByteArray(width * height)
                for (dy in 0 until width) {
                    val col = width - 1 - dy
                    val outBase = dy * height
                    for (dx in 0 until height) {
                        out[outBase + dx] = y[dx * stride + col]
                    }
                }
                Gray(out, height, width)
            }

            else -> {
                val out = ByteArray(width * height)
                var i = 0
                for (dy in 0 until height) {
                    val rowBase = dy * stride
                    for (dx in 0 until width) {
                        out[i++] = y[rowBase + dx]
                    }
                }
                Gray(out, width, height)
            }
        }
    }

    /** 从 YUV_420_888 的 Y 平面解二维码；解不出返回 null。 */
    fun decodeYuv(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
    ): String? {
        val gray = runCatching { uprightGray(y, width, height, rowStride, rotationDegrees) }
            .getOrNull() ?: return null
        return decodeGray(gray.data, gray.width, gray.height)
    }

    /** 解一张紧凑灰度图（每像素 1 字节，行宽 = width）。 */
    fun decodeGray(gray: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || gray.size < width * height) return null
        val source = PlanarYUVLuminanceSource(gray, width, height, 0, 0, width, height, false)
        val reader = MultiFormatReader()
        return try {
            reader.setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }
}
