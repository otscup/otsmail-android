package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/** 设置页里的「管理」入口（管理员才用得到；权限不够时接口会返回真话，不在这里假装禁用）。 */
@Composable
internal fun AdminLinks(onOpenAdmin: (String) -> Unit) {
    val c = OT.colors
    val d = OT.dimens
    // key to (图标, 同色底) —— 对应样张设置 App 的彩色图标块
    val rows: List<Triple<String, String, Pair<ImageVector, Color>>> = listOf(
        Triple("drafts", "草稿箱", Icons.Outlined.Drafts to c.orange),
        Triple("all", "全部邮件", Icons.Outlined.Mail to c.blue),
        Triple("users", "用户管理", Icons.Outlined.People to c.green),
        Triple("roles", "权限控制", Icons.Outlined.Shield to c.purple),
        Triple("regkeys", "注册密钥", Icons.Outlined.VpnKey to c.orange),
        Triple("analysis", "分析", Icons.Outlined.Insights to c.teal),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.sp4.dp)
            .background(c.bgCard, RoundedCornerShape(14.dp)),
    ) {
        for ((i, row) in rows.withIndex()) {
            if (i > 0) HorizontalDivider(color = c.separator, modifier = Modifier.padding(start = 58.dp))
            OTRow(
                title = row.second,
                icon = row.third.first,
                iconTint = row.third.second,
                onClick = { onOpenAdmin(row.first) },
                trailing = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.label3, modifier = Modifier.size(20.dp))
                },
            )
        }
    }
}
