package com.otscup.otsmail.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.otscup.otsmail.MainActivity
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.SnoozeReminder
import com.otscup.otsmail.data.SnoozeReminderStore
import com.otscup.otsmail.data.SnoozeStatus
import com.otscup.otsmail.data.SnoozeTime

object SnoozeScheduler {
    const val ACTION_FIRE = "com.otscup.otsmail.SNOOZE_FIRE"
    const val ACTION_DONE = "com.otscup.otsmail.SNOOZE_DONE"
    const val ACTION_RESNOOZE = "com.otscup.otsmail.SNOOZE_RESNOOZE"
    const val EXTRA_ID = "snoozeId"
    private const val CHANNEL = "ot_snooze_reminder"
    private const val RESNOOZE_MS = 30L * 60 * 1000

    fun currentAccountKey(ctx: Context): String = Session(ctx).let { SnoozeTime.accountKey(it.base, it.accountId, it.userId, it.email) }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "稍后提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "稍后处理邮件的提醒"
            enableVibration(true)
        })
    }

    fun schedule(ctx: Context, r: SnoozeReminder): SnoozeReminder {
        val saved = SnoozeReminderStore.upsert(ctx, r.copy(accountKey = if (r.accountKey.isBlank()) currentAccountKey(ctx) else r.accountKey, atLocal = SnoozeTime.toLocalLabel(r.atMillis), status = SnoozeStatus.pending))
        setAlarm(ctx, saved)
        return saved
    }

    fun cancelAlarm(ctx: Context, id: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pi(ctx, id, ACTION_FIRE))
    }

    fun cancelAlarmsForAccount(ctx: Context, accountKey: String) {
        SnoozeReminderStore.pendingForAccount(ctx, accountKey).forEach { cancelAlarm(ctx, it.id) }
    }

    /** 解绑第三方邮箱：取消该 extId 名下所有待触发闹钟。 */
    fun cancelAlarmsForExt(ctx: Context, extId: Long) {
        SnoozeReminderStore.all(ctx).filter { it.extId == extId && it.status == SnoozeStatus.pending }.forEach { cancelAlarm(ctx, it.id) }
    }

    /** 切号/退出：取消所有闹钟但保留数据（重登同账号恢复）。 */
    fun cancelAlarmsOnly(ctx: Context) {
        SnoozeReminderStore.all(ctx).filter { it.status == SnoozeStatus.pending }.forEach { cancelAlarm(ctx, it.id) }
    }

    fun rescheduleForCurrentAccount(ctx: Context) {
        val key = currentAccountKey(ctx)
        val s = Session(ctx)
        if (key.isBlank() || key.endsWith("#") || s.token.isBlank()) return
        SnoozeReminderStore.pendingForAccount(ctx, key).forEach { setAlarm(ctx, it) }
    }

    fun rescheduleAllPending(ctx: Context) {
        SnoozeReminderStore.all(ctx).filter { it.status == SnoozeStatus.pending }.forEach { setAlarm(ctx, it) }
    }

    fun complete(ctx: Context, id: String) {
        cancelAlarm(ctx, id)
        SnoozeReminderStore.setStatus(ctx, id, SnoozeStatus.done)
        NotificationManagerCompat.from(ctx).cancel(notifId(id))
    }

    fun resnooze(ctx: Context, id: String) {
        val r = SnoozeReminderStore.get(ctx, id) ?: return
        val at = System.currentTimeMillis() + RESNOOZE_MS
        SnoozeReminderStore.setStatus(ctx, id, SnoozeStatus.pending, at)
        SnoozeReminderStore.get(ctx, id)?.let { setAlarm(ctx, it) }
        NotificationManagerCompat.from(ctx).cancel(notifId(id))
    }

    private fun setAlarm(ctx: Context, r: SnoozeReminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pi(ctx, r.id, ACTION_FIRE)
        val at = r.atMillis.coerceAtLeast(System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pi(ctx: Context, id: String, action: String): PendingIntent {
        val i = Intent(ctx, if (action == ACTION_FIRE) SnoozeReceiver::class.java else SnoozeActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        val req = (id.hashCode() xor action.hashCode()) and 0x7fffffff
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ACTION_FIRE) PendingIntent.getBroadcast(ctx, req, i, flags)
        else PendingIntent.getBroadcast(ctx, req, i, flags)
    }

    fun showFired(ctx: Context, r: SnoozeReminder) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SNOOZE_OPEN, r.id)
            putExtra(MainActivity.EXTRA_SNOOZE_MAIL, r.mailId)
            putExtra(MainActivity.EXTRA_SNOOZE_SOURCE, r.source)
        }
        val content = PendingIntent.getActivity(ctx, notifId(r.id), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val done = pi(ctx, r.id, ACTION_DONE)
        val again = pi(ctx, r.id, ACTION_RESNOOZE)
        val title = r.subject.ifBlank { "稍后提醒" }
        val text = listOfNotNull(r.fromName.takeIf { it.isNotBlank() }, "可稍后送达（省电/锁屏可能延迟）").joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "完成", done)
            .addAction(0, "再提醒", again)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId(r.id), n) } catch (_: SecurityException) {}
    }

    private fun notifId(id: String): Int = 0x53000000 xor (id.hashCode() and 0x0fffffff)
}

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(SnoozeScheduler.EXTRA_ID) ?: return
        val ctx = context.applicationContext
        val r = SnoozeReminderStore.get(ctx, id) ?: return
        if (r.status != SnoozeStatus.pending) return
        SnoozeReminderStore.setStatus(ctx, id, SnoozeStatus.fired)
        SnoozeScheduler.showFired(ctx, r)
    }
}

class SnoozeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(SnoozeScheduler.EXTRA_ID) ?: return
        val ctx = context.applicationContext
        when (intent.action) {
            SnoozeScheduler.ACTION_DONE -> SnoozeScheduler.complete(ctx, id)
            SnoozeScheduler.ACTION_RESNOOZE -> SnoozeScheduler.resnooze(ctx, id)
        }
    }
}

class SnoozeRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                SnoozeScheduler.rescheduleAllPending(context.applicationContext)
        }
    }
}
