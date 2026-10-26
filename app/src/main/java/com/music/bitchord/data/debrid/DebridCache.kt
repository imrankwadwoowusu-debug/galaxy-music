package com.music.bitchord.data.debrid

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.smb.SmbAuth
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.smb.SmbConfig
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BitChord"

/**
 * Writes addon streams and on-device files into the listener's debrid account.
 *
 * Three routes, because the provider offers three:
 *
 *  - **An addon's torrent stream** has an infohash. It is added with
 *    `add_only_if_cached`, so the operation is a cache lookup rather than a
 *    server-side download of something the provider does not hold.
 *  - **A public HTTP file** — a downloaded track's original source, or a
 *    file on a public WebDAV server — is handed to the provider's web
 *    downloader, which fetches it itself. TorBox has no media-upload
 *    endpoint, so the bytes must be reachable; a `content://` file on this
 *    phone is not.
 *  - **An SMB file** is first copied to the configured public WebDAV server,
 *    then fetched from there by the provider. An SMB share is not something
 *    TorBox can open, and a WebDAV server that requires a password is not
 *    something it can sign in to, so both are stated plainly rather than
 *    attempted and failed.
 *
 * Nothing here runs on the playback path. Automatic caching is post-play and
 * best-effort; a manual cache gets a row in the song's action sheet so its
 * progress and its outcome are visible.
 */
object DebridCache {

    /** The folder the SMB bridge writes into, under the configured WebDAV root. */
    private const val BRIDGE_FOLDER = "BitChord Debrid"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ids already handed to the provider in this process, so playback retries do not re-add. */
    private val autoSeen = ConcurrentHashMap.newKeySet<String>()

    sealed interface State {
        data object Queued : State
        data object Running : State
        data object Cached : State
        data class Failed(val reason: String) : State
    }

    private val _active = MutableStateFlow<Map<String, State>>(emptyMap())
    val active: StateFlow<Map<String, State>> = _active.asStateFlow()

    /**
     * Whether the action sheet should offer a cache row for [song].
     *
     * A remote WebDAV/SMB row, or a saved file whose original source URL was
     * recorded, can be handed to the provider. A local file with no recorded
     * source cannot: the provider can fetch a URL, not this phone's storage.
     */
    fun canCache(song: Song): Boolean {
        val uri = song.localUri
        if (uri != null && (uri.startsWith("http://", true) ||
                uri.startsWith("https://", true) ||
                uri.startsWith(SmbConfig.SCHEME + "://", true))
        ) {
            return true
        }
        return Downloads.sourceUrlFor(song.videoId) != null
    }

    /** Drop a finished row, so reopening the sheet starts from a clean answer. */
    fun dismiss(videoId: String) {
        _active.update { current ->
            when (current[videoId]) {
                is State.Cached, is State.Failed -> current - videoId
                else -> current
            }
        }
    }

    /**
     * Cache [song]'s bytes into the account, reporting through [active].
     *
     * The row is keyed by the row the user tapped; a failed or finished state
     * stays until [dismiss] so the sheet can say what happened after the work
     * outlived the tap.
     */
    fun cacheSong(song: Song) {
        val key = AppSettings.debridApiKey.value.trim()
        if (key.isEmpty()) {
            _active.update { it + (song.videoId to State.Failed("Add a debrid API key first")) }
            return
        }
        val current = _active.value[song.videoId]
        if (current is State.Queued || current is State.Running) return

        _active.update { it + (song.videoId to State.Queued) }
        scope.launch {
            _active.update { it + (song.videoId to State.Running) }
            val outcome = try {
                cacheOne(song, key)
                State.Cached
            } catch (e: CancellationException) {
                _active.update { it - song.videoId }
                throw e
            } catch (e: Exception) {
                State.Failed(e.friendly())
            }
            _active.update { it + (song.videoId to outcome) }
        }
    }

