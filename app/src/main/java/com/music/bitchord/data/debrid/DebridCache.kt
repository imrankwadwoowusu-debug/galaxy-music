package com.music.bitchord.data.debrid

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.smb.SmbAuth
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.smb.SmbConfig
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
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
 *    downloader, which fetches it itself.
 *  - **A rejected addon URL** falls back to a torrent made from the complete
 *    local playback-cache copy. The device seeds the bytes until TorBox reports
 *    completion; only the .torrent metadata is sent through the API.
 *  - **An SMB file** is first copied to the configured public WebDAV server,
 *    then fetched from there by the provider. An SMB share is not something
 *    TorBox can open, and a WebDAV server that requires a password is not
 *    something it can sign in to, so both are stated plainly rather than
 *    attempted and failed. (A local file needs no WebDAV bridge: the seeder
 *    route can serve a completed copy directly from this device.)
 *
 * Automatic caching is post-play and best-effort. Once a mapping exists, a
 * later play asks TorBox for a fresh URL before contacting the addon, and
 * falls back to the addon if the provider-side copy is not ready. Manual
 * caching gets a row in the song's action sheet so its outcome is visible.
 */
object DebridCache {

    /** The folder the SMB bridge writes into, under the configured WebDAV root. */
    private const val BRIDGE_FOLDER = "BitChord Debrid"
    private const val PREFS_NAME = "bitchord_debrid_stream_cache"
    private const val PREF_REFERENCES = "addon_references"
    private const val REPLAY_LOOKUP_TIMEOUT_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val references = ConcurrentHashMap<String, AddonCacheReference>()
    @Volatile private var preferences: SharedPreferences? = null

    @Serializable
    private data class AddonCacheReference(
        val torrentHash: String? = null,
        val fileIndex: Int = -1,
        val webDownloadId: Long? = null,
        val pendingTorrentUpload: Boolean = false,
        val apiKeyFingerprint: String,
        val format: CachedStreamFormat,
        val durationSec: Int? = null,
    )

    @Serializable
    private data class CachedStreamFormat(
        val codec: String? = null,
        val kbps: Int? = null,
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
        val kbpsInferred: Boolean = false,
        val container: String? = null,
    ) {
        fun toStreamFormat() = StreamFormat(codec, kbps, sampleRateHz, bitDepth, kbpsInferred, container)

        companion object {
            fun from(format: StreamFormat) = CachedStreamFormat(
                format.codec, format.kbps, format.sampleRateHz, format.bitDepth,
                format.kbpsInferred, format.container,
            )
        }
    }

    data class CachedAddonStream(val url: String, val format: StreamFormat, val durationSec: Int?)

