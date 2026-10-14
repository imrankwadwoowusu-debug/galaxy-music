// Runs in the music.youtube.com page's own JS world (MAIN) so it can reach the
// YouTube player API on #movie_player. It is the helper tab's "engine":
// the side panel never shows this tab, it just drives it.
import type { PageCommand, PageState } from '../lib/types';

const W = window as unknown as { __bcMain?: boolean };
const FIRST = !W.__bcMain;
W.__bcMain = true;
// Only the pinned helper tab is driven; the user's own YouTube Music tabs are
// left alone (no data saver, no media-key takeover). The flag survives reloads.
if (/[?&]bchelper=1/.test(location.search)) sessionStorage.setItem('bc-helper', '1');
let IS_HELPER = sessionStorage.getItem('bc-helper') === '1';

type YTPlayer = HTMLElement & {
  loadVideoById(id: string | { videoId: string; startSeconds?: number }): void;
  playVideo(): void;
  pauseVideo(): void;
  seekTo(s: number, allowSeekAhead: boolean): void;
  getCurrentTime(): number;
  getDuration(): number;
  getPlayerState(): number;
  getVideoData(): { video_id: string; title: string; author: string };
  setVolume(v: number): void;
  setPlaybackQualityRange?(min: string, max?: string): void;
  setPlaybackQuality?(q: string): void;
  getPlaybackQuality?(): string;
  getVolume(): number;
  addEventListener(ev: string, fn: (s: number) => void): void;
};

