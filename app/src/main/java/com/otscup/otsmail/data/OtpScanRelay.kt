package com.otscup.otsmail.data

/**
 * 扫 otpauth:// 二维码的回传通道。
 * 扫码页与会话页是两条路由，用一次性队列把原始文本带回来，避免引入导航结果 API。
 */
object OtpScanRelay {

    private val queue = ArrayDeque<String>()

    @Synchronized
    fun post(text: String) {
        if (text.isNotBlank()) queue.addLast(text)
    }

    @Synchronized
    fun poll(): String? = queue.removeFirstOrNull()
}
