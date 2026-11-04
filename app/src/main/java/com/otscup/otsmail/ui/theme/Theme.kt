package com.otscup.otsmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 结构尺寸，照 tokens.css 的 8pt 网格；页面里不要写裸 dp。 */
data class OTDimens(
    val statusH: Int = 54,
    val navH: Int = 44,
    val homeH: Int = 34,
    val tap: Int = 44,
    val screenPad: Int = 16,
    val rowMin: Int = 44,
    val sp1: Int = 4,
    val sp2: Int = 8,
    val sp3: Int = 12,
    val sp4: Int = 16,
    val sp5: Int = 20,
    val sp6: Int = 24,
    val sp8: Int = 32,
    val rXs: Int = 4,
    val rSm: Int = 8,
    val rMd: Int = 12,
    val rLg: Int = 16,
    val rCard: Int = 20,
    val rDrawer: Int = 28,
    val rIcon: Int = 7,
    val rPill: Int = 100,
    val drawerW: Int = 322,
    val drawerGap: Int = 8,
    val mailRowH: Int = 78,
    val searchH: Int = 44,
    val fabSize: Int = 52,
    val actionH: Int = 46,
    val sheetGrabber: Int = 36,
)

val LocalOTColors = staticCompositionLocalOf { LightOTColors }
val LocalOTType = staticCompositionLocalOf { otTypography }
val LocalOTDimens = staticCompositionLocalOf { OTDimens() }

@Composable
fun OTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkOTColors else LightOTColors
    val scheme = if (darkTheme) {
        darkColorScheme(primary = colors.blue, background = colors.bgGrouped, surface = colors.bgCard, onSurface = colors.label)
    } else {
        lightColorScheme(primary = colors.blue, background = colors.bgGrouped, surface = colors.bgCard, onSurface = colors.label)
    }

    CompositionLocalProvider(
        LocalOTColors provides colors,
        LocalOTType provides otTypography,
        LocalOTDimens provides OTDimens(),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 页面里统一从这里取 token：val ot = OTTheme.tokens（扩展见 OTTokenAccess.kt）。 */
object OT {
    val colors: OTColors
        @Composable get() = LocalOTColors.current
    val type: OTType
        @Composable get() = LocalOTType.current
    val dimens: OTDimens
        @Composable get() = LocalOTDimens.current

    /** dp 便捷值（很多地方只想要 dp） */
    fun dp(v: Int) = v.dp
}
