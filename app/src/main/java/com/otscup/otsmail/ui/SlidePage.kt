package com.otscup.otsmail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
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
 * 与手势有关的决定：
 * 1. 只有**左缘 20dp 起手**的手势才可能触发转场；内容区（含 WebView 正文、竖向滚动）完全不挂横向手势。
 *    之前把 drag 挂在整块内容区上，斜着上下滑时 dx 会被直接注入 translationX → 整页左右晃、滚动被打断。
 * 2. 起手后先做主轴判定：|dx| 必须超过 8dp 且大于 1.6 倍 |dy| 才接管；竖向意图更明显时立刻放弃，把事件留给内容区。
 * 3. 拖拽期间直接跟手（dx 每帧同步更新），抬手才用 spring 决定回弹还是退出；松手阈值 32% 屏宽。
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
        val slop = 8.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var sumX = 0f
            var sumY = 0f
            var cur = anim.value
            var accepted = false
            dx = cur
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                val delta = change.positionChange()
                if (!accepted) {
                    sumX += delta.x
                    sumY += delta.y
                    if (abs(sumX) > slop && abs(sumX) > abs(sumY) * 1.6f) {
                        accepted = true
                        dragging = true
                    } else if (abs(sumY) > slop && abs(sumY) > abs(sumX)) {
                        // 竖向意图明确：不接管，让正文/列表继续滚
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
            .graphicsLayer {
                translationX = if (reduce) 0f else if (dragging) dx else anim.value
                // 轻微淡出：位移越大越透，避免整页像贴纸一样平移
                alpha = if (reduce) enterAlpha.value * dismissAlpha.value
                else ((enterAlpha.value * dismissAlpha.value) * (1f - 0.2f * (translationX / widthPx)).coerceIn(0f, 1f))
            },
    ) {
        Box(Modifier.fillMaxSize()) { content() }
        // 左缘 20dp 的透明热区：WebView 自绘会吃掉落在它身上的横向手势，只有这一条能稳定抢到。
        // 顶部让出 96dp，否则会盖住导航栏左上角的返回/取消按钮，点按永远到不了底层。
        Box(
            Modifier
                .fillMaxHeight()
                .width(20.dp)
                .align(Alignment.CenterStart)
                .padding(top = 96.dp),
        ) {
            Box(Modifier.fillMaxSize().then(edgeDrag))
        }
    }
}
