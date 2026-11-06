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

    @Test
    fun `相对图片与链接按服务端 origin 补全`() {
        val out = sanitizeEmailHtml(
            "<img src=\"/api/mail/img?id=1\"><a href=\"/setting\">x</a><img srcset=\"/a.png 1x, /b.png 2x\">",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("src=\"https://mail.example.com/api/mail/img?id=1\""))
        assertTrue(out.contains("href=\"https://mail.example.com/setting\""))
        assertTrue(out.contains("https://mail.example.com/a.png 1x"))
        assertTrue(out.contains("https://mail.example.com/b.png 2x"))
    }

    @Test
    fun `绝对地址与锚点不被改写`() {
        val out = sanitizeEmailHtml(
            "<img src=\"https://e.x/a.png\"><a href=\"#top\">x</a><a href=\"mailto:a@b.c\">m</a>" +
                "<img src=\"//cdn.x/b.png\">",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("https://e.x/a.png"))
        assertTrue(out.contains("href=\"#top\""))
        assertTrue(out.contains("mailto:a@b.c"))
        // 协议相对地址按 https 补全，不能留给中性 origin 解析成 otsmail.invalid
        assertTrue(out.contains("https://cdn.x/b.png"))
    }
}
