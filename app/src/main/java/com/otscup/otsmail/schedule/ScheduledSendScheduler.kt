package com.otscup.otsmail.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ScheduledMail
import com.otscup.otsmail.data.ScheduledSendStore
import com.otscup.otsmail.data.ScheduledSendTime
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.MailSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * 定时发送的调度：用 AlarmManager（RTC_WAKEUP + allowWhileIdle）。
 * 没有 WorkManager 依赖；刚性的「到点发信」用闹钟更贴近语义，且 Doze 下
 * setAndAllowWhileIdle 仍会唤醒（系统会合并、可能晚几分钟）。
 * 有精确闹钟权限时用 setExactAndAllowWhileIdle，没有就降级为不精确，
 * 不额外申请 SCHEDULE_EXACT_ALARM（避免把用户拉去系统设置页开权限）。
 * 重启/覆盖安装后由 [ScheduledSendRescheduleReceiver] 从存储重排，不依赖内存。
 */
internal object ScheduledSendScheduler {
    private const val ACTION_FIRE = "com.otscup.otsmail.SCHEDULED_SEND"
    private const val EXTRA_ID = "scheduledId"

    fun schedule(ctx: Context, mail: ScheduledMail) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx, mail.id)
        val at = mail.atMillis
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.w("ScheduledSend", "排闹钟失败：${it.message}") }
    }

    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(ctx, id)) }
        pendingIntent(ctx, id).cancel()
    }

    /** 重启/装包/手动触发：把存储里所有等待项重新排上（过去时刻会立即触发）。 */
    fun rescheduleAll(ctx: Context) {
        ScheduledSendStore.list(ctx).filter { it.pending }.forEach { schedule(ctx, it) }
    }

    /**
     * 设备换时区：把等待项按原来的「墙上时间」重算成新时区的绝对时刻。
     * 例如 8 点从 +08 换到 +09，仍然是当地的 8 点，而不是被算成 9 点。
     */
    fun reanchorAll(ctx: Context, zone: ZoneId = ZoneId.systemDefault()) {
        ScheduledSendStore.reanchorAll(ctx, zone)
        rescheduleAll(ctx)
    }

    private fun pendingIntent(ctx: Context, id: String): PendingIntent {
        val intent = Intent(ctx, ScheduledSendReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 到点触发：把闹钟拉起来后立刻发信；失败只标失败，不删记录。 */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("scheduledId") ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledSendRunner.run(app, id)
            } catch (e: Throwable) {
                Log.w("ScheduledSend", "定时发送异常：${e.message}")
                runCatching { ScheduledSendStore.markFailed(app, id, friendlyError(e)) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 开机 / 覆盖安装 / 换时区后恢复调度，不依赖进程是否活过。 */
class ScheduledSendRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED -> ScheduledSendScheduler.rescheduleAll(context.applicationContext)

            Intent.ACTION_TIMEZONE_CHANGED ->
                ScheduledSendScheduler.reanchorAll(context.applicationContext)
        }
    }
}

/** 真正提交：读记录 → 恢复登录态 → 发信 → 成功删除 / 失败保留原因。 */
internal object ScheduledSendRunner {
    suspend fun run(context: Context, id: String) {
        val mail = ScheduledSendStore.get(context, id) ?: return
        if (!mail.pending) return
        Session(context).apply()
        if (Api.base.isBlank() || Api.token.isBlank()) {
            ScheduledSendStore.markFailed(context, id, "登录已失效，定时邮件未发送")
            notify(context, mail, "登录已失效，请重新登录后重试")
            return
        }
        val attachments = runCatching {
            val arr = JSONArray(mail.attachmentsJson)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList<JSONObject>())
        runCatching {
            Api.send(
                accountId = mail.accountId,
                to = mail.to,
                subject = mail.subject,
                text = mail.text,
                html = mail.html,
                attachments = attachments,
                cc = mail.cc,
                bcc = mail.bcc,
            )
        }.onSuccess {
            ScheduledSendStore.remove(context, id)
            Handler(Looper.getMainLooper()).post { MailSync.changed() }
            notify(context, mail, null)
        }.onFailure {
            val reason = friendlyError(it)
            ScheduledSendStore.markFailed(context, id, reason)
            notify(context, mail, reason)
        }
    }
}

/**
 * 结果通知：成功/失败都给一条（尽力而为）。
 * 用户关掉通知权限时静默跳过——失败原因仍写在「定时发送」列表里，不会丢。
 */
private fun notify(context: Context, mail: ScheduledMail, error: String?) {
    runCatching {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "scheduled_send"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "定时发送",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "定时邮件发送成功或失败的结果" },
            )
        }
        val title = if (error == null) "定时邮件已发送" else "定时邮件发送失败"
        val body = mail.subject.ifBlank { mail.to.joinToString(", ") }
        val text = if (error == null) body else "$body\n$error"
        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = contentIntent?.let {
            PendingIntent.getActivity(
                context,
                mail.id.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            nm.notify(mail.id.hashCode(), notification)
        }
    }.onFailure { Log.w("ScheduledSend", "结果通知失败：${it.message}") }
}
