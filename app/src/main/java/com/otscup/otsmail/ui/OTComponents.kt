package com.otscup.otsmail.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.R
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 一组 inset 卡片（.group：2025 版 20 圆角白卡） */
@Composable
fun OTGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OT.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OT.dimens.rCard.dp))
            .background(c.bgCard),
    ) {
        content()
    }
}

/** 邮件按天分组卡（.daygroup .card）：20 圆角、无阴影 */
@Composable
fun OTMailCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OT.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OT.dimens.rCard.dp))
            .background(c.bgCard),
    ) {
        content()
    }
}

/** 卡外分组标题（.group__header）：上 16 / 下 8 / 左 20 */
@Composable
fun OTGroupHeader(text: String) {
    Text(
        text,
        style = OT.type.footnote,
        color = OT.colors.label2,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** 列表内的日期标题：小号灰字，可在滚动时粘顶 */
@Composable
fun OTDayHeader(text: String) {
    Text(
        text,
        style = OT.type.caption1.copy(fontWeight = FontWeight.SemiBold),
        color = OT.colors.label2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** 卡内发丝线（.mail-row + .mail-row::before 默认 52% 淡线） */
@Composable
fun OTDivider(inset: Int = 0, endInset: Int = 0, strong: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset.dp, end = endInset.dp)
            .height(0.5.dp)
            .background(if (strong) OT.colors.separator else OT.colors.separatorSoft),
    )
}

/** 抽屉分组之间的虚分隔（.drawer__dash：1.5px dotted、左右 18dp） */
@Composable
fun OTDash() {
    val sep = OT.colors.separator
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = OTSpace.s2)
            .height(1.5.dp),
    ) {
        drawLine(
            color = sep,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
        )
    }
}

/** 大标题（.large-title 34/41）；收件箱根页不用，只给 ?view 之外的场景 */
@Composable
fun OTLargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OT.type.largeTitle, color = OT.colors.label, modifier = modifier)
}

/** ?view= 时列表上方的邮箱名（.viewtitle：20 bold） */
@Composable
fun OTViewTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OT.type.title3.copy(fontWeight = FontWeight.Bold),
        color = OT.colors.label,
        modifier = modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** 搜索胶囊（.searchpill：44 高、r-pill、白底 + 极淡阴影、左 16 右 4） */
