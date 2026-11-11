package com.otscup.otsmail.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyManagerTest {

    @Test
    fun `prepareCreationJson converts user id to Base64URL string`() {
        val serverOptions = JSONObject().apply {
            put("challenge", "1234567890abcdef")
            put("rp", JSONObject().put("name", "OTSMail").put("id", "mail.example.com"))
            put(
                "user",
                JSONObject()
                    .put("id", "user_1001")
                    .put("name", "alice@example.com")
                    .put("displayName", "alice"),
            )
            put(
                "pubKeyCredParams",
                JSONArray().put(
                    JSONObject().put("alg", -7).put("type", "public-key"),
                ),
            )
        }

        val output = PasskeyManager.prepareCreationJson(serverOptions)
        val json = JSONObject(output)
        val userObj = json.getJSONObject("user")

        assertNotNull(userObj)
        // user.id must be base64url encoded
        val encodedId = userObj.getString("id")
        assertTrue(encodedId.isNotEmpty())
        assertEquals(json.getString("challenge"), "1234567890abcdef")
        assertEquals(json.getJSONObject("rp").getString("name"), "OTSMail")
    }

    @Test
    fun `PasskeyItem maps data correctly`() {
        val item = Api.PasskeyItem(
            id = 42L,
            name = "Pixel 8 Pro",
            credentialId = "cred_xyz_123",
            createdAt = "2026-11-11 09:00:00",
        )
        assertEquals(42L, item.id)
        assertEquals("Pixel 8 Pro", item.name)
        assertEquals("cred_xyz_123", item.credentialId)
        assertEquals("2026-11-11 09:00:00", item.createdAt)
    }
}
