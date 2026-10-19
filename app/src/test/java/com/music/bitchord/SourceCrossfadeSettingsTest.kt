package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceCrossfadeSettingsTest {

    @Test
    fun `source crossfade defaults to false`() {
        assertFalse(AppSettings.sourceCrossfadeEnabled.value)
    }
}
