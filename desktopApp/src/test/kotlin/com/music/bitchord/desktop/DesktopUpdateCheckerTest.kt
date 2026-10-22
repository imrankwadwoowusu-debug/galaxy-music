package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateCheckerTest {

    @Test
    fun `windows prefers the setup exe`() {
        assertEquals(
            listOf("-windows-x64-setup.exe"),
            DesktopUpdateChecker.installerSuffixes(
                isWindows = true,
                isLinux = false,
                isMac = false,
                isArm64 = false,
            ),
        )
    }

    @Test
    fun `linux prefers AppImage then deb`() {
        assertEquals(
            listOf(".AppImage", "-linux-amd64.deb"),
            DesktopUpdateChecker.installerSuffixes(
                isWindows = false,
                isLinux = true,
                isMac = false,
                isArm64 = false,
            ),
        )
    }

    @Test
    fun `apple silicon prefers arm64 dmg then pkg`() {
        assertEquals(
            listOf("-macos-arm64.dmg", "-macos-arm64.pkg"),
            DesktopUpdateChecker.installerSuffixes(
                isWindows = false,
                isLinux = false,
                isMac = true,
                isArm64 = true,
            ),
        )
    }

    @Test
    fun `intel mac prefers x64 dmg then pkg`() {
        assertEquals(
            listOf("-macos-x64.dmg", "-macos-x64.pkg"),
            DesktopUpdateChecker.installerSuffixes(
                isWindows = false,
                isLinux = false,
                isMac = true,
                isArm64 = false,
            ),
        )
    }

    @Test
    fun `unknown host has no installer`() {
        assertNull(
            DesktopUpdateChecker.installerSuffixes(
                isWindows = false,
                isLinux = false,
                isMac = false,
                isArm64 = false,
            ),
        )
    }

    @Test
    fun `beta current is older than plain release`() {
        assertTrue(DesktopUpdateChecker.isNewer("1.8", "1.8-beta1"))
    }

    @Test
    fun `same version is not newer`() {
        assertTrue(!DesktopUpdateChecker.isNewer("1.8", "1.8"))
    }
}
