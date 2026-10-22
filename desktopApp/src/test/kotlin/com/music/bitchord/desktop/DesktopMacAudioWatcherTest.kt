package com.music.bitchord.desktop

import kotlin.test.Test

class DesktopMacAudioWatcherTest {

    @Test
    fun `starting the watcher does not throw`() {
        DesktopMacAudioWatcher.ensureStarted()
        DesktopMacAudioWatcher.ensureStarted()
    }
}
