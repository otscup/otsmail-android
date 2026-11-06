package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务器地址规范化是登录第一步，写错就是全员连不上；这里只测纯函数 [ServerAddress.parse]。 */
class ServerAddressTest {

    @Test
    fun `不带协议时默认 https 并追加 api 后缀`() {
        val r = ServerAddress.parse("mail.example.com")
        assertTrue(r is ServerAddress.Parsed.Ok)
        assertEquals("https://mail.example.com/api", (r as ServerAddress.Parsed.Ok).apiBase)
    }

    @Test
    fun `显式 https 带端口与 api 斜杠都被规范化`() {
        val r = ServerAddress.parse("https://Mail.Example.com:8443/api/")
        assertEquals("https://mail.example.com:8443/api", (r as ServerAddress.Parsed.Ok).apiBase)
    }

    @Test
    fun `拒绝明文 http`() {
        val r = ServerAddress.parse("http://mail.example.com")
        assertEquals("请使用 https 地址", (r as ServerAddress.Parsed.Fail).message)
    }

    @Test
    fun `拒绝带业务路径`() {
        val r = ServerAddress.parse("https://mail.example.com/inbox")
        assertEquals("服务器地址只填域名，不要带路径", (r as ServerAddress.Parsed.Fail).message)
    }

    @Test
    fun `拒绝空值与空格`() {
        assertTrue(ServerAddress.parse("   ") is ServerAddress.Parsed.Fail)
        assertTrue(ServerAddress.parse("mail example.com") is ServerAddress.Parsed.Fail)
    }

    @Test
    fun `拒绝带用户名密码`() {
        assertTrue(ServerAddress.parse("https://user:pw@mail.example.com") is ServerAddress.Parsed.Fail)
    }
}