@Composable
fun OTSearchPill(
    value: String,
    placeholder: String = "搜索邮件",
    onValueChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = OT.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(OT.dimens.searchH.dp)
            .shadow(
                2.dp,
                RoundedCornerShape(OT.dimens.rPill.dp),
                ambientColor = Color(0xFF142850).copy(alpha = 0.04f),
                spotColor = Color(0xFF142850).copy(alpha = 0.04f),
            )
            .clip(RoundedCornerShape(OT.dimens.rPill.dp))
            .background(c.bgCard)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ot_search), contentDescription = null, tint = c.label2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = OT.type.subhead, color = c.label3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = OT.type.subhead.copy(color = c.label),
                cursorBrush = SolidColor(c.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

/** AI 入口（.aibtn：38 圆、透明底、蓝色四角星） */
@Composable
fun OTIconButton(icon: ImageVector, description: String, tint: Color, size: Int = 44, onClick: () -> Unit) {
    OTIconButtonBox(description, tint, size, onClick) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** 同一按钮的矢量资源版本，用于自绘的圆润图标集（res/drawable/ot_*）。 */
@Composable
fun OTIconButton(@DrawableRes resId: Int, description: String, tint: Color, size: Int = 44, onClick: () -> Unit) {
    OTIconButtonBox(description, tint, size, onClick) {
        Icon(painterResource(resId), contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun OTIconButtonBox(description: String, tint: Color, size: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(OT.dimens.rMd.dp))
            .tapArea(onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * AI 入口星形：形状沿用 Material AutoAwesome（不必自造），只把填充换成线性渐变。
 * 亮色：蓝→紫→品红→橙，135°（左上→右下），在白色卡面上对比足够；
 * 暗色：同色相提亮一档，避免紫/蓝在 #1C1C1E 上糊掉。
 */
@Composable
fun AiStarGradient(size: Dp = 22.dp) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) listOf(
        Color(0xFF5AC8FA), Color(0xFFB18CFF), Color(0xFFFF7BA9), Color(0xFFFFC46B),
    ) else listOf(
        Color(0xFF0A84FF), Color(0xFF7B61FF), Color(0xFFFF2D95), Color(0xFFFF9F0A),
    )
    val vector = Icons.Outlined.AutoAwesome
    val path = remember { vector.flattenToPath() }
    Canvas(Modifier.size(size).semantics { contentDescription = "AI 助手" }) {
        val s = this.size.minDimension / vector.viewportWidth
        scale(s, s, pivot = Offset.Zero) {
            drawPath(
                path,
                brush = Brush.linearGradient(
                    colors,
                    start = Offset.Zero,
                    end = Offset(vector.viewportWidth, vector.viewportHeight),
                ),
            )
        }
    }
}

/** 把 ImageVector 的所有子路径合并成一个 Path，便于用 Brush 填充。 */
private fun ImageVector.flattenToPath(): Path {
    val parser = PathParser()
    fun walk(group: VectorGroup) {
        group.forEach { node ->
            when (node) {
                is VectorPath -> parser.addPathNodes(node.pathData)
                is VectorGroup -> walk(node)
                else -> Unit
            }
        }
    }
    walk(root)
    return parser.toPath()
}

/** 胶囊标签（.chip：高 20、圆角 pill、11pt medium） */
@Composable
fun OTChip(text: String, tint: Color, outline: Boolean = false) {
    val c = OT.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .then(if (outline) Modifier.border(1.dp, c.separator, RoundedCornerShape(999.dp)) else Modifier)
            .background(if (outline) Color.Transparent else c.bgPressed)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = OT.type.caption2.copy(fontWeight = FontWeight.Medium),
            color = if (outline) c.label2 else tint,
            maxLines = 1,
        )
    }
}

/**
 * 头像：有图片（服务端外链或本地 data URL）就显示图片，没有则姓名首字 + 按人稳定取色。
 *
 * 取色不再用固定的 6 色轮转（列表里撞色太明显），改为跟网页端一致的散列色相：
 * seed 取发件邮箱/姓名，同一个人在任何位置颜色都一样。
 */
@Composable
fun OTAvatar(
    name: String,
    size: Int = 34,
    color: Color? = null,
    imageUrl: String? = null,
    seed: String? = null,
) {
    val c = OT.colors
    val bitmap = rememberAvatarImage(imageUrl)
    val bg = color ?: stableAvatarColor(seed ?: name)
    // 黄/浅绿底上白字对比不够，按亮度改深色字
    val textColor = if (bg.luminance() > 0.55f) Color(0xFF1C1C1E) else c.onAccent
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                avatarInitial(name),
                style = OT.type.caption1.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/** 中文取首字，英文取前两个词首字母（网页端 senderInitial 的同一规则）。 */
private fun avatarInitial(name: String): String {
    val v = name.trim().ifEmpty { "?" }
    if (v.first().code in 0x3400..0x9FFF) return v.take(1)
    val letters = v.split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.joinToString("").take(2)
    return letters.uppercase().ifEmpty { "?" }
}

/** seed → 色相（0–359），同人稳定；饱和度/明度固定，避免暗色主题下糊成一片。 */
private fun stableAvatarColor(seed: String): Color {
    var h = 0
    for (ch in seed) {
        h = h * 31 + ch.code
        h = h and 0x7fffffff
    }
    return Color.hsv((h % 360).toFloat(), 0.62f, 0.82f)
}

private val avatarImageCache = HashMap<String, ImageBitmap?>()

/** 极简图片加载：外链用 HttpURLConnection，data URL 直接解 base64，结果按 URL 缓存。 */
@Composable
fun rememberAvatarImage(url: String?): ImageBitmap? {
    if (url.isNullOrEmpty()) return null
    var image by remember(url) { mutableStateOf(avatarImageCache[url]) }
    LaunchedEffect(url) {
        if (image == null) {
            image = withContext(Dispatchers.IO) {
                runCatching { decodeAvatar(url) }.getOrNull()
            }
            avatarImageCache[url] = image
        }
    }
    return image
}

private fun decodeAvatar(value: String): ImageBitmap? {
    if (value.startsWith("data:", ignoreCase = true)) {
        val base64 = value.substringAfter("base64,", "")
        if (base64.isEmpty()) return null
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    val conn = (URL(value).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 6_000
    }
    return conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
}

/** 账户彩点（.acc-dot：11 圆、空心=已读 / 实心=未读） */
@Composable
fun OTAccountDot(color: Color, unread: Boolean, size: Int = 11) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(
                if (unread) Modifier.background(color)
                else Modifier.border(1.6.dp, color, CircleShape),
            ),
    )
}

/** 多选勾选圈（22dp）：未选空心圆环，已选蓝底白勾。 */
@Composable
fun OTSelectionDot(selected: Boolean, size: Int = 22) {
    val c = OT.colors
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(c.blue)
                    else Modifier.border(1.5.dp, c.label3, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** 邮件行（.mail-row：78 高、12/14/11、头像 34、星标贴右下） */
@Composable
fun OTMailRow(
    from: String,
    subject: String,
    snippet: String,
    time: String,
    unread: Boolean,
    starred: Boolean,
    category: String? = null,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    showAvatar: Boolean = true,
    accountColor: Color? = null,
    avatarUrl: String? = null,
    /** 取色种子：传发件邮箱比姓名更稳定（同名不同人不会撞色） */
    seed: String? = null,
    /** 多选模式：左侧换成勾选圈，整行可切换选中。 */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    /** 多账户混合列表才传：这封邮件所属/发往的邮箱地址，显示在主题行右侧 */
    mailboxLabel: String? = null,
    /** 全站列表才传：邮件所属用户的登录邮箱，小字缩在 mailboxLabel 下面 */
    ownerLabel: String? = null,
) {
    val t = OT.type
    val c = OT.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OT.dimens.mailRowH.dp)
            .then(if (selected) Modifier.background(c.bgInput) else Modifier)
            .pressable(
                // 列表行按下轻微收缩（apple-design：行按下轻缩放），幅度小到不觉得"弹"
                scale = 0.985f,
                pressedColor = c.bgPressed,
                shape = RoundedCornerShape(0.dp),
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 1.dp)) {
                if (selectionMode) {
                    OTSelectionDot(selected)
                } else if (showAvatar) {
                    OTAvatar(from, 34, accountColor, imageUrl = avatarUrl, seed = seed ?: from)
                } else {
                    OTAccountDot(accountColor ?: c.acc1, unread)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        from,
                        style = t.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = c.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(time, style = t.caption1, color = c.label2, maxLines = 1)
                    if (unread && showAvatar) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c.blue))
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subject,
                        style = t.subhead.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                        color = c.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!mailboxLabel.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                mailboxLabel,
                                style = t.caption1,
                                color = c.label2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 128.dp),
                            )
                            if (!ownerLabel.isNullOrBlank()) Text(
                                ownerLabel,
                                style = t.caption2,
                                color = c.label3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 128.dp),
                            )
                        }
                    } else if (!ownerLabel.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ownerLabel,
                            style = t.caption2,
                            color = c.label3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 128.dp),
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    snippet,
                    style = t.footnote,
                    color = if (unread) c.label else c.label2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 48.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .tapArea { onStar() },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Icon(
                painterResource(if (starred) R.drawable.ot_star_fill else R.drawable.ot_star),
                contentDescription = if (starred) "取消星标" else "标星",
                tint = if (starred) c.orange else c.label3,
                modifier = Modifier.padding(end = 6.dp, bottom = 7.dp).size(18.dp),
            )
        }
    }
}

