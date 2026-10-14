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

## v0.7.2 (14 Oct 2026)
- Removed the "New" tab: new releases now sit at the top of Explore, with genre chips and no language chips.
- The languages for new releases moved to Settings → New releases languages.

## v0.8.0 (14 Oct 2026)
- Up Next queue, modelled on Apple Music:
  - The song now playing, then Playing Next with its song count and total time.
  - History can be shown above them.
  - Drag the handle to reorder songs with a mouse, touch or pen; the list scrolls itself near the edges. With a handle focused, the arrow keys move a song.
  - The ⋯ menu on each song has Play next, Move to end and Remove from queue. Play next also works on songs in History.
  - Edit mode adds remove buttons to every song and a Clear button for the whole list.
  - Edits show at once. The background moves songs without interrupting the one playing, and the YouTube Music tab is told about the new next song.
- Removed the equalizer from the player and from Settings. Audio now always plays flat.

## v0.8.1 (14 Oct 2026): full audit — security, bugs, accessibility, speed
Security
- The service worker accepts player commands only from BitChord's own pages, and checks each one (indexes in range, well-formed tracks, sane seek/volume/sleep values). Audio events are accepted only from the offscreen page; the offscreen page takes orders only from the service worker; the panel accepts state only from the service worker.
- With the equalizer gone, songs play as plain audio elements again, so the rule that added `Access-Control-Allow-Origin: *` to googlevideo/saavncdn responses (DNR 16) is removed.
- Musixmatch: an all-zero token is treated as a refusal, and its made-up "anti-scraping" lyrics (evenly spaced nonsense lines) are never shown.
Bugs fixed
- Double-clicking a song played it 3 times and appended its radio 3 times. A slow radio for an earlier song no longer lands in a new queue; the radio is fetched while the song starts.
- Lyrics from the previous song could replace the current song's; a failed lookup no longer spins forever.
- Account page leaked a cookie listener on every visit (repeated account checks).
- Removing two queue songs quickly removed the wrong one.
- A new search briefly ran with the old filter chip; clearing the search box left a spinner.
- Listen Now could show another chip's extra shelves; Retry made later visits skip the cache.
- Seek bar froze on touch/pen; volume and crossfade sliders lag-free; two quick setting changes no longer drop one.
- Escape now closes the Lyrics sources dialog first, and cancels a running transfer when it leaves the page.
- Clear cache now also clears the in-memory copy; cache entries are versioned so an older build's data can't crash a newer one; a crash screen offers "Clear cache and reload" instead of a blank panel.
- Media keys / Chrome media controls "previous" restarts the song after 3 s, like the button; the keyboard shortcut starts a stopped song.
Accessibility
- Settings rows, account switcher, lyrics sources, transfer pickers, queue rows, top result and artist "About" work with Enter/Space; Space no longer toggles music while a control is focused. Sources and lyrics sources reorder with Alt+↑/↓. Cards no longer nest a button inside a button.
Speed
- Rows and cards are memoised and the app's callbacks are stable, so player ticks no longer re-render every list. Long playlists render 100 songs at a time. Lyrics tick at frame rate only for the line being sung. Cache writes no longer read all of storage. The user's own YouTube Music tabs no longer message BitChord every second.

## v0.9.1 (14 Oct 2026): audit of v0.9

- Play after a long pause works again: Chrome closes an idle audio page after ~30 s of silence, so Play now reloads the song where it was. The load token and position survive the service worker sleeping, so events from a surviving player are no longer dropped.
- The offscreen page is created once even when several commands arrive together (uses `runtime.getContexts`, Chrome 116+).
- Rapid Next presses can no longer let an older song's load win (token re-checked; the player ignores older loads).
- Sleep timer: a song ending during the fade-out no longer starts the next one; the YouTube player's fade is cancelled by any new command.
- No crossfade on the YouTube player (it used to cut up to 12 s off the end).
- YouTube player: pre-roll ads are detected by a much shorter length than the song, so the bar, lyrics and "ended" follow the song; replays and seeks report "ended" again; leftover reports from the previous video are ignored.
- Watchdog respects a pause pressed before the song starts, and also guards the mid-song reload.
- Lyrics: titles match by whole words ("Hero" no longer matches "Superhero"); the lookup waits for the song's length when the catalogue lacks it; failed lookups are not cached; LRCLIB keeps plain lyrics when its timings don't fit.
- Security: only the service worker can drive the player, only the player page can send audio events, and every message channel is answered. The `tabs` permission is dropped. v0.8's helper tab is found by its sessionStorage mark and closed on upgrade.

## v0.9.0 (14 Oct 2026): correct lyrics, and no more YouTube Music tab
Lyrics
- Root cause of identical/random lyrics: Musixmatch answers unauthorised clients with the same decoy text ("Wob gopini den…") for every song, and KuGou, LRCLIB and LyricsPlus answers were taken without checking which song they were for.
- Every provider's answer is now verified against the playing track: title proper (bracketed film/language tags ignored), artist overlap, language version (a Tamil version never shows Telugu lyrics), instrumental, and length within 15 s. Synced lyrics that run past the song's end are dropped. KuGou/LRCLIB pick the first *matching* candidate instead of the first one.
- The panel passes the catalogue length up front, searches use the title proper, "server busy" answers are retried once, and KuGou credit lines (Drums：…, 作词：…) are stripped.
- Unknown songs show "No lyrics found" instead of someone else's lyrics.
Playback
- The pinned music.youtube.com helper tab is gone (and closed on upgrade). When no direct stream (JioSaavn, YouTube) works, the song plays in a hidden YouTube player inside BitChord's own offscreen page, driven over YouTube's postMessage protocol: play/pause, seek, volume, sleep fade, track changes, media keys and progress all go through the usual controls. If a video refuses embedding, another upload of the same recording is tried.
- DNR rule 17 gives the embed a Referer (YouTube refuses an embed without one); the YouTube Music content scripts and the helper-tab ad rules are removed.
