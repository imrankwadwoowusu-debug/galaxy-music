// Offscreen document = BitChord's "PlaybackService" for Chrome. MV3 service
// workers can't play audio, so the <audio> element lives here and survives the
// side panel being closed.
const a = document.getElementById('a') as HTMLAudioElement;
let token = 0;
let lastSent = 0;

const emit = (kind: string, extra: Record<string, unknown> = {}) =>
  chrome.runtime.sendMessage({ type: 'audio-event', kind, token, position: a.currentTime || 0, duration: isFinite(a.duration) ? a.duration : 0, playing: !a.paused && !a.ended, volume: Math.round(a.volume * 100), ...extra }).catch(() => {});

a.addEventListener('timeupdate', () => { const n = performance.now(); if (n - lastSent > 500) { lastSent = n; emit('time'); } });
a.addEventListener('playing', () => emit('playing'));
a.addEventListener('pause', () => emit('pause'));
a.addEventListener('ended', () => emit('ended'));
a.addEventListener('durationchange', () => emit('time'));
a.addEventListener('error', () => { if (a.getAttribute('src')) emit('error', { message: a.error?.message || `media error ${a.error?.code}`, code: a.error?.code }); });

// Smooth volume ramps: no clicks or a burst of the old song when tracks change.
let target = 1;
let ramp: ReturnType<typeof setInterval> | null = null;
function fadeTo(v: number, ms: number): Promise<void> {
  if (ramp) clearInterval(ramp);
  return new Promise((done) => {
    const from = a.volume, steps = Math.max(1, Math.round(ms / 20));
    let i = 0;
    ramp = setInterval(() => {
      i++;
      a.volume = Math.min(1, Math.max(0, from + (v - from) * (i / steps)));
      if (i >= steps) { clearInterval(ramp!); ramp = null; done(); }
    }, 20);
  });
}

function setMeta(meta?: { title: string; artist: string; album?: string; artwork?: string }) {
  if (!meta || !('mediaSession' in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: meta.title, artist: meta.artist, album: meta.album ?? '',
    artwork: meta.artwork ? [{ src: meta.artwork, sizes: '544x544', type: 'image/jpeg' }] : [],
  });
}
try {
  const ms = navigator.mediaSession;
  ms.setActionHandler('play', () => a.play());
  ms.setActionHandler('pause', () => a.pause());
  ms.setActionHandler('nexttrack', () => chrome.runtime.sendMessage({ type: 'audio-event', kind: 'media-next', token }));
  ms.setActionHandler('previoustrack', () => chrome.runtime.sendMessage({ type: 'audio-event', kind: 'media-prev', token }));
  ms.setActionHandler('seekto', (d) => { if (d.seekTime != null) a.currentTime = d.seekTime; });
} catch { /* ignore */ }

chrome.runtime.onMessage.addListener((msg, _s, reply) => {
  if (msg?.target !== 'offscreen') return;
  switch (msg.cmd) {
    case 'load': {
      token = msg.token;
      target = (msg.volume ?? 100) / 100;
      if (ramp) { clearInterval(ramp); ramp = null; }
      a.volume = 0;
      a.src = msg.url;
      if (msg.startAt > 1) {
        // resume where the previous stream stopped (source switch mid-song)
        a.addEventListener('loadedmetadata', () => { try { a.currentTime = msg.startAt; } catch { /* ignore */ } }, { once: true });
      }
      setMeta(msg.meta);
      a.addEventListener('playing', () => fadeTo(target, 250), { once: true });
      a.play().catch((e) => { if (!a.error && e?.name === 'NotAllowedError') emit('error', { message: String(e?.message ?? e) }); });
      break;
    }
    case 'play': a.play().catch(() => {}); break;
    case 'pause': a.pause(); break;
    case 'toggle': a.paused ? a.play().catch(() => {}) : a.pause(); break;
    case 'seek': a.currentTime = msg.seconds; break;
    case 'volume': target = msg.value / 100; if (!ramp) a.volume = target; break;
    case 'stop': {
      token = -1;
      const stopNow = () => { if (token !== -1) return; a.pause(); a.removeAttribute('src'); a.load(); };
      if (!a.paused && a.volume > 0.05) fadeTo(0, 120).then(stopNow); else stopNow();
      break;
    }
    case 'ping': break;
  }
  reply?.({ ok: true });
});
