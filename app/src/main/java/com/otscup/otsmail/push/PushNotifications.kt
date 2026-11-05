package com.otscup.otsmail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.otscup.otsmail.MainActivity
import com.otscup.otsmail.R

/**
 * 新邮件通知：独立渠道（低打扰、无声音/震动），按发件人分组，
 * 点击带着 emailId 直达详情页，而不是只落到收件箱。
 */
object PushNotifications {

    const val CHANNEL_ID = "ot_mail_push"
    const val EXTRA_EMAIL_ID = "emailId"

    /** 通知 action 的 Intent action / extras，Receiver 与这里共用。 */
    const val ACTION_READ = "com.otscup.otsmail.push.READ"
    const val ACTION_DELETE = "com.otscup.otsmail.push.DELETE"
    const val ACTION_REPLY = "com.otscup.otsmail.push.REPLY"
    const val EXTRA_REPLY_TEXT = "replyText"
    const val EXTRA_SENDER = "sender"
    const val EXTRA_SUBJECT = "subject"

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
    fun notificationId(emailId: Long): Int = (emailId xor (emailId ushr 32)).toInt()

    /** action 成功后收起这条通知；失败时保留，避免用户以为操作成功、通知却不见了。 */
    fun cancel(context: Context, emailId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(emailId))
    }

    /**
     * 三个 action 的广播 PendingIntent。
     * 已读/删除用 FLAG_IMMUTABLE；快速回复必须带 RemoteInput，系统要把输入结果写进 Intent，
     * Android 12+ 只有 mutable 才能收到回复文本，所以只有这一个用 FLAG_MUTABLE。
     */
    private fun actionIntent(
        context: Context,
        emailId: Long,
        action: String,
        mutable: Boolean,
        from: String = "",
        subject: String = "",
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            // data 参与 PendingIntent 匹配，保证三个 action 各是各的，不会互相覆盖 extras
            data = Uri.parse("otsmail://push/$emailId/${action.substringAfterLast('.')}")
            putExtra(EXTRA_EMAIL_ID, emailId)
            if (from.isNotEmpty()) putExtra(EXTRA_SENDER, from)
            if (subject.isNotEmpty()) putExtra(EXTRA_SUBJECT, subject)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, notificationId(emailId), intent, flags)
    }

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

        // 三个快捷操作：已读、删除、快速回复（RemoteInput 允许在通知栏直接输入）
        val readPending = actionIntent(context, emailId, ACTION_READ, mutable = false)
        val deletePending = actionIntent(context, emailId, ACTION_DELETE, mutable = false)
        val replyPending = actionIntent(
            context, emailId, ACTION_REPLY, mutable = true, from = from.trim(), subject = subject,
        )
        val replyInput = RemoteInput.Builder(EXTRA_REPLY_TEXT).setLabel("回复…").build()
        val readAction = Notification.Action.Builder(0, "标记已读", readPending).build()
        val deleteAction = Notification.Action.Builder(0, "删除", deletePending).build()
        val replyAction = Notification.Action.Builder(0, "快速回复", replyPending)
            .addRemoteInput(replyInput)
            .setAllowGeneratedReplies(true)
            .build()

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
            .addAction(readAction)
            .addAction(deleteAction)
            .addAction(replyAction)

        runCatching { nm.notify(notificationId(emailId), builder.build()) }
    }
}
