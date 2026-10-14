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