    /**
     * Add an addon stream to the account after playback has already opened it.
     *
     * Called only for a stream that was successfully opened, and only when the
     * listener switched auto-cache on. Deduplicated per process: a player can
     * ask for the same stream again during an upgrade or a retry, and each of
     * those is the same add.
     */
    fun autoCacheAddonStream(
        stream: AddonStream,
        title: String?,
        artist: String?,
        format: String?,
    ) {
        if (!AppSettings.debridAutoCache.value) return
        val key = AppSettings.debridApiKey.value.trim()
        if (key.isEmpty()) return

        val hash = stream.infoHash?.trim()?.takeIf { it.isNotEmpty() }
        val url = stream.url.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (hash == null && url == null) return
        // A manifest is an index, not a file the provider's downloader can
        // keep. A torrent hash and a direct file URL are the only two shapes
        // that become a playable account entry.
        if (hash == null && (stream.transport != null || isManifestUrl(url.orEmpty()))) return

        val identity = hash?.lowercase(Locale.ROOT) ?: url.orEmpty()
        if (!autoSeen.add("$key\n$identity")) return
        val name = displayName(title, artist, url, format)

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (hash != null) {
                        if (DebridApi.library(key)?.any { it.hash.equals(hash, ignoreCase = true) } == true) {
                            return@withContext
                        }
                        DebridApi.addCachedTorrent(key, hash, magnet = stream.magnet, name = name)
                        DebridApi.invalidateLibrary()
                    } else if (url != null) {
                        if (!isPublicHttpUrl(url)) return@withContext
                        DebridApi.createWebDownload(key, url, name = name)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TrackLog.w(TAG, "Debrid auto-cache failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun cacheOne(song: Song, key: String) {
        val localUri = song.localUri

        if (localUri != null && localUri.startsWith(SmbConfig.SCHEME + "://", true)) {
            val link = bridgeSmbToWebDav(song, localUri)
            if (DebridApi.createWebDownload(key, link, displayName(song.title, song.artist, link, null)) == null) {
                throw IllegalStateException("Debrid could not fetch the bridged WebDAV file")
            }
            return
        }

        val link = localUri?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        } ?: Downloads.sourceUrlFor(song.videoId)
            ?: throw IllegalStateException(
                "This file has no public source link. Debrid can only fetch a URL, not this phone's storage.",
            )

        if (isManifestUrl(link)) {
            throw IllegalStateException("The original was a stream manifest, not a file Debrid can keep.")
        }
        if (!isPublicHttpUrl(link)) {
            throw IllegalStateException(
                "Debrid cannot reach a private or local address. The source must be public.",
            )
        }
        if (isAuthenticatedWebDavUrl(link)) {
            throw IllegalStateException(
                "Debrid cannot sign in to this WebDAV server. Use a share that is public without a password.",
            )
        }
        if (DebridApi.createWebDownload(key, link, displayName(song.title, song.artist, link, null)) == null) {
            throw IllegalStateException("Debrid could not fetch that link")
        }
    }

    /**
     * Copy an SMB file to the configured WebDAV server and return the URL the
     * provider can fetch.
     *
     * The bridge is only offered where it can work: a WebDAV server that is
     * configured, reachable from outside this network, and open without
     * authentication. TorBox carries no credentials for a private server, and
     * an SMB share is not a URL it can open at all.
     */
    private suspend fun bridgeSmbToWebDav(song: Song, smbUrl: String): String {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) {
            throw IllegalStateException(
                "Debrid cannot open an SMB share. Configure a public WebDAV server to bridge the file.",
            )
        }
        if (AppSettings.webdavUsername.value.isNotBlank() ||
            AppSettings.webdavPassword.value.isNotBlank()
        ) {
            throw IllegalStateException(
                "Debrid cannot sign in to WebDAV. The bridge needs a server that is public without a password.",
            )
        }
        if (!isPublicHttpUrl(base)) {
            throw IllegalStateException(
                "The configured WebDAV server is not reachable from the internet, so Debrid cannot fetch the bridge copy.",
            )
        }

        val path = SmbConfig.relativePath(smbUrl, SmbAuth.share)
            ?: throw IllegalStateException("That SMB row does not name a file on the configured share")
        val extension = extensionOf(path).ifBlank { "mp3" }
        val handle = SmbClient.openFile(path)
        val length = try {
            handle.length
        } catch (e: Exception) {
            runCatching { handle.close() }
            throw IllegalStateException("Could not read the SMB file: ${e.message}")
        }
        if (length <= 0) {
            runCatching { handle.close() }
            throw IllegalStateException("The SMB file is empty")
        }

        return try {
            withContext(Dispatchers.IO) {
                val root = WebDavConfig.normalizeUrl(base)
                val folder = WebDavClient.joinUrl(root, BRIDGE_FOLDER)
                WebDavClient.ensureCollection(folder, "", "").getOrThrow()
                val target = WebDavClient.joinUrl(
                    folder,
                    WebDavClient.uploadFileName(song.title, song.artist, extension),
                )
                val input = SmbFileInputStream(handle, length)
                WebDavClient.putFile(
                    fileUrl = target,
                    stream = input,
                    contentLength = length,
                    mimeType = mimeTypeFor(extension),
                    username = "",
                    password = "",
                    overwrite = true,
                ).getOrThrow()
                target
            }
        } finally {
            // The upload body closes the stream on the normal path; this also
            // covers every failure before the body starts writing, where the
            // handle would otherwise stay open until the session expires.
            runCatching { handle.close() }
        }
    }

