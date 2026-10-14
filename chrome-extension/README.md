# BitChord for Chrome (side panel)

A Chrome extension port of [BitChord](https://github.com/kushagrasinghx/BitChord): YouTube Music
with an Apple Music-style player, docked in Chrome's side panel so it sits next to your work.
GPL-3.0, like upstream. Not affiliated with YouTube or Google.

## Install (developer mode)
1. Unzip `bitchord-extension.zip` (or run `npm install && npm run build` and use `dist/`).
2. Open `chrome://extensions`, turn on **Developer mode** (top right).
3. Click **Load unpacked** and pick the unzipped folder.
4. Pin the BitChord icon. Click it (or press **Alt+M**) to open the side panel.

## How it works
- **Side panel UI** (React): search, Home (Quick Picks, Recently Played, New Releases,
  Moods & Genres), Liked Songs, Now Playing with synced lyrics and queue.
  Wide panel = two columns; narrow panel = single column + mini player.
  The ↗ button opens the same app in a full tab.
- **Data**: YouTube Music InnerTube API (`search`, `browse`, `next`) called straight from the extension.
- **Audio (v0.2, standalone)**: plays inside the extension (offscreen document), no tab needed.
  Source chain, ported from BitChord's SourceResolver: JioSaavn 320 kbps (when the same recording
  exists) → YouTube direct (native ANDROID_VR client, like BitChord) → pinned music.youtube.com tab
  as last resort. Change the order in Settings (gear icon). The line under the seek bar shows the
  live source and bitrate.
- **Account**: if you're signed in to music.youtube.com in Chrome, Home is personalised
  (SAPISIDHASH signing, as BitChord does). Toggle in Settings.
- **Lyrics**: LRCLIB (line-synced). Click a line to jump there.

## Shortcuts
| Keys | Action |
|---|---|
| Alt+M | Open BitChord |
| Alt+Shift+P | Play / pause |
| Alt+Shift+. / , | Next / previous |
| Space (in panel) | Play / pause |
| / (in panel) | Focus search |
OS media keys also follow BitChord's queue. Change keys at `chrome://extensions/shortcuts`.

## Known limits (v0.1)
- YouTube direct depends on YouTube still serving ANDROID_VR without a PoToken; if it stops,
  tracks fall back to JioSaavn or the helper tab automatically.
- Helper-tab mode only: if Chrome blocks autoplay in the new tab, a red banner appears.
- Not for the Chrome Web Store (personal use).

## Project layout
```
public/manifest.json        MV3 manifest
src/background/index.ts     queue, source chain, engines, commands
src/background/rules.ts     header rewriting (DNR) per client
src/offscreen/index.ts      <audio> playback + media session
src/lib/sources/            youtube.ts (native client), jiosaavn.ts (DES)
src/lib/matcher.ts          TrackMatcher port
src/content/ytm-main.ts     runs inside music.youtube.com, drives #movie_player
src/content/ytm-bridge.ts   relays messages page <-> extension
src/lib/innertube.ts        YouTube Music API client + parsers
src/lib/lyrics.ts           LRCLIB client
src/lib/theme.ts            artwork colour extraction
src/sidepanel/              React UI
```

## v0.3 (14 Oct 2026)
- Official BitChord logomark (toolbar icon + header).
- Faster loading: cached Home/Moods/Search/albums (instant repaint, background refresh), first Home page paints before the rest streams in, 12 s request timeouts with "Try again", skeleton placeholders, broken-artwork retry, lighter thumbnails, visitorData so Home continues past two shelves.
- Home: greeting, mood filter chips, YouTube Music-style Quick Picks columns, Recently Played tiles, every Home shelf as a scrolling row with arrows, Moods & Genres tile row.
- Moods & Genres: colour tiles grouped by section; category pages with a colour hero.
- Search bar: rich suggestions (artists/albums/songs with art), recent searches, keyboard navigation (↑ ↓ Enter Esc), `/` or Ctrl/⌘+K to focus. Results: Top result card, "See all" jumps to the filter chip.
- Settings → Accounts: YouTube Music (default), Google (pick which Chrome Google account), Apple Music (link status). Sign-in happens on Google's/Apple's own pages.
- Engine: one source switch per failure burst; the stats line explains why a track fell back to the tab. DevTools: `self.__bitchordLog` in the service worker.

