package com.otscup.otsmail.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * RFC 2369 & RFC 8058 邮件退订解析与一键退订实现。
 * 支持解析 List-Unsubscribe 头中的 HTTPS / mailto 链接，
 * 以及自动判定 List-Unsubscribe-Post: List-Unsubscribe=One-Click 标准。
 */
object UnsubscribeHelper {

    data class UnsubInfo(
        val httpUrl: String?,
        val mailtoUri: String?,
        val isOneClick: Boolean,
    )

    fun parse(listUnsubscribe: String, listUnsubscribePost: String = ""): UnsubInfo? {
        if (listUnsubscribe.isBlank()) return null

        val urls = Regex("<([^>]+)>").findAll(listUnsubscribe)
            .map { it.groupValues[1].trim() }
            .toList()

        val httpUrl = urls.firstOrNull {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val mailtoUri = urls.firstOrNull {
            it.startsWith("mailto:", ignoreCase = true)
        }

        if (httpUrl == null && mailtoUri == null) {
            // 兼容没有尖括号包裹的单裸 URL
            val rawTrim = listUnsubscribe.trim()
            if (rawTrim.startsWith("http://", ignoreCase = true) || rawTrim.startsWith("https://", ignoreCase = true)) {
                val oneClick = listUnsubscribePost.contains("List-Unsubscribe=One-Click", ignoreCase = true)
                return UnsubInfo(httpUrl = rawTrim, mailtoUri = null, isOneClick = oneClick)
            }
            return null
        }

        val isOneClick = listUnsubscribePost.contains("List-Unsubscribe=One-Click", ignoreCase = true) && !httpUrl.isNullOrBlank()

        return UnsubInfo(
            httpUrl = httpUrl,
            mailtoUri = mailtoUri,
            isOneClick = isOneClick,
        )
    }

    suspend fun executeOneClick(httpUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { os ->
                    os.write("List-Unsubscribe=One-Click".toByteArray(Charsets.UTF_8))
                }
            }
            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Unit
            } else {
                throw ApiException(responseCode, "退订服务响应异常: HTTP $responseCode")
            }
        }
    }

    fun openInBrowserOrMail(context: Context, info: UnsubInfo) {
        runCatching {
            if (!info.httpUrl.isNullOrBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.httpUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else if (!info.mailtoUri.isNullOrBlank()) {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(info.mailtoUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
