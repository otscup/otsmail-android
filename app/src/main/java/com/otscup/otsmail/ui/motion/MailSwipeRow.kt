package com.otscup.otsmail.ui.motion

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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

/** 松手时"滑过多少算触发"，按行宽比例。0.34 与 Gmail/Spark 的手感接近。 */
private const val TRIGGER_FRACTION = 0.34f

/** 轻甩也算触发：速度超过这个值就不看位移（px/s）。 */
private const val FLING_VELOCITY = 1200f

/** 行内起手必须离开左缘这么远——那 20dp 是 SlidePage / 系统返回手势的地盘。 */
private val LEFT_EDGE_GUARD = 20.dp

/**
 * 邮件行左右滑动：
 * - 右滑（内容向右走）= 回复，左侧露出蓝底 + 回复图标；
 * - 左滑 = 删除，右侧露出红底 + 垃圾桶图标；
 * - 只在行内跟手 1:1，其它行不动；
 * - 竖向滚动优先：横向位移必须先超过 touchSlop 且明显大于竖向位移才接管；
 * - 松手先按速度做动量投射，再决定"触发"还是"回弹"，弹簧接住手指速度（无接缝）；
 * - 触发时给一次轻触觉；多选态直接禁用，避免和长按/点选打架。
 */
@Composable
fun MailSwipeRow(
    onReply: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    // 松手后的回弹/触发动画：再次按下时先取消，从当前位移接着跟手（可中断）
    var animJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 从屏幕左缘 20dp 起手时置位：那一段留给返回手势，滑动不接管
    var blockDrag by remember { mutableStateOf(false) }

    // 进入多选/离开列表时把残余位移归零，避免切换瞬间露出半条动作底色
    LaunchedEffect(enabled) {
        if (!enabled) offsetX = 0f
    }

    val threshold = widthPx * TRIGGER_FRACTION
    val dragState = rememberDraggableState { delta ->
        if (!blockDrag) offsetX = resist(offsetX + delta, widthPx, widthPx)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                // 只观察、不消费：记录这次手势是不是从左缘开始的
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    blockDrag = down.position.x <= LEFT_EDGE_GUARD.toPx()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        pressed = ch.pressed
                    }
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                // 不立即接管：先等横向 touchSlop；竖向滚动由列表先拿走时这里就收不到
                startDragImmediately = false,
                onDragStarted = { animJob?.cancel() },
                onDragStopped = { vx ->
                    val projected = offsetX + projectMomentum(vx)
                    val reply = projected > threshold || vx > FLING_VELOCITY
                    val del = projected < -threshold || vx < -FLING_VELOCITY
                    animJob = scope.launch {
                        animate(
                            initialValue = offsetX,
                            targetValue = when {
                                reply -> widthPx
                                del -> -widthPx
                                else -> 0f
                            },
                            initialVelocity = if ((reply && vx > 0f) || (del && vx < 0f)) vx else 0f,
                            animationSpec = if (reply || del) OTSpring.settle() else OTSpring.momentum(),
                        ) { v, _ -> offsetX = v }
                        when {
                            reply -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                offsetX = 0f
                                onReply()
                            }
                            del -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                offsetX = 0f
                                onDelete()
                            }
                        }
                    }
                },
            ),
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
                painterResource(if (replying) R.drawable.ot_reply else R.drawable.ot_trash),
                contentDescription = if (replying) "回复" else "删除",
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
