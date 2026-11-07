package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otscup.otsmail.data.ExtProviders

/**
 * 外部邮箱服务商标识：品牌色圆角方块 + 字母/首字。
 * 纯 Compose 绘制，不引入第三方图片资源；白字压在饱和底色上，深浅色模式都可辨认。
 * 尺寸默认 26dp，与列表行 48dp 的最小行高对齐，不撑高行。
 */
internal data class ExtBadgeSpec(val label: String, val color: Color, val name: String)

internal fun extBadgeSpec(providerId: String, address: String): ExtBadgeSpec {
    val id = providerId.ifEmpty { ExtProviders.detect(address)?.id.orEmpty() }
    // foxmail.com 复用 QQ 的服务器，但标识按域名区分
    val foxmail = id == "qq" && address.substringAfterLast("@", "").lowercase().let {
        it == "foxmail.com" || it.endsWith(".foxmail.com")
    }
    return when {
        id == "gmail" -> ExtBadgeSpec("M", Color(0xFFEA4335), "Gmail")
        id == "outlook" -> ExtBadgeSpec("O", Color(0xFF0F6CBD), "Outlook")
        id == "netease163" -> ExtBadgeSpec("163", Color(0xFFD71A1B), "网易 163")
        id == "netease126" -> ExtBadgeSpec("126", Color(0xFFB71C1C), "网易 126")
        foxmail -> ExtBadgeSpec("F", Color(0xFFF5A623), "Foxmail")
        id == "qq" -> ExtBadgeSpec("QQ", Color(0xFF12B7F5), "QQ 邮箱")
        else -> ExtBadgeSpec("@", Color(0xFF8E8E93), "其他邮箱")
    }
}

@Composable
internal fun ExtProviderBadge(
    providerId: String,
    address: String,
    size: Dp = 26.dp,
) {
    val spec = remember(providerId, address) { extBadgeSpec(providerId, address) }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(spec.color)
            .semantics { contentDescription = spec.name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            spec.label,
            color = Color.White,
            fontSize = when (spec.label.length) {
                1 -> 13.sp
                2 -> 10.sp
                else -> 9.sp
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