    /**
     * Whether a provider on the internet could plausibly reach [raw].
     *
     * Deliberately conservative about the RFC 1918 ranges, loopback, link-local
     * and the usual local suffixes. A hostname that merely has no dot is left
     * alone: it may be a perfectly public name, and refusing it here would be
     * guessing.
     */
    internal fun isPublicHttpUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        // Credentials in the URL are not something the provider can use, and
        // forwarding them to a third party is worse than declining the row.
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host == "::1" || host == "0.0.0.0") return false
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal")) return false
        if (host.startsWith("127.") || host.startsWith("169.254.")) return false
        if (host.startsWith("10.") || host.startsWith("192.168.")) return false
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return false
        }
        if (':' in host) {
            // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
            if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
                host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
            ) {
                return false
            }
        }
        return true
    }

    internal fun isManifestUrl(raw: String): Boolean {
        val path = raw.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    /**
     * A URL under the configured WebDAV library while that library has a
     * username or password. The provider gets no credentials from here, so
     * pretending it could read this would only produce a late 401.
     */
    private fun isAuthenticatedWebDavUrl(raw: String): Boolean {
        if (AppSettings.webdavUsername.value.isBlank() &&
            AppSettings.webdavPassword.value.isBlank()
        ) {
            return false
        }
        val root = WebDavConfig.normalizeUrl(AppSettings.webdavUrl.value).trimEnd('/')
        if (root.isEmpty()) return false
        return raw.trim().lowercase(Locale.ROOT)
            .startsWith(root.lowercase(Locale.ROOT) + "/")
    }

    private fun displayName(title: String?, artist: String?, url: String?, format: String?): String? {
        val sourceName = url?.let(::fileNameOf)
        if (sourceName != null && extensionOf(sourceName).length in 1..5) return sourceName
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val extension = extensionOf(url.orEmpty()).ifBlank { extensionOfFormat(format) }
        return WebDavClient.uploadFileName(cleanTitle, artist.orEmpty(), extension)
    }

    private fun fileNameOf(url: String): String? = runCatching {
        val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        URLDecoder.decode(raw, "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun extensionOf(value: String): String =
        value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            .orEmpty()

    private fun extensionOfFormat(format: String?): String = when (format?.lowercase(Locale.ROOT)) {
        "flac" -> "flac"
        "alac" -> "m4a"
        "mp3" -> "mp3"
        "aac", "m4a", "mp4" -> "m4a"
        "eac3", "ec3", "atmos" -> "eac3"
        "ac4", "ac-4" -> "ac4"
        "ogg" -> "ogg"
        "opus" -> "opus"
        "wav" -> "wav"
        else -> "flac"
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "m4a", "mp4", "alac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "eac3", "ec3" -> "audio/eac3"
        "ac4" -> "audio/ac4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private fun Throwable.friendly(): String = when (this) {
        is IllegalStateException, is IllegalArgumentException -> message ?: "Could not cache to Debrid"
        else -> "Could not cache to Debrid — check your connection"
    }

    /**
     * An [InputStream] over an SMB file handle, using absolute offsets because
     * the handle holds no cursor. The handle is closed with the stream, which
     * is what [WebDavClient.putFile] does on every exit.
     */
    private class SmbFileInputStream(
        private val handle: com.hierynomus.smbj.share.File,
        private val length: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (position >= length) return -1
            val capped = minOf(count.toLong(), length - position).toInt()
            val read = handle.read(buffer, position, offset, capped)
            if (read <= 0) return -1
            position += read
            return read
        }

        override fun available(): Int =
            (length - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        override fun close() {
            runCatching { handle.close() }
        }
    }
}
