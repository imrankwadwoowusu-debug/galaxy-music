package com.music.bitchord.desktop

import kotlin.test.Test

class DesktopMacMediaTest {

    @Test
    fun `publish is a no-op when controls were never started`() {
        // Must not throw on Linux/Windows CI or when the dylib is absent.
        DesktopMacMedia.publish(DesktopPlaybackState())
    }

    @Test
    fun `stop is safe before start`() {
        DesktopMacMedia.stop()
    }
}