/** 未读数徽标（.badge：22 高、橙底、12 semibold） */
@Composable
fun OTBadge(count: Int) {
    val c = OT.colors
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.badgeBg)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = OT.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = c.badgeFg, maxLines = 1)
    }
}

/**
 * 写邮件 FAB（.fab：52、右 16、home 上方 16）。
 * 不用蓝底：卡面底色 + 0.5dp 发丝描边，阴影压到极轻；图标是深灰线条（不是色块）。
 */
@Composable
fun OTFab(onClick: () -> Unit) {
    val c = OT.colors
    // 深灰笔触：亮 #3A3A3C（白卡上 11.4:1）、暗 #D1D1D6（#1C1C1E 上 11.2:1），都过 WCAG AAA
    val ink = if (isSystemInDarkTheme()) Color(0xFFD1D1D6) else Color(0xFF3A3A3C)
    Box(
        Modifier
            .size(OT.dimens.fabSize.dp)
            .shadow(
                2.dp,
                CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.06f),
            )
            .clip(CircleShape)
            .background(c.bgCard)
            .border(0.5.dp, c.separator, CircleShape)
            .pressable(scale = 0.96f, pressedColor = c.bgPressed, shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ot_compose), contentDescription = "写邮件", tint = ink, modifier = Modifier.size(24.dp))
    }
}

/** UISwitch（51×31，绿色轨道；不用 Material 默认那套） */
@Composable
fun OTSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = OT.colors
    val knob by animateDpAsState(if (checked) 24.dp else 4.dp, tween(200, easing = LinearEasing), label = "knob")
    Box(
        Modifier
            .width(51.dp)
            .height(31.dp)
            .clip(CircleShape)
            .background(if (checked) c.green else c.bgInput)
            .tapArea { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(start = knob, top = 2.dp)
                .size(27.dp)
                .clip(CircleShape)
                .background(c.onAccent),
        )
    }
}

/** 空状态（.empty：顶部 28vh、56 灰图标、17 semibold 标题、13 说明） */
@Composable
fun OTEmptyState(icon: Painter, title: String, text: String = "") {
    val c = OT.colors
    val top = (LocalConfiguration.current.screenHeightDp * 0.28f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = top, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = c.label4, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = OT.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text, style = OT.type.footnote, color = c.label2)
        }
    }
}

/** 8pt grid spacing tokens - use these instead of raw .dp */
object OTSpace {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s7 = 32.dp; val s8 = 44.dp
    val cardGap = 8.dp
}
