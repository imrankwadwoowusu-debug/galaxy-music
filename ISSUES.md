# BitChord TV: Lossless Audio Upgrade Analysis & Status

## 1. Executive Summary

BitChord features an intelligent multi-tiered audio engine that dynamically upgrades tracks during playback ("second look") to the highest available fidelity (FLAC 16-bit / 24-bit via LosslessBridge: Qobuz, Tidal, Deezer, JioSaavn).

In this BitChord TV build:
- **Prebuilt Lossless Addon & Sources:** The lossless addon integration is **completely prebuilt directly into the application**. Users do **not** need to manually add, import, install, or configure any external addons, URLs, or source modules for lossless playback.
- **The Issue on TV:** Even with lossless sources prebuilt and available, tracks played from YouTube playlists (e.g., *Pop Gold*) on **BitChord TV** (Android TV / Fire TV) failed to upgrade and remained permanently on lossy YouTube Opus (48 kHz / 129 kbps).

Through in-depth reverse-engineering and source inspection, we identified **4 structural gates** in the upstream code that hard-blocked video streams and TV playback from initiating or completing quality upgrades. 

After removing these restrictions:
- **Verified Working**: *"Love Me Like You Do (Official Video)"* by Ellie Goulding (`mediaId=AJtDXIazrMo`) upgraded seamlessly from YouTube Opus to **Lossless FLAC (16-bit / 44.1 kHz · Qobuz)** with live stream swap and player UI badges updating on the Fire TV Stick (`192.168.1.166:5555`), with zero manual addon setup required.
- **Remaining Limitation**: Tracks with extended cinematic visual intros/outros (such as *"Diamonds"* by Rihanna and *"Love Yourself (PURPOSE : The Movement)"* by Justin Bieber) do not upgrade due to strict duration drift safeguards designed to protect audio sync.

---

## 2. Prebuilt Zero-Config Architecture

- **No Manual Configuration:** The user does not need to enter addon URLs, install plugins, or adjust module endpoints. The app ships with the lossless provider wired directly into `SourceRegistry`.
- **Autonomous Second Look:** Once the application boots and plays a stream, `QualityUpgrade` and `SourceResolver` automatically query the prebuilt source in the background without user intervention.

---

## 3. Identified Upstream Gates & Fixes

### Gate 1: Music Video URI Marker Check (`QualityUpgrade.couldStillUpgrade`)
- **Location:** `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt` (`couldStillUpgrade`)
- **Root Cause:**
  ```kotlin
  if (uri == null || uri.getQueryParameter("v") == null ||
      uri.getQueryParameter("m") == "1"
  ) return false
  ```
  On BitChord TV and video-backed playlists, tracks are queued with `m=1` indicating a video item. This condition caused `couldStillUpgrade` to return `false` immediately, permanently barring video tracks from being evaluated for upgrades.
- **Fix:** Removed `uri.getQueryParameter("m") == "1"`.

---

### Gate 2: Target Video Flag Bypass (`QualityUpgrade.settledForLess`)
- **Location:** `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt` (`settledForLess`)
- **Root Cause:**
  ```kotlin
  if (target.title.isBlank() || target.isVideo ||
      mediaId in refused ||
      !SourceResolver.canSubstituteForYouTube()
  ) {
      inFlight?.cancel()
      return false
  }
  ```
  When the initial YouTube stream started playing, `settledForLess` checked `target.isVideo`. Because `isVideo` was `true`, it cancelled any in-flight lookup and returned `false`, never adding the track to `QualityUpgrade.pending`.
- **Fix:** Removed `target.isVideo` check so video items can register as pending for a background second look.

---

