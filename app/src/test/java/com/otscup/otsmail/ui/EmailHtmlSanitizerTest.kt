package com.otscup.otsmail.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebView 渲染前的最小清洗：脚本/事件属性/危险协议不能漏到正文里。 */
class EmailHtmlSanitizerTest {

    @Test
    fun `脚本标签与事件属性被删除`() {
        val out = sanitizeEmailHtml("<p onclick=\"steal()\">hi</p><script>alert(1)</script>")
        assertFalse(out.contains("<script", ignoreCase = true))
        assertFalse(out.contains("onclick", ignoreCase = true))
        assertTrue(out.contains("hi"))
    }

    @Test
    fun `javascript 与 vbscript 协议被中性化`() {
        val out = sanitizeEmailHtml("<a href=\"JavaScript:alert(1)\">x</a><img src=\"vbscript:msgbox\">")
        assertFalse(out.contains("javascript:", ignoreCase = true))
        assertFalse(out.contains("vbscript:", ignoreCase = true))
    }

    @Test
    fun `普通样式与图片保留`() {
        val out = sanitizeEmailHtml("<p style=\"color:#333\">hi</p><img src=\"https://e.x/a.png\">")
        assertTrue(out.contains("style="))
        assertTrue(out.contains("https://e.x/a.png"))
    }

    @Test
    fun `空正文原样返回`() {
        assertTrue(sanitizeEmailHtml("  ") == "  ")
    }
}