// ---------- ad-free ----------
// 1) Strip ad slots from every player/next response before the page reads
//    them (the same trick content blockers use), so ads are never scheduled.
const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adParams'];
function stripAds(o: any, depth = 0): any {
  if (!o || typeof o !== 'object' || depth > 6) return o;
  for (const k of AD_KEYS) if (k in o) delete o[k];
  if (o.playerResponse) stripAds(o.playerResponse, depth + 1);
  if (Array.isArray(o)) o.forEach((x) => stripAds(x, depth + 1));
  else for (const k of ['response', 'playerResponse', 'contents']) if (o[k] && typeof o[k] === 'object' && depth < 2) stripAds(o[k], depth + 1);
  return o;
}
if (FIRST) {
const origParse = JSON.parse;
JSON.parse = function (this: unknown, text: string, reviver?: any) {
  const v = origParse.call(this, text, reviver);
  try { if (typeof text === 'string' && text.length > 200 && /"(adPlacements|playerAds|adSlots)"/.test(text)) stripAds(v); } catch { /* ignore */ }
  return v;
} as typeof JSON.parse;
const origJson = Response.prototype.json;
Response.prototype.json = async function () {
  const v = await origJson.call(this);
  try { if (/\/youtubei\/v1\/(player|next)/.test(this.url)) stripAds(v); } catch { /* ignore */ }
  return v;
};
for (const name of ['ytInitialPlayerResponse']) {
  let held: any;
  try {
    Object.defineProperty(window, name, { configurable: true, get: () => held, set: (v) => { held = stripAds(v); } });
  } catch { /* ignore */ }
}
}
// 2) If an ad still slips through: mute it, jump to its end, press Skip.
let mutedForAd = false;
function skipAds() {
  const mp = document.getElementById('movie_player');
  const showing = !!mp?.classList.contains('ad-showing') || !!mp?.classList.contains('ad-interrupting');
  const v = mp?.querySelector('video') as HTMLVideoElement | null;
  if (showing && v) {
    if (!v.muted) { v.muted = true; mutedForAd = true; }
    if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
    (document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[class*="skip"]') as HTMLElement | null)?.click();
  } else if (!showing && mutedForAd && v) { v.muted = false; mutedForAd = false; }
  return showing;
}
let adNow = false;
new MutationObserver(() => { adNow = skipAds(); }).observe(document.documentElement, { attributes: true, subtree: true, attributeFilter: ['class'] });

const player = (): YTPlayer | null => document.getElementById('movie_player') as YTPlayer | null;
const video = (): HTMLVideoElement | null => (player()?.querySelector('video') as HTMLVideoElement | null) ?? document.querySelector('video');

function snapshot(event?: string): PageState | null {
  const p = player();
  if (!p || typeof p.getPlayerState !== 'function') return null;
  const data = p.getVideoData?.() ?? ({} as any);
  const v = video();
  return {
    videoId: data.video_id || null,
    state: p.getPlayerState(),
    // the media element is exact; the player API rounds and lags while hidden
    position: v && isFinite(v.currentTime) ? v.currentTime : p.getCurrentTime?.() ?? 0,
    duration: (v && isFinite(v.duration) && v.duration) || p.getDuration?.() || 0,
    volume: p.getVolume?.() ?? 100,
    title: data.title,
    author: data.author,
    ad: adNow,
    at: Date.now(),
    event,
    quality: p.getPlaybackQuality?.(),
  };
}

function send(kind: string, payload: unknown) {
  window.postMessage({ __bc: kind, payload }, location.origin);
}
const push = (event?: string) => { const s = snapshot(event); if (s) send('state', s); };

// ---------- data saver ----------
// The helper only needs sound. Ask for the smallest picture, and prefer the
// "Song" (audio artwork) version over the music video when YouTube Music has both.
let saver = localStorage.getItem('bc-saver') !== '0';
let saverFor = '';
function applySaver(force = false) {
  if (!IS_HELPER || !saver) return;
  const p = player();
  const id = p?.getVideoData?.().video_id ?? '';
  if (!p || (!force && saverFor === id)) return;
  saverFor = id;
  try { p.setPlaybackQualityRange?.('tiny', 'tiny'); p.setPlaybackQuality?.('tiny'); } catch { /* old API */ }
  const songBtn = document.querySelector('ytmusic-av-toggle .song-button:not([aria-pressed="true"]), ytmusic-av-toggle [data-mode="song"]:not(.selected)') as HTMLElement | null;
  const toggle = document.querySelector('ytmusic-av-toggle') as HTMLElement | null;
  if (songBtn && toggle && !toggle.hasAttribute('hidden') && toggle.getAttribute('playback-mode') !== 'ATV_PREFERRED') songBtn.click();
  const v = video();
  if (v) v.style.visibility = 'hidden'; // nothing to paint in a tab nobody looks at
}

// ---------- queue hand-off ----------
// The extension tells us the next song in advance, so when this one ends we
// switch to it here, instantly, before YouTube Music's own autoplay can start
// something else (which used to play a few seconds of the wrong song).
let nextHint: string | null = null;
let endedFor = '';
let handedOffAt = 0;
function onEnded() {
  const p = player();
  const id = p?.getVideoData?.().video_id ?? '';
  if (!p || adNow || endedFor === id) return;
  // The player and the media element both report the end; after we switch,
  // the second report would read the NEW song's id. Ignore it.
  if (Date.now() - handedOffAt < 2000) return;
  const v = video();
  if (v && !v.ended && isFinite(v.duration) && v.duration - v.currentTime > 1) return;
  endedFor = id;
  if (nextHint && nextHint !== id) {
    const to = nextHint; nextHint = null;
    handedOffAt = Date.now();
    p.loadVideoById(to); p.playVideo();
    send('advanced', { from: id, to });
  } else {
    p.pauseVideo();
    send('ended', { videoId: id });
  }
  push('ended');
}

let hookedPlayer: YTPlayer | null = null;
let hookedVideo: HTMLVideoElement | null = null;
function hook() {
  const p = player();
  if (p && p !== hookedPlayer && typeof p.addEventListener === 'function') {
    hookedPlayer = p;
    p.addEventListener('onStateChange', (st: number) => {
      if (st === 0) onEnded();
      if (st === 1 || st === -1 || st === 3) applySaver();
      push('state');
    });
    send('ready', snapshot('ready'));
  }
  const v = video();
  if (v && v !== hookedVideo) {
    hookedVideo = v;
    for (const ev of ['play', 'playing', 'pause', 'waiting', 'seeked', 'loadedmetadata', 'volumechange', 'ratechange']) v.addEventListener(ev, () => push(ev));
    v.addEventListener('ended', onEnded);
    // Fire the hand-off a beat before the very end: no silent gap, no autoplay race.
    v.addEventListener('timeupdate', () => { if (nextHint && v.duration > 5 && v.duration - v.currentTime < 0.35 && !v.paused) onEnded(); });
  }
}

function run(c: PageCommand) {
  const p = player();
  if (!p || typeof p.loadVideoById !== 'function') {
    if (c.cmd === 'load') location.href = `/watch?v=${encodeURIComponent(c.videoId)}&bchelper=1${c.startSeconds ? `&t=${Math.floor(c.startSeconds)}` : ''}`;
    return;
  }
  switch (c.cmd) {
    case 'load': {
      endedFor = '';
      // Already on it (the tab opened onto it, or we switched here on our own): don't restart.
      if (p.getVideoData?.().video_id === c.videoId && [1, 3].includes(p.getPlayerState())) break;
      p.loadVideoById(c.startSeconds ? { videoId: c.videoId, startSeconds: c.startSeconds } : c.videoId);
      p.playVideo();
      break;
    }
    case 'play': p.playVideo(); break;
    case 'pause': p.pauseVideo(); break;
    case 'toggle': p.getPlayerState() === 1 ? p.pauseVideo() : p.playVideo(); break;
    case 'seek': p.seekTo(c.seconds, true); break;
    case 'volume': p.setVolume(c.value); break;
    case 'hint': nextHint = c.videoId; break;
    case 'config':
      // Only the background's own helper tab gets config: mark it, in case the URL flag was lost in a redirect.
      IS_HELPER = true; sessionStorage.setItem('bc-helper', '1');
      saver = c.dataSaver; localStorage.setItem('bc-saver', saver ? '1' : '0');
      if (saver) applySaver(true); else { try { p.setPlaybackQualityRange?.('auto', 'auto'); } catch { /* ignore */ } }
      break;
    case 'ping': break;
  }
  // answer every command at once, so the panel never waits for the next tick
  setTimeout(() => push(c.cmd), 60);
  setTimeout(() => push(c.cmd), 450);
}

if (FIRST) {
  window.addEventListener('message', (e) => {
    if (e.source !== window || e.data?.__bc !== 'cmd') return;
    run(e.data.payload as PageCommand);
  });
}

// Route OS media keys / Chrome media controls to BitChord's queue rather than
// YouTube Music's own queue. Re-applied because the page re-registers handlers.
function claimMediaKeys() {
  if (!IS_HELPER) return;
  try {
    navigator.mediaSession.setActionHandler('nexttrack', () => send('media-key', 'next'));
    navigator.mediaSession.setActionHandler('previoustrack', () => send('media-key', 'prev'));
  } catch { /* unsupported */ }
}

// Events carry the real-time changes; this is only a heartbeat (it was 2/s).
if (FIRST) setInterval(() => {
  adNow = skipAds();
  hook();
  claimMediaKeys();
  if (IS_HELPER) applySaver();
  push();
}, 1000);
