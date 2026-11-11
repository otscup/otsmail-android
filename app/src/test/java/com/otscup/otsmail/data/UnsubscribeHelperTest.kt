package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsubscribeHelperTest {

    @Test
    fun parse_standardRfc8058OneClick() {
        val header = "<https://example.com/unsub?id=123>, <mailto:unsub@example.com?subject=unsub>"
        val post = "List-Unsubscribe=One-Click"

        val info = UnsubscribeHelper.parse(header, post)
        assertNotNull(info)
        assertEquals("https://example.com/unsub?id=123", info?.httpUrl)
        assertEquals("mailto:unsub@example.com?subject=unsub", info?.mailtoUri)
        assertTrue(info?.isOneClick == true)
    }

    @Test
    fun parse_onlyMailto() {
        val header = "<mailto:support@domain.com?subject=unsubscribe>"
        val post = ""

        val info = UnsubscribeHelper.parse(header, post)
        assertNotNull(info)
        assertNull(info?.httpUrl)
        assertEquals("mailto:support@domain.com?subject=unsubscribe", info?.mailtoUri)
        assertFalse(info?.isOneClick == true)
    }

    @Test
    fun parse_onlyHttpWithoutOneClick() {
        val header = "<https://service.domain.com/notifications/unsub>"
        val post = ""

        val info = UnsubscribeHelper.parse(header, post)
        assertNotNull(info)
        assertEquals("https://service.domain.com/notifications/unsub", info?.httpUrl)
        assertNull(info?.mailtoUri)
        assertFalse(info?.isOneClick == true)
    }

    @Test
    fun parse_blank() {
        assertNull(UnsubscribeHelper.parse("", ""))
        assertNull(UnsubscribeHelper.parse("   ", "One-Click"))
    }
}
