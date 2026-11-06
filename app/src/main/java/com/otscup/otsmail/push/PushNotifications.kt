package com.otscup.otsmail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.otscup.otsmail.MainActivity
import com.otscup.otsmail.R

/**
 * 新邮件通知：按「声音 × 震动」预建四条渠道，按发件人分组，
 * 点击带着 emailId 直达详情页，而不是只落到收件箱。
 *
 * 安卓 8 以后渠道的声音/震动由系统保存，App 无法修改已建渠道，
 * 所以只能组合预建，发通知时按当前设置选一条。
 */
object PushNotifications {

    const val CHANNEL_SOUND_VIBRATE = "ot_mail_push_sv"
    const val CHANNEL_SOUND = "ot_mail_push_s"
    const val CHANNEL_VIBRATE = "ot_mail_push_v"
    const val CHANNEL_SILENT = "ot_mail_push_silent"

    /**
     * 旧渠道，启动时删除，避免设置页堆一堆。
     * 注意不能再把 "ot_mail_push" 放进来：它是 manifest 里 FCM 的默认渠道 id，
     * 删掉后服务端一旦改发带 notification 块的消息，通知会落到系统兜底渠道。
     */
    private val LEGACY_CHANNELS = listOf("ot_mail_push_v2")

    private const val TAG = "OtsFcm"
    const val EXTRA_EMAIL_ID = "emailId"

    /** 通知 action 的 Intent action / extras，Receiver 与这里共用。 */
    const val ACTION_READ = "com.otscup.otsmail.push.READ"
    const val ACTION_DELETE = "com.otscup.otsmail.push.DELETE"
    const val ACTION_REPLY = "com.otscup.otsmail.push.REPLY"
    const val EXTRA_REPLY_TEXT = "replyText"
    const val EXTRA_SENDER = "sender"
    const val EXTRA_SUBJECT = "subject"

    /** 四条渠道要在发通知前建好；MainActivity 与 MessagingService 都会调（幂等）。 */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        createChannel(
            nm, CHANNEL_SOUND_VIBRATE, "OTSMail 新邮件（有声震动）", "响铃并震动（默认）",
            sound = true, vibrate = true, lowImportance = false,
        )
        createChannel(
            nm, CHANNEL_SOUND, "OTSMail 新邮件（有声）", "只响铃，不震动",
            sound = true, vibrate = false, lowImportance = false,
        )
        createChannel(
            nm, CHANNEL_VIBRATE, "OTSMail 新邮件（静音震动）", "不响铃，只震动",
            sound = false, vibrate = true, lowImportance = false,
        )
        createChannel(
            nm, CHANNEL_SILENT, "OTSMail 新邮件（静音）", "不响铃也不震动",
            sound = false, vibrate = false, lowImportance = true,
        )
    }

    private fun createChannel(
        nm: NotificationManager,
        id: String,
        name: String,
        description: String,
        sound: Boolean,
        vibrate: Boolean,
        lowImportance: Boolean,
    ) {
        if (nm.getNotificationChannel(id) != null) return
        // 渠道一旦创建，声音/震动就归用户掌握；这里只在首次创建时写入默认值。
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            id,
            name,
            if (lowImportance) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            this.description = description
            if (sound) setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attrs) else setSound(null, null)
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = longArrayOf(0, 250, 200, 250)
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

        // 「要不要发」在进入通知构建前就拦掉，列表/未读仍照常由 MailSync 刷新，
        // 不靠静音渠道假装关闭，避免角标与通知对不上。
        val blocked = when {
            !PushSettings.enabled(context) -> "总开关关闭"
            PushSettings.isMuted(context, from) -> "发件人已静音"
            PushSettings.inDnd(context) -> "免打扰时段"
            else -> null
        }
        if (blocked != null) {
            Log.i(TAG, "通知被设置拦截 emailId=$emailId reason=$blocked")
            return
        }

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
        // 通知最多只渲染 3 个 action，第 4 个会被 SystemUI 直接丢弃；
        // 静音入口改放邮件详情「更多」与设置页，避免按钮存在但点不到。
        val builder = Notification.Builder(context, PushSettings.channelId(context))
            .setSmallIcon(R.drawable.ic_notif_at)
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
