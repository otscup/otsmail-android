package com.otscup.otsmail.data

import android.content.Context

/**
 * AI 隐私提示：第一次把邮件/草稿内容发给服务器配置的 AI 服务前提示一次，用户确认后不再弹。
 * 只记在本机 SharedPreferences，不上报、不区分账号（同机同一用户）。
 */
object AiConsent {
    private const val PREF = "otsmail_ai"
    private const val KEY = "privacy_ack"

    fun needed(context: Context): Boolean =
        !context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
