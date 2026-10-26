package com.music.bitchord.data.debrid

import android.content.Context
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.Locale

/**
 * Sends a torrent *description* to TorBox and serves the actual cached bytes
 * from this device until TorBox reports that its account copy is complete.
 */
internal object DebridTorrentSeeder {
    private const val TAG = "BitChord"
    private const val PIECE_BYTES = 256 * 1024
    private const val POLL_MS = 5_000L
    private const val MAX_WAIT_MS = 30 * 60 * 1_000L

    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "http://tracker.files.fm:6969/announce",
        "http://open.acgnxtracker.com:80/announce",
    )

    data class Complete(val torrentId: Long, val infoHash: String)

    suspend fun transfer(
        context: Context,
        mediaFile: File,
        apiKey: String,
        displayName: String,
    ): Complete = withContext(Dispatchers.IO) {
        require(mediaFile.isFile && mediaFile.length() > 0L) { "Cached audio file is missing or empty" }
        val workDir = mediaFile.parentFile
            ?: File(context.noBackupFilesDir, "debrid-seeding").apply { mkdirs() }
        val torrentFile = File(workDir, "${mediaFile.nameWithoutExtension}.torrent")
        val generated = TorrentBuilder()
            .path(mediaFile)
            .pieceSize(PIECE_BYTES)
            .creator("BitChord")
            .apply { trackers.forEach { addTracker(it) } }
            .generate()
        torrentFile.writeBytes(generated.entry().bencode())

        val info = TorrentInfo(torrentFile)
        val hash = info.infoHash().toHex().lowercase(Locale.ROOT)
        val seeder = SessionManager()
        try {
            // The source bytes remain in this device's cache copy. TorBox gets
            // only the .torrent metadata, then retrieves pieces over BitTorrent.
            seeder.start()
            seeder.download(
                info,
                mediaFile.parentFile,
                null,
                null,
                null,
                TorrentFlags.SEED_MODE,
            )
            val torrentId = DebridApi.addSeededTorrent(apiKey, torrentFile, displayName)
                ?: error("TorBox did not accept the torrent metadata")

            val deadline = System.currentTimeMillis() + MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                DebridApi.invalidateLibrary()
                val accountFiles = DebridApi.library(apiKey)
                if (accountFiles?.any { it.hash.equals(hash, ignoreCase = true) } == true) {
                    if (!DebridApi.airlockTorrent(apiKey, torrentId, displayName)) {
                        TrackLog.w(TAG, "TorBox cached ${hash.take(12)} but did not confirm airlock")
                    }
                    return@withContext Complete(torrentId, hash)
                }
                delay(POLL_MS)
            }
            error("TorBox did not finish downloading before the 30-minute limit")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "TorBox cache upload failed for ${mediaFile.name}: ${e.message}")
            throw e
        } finally {
            runCatching { seeder.stop() }
            torrentFile.delete()
        }
    }
}
