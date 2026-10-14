package com.music.bitchord.data.service

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.innertube.PlayerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The service file on Android: persistence, observable UI state, and the
 * networked import paths. All values and validation live in shared
 * [ServiceValues], which every platform target reads; this object mirrors
 * the installed file into prefs and a [StateFlow] for Settings.
 */
object ServiceConfig {

    /** Whether [host] may receive the session cookie and API signature. */
    fun canSendAuth(host: String): Boolean = ServiceValues.canSendAuth(host)

    // ---- Store ---------------------------------------------------------------

    private var prefs: android.content.SharedPreferences? = null

    /** The installed file, or null while none is imported. */
    private val _state = MutableStateFlow<ServiceValues.Active?>(null)
    val state: StateFlow<ServiceValues.Active?> = _state.asStateFlow()

    val isReady: Boolean get() = ServiceValues.isReady

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY_JSON, null) ?: return
        val at = prefs?.getLong(KEY_IMPORTED_AT, 0L) ?: 0L
        ServiceValues.validate(raw).onSuccess { file ->
            val fingerprint = ServiceValues.fingerprintOf(raw)
            ServiceValues.install(file, fingerprint, at)
            _state.value = ServiceValues.requireReady()
        }.onFailure {
            // A file this build can no longer read is dropped rather than
            // half-honoured; the Settings screen reports it as missing.
            prefs?.edit()?.remove(KEY_JSON)?.remove(KEY_IMPORTED_AT)?.apply()
        }
    }

    /** For JVM unit tests only: installs [file] without touching storage. */
    fun installForTests(file: ServiceFile) {
        ServiceValues.installForTests(file)
        _state.value = ServiceValues.requireReady()
    }

    /** For JVM unit tests only. */
    fun clearForTests() {
        ServiceValues.clearForTests()
        _state.value = null
    }

    fun importText(text: String): Result<ServiceValues.ImportSummary> = runCatching {
        require(text.length <= ServiceValues.MAX_FILE_CHARS) { "That file is too large to be a service file" }
        val summary = ServiceValues.importText(text).getOrThrow()
        val now = System.currentTimeMillis()
        prefs?.edit()
            ?.putString(KEY_JSON, text)
            ?.putLong(KEY_IMPORTED_AT, now)
            ?.apply()
        _state.value = ServiceValues.requireReady()
        summary
    }

    suspend fun importUri(context: Context, source: Uri): Result<ServiceValues.ImportSummary> {
        val text = runCatching {
            context.contentResolver.openInputStream(source)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Couldn't open that file")
        }.getOrElse { return Result.failure(it) }
        return importText(text)
    }

    /**
     * Pre-checks a service file link before anything is fetched. Pure and
     * unit-tested: https only, no credentials, public host, sane length.
     */
    fun checkImportUrl(url: String): Result<Unit> = runCatching {
        val u = url.trim()
        require(u.length in 12..MAX_URL_CHARS) { "That doesn't look like a file link" }
        require(u.startsWith("https://")) { "Service file links must be https" }
        val authority = u.substringAfter("://").substringBefore("/")
        require(authority.isNotEmpty() && "@" !in authority) {
            "Service file links must not carry credentials"
        }
        val host = authority.substringBefore(":")
        require(ServiceValues.isBareHost(host)) { "That link doesn't name a valid host" }
        require(!ServiceValues.isPrivateHost(host)) { "Service file links must point at a public host" }
    }

    /**
     * Downloads [url] and imports it as a service file.
     *
     * Explicitly user-invoked only: nothing in the app fetches a link on its
     * own, no link is baked in, and an imported file is never re-fetched. The
     * download is size-capped and the text goes through the same validation
     * as a picked file.
     */
    suspend fun importUrl(url: String): Result<ServiceValues.ImportSummary> {
        checkImportUrl(url).getOrElse { return Result.failure(it) }
        val text = runCatching {
            kotlinx.coroutines.withTimeout(30_000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url.trim()).get().build()
                    com.music.bitchord.data.Http.client.newCall(request).execute().use { response ->
                        require(response.isSuccessful) { "That link answered ${response.code}" }
                        val body = response.body ?: error("That link sent nothing back")
                        val sink = okio.Buffer()
                        var remaining = ServiceValues.MAX_FILE_CHARS + 1L
                        val source = body.source()
                        while (!source.exhausted()) {
                            val read = source.read(sink, minOf(8_192L, remaining))
                            if (read == -1L) break
                            remaining -= read
                            require(remaining > 0) { "That file is too large to be a service file" }
                        }
                        sink.readUtf8()
                    }
                }
            }
        }.getOrElse { return Result.failure(it) }
        return importText(text)
    }

    /**
     * One cheap anonymous request against the file's own reachability URL.
     * Reports the HTTP code; anything thrown (DNS, TLS, timeout) reads as
     * unreachable rather than as a verdict on the file's contents.
     */
    suspend fun probe(): Result<Int> = runCatching {
        val active = requireReady()
        kotlinx.coroutines.withTimeout(15_000L) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = okhttp3.Request.Builder()
                    .url(active.file.swDataUrl)
                    .header("User-Agent", active.file.webClient.ua)
                    .get()
                    .build()
                com.music.bitchord.data.Http.client.newCall(request).execute().use { it.code }
            }
        }
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_JSON)?.remove(KEY_IMPORTED_AT)?.apply()
        ServiceValues.clear()
        _state.value = null
    }

    fun requireReady(): ServiceValues.Active = ServiceValues.requireReady()

    fun validate(text: String): Result<ServiceFile> = ServiceValues.validate(text)

    // ---- Typed accessors (all throw [ServiceFileRequired] while empty) --------

    fun musicBase(): String = ServiceValues.musicBase()
    fun tubeBase(): String = ServiceValues.tubeBase()
    fun musicOrigin(): String = ServiceValues.musicOrigin()
    fun tubeOrigin(): String = ServiceValues.tubeOrigin()
    fun webName(): String = ServiceValues.webName()
    fun webVersion(): String = ServiceValues.webVersion()
    fun webId(): String = ServiceValues.webId()
    fun webUa(): String = ServiceValues.webUa()
    fun webStats(): StatsP = ServiceValues.webStats()
    fun swDataUrl(): String = ServiceValues.swDataUrl()
    fun loginOrigin(): String = ServiceValues.loginOrigin()
    fun loginUrl(): String = ServiceValues.loginUrl()
    fun loginCookieHosts(): List<String> = ServiceValues.loginCookieHosts()
    fun catalogue(): CatalogueBlock = ServiceValues.catalogue()
    fun shareOrigin(): String = ServiceValues.shareOrigin()
    fun linkHosts(): Set<String> = ServiceValues.linkHosts()
    fun shortHost(): String = ServiceValues.shortHost()

    fun watchUrl(videoId: String): String = ServiceValues.watchUrl(videoId)
    fun channelUrl(channelId: String): String = ServiceValues.channelUrl(channelId)
    fun playlistUrl(listId: String): String = ServiceValues.playlistUrl(listId)
    fun browseUrl(browseId: String): String = ServiceValues.browseUrl(browseId)

    /** Whether [url] names the configured stream host (or its subdomains). */
    fun isStreamHost(url: String): Boolean = ServiceValues.isStreamHost(url)

    /**
     * The browser-shaped web client as a player identity. Only meaningful
     * where a caller dresses a fetch as it; the player walk itself lives in
     * the extraction library's catalog now.
     */
    fun webPlayerClient(): PlayerClient = ServiceValues.webPlayerClient()

    /**
     * Player clients in file order, cheapest and most reliable first as the
     * file's author ranked them. Used to dress media fetches for URLs the
     * extraction library did not mint (chiefly the extractor failsafe's).
     */
    fun orderedClients(): List<PlayerClient> = ServiceValues.orderedClients()

    /**
     * The client a stream URL says minted it, so the media fetch can be
     * dressed as that client. Falls back to the first ordered client for a
     * URL that names no known client.
     */
    fun clientForUrl(url: String): PlayerClient = ServiceValues.clientForUrl(url)

    private const val PREFS = "service_config"
    private const val KEY_JSON = "service_file_json"
    private const val KEY_IMPORTED_AT = "service_file_imported_at"

    private const val MAX_URL_CHARS = 2_048
}
