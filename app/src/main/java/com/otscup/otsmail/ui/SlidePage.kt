package com.otscup.otsmail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.motion.OTSpring
import com.otscup.otsmail.ui.motion.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 从右滑入、右滑可拖拽返回的页面容器（模仿 UINavigationController push）。
 *
 * 手势接在页面根节点上、只在 Initial 阶段观察，不再用透明热区覆盖左缘：
 * 1. 起手点必须在**左缘 24dp** 内；更靠里的横向手势留给内容（WebView 横向滚动、列表行左右滑）。
 * 2. 只认**向右**的主轴位移：|dx| > 8dp 且 |dx| > |dy|（约 45°内）才接管，斜着滑也能返回；
 *    竖向或向左的意图立刻放弃，事件原样交给列表/正文，不会出现"斜滑整页晃"。
 * 3. 接管后在 Initial 阶段消费事件，子层（WebView/列表）收不到，不会边返回边滚动。
 * 4. 不消费起手 tap：左缘的返回按钮、输入框照常可点（旧实现用 20dp 热区+顶部让出 96dp，
 *    导致顶部边缘滑不动，也是"右滑返回时好时坏"的来源之一）。
 * 5. 松手阈值 32% 屏宽，回弹用弹簧；减少动画时只淡出。
 *
 * shortcut: 拖拽时底下的页面不会跟着视差后移（iOS 是这样做的），等需要更强的层次感再补。
 */
@Composable
fun SlidePage(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val anim = remember { Animatable(widthPx) }
    val enterAlpha = remember { Animatable(0f) }
    var dx by remember { mutableFloatStateOf(widthPx) }
    var dragging by remember { mutableStateOf(false) }
    // 系统「移除动画」/无障碍里的动画缩放为 0：只做很短的淡入淡出，不位移
    val reduce = rememberReduceMotion()
    val dismissAlpha = remember { Animatable(1f) }

    // 进场：轻微位移 + 渐变，弹簧收尾（不超调）；减少动画时只淡入
    LaunchedEffect(Unit) {
        launch { enterAlpha.animateTo(1f, tween(200)) }
        if (reduce) anim.snapTo(0f) else anim.animateTo(0f, OTSpring.move())
    }

    val edgeDrag = Modifier.pointerInput(widthPx) {
        val edge = 24.dp.toPx()
        val slop = 8.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 起手不在左缘：这不是返回手势，直接等这一轮指针结束
            if (down.position.x > edge) return@awaitEachGesture
            var sumX = 0f
            var sumY = 0f
            var cur = anim.value
            var accepted = false
            dx = cur
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                val delta = change.positionChange()
                if (!accepted) {
                    sumX += delta.x
                    sumY += delta.y
                    if (sumX > slop && sumX > abs(sumY)) {
                        accepted = true
                        dragging = true
                    } else if (abs(sumY) > slop && abs(sumY) > abs(sumX)) {
                        // 竖向意图明确：不接管，让正文/列表继续滚
                        break
                    } else if (sumX < -slop && abs(sumX) > abs(sumY)) {
                        // 向左滑不是返回：留给内容（例如邮件行左滑删除）
                        break
                    }
                }
                if (accepted) {
                    change.consume()
                    cur = (cur + delta.x).coerceIn(0f, widthPx)
                    dx = cur
                }
            }
            if (accepted) {
                dragging = false
                val end = cur
                scope.launch {
                    anim.snapTo(end)
                    if (end > widthPx * 0.32f) {
                        dismissAlpha.animateTo(0f, tween(160))
                        onDismiss()
                    } else if (reduce) {
                        dismissAlpha.snapTo(1f)
                        anim.snapTo(0f)
                    } else {
                        // 回弹用弹簧接住当前位移，不做硬切
                        anim.animateTo(0f, OTSpring.momentum())
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(edgeDrag)
            .graphicsLayer {
                translationX = if (reduce) 0f else if (dragging) dx else anim.value
                // 轻微淡出：位移越大越透，避免整页像贴纸一样平移
                alpha = if (reduce) enterAlpha.value * dismissAlpha.value
                else ((enterAlpha.value * dismissAlpha.value) * (1f - 0.2f * (translationX / widthPx)).coerceIn(0f, 1f))
            },
    ) {
        content()
    }
}
