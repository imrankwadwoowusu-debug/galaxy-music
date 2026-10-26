package com.music.bitchord.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Links to other platforms' music — Spotify, Apple Music, Deezer, SoundCloud —
 * that BitChord can serve by finding the same music on YouTube Music.
 *
 * None of these platforms hand out a stream this app can play, and their links
 * name a song or a page by an id YouTube has never heard of. So a link is turned
 * into text instead: its title and artist, read from the platform's own public
 * metadata endpoint. That text then goes through the same search a spoken
 * "play …" request does, see [LinkRequest.Search].
 *
 * The hosts are listed by hand in the manifest's `ExternalLinksAlias`. Adding a
 * platform means: a [Platform] entry, a branch in [parse] and one in [describe],
 * and its host in the manifest, where it becomes a line the user can tick under
 * "Supported web addresses".
 */
object ExternalLinks {

    /** The manifest alias carrying the other platforms' intent filters. See [setEnabled]. */
    private const val ALIAS = "com.music.bitchord.ExternalLinksAlias"

    /** Spotify's page is far larger than its `<head>`, where the tags read here sit. */
    private const val PAGE_HEAD_BYTES = 64L * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** First path words on SoundCloud that are site pages, not a user's own `artist/track`. */
    private val SOUNDCLOUD_NOT_ARTISTS = setOf(
        "search", "discover", "charts", "you", "stream", "upload", "pages", "jobs",
    )

    /** Second path words on a SoundCloud profile that are its tabs, not one of its songs. */
    private val SOUNDCLOUD_PROFILE_TABS = setOf(
        "tracks", "albums", "popular-tracks", "reposts", "likes", "following", "followers", "comments",
    )

    enum class Platform { SPOTIFY, APPLE_MUSIC, DEEZER, SOUNDCLOUD }

    /** A song, or a page of several. Decides whether the match plays or is only shown. */
    enum class Kind { TRACK, COLLECTION }

    /**
     * A link on another platform, reduced to what its metadata endpoint needs.
     *
     * [noun] is the path word the link used for what it names (`track`, `album`,
     * `playlist`, `set`), kept because Deezer's API files each one under its own path.
     */
    data class Link(
        val platform: Platform,
        val kind: Kind,
        val noun: String,
        val id: String,
        val url: String,
    )

    /**
     * Turns a link on another platform into what it names, or null for a link
     * this app does not understand — a Spotify artist page, a Deezer chart, a
     * short link that would need following first.
     */
    fun parse(uri: Uri): Link? =
        parse(uri.host, uri.pathSegments.orEmpty(), uri.getQueryParameter("i"), uri.toString())

    /**
     * [parse] on the pieces of a link rather than the [Uri] itself, which the
     * unit tests' stub framework cannot build. [song] is Apple Music's `?i=`.
     */
    internal fun parse(host: String?, pathSegments: List<String>, song: String?, url: String): Link? {
        val segments = pathSegments.filterNot { it.startsWith("intl-") }
        return when (host?.lowercase()?.removePrefix("www.")) {
            "open.spotify.com" -> {
                // An artist page is a selection rather than a running order, so
                // only the three kinds that are a list of songs get through.
                val noun = segments.firstOrNull() ?: return null
                val kind = kindOf(noun) ?: return null
                segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?.let { Link(Platform.SPOTIFY, kind, noun, it, url) }
            }
            "music.apple.com" -> {
                // A song inside an album link is named by `?i=`. A bare album
                // link ends in the album's numeric id. Apple's playlist ids are
                // not ones its lookup endpoint knows, so those are left out.
                song?.takeIf { it.isNotBlank() }?.let {
                    return Link(Platform.APPLE_MUSIC, Kind.TRACK, "track", it, url)
                }
                if (segments.getOrNull(1) != "album") return null
                segments.lastOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.let { Link(Platform.APPLE_MUSIC, Kind.COLLECTION, "album", it, url) }
            }
            "deezer.com" -> {
                // Deezer puts a two-letter language first (`/en/track/…`).
                val path = segments.dropWhile { it.length == 2 }
                val noun = path.firstOrNull() ?: return null
                val kind = kindOf(noun) ?: return null
                path.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.let { Link(Platform.DEEZER, kind, noun, it, url) }
            }
            "soundcloud.com", "m.soundcloud.com" -> {
                // `artist/track` is a song and `artist/sets/name` a playlist.
                // Anything else on the site — search, charts, a profile and its
                // tabs — is not.
                if (segments.size < 2 || segments[0] in SOUNDCLOUD_NOT_ARTISTS) return null
                val isSet = segments[1] == "sets"
                if (isSet && segments.size < 3) return null
                if (!isSet && segments.size == 2 && segments[1] in SOUNDCLOUD_PROFILE_TABS) return null
                Link(
                    Platform.SOUNDCLOUD,
                    if (isSet) Kind.COLLECTION else Kind.TRACK,
                    if (isSet) "set" else "track",
                    segments.joinToString("/"),
                    url,
                )
            }
            else -> null
        }
    }

