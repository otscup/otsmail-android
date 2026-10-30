package com.otscup.otsmail.ui.motion

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 松手时"滑过多少算归档"，按行宽比例。0.42 是 Gmail/Spark 那一档。 */
private const val ARCHIVE_FRACTION = 0.42f

/** 轻甩也算归档：超过这个速度就不用看位移了（px/s）。 */
private const val FLING_VELOCITY = 1200f

/**
 * 可左滑归档的列表行。手感按 apple-design：
 *
 * - 跟手 1:1（从按下点算增量，不是从中心，抓哪儿算哪儿）
 * - 10dp 滞回：先确认是横向手势，竖向滚动不受影响
 * - 右边缘 24dp 不接管：那段留给系统返回手势
 * - 松手用速度判方向（分量+投影），再交还速度给弹簧，没有接缝
 * - 往回拖时按橡皮筋衰减，不是硬停
 * - 全程可打断：任何时刻再按住都会从当前位移继续，不从头播放
 *
 * 用法：把行内容放进 content，onArchive 里做真正的删除/归档调用。
 */
@Composable
fun SwipeToArchiveRow(
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val d = OT.dimens
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = widthPx * ARCHIVE_FRACTION

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(widthPx, enabled) {
                if (!enabled || widthPx <= 0f) return@pointerInput
                val edgeGuard = 24.dp.toPx()
                val hysteresis = 10.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > widthPx - edgeGuard) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var travel = 0f
                    drag(down.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.positionChange().x
                        if (!dragging) {
                            travel += dx
                            if (abs(travel) < hysteresis) {
                                change.consume()
                                return@drag
                            }
                            dragging = true
                        }
                        val raw = offsetX + dx
                        offsetX = if (raw > 0f) rubberBand(raw, widthPx) else raw.coerceIn(-widthPx, 0f)
                        change.consume()
                    }
                    if (!dragging) return@awaitEachGesture
                    val vx = tracker.calculateVelocity().x
                    val projected = offsetX + projectMomentum(vx)
                    val archive = projected < -threshold || vx < -FLING_VELOCITY
                    scope.launch {
                        animate(
                            initialValue = offsetX,
                            targetValue = if (archive) -widthPx else 0f,
                            initialVelocity = if (archive) 0f else vx,
                            animationSpec = if (archive) OTSpring.settle() else OTSpring.momentum(),
                        ) { value, _ -> offsetX = value }
                        if (archive) {
                            onArchive()
                            offsetX = 0f
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(c.red, RoundedCornerShape(d.rMd.dp)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = "归档",
                style = t.subhead,
                color = Color.White,
                modifier = Modifier.padding(end = d.sp4.dp),
            )
        }
        Box(
            modifier = Modifier.graphicsLayer { translationX = offsetX },
        ) { content() }
    }
}
