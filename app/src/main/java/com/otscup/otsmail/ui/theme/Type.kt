package com.otscup.otsmail.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号/行高/字距都照 tokens.css 的字面值抄，单位 px→sp 直接用（393dp 基准）。
 * SF Pro 在 Android 上不存在，回退系统默认（Roboto/思源），字距保持原值。
 */
val otTypography = OTType(
    largeTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.37.sp, fontWeight = FontWeight.Bold),
    title1 = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.36.sp, fontWeight = FontWeight.Bold),
    title2 = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.35.sp, fontWeight = FontWeight.SemiBold),
    title3 = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 0.38.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.41).sp),
    subhead = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.24).sp),
    footnote = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.08).sp),
    caption1 = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    caption2 = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
)

data class OTType(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val body: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
)
