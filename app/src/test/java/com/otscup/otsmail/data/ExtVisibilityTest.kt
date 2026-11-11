package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「在收件箱中显示」的纯逻辑：被隐藏的账户不进收件箱筛选与合并。 */
class ExtVisibilityTest {

    @Test
    fun visible_剔除已隐藏账户() {
        assertEquals(listOf(1L, 3L), ExtVisibility.visible(listOf(1L, 2L, 3L), setOf(2L)))
    }

    @Test
    fun visible_没有隐藏时原样返回() {
        assertEquals(listOf(1L, 2L), ExtVisibility.visible(listOf(1L, 2L), emptySet()))
    }

    @Test
    fun visible_全部隐藏时为空() {
        assertEquals(emptyList<Long>(), ExtVisibility.visible(listOf(1L, 2L), setOf(1L, 2L)))
    }

    @Test
    fun visible_无关id不影响结果() {
        // 解绑后残留的 extId 属于别的账户，不该误伤现有账户
        assertEquals(listOf(7L), ExtVisibility.visible(listOf(7L), setOf(99L)))
    }
}
