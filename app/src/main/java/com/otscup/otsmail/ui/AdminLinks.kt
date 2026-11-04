package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/**
 * 设置页里的「管理」入口（管理员才用得到；权限不够时接口会返回真话，不在这里假装禁用）。
 * 行样式与 SettingRow 一致：无行首图标，只有标题 + 右侧箭头。
 */
@Composable
internal fun AdminLinks(onOpenAdmin: (String) -> Unit) {
    val c = OT.colors
    val d = OT.dimens
    val rows = listOf(
        "drafts" to "草稿箱",
        "all" to "全部邮件",
        "users" to "用户管理",
        "roles" to "权限控制",
        "regkeys" to "注册密钥",
        "analysis" to "分析",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.sp4.dp)
            .background(c.bgCard, RoundedCornerShape(14.dp)),
    ) {
        for ((i, row) in rows.withIndex()) {
            if (i > 0) OTDivider(inset = 16)
            SettingRow(
                title = row.second,
                value = "",
                onClick = { onOpenAdmin(row.first) },
                showChevron = true,
            )
        }
    }
}
