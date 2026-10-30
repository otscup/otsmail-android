package com.otscup.otsmail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 从右滑入、右滑可拖拽返回的页面容器（模仿 UINavigationController push）。
 *
 * 三条与手感有关的决定：
 * 1. 进场用 240ms 的 cubic-bezier(0.2, 0, 0, 1)（跟样张的 --ease-ios 同族），不用 Material 默认的 300ms；
 * 2. 拖拽期间直接跟手（dx 每帧同步更新），不走动画——抬手才用 spring 决定回弹还是退出；
 * 3. 松手阈值 32% 屏宽，回弹用 dampingRatio = 1f 的临界阻尼（不弹跳），退出用 200ms 收尾。
 *
 * shortcut: 拖拽时底下的页面不会跟着视差后移（iOS 是这样做的），等需要更强的层次感再补。
 */
@Composable
fun SlidePage(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val anim = remember { Animatable(widthPx) }
    var dx by remember { mutableFloatStateOf(widthPx) }
    var dragging by remember { mutableStateOf(false) }

    // 进场：从右侧滑进来（第一帧就在屏外，避免闪一下）
    LaunchedEffect(Unit) {
        anim.animateTo(0f, tween(240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }

    val drag = Modifier.pointerInput(widthPx) {
        detectHorizontalDragGestures(
            onDragStart = {
                dragging = true
                dx = anim.value
            },
            onHorizontalDrag = { change, delta ->
                change.consume()
                dx = (dx + delta).coerceIn(0f, widthPx)
            },
            onDragEnd = {
                dragging = false
                val dismiss = dx > widthPx * 0.32f
                scope.launch {
                    anim.snapTo(dx)
                    if (dismiss) {
                        anim.animateTo(widthPx, tween(200, easing = FastOutLinearInEasing))
                        onDismiss()
                    } else {
                        anim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f))
                    }
                }
            },
            onDragCancel = {
                dragging = false
                scope.launch {
                    anim.snapTo(dx)
                    anim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = if (dragging) dx else anim.value },
    ) {
        Box(Modifier.fillMaxSize().then(drag)) { content() }
        // 左缘 28dp 的透明热区：WebView 自绘会吃掉落在它身上的横向手势，只有这一条能稳定抢到
        Box(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .align(Alignment.CenterStart)
                .then(drag),
        )
    }
}
