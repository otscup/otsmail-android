package com.otscup.otsmail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AttachmentUnavailable(message: String = "附件地址不可用") : Exception(message)
class AttachmentTooLarge(message: String = "附件超过 10MB，请下载查看") : Exception(message)

/**
 * 附件内联预览：只用平台 BitmapFactory / PdfRenderer，不引图片库。
 *
 * - 一律走附件对象的签名 url（[Api.attachmentUrl] 补齐 origin），没有 url 直接报错；
 * - 渐进式下载到 cache，超过 10MB 立即中止，避免 OOM；
 * - 图片先 inJustDecodeBounds 再按目标尺寸算 inSampleSize，不把原图整张读进内存；
 * - PDF 用 PdfRenderer 渲染第 0 页（缩略图/单页预览共用）；
 * - 下载与解码串行化（Mutex），避免打开详情时多张图同时解码拖慢主线程。
 */
object Attachments {
    const val MAX_BYTES = 10L * 1024 * 1024

    enum class Kind { IMAGE, PDF, OTHER }

    fun kindOf(filename: String): Kind {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> Kind.IMAGE
            "pdf" -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    private val mutex = Mutex()

    /** 缩略图按 url 缓存；数量上限 12 张，避免来回翻详情反复下载解码。 */
    private val thumbs = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    suspend fun thumbnail(context: Context, url: String, kind: Kind, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$kind|$maxPx|$url"
            thumbs.get(key)?.let { return@withContext it }
            mutex.withLock {
                thumbs.get(key)?.let { return@withLock it }
                val file = ensureFile(context, url)
                val bmp = when (kind) {
                    Kind.IMAGE -> decode(file, maxPx)
                    Kind.PDF -> renderPdf(file, maxPx)
                    Kind.OTHER -> null
                }
                if (bmp != null) thumbs.put(key, bmp)
                bmp
            }
        }

    /** 全屏图片预览：按屏幕宽度采样解码，不读原图。 */
    suspend fun image(context: Context, url: String, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock { decode(ensureFile(context, url), maxPx) }
        }

    suspend fun pdfPage(context: Context, url: String, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock { renderPdf(ensureFile(context, url), maxPx) }
        }

    /**
     * 保存附件到应用下载目录。演示包用 data URL 提供附件，DownloadManager 只认 http(s)，
     * 这种情况走这里把缓存文件复制出来；正式包的 http 附件仍由 DownloadManager 处理。
     */
    suspend fun save(context: Context, url: String, filename: String): File =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val src = ensureFile(context, url)
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                dir.mkdirs()
                val safe = filename
                    .replace('\\', '/')
                    .substringAfterLast('/')
                    .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
                    .trim()
                    .trimStart('.')
                    .take(120)
                    .ifEmpty { "attachment" }
                val dest = File(dir, safe)
                src.copyTo(dest, overwrite = true)
                dest
            }
        }

    private fun ensureFile(context: Context, url: String): File {
        val u = url.trim()
        if (u.isEmpty()) throw AttachmentUnavailable()
        val dir = File(context.cacheDir, "att").apply { mkdirs() }
        val name = sha256(u).take(32)
        val f = File(dir, name)
        // 文件已缓存且非空：直接复用；签名 url 变化会产生新 hash，不会读到旧内容
        if (f.exists() && f.length() > 0) return f
        val tmp = File(dir, "$name.part")
        try {
            download(u, tmp)
            if (tmp.length() > MAX_BYTES) throw AttachmentTooLarge()
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw IOException("附件缓存写入失败")
            return f
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun download(url: String, dest: File) {
        if (url.startsWith("data:", true)) {
            val comma = url.indexOf(',')
            if (comma <= 0) throw AttachmentUnavailable()
            val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            if (bytes.size > MAX_BYTES) throw AttachmentTooLarge()
            dest.outputStream().use { it.write(bytes) }
            return
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("附件请求失败（HTTP $code）")
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) throw AttachmentTooLarge()
            var read = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        read += n
                        if (read > MAX_BYTES) throw AttachmentTooLarge()
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // 保证解码后的长边至少覆盖目标尺寸，同时不把远大于目标的像素读进内存
        while (longest / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun renderPdf(file: File, maxPx: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val scale = maxPx.toFloat() / maxOf(page.width, page.height)
                    val w = maxOf(1, (page.width * scale).toInt())
                    val h = maxOf(1, (page.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
