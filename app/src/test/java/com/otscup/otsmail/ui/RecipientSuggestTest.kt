package com.otscup.otsmail.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收件人补全的匹配与排序规则：只允许来自邮件历史的候选，不接系统通讯录。 */
class RecipientSuggestTest {

    private fun c(name: String, email: String, count: Int = 0, lastUsed: String = "", initials: String = "") =
        RecipientCandidate(name = name, email = email, count = count, lastUsed = lastUsed, initials = initials)

    @Test
    fun `多收件人输入只匹配最后一个片段`() {
        assertEquals("li", RecipientSuggest.querySegment("a@b.com, li"))
        assertEquals("li", RecipientSuggest.querySegment("a@b.com；li"))
        assertEquals("张三", RecipientSuggest.querySegment("a@b.com,张三"))
        assertEquals("a@b.com", RecipientSuggest.querySegment("a@b.com, "))
    }

    @Test
    fun `姓名邮箱前缀与拼音首字母都能命中`() {
        val pool = listOf(
            c("张三", "zhangsan@x.com", initials = "zs"),
            c("李四", "lisi@x.com", initials = "ls"),
            c("Alice", "alice@y.com"),
        )
        assertEquals(listOf("zhangsan@x.com"), RecipientSuggest.rank(pool, "zs").map { it.email })
        assertEquals(listOf("zhangsan@x.com"), RecipientSuggest.rank(pool, "张").map { it.email })
        assertEquals(listOf("alice@y.com"), RecipientSuggest.rank(pool, "ali").map { it.email })
        assertTrue(RecipientSuggest.rank(pool, "@x.com").map { it.email }.containsAll(listOf("zhangsan@x.com", "lisi@x.com")))
    }

    @Test
    fun `按频次排序，频次相同按最近使用时间`() {
        val pool = listOf(
            c("王五", "wang@x.com", count = 5, lastUsed = "2026-01-01"),
            c("赵六", "zhao@x.com", count = 2, lastUsed = "2026-06-01"),
            c("钱七", "qian@x.com", count = 2, lastUsed = "2026-09-01"),
        )
        assertEquals(
            listOf("wang@x.com", "qian@x.com", "zhao@x.com"),
            RecipientSuggest.rank(pool, "x.com").map { it.email },
        )
    }

    @Test
    fun `有频次的排在只有一次往来的之前`() {
        val pool = listOf(
            c("历史邮件里的", "once@x.com", count = 0, lastUsed = "2026-10-01"),
            c("常联系人", "often@x.com", count = 3, lastUsed = "2026-09-01"),
        )
        assertEquals(listOf("often@x.com", "once@x.com"), RecipientSuggest.rank(pool, "x.com").map { it.email })
    }

    @Test
    fun `已经填进收件人栏的地址不再出现在候选`() {
        val pool = listOf(c("张三", "zs@x.com", count = 3), c("李四", "ls@x.com"))
        val out = RecipientSuggest.rank(pool, "x.com", listOf("ZS@x.com"))
        assertEquals(listOf("ls@x.com"), out.map { it.email })
    }

    @Test
    fun `同名不同址是同两条候选`() {
        val pool = listOf(c("李四", "li1@x.com", count = 2), c("李四", "li2@x.com", count = 1))
        val out = RecipientSuggest.rank(pool, "李四")
        assertEquals(2, out.size)
        assertTrue(out.any { it.email == "li1@x.com" })
        assertTrue(out.any { it.email == "li2@x.com" })
    }

    @Test
    fun `同一地址重复出现只保留一条并取最大频次`() {
        val pool = listOf(c("张三", "zs@x.com", count = 2), c("zs", "ZS@x.com", count = 4))
        val out = RecipientSuggest.rank(pool, "zs@")
        assertEquals(1, out.size)
        assertEquals(4, out.first().count)
    }

    @Test
    fun `最多返回 6 条且无匹配时为空`() {
        val pool = (1..10).map { c("联系人$it", "c$it@x.com", count = 11 - it) }
        assertEquals(6, RecipientSuggest.rank(pool, "x.com").size)
        assertTrue(RecipientSuggest.rank(pool, "none").isEmpty())
        assertTrue(RecipientSuggest.rank(pool, "  ").isEmpty())
    }

    @Test
    fun `营销与非邮件地址不作为候选`() {
        assertTrue(RecipientSuggest.isAutomated("noreply@x.com"))
        assertTrue(RecipientSuggest.isAutomated("notification@x.com"))
        assertTrue(RecipientSuggest.isAutomated("postmaster@x.com"))
        assertFalse(RecipientSuggest.isAutomated("zhangsan@x.com"))
        assertFalse(RecipientSuggest.isPlainAddress("not-an-email"))
        assertTrue(RecipientSuggest.isPlainAddress("a@b.com"))
    }
}