    private fun kindOf(noun: String): Kind? = when (noun) {
        "track" -> Kind.TRACK
        "album", "playlist" -> Kind.COLLECTION
        else -> null
    }

    /**
     * Reads what [link] names and turns it into the search that finds it on
     * YouTube Music. Null when the platform cannot be reached or does not know
     * the id; the caller then tells the user the link could not be opened.
     */
    suspend fun resolve(link: Link): LinkRequest.Search? {
        val text = describe(link)?.takeIf { it.isNotBlank() } ?: return null
        // A single song is one answer, so it is played like a spoken request.
        // A page of songs is shown, since the match is one of many that name it.
        return LinkRequest.Search(text, play = link.kind == Kind.TRACK)
    }

    /** `"Title Artist"`, or null. Each platform's own public endpoint, no key needed. */
    private suspend fun describe(link: Link): String? = when (link.platform) {
        // The oEmbed title is the song's name alone, which on its own matches
        // whichever song of that name YouTube ranks first. The artist is only
        // on the page itself, so it is read from there, when it can be.
        Platform.SPOTIFY -> fetchJson(
            "https://open.spotify.com/oembed".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("url", link.url)
                ?.build(),
        )?.string("title")?.let { title ->
            val artist = if (link.noun == "playlist") null else spotifyArtist(fetchPageHead(link.url))
            listOfNotNull(title, artist).joinToString(" ")
        }

        Platform.APPLE_MUSIC -> fetchJson(
            "https://itunes.apple.com/lookup".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("id", link.id)
                ?.build(),
        )?.let { appleSearchText(link.kind, it) }

        Platform.DEEZER -> fetchJson("https://api.deezer.com/${link.noun}/${link.id}".toHttpUrlOrNull())
            ?.let(::deezerSearchText)

        Platform.SOUNDCLOUD -> fetchJson(
            "https://soundcloud.com/oembed".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("url", link.url)
                ?.build(),
        )?.string("title")
    }

    /** iTunes lookup: a track is named by its own title, an album by `collectionName`. */
    internal fun appleSearchText(kind: Kind, lookup: JsonObject): String? {
        val item = (lookup["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val title = item.string(if (kind == Kind.TRACK) "trackName" else "collectionName") ?: return null
        return listOfNotNull(title, item.string("artistName")).joinToString(" ")
    }

    /** Deezer API: a playlist has no single artist to add after its title. */
    internal fun deezerSearchText(item: JsonObject): String? {
        val title = item.string("title") ?: return null
        val artist = (item["artist"] as? JsonObject)?.string("name")
        return listOfNotNull(title, artist).joinToString(" ")
    }

    private val SPOTIFY_DESCRIPTION =
        Regex("""property="og:description"\s+content="([^"]*)"""")

    /**
     * The artist named first in a Spotify page's description — `"Rick Astley ·
     * Whenever You Need Somebody · Song · 1987"` — or null when the page has no
     * such tag, as when it could not be read.
     */
    internal fun spotifyArtist(pageHead: String?): String? {
        val description = pageHead?.let { SPOTIFY_DESCRIPTION.find(it)?.groupValues?.get(1) } ?: return null
        return unescapeHtml(description.substringBefore(" · ")).trim().takeIf { it.isNotEmpty() }
    }

    private val HTML_ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    private fun unescapeHtml(text: String): String = HTML_ENTITY.replace(text) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }

    /**
     * Points the manifest alias at the Settings choice.
     *
     * Disabling it takes the other platforms' hosts out of what the system
     * routes to BitChord, so their links open in the app that owns them again.
     * The flag is set too, for links that still reach the app another way (a
     * share sheet), which [MusicLink] then ignores.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        AppSettings.setExternalLinks(enabled)
        applyAlias(context, enabled)
    }

    /** Puts the alias where the saved choice says it should be. Run once the settings are loaded. */
    fun sync(context: Context) = applyAlias(context, AppSettings.externalLinks.value)

    private fun applyAlias(context: Context, enabled: Boolean) {
        // The TV app is built from these same sources but declares no such alias
        // in its own manifest, and the system throws for a component it does not
        // know. There the switch is only the flag, which [MusicLink] honours.
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** The JSON object at [url], or null on any failure: no network, a non-2xx, a body that is not an object. */
    private suspend fun fetchJson(url: HttpUrl?): JsonObject? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let { json.parseToJsonElement(it) as? JsonObject }
                }
            }.getOrNull()
        }
    }

    /** The first [PAGE_HEAD_BYTES] of the page at [url], or null on any failure. */
    private suspend fun fetchPageHead(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // English, so the description's first part is the artist in
                // every region rather than a translated label.
                val request = Request.Builder().url(httpUrl).header("Accept-Language", "en").build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val source = response.body?.source() ?: return@use null
                    source.request(PAGE_HEAD_BYTES)
                    source.buffer.readUtf8(minOf(source.buffer.size, PAGE_HEAD_BYTES))
                }
            }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
