package com.otscup.otsmail.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.SnoozeReminder
import com.otscup.otsmail.data.SnoozeReminderStore
import com.otscup.otsmail.data.SnoozeStatus
import com.otscup.otsmail.data.SnoozeTime
import com.otscup.otsmail.push.PushNotifications
import com.otscup.otsmail.schedule.SnoozeScheduler
import com.otscup.otsmail.ui.theme.OT

@Composable
fun SnoozeReminderScreen(
    session: Session,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenMail: (SnoozeReminder) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val ctx = LocalContext.current
    val accountKey = remember(session.base, session.accountId, session.userId, session.email) {
        SnoozeTime.accountKey(session.base, session.accountId, session.userId, session.email)
    }
    var reminders by remember(accountKey) {
        mutableStateOf(
            SnoozeReminderStore.allForAccount(ctx, accountKey)
                .filter { it.status == SnoozeStatus.pending || it.status == SnoozeStatus.fired }
                .sortedBy { it.atMillis }
        )
    }
    fun reload() {
        reminders = SnoozeReminderStore.allForAccount(ctx, accountKey)
            .filter { it.status == SnoozeStatus.pending || it.status == SnoozeStatus.fired }
            .sortedBy { it.atMillis }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(c.bgGrouped)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.label) }
            Text("稍后提醒", style = t.title3, color = c.label, modifier = Modifier.weight(1f))
        }
        if (reminders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("没有稍后提醒", style = t.body, color = c.label)
                    Spacer(Modifier.height(8.dp))
                    Text("在邮件详情里设置后，会在这里显示", style = t.footnote, color = c.label2)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(reminders, key = { it.id }) { r ->
                    SnoozeReminderRow(
                        r = r,
                        onOpen = {
                            val cur = SnoozeTime.accountKey(session.base, session.accountId, session.userId, session.email)
                            if (r.accountKey != cur) onToast("该提醒属于其他账号，登录对应账号后可查看")
                            else if (r.mailId.isNotBlank() && r.mailId != "0") onOpenMail(r) else onToast("原邮件不可用")
                        },
                        onResnooze = {
                            SnoozeScheduler.resnooze(ctx, r.id)
                            onToast("已再提醒 30 分钟")
                            reload()
                        },
                        onDone = {
                            SnoozeScheduler.complete(ctx, r.id)
                            onToast("已完成")
                            reload()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SnoozeReminderRow(r: SnoozeReminder, onOpen: () -> Unit, onResnooze: () -> Unit, onDone: () -> Unit) {
    val c = OT.colors
    val t = OT.type
    val overdue = r.status == SnoozeStatus.fired || r.atMillis <= System.currentTimeMillis()
    Surface(shape = RoundedCornerShape(12.dp), color = c.bgElevated, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(14.dp)) {
            Text(r.subject.ifBlank { "（无主题）" }, style = t.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val timeText = if (overdue) "提醒时间已过 · ${r.atLocal.ifBlank { SnoozeTime.toLocalLabel(r.atMillis) }}" else "提醒时间：${r.atLocal.ifBlank { SnoozeTime.toLocalLabel(r.atMillis) }}"
            Text(timeText, style = t.footnote, color = if (overdue) c.orange else c.label2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onResnooze, label = { Text("再提醒") })
                AssistChip(onClick = onDone, label = { Text("完成") })
            }
        }
    }
}

@Composable
fun SnoozeTimeSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long, String) -> Unit,
) {
    if (!visible) return
    val c = OT.colors
    val ctx = LocalContext.current
    var customOpen by remember(visible) { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    val tonight = (cal.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 20); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    val tomorrowMorning = (cal.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val options = listOf(
        "1 小时后" to now + 60L * 60_000,
        "今晚 20:00" to tonight,
        "明早 09:00" to tomorrowMorning,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("稍后提醒", color = c.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (label, at) ->
                    Text(label, color = c.blue, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(at, SnoozeTime.toLocalLabel(at)); onDismiss() }.padding(vertical = 10.dp))
                }
                Text("自定义时间", color = c.blue, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { customOpen = true }.padding(vertical = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.label2) } },
        containerColor = c.bgElevated,
    )
    if (customOpen) {
        val baseCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            android.app.TimePickerDialog(ctx, { _, h, min ->
                val picked = java.util.Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
                val at = if (picked > System.currentTimeMillis()) picked else System.currentTimeMillis() + 5L * 60_000
                onPick(at, SnoozeTime.toLocalLabel(at)); customOpen = false; onDismiss()
            }, baseCal.get(java.util.Calendar.HOUR_OF_DAY), baseCal.get(java.util.Calendar.MINUTE), true).show()
        }, baseCal.get(java.util.Calendar.YEAR), baseCal.get(java.util.Calendar.MONTH), baseCal.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
            setOnCancelListener { customOpen = false }
            show()
        }
    }
}

fun ensureSnoozeNotificationPermission(
    context: android.content.Context,
    requestPermission: (String) -> Unit,
    openSettings: () -> Unit,
    onToast: (String) -> Unit,
): Boolean {
    if (PushNotifications.enabled(context)) return true
    onToast("通知未开启，可能无法收到稍后提醒")
    openSettings()
    return true // still allow creating local reminder record; delivery may be blocked
}

fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
