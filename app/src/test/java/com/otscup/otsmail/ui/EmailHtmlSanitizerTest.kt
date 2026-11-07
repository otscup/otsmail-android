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
        val out = sanitizeEmailHtml("<p style=\"color:#333\">hi</p><img width=\"600\" height=\"300\" src=\"https://e.x/a.png\">")
        assertTrue(out.contains("style="))
        assertTrue(out.contains("https://e.x/a.png"))
    }

    @Test
    fun `追踪像素被换成透明占位`() {
        val out = sanitizeEmailHtml(
            "<img width=\"1\" height=\"1\" src=\"https://e.x/open?u=1\">" +
                "<img style=\"width:1px;height:1px\" src=\"https://e.x/p.gif\">" +
                "<img style=\"display:none\" src=\"https://e.x/hidden.gif\">",
        )
        assertFalse(out.contains("e.x/open"))
        assertFalse(out.contains("e.x/p.gif"))
        assertFalse(out.contains("e.x/hidden.gif"))
        assertTrue(out.contains("data:image/gif"))
    }

    @Test
    fun `带尺寸的正常图片不被当成追踪像素`() {
        val out = sanitizeEmailHtml(
            "<img width=\"600\" src=\"https://e.x/hero.png\">" +
                "<img height=\"200\" src=\"https://e.x/b.png\">" +
                "<img style=\"width:100%\" src=\"https://e.x/c.png\">",
        )
        assertTrue(out.contains("https://e.x/hero.png"))
        assertTrue(out.contains("https://e.x/b.png"))
        assertTrue(out.contains("https://e.x/c.png"))
    }

    @Test
    fun `空正文原样返回`() {
        assertTrue(sanitizeEmailHtml("  ") == "  ")
    }

    @Test
    fun `相对图片与链接按服务端 origin 补全`() {
        val out = sanitizeEmailHtml(
            "<img width=\"600\" src=\"/api/mail/img?id=1\"><a href=\"/setting\">x</a><img width=\"600\" srcset=\"/a.png 1x, /b.png 2x\">",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("src=\"https://mail.example.com/api/mail/img?id=1\""))
        assertTrue(out.contains("href=\"https://mail.example.com/setting\""))
        assertTrue(out.contains("https://mail.example.com/a.png 1x"))
        assertTrue(out.contains("https://mail.example.com/b.png 2x"))
    }

    @Test
    fun `CSS 背景图与 video poster 的相对地址也按服务端 origin 补全`() {
        val out = sanitizeEmailHtml(
            "<div style=\"background-image:url('/api/proxy/image?url=x')\">x</div>" +
                "<video poster=\"/api/proxy/image?url=y\"></video>" +
                "<style>.b{background:url(/api/proxy/image?url=z)}</style>",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("url('https://mail.example.com/api/proxy/image?url=x')"))
        assertTrue(out.contains("poster=\"https://mail.example.com/api/proxy/image?url=y\""))
        assertTrue(out.contains("url(https://mail.example.com/api/proxy/image?url=z)"))
    }

    @Test
    fun `CSS 里的 data 与锚点引用不被改写`() {
        val out = sanitizeEmailHtml(
            "<div style=\"background:url(#grad)\"></div><img width=\"600\" src=\"data:image/gif;base64,R0lGOD\">",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("url(#grad)"))
        assertTrue(out.contains("data:image/gif;base64"))
    }

    @Test
    fun `绝对地址与锚点不被改写`() {
        val out = sanitizeEmailHtml(
            "<img width=\"600\" src=\"https://e.x/a.png\"><a href=\"#top\">x</a><a href=\"mailto:a@b.c\">m</a>" +
                "<img width=\"600\" src=\"//cdn.x/b.png\">",
            "https://mail.example.com/",
        )
        assertTrue(out.contains("https://e.x/a.png"))
        assertTrue(out.contains("href=\"#top\""))
        assertTrue(out.contains("mailto:a@b.c"))
        // 协议相对地址按 https 补全，不能留给中性 origin 解析成 otsmail.invalid
        assertTrue(out.contains("https://cdn.x/b.png"))
    }

    @Test
    fun `固定像素宽度的表格被改成自适应`() {
        val out = sanitizeEmailHtml(
            "<div style=\"max-width:600px\">" +
                "<table width=\"600\" style=\"width:600px;\"><tr>" +
                "<td style=\"vertical-align:top;width:600px;\">正文</td></tr></table></div>",
        )
        // 注意：外层 div 的 max-width:600px 会被保留（渲染时由 shell CSS 压成 100%），
        // 这里只断言没有独立的 width:600px 固定宽度
        assertFalse(Regex("(?i)(?<![-\\w])width:600px").containsMatchIn(out))
        assertTrue(out.contains("max-width:100%"))
        assertTrue(out.contains("width=\"100%\""))
        // 正文内容本身不能被清洗掉
        assertTrue(out.contains("正文"))
    }

    @Test
    fun `多列像素列宽被去掉避免窄屏裁切`() {
        val out = sanitizeEmailHtml(
            "<table width=\"600\"><tr>" +
                "<td width=\"200\">A</td><td style=\"width:200px\">B</td><td>C</td></tr></table>",
        )
        // 表格整体改成 100%，单元格列宽不再累加出 600px 的横向溢出
        assertTrue(out.contains("width=\"100%\""))
        assertFalse(Regex("(?i)width=\"200\"").containsMatchIn(out))
        assertFalse(Regex("(?i)width:200px").containsMatchIn(out))
        assertTrue(out.contains(">A<"))
        assertTrue(out.contains(">B<"))
    }

    @Test
    fun `单元格小宽度与百分比列宽保持原样`() {
        val out = sanitizeEmailHtml(
            "<table width=\"100%\"><tr>" +
                "<td width=\"24\">i</td><td width=\"33%\">a</td><td width=\"200\">b</td></tr></table>",
        )
        // 图标/间距单元格与百分比列宽不动，只有普通像素列宽被去掉
        assertTrue(out.contains("width=\"24\""))
        assertTrue(out.contains("width=\"33%\""))
        assertFalse(out.contains("width=\"200\""))
    }

    @Test
    fun `图片与百分比列宽保持原样`() {
        val out = sanitizeEmailHtml(
            "<img width=\"600\" src=\"https://e.x/hero.png\">" +
                "<table width=\"100%\"><tr><td width=\"33%\">a</td><td width=\"33%\">b</td><td>c</td></tr></table>",
        )
        // 大图保留固定宽度，避免在大屏上被 width=100% 拉伸
        assertTrue(out.contains("width=\"600\""))
        assertTrue(out.contains("https://e.x/hero.png"))
        // 百分比列宽不动，否则表头会被挤成竖排
        assertTrue(out.contains("width=\"33%\""))
    }

    @Test
    fun `小尺寸宽度与 pre 长行不被误改`() {
        val out = sanitizeEmailHtml(
            "<img width=\"24\" style=\"width:24px\" src=\"https://e.x/i.png\">" +
                "<div style=\"width:120px\">x</div>" +
                "<pre>very-long-line-without-breaks-aaaaaaaaaaaaaaaaaaaaaaaaaaaa</pre>",
        )
        assertTrue(out.contains("width=\"24\""))
        assertTrue(out.contains("width:24px"))
        assertTrue(out.contains("width:120px"))
        assertTrue(out.contains("<pre>"))
    }
}
