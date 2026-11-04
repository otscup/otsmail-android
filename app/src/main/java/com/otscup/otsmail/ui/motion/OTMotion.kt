package com.otscup.otsmail.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.abs

/**
 * 手感常量。数值来自 apple-design（WWDC "Designing Fluid Interfaces"，Emil Kowalski 的翻译版）。
 *
 * Apple 用 damping(0..1) + response(秒)：response 不是时长，是弹簧"到达目标"的快慢。
 * Compose 用 dampingRatio + stiffness，质量归一(mass=1)时：stiffness = (2π / response)²。
 * 所以 response 0.4 → 247，0.3 → 439；别用 Compose 的 StiffnessMedium(1500)，那快得发硬。
 */
object OTSpring {
    fun of(responseSec: Float, dampingRatio: Float): SpringSpec<Float> =
        spring(dampingRatio = dampingRatio, stiffness = stiffnessFor(responseSec))

    fun stiffnessFor(responseSec: Float): Float {
        val omega = (2 * PI / responseSec).toFloat()
        return omega * omega
    }

    /** 默认：移动、重定位。临界阻尼，不超调（damping 1.0 / response 0.4）。 */
    fun move(): SpringSpec<Float> = of(0.4f, 1f)

    /** 松手后带惯性的回弹。只在手指真的甩过之后用（damping 0.8 / response 0.4）。 */
    fun momentum(): SpringSpec<Float> = of(0.4f, 0.8f)

    /** 抽屉 / 上拉面板（damping 0.8 / response 0.3）。 */
    fun sheet(): SpringSpec<Float> = of(0.3f, 0.8f)

    /** 快速归位、不允许超调（response 0.3 / damping 1.0）。 */
    fun settle(): SpringSpec<Float> = of(0.3f, 1f)
}

/**
 * 橡皮筋：越界越多，跟手越少。真实的边界是"越拖越沉"，不是"撞墙"。
 * 与 apple-design 第 9 节同一公式。
 */
fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    if (overshoot == 0f || dimension <= 0f) 0f
    else (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/**
 * 动量投射：松手后按速度推算"本来会停在哪"，再据此决定吸附点。
 * 用 Apple 交付的指数衰减式，不是物理课本的 v²/2a。d≈0.998 是常规滚动手感。
 */
fun projectMomentum(velocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
    (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)

/**
 * 系统「移除动画」（设置 → 无障碍 → 移除动画）会把 ANIMATOR_DURATION_SCALE 置 0。
 * 这时所有位移弹簧都应降级成很短的淡入淡出，而不是照常播放。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) == 0f
    }
}

/**
 * 按下反馈：按下立刻缩，松手用弹簧回弹。系统移除动画时不做缩放，只保留原有的按下底色。
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    if (rememberReduceMotion()) return this
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(durationMillis = 90) else spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
