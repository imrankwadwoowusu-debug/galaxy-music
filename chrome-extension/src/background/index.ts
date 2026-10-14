// Service worker: the hub, modelled on BitChord's PlaybackService + SourceResolver.
//
// For each track it resolves a stream through a source chain (JioSaavn 320
// kbps match / YouTube direct via native ANDROID_VR client), plays it in the
// offscreen <audio> element, and falls back to driving a pinned
// music.youtube.com tab when every direct source fails.
import { DEFAULT_SETTINGS, type PageCommand, type PageState, type PlayerState, type Settings, type Track, type UiCommand } from '../lib/types';
import { lastfmNowPlaying, lastfmScrobble } from '../lib/lastfm';
import { pushRecent } from '../lib/history';
import { directOrder, getSettings, QUALITY_KBPS } from '../lib/settings';
import { resolveYouTube, type ResolvedStream } from '../lib/sources/youtube';
import { resolveSaavn } from '../lib/sources/jiosaavn';
import { getQueue, getTrackDetails } from '../lib/innertube';
import { toSeconds } from '../lib/matcher';
import { installStaticRules, setNativeUserAgent, syncYouTubeCookies } from './rules';

const HELPER_URL = 'https://music.youtube.com/';
const START_TIMEOUT_MS = 7000;
const PREFETCH_TTL_MS = 25 * 60 * 1000;
let lastStream = '';
let resumeTried = '';

const initial: PlayerState = {
  videoId: null, track: null, playing: false, position: 0, duration: 0, volume: 100,
  helperReady: false, needsActivation: false, queue: [], index: -1, shuffle: false, repeat: 'off',
  engine: null, stream: null, resolving: false, error: null, sleepAt: null, sleepEndOfTrack: false,
};

let state: PlayerState = { ...initial };
let helperTabId: number | null = null;
let expectedVideoId: string | null = null;
let lastLoadAt = 0;
let loadToken = 0;
let startTimer: ReturnType<typeof setTimeout> | null = null;
let triedSources = new Set<string>();
let failReasons: string[] = [];
let switchingToken = -1; // a source switch is already running for this load
const restored: Promise<void> = restore();

// Settings, kept in memory: read on every audio tick (crossfade, scrobbling).
let cfg: Settings = { ...DEFAULT_SETTINGS };
const cfgReady = getSettings().then((x) => { cfg = x; });
chrome.storage.onChanged.addListener((c) => {
  if (!c.settings) return;
  getSettings().then((x) => {
    const eqChanged = x.eqOn !== cfg.eqOn || x.eqGains.join() !== cfg.eqGains.join();
    cfg = x;
    if (eqChanged) sendEq();
  });
});
async function sendEq() {
  if (!(await chrome.offscreen.hasDocument?.())) return;
  chrome.runtime.sendMessage({ target: 'offscreen', cmd: 'eq', on: cfg.eqOn, gains: cfg.eqGains }).catch(() => {});
}

// Ring buffer of recent engine events, readable from DevTools: self.__bitchordLog
const LOG: string[] = [];
(self as unknown as { __bitchordLog: string[] }).__bitchordLog = LOG;
function log(...a: unknown[]) {
  LOG.push(`${new Date().toISOString().slice(11, 19)} ${a.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).join(' ')}`);
  if (LOG.length > 80) LOG.shift();
}

// ---------- persistence ----------
async function restore() {
  const [local, session] = await Promise.all([
    chrome.storage.local.get(['queue', 'index', 'shuffle', 'repeat', 'volume']),
    chrome.storage.session.get(['helperTabId', 'expectedVideoId', 'engine', 'sleepAt', 'sleepEndOfTrack']),
  ]);
  state.queue = (local.queue as Track[]) ?? [];
  state.index = (local.index as number) ?? -1;
  state.shuffle = !!local.shuffle;
  state.repeat = (local.repeat as PlayerState['repeat']) ?? 'off';
  state.volume = (local.volume as number) ?? 100;
  state.track = state.queue[state.index] ?? null;
  state.videoId = state.track?.videoId ?? null;
  state.engine = (session.engine as PlayerState['engine']) ?? null;
  helperTabId = (session.helperTabId as number) ?? null;
  state.sleepAt = (session.sleepAt as number) ?? null;
  state.sleepEndOfTrack = !!session.sleepEndOfTrack;
  if (state.sleepAt) sleepTimer = setTimeout(checkSleep, Math.max(0, state.sleepAt - Date.now()) + 50);
  expectedVideoId = (session.expectedVideoId as string) ?? null;
  if (helperTabId != null) {
    try { await chrome.tabs.get(helperTabId); state.helperReady = true; } catch { helperTabId = null; }
  }
}

