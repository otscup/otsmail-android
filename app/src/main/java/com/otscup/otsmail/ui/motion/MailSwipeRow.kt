package com.otscup.otsmail.ui.motion

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.R
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 松手时"滑过多少算触发"，按行宽比例。46% 比原来 34% 更保守，避免误删。 */
private const val TRIGGER_FRACTION = 0.46f

/** 横向位移必须明显大于竖向（2 倍，约 27°）才接管；30°/45° 斜滑一律让给竖向滚动。 */
private const val HORIZONTAL_LOCK_RATIO = 2.0f

/** 行内起手必须离开左缘这么远——那 20dp 是 SlidePage / 系统返回手势的地盘。 */
private val LEFT_EDGE_GUARD = 20.dp

/**
 * 邮件行左右滑动：
 * - 右滑（内容向右走）= 回复，左侧露出蓝底 + 回复图标；
 * - 左滑 = 删除，右侧露出红底 + 垃圾桶图标；
 * - 只在行内跟手 1:1，其它行不动；
 * - 竖向优先：竖向位移先过 touchSlop 就直接放行给列表；横向必须超过竖向 2 倍才接管；
 * - 松手不做惯性投影、也不看甩动速度，只按当前实际位移是否过 46% 行宽判定；
 * - 触发时给一次轻触觉；多选态直接禁用，避免和长按/点选打架。
 */
@Composable
fun MailSwipeRow(
    onReply: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 右滑动作的图标/说明：已删除列表里右滑是「恢复」，不是「回复」 */
    rightIcon: Int = R.drawable.ot_reply,
    rightLabel: String = "回复",
    content: @Composable () -> Unit,
) {
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    // 松手后的回弹/触发动画：再次按下时先取消，从当前位移接着跟手（可中断）
    var animJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 从屏幕左缘 20dp 起手时，那一段留给 SlidePage / 系统返回手势，滑动不接管

    // 进入多选/离开列表时把残余位移归零，避免切换瞬间露出半条动作底色
    LaunchedEffect(enabled) {
        if (!enabled) offsetX = 0f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(enabled, widthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    // 左缘 20dp 留给系统返回手势
                    if (down.position.x <= LEFT_EDGE_GUARD.toPx()) return@awaitEachGesture
                    animJob?.cancel()
                    val slop = viewConfiguration.touchSlop
                    val activeId = down.id
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalDx = 0f
                    var totalDy = 0f
                    var locked = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val ch = event.changes.firstOrNull { it.id == activeId } ?: break
                        if (!ch.pressed) break
                        // 竖向滚动已经把这个事件拿走时，不再横向接管
                        if (!locked && ch.isConsumed) break
                        val dx = ch.position.x - lastX
                        val dy = ch.position.y - lastY
                        lastX = ch.position.x
                        lastY = ch.position.y
                        totalDx += dx
                        totalDy += dy
                        if (!locked) {
                            // 竖向优先：竖向先过 slop 就直接把这次手势让给列表
                            if (abs(totalDy) > slop && abs(totalDy) >= abs(totalDx)) break
                            // 横向必须明显大于竖向（2 倍）才锁定；30°/45° 斜滑不接管
                            if (abs(totalDx) > slop && abs(totalDx) > abs(totalDy) * HORIZONTAL_LOCK_RATIO) {
                                locked = true
                            } else {
                                continue
                            }
                        }
                        ch.consume()
                        offsetX = resist(offsetX + dx, widthPx, widthPx)
                    }
                    if (!locked) return@awaitEachGesture

                    // 松手：不做惯性投影、不看甩动速度，只按当前实际位移判定
                    val threshold = widthPx * TRIGGER_FRACTION
                    val target = when {
                        offsetX >= threshold -> widthPx
                        offsetX <= -threshold -> -widthPx
                        else -> 0f
                    }
                    animJob = scope.launch {
                        animate(
                            initialValue = offsetX,
                            targetValue = target,
                            initialVelocity = 0f,
                            animationSpec = if (target != 0f) OTSpring.settle() else OTSpring.move(),
                        ) { v, _ -> offsetX = v }
                        if (target > 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            offsetX = 0f
                            onReply()
                        } else if (target < 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            offsetX = 0f
                            onDelete()
                        }
                    }
                }
            },
    ) {
        // 动作底色按方向切换：右滑（内容右移）露蓝 = 回复，左滑露红 = 删除。
        // 两层满铺底色叠在一起时后画的会盖住前一层，所以这里只画一层、按位移符号换色。
        val replying = offsetX > 0f
        Box(
            Modifier
                .matchParentSize()
                .background(if (replying) c.blue else c.red),
            contentAlignment = if (replying) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(
                painterResource(if (replying) rightIcon else R.drawable.ot_trash),
                contentDescription = if (replying) rightLabel else "删除",
                tint = c.onAccent,
                modifier = (if (replying) Modifier.padding(start = 24.dp) else Modifier.padding(end = 24.dp)).size(24.dp),
            )
        }
        Box(Modifier.graphicsLayer { translationX = offsetX }) { content() }
    }
}

/** 行宽内 1:1 跟手；只有拖过整行才进橡皮筋（越界越沉）。 */
private fun resist(raw: Float, width: Float, limit: Float): Float =
    when {
        raw > limit -> limit + rubberBand(raw - limit, width)
        raw < -limit -> -(limit + rubberBand(-raw - limit, width))
        else -> raw
    }