### Gate 3: Read-Ahead / Pinned YouTube Track Bypass (`PlaybackService.kt`)
- **Location:** `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- **Root Cause:**
  ```kotlin
  if (StreamChoice.isSubstitute(videoId) &&
      serving.format.isLossless != true &&
      QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
  ) {
      val pending = QualityUpgrade.settledForLess(...)
  ```
  When a YouTube track was warmed or pre-resolved by read-ahead cache, `StreamChoice.isSubstitute(videoId)` evaluated to `false` (since YouTube is the native fallback, not a substitute module). This caused pre-resolved tracks to bypass `settledForLess`, meaning subsequent tracks in a playlist never entered the upgrade queue.
- **Fix:** Removed `StreamChoice.isSubstitute(videoId)` requirement so any non-lossless serving stream can schedule an upgrade if requested.

---

### Gate 4: SourceResolver Background Upgrade Gate (`SourceResolver.upgradeFor`)
- **Location:** `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt` (`upgradeFor`)
- **Root Cause:**
  ```kotlin
  suspend fun upgradeFor(
      target: TrackMatcher.Target,
      playing: StreamFormat? = null,
      servedBy: String? = null,
  ): SourceStream? {
      if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
  ```
  Even if a video track survived into `QualityUpgrade.lookAgain`, `SourceResolver.upgradeFor` unconditionally returned `null` for any `target.isVideo == true`, completely preventing the prebuilt lossless provider from querying lossless streams.
- **Fix:** Removed `|| target.isVideo` check in `upgradeFor`.

---

## 4. Analysis: Why "Diamonds" & "Love Yourself" Do Not Upgrade Yet

While *"Love Me Like You Do"* upgraded cleanly to FLAC, tracks like *"Diamonds"* and *"Love Yourself"* in the *Pop Gold* playlist remain on YouTube Opus.

### A. The Duration Discrepancy

| Track | YouTube Video Runtime | Studio FLAC Runtime | Difference |
| :--- | :--- | :--- | :--- |
| **Love Me Like You Do** (Ellie Goulding) | 249s (4:09) | 252s (4:12) | **~3 seconds** (Within tolerance) |
| **Diamonds** (Rihanna) | 283s (4:43) | 225.1s (3:45) | **58 seconds** (Severe drift) |
| **Love Yourself** (Justin Bieber) | 272s (4:32) | 233.7s (3:53) | **38.3 seconds** (Severe drift) |

### B. What Causes the Drift?
- **Rihanna – Diamonds:** The official YouTube music video contains a ~50-second cinematic, atmospheric spoken intro with slow-motion visuals (horses, smoke, dialogue) before the first music note plays.
- **Justin Bieber – Love Yourself (PURPOSE : The Movement):** The video version is a narrative dance short film featuring an extended dramatic dialogue preamble.
- Conversely, studio FLAC masters on Qobuz/Tidal contain *only* the musical track without any visual preamble.

### C. Upstream Safety Guards Blocking the Upgrade
1. **`SourceResolver.sameRecordingAs` & `UPGRADE_DRIFT_SEC = 2`:**
   During the audition/swap phase, `sameRecordingAs` validates that candidate duration matches playing duration within **2 seconds**. A 58s or 38.3s drift fails this check immediately.
2. **`SourceResolver.upgradeFor` strict length filter:**
   `matches = matches.filter { TrackMatcher.withinSeconds(it, target, UPGRADE_DRIFT_SEC) }` strictly discards any candidate differing by more than 2 seconds.
3. **`TrackMatcher.DURATION_LIMIT_SEC = 30` & `isSevereMismatch`:**
   Even in general ranking, any drift exceeding 30 seconds is treated as a severe mismatch / candidate rejection.

### D. Why Blindly Forcing a Swap Would Cause Playback Breakage
If the engine were to force-swap a 225-second studio FLAC into an actively playing 283-second video:
1. **Audio/Video Desync:** At 0:05, the screen shows the silent visual dialogue intro, but the listener would hear the full studio verse and beat playing immediately.
2. **Premature Audio Truncation:** The FLAC audio stream would conclude at 3:45, leaving the video to play out the remaining 58 seconds in complete silence.

### E. Recommended Path Forward for Upstream
To support seamless lossless upgrades for music videos with extended intros:
1. **InnerTube Audio Metadata Lookup:** When parsing video items, retrieve the corresponding YouTube Music canonical song duration (`albumAudioDuration`) and store it as `target.audioDurationSec`.
2. **Intro Offset Lead-in Alignment:** Support an optional audio-delay/offset parameter when substituting studio audio onto a video container, or offer an explicit "Play Studio Master Audio" toggle in the TV player UI.
