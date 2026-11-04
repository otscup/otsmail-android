package com.otscup.otsmail.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * tokens.css 的 1:1 映射（2025 版）。改颜色只改这里，别在页面里写 Color(0xFF...)。
 * 亮色对应 :root，暗色对应 [data-theme=dark]。
 */
data class OTColors(
    val bgGrouped: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val bgBar: Color,
    val bgDrawer: Color,
    val bgInput: Color,
    val bgPressed: Color,
    val label: Color,
    val label2: Color,
    val label3: Color,
    val label4: Color,
    val separator: Color,
    val separatorSoft: Color,
    val separatorOpaque: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val purple: Color,
    val teal: Color,
    val indigo: Color,
    val gray: Color,
    val acc1: Color,
    val acc2: Color,
    val acc3: Color,
    val acc4: Color,
    val acc5: Color,
    val acc6: Color,
    val badgeBg: Color,
    val badgeFg: Color,
    val onAccent: Color,
    val logoGradStart: Color,
    val logoGradEnd: Color,
    val scrim: Color,
    val toastBg: Color,
    val toastFg: Color,
    val tintIcon: Float,
    val tintChip: Float,
    val tintSelect: Float,
)

val LightOTColors = OTColors(
    bgGrouped = Color(0xFFF2F2F7),
    bgCard = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFFFFFFF),
    bgBar = Color(0xD1F9F9F9),        // rgba(249,249,249,.82)
    bgDrawer = Color(0xFFFFFFFF),
    bgInput = Color(0x1F767680),      // rgba(118,118,128,.12)
    bgPressed = Color(0x0F000000),    // rgba(0,0,0,.06)
    label = Color(0xFF000000),
    label2 = Color(0x993C3C43),       // rgba(60,60,67,.6)
    label3 = Color(0x4D3C3C43),       // .3
    label4 = Color(0x2E3C3C43),       // .18
    separator = Color(0x4A3C3C43),    // .29
    separatorSoft = Color(0x263C3C43), // color-mix(separator 52%, transparent)
    separatorOpaque = Color(0xFFC6C6C8),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    purple = Color(0xFFAF52DE),
    teal = Color(0xFF30B0C7),
    indigo = Color(0xFF5856D6),
    gray = Color(0xFF8E8E93),
    acc1 = Color(0xFF0A84FF),
    acc2 = Color(0xFF30B0C7),
    acc3 = Color(0xFFFFD60A),
    acc4 = Color(0xFFFF9F0A),
    acc5 = Color(0xFFFF453A),
    acc6 = Color(0xFFBF5AF2),
    badgeBg = Color(0xFFFF6A2B),
    badgeFg = Color(0xFFFFFFFF),
    onAccent = Color(0xFFFFFFFF),
    logoGradStart = Color(0xFF4FACFF),
    logoGradEnd = Color(0xFF0057D9),
    scrim = Color(0x66000000),        // rgba(0,0,0,.4)
    toastBg = Color(0xEB1E1E20),
    toastFg = Color(0xFFFFFFFF),
    tintIcon = 0.14f,
    tintChip = 0.12f,
    tintSelect = 0.12f,
)

val DarkOTColors = OTColors(
    bgGrouped = Color(0xFF000000),
    bgCard = Color(0xFF1C1C1E),
    bgElevated = Color(0xFF2C2C2E),
    bgBar = Color(0xCC161618),        // rgba(22,22,24,.8)
    bgDrawer = Color(0xFF1C1C1E),
    bgInput = Color(0x3D767680),      // rgba(118,118,128,.24)
    bgPressed = Color(0x14FFFFFF),    // rgba(255,255,255,.08)
    label = Color(0xFFFFFFFF),
    label2 = Color(0x99EBEBF5),       // rgba(235,235,245,.6)
    label3 = Color(0x4DEBEBF5),
    label4 = Color(0x29EBEBF5),       // .16
    separator = Color(0x99545458),    // rgba(84,84,88,.6)
    separatorSoft = Color(0x4F545458),
    separatorOpaque = Color(0xFF38383A),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    purple = Color(0xFFBF5AF2),
    teal = Color(0xFF40C8E0),
    indigo = Color(0xFF5E5CE6),
    gray = Color(0xFF8E8E93),
    acc1 = Color(0xFF0A84FF),
    acc2 = Color(0xFF30B0C7),
    acc3 = Color(0xFFFFD60A),
    acc4 = Color(0xFFFF9F0A),
    acc5 = Color(0xFFFF453A),
    acc6 = Color(0xFFBF5AF2),
    badgeBg = Color(0xFFFF7A3D),
    badgeFg = Color(0xFFFFFFFF),
    onAccent = Color(0xFFFFFFFF),
    logoGradStart = Color(0xFF3F9DFF),
    logoGradEnd = Color(0xFF0040A0),
    scrim = Color(0x8F000000),        // rgba(0,0,0,.56)
    toastBg = Color(0xF03A3A3C),
    toastFg = Color(0xFFFFFFFF),
    tintIcon = 0.26f,
    tintChip = 0.24f,
    tintSelect = 0.20f,
)
