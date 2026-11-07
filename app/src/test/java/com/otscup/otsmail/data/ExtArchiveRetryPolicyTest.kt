package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtArchiveRetryPolicyTest {

    @Test
    fun `首次失败从 1 开始计数`() {
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts(null, 12))
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("", 12))
    }

    @Test
    fun `同一封失败累加`() {
        assertEquals(2, ExtArchiveRetryPolicy.nextAttempts("12:1", 12))
        assertEquals(5, ExtArchiveRetryPolicy.nextAttempts("12:4", 12))
    }

    @Test
    fun `换一封重新从 1 计数`() {
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("12:4", 13))
    }

    @Test
    fun `损坏的计数不会导致误判跳过`() {
        // 格式不对、uid 非数字、次数非数字、多段，全部按「这一封第一次失败」处理
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("garbage", 12))
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("x:3", 12))
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("12:abc", 12))
        assertEquals(1, ExtArchiveRetryPolicy.nextAttempts("12:3:4", 12))
    }

    @Test
    fun `归档成功必定推进游标`() {
        assertEquals(
            ExtArchiveAction.ADVANCE,
            ExtArchiveRetryPolicy.decide(failed = false, attempts = ExtArchiveRetryPolicy.MAX_ATTEMPTS),
        )
    }

    @Test
    fun `失败未达上限保留游标重试`() {
        for (attempts in 1 until ExtArchiveRetryPolicy.MAX_ATTEMPTS) {
            assertEquals(
                "第 $attempts 次失败应保留游标",
                ExtArchiveAction.RETRY,
                ExtArchiveRetryPolicy.decide(failed = true, attempts = attempts),
            )
        }
    }

    @Test
    fun `达到与超过上限才允许跳过`() {
        assertEquals(
            ExtArchiveAction.SKIP,
            ExtArchiveRetryPolicy.decide(failed = true, attempts = ExtArchiveRetryPolicy.MAX_ATTEMPTS),
        )
        assertEquals(
            ExtArchiveAction.SKIP,
            ExtArchiveRetryPolicy.decide(failed = true, attempts = ExtArchiveRetryPolicy.MAX_ATTEMPTS + 1),
        )
    }

    @Test
    fun `连续失败五次的完整判定序列`() {
        var stored: String? = null
        val actions = mutableListOf<ExtArchiveAction>()
        repeat(ExtArchiveRetryPolicy.MAX_ATTEMPTS) { round ->
            val attempts = ExtArchiveRetryPolicy.nextAttempts(stored, 88)
            stored = "88:$attempts"
            actions += ExtArchiveRetryPolicy.decide(failed = true, attempts = attempts)
            assertEquals("第 ${round + 1} 轮的计数", round + 1, attempts)
        }
        assertEquals(
            listOf(
                ExtArchiveAction.RETRY,
                ExtArchiveAction.RETRY,
                ExtArchiveAction.RETRY,
                ExtArchiveAction.RETRY,
                ExtArchiveAction.SKIP,
            ),
            actions,
        )
    }

    @Test
    fun `自定义上限可收紧`() {
        assertEquals(ExtArchiveAction.RETRY, ExtArchiveRetryPolicy.decide(true, 2, maxAttempts = 3))
        assertEquals(ExtArchiveAction.SKIP, ExtArchiveRetryPolicy.decide(true, 3, maxAttempts = 3))
    }
}