    /** Restore stable addon-to-cache mappings before playback sources are built. */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        val stored = runCatching {
            val raw = prefs.getString(PREF_REFERENCES, null) ?: return@runCatching emptyMap()
            codec.decodeFromString(MapSerializer(String.serializer(), AddonCacheReference.serializer()), raw)
        }.getOrElse {
            TrackLog.w(TAG, "Could not restore addon cache references: ${it.message}")
            emptyMap()
        }
        synchronized(references) {
            references.clear()
            references.putAll(stored)
        }
    }

    /** Stable across process restarts and scoped to one configured addon row. */
    internal fun addonCacheKey(configId: String, trackId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$configId\n$trackId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun rememberAddonCache(cacheKey: String, reference: AddonCacheReference) {
        synchronized(references) {
            references[cacheKey] = reference
            val prefs = preferences ?: return
            val encoded = codec.encodeToString(
                MapSerializer(String.serializer(), AddonCacheReference.serializer()),
                references.toMap(),
            )
            prefs.edit().putString(PREF_REFERENCES, encoded).apply()
        }
    }

    /** Get a fresh TorBox URL for a previously cached addon stream, or miss. */
    suspend fun cachedAddonStream(cacheKey: String): CachedAddonStream? {
        val reference = references[cacheKey] ?: return null
        val key = AppSettings.debridApiKey.value.trim().takeIf { it.isNotEmpty() } ?: return null
        if (reference.apiKeyFingerprint != apiKeyFingerprint(key)) return null
        return withTimeoutOrNull(REPLAY_LOOKUP_TIMEOUT_MS) {
            val url = if (reference.webDownloadId != null) {
                DebridApi.webDownloadUrl(key, reference.webDownloadId)
            } else {
                val hash = reference.torrentHash ?: return@withTimeoutOrNull null
                val library = DebridApi.library(key) ?: return@withTimeoutOrNull null
                val torrentFiles = library.filter {
                    it.hash.equals(hash, ignoreCase = true)
                }
                val file = if (reference.fileIndex >= 0) {
                    torrentFiles.firstOrNull { it.fileIndex == reference.fileIndex }
                } else {
                    // Without Stremio's fileIdx only a single-file torrent is
                    // unambiguous. Never silently stream another file in a pack.
                    torrentFiles.singleOrNull()
                } ?: run {
                    // The library cache may have captured this torrent while
                    // it was still finishing; let the very next play refresh.
                    DebridApi.invalidateLibrary()
                    return@withTimeoutOrNull null
                }
                DebridApi.downloadUrl(key, file.torrentId, file.fileId)
            } ?: return@withTimeoutOrNull null
            CachedAddonStream(url, reference.format.toStreamFormat(), reference.durationSec)
        }
    }

    private fun apiKeyFingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun needsLocalTorrentUpload(mediaId: String): Boolean {
        if (!AppSettings.debridAutoCache.value) return false
        val (configId, trackId) = SourceRegistry.parseTrackKey(mediaId) ?: return false
        val reference = references[addonCacheKey(configId, trackId)] ?: return false
        val key = AppSettings.debridApiKey.value.trim().takeIf { it.isNotEmpty() } ?: return false
        return reference.pendingTorrentUpload && reference.apiKeyFingerprint == apiKeyFingerprint(key)
    }

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
        cacheKey: String,
        title: String?,
        artist: String?,
        format: String?,
        sourceFormat: StreamFormat,
        durationSec: Int?,
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
        if (stream.transport != null || isManifestUrl(url.orEmpty())) return

        val identity = hash?.lowercase(Locale.ROOT)
            ?.let { "$it\n${stream.fileIdx?.coerceAtLeast(0) ?: -1}" }
            ?: url.orEmpty()
        val seenKey = "$key\n$cacheKey\n$identity"
        if (!autoSeen.add(seenKey)) return
        val name = displayName(title, artist, url, format)
        val savedFormat = CachedStreamFormat.from(sourceFormat)
        val keyFingerprint = apiKeyFingerprint(key)

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (hash != null) {
                        val present = DebridApi.library(key)?.any {
                            it.hash.equals(hash, ignoreCase = true)
                        }
                        if (present != true) {
                            val added = DebridApi.addCachedTorrent(key, hash, magnet = stream.magnet, name = name)
                            if (added == null) {
                                TrackLog.w(TAG, "Debrid could not add addon torrent ${hash.take(12)} yet")
                            }
                            DebridApi.invalidateLibrary()
                        }
                        rememberAddonCache(
                            cacheKey,
                            AddonCacheReference(
                                torrentHash = hash.lowercase(Locale.ROOT),
                                fileIndex = stream.fileIdx?.takeIf { it >= 0 } ?: -1,
                                apiKeyFingerprint = keyFingerprint,
                                format = savedFormat,
                                durationSec = durationSec,
                            ),
                        )
                    } else if (url != null) {
                        if (!isPublicHttpUrl(url)) return@withContext
                        val webId = DebridApi.createWebDownload(key, url, name = name)
                        if (webId != null) {
                            rememberAddonCache(
                                cacheKey,
                                AddonCacheReference(
                                    webDownloadId = webId,
                                    apiKeyFingerprint = keyFingerprint,
                                    format = savedFormat,
                                    durationSec = durationSec,
                                ),
                            )
                        } else {
                            // The URL route is often refused for CDN URLs. Keep
                            // a client-local mapping so playback-end handling
                            // can seed the complete BitChord cache file instead.
                            rememberAddonCache(
                                cacheKey,
                                AddonCacheReference(
                                    pendingTorrentUpload = true,
                                    apiKeyFingerprint = keyFingerprint,
                                    format = savedFormat,
                                    durationSec = durationSec,
                                ),
                            )
                            throw IllegalStateException(
                                "Debrid refused addon stream host ${urlHost(url) ?: "(unknown)"}; " +
                                    "will try the completed local cache",
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    autoSeen.remove(seenKey)
                    throw e
                } catch (e: Exception) {
                    autoSeen.remove(seenKey)
                    TrackLog.w(TAG, "Debrid auto-cache failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Turn a complete played-cache entry into a TorBox torrent upload. The
     * media bytes are copied out of Media3's cache, seeded by libtorrent4j,
     * and kept available until TorBox's account listing reports completion.
     */
    suspend fun uploadCompletedAddonCache(
        context: Context,
        mediaId: String,
        title: String,
        artist: String,
    ) {
        if (!AppSettings.debridAutoCache.value) return
        val (configId, trackId) = SourceRegistry.parseTrackKey(mediaId) ?: return
        val cacheKey = addonCacheKey(configId, trackId)
        val reference = references[cacheKey]?.takeIf { it.pendingTorrentUpload } ?: return
        val apiKey = AppSettings.debridApiKey.value.trim().takeIf { it.isNotEmpty() } ?: return
        if (reference.apiKeyFingerprint != apiKeyFingerprint(apiKey)) return

        val complete = AudioCache.completeCopyOf(mediaId) ?: run {
            TrackLog.w(TAG, "No complete local cache copy to seed for '$title'")
            return
        }
        val stagingDir = File(context.applicationContext.noBackupFilesDir, "debrid-seeding/$cacheKey")
            .apply { mkdirs() }
        val fileName = displayName(title, artist, null, complete.extension)
            ?: "$cacheKey.${complete.extension}"
        val staged = File(stagingDir, fileName)
        try {
            FileOutputStream(staged).use { output -> AudioCache.copyOut(complete, output) { _, _ -> } }
            val cached = DebridTorrentSeeder.transfer(context.applicationContext, staged, apiKey, fileName)
            rememberAddonCache(
                cacheKey,
                reference.copy(
                    torrentHash = cached.infoHash,
                    fileIndex = 0,
                    webDownloadId = null,
                    pendingTorrentUpload = false,
                ),
            )
            TrackLog.d(TAG, "TorBox finished caching '$title' from this device (${cached.infoHash.take(12)})")
        } finally {
            stagingDir.deleteRecursively()
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

    /** Host-only diagnostics: never log path, query parameters, or URL credentials. */
    internal fun urlHost(raw: String): String? = runCatching {
        URI(raw.trim()).takeIf { it.userInfo == null }?.host?.lowercase(Locale.ROOT)
    }.getOrNull()?.takeIf { it.isNotBlank() }

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
