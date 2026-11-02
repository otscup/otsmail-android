package com.otscup.otsmail.ui
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.Search

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder

/** 一组 inset 卡片（对应样张 .group：灰底上浮一张白卡，卡内行间才有发丝线） */
@Composable
fun OTGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OT.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgCard),
        content = { content() },
    )
}

/** 卡外分组标题（.group__header）：上 24 / 下 8 / 左 20，对齐卡内文字起点 */
@Composable
fun OTGroupHeader(text: String) {
    val t = OT.type; val c = OT.colors
    Text(
        text,
        style = t.footnote,
        color = c.label2,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** 列表内的日期标题（.day：13/18、ink2、14/16/8） */
@Composable
fun OTDayHeader(text: String) {
    Text(
        text,
        style = OT.type.footnote,
        color = OT.colors.label2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
    )
}

/** 卡内发丝线（样张 .row + .row::before left:58） */
@Composable
fun OTDivider(inset: Int = 0) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset.dp)
            .height(0.5.dp)
            .background(OT.colors.separator),
    )
}

/** 抽屉分组之间的虚分隔（样张 .drawer__dash：1.5px dotted、左右 18dp）。 */
@Composable
fun OTDash() {
    val sep = OT.colors.separator
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = OTSpace.s2)
            .height(1.5.dp),
    ) {
        drawLine(
            color = sep,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
        )
    }
}

/** 大标题（.large-title 34/41） */
@Composable
fun OTLargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OT.type.largeTitle, color = OT.colors.label, modifier = modifier)
}

/** 搜索胶囊（.searchbar：高 36、圆角 10、bgInput，右侧可挂一个 AI 圆钮） */
@Composable
fun OTSearchPill(
    value: String,
    placeholder: String = "搜索邮件",
    onValueChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = OT.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.bgInput)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = c.label3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = OT.type.subhead, color = c.label3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = OT.type.subhead.copy(color = c.label),
                cursorBrush = SolidColor(c.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

/** 胶囊标签（.chip：高 20、圆角 10、11pt） */
@Composable
fun OTChip(text: String, tint: Color) {
    val c = OT.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.bgPressed)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(text, style = OT.type.caption2, color = c.label2, maxLines = 1)
    }
}

/** 首字母头像（.avatar：34 圆、按名字取色） */
@Composable
fun OTAvatar(name: String, size: Int = 34) {
    val c = OT.colors
    val palette = listOf(c.blue, c.green, c.orange, c.purple, c.teal, c.indigo, c.red)
    val color = palette[(name.hashCode().let { if (it < 0) -it else it }) % palette.size]
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = OT.type.subhead.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
        )
    }
}

/** 邮件行（.row：min-height 86、内边距 11/12/11/10、点 8 + 头像 34 + 主列 + 时间 + 星标 36） */
@Composable
fun OTMailRow(
    from: String,
    subject: String,
    snippet: String,
    time: String,
    unread: Boolean,
    starred: Boolean,
    category: String?,
    onOpen: () -> Unit,
    onStar: () -> Unit,
) {
    val t = OT.type; val c = OT.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 86.dp)
            .pressable(scale = 0.99f, pressedColor = c.bgPressed, shape = RoundedCornerShape(0.dp), onClick = onOpen)
            .padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp), contentAlignment = Alignment.Center) {
            if (unread) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.blue))
            }
        }
        Spacer(Modifier.width(10.dp))
        OTAvatar(from, 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    from, style = t.subhead.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                    color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, false),
                )
                if (!category.isNullOrEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    OTChip(category, c.gray)
                }
                Spacer(Modifier.weight(1f))
                Text(time, style = t.caption1, color = c.label3, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subject, style = t.body.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(snippet, style = t.subhead, color = c.label2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(36.dp)
                .tapArea { onStar() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (starred) "取消星标" else "标星",
                tint = if (starred) c.orange else c.label3,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 8pt grid spacing tokens - use these instead of raw .dp */
object OTSpace {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s7 = 32.dp; val s8 = 44.dp
    val cardGap = 8.dp
}
