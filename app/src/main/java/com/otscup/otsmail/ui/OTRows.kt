package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/**
 * 设置/管理页的标准行：可选左侧 30dp 同色 14% 圆角图标块 + 标题/副标题 + 右侧自定内容。
 * 对齐设计稿 v5：行高 >= 44，图标块 30，文字起点 58（16 边距 + 30 + 12）。
 */
@Composable
fun OTRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = OT.colors
    val t = OT.type
    val tint = iconTint ?: c.blue
    val base = if (onClick != null) {
        modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(pressedColor = c.bgInput) { onClick() }
    } else {
        modifier.fillMaxWidth().heightIn(min = 44.dp)
    }
    Row(base.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            ORIconTile(icon, tint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body, color = c.label, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = t.footnote, color = c.label2, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 设计稿里的左侧色块：30×30、圆角 7、同色 14% 底、图标 18dp。 */
@Composable
fun ORIconTile(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}