function persist() {
  chrome.storage.local.set({ queue: state.queue, index: state.index, shuffle: state.shuffle, repeat: state.repeat, volume: state.volume });
  chrome.storage.session.set({ helperTabId, expectedVideoId, engine: state.engine });
}

let bTimer: ReturnType<typeof setTimeout> | null = null;
function broadcast() {
  if (bTimer) return;
  bTimer = setTimeout(() => {
    bTimer = null;
    chrome.runtime.sendMessage({ type: 'state', state }).catch(() => {});
  }, 50);
}

// ---------- offscreen audio ----------
async function ensureOffscreen() {
  const has = await chrome.offscreen.hasDocument?.();
  if (has) return;
  try {
    await chrome.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: [chrome.offscreen.Reason.AUDIO_PLAYBACK],
      justification: 'Play music while the BitChord side panel is closed',
    });
    await cfgReady;
    await chrome.runtime.sendMessage({ target: 'offscreen', cmd: 'eq', on: cfg.eqOn, gains: cfg.eqGains }).catch(() => {});
  } catch (e) {
    if (!String(e).includes('single offscreen')) throw e;
  }
}
async function audio(cmd: Record<string, unknown>) {
  await ensureOffscreen();
  return chrome.runtime.sendMessage({ target: 'offscreen', ...cmd }).catch(() => null);
}

// ---------- helper tab (fallback engine) ----------
async function sendPage(command: PageCommand) {
  if (helperTabId == null) return;
  try { await chrome.tabs.sendMessage(helperTabId, { type: 'page-cmd', command }); } catch { /* loading */ }
}
async function ensureHelper(videoId?: string): Promise<boolean> {
  if (helperTabId != null) {
    try { await chrome.tabs.get(helperTabId); return true; } catch { helperTabId = null; }
  }
  const url = videoId ? `${HELPER_URL}watch?v=${encodeURIComponent(videoId)}` : HELPER_URL;
  const tab = await chrome.tabs.create({ url, pinned: true, active: false });
  helperTabId = tab.id ?? null;
  state.helperReady = false;
  persist();
  return false;
}
async function playInTab(t: Track) {
  state.engine = 'tab';
  state.stream = 'YouTube Music tab';
  expectedVideoId = t.videoId;
  lastLoadAt = Date.now();
  const existed = await ensureHelper(t.videoId);
  if (existed) await sendPage({ cmd: 'load', videoId: t.videoId });
}

// ---------- source chain ----------
const describe = (s: ResolvedStream) => {
  const codec = /opus/.test(s.mimeType) ? 'Opus' : /mp4a|mp4/.test(s.mimeType) ? 'AAC' : s.mimeType.split(';')[0];
  return `${s.source === 'jiosaavn' ? 'JioSaavn' : `YouTube (${s.client})`} · ${s.kbps} kbps ${codec}`;
};

const details = new Map<string, Awaited<ReturnType<typeof getTrackDetails>>>();
async function enrich(t: Track) {
  let info = details.get(t.videoId);
  if (!info) {
    info = await getTrackDetails(t.videoId).catch(() => ({}));
    details.set(t.videoId, info);
    if (details.size > 300) details.delete(details.keys().next().value!);
  }
  if (!toSeconds(t.duration) && info.duration) t.duration = info.duration;
  if (!t.album && info.album) t.album = info.album;
  if (state.track?.videoId === t.videoId) { state.track = { ...state.track, duration: t.duration, album: t.album }; broadcast(); }
  return info;
}

