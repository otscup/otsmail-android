package com.otscup.otsmail.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Draft
import com.otscup.otsmail.data.Drafts
import com.otscup.otsmail.data.ScheduledMail
import com.otscup.otsmail.data.ScheduledSendStore
import com.otscup.otsmail.data.ScheduledSendTime
import com.otscup.otsmail.schedule.ScheduledSendRunner
import com.otscup.otsmail.schedule.ScheduledSendScheduler
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.UUID

/**
 * 定时发送的时间选择：几个常用快捷项 + 自定义日期/时间。
 * 全部按设备本地时区计算；用户改时区后，存储的「墙上时间」会由
 * [com.otscup.otsmail.schedule.ScheduledSendRescheduleReceiver] 重新锚定。
 */
@Composable
internal fun ScheduleTimeSheet(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val t = OT.type
    val c = OT.colors
    val ctx = LocalContext.current
    // 打开面板时冻结「现在」：用户停留一会儿再选，快捷项仍是当时看到的时间
    val now = remember { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val morning = remember { ScheduledSendTime.nextMorning(now, zone) }
    val evening = remember { ScheduledSendTime.nextEvening(now, zone) }
    val eveningToday = Instant.ofEpochMilli(evening).atZone(zone).toLocalDate() == today
    val hourLater = remember { ScheduledSendTime.atLeastOneMinuteAhead(now + 60 * 60 * 1000L, now) }

    fun pickCustom() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = maxOf(morning, now + 60 * 60 * 1000L)
        }
        DatePickerDialog(
            ctx,
            { _, year, month, day ->
                TimePickerDialog(
                    ctx,
                    { _, hour, minute ->
                        val at = ScheduledSendTime.atLocal(
                            zone,
                            LocalDate.of(year, month + 1, day),
                            hour,
                            minute,
                        )
                        onPick(ScheduledSendTime.atLeastOneMinuteAhead(at, System.currentTimeMillis()))
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    OTSheet(onDismiss = onDismiss) {
        Text(
            "定时发送",
            style = t.title3,
            color = c.label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            "按设备本地时区（${zone.id}）计算，到点自动在后台发送",
            style = t.footnote,
            color = c.label2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
            ScheduleQuickRow(
                title = "1 小时后",
                value = ScheduledSendTime.display(hourLater, zone),
                onClick = { onPick(hourLater) },
            )
            OTDivider(inset = 16)
            ScheduleQuickRow(
                title = if (eveningToday) "今天下班后" else "明天下班后",
                value = ScheduledSendTime.display(evening, zone),
                onClick = { onPick(evening) },
            )
            OTDivider(inset = 16)
            ScheduleQuickRow(
                title = "明早 8:00",
                value = ScheduledSendTime.display(morning, zone),
                onClick = { onPick(morning) },
            )
            OTDivider(inset = 16)
            ScheduleQuickRow(
                title = "自定义日期和时间",
                value = "",
                onClick = { pickCustom() },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ScheduleQuickRow(title: String, value: String, onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).tapArea { onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = c.blue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(title, style = t.body, color = c.label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = t.subhead, color = c.label2)
        }
    }
}

/**
 * 定时任务管理页：待发送按时间升序；发送失败的保留原因，可重试或转成草稿。
 * 取消定时会先把整封邮件写成草稿再删记录，不会静默丢内容。
 */
@Composable
fun ScheduledSendScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val t = OT.type
    val c = OT.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ver by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<ScheduledMail>>(emptyList()) }
    var editing by remember { mutableStateOf<ScheduledMail?>(null) }

    LaunchedEffect(ver) {
        list = withContext(Dispatchers.IO) {
            // 不能按登录账号过滤：定时任务记的是写信页选中的发件账号 id（fromId），
            // 与 Session.accountId 不同源（切换过发件人时尤甚），过滤会让任务在管理页
            // 消失但闹钟照常触发。任务本身按 id 取消/重试，这里列出全部本地任务。
            ScheduledSendStore.list(ctx)
        }
    }

    fun saveAsDraft(m: ScheduledMail) {
        val draft = Draft(
            id = UUID.randomUUID().toString(),
            accountId = m.accountId,
            fromId = m.accountId,
            toTags = m.to,
            toInput = "",
            ccTags = m.cc,
            ccInput = "",
            bccTags = m.bcc,
            bccInput = "",
            subject = m.subject,
            body = m.body.ifEmpty { m.text },
            selStart = -1,
            selEnd = -1,
            quoted = m.quoted,
            attachmentsJson = m.attachmentsJson,
            attachmentsDropped = false,
            updatedAt = System.currentTimeMillis(),
        )
        Drafts.save(ctx, draft)
    }

    Column(Modifier.fillMaxSize().background(c.bgGrouped).statusBarsPadding().navigationBarsPadding()) {
        OTNavBar(title = "定时发送（${list.count { it.pending }}）", onLeading = onBack)
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Schedule, null, tint = c.label3, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("暂无定时邮件", style = t.body, color = c.label)
                Spacer(Modifier.height(4.dp))
                Text("在写信页点右上角时钟即可定时发送", style = t.subhead, color = c.label2)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val pending = list.filter { it.pending }
                val failed = list.filterNot { it.pending }
                if (pending.isNotEmpty()) {
                    OTGroupHeader("待发送")
                    OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                        pending.forEachIndexed { i, m ->
                            if (i > 0) OTDivider(inset = 16)
                            PendingRow(
                                mail = m,
                                onReschedule = { editing = m },
                                onCancel = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            saveAsDraft(m)
                                            ScheduledSendStore.remove(ctx, m.id)
                                        }
                                        ScheduledSendScheduler.cancel(ctx, m.id)
                                        ver += 1
                                        onToast("已取消定时，邮件已存为草稿")
                                    }
                                },
                            )
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    OTGroupHeader("发送失败")
                    OTGroup(Modifier.padding(horizontal = OTSpace.s4)) {
                        failed.forEachIndexed { i, m ->
                            if (i > 0) OTDivider(inset = 16)
                            FailedRow(
                                mail = m,
                                onRetry = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ScheduledSendRunner.run(ctx, m.id) }
                                        ver += 1
                                        val after = withContext(Dispatchers.IO) { ScheduledSendStore.get(ctx, m.id) }
                                        onToast(if (after == null) "已重新发送" else "仍然失败：${after.error}")
                                    }
                                },
                                onDraft = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            saveAsDraft(m)
                                            ScheduledSendStore.remove(ctx, m.id)
                                        }
                                        ver += 1
                                        onToast("已转为草稿")
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ScheduledSendStore.remove(ctx, m.id) }
                                        ver += 1
                                        onToast("已删除这封定时邮件")
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { m ->
        ScheduleTimeSheet(
            onDismiss = { editing = null },
            onPick = { at ->
                editing = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ScheduledSendStore.reschedule(ctx, m.id, at, ScheduledSendTime.localStamp(at))
                    }
                    val updated = withContext(Dispatchers.IO) { ScheduledSendStore.get(ctx, m.id) }
                    if (updated != null) ScheduledSendScheduler.schedule(ctx, updated)
                    ver += 1
                    onToast("已改到 ${ScheduledSendTime.display(at)}")
                }
            },
        )
    }
}

