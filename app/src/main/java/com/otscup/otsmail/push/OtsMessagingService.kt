package com.otscup.otsmail.push

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.otscup.otsmail.data.PushManager
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.MailSync

/**
 * 服务端通过 FCM HTTP v1 发 data-only 消息（fcm-service.js sendOne）：
 * { emailId, type: 'email', sender, name, subject, snippet }。
 * 旧版服务端键名是 from，这里 sender 优先、from 兜底，两版都能收到。
 * 通知由本机 PushNotifications 自己建（payload 没有 notification 块，系统不会代劳）。
 */
class OtsMessagingService : FirebaseMessagingService() {

    private companion object {
        const val TAG = "OtsFcm"
    }

    override fun onNewToken(token: String) {
        PushManager.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(TAG, "onMessageReceived keys=${data.keys.sorted()}")
        val emailId = data["emailId"]?.toLongOrNull()
            ?: data["email_id"]?.toLongOrNull()
            ?: 0L
        val from = cleanSender(
            data["sender"],
            data["from"],
            message.from,
        )
        val name = data["name"].orEmpty()
        val subject = data["subject"].orEmpty().ifEmpty { message.notification?.title.orEmpty() }
        val snippet = data["snippet"].orEmpty()
            .ifEmpty { data["text"].orEmpty() }
            .ifEmpty { message.notification?.body.orEmpty() }

        if (emailId > 0L) {
            // 未登录（或已退出登录但服务端仍登记着这台设备）时不展示任何通知：
            // 否则通知栏会露出上一个账号的发件人/主题/摘要。
            val loggedIn = Session(this).token.isNotEmpty()
            // 去重：data-only 永远为 true（保持现状）；带 notification 块且同 emailId 已展示过则跳过
            val show = loggedIn && PushDedupe.decide(this, emailId, message.notification != null)
            if (show) {
                PushNotifications.showMail(this, emailId, from, name, subject, snippet)
            }
        }
        // onMessageReceived 不保证在主线程；Compose 的 mutableIntStateOf 要在主线程写
        Handler(Looper.getMainLooper()).post { MailSync.changed() }
    }

    /**
     * 取发件人。`message.from` 是 FCM 的数字 sender id（不是邮箱），
     * 纯数字一律丢弃，避免通知标题出现一串数字；都没有时交给 PushNotifications 回退「新邮件」。
     */
    private fun cleanSender(vararg candidates: String?): String {
        for (candidate in candidates) {
            val value = candidate?.trim().orEmpty()
            if (value.isEmpty()) continue
            if (value.all { it.isDigit() }) continue
            return value
        }
        return ""
    }
}