## v0.4 (14 Oct 2026): BitChord's own look
- Listen Now / Explore / Library / Search on a floating tab bar; BitChord header arcs; sign-in card; hero cards; Explore tiles with tilted cover art.
- Settings rebuilt like the Android app: Account & integrations (YouTube Music default, Google account picker, Apple Music, ListenBrainz scrobbling), Sources (drag to reorder JioSaavn / YouTube Music, tab fallback), Streaming quality, Autoplay radio, Hide volume bar, Theme (System/Light/Dark), Reduce animation, Reduce dynamic blur, Synced lyrics, Blur unfocused lyrics, Lyrics sources, Clear cache, Export data, Stats for nerds, Lyrics debug logs.
- Lyrics sources: LyricsPlus (syllable), PaxSenix (Apple Music word timing), KuGou, LRCLIB, Musixmatch, Genius: asked in parallel, taken in your order, drag to reorder, "Prioritize syllable lyrics". Word-by-word fill in the lyrics view.
- Your logomark as the toolbar icon.

## v0.4.1 (14 Oct 2026)
- Search mismatch fix: search rows have no runtime/album, so new songs could be swapped for an older JioSaavn song with the same name. Now the real length/album/type is fetched first; JioSaavn is used only with an exact title, the lead artist (or 2+ names) in common, the same language/version tags (remix, mix, Eclipsa, Telugu, …) and a length within 4 s (songs) / 10 s (videos). Fan uploads always play from YouTube.
- Ad-free: the YouTube Music tab fallback strips ad slots from player responses, blocks ad servers, and auto-mutes/skips any ad that slips through; ads are never mistaken for a song change.
- Smoother track changes: the previous song stops instantly with a short fade, new songs fade in, and a network blip mid-song resumes at the same second instead of restarting or switching recordings.

## v0.4.2 (14 Oct 2026)
- Automatic sign-in fixed: BitChord picks up the YouTube Music / Google / Apple sessions already in Chrome and reacts live when you sign in, sign out or switch accounts in any tab. No reload, and no stale "Sign in" banner (the account, Home feed and Library are keyed to the session).
- Session cookies are attached explicitly to YouTube Music calls (DNR rule 12), so Chrome's third-party-cookie blocking can't make it look signed out. Anonymous retries stay anonymous (rule 13).
- Authorization now sends SAPISIDHASH + SAPISID1PHASH + SAPISID3PHASH like the web client.
- New host permissions *.youtube.com, *.google.com, *.apple.com (needed to read the dot-domain session cookies). Chrome may ask you to re-enable the extension after updating.

