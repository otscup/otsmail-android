package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证码识别规则：关键词 + 4–8 位数字 + 排除项。
 * 这里覆盖的是「真验证码要命中」和「常见误判要排除」两类 case。
 */
class VerificationCodeTest {

    @Test
    fun `中文验证码命中`() {
        val hit = VerificationCode.find("您的验证码是 528164，5 分钟内有效。")
        assertNotNull(hit)
        assertEquals("528164", hit!!.code)
    }

    @Test
    fun `英文 verification code 命中`() {
        val hit = VerificationCode.find("Your verification code is 482910. It expires in 10 minutes.")
        assertEquals("482910", hit?.code)
    }

    @Test
    fun `OTP 与 passcode 命中`() {
        assertEquals("7315", VerificationCode.find("OTP: 7315")?.code)
        assertEquals("906321", VerificationCode.find("Your passcode is 906321")?.code)
    }

    @Test
    fun `主题写关键词正文写数字也能命中`() {
        val hit = VerificationCode.find("Your verification code\n4829")
        assertEquals("4829", hit?.code)
    }

    @Test
    fun `八位验证码命中`() {
        assertEquals("12345678", VerificationCode.find("校验码：12345678")?.code)
    }

    @Test
    fun `全角数字先转半角再识别`() {
        assertEquals("654321", VerificationCode.find("验证码：６５４３２１")?.code)
    }

    @Test
    fun `金额不当作验证码`() {
        assertNull(VerificationCode.find("本次订单金额 ¥1299，优惠 300 元。"))
        assertNull(VerificationCode.find("合计 1299 元，验证码请勿泄露。"))
    }

    @Test
    fun `订单号不当作验证码`() {
        assertNull(VerificationCode.find("您的订单号 202611070001，请妥善保管。"))
        assertEquals(
            "654321",
            VerificationCode.find("订单号123456 验证码654321")?.code,
        )
        assertEquals(
            "654321",
            VerificationCode.find("订单号 123456，验证码 654321")?.code,
        )
    }

    @Test
    fun `电话号码不当作验证码`() {
        assertNull(VerificationCode.find("客服电话 86271234，工作时间 9:00-18:00。"))
        assertEquals(
            "123456",
            VerificationCode.find("验证码 123456 客服电话 86271234")?.code,
        )
    }

    @Test
    fun `日期与年份不当作验证码`() {
        assertNull(VerificationCode.find("验证码有效期至 2026 年 11 月 07 日。"))
        assertNull(VerificationCode.find("date 2026-11-07 code 已失效"))
        assertEquals("1234", VerificationCode.find("验证码 1234，有效期至 2026-11-07")?.code)
    }

    @Test
    fun `九位以上长编号与内嵌字母的串不切段命中`() {
        assertNull(VerificationCode.find("验证码 123456789"))
        assertNull(VerificationCode.find("SKU123456 已发货，code 见包装。"))
        assertNull(VerificationCode.find("运单号 SF1234567890123，验证码不在短信里。"))
    }

    @Test
    fun `没有关键词的裸数字不命中`() {
        assertNull(VerificationCode.find("今天收到 12 封邮件，共 3 个附件。"))
        assertNull(VerificationCode.find("2026 年度报告已更新。"))
    }

    @Test
    fun `decode 之类的单词不冒充 code`() {
        assertNull(VerificationCode.find("We decoded the message 2026 successfully."))
        assertNull(VerificationCode.find("encoding error 4829"))
    }

    @Test
    fun `链接参数里的数字不当作验证码`() {
        assertNull(VerificationCode.find("https://example.com/verify?code=1234"))
        assertNull(VerificationCode.find("打开 https://x.example/a?code=482910 完成验证"))
    }

    @Test
    fun `HTML 样式里的颜色值不会被误认`() {
        val html = "<style>.a{color:#123456}</style><p>验证码 <b>654321</b></p>"
        assertEquals("654321", VerificationCode.find(html)?.code)
        assertNull(VerificationCode.find("<span style=\"color:#123456\">hi</span>"))
    }

    @Test
    fun `积分里程等数量词不当作验证码`() {
        assertNull(VerificationCode.find("本次消费获得 1000 积分，可在会员日兑换。"))
        assertNull(VerificationCode.find("您的里程 8888 点即将过期。"))
    }

    @Test
    fun `多封邮件正文混排时优先取离关键词最近的码`() {
        val hit = VerificationCode.find(
            "验证码 1111\n" +
                "下面是上一封邮件的引用：\n" +
                "您的验证码是 999999",
        )
        // 两段都命中，取第一段里最近的 1111（同一行紧邻关键词）
        assertEquals("1111", hit?.code)
    }
}