@Composable
private fun PendingRow(mail: ScheduledMail, onReschedule: () -> Unit, onCancel: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ScheduledSendTime.display(mail.atMillis),
                style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = c.blue,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                mail.subject.ifEmpty { "（无主题）" },
                style = t.body,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            mail.to.joinToString(", ") + (mail.cc.takeIf { it.isNotEmpty() }?.let { " · 抄送 ${it.size} 人" } ?: ""),
            style = t.caption1,
            color = c.label2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                "改时间",
                style = t.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = c.blue,
                modifier = Modifier.tapArea { onReschedule() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "取消定时",
                style = t.footnote,
                color = c.red,
                modifier = Modifier.tapArea { onCancel() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FailedRow(
    mail: ScheduledMail,
    onRetry: () -> Unit,
    onDraft: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ScheduledSendTime.display(mail.atMillis)} 未发送",
                style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = c.red,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                mail.subject.ifEmpty { "（无主题）" },
                style = t.body,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (mail.error.isNotEmpty()) {
            Text(
                mail.error,
                style = t.caption1,
                color = c.red,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            mail.to.joinToString(", "),
            style = t.caption1,
            color = c.label2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                "重试",
                style = t.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = c.blue,
                modifier = Modifier.tapArea { onRetry() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "转为草稿",
                style = t.footnote,
                color = c.blue,
                modifier = Modifier.tapArea { onDraft() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "删除",
                style = t.footnote,
                color = c.red,
                modifier = Modifier.tapArea { onDelete() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
