package com.otscup.otsmail.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 登录页第三方按钮的四个标志，路径直接取自 05-login.html 的 inline svg。
 * 只用于视觉占位，点击后提示"暂未接入"，不发任何网络请求。
 */
object BrandIcons {

    fun github(ink: Color): ImageVector = ImageVector.Builder("github", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M12 2.2a9.8 9.8 0 0 0-3.1 19.1c.5.1.7-.2.7-.5v-1.7c-2.7.6-3.3-1.3-3.3-1.3-.4-1.1-1.1-1.4-1.1-1.4-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.3 1.1 2.9.8.1-.6.3-1.1.6-1.3-2.2-.2-4.5-1.1-4.5-4.9 0-1.1.4-2 1-2.7-.1-.2-.4-1.3.1-2.7 0 0 .8-.3 2.7 1a9.3 9.3 0 0 1 4.9 0c1.9-1.2 2.7-1 2.7-1 .5 1.4.2 2.5.1 2.7.6.7 1 1.6 1 2.7 0 3.8-2.3 4.7-4.5 4.9.3.3.7 1 .7 2v3c0 .3.2.6.7.5A9.8 9.8 0 0 0 12 2.2Z",
            ),
            fill = SolidColor(ink),
        )
        .build()

    fun google(ink: Color): ImageVector = ImageVector.Builder("google", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M12 10.2v3.9h5.5c-.2 1.3-1.6 3.9-5.5 3.9a6 6 0 0 1 0-12c1.7 0 2.9.7 3.6 1.4l2.5-2.4A9.3 9.3 0 0 0 12 2.6a9.4 9.4 0 1 0 0 18.8c5.4 0 9-3.8 9-9.2 0-.6-.1-1.1-.2-1.6H12Z",
            ),
            fill = SolidColor(ink),
        )
        .build()

    fun linuxDo(ink: Color, paper: Color): ImageVector = ImageVector.Builder("linuxdo", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes("M12 2.8a5.2 6.2 0 1 0 0 12.4a5.2 6.2 0 1 0 0-12.4Z"),
            fill = SolidColor(ink),
        )
        .addPath(
            pathData = addPathNodes("M9.8 6.9a1.1 1.5 0 1 0 0 3a1.1 1.5 0 1 0 0-3Z"),
            fill = SolidColor(paper),
        )
        .addPath(
            pathData = addPathNodes("M14.2 6.9a1.1 1.5 0 1 0 0 3a1.1 1.5 0 1 0 0-3Z"),
            fill = SolidColor(paper),
        )
        .addPath(
            pathData = addPathNodes("M10 12.4h4"),
            stroke = SolidColor(ink),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
        )
        .addPath(
            pathData = addPathNodes(
                "M12 15c1.6 0 3 .9 3.5 2.2l.8 2.4a1.6 1.6 0 0 1-1.5 2.1H9.2a1.6 1.6 0 0 1-1.5-2.1l.8-2.4C9 15.9 10.4 15 12 15Z",
            ),
            fill = SolidColor(ink),
        )
        .build()

    fun shield(ink: Color): ImageVector = ImageVector.Builder("shield", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes("M12 2.6 20 6v6.1c0 4.6-3.4 8.4-8 9.3-4.6-.9-8-4.7-8-9.3V6l8-3.4Z"),
            stroke = SolidColor(ink),
            strokeLineWidth = 1.7f,
            strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            pathData = addPathNodes("m8.6 12 2.4 2.4 4.4-4.6"),
            stroke = SolidColor(ink),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
