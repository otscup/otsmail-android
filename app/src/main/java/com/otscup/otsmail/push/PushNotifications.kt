package com.otscup.otsmail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.otscup.otsmail.MainActivity
import com.otscup.otsmail.R

/**
 * 新邮件通知：独立渠道（低打扰、无声音/震动），按发件人分组，
 * 点击带着 emailId 直达详情页，而不是只落到收件箱。
 */
object PushNotifications {

    const val CHANNEL_ID = "ot_mail_push"
    const val EXTRA_EMAIL_ID = "emailId"

    /** 渠道要在发通知前建好；MainActivity 与 MessagingService 都会调（幂等）。 */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "新邮件", NotificationManager.IMPORTANCE_LOW).apply {
            description = "收到新邮件时提醒，不响铃、不震动"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(channel)
    }

    /** 同一封邮件重复推送时用同一个 id，更新而不是堆叠。 */
    private fun notificationId(emailId: Long): Int = (emailId xor (emailId ushr 32)).toInt()

    fun showMail(
        context: Context,
        emailId: Long,
        from: String,
        name: String,
        subject: String,
        snippet: String,
    ) {
        if (emailId <= 0L) return
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val who = name.trim().ifEmpty { from.trim() }.ifEmpty { "新邮件" }
        val senderLine = if (from.isNotBlank() && who != from.trim()) "$who <$from>" else who
        val title = subject.trim().ifEmpty { "（无主题）" }
        val body = snippet.trim().replace('\n', ' ')

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_EMAIL_ID, emailId)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(emailId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val group = "sender:" + from.trim().lowercase().ifEmpty { "unknown" }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ot_mail)
            .setColor(0xFF2F6FED.toInt())
            .setContentTitle(senderLine)
            .setContentText(title)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    if (body.isEmpty()) title else "$title\n$body",
                ),
            )
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setShowWhen(true)
            .setContentIntent(pending)

        runCatching { nm.notify(notificationId(emailId), builder.build()) }
    }
}
