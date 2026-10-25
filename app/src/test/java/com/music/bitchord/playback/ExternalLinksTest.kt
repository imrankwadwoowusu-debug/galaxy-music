package com.music.bitchord.playback

import com.music.bitchord.playback.ExternalLinks.Kind
import com.music.bitchord.playback.ExternalLinks.Link
import com.music.bitchord.playback.ExternalLinks.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinksTest {

    private fun parse(host: String, vararg path: String, song: String? = null): Link? =
        ExternalLinks.parse(host, path.toList(), song, "https://$host/${path.joinToString("/")}")

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun spotifyTrackAlbumAndPlaylistAreRecognised() {
        assertEquals(
            Link(Platform.SPOTIFY, Kind.TRACK, "track", "4cOd", "https://open.spotify.com/track/4cOd"),
            parse("open.spotify.com", "track", "4cOd"),
        )
        assertEquals(Kind.COLLECTION, parse("open.spotify.com", "album", "6ZG5")?.kind)
        assertEquals(Kind.COLLECTION, parse("open.spotify.com", "playlist", "37i9")?.kind)
    }

    @Test
    fun spotifyLanguagePrefixIsSkippedAndArtistPagesAreRefused() {
        assertEquals("4cOd", parse("open.spotify.com", "intl-es", "track", "4cOd")?.id)
        assertNull(parse("open.spotify.com", "artist", "0gxy"))
        assertNull(parse("open.spotify.com", "track"))
        assertNull(parse("open.spotify.com"))
    }

    @Test
    fun appleMusicSongInsideAnAlbumIsNamedByTheQueryParameter() {
        val link = parse("music.apple.com", "us", "album", "some-album", "1440857781", song = "1440857800")
        assertEquals(Kind.TRACK, link?.kind)
        assertEquals("1440857800", link?.id)
    }

    @Test
    fun appleMusicBareAlbumNeedsANumericId() {
        val link = parse("music.apple.com", "us", "album", "some-album", "1440857781")
        assertEquals(Kind.COLLECTION, link?.kind)
        assertEquals("1440857781", link?.id)
        assertNull(parse("music.apple.com", "us", "album", "some-album"))
        // Playlist ids are not ones the lookup endpoint knows.
        assertNull(parse("music.apple.com", "us", "playlist", "mix", "pl.u-abc"))
    }

    @Test
    fun deezerSkipsTheLanguageAndKeepsTheNoun() {
        val track = parse("www.deezer.com", "en", "track", "3135556")
        assertEquals(Link(Platform.DEEZER, Kind.TRACK, "track", "3135556", track?.url ?: ""), track)
        assertEquals("playlist", parse("deezer.com", "playlist", "908622995")?.noun)
        assertNull(parse("deezer.com", "en", "artist", "27"))
        assertNull(parse("deezer.com", "en", "track", "not-a-number"))
    }

    @Test
    fun soundCloudTellsASongFromAPlaylistAndFromSitePages() {
        val song = parse("soundcloud.com", "flume", "say-it")
        assertEquals(Kind.TRACK, song?.kind)
        assertEquals("flume/say-it", song?.id)

        val set = parse("m.soundcloud.com", "flume", "sets", "mix")
        assertEquals(Kind.COLLECTION, set?.kind)
        assertEquals("set", set?.noun)

        assertNull(parse("soundcloud.com", "flume"))
        assertNull(parse("soundcloud.com", "flume", "sets"))
        assertNull(parse("soundcloud.com", "flume", "likes"))
        assertNull(parse("soundcloud.com", "search", "sounds"))
        assertNull(parse("soundcloud.com", "discover", "sets"))
    }

    @Test
    fun otherHostsAndMissingHostsAreNotLinksToThisApp() {
        assertNull(parse("example.com", "track", "1"))
        assertNull(ExternalLinks.parse(null, listOf("track", "1"), null, "x"))
    }

    @Test
    fun hostMatchingIgnoresCase() {
        assertEquals(Platform.SPOTIFY, parse("Open.Spotify.com", "track", "4cOd")?.platform)
    }

    @Test
    fun appleLookupNamesATrackByItsTitleAndAnAlbumByItsCollection() {
        val lookup = obj(
            """{"results":[{"trackName":"Better Together","collectionName":"In Between Dreams","artistName":"Jack Johnson"}]}""",
        )
        assertEquals("Better Together Jack Johnson", ExternalLinks.appleSearchText(Kind.TRACK, lookup))
        assertEquals("In Between Dreams Jack Johnson", ExternalLinks.appleSearchText(Kind.COLLECTION, lookup))
    }

    @Test
    fun appleLookupWithNothingFoundGivesNoSearch() {
        assertNull(ExternalLinks.appleSearchText(Kind.TRACK, obj("""{"resultCount":0,"results":[]}""")))
        assertNull(ExternalLinks.appleSearchText(Kind.TRACK, obj("""{"results":[{"artistName":"Someone"}]}""")))
    }

    @Test
    fun deezerAddsTheArtistWhenThereIsOne() {
        assertEquals(
            "One More Time Daft Punk",
            ExternalLinks.deezerSearchText(obj("""{"title":"One More Time","artist":{"name":"Daft Punk"}}""")),
        )
        assertEquals("En mode 60", ExternalLinks.deezerSearchText(obj("""{"title":"En mode 60"}""")))
        assertNull(ExternalLinks.deezerSearchText(obj("""{"error":{"type":"DataException"}}""")))
    }

    @Test
    fun spotifyArtistIsTheFirstPartOfThePageDescription() {
        val page = """<head><meta property="og:description" content="Rick Astley · Whenever You Need Somebody · Song · 1987"/></head>"""
        assertEquals("Rick Astley", ExternalLinks.spotifyArtist(page))
    }

    @Test
    fun spotifyArtistIsUnescapedFromHtml() {
        val page = """<meta property="og:description" content="Simon &amp; Garfunkel · Bridge &#x27;n&#39; Troubled · album"/>"""
        assertEquals("Simon & Garfunkel", ExternalLinks.spotifyArtist(page))
    }

    @Test
    fun spotifyArtistIsNullWhenThePageHasNoDescription() {
        assertNull(ExternalLinks.spotifyArtist(null))
        assertNull(ExternalLinks.spotifyArtist("<head><title>x</title></head>"))
        assertNull(ExternalLinks.spotifyArtist("""<meta property="og:description" content=""/>"""))
    }
}