async function resolveNext(t: Track, order: string[], triedSources: Set<string>, failReasons: string[]): Promise<ResolvedStream | null> {
  for (const src of order) {
    if (triedSources.has(src)) continue;
    triedSources.add(src);
    try {
      const q = (await getSettings()).quality;
      let tol = 12;
      if (src === 'saavn') {
        // Search rows carry no runtime or album; without them a new song can
        // be "matched" to an older one with the same name. Fetch them first.
        const info = await enrich(t);
        if (info.videoType && /UGC|PODCAST/.test(info.videoType)) { failReasons.push('JioSaavn: skipped (fan upload)'); continue; }
        if (!toSeconds(t.duration)) { failReasons.push('JioSaavn: skipped (unknown length)'); continue; }
        // A YouTube "song" is the label's audio: the catalogue copy must run
        // the same length. Music videos get a little slack for intros/outros.
        tol = info.videoType === 'MUSIC_VIDEO_TYPE_ATV' ? 4 : 10;
      }
      const r = src === 'saavn' ? await resolveSaavn(t, undefined, q, tol) : await resolveYouTube(t.videoId, setNativeUserAgent, QUALITY_KBPS[q]);
      log('source', src, r ? `ok ${r.kbps}kbps ${r.url.slice(0, 60)}` : 'no match');
      if (r) { resolved.set(t.videoId, { r, src, at: Date.now() }); if (resolved.size > 40) resolved.delete(resolved.keys().next().value!); return r; }
      failReasons.push(`${src === 'saavn' ? 'JioSaavn' : 'YouTube'}: no match`);
    } catch (e) {
      console.warn('[BitChord] source failed', src, e);
      log('source', src, 'error', String((e as Error)?.message ?? e));
      failReasons.push(`${src === 'saavn' ? 'JioSaavn' : 'YouTube'}: ${String((e as Error)?.message ?? e).slice(0, 80)}`);
    }
  }
  return null;
}

// Streams already resolved (the next song, prefetched while this one plays,
// and songs played recently): a track change starts at once instead of
// waiting on JioSaavn/YouTube lookups.
const resolved = new Map<string, { r: ResolvedStream; src: string; at: number }>();
function takeResolved(t: Track, order: string[]): { r: ResolvedStream; src: string } | null {
  const hit = resolved.get(t.videoId);
  if (!hit || Date.now() - hit.at > PREFETCH_TTL_MS || !order.includes(hit.src)) return null;
  // only use a lower-priority source if every source before it already failed
  if (order.slice(0, order.indexOf(hit.src)).some((x) => !triedSources.has(x))) return null;
  return hit;
}
let prefetchedFor = '';
async function prefetchNext() {
  if (state.repeat === 'one') return;
  const next = state.queue[state.index + 1];
  if (!next || prefetchedFor === next.videoId) return;
  prefetchedFor = next.videoId;
  if (resolved.has(next.videoId) && Date.now() - resolved.get(next.videoId)!.at < PREFETCH_TTL_MS) return;
  const order = await sourceOrder();
  log('prefetch', next.title);
  await resolveNext(next, order, new Set(), []).catch(() => null);
}

async function sourceOrder(): Promise<string[]> {
  return directOrder(await getSettings());
}

