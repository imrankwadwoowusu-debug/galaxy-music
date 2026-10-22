package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMacKeychainTest {

    @Test
    fun `generic password round trips through the login keychain`() {
        if (!DesktopPlatform.isMac) return
        assertTrue(DesktopMacKeychain.isAvailable(), "Security.framework constants must resolve")
        val account = "roundtrip-${System.nanoTime()}"
        val secret = "bitchord-keychain-probe".toByteArray(Charsets.UTF_8)
        try {
            assertTrue(
                DesktopMacKeychain.storeGenericPassword(SERVICE, account, "BitChord keychain probe", secret),
                "SecItemAdd failed",
            )
            assertContentEquals(secret, DesktopMacKeychain.findGenericPassword(SERVICE, account))
        } finally {
            DesktopMacKeychain.deleteGenericPassword(SERVICE, account)
        }
        assertNull(DesktopMacKeychain.findGenericPassword(SERVICE, account))
    }

    private companion object {
        private const val SERVICE = "BitChord-test"
    }
}
