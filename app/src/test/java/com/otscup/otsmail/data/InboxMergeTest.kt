package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxMergeTest {

    private fun server(id: Long, epoch: Long?, time: String = epoch?.toString().orEmpty()) =
        MailItem(
            emailId = id,
            from = "a@x.com",
            name = "A",
            subject = "s$id",
            text = "t$id",
            time = time,
            unread = false,
            starred = false,
        )

    private fun ext(extId: Long, localId: Long, epoch: Long) = ExtLocalMail(
        localId = localId,
        extId = extId,
        uid = localId,
        uidValidity = 1,
        messageId = "m$localId",
        subject = "x$localId",
        fromName = "X",
        fromAddr = "x@y.com",
        toName = "",
        toAddr = "me@z.com",
        cc = "",
        dateMs = epoch,
        seen = true,
        snippet = "",
        bodyText = "",
        bodyHtml = "",
        attachmentsJson = "",
    )

    @Test
    fun `两类邮件按时间倒序交错`() {
        val rows = InboxMerge.merge(
            server = listOf(server(1, 300), server(2, 100)),
            ext = listOf(ext(7, 11, 200)),
            serverEpoch = { it.time.toLongOrNull() },
            serverHasMore = false,
        )
        assertEquals(listOf("s:1", "x:7:11", "s:2"), rows.map { it.key })
    }

    @Test
    fun `服务端还有下一页时只补窗口内的第三方邮件`() {
        val rows = InboxMerge.merge(
            server = listOf(server(1, 300), server(2, 100)),
            ext = listOf(ext(7, 11, 400), ext(7, 12, 50)),
            serverEpoch = { it.time.toLongOrNull() },
            serverHasMore = true,
        )
        // 400 在窗口内（比最旧的 100 新），50 比窗口更旧，先不显示，等翻页后自然补进来
        assertEquals(listOf("x:7:11", "s:1", "s:2"), rows.map { it.key })
    }

    @Test
    fun `服务端全部加载完后放开整个本地库`() {
        val rows = InboxMerge.merge(
            server = listOf(server(1, 300), server(2, 100)),
            ext = listOf(ext(7, 11, 400), ext(7, 12, 50)),
            serverEpoch = { it.time.toLongOrNull() },
            serverHasMore = false,
        )
        assertEquals(listOf("x:7:11", "s:1", "s:2", "x:7:12"), rows.map { it.key })
    }

    @Test
    fun `服务端时间解析失败时保持原有顺序`() {
        val rows = InboxMerge.merge(
            server = listOf(server(1, null, "坏时间"), server(2, null, "坏时间2"), server(3, 100)),
            ext = emptyList(),
            serverEpoch = { it.time.toLongOrNull() },
            serverHasMore = false,
        )
        assertEquals(listOf("s:1", "s:2", "s:3"), rows.map { it.key })
    }

    @Test
    fun `key 唯一且能区分来源`() {
        val rows = InboxMerge.merge(
            server = listOf(server(1, 300)),
            ext = listOf(ext(7, 1, 300)),
            serverEpoch = { it.time.toLongOrNull() },
            serverHasMore = false,
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertFalse(rows.first { it.key == "s:1" }.isExt)
        assertTrue(rows.first { it.key == "x:7:1" }.isExt)
    }
}
