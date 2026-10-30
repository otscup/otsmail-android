package com.otscup.otsmail.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * tokens.css 的 1:1 映射。改颜色只改这里，别在页面里写 Color(0xFF...)。
 * 亮色对应 :root，暗色对应 [data-theme=dark]。
 */
data class OTColors(
    val bgGrouped: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val bgInput: Color,
    val bgPressed: Color,
    val label: Color,
    val label2: Color,
    val label3: Color,
    val separator: Color,
    val separatorOpaque: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val purple: Color,
    val teal: Color,
    val indigo: Color,
    val gray: Color,
    val tintIcon: Float,
    val tintChip: Float,
)

val LightOTColors = OTColors(
    bgGrouped = Color(0xFFF2F2F7),
    bgCard = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFFFFFFF),
    bgInput = Color(0x1F767680),      // rgba(118,118,128,.12)
    bgPressed = Color(0xFFE5E5EA),
    label = Color(0xFF000000),
    label2 = Color(0x993C3C43),       // rgba(60,60,67,.6)
    label3 = Color(0x4D3C3C43),       // .3
    separator = Color(0x4A3C3C43),    // rgba(60,60,67,.29)
    separatorOpaque = Color(0xFFC6C6C8),
    blue = Color(0xFF0A6CFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    purple = Color(0xFFAF52DE),
    teal = Color(0xFF30B0C7),
    indigo = Color(0xFF5856D6),
    gray = Color(0xFF8E8E93),
    tintIcon = 0.14f,
    tintChip = 0.12f,
)

val DarkOTColors = OTColors(
    bgGrouped = Color(0xFF000000),
    bgCard = Color(0xFF1C1C1E),
    bgElevated = Color(0xFF2C2C2E),
    bgInput = Color(0x3D767680),      // 暗色下灰底要更亮一点才看得见
    bgPressed = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    label2 = Color(0x99EBEBF5),       // rgba(235,235,245,.6)
    label3 = Color(0x4DEBEBF5),
    separator = Color(0x99545458),    // rgba(84,84,88,.6)
    separatorOpaque = Color(0xFF38383A),
    blue = Color(0xFF3B87FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    purple = Color(0xFFBF5AF2),
    teal = Color(0xFF40C8E0),
    indigo = Color(0xFF5E5CE6),
    gray = Color(0xFF8E8E93),
    tintIcon = 0.26f,
    tintChip = 0.24f,
)
