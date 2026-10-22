package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cross-correlates audio energy envelopes across playback sources to compute
 * the precise latency/seek offset for seamless stream quality upgrades.
 */
@UnstableApi
object StreamAudioAligner {

    private const val TAG = "StreamAudioAligner"

    const val HOP_MS = 2.0 // 500 fps high-precision envelope
    const val WIN_MS = 10.0 // 10ms transient window
    const val MIN_CONFIDENCE = 0.40 // Minimum normalized cross-correlation
    const val TIMEOUT_MS = 3000L // 3.0s deadline for alignment analysis
    const val ANALYSIS_WARM_BYTES = 1_500_000L // 1.5MB background pre-warm
    const val PRIMARY_WINDOW_START_SEC = 0.0
    const val PRIMARY_WINDOW_END_SEC = 15.0
    const val SECONDARY_WINDOW_BEFORE_SEC = 5.0
    const val SECONDARY_WINDOW_AFTER_SEC = 10.0

    // Cache computed offsets so repeat upgrades or seeks reuse the measurement instantly:
    // (mediaId, streamKey) -> offsetMs
    private val offsetCache = ConcurrentHashMap<Pair<String, String>, Long>()

    fun getCachedOffsetMs(mediaId: String, streamUrl: String): Long? =
        offsetCache[mediaId to streamUrl]

    fun putCachedOffsetMs(mediaId: String, streamUrl: String, offsetMs: Long) {
        offsetCache[mediaId to streamUrl] = offsetMs
    }

    fun clearCache() {
        offsetCache.clear()
    }

    /**
     * Resolves analysis window bounds based on opening availability and playback position.
     */
    fun resolveWindow(hasOpening: Boolean, currentPosMs: Long): Pair<Double, Double> {
        return if (hasOpening) {
            PRIMARY_WINDOW_START_SEC to PRIMARY_WINDOW_END_SEC
        } else {
            val curSec = currentPosMs / 1000.0
            val start = max(0.0, curSec - SECONDARY_WINDOW_BEFORE_SEC)
            val end = curSec + SECONDARY_WINDOW_AFTER_SEC
            start to end
        }
    }