let cachedSrc = ''; // the playing stream came from the resolved cache (may have expired)
let pendingFade = 0; // seconds of crossfade for the load in flight
async function startDirect(t: Track, token: number, startAt = 0): Promise<void> {
  if (switchingToken === token) return; // errors arrive in bursts: switch once
  switchingToken = token;
  let r: ResolvedStream | null;
  try {
    const order = await sourceOrder();
    const hit = takeResolved(t, order);
    if (hit) { triedSources.add(hit.src); r = hit.r; cachedSrc = hit.src; log('source', hit.src, 'cached'); }
    else { cachedSrc = ''; r = await resolveNext(t, order, triedSources, failReasons); }
    if (!r) resolved.delete(t.videoId);
  } finally {
    if (switchingToken === token) switchingToken = -1;
  }
  if (token !== loadToken) return; // user moved on
  const fade = pendingFade; pendingFade = 0;
  if (!r) {
    // Every direct source failed: fall back to the YouTube Music tab.
    await audio({ cmd: 'stop' });
    state.resolving = false;
    if (!(await getSettings()).tabFallback) {
      state.engine = null; state.playing = false;
      state.error = `Couldn't play this song (${failReasons.join('; ') || 'no source'})`;
      persist(); broadcast();
      return;
    }
    await playInTab(t);
    if (failReasons.length) state.stream = `YouTube Music tab (${failReasons.join('; ')})`;
    persist(); broadcast();
    return;
  }
  if (r.userAgent) await setNativeUserAgent(r.userAgent);
  if (helperTabId != null) sendPage({ cmd: 'pause' });
  state.engine = 'direct';
  lastStream = r.url;
  state.stream = describe(r);
  state.resolving = false;
  state.duration = r.durationSec ?? state.duration;
  await audio({ cmd: 'load', url: r.url, token, volume: state.volume, startAt, fade, meta: { title: t.title, artist: t.artists, album: t.album, artwork: t.thumbnail } });
  armStartTimer(t, token);
  persist(); broadcast();
}

function armStartTimer(t: Track, token: number) {
  if (startTimer) clearTimeout(startTimer);
  startTimer = setTimeout(() => {
    if (token === loadToken && !state.playing && state.position < 0.5) {
      console.warn('[BitChord] stream never started, trying next source');
      log('watchdog: stream never started');
      resolved.delete(t.videoId);
      startDirect(t, token);
    }
  }, START_TIMEOUT_MS);
}

// ---------- queue ----------
async function playCurrent(fade = 0) {
  const t = state.queue[state.index];
  if (!t) return;
  log('play', t.title, fade ? `crossfade ${fade}s` : '');
  pendingFade = fade;
  const token = ++loadToken;
  triedSources = new Set();
  failReasons = [];
  Object.assign(state, { track: t, videoId: t.videoId, position: 0, duration: 0, needsActivation: false, resolving: true, error: null, stream: null });
  pushRecent(t);
  persist(); broadcast();
  // Silence the previous song right away instead of letting it run on while
  // the next one resolves (heard as the music "fluctuating" between songs).
  if (state.engine === 'direct' && !fade) audio({ cmd: 'stop' });
  resumeTried = '';
  const order = await sourceOrder();
  if (!order.length) { state.resolving = false; await audio({ cmd: 'stop' }); await playInTab(t); persist(); broadcast(); return; }
  if (state.engine === 'tab' && helperTabId != null) sendPage({ cmd: 'pause' });
  await startDirect(t, token);
}

async function advance(dir: 1 | -1, auto = false, fade = 0) {
  if (!state.queue.length) return;
  if (auto && state.sleepEndOfTrack) {
    // Sleep timer "end of this song": stop here, ready to resume on the next one.
    state.sleepEndOfTrack = false; state.playing = false;
    if (state.index + 1 < state.queue.length) state.index++;
    state.track = state.queue[state.index]; state.videoId = state.track?.videoId ?? null; state.position = 0;
    if (state.engine === 'tab') sendPage({ cmd: 'pause' }); else audio({ cmd: 'stop' });
    state.engine = null; persist(); broadcast(); return;
  }
  if (auto && state.repeat === 'one') return playCurrent();
  let i = state.index + dir;
  if (i >= state.queue.length) {
    // BitChord-style autoplay: when the queue runs out, keep going with radio.
    if (auto && state.repeat === 'off' && (await getSettings()).autoRadio && state.track) {
      try {
        const have = new Set(state.queue.map((x) => x.videoId));
        const more = (await getQueue({ videoId: state.track.videoId })).filter((x) => !have.has(x.videoId));
        if (more.length) { state.queue.push(...more.slice(0, 25)); state.index = i; return playCurrent(); }
      } catch { /* fall through */ }
    }
    if (state.repeat === 'all' || !auto) i = 0; else { state.playing = false; broadcast(); return; }
  }
  if (i < 0) i = state.repeat === 'all' ? state.queue.length - 1 : 0;
  state.index = i;
  await playCurrent(fade);
}

