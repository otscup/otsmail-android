package com.otscup.otsmail.data

import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 全项目统一的 TLS 包装（探测、IMAP、SMTP 共用）。
 *
 * 之前直接用 `SSLSocketFactory.getDefault()` 有两个隐患：
 * 1. 旧系统的默认协议可能只协商到 TLS 1.0/1.1，现代邮件服务器会直接断开（表现为「握手失败 / connection closed」）；
 * 2. 不显式设置 SNI 时，多域名证书（Gmail 等）可能拿到错误证书或直接被拒绝。
 *
 * 这里显式使用 `SSLContext("TLS")`（Android 5+ 默认即 TLS 1.2+），再按系统支持情况
 * 把可用协议限制到 TLS 1.2 及以上；`createSocket(raw, host, port, true)` 会带上 SNI。
 * 主机名校验不额外开启：企业邮箱常见自签/通用证书，证书链仍由系统信任库校验。
 */
object ExtTls {

    /**
     * 必须显式 init：Android/JSSE 的 SSLContext 在 init(null, null, null) 之前
     * 调用 getSocketFactory() 会抛 IllegalStateException("SSLContext is not initialized")，
     * 表现为「TLS 握手失败 / 连接被关闭」，实际根本没开始握手。
     */
    private val factory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
    }

    /** SSLv3 / TLS 1.0 / 1.1 视为过旧，不再协商 */
    fun isModern(protocol: String): Boolean = when (protocol.uppercase()) {
        "SSL", "SSLV2", "SSLV3", "TLSV1", "TLSV1.1" -> false
        else -> true
    }

    fun wrap(raw: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.useClientMode = true
        val modern = ssl.supportedProtocols.filter { isModern(it) }
        if (modern.isNotEmpty()) ssl.enabledProtocols = modern.toTypedArray()
        ssl.startHandshake()
        return ssl
    }
}
