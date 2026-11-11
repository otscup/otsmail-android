package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「稍后提醒」的账号归属键与同邮件去重键。 */
class SnoozeTimeTest {
    @Test
    fun accountKey_prefersAccountId() {
        assertEquals("https://a.com#7", SnoozeTime.accountKey("https://a.com/", 7L, 99L, "x@y.com"))
    }

    @Test
    fun accountKey_fallsBackToUserIdThenEmail() {
        assertEquals("https://a.com#99", SnoozeTime.accountKey("https://a.com", 0L, 99L, "x@y.com"))
        assertEquals("https://a.com#x@y.com", SnoozeTime.accountKey("https://a.com", 0L, 0L, " X@Y.com "))
    }

    @Test
    fun accountKey_separatesAccountsOnSameServer() {
        assertNotEquals(
            SnoozeTime.accountKey("https://a.com", 1L, 0L, "x@y.com"),
            SnoozeTime.accountKey("https://a.com", 2L, 0L, "x@y.com"),
        )
    }

    @Test
    fun upsertKey_同账号同邮件同来源才算同一条() {
        val k = SnoozeTime.upsertKey("https://a.com#1", "ots", "42")
        assertEquals("https://a.com#1|ots|42", k)
        assertNotEquals(k, SnoozeTime.upsertKey("https://a.com#1", "ots", "43"))
        assertNotEquals(k, SnoozeTime.upsertKey("https://a.com#1", "ext", "42"))
    }

    /** 第三方提醒靠 extId 解绑清理，序列化必须保留；OTS 提醒不能凭空多出 extId。 */
    @Test
    fun json_保留extId且旧数据不会凭空有extId() {
        val ext = SnoozeReminder(id = "a", accountKey = "k", extId = 3L, mailId = "mid", source = "ext")
        assertEquals(3L, SnoozeReminder.fromJson(ext.toJson())?.extId)
        val ots = SnoozeReminder(id = "b", accountKey = "k", mailId = "42", source = "ots")
        assertNull(SnoozeReminder.fromJson(ots.toJson())?.extId)
    }
}
