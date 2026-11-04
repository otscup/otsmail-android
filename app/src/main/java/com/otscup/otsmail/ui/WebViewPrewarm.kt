package com.otscup.otsmail.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView

/**
 * WebView 引擎预热。
 *
 * 第一次 `WebView(context)` + 首次 load 要 0.9~1.4s（Chromium 引擎初始化），
 * 这段时间都算在"点开一封邮件"的体感里。App 起来后空闲时先建一个丢在后台跑空白页，
 * 点开邮件时直接取走这个实例，省掉引擎初始化那一段。
 *
 * 只持有一个实例，且用 applicationContext（不持有 Activity）；被详情页取走后置空，
 * 不会出现两个详情页共用同一个 WebView。
 */
object WebViewPrewarm {
    private var spare: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun prewarm(context: Context) {
        if (spare != null) return
        val w = runCatching {
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            }
        }.getOrNull() ?: return
        spare = w
    }

    /** 取走预热实例（只有详情页会取），取走后置空。 */
    fun take(): WebView? = spare.also { spare = null }
}
