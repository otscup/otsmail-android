package com.otscup.otsmail.data

import android.content.Context

/**
 * 按「这一封」记住是否显示远程图片。
 *
 * 默认自动加载（和 Gmail 一致），用户点「这封不显示图片」才把这封拉黑；
 * 只影响单封，不按发件人记忆，避免「有的想看有的不想看」的历史问题。
 * 记录按账号分键，退出登录时和静音名单一样整体清掉，换号不会串数据。
 */
object ImagePrefs {

    private const val SP = "otsmail_images"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun key(context: Context): String =
        "noimg_" + Session(context).accountId

    private fun blockedIds(context: Context): Set<String> =
        sp(context).getStringSet(key(context), emptySet()).orEmpty()

    fun isBlocked(context: Context, emailId: Long): Boolean =
        blockedIds(context).contains(emailId.toString())

    fun setBlocked(context: Context, emailId: Long, blocked: Boolean) {
        val cur = blockedIds(context)
        val next = if (blocked) cur + emailId.toString() else cur - emailId.toString()
        sp(context).edit().putStringSet(key(context), next).apply()
    }

    /** 退出登录清空：所有账号的「这封不显示图片」记录都不能带到下一个账号。 */
    fun clear(context: Context) {
        sp(context).edit().clear().apply()
    }
}