## v0.5.0 (14 Oct 2026) — Transfer music
- Settings → Your data → **Transfer music** (also in Library). Imports from:
  - **Spotify**: Liked Songs and all your playlists, using the open.spotify.com session already signed in in Chrome (a hook stores the web player's token in the page; nothing leaves your browser except calls to Spotify/YouTube Music). A pinned background tab opens and closes on its own when needed.
  - **Apple Music**: library songs and playlists via the music.apple.com session (MusicKit tokens, amp-api).
  - **Paste a Spotify playlist/album link**: reads the public embed with no sign-in; lists longer than ~100 songs are read in full when you're signed in to Spotify.
  - **CSV**: Exportify, TuneMyMusic, Soundiiz exports and similar.
- Matching: YouTube Music Songs search, strict title/lead-artist/length match with a careful fallback. A live test of Spotify's "Hot Hits Hindi" matched 50 of 50 correctly. Songs that can't be found are listed.
- Saves into BitChord: Liked songs merge into Liked songs, and each playlist appears in Library. Optional "Also save to my YouTube Music" creates private playlists and likes songs in your account.
- New permissions: scripting, open.spotify.com, api.spotify.com, amp-api.music.apple.com.

## v0.5.1 (14 Oct 2026) — Transfer music review fixes
- Spotify while signed out: Spotify gives logged-out visitors a guest token, which made BitChord sit on "Connecting…" for over 40 s. It now shows "Sign in to Spotify" in about 3 s.
- Rate limits: song searches that hit YouTube Music's rate limit back off and retry instead of counting as "not found".
- Importing the same playlist again replaces the old copy instead of duplicating it.
- A bad link is rejected immediately, without starting an import.
- Liked-song imports with zero matches no longer report "saved to YouTube Music".

## v0.6.0 (14 Oct 2026): artist pages, crossfade, equalizer, Last.fm, sleep timer
New:
- **Artist pages** like BitChord's ArtistScreen: a header photo and banner, monthly audience and subscribers, Shuffle and Radio, Top songs (with See all), Albums, Singles & EPs, Videos, Featured on, Fans might also like, and About. Tap an artist anywhere: a song row, search, the player, or a card. Back returns to the previous page.
- **Crossfade** (Settings → Playback, 0–12 s): equal-power blend between songs, with two audio elements in the offscreen document.
- **Equalizer**: 10 bands from 31 Hz to 16 kHz, nine presets plus Custom. Open it from the player (sliders icon) or Settings → Playback. Uses Web Audio with a limiter; DNR rule 16 makes direct streams CORS-readable. If a stream refuses, that song plays without the EQ instead of failing.
- **Last.fm scrobbling** (Account & integrations → Scrobbling): desktop auth with your own API key and secret. Sends now-playing, and scrobbles after half the song or 4 minutes.
- **Sleep timer** (moon icon on the player): 15/30/45/60 minutes or the end of this song. Fades out over 6 s and survives the panel closing.

Improved:
- **Faster track changes**: while a song plays, the next one's stream is resolved in the background. Recently played streams are reused for 25 minutes, and expired links are looked up again automatically. The start watchdog dropped from 9 s to 7 s.
- **Smoother lyrics**: the playback clock is interpolated every animation frame, so the word-by-word fill no longer steps every 100 ms or jitters backwards when a late update arrives.
- **Transfer matching**:
  - Strips "From \"Film\"" tails.
  - Catches transliterated titles (same lead artist, most words shared, runtime within 2 s).
  - Gives an album-name bonus.
  - Stops early on strong hits.
  - Remembers matches between imports.

## v0.7.0 (14 Oct 2026): Discover, new music from more than YouTube Music
- **New tab, "New Releases"**: the latest songs from three catalogues, all public and without sign-in:
  - **JioSaavn**: new releases per language (content.getAlbums&languages=…, plus the songs of each new album). These songs play straight from JioSaavn at 320 kbps.
  - **Apple Music**: iTunes charts per regional genre (Telugu, Tamil, Bollywood, Kannada) and per style, with the lookup API filling in runtime, genre, release date and 1000 px official artwork.
  - **Deezer**: worldwide and per-genre charts with 1000 px covers.
- **Languages**: Telugu, Hindi, Tamil, English, Punjabi, Kannada, Malayalam, Marathi, Bengali, Bhojpuri, Gujarati and Korean. You can pick several, and the choice is saved.
- **Genres**: Film, Pop, Hip-hop, Dance, R&B, Rock, Electronic, Devotional, Sufi & ghazal, Indian folk and Indian classical.
- **Matching**: each song is merged across catalogues by its title proper and lead artist. It keeps the best official cover art (Apple, then Deezer, then JioSaavn) and the catalogue's language (JioSaavn, then Apple's regional genre, then the script of the title).
- **New releases list**: covers the last 6 weeks, newest first. Languages alternate within a release day.
- **Playback for songs found outside YouTube Music**:
  - "jsv:" songs use their own JioSaavn stream.
  - "itm:" and "dz:" songs are matched to the same recording on YouTube Music (title, artist, runtime within 8 s), and the normal source chain takes over from there.
- **Fix**: a stream that failed to decode could loop between the cached and fresh copy of the same source. The cache now skips sources already tried, and an expired link is retried only once.

## v0.7.1 (14 Oct 2026): a leaner, better-synced YouTube Music tab
- **Data saver** (Settings → Sources → Tab data saver, on by default):
  - The helper tab asks for the smallest video stream and switches to the audio-only "Song" version when YouTube Music has one.
  - It blocks images and fonts in that tab only, and stops painting video.
  - Your own YouTube Music tabs aren't affected.
- **Instant hand-off**:
  - The extension tells the tab which song comes next.
  - At the end of a song the tab switches by itself a beat before the end, so YouTube Music's autoplay no longer plays a few seconds of the wrong song.
  - The duplicate "ended" report after a switch is ignored, so no song gets skipped.
- **Sync**:
  - The page reports on media events (play, pause, seek, buffering, end) and answers every command at once. Before, it polled twice a second.
  - Readings are timestamped and the background corrects for message lag.
  - The heartbeat dropped to 1/s.
- **Self-healing**:
  - If the tab's relay died after an extension update, the background re-injects it instead of losing every command.
  - A discarded tab is reloaded onto the song, and the tab is marked non-discardable.
  - After a reload, the song resumes where it was instead of from 0:00.
  - Tab id swaps are followed.
- **Steady**: once direct sources fail, the tab is kept for 10 minutes. It no longer pauses, retries direct, and restarts the tab on every song.
