package com.otscup.otsmail.data

import android.content.Context

/**
 * 第三方邮箱是否在收件箱里显示：只记本机，不影响同步、本地邮件和稍后提醒（隐藏 ≠ 解绑）。
 * 只存被隐藏的 extId，默认显示；extId 是服务端全局主键，换号后旧 id 匹配不到任何账号。
 */
object ExtVisibility {
    private const val PREF = "otsmail_ext_visibility"
    private const val KEY = "hidden"

    fun hidden(context: Context): Set<Long> =
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun setHidden(context: Context, extId: Long, hidden: Boolean) {
        val next = this.hidden(context).toMutableSet()
        if (hidden) next += extId else next -= extId
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, next.map { it.toString() }.toSet())
            .apply()
    }

    /** 纯逻辑，单测覆盖：从已绑定账户里剔除被隐藏的。 */
    fun visible(extIds: List<Long>, hidden: Set<Long>): List<Long> = extIds.filterNot { it in hidden }
}
