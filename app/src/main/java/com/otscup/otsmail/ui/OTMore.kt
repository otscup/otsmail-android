package com.otscup.otsmail.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forward
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.ReplyAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.R
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/** 内联导航栏（.navbar：44 高，左 12×20 蓝 chevron，居中 body semibold，右 22 图标） */
@Composable
fun OTNavBar(
    title: String = "",
    onLeading: (() -> Unit)? = null,
    leadingText: String = "‹",
    trailing: @Composable (RowScope.() -> Unit) = {},
) {
    val t = OT.type
    val c = OT.colors
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp)) {
        if (onLeading != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                    .tapArea { onLeading() },
                contentAlignment = Alignment.Center,
            ) {
                if (leadingText == "‹") {
                    Icon(
                        painterResource(R.drawable.ot_chevron_left),
                        contentDescription = "返回",
                        tint = c.blue,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(leadingText, style = t.body, color = c.blue)
                }
            }
        }
        Text(
            title,
            style = t.body.copy(fontWeight = FontWeight.SemiBold),
            color = c.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 88.dp),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

/** 表单行（.row--field：标签 13 label2 + 输入 17，8/16/9，最小 56） */
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
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = OT.type
    val c = OT.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = t.footnote, color = c.label2, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            readOnly = readOnly,
            textStyle = t.body.copy(color = if (readOnly) c.label2 else c.label),
            cursorBrush = SolidColor(c.blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = t.body, color = c.label3)
                inner()
            },
        )
    }
}

/** 蓝色主按钮（.btn--filled：48 高、12 圆角、body semibold） */
@Composable
fun OTPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .background(if (enabled) c.blue else c.label3.copy(alpha = 0.35f))
            .tapArea { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent)
    }
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
        OTSwitch(checked, onChange)
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

/** 详情页底部动作条（.actionbar：bg-bar + 上边线；21 图标 + 11 label2） */
@Composable
fun OTActionBar(items: List<Triple<String, String, () -> Unit>>, dangerIndex: Int = -1) {
    val t = OT.type
    val c = OT.colors
    Column(Modifier.fillMaxWidth().background(c.bgBar)) {
        OTDivider(strong = true)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { i, item ->
                val tint = if (i == dangerIndex) c.red else c.label2
                Column(
                    Modifier
                        .width(72.dp)
                        .heightIn(min = OT.dimens.actionH.dp)
                        .clip(RoundedCornerShape(OT.dimens.rMd.dp))
                        .tapArea { item.third() }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val glyph = actionIcon(item.first)
                    if (glyph != null) {
                        Icon(glyph, contentDescription = item.second, tint = tint, modifier = Modifier.size(24.dp))
                    } else {
                        Text(item.first, style = t.title3, color = tint)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(item.second, style = t.caption2, color = tint)
                }
            }
        }
    }
}

/** 通用底部 sheet（.sheet：顶部 20 圆角、36×5 抓手、bg-elevated） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OTSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = OT.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.bgElevated,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = OT.dimens.sheetGrabber.dp, height = 5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.label4),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            content()
        }
    }
}

@Composable
private fun actionIcon(glyph: String): Painter? = when (glyph) {
    "↩" -> painterResource(R.drawable.ot_reply)
    "⇉" -> painterResource(R.drawable.ot_reply_all)
    "↪" -> painterResource(R.drawable.ot_forward)
    "⋯" -> painterResource(R.drawable.ot_more)
    "✕", "🗑" -> painterResource(R.drawable.ot_trash)
    else -> null
}
