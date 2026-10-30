package com.otscup.otsmail.ui
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Forward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/** 内联导航栏：44dp，左返回 / 居中标题 / 右侧动作（v5 定稿） */
@Composable
fun OTNavBar(
    title: String = "",
    onLeading: (() -> Unit)? = null,
    leadingText: String = "‹",
    trailing: @Composable (RowScope.() -> Unit) = {},
) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onLeading != null) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).tapArea { onLeading() },
                contentAlignment = Alignment.Center,
            ) {
                if (leadingText == "‹") {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = "返回",
                        tint = c.blue,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Text(leadingText, style = t.title2, color = c.blue)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = t.body, color = c.label, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 灰标签 + 无边框输入 + 可选“当前值”说明（v5 的 .row--field） */
@Composable
fun OTFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit = {},
    hint: String = "",
    secret: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
) {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = t.caption1, color = c.label3)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            readOnly = readOnly,
            textStyle = t.body.copy(color = if (readOnly) c.label2 else c.label),
            cursorBrush = SolidColor(c.blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = t.body, color = c.label3)
                inner()
            },
        )
    }
}

/** 蓝色主按钮（48dp） */
@Composable
fun OTPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) c.blue else c.label3.copy(alpha = 0.35f))
            .tapArea { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = t.body, color = Color.White) }
}

/** 分组卡里的一行开关 */
@Composable
fun OTSwitchRow(title: String, checked: Boolean, subtitle: String = "", onChange: (Boolean) -> Unit) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body, color = c.label)
            if (subtitle.isNotEmpty()) Text(subtitle, style = t.footnote, color = c.label2)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.green, uncheckedTrackColor = c.bgInput),
        )
    }
}

/** 分析页统计卡 */
@Composable
fun OTStat(label: String, value: String, sub: String = "") {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, style = t.footnote, color = c.label2)
        Text(value, style = t.title1, color = c.label)
        if (sub.isNotEmpty()) Text(sub, style = t.caption1, color = c.label3)
    }
}

/** 详情页底部动作条（图标 + 11pt 标签） */
@Composable
fun OTActionBar(items: List<Triple<String, String, () -> Unit>>, dangerIndex: Int = -1) {
    val t = OT.type
    val c = OT.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgCard).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { i, item ->
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(10.dp)).tapArea { item.third() }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val glyph = actionIcon(item.first)
                if (glyph != null) {
                    Icon(glyph, contentDescription = item.second, tint = if (i == dangerIndex) c.red else c.blue, modifier = Modifier.size(22.dp))
                } else {
                    Text(item.first, style = t.title3, color = if (i == dangerIndex) c.red else c.blue)
                }
                Spacer(Modifier.height(2.dp))
                Text(item.second, style = t.caption2, color = if (i == dangerIndex) c.red else c.label2)
            }
        }
    }
}

private fun actionIcon(glyph: String) = when (glyph) {
    "↩" -> Icons.Outlined.Reply
    "⇉" -> Icons.Outlined.ReplyAll
    "↪" -> Icons.Outlined.Forward
    "✕" -> Icons.Outlined.Delete
    "🗑" -> Icons.Outlined.Delete
    else -> null
}
