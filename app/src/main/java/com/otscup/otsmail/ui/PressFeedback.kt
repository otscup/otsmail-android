package com.otscup.otsmail.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * iOS 式按下反馈：按下瞬间整块微缩 + 压深，抬起才提交。
 * 与 Material 涟漪的区别：涟漪是「水面波纹」，按下反馈是「这块东西被你按进去了」。
 * 关掉 indication（涟漪）是关键，否则两层会叠在一起。
 *
 * @param pressedColor 按下时叠的颜色，深色通常是白色低透明度
 * @param shape 有圆角的可点区域必须传，否则压色会盖住圆角
 */
/**
 * 导航栏按钮（返回 / 保存 / 关闭）用这个：不做缩放，命中区不随动画变化。
 * 缩放会把按下瞬间的命中矩形往里收，34dp 的边缘点会被吃掉。
 */
@Composable
fun Modifier.tapArea(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.975f,
    pressedColor: Color = Color.Black.copy(alpha = 0.06f),
    shape: Shape = RoundedCornerShape(0),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    // 系统「移除动画」时只保留按下底色，不做缩放
    val reduce = com.otscup.otsmail.ui.motion.rememberReduceMotion()
    // 按下 90ms 立刻到位；松手用轻弹簧回弹（apple-design：按下轻缩放 + 松手回弹）
    val t by animateFloatAsState(
        targetValue = if (down) 1f else 0f,
        animationSpec = if (down) tween(90, easing = LinearEasing) else spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "press",
    )

    return this
        .graphicsLayer {
            val s = if (reduce) 1f else 1f - (1f - scale) * t
            scaleX = s
            scaleY = s
        }
        .clip(shape)
        .background(pressedColor.copy(alpha = pressedColor.alpha * t.coerceIn(0f, 1f)), shape)
        // 长按进多选：必须用 combinedClickable，clickable 只有 onClick
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}
