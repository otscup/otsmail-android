package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.UnsubscribeHelper
import com.otscup.otsmail.data.UnsubscribeItem
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/**
 * 集中退订管理中心（RFC 8058 & RFC 2369）。
 * 聚合所有识别到退订头的邮件源，支持一览已收到邮件总数、发件人，并支持一键免跳出退订。
 */
@Composable
fun UnsubscribeScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<UnsubscribeItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val unsubscribedEmails = remember { mutableStateListOf<String>() }
    var processingEmail by remember { mutableStateOf("") }

    fun reload() {
        loading = true
        scope.launch {
            runCatching { Api.unsubscribeList() }
                .onSuccess {
                    items = it
                    loading = false
                }
                .onFailure {
                    loading = false
                    onToast(friendlyError(it))
                }
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OTNavBar(
            title = "退订管理中心",
            onLeading = onBack,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { reload() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = c.blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (loading && items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = c.blue, strokeWidth = 2.5.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    // 说明横幅
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.purple.copy(alpha = 0.08f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Unsubscribe,
                            contentDescription = null,
                            tint = c.purple,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "集中管理邮件列表与营销简讯。遵循 RFC 8058 国际退订标准，支持免进入浏览器直接发送 One-Click 退订请求。",
                            style = t.footnote,
                            color = c.label2,
                            lineHeight = 18.sp,
                        )
                    }
                }

                item {
                    OTGroupHeader("已识别的订阅源 (${items.size})")
                }

                if (items.isEmpty()) {
                    item {
                        OTGroup(Modifier.padding(horizontal = 16.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Outlined.MarkEmailRead,
                                        contentDescription = null,
                                        tint = c.label3,
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "暂无可退订的邮件列表或订阅源",
                                        style = t.body,
                                        color = c.label3,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        OTGroup(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                items.forEachIndexed { index, sub ->
                                    if (index > 0) OTDivider(inset = 56)
                                    val isDone = sub.sendEmail in unsubscribedEmails
                                    val isBusy = processingEmail == sub.sendEmail
                                    val unsubInfo = remember(sub.listUnsubscribe, sub.listUnsubscribePost) {
                                        UnsubscribeHelper.parse(sub.listUnsubscribe, sub.listUnsubscribePost)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OTAvatar(
                                            name = sub.name.ifEmpty { sub.sendEmail },
                                            size = 40,
                                        )

                                        Spacer(Modifier.width(12.dp))

                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = sub.name.ifEmpty { sub.sendEmail },
                                                    style = t.body.copy(fontWeight = FontWeight.SemiBold),
                                                    color = c.label,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(100))
                                                        .background(c.bgInput)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        text = "${sub.count} 封",
                                                        style = t.caption2,
                                                        color = c.label2,
                                                    )
                                                }
                                            }

                                            Spacer(Modifier.height(3.dp))

                                            Text(
                                                text = sub.sendEmail,
                                                style = t.footnote,
                                                color = c.label2,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )

                                            if (sub.createTime.isNotBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = "最近收到: ${TimeFmt.preciseTime(sub.createTime)}",
                                                    style = t.caption1,
                                                    color = c.label3,
                                                )
                                            }
                                        }

                                        Spacer(Modifier.width(8.dp))

                                        if (isDone) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(100))
                                                    .background(c.green.copy(alpha = 0.12f))
                                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    tint = c.green,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = "已请求退订",
                                                    style = t.caption1.copy(fontWeight = FontWeight.Medium),
                                                    color = c.green,
                                                )
                                            }
                                        } else if (isBusy) {
                                            CircularProgressIndicator(
                                                color = c.blue,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(c.blue.copy(alpha = 0.10f))
                                                    .clickable {
                                                        if (unsubInfo != null) {
                                                            if (unsubInfo.isOneClick && !unsubInfo.httpUrl.isNullOrBlank()) {
                                                                processingEmail = sub.sendEmail
                                                                scope.launch {
                                                                    val res = UnsubscribeHelper.executeOneClick(unsubInfo.httpUrl)
                                                                    processingEmail = ""
                                                                    if (res.isSuccess) {
                                                                        unsubscribedEmails.add(sub.sendEmail)
                                                                        onToast("已成功提交一键退订请求")
                                                                    } else {
                                                                        onToast("一键退订未响应，正在打开网页退订")
                                                                        UnsubscribeHelper.openInBrowserOrMail(context, unsubInfo)
                                                                    }
                                                                }
                                                            } else {
                                                                UnsubscribeHelper.openInBrowserOrMail(context, unsubInfo)
                                                            }
                                                        } else {
                                                            onToast("未找到有效的退订链接")
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Text(
                                                    text = if (unsubInfo?.isOneClick == true) "一键退订" else "退订",
                                                    style = t.caption1.copy(fontWeight = FontWeight.SemiBold),
                                                    color = c.blue,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