function shuffleRest() {
  const head = state.queue.slice(0, state.index + 1);
  const rest = state.queue.slice(state.index + 1);
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [rest[i], rest[j]] = [rest[j], rest[i]];
  }
  state.queue = [...head, ...rest];
}

// Transport commands go to whichever engine is live
const live = {
  toggle: () => (state.engine === 'tab' ? sendPage({ cmd: 'toggle' }) : audio({ cmd: 'toggle' })),
  seek: (s: number) => (state.engine === 'tab' ? sendPage({ cmd: 'seek', seconds: s }) : audio({ cmd: 'seek', seconds: s })),
  volume: (v: number) => (state.engine === 'tab' ? sendPage({ cmd: 'volume', value: v }) : audio({ cmd: 'volume', value: v })),
};

// ---------- ListenBrainz scrobbling (Settings → Account & integrations) ----------
let scrobbled = '';
let nowPlayingSent = '';
async function listenbrainz(kind: 'playing_now' | 'single') {
  const t = state.track;
  if (!t) return;
  const { listenbrainzToken: token } = await getSettings();
  if (!token) return;
  const payload: Record<string, unknown> = {
    track_metadata: {
      artist_name: t.artists, track_name: t.title, release_name: t.album,
      additional_info: { media_player: 'BitChord for Chrome', submission_client: 'BitChord for Chrome', origin_url: `https://music.youtube.com/watch?v=${t.videoId}`, duration_ms: state.duration ? Math.round(state.duration * 1000) : undefined },
    },
  };
  if (kind === 'single') payload.listened_at = Math.floor(Date.now() / 1000) - Math.round(state.position);
  fetch('https://api.listenbrainz.org/1/submit-listens', {
    method: 'POST', headers: { Authorization: `Token ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ listen_type: kind, payload: [payload] }),
  }).catch(() => {});
}
function lastfm(kind: 'now' | 'scrobble') {
  const t = state.track;
  if (!t || !cfg.lastfmSession || !cfg.lastfmKey || !cfg.lastfmSecret) return;
  const info = { artist: t.artists, track: t.title, album: t.album, duration: state.duration || undefined, timestamp: Math.floor(Date.now() / 1000) - Math.round(state.position) };
  (kind === 'now' ? lastfmNowPlaying : lastfmScrobble)(cfg.lastfmKey, cfg.lastfmSecret, cfg.lastfmSession, info)
    .catch((e) => log('last.fm', kind, String(e?.message ?? e)));
}
function maybeScrobble() {
  const id = state.track?.videoId;
  if (!id || !state.playing) return;
  if (nowPlayingSent !== id) { nowPlayingSent = id; listenbrainz('playing_now'); lastfm('now'); }
  // Last.fm rules: tracks over 30 s, after half the song or 4 minutes.
  const need = Math.min(240, (state.duration || 60) / 2);
  if (scrobbled !== id && state.position >= need && (state.duration || 61) > 30) { scrobbled = id; listenbrainz('single'); lastfm('scrobble'); }
}

// ---------- sleep timer ----------
let sleepTimer: ReturnType<typeof setTimeout> | null = null;
function setSleep(minutes: number) {
  if (sleepTimer) { clearTimeout(sleepTimer); sleepTimer = null; }
  state.sleepAt = minutes > 0 ? Date.now() + minutes * 60_000 : null;
  state.sleepEndOfTrack = minutes === -1;
  if (state.sleepAt) sleepTimer = setTimeout(checkSleep, minutes * 60_000 + 50);
  chrome.storage.session.set({ sleepAt: state.sleepAt, sleepEndOfTrack: state.sleepEndOfTrack });
}
function checkSleep() {
  if (!state.sleepAt || Date.now() < state.sleepAt) return;
  log('sleep timer: pausing');
  setSleep(0);
  // Fade out over a few seconds instead of cutting off mid-word.
  if (state.engine === 'tab') sendPage({ cmd: 'pause' }); else audio({ cmd: 'fade-pause', ms: 6000 });
  broadcast();
}

// ---------- crossfade + prefetch, driven by the audio clock ----------
let xfadeToken = -1;
function onTick() {
  checkSleep();
  if (state.engine !== 'direct' || !state.playing) return;
  if (state.position > 8) prefetchNext();
  const x = cfg.crossfade;
  const left = state.duration - state.position;
  if (x > 0 && state.duration > 30 && xfadeToken !== loadToken && state.repeat !== 'one' && !state.sleepEndOfTrack
    && state.index + 1 < state.queue.length && left > 0 && left <= x + 0.3) {
    xfadeToken = loadToken;
    advance(1, true, Math.min(x, left));
  }
}

// ---------- events from offscreen audio ----------
function onAudioEvent(m: any) {
  if (m.kind !== 'time') log('audio', m.kind, m.token, loadToken, m.message ?? '');
  if (m.token !== loadToken || state.engine !== 'direct') return;
  if (m.kind === 'media-next') return void advance(1);
  if (m.kind === 'media-prev') return void advance(-1);
  state.position = m.position;
  if (m.duration) state.duration = m.duration;
  state.playing = m.playing;
  if (m.kind === 'playing' && startTimer) { clearTimeout(startTimer); startTimer = null; }
  maybeScrobble();
  onTick();
  if (m.kind === 'ended') return void advance(1, true);
  if (m.kind === 'error') {
    console.warn('[BitChord] audio error', m.message);
    if (/user didn't interact|play\(\) failed because/i.test(m.message)) { state.error = 'Press play to start'; broadcast(); return; }
    const t = state.track;
    const at = state.position;
    // A network blip mid-song: reload the same stream where it stopped, once,
    // before giving up on the source (switching sources swaps the recording).
    if (t && at > 2 && lastStream && resumeTried !== t.videoId) {
      resumeTried = t.videoId;
      log('resume same stream at', at.toFixed(1));
      audio({ cmd: 'load', url: lastStream, token: loadToken, volume: state.volume, startAt: at, meta: { title: t.title, artist: t.artists, album: t.album, artwork: t.thumbnail } });
      return;
    }
    if (t && switchingToken !== loadToken) {
      if (startTimer) { clearTimeout(startTimer); startTimer = null; }
      if (cachedSrc) { triedSources.delete(cachedSrc); resolved.delete(t.videoId); cachedSrc = ''; } // stale link: look it up again
      else failReasons.push(`${state.stream?.split(' ·')[0] ?? 'stream'}: ${String(m.message).slice(0, 60)}`);
      state.resolving = true; broadcast();
      audio({ cmd: 'stop' });
      startDirect(t, loadToken, at > 2 ? at : 0);
    }
    return;
  }
  broadcast();
}

// ---------- events from helper tab ----------
function onPageState(s: PageState | null) {
  if (!s || state.engine !== 'tab') return;
  state.helperReady = true;
  if (s.ad) { state.stream = 'YouTube Music tab · skipping an ad'; broadcast(); return; } // never mistake an ad for a song change
  state.playing = s.state === 1 || s.state === 3;
  state.position = s.position;
  state.duration = s.duration || state.duration;
  const sinceLoad = Date.now() - lastLoadAt;
  if (s.state === 0 && s.videoId && s.videoId === expectedVideoId) { expectedVideoId = null; advance(1, true); return; }
  if (expectedVideoId && s.videoId && s.videoId !== expectedVideoId && sinceLoad > 4000 && state.queue.length) { advance(1, true); return; }
  maybeScrobble();
  checkSleep();
  state.needsActivation = sinceLoad > 5000 && (s.state === -1 || s.state === 5) && s.position < 0.5 && s.videoId === expectedVideoId;
  broadcast();
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.target === 'offscreen' || msg?.type === 'state') return; // not for us
  (async () => {
    await restored;
    if (msg?.type === 'audio-event') return onAudioEvent(msg);
    if (msg?.type === 'helper-hello') {
      if (sender.tab?.id === helperTabId) {
        state.helperReady = true;
        setTimeout(() => { if (expectedVideoId && state.engine === 'tab') sendPage({ cmd: 'load', videoId: expectedVideoId }); }, 2500);
      }
      return;
    }
    if (msg?.type === 'page-event') {
      if (sender.tab?.id !== helperTabId) return;
      if (msg.kind === 'state' || msg.kind === 'ready') onPageState(msg.payload);
      if (msg.kind === 'media-key' && state.engine === 'tab') advance(msg.payload === 'next' ? 1 : -1);
      return;
    }
    const cmd = msg as UiCommand;
    switch (cmd?.type) {
      case 'get-state': sendResponse(state); return;
      case 'play-queue':
        state.queue = cmd.tracks; state.index = cmd.index;
        if (state.shuffle) shuffleRest();
        await playCurrent(); break;
      case 'play-index': state.index = cmd.index; await playCurrent(); break;
      case 'add-next': state.queue.splice(state.index + 1, 0, cmd.track); if (state.index < 0) { state.index = 0; await playCurrent(); } break;
      case 'add-queue': state.queue.push(cmd.track); if (state.index < 0) { state.index = 0; await playCurrent(); } break;
      case 'append': {
        const have = new Set(state.queue.map((x) => x.videoId));
        state.queue.push(...cmd.tracks.filter((x) => !have.has(x.videoId)));
        break;
      }
      case 'remove-index':
        state.queue.splice(cmd.index, 1);
        if (cmd.index < state.index) state.index--;
        break;
      case 'toggle':
        if (!state.engine && state.track) await playCurrent();
        else { state.error = null; await live.toggle(); }
        break;
      case 'next': await advance(1); break;
      case 'prev':
        if (state.position > 3) await live.seek(0); else await advance(-1);
        break;
      case 'seek': await live.seek(cmd.seconds); state.position = cmd.seconds; break;
      case 'volume': await live.volume(cmd.value); state.volume = cmd.value; break;
      case 'shuffle': state.shuffle = !state.shuffle; if (state.shuffle) shuffleRest(); break;
      case 'repeat': state.repeat = state.repeat === 'off' ? 'all' : state.repeat === 'all' ? 'one' : 'off'; break;
      case 'sleep': setSleep(cmd.minutes); break;
      case 'activate-helper':
        if (helperTabId != null) {
          const tab = await chrome.tabs.update(helperTabId, { active: true });
          if (tab?.windowId) chrome.windows.update(tab.windowId, { focused: true });
        }
        break;
      case 'open-tab': chrome.tabs.create({ url: chrome.runtime.getURL('sidepanel.html?mode=tab') }); break;
    }
    persist();
    broadcast();
    sendResponse(state);
  })();
  return true;
});

chrome.tabs.onRemoved.addListener((tabId) => {
  if (tabId === helperTabId) {
    helperTabId = null; state.helperReady = false;
    if (state.engine === 'tab') { state.playing = false; state.engine = null; }
    persist(); broadcast();
  }
});

chrome.commands.onCommand.addListener(async (command) => {
  await restored;
  if (command === 'toggle-play') live.toggle();
  if (command === 'next-track') advance(1);
  if (command === 'prev-track') advance(-1);
});

async function setup() {
  await chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
  await installStaticRules();
  await syncYouTubeCookies();
}
syncYouTubeCookies();
chrome.storage.onChanged.addListener((c) => { if (c.settings) syncYouTubeCookies(); });
let cookieTimer: ReturnType<typeof setTimeout> | null = null;
chrome.cookies.onChanged.addListener(({ cookie }) => {
  if (!cookie.domain.endsWith('youtube.com')) return;
  if (cookieTimer) clearTimeout(cookieTimer);
  cookieTimer = setTimeout(syncYouTubeCookies, 1000);
});
chrome.runtime.onInstalled.addListener(setup);
chrome.runtime.onStartup.addListener(setup);
