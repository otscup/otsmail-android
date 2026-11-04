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
 *
 * 「全部邮件」是全站数据（/allEmail/list，无 user_id 过滤），入口必须按权限决定是否渲染：
 * `/my/loginUserInfo` 的 permKeys 含 `all-email:query`（或 `*`），或 user.type === 0（系统管理员）。
 * 没权限就整行不渲染——不显示禁用态，也不给普通用户留一个点进去 403 的入口。
 */
@Composable
internal fun AdminLinks(onOpenAdmin: (String) -> Unit, showAllMail: Boolean = false) {
    val c = OT.colors
    val d = OT.dimens
    val rows = buildList {
        add("drafts" to "草稿箱")
        if (showAllMail) add("all" to "全部邮件")
        add("users" to "用户管理")
        add("roles" to "权限控制")
        add("regkeys" to "注册密钥")
        add("analysis" to "分析")
    }
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
