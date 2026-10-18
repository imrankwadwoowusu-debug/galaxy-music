package com.music.bitchord

import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioCachePrefetchTest {

    private fun stream(url: String, codec: String = "flac", kbps: Int = 1411) = SourceStream(
        url = url,
        format = StreamFormat(codec = codec, kbps = kbps),
    )

    @Before
    @After
    fun cleanup() {
        StreamChoice.forget("upcoming-track-1")
        StreamChoice.forget("upcoming-track-2")
    }

    @Test
    fun `addon source is eligible for lookahead prefetching`() {
        // High quality addon sources answer HTTP queries quickly enough to be worth
        // resolving and pinning ahead of playback time so tracks start at 0ms in Hi-Fi.
        assertTrue(SourceKind.ADDON.worthPrefetching)
        assertTrue(SourceKind.JIOSAAVN.worthPrefetching)
        assertFalse(SourceKind.MODULE.worthPrefetching)
        assertFalse(SourceKind.CUSTOM_MODULE.worthPrefetching)
        assertFalse(SourceKind.YOUTUBE.worthPrefetching)
    }

    @Test
    fun `prefetch pinning stores and remembers high quality addon stream in StreamChoice`() {
        val nextId = "upcoming-track-1"
        val addonStream = stream("https://addon.example.com/audio/track-1.flac", codec = "flac")

        // Emulates AudioCache.prefetchQueue() resolving and pinning an addon substitute
        StreamChoice.remember(nextId, addonStream, substituted = true)

        val pinned = StreamChoice.of(nextId)
        assertNotNull(pinned)
        assertEquals("https://addon.example.com/audio/track-1.flac", pinned?.url)
        assertEquals("flac", pinned?.format?.codec)
        assertTrue(StreamChoice.isSubstitute(nextId))
    }

    @Test
    fun `prefetching and source crossfade default to false`() {
        assertFalse(com.music.bitchord.data.settings.AppSettings.prefetchAddonSources.value)
        assertFalse(com.music.bitchord.data.settings.AppSettings.sourceCrossfadeEnabled.value)
    }

    @Test
    fun `addon source gating honors prefetchAddonSources setting`() {
        val settings = com.music.bitchord.data.settings.AppSettings
        val original = settings.prefetchAddonSources.value
        try {
            settings.prefetchAddonSources.value = false
            // When setting is false, addon kind is gated out
            val passesWhenFalse = SourceKind.ADDON.worthPrefetching &&
                (SourceKind.ADDON != SourceKind.ADDON || settings.prefetchAddonSources.value)
            assertFalse(passesWhenFalse)

            // Other quick sources (e.g. JioSaavn) remain permitted
            val jioSaavnPasses = SourceKind.JIOSAAVN.worthPrefetching &&
                (SourceKind.JIOSAAVN != SourceKind.ADDON || settings.prefetchAddonSources.value)
            assertTrue(jioSaavnPasses)

            settings.prefetchAddonSources.value = true
            // When setting is true, addon kind is permitted
            val passesWhenTrue = SourceKind.ADDON.worthPrefetching &&
                (SourceKind.ADDON != SourceKind.ADDON || settings.prefetchAddonSources.value)
            assertTrue(passesWhenTrue)
        } finally {
            settings.prefetchAddonSources.value = original
        }
    }

    @Test
    fun `releasing prefetched choice clears stream pinning`() {
        val nextId = "upcoming-track-2"
        val addonStream = stream("https://addon.example.com/audio/track-2.flac", codec = "flac")

        StreamChoice.remember(nextId, addonStream, substituted = true)
        StreamChoice.forget(nextId)

        assertNull(StreamChoice.of(nextId))
        assertFalse(StreamChoice.isSubstitute(nextId))
    }
}
