package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/** 设置页里的「管理」入口（管理员才用得到；权限不够时接口会返回真话，不在这里假装禁用）。 */
@Composable
internal fun AdminLinks(onOpenAdmin: (String) -> Unit) {
    val c = OT.colors
    val t = OT.type
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(pressedColor = c.bgInput) { onOpenAdmin(row.first) }
                    .padding(horizontal = d.sp4.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(row.third.second.copy(alpha = 0.14f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(row.third.first, contentDescription = null, tint = row.third.second, modifier = Modifier.size(18.dp))
                }
                Text(row.second, style = t.body, color = c.label, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.label3, modifier = Modifier.size(20.dp))
            }
        }
    }
}