    /**
     * Computes RMS energy envelope over sliding windows.
     */
    fun computeEnergyEnvelope(
        samples: FloatArray,
        sampleRate: Double,
        hopMs: Double = HOP_MS,
        winMs: Double = WIN_MS,
    ): FloatArray {
        val hopSamples = max(1, (sampleRate * (hopMs / 1000.0)).toInt())
        val winSamples = max(hopSamples, (sampleRate * (winMs / 1000.0)).toInt())
        val numFrames = max(0, (samples.size - winSamples) / hopSamples)
        if (numFrames <= 0) return FloatArray(0)

        val env = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * hopSamples
            val end = min(samples.size, start + winSamples)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            env[i] = sqrt(sumSq / (end - start)).toFloat()
        }
        return env
    }

    /**
     * Correlates two PCM signals and returns Pair(totalOffsetMs, confidence).
     */
    fun computeOffsetFromSignals(
        sourceSamples: FloatArray,
        sourceSampleRate: Double,
        sourceStartSec: Double,
        targetSamples: FloatArray,
        targetSampleRate: Double,
        targetStartSec: Double,
    ): Pair<Long, Double> {
        val sourceEnv = computeEnergyEnvelope(sourceSamples, sourceSampleRate)
        val targetEnv = computeEnergyEnvelope(targetSamples, targetSampleRate)
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return Pair(0L, 0.0)

        val correlation = VersionAudioAligner.crossCorrelate(
            sourceEnv = sourceEnv,
            targetEnv = targetEnv,
            hopMs = HOP_MS,
            maxLagSec = 15.0,
        )

        val bestLagFrames = correlation.lagFrames
        val floor = correlation.floor
        val ceiling = correlation.ceiling
        if (bestLagFrames == floor || bestLagFrames == ceiling) {
            TrackLog.w(
                TAG,
                "Stream alignment: peak pinned to searchable edge ($bestLagFrames of $floor..$ceiling)",
            )
            return Pair(0L, 0.0)
        }

        val lagMs = (bestLagFrames * HOP_MS).toLong()
        val startDiffMs = ((targetStartSec - sourceStartSec) * 1000.0).toLong()
        val totalOffsetMs = lagMs + startDiffMs
        return Pair(totalOffsetMs, correlation.confidence)
    }

    /**
     * Calculates or retrieves the time offset (in milliseconds) from [currentSong]
     * to [targetStreamUri]. Clamped to fallback 0L if analysis fails or times out.
     */
    suspend fun findStreamOffsetMs(
        context: Context?,
        mediaId: String,
        currentPosMs: Long,
        currentSong: Song?,
        targetStreamUri: Uri,
    ): Long = withTimeoutOrNull(TIMEOUT_MS) {
        val streamKey = targetStreamUri.toString()
        val cached = offsetCache[mediaId to streamKey]
        if (cached != null) return@withTimeoutOrNull cached

        withContext(Dispatchers.IO) {
            // Pre-warm 1.5 MB of audio data in background
            runCatching { AudioCache.warmRange(targetStreamUri, 0L, ANALYSIS_WARM_BYTES) }

            val sourceDs = openSourceDataSource(context, currentSong)
            val targetDs = StreamAudioSource.open(context, targetStreamUri)

            if (sourceDs == null || targetDs == null) {
                StreamAudioSource.safeClose(sourceDs)
                StreamAudioSource.safeClose(targetDs)
                TrackLog.w(TAG, "Cannot open data sources for stream alignment on $mediaId")
                return@withContext 0L
            }

            try {
                // Primary: Try opening window [0.0 .. 15.0s]
                var sourceDecode = AudioDecoder.decodeRegion(
                    sourceDs,
                    PRIMARY_WINDOW_START_SEC,
                    PRIMARY_WINDOW_END_SEC,
                )
                var targetDecode = AudioDecoder.decodeRegion(
                    targetDs,
                    PRIMARY_WINDOW_START_SEC,
                    PRIMARY_WINDOW_END_SEC,
                )

                // Secondary: Fall back around current playback position if primary missing/evicted
                if (sourceDecode == null || targetDecode == null ||
                    sourceDecode.first.samples.isEmpty() || targetDecode.first.samples.isEmpty()
                ) {
                    val (secStart, secEnd) = resolveWindow(hasOpening = false, currentPosMs = currentPosMs)
                    sourceDecode = AudioDecoder.decodeRegion(sourceDs, secStart, secEnd)
                    targetDecode = AudioDecoder.decodeRegion(targetDs, secStart, secEnd)
                }

                if (sourceDecode == null || targetDecode == null ||
                    sourceDecode.first.samples.isEmpty() || targetDecode.first.samples.isEmpty()
                ) {
                    TrackLog.w(TAG, "PCM decoding failed for stream alignment on $mediaId: source=${sourceDecode?.first?.samples?.size} target=${targetDecode?.first?.samples?.size} (null = decoder/extractor failed, 0 = nothing cached in window)")
                    return@withContext 0L
                }

                val (pcmSrc, startSrc) = sourceDecode
                val (pcmTgt, startTgt) = targetDecode
                val (offsetMs, confidence) = computeOffsetFromSignals(
                    pcmSrc.samples,
                    pcmSrc.sampleRate,
                    startSrc,
                    pcmTgt.samples,
                    pcmTgt.sampleRate,
                    startTgt,
                )

                if (confidence >= MIN_CONFIDENCE) {
                    offsetCache[mediaId to streamKey] = offsetMs
                    TrackLog.d(TAG, "Stream alignment for $mediaId: offset=${offsetMs}ms, confidence=$confidence")
                    return@withContext offsetMs
                }

                TrackLog.w(TAG, "Stream alignment confidence $confidence < $MIN_CONFIDENCE for $mediaId; fallback 0ms")
                0L
            } catch (e: Exception) {
                Log.w(TAG, "Stream audio alignment failed for $mediaId", e)
                0L
            } finally {
                StreamAudioSource.safeClose(sourceDs)
                StreamAudioSource.safeClose(targetDs)
            }
        }
    } ?: run {
        TrackLog.w(TAG, "Stream alignment timed out after ${TIMEOUT_MS}ms for $mediaId; fallback 0ms")
        0L
    }

    private fun openSourceDataSource(context: Context?, song: Song?): MediaDataSource? {
        if (song == null) return null
        song.localUri?.let { local ->
            val uri = Uri.parse(local)
            if (LocalAudioSource.isLocal(uri) && context != null) {
                return LocalAudioSource.open(context.contentResolver, uri)
            }
        }
        val watch = Uri.parse("bitchord://watch?v=${song.videoId}")
        val onDisk = AudioCache.renditionsOf(watch)
        val sameSource = onDisk
            .filter { !it.key.endsWith("#alt") }
            .maxByOrNull { it.cachedPrefix }
        val pick = if (sameSource != null &&
            sameSource.cachedPrefix >= AudioCache.MIN_ALIGNMENT_PREFIX_BYTES
        ) {
            sameSource
        } else {
            onDisk.maxByOrNull { it.cachedPrefix }
        } ?: return null
        return AudioCache.renditionDataSource(watch, pick)
    }
}
