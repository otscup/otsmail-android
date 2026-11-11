package com.otscup.otsmail.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VipContactTest {

    @Test
    fun `isVip identifies VIP by group or remark`() {
        val normal = Api.ContactItem(
            id = 1L,
            name = "Bob",
            email = "bob@example.com",
            group = "Colleagues",
            remark = "designer",
        )
        assertFalse(normal.isVip)

        val vipByGroup = Api.ContactItem(
            id = 2L,
            name = "CEO",
            email = "boss@example.com",
            group = "VIP",
            remark = "boss",
        )
        assertTrue(vipByGroup.isVip)

        val vipByRemark = Api.ContactItem(
            id = 3L,
            name = "Important Client",
            email = "client@corp.com",
            group = "",
            remark = "VIP特别关注",
        )
        assertTrue(vipByRemark.isVip)

        val vipByCaseInsensitive = Api.ContactItem(
            id = 4L,
            name = "Partner",
            email = "partner@corp.com",
            group = "vip",
            remark = "",
        )
        assertTrue(vipByCaseInsensitive.isVip)
    }

    @Test
    fun `isVipSender parses email addresses correctly`() {
        Api.vipEmails = setOf("ceo@company.com", "vip@example.org")

        assertTrue(Api.isVipSender("ceo@company.com"))
        assertTrue(Api.isVipSender("CEO <ceo@company.com>"))
        assertTrue(Api.isVipSender("\"Big Boss\" <VIP@example.org>"))
        assertFalse(Api.isVipSender("other@company.com"))
        assertFalse(Api.isVipSender(""))
    }
}
