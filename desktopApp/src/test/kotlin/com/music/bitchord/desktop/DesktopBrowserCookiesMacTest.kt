package com.music.bitchord.desktop

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopBrowserCookiesMacTest {

    @Test
    fun `mac v10 cookie round trips with Safe Storage password`() {
        val password = "test-safe-storage-password".toByteArray(Charsets.UTF_8)
        val plain = "SAPISID=abc123; Path=/"
        val sealed = sealMacV10(plain.toByteArray(Charsets.UTF_8), password)
        assertEquals(plain, DesktopBrowserCookies.decryptMac(sealed, host = "", password))
    }

    @Test
    fun `mac decrypt rejects missing password`() {
        val sealed = sealMacV10("x".toByteArray(), "pw".toByteArray())
        assertNull(DesktopBrowserCookies.decryptMac(sealed, host = "", safeStoragePassword = null))
    }

    @Test
    fun `mac decrypt rejects wrong password`() {
        val sealed = sealMacV10("secret".toByteArray(), "correct".toByteArray())
        assertNull(DesktopBrowserCookies.decryptMac(sealed, host = "", "wrong".toByteArray()))
    }

    private fun sealMacV10(plain: ByteArray, password: ByteArray): ByteArray {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(String(password, Charsets.UTF_8).toCharArray(), SALT, 1003, 128))
            .encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
            doFinal(plain)
        }
        return "v10".toByteArray(Charsets.US_ASCII) + cipher
    }

    private companion object {
        private val SALT = "saltysalt".toByteArray(Charsets.UTF_8)
    }
}
