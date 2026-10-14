// Runs in the music.youtube.com page's own JS world (MAIN) so it can reach the
// YouTube player API on #movie_player. It is the helper tab's "engine":
// the side panel never shows this tab, it just drives it.
import type { PageCommand, PageState } from '../lib/types';

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

function snapshot(): PageState | null {
  const p = player();
  if (!p || typeof p.getPlayerState !== 'function') return null;
  const data = p.getVideoData?.() ?? ({} as any);
  return {
    videoId: data.video_id || null,
    state: p.getPlayerState(),
    position: p.getCurrentTime?.() ?? 0,
    duration: p.getDuration?.() ?? 0,
    volume: p.getVolume?.() ?? 100,
    title: data.title,
    author: data.author,
    ad: adNow,
  };
}

function send(kind: string, payload: unknown) {
  window.postMessage({ __bc: kind, payload }, location.origin);
}

let hooked = false;
function hook() {
  const p = player();
  if (!p || hooked || typeof p.addEventListener !== 'function') return;
  hooked = true;
  // Push state immediately on every change so the extension can take over
  // before YouTube Music's own autoplay moves on.
  p.addEventListener('onStateChange', () => send('state', snapshot()));
  send('ready', snapshot());
}

function run(c: PageCommand) {
  const p = player();
  if (!p || typeof p.loadVideoById !== 'function') {
    if (c.cmd === 'load') location.href = `/watch?v=${encodeURIComponent(c.videoId)}`;
    return;
  }
  switch (c.cmd) {
    case 'load': {
      // Already on it (e.g. the tab opened straight onto this video): don't restart.
      if (p.getVideoData?.().video_id === c.videoId && [1, 3].includes(p.getPlayerState())) break;
      p.loadVideoById(c.videoId);
      p.playVideo();
      break;
    }
    case 'play': p.playVideo(); break;
    case 'pause': p.pauseVideo(); break;
    case 'toggle': p.getPlayerState() === 1 ? p.pauseVideo() : p.playVideo(); break;
    case 'seek': p.seekTo(c.seconds, true); break;
    case 'volume': p.setVolume(c.value); break;
  }
}

window.addEventListener('message', (e) => {
  if (e.source !== window || e.data?.__bc !== 'cmd') return;
  run(e.data.payload as PageCommand);
});

// Route OS media keys / Chrome media controls to BitChord's queue rather than
// YouTube Music's own queue. Re-applied because the page re-registers handlers.
function claimMediaKeys() {
  try {
    navigator.mediaSession.setActionHandler('nexttrack', () => send('media-key', 'next'));
    navigator.mediaSession.setActionHandler('previoustrack', () => send('media-key', 'prev'));
  } catch { /* unsupported */ }
}

setInterval(() => {
  adNow = skipAds();
  hook();
  claimMediaKeys();
  const s = snapshot();
  if (s) send('state', s);
}, 500);
