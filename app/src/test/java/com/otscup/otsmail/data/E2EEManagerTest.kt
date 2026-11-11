package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

class E2EEManagerTest {

    @Test
    fun `hybrid encryption and decryption roundtrip`() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val privB64 = Base64.getEncoder().encodeToString(pair.private.encoded)

        val plaintext = "Hello OTSMail E2EE! 这是一封端到端加密的超级机密邮件内容。"
        val encryptedBundle = E2EEManager.encrypt(plaintext, pubB64)

        assertNotNull(encryptedBundle)
        assertTrue(encryptedBundle.contains("cipher"))
        assertTrue(encryptedBundle.contains("enc_key"))
        assertTrue(encryptedBundle.contains("iv"))
        assertNotEquals(plaintext, encryptedBundle)

        // Simulate decrypting on the receiver side
        val json = org.json.JSONObject(encryptedBundle)
        val encKeyBytes = Base64.getDecoder().decode(json.getString("enc_key"))
        val iv = Base64.getDecoder().decode(json.getString("iv"))
        val cipherBytes = Base64.getDecoder().decode(json.getString("cipher"))

        val rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(javax.crypto.Cipher.DECRYPT_MODE, pair.private)
        val aesKeyBytes = rsaCipher.doFinal(encKeyBytes)
        val aesKey = javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES")

        val aesCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        val decrypted = String(aesCipher.doFinal(cipherBytes), Charsets.UTF_8)

        assertEquals(plaintext, decrypted)
    }
}
