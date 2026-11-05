package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.CancellationException

/** 多选的批量动作。服务端只有单封接口，所以逐条串行调用，不伪造批量。 */
internal enum class MailBatch(val label: String) {
    READ("已读"),
    UNREAD("未读"),
    STAR("星标"),
    UNSTAR("取消星标"),
    DELETE("删除"),
    /** 已删除列表专用：恢复到收件箱 */
    RESTORE("恢复"),
}

internal object MailBatchOps {

    /** 逐条串行；成功项立即同步内存列表与 SQLite 缓存。返回失败条数。 */
    suspend fun run(action: MailBatch, ids: List<Long>): Int {
        var failed = 0
        for (id in ids) {
            try {
                when (action) {
                    MailBatch.READ -> {
                        Api.markRead(id)
                        MailCache.markRead(id)
                    }

                    MailBatch.UNREAD -> {
                        Api.markUnread(id)
                        MailCache.markUnread(id)
                    }

                    MailBatch.STAR -> {
                        Api.starAdd(id)
                        MailCache.setStar(id, true)
                    }

                    MailBatch.UNSTAR -> {
                        Api.starCancel(id)
                        MailCache.setStar(id, false)
                    }

                    MailBatch.DELETE -> {
                        Api.trash(listOf(id))
                        MailCache.removeItem(id)
                    }

                    MailBatch.RESTORE -> {
                        Api.restore(id)
                        MailCache.removeItem(id)
                    }
                }
            } catch (e: CancellationException) {
                // 页面退出/协程取消不是"失败"：直接向上抛，避免残留的取消异常被计进失败条数
                throw e
            } catch (_: Throwable) {
                failed++
            }
        }
        return failed
    }

    /** 批量移动分类，同样逐条；每条结束后回报 (已完成, 总数)，方便界面显示进度。返回失败条数。 */
    suspend fun move(category: String, ids: List<Long>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        var failed = 0
        ids.forEachIndexed { index, id ->
            try {
                Api.moveCategory(id, category)
                MailCache.setCategory(id, category)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                failed++
            }
            onProgress(index + 1, ids.size)
        }
        return failed
    }
}

/** 多选时的标题栏：× + 已选 N 封 + 全选/取消全选。 */
@Composable
internal fun MailSelectionHeader(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).tapArea { onExit() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = "退出多选", tint = c.blue, modifier = Modifier.size(20.dp))
        }
        Text(
            "已选 $count 封",
            style = t.body.copy(fontWeight = FontWeight.SemiBold),
            color = c.label,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box(
            Modifier.height(44.dp).tapArea { onToggleAll() }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (allSelected) "取消全选" else "全选",
                style = t.subhead,
                color = c.blue,
            )
        }
    }
}

/** 多选底部动作条：已读/未读/星标/取消星标/移动/删除；已删除列表额外给「恢复」。 */
@Composable
internal fun MailSelectionActions(
    busy: Boolean,
    onAction: (MailBatch) -> Unit,
    onMove: () -> Unit,
    /** 当前在「已删除」列表：多选也要能恢复 */
    showRestore: Boolean = false,
) {
    val c = OT.colors
    OTDivider(strong = true)
    Row(
        Modifier.fillMaxWidth().background(c.bgBar).navigationBarsPadding().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (showRestore) {
            SelectionAction(Icons.Outlined.MarkEmailRead, "恢复", busy, tint = c.blue) { onAction(MailBatch.RESTORE) }
        }
        SelectionAction(Icons.Outlined.MarkEmailRead, "已读", busy) { onAction(MailBatch.READ) }
        SelectionAction(Icons.Outlined.MarkEmailUnread, "未读", busy) { onAction(MailBatch.UNREAD) }
        SelectionAction(Icons.Outlined.Star, "星标", busy) { onAction(MailBatch.STAR) }
        SelectionAction(Icons.Outlined.StarBorder, "取消星标", busy) { onAction(MailBatch.UNSTAR) }
        SelectionAction(Icons.Outlined.LocalOffer, "移到分类", busy, onClick = onMove)
        SelectionAction(Icons.Outlined.Delete, "删除", busy, tint = c.red) { onAction(MailBatch.DELETE) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    tint: Color = OT.colors.label2,
    onClick: () -> Unit,
) {
    val c = OT.colors
    Column(
        Modifier
            .weight(1f)
            .height(50.dp)
            .tapArea { if (!busy) onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = if (busy) c.label3 else tint, modifier = Modifier.size(22.dp))
        Text(label, style = OT.type.caption2, color = if (busy) c.label3 else tint, maxLines = 1, textAlign = TextAlign.Center)
    }
}
