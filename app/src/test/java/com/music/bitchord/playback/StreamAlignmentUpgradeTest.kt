package com.music.bitchord.playback

import com.music.bitchord.playback.smart.StreamAudioAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAlignmentUpgradeTest {

    @Test
    fun `positive offset applies compensated forward seek`() {
        val currentPos = 30000L
        val offsetMs = 350L // target event happens 350ms later
        val seekPos = StreamAudioAligner.seekPositionMs(
            currentPositionMs = currentPos,
            offsetMs = offsetMs,
        )
        assertEquals(30350L, seekPos)
    }

    @Test
    fun `negative offset applies compensated backward seek`() {
        val currentPos = 30000L
        val offsetMs = -200L // target event happens 200ms earlier
        val seekPos = StreamAudioAligner.seekPositionMs(
            currentPositionMs = currentPos,
            offsetMs = offsetMs,
        )
        assertEquals(29800L, seekPos)
    }

    @Test
    fun `negative seek near track start clamps to 0ms without stalling`() {
        assertEquals(0L, StreamAudioAligner.seekPositionMs(currentPositionMs = 100L, offsetMs = -500L))
    }

    @Test
    fun `zero offset or fallback leaves seek position unchanged`() {
        val currentPos = 12345L
        val offsetMs = 0L
        val seekPos = StreamAudioAligner.seekPositionMs(
            currentPositionMs = currentPos,
            offsetMs = offsetMs,
        )
        assertEquals(12345L, seekPos)
    }

    @Test
    fun `standby sync lead converges to the mean of observed seek latencies`() {
        val lead = StandbySyncLead()

        // The first measurement only establishes the baseline; nothing is seeked on it yet.
        assertEquals(0L, lead.observe(280L))

        // That seek cost 0 + 20ms; one sample.
        assertEquals(20L, lead.observe(20L))
        assertTrue(lead.leadMs == 20L)

        // Next seek overshot by 40ms: samples are 20 and -20, mean 0.
        assertEquals(0L, lead.observe(-40L))
    }
}
