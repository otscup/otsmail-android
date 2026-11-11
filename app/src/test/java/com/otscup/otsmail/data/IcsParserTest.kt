package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    @Test
    fun parse_validGoogleMeetInvite_success() {
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            DTSTART:20261118T140000Z
            DTEND:20261118T163000Z
            DTSTAMP:20261110T080000Z
            ORGANIZER;CN=Google Cloud:mailto:events@cloud.google.com
            UID:test-uid-123456@google.com
            SUMMARY:Google Cloud Next 架构线上研讨会
            LOCATION:Google Meet: https://meet.google.com/abc-defg-hij
            DESCRIPTION:深入分享 Cloudflare Workers 与 Android 协同实战。\n欢迎参加！
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = IcsParser.parse(ics)
        assertNotNull(event)
        assertEquals("Google Cloud Next 架构线上研讨会", event!!.summary)
        assertEquals("Google Meet: https://meet.google.com/abc-defg-hij", event.location)
        assertTrue(event.description.contains("Cloudflare Workers"))
        assertFalse(event.allDay)
        assertTrue(event.startTimeMillis > 0)
        assertTrue(event.endTimeMillis > event.startTimeMillis)
        // 2.5 hours difference = 150 minutes * 60 * 1000 = 9,000,000 ms
        assertEquals(9_000_000L, event.endTimeMillis - event.startTimeMillis)
    }

    @Test
    fun parse_allDayEvent_success() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20261120
            SUMMARY:团队技术分享日
            DESCRIPTION:全天分享
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = IcsParser.parse(ics)
        assertNotNull(event)
        assertEquals("团队技术分享日", event!!.summary)
        assertTrue(event.allDay)
        assertEquals(86400000L, event.endTimeMillis - event.startTimeMillis)
    }

    @Test
    fun parse_emptyOrInvalid_returnsNull() {
        assertNull(IcsParser.parse(""))
        assertNull(IcsParser.parse("This is just a normal text without VEVENT"))
    }
}
