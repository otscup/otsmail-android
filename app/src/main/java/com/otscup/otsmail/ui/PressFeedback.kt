package com.otscup.otsmail.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * iOS 寮忔寜涓嬪弽棣堬細鎸変笅鐬棿鏁村潡寰缉 + 鍘嬫繁锛屾姮璧锋墠鎻愪氦銆? *
 * 鍜?Material 娑熸吉鐨勫尯鍒細娑熸吉鏄€屾按闈㈡尝绾广€嶏紝鎸変笅鍙嶉鏄€岃繖鍧椾笢瑗胯浣犳寜杩涘幓浜嗐€嶃€? * 鍏虫帀 indication锛堟稛婕級鏄叧閿紝鍚﹀垯涓ゅ眰浼氬彔鍦ㄤ竴璧枫€? *
 * @param pressedColor 鎸変笅鏃跺彔鐨勯鑹诧紝娣辫壊閫氬父鏄櫧鑹蹭綆閫忔槑搴? * @param shape 鏈夊渾瑙掔殑鍙偣鍖哄煙蹇呴』浼狅紝鍚﹀垯鍘嬭壊浼氱洊浣忓渾瑙? */
/**
 * 瀵艰埅鏍忔寜閽紙杩斿洖 / 淇濆瓨 / 鍏抽棴锛夌敤杩欎釜锛氫笉鍋氱缉鏀撅紝鍛戒腑鍖轰笉闅忓姩鐢诲彉鍖栥€? * 缂╂斁浼氭妸鎸変笅鐬棿鐨勫懡涓煩褰㈠線閲屾敹锛?4dp 鐨勮竟缂樼偣浼氳鍚冩帀銆? */
@Composable
fun Modifier.tapArea(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.975f,
    pressedColor: Color = Color.Black.copy(alpha = 0.06f),
    shape: Shape = RoundedCornerShape(0),
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    // 90ms锛氱煭鍒版劅瑙夋槸鐬椂鐨勶紝闀垮埌鑲夌溂鑳界湅瑙佽繃娓★紱涓嶈兘鐢?spring锛屾寜涓嬫€佽绔嬪埢鍒颁綅
    val t by animateFloatAsState(if (down) 1f else 0f, tween(90, easing = LinearEasing), label = "press")

    return this
        .graphicsLayer {
            val s = 1f - (1f - scale) * t
            scaleX = s
            scaleY = s
        }
        .clip(shape)
        .background(pressedColor.copy(alpha = pressedColor.alpha * t), shape)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}
