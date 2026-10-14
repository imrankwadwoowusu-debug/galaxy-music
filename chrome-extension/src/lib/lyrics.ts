// Lyrics, ported from BitChord's LyricsRepository: every enabled provider is
// asked at the same time, answers are taken in the user's priority order,
// and (optionally) a word-timed answer beats a line-timed one.
import { best, toSeconds } from './matcher';

export interface LyricWord { t: number; d: number; text: string } // seconds
export interface LyricLine { time: number; end?: number; text: string; words?: LyricWord[] }
export interface Lyrics { synced: LyricLine[] | null; plain: string | null; source?: string; wordSynced?: boolean }

export type LyricsSourceId = 'lyricsplus' | 'paxsenix' | 'kugou' | 'lrclib' | 'musixmatch' | 'genius';
export interface LyricsSourceInfo { id: LyricsSourceId; label: string; hint: string }

// Same order and wording as the app's "Lyrics sources" sheet.
export const LYRICS_SOURCES: LyricsSourceInfo[] = [
  { id: 'lyricsplus', label: 'LyricsPlus', hint: 'Syllable by syllable, on community servers' },
  { id: 'paxsenix', label: 'PaxSenix', hint: 'Apple Music timings, word by word' },
  { id: 'kugou', label: 'KuGou', hint: 'Whole lines, strong outside the English catalogue' },
  { id: 'lrclib', label: 'LRCLIB', hint: 'Whole lines only, and always up' },
  { id: 'musixmatch', label: 'Musixmatch', hint: 'Whole lines, from the biggest lyrics catalogue' },
  { id: 'genius', label: 'Genius', hint: 'Plain text fallback, massive web catalogue' },
];
export const DEFAULT_LYRICS_ORDER: LyricsSourceId[] = LYRICS_SOURCES.map((s) => s.id);

export interface LyricsPrefs { order: LyricsSourceId[]; enabled: LyricsSourceId[]; preferSyllable: boolean }

interface Query { title: string; artist: string; album?: string; duration?: number; videoId?: string }

/* eslint-disable @typescript-eslint/no-explicit-any */
const TIMEOUT = 9000;
async function getJson(url: string, init?: RequestInit): Promise<any> {
  const ctl = new AbortController();
  const id = setTimeout(() => ctl.abort(), TIMEOUT);
  try {
    const r = await fetch(url, { ...init, signal: ctl.signal, credentials: 'omit' });
    if (!r.ok) return null;
    return await r.json();
  } catch { return null; } finally { clearTimeout(id); }
}
async function getText(url: string): Promise<string | null> {
  const ctl = new AbortController();
  const id = setTimeout(() => ctl.abort(), TIMEOUT);
  try {
    const r = await fetch(url, { signal: ctl.signal, credentials: 'omit' });
    return r.ok ? await r.text() : null;
  } catch { return null; } finally { clearTimeout(id); }
}

export function parseLrc(lrc: string): LyricLine[] {
  const out: LyricLine[] = [];
  for (const raw of lrc.split('\n')) {
    const stamps = [...raw.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
    if (!stamps.length) continue;
    const text = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const m of stamps) out.push({ time: Number(m[1]) * 60 + Number(m[2]), text });
  }
  return out.sort((a, b) => a.time - b.time);
}

const clean = (s: string) => s.replace(/\(.*?(official|video|audio|lyric|visuali[sz]er).*?\)|\[.*?\]/gi, '').trim();
const titleOnly = (s: string) => clean(s).replace(/\s*\((from|feat|ft)[^)]*\)/gi, '').trim();
const firstArtist = (a: string) => a.split(/,|&| x | feat\.? /i)[0].trim();

// ---------- providers ----------
const LYRICSPLUS_HOSTS = ['https://lyricsplus.binimum.org', 'https://lyricsplus.prjktla.workers.dev', 'https://lyricsplus.atomix.one', 'https://lyricsplus.prjktla.my.id', 'https://lyricsplus-seven.vercel.app'];
async function lyricsPlus(q: Query): Promise<Lyrics | null> {
  const p = new URLSearchParams({ title: titleOnly(q.title), artist: firstArtist(q.artist) });
  if (q.duration) p.set('duration', String(Math.round(q.duration)));
  if (q.album) p.set('album', q.album);
  for (const h of LYRICSPLUS_HOSTS) {
    const d = await getJson(`${h}/v2/lyrics/get?${p}`);
    if (!d?.lyrics?.length) continue;
    const word = d.type === 'Word' || d.type === 'Syllable';
    const lines: LyricLine[] = d.lyrics.map((l: any) => ({
      time: l.time / 1000,
      end: (l.time + l.duration) / 1000,
      text: l.text,
      words: word && l.syllabus?.length ? l.syllabus.map((s: any) => ({ t: s.time / 1000, d: s.duration / 1000, text: s.text })) : undefined,
    }));
    return { synced: lines, plain: null, wordSynced: word };
  }
  return null;
}

async function itunesId(q: Query): Promise<string | null> {
  for (const country of ['in', 'us']) {
    const d = await getJson(`https://itunes.apple.com/search?${new URLSearchParams({ term: `${titleOnly(q.title)} ${firstArtist(q.artist)}`, entity: 'song', limit: '10', country })}`);
    const rows: { id: string; title: string; artist: string; durationSec: number }[] = (d?.results ?? []).map((r: any) => ({ id: String(r.trackId), title: r.trackName, artist: r.artistName, durationSec: Math.round((r.trackTimeMillis ?? 0) / 1000) }));
    const hit = best({ title: q.title, artist: q.artist, durationSec: q.duration }, rows);
    if (hit) return hit.id;
  }
  return null;
}
async function paxSenix(q: Query): Promise<Lyrics | null> {
  const id = await itunesId(q);
  if (!id) return null;
  const d = await getJson(`https://lyrics.paxsenix.org/apple-music/lyrics?id=${id}`);
  if (!d?.content?.length) return null;
  const word = d.type === 'Syllable' || d.type === 'Word';
  const lines: LyricLine[] = d.content.map((l: any) => {
    const parts: any[] = l.text ?? [];
    const words = parts.map((w, i) => ({ t: w.timestamp / 1000, d: w.duration / 1000, text: w.text + (w.part || i === parts.length - 1 ? '' : ' ') }));
    return { time: l.timestamp / 1000, end: l.endtime / 1000, text: words.map((w) => w.text).join('').trim(), words: word ? words : undefined };
  });
  return { synced: lines, plain: null, wordSynced: word };
}

async function kugou(q: Query): Promise<Lyrics | null> {
  const p = new URLSearchParams({ ver: '1', man: 'yes', client: 'pc', keyword: `${firstArtist(q.artist)} - ${titleOnly(q.title)}` });
  if (q.duration) p.set('duration', String(Math.round(q.duration * 1000)));
  const s = await getJson(`https://lyrics.kugou.com/search?${p}`);
  const c = s?.candidates?.[0];
  if (!c) return null;
  const d = await getJson(`https://lyrics.kugou.com/download?${new URLSearchParams({ fmt: 'lrc', charset: 'utf8', client: 'pc', ver: '1', id: c.id, accesskey: c.accesskey })}`);
  if (!d?.content) return null;
  const lrc = new TextDecoder().decode(Uint8Array.from(atob(d.content), (ch) => ch.charCodeAt(0)));
  const lines = parseLrc(lrc).filter((l, i) => !(i < 4 && /(lyrics|composed|作词|作曲|by[:：])/i.test(l.text)) && !(i === 0 && l.text.includes(' - ')));
  return lines.length ? { synced: lines, plain: null } : null;
}

async function lrclib(q: Query): Promise<Lyrics | null> {
  const p = new URLSearchParams({ track_name: clean(q.title), artist_name: firstArtist(q.artist) });
  if (q.duration) p.set('duration', String(Math.round(q.duration)));
  let d = await getJson(`https://lrclib.net/api/get?${p}`);
  if (!d) {
    const list: any[] = (await getJson(`https://lrclib.net/api/search?${new URLSearchParams({ q: `${titleOnly(q.title)} ${firstArtist(q.artist)}` })}`)) ?? [];
    d = list.find((x) => x.syncedLyrics) ?? list[0];
  }
  if (!d) return null;
  return { synced: d.syncedLyrics ? parseLrc(d.syncedLyrics) : null, plain: d.plainLyrics ?? null };
}

let mxmToken: string | null = null;
let mxmRetryAt = 0;
/** Musixmatch answers unauthorised clients with made-up lyrics ("Wob gopini den…") on an
 *  exact 4-second grid. Never show those. */
function looksFake(lines: LyricLine[]): boolean {
  if (lines.length < 6) return false;
  const gaps = lines.slice(1, 12).map((l, i) => +(l.time - lines[i].time).toFixed(2));
  return gaps.every((g) => g === gaps[0]) && lines.slice(0, 12).every((l) => Number.isInteger(l.time));
}
async function musixmatch(q: Query): Promise<Lyrics | null> {
  const base = 'https://apic-desktop.musixmatch.com/ws/1.1';
  const common = { app_id: 'web-desktop-app-v1.0', format: 'json' };
  if (!mxmToken) {
    if (Date.now() < mxmRetryAt) return null;
    const t = await getJson(`${base}/token.get?${new URLSearchParams({ ...common, user_language: 'en', t: String(Date.now()) })}`);
    mxmToken = t?.message?.body?.user_token ?? null;
    // An all-zero or "UpgradeOnly" token is a refusal, not a session.
    if (!mxmToken || /UpgradeOnlyUpgradeOnly|^0+$/.test(mxmToken)) { mxmToken = null; mxmRetryAt = Date.now() + 5 * 60_000; return null; }
  }
  const p = new URLSearchParams({ ...common, usertoken: mxmToken, q_track: titleOnly(q.title), q_artist: firstArtist(q.artist), ...(q.duration ? { q_duration: String(Math.round(q.duration)) } : {}), namespace: 'lyrics_richsynched', subtitle_format: 'lrc' });
  const d = await getJson(`${base}/macro.subtitles.get?${p}`);
  const calls = d?.message?.body?.macro_calls;
  const lrc = calls?.['track.subtitles.get']?.message?.body?.subtitle_list?.[0]?.subtitle?.subtitle_body;
  if (d?.message?.header?.status_code === 401) { mxmToken = null; return null; }
  if (lrc) { const lines = parseLrc(lrc); return looksFake(lines) ? null : { synced: lines, plain: null }; }
  const plain = calls?.['track.lyrics.get']?.message?.body?.lyrics?.lyrics_body;
  return plain ? { synced: null, plain } : null;
}

async function genius(q: Query): Promise<Lyrics | null> {
  const s = await getJson(`https://genius.com/api/search/multi?${new URLSearchParams({ q: `${titleOnly(q.title)} ${firstArtist(q.artist)}` })}`);
  const hits: any[] = (s?.response?.sections ?? []).flatMap((x: any) => x.hits ?? []).filter((h: any) => h.type === 'song');
  const rows: { url: string; title: string; artist: string }[] = hits.map((h: any) => ({ url: h.result.url, title: h.result.title, artist: h.result.primary_artist?.name ?? '' }));
  const hit = best({ title: q.title, artist: q.artist }, rows);
  if (!hit) return null;
  const html = await getText(hit.url);
  if (!html) return null;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const parts = [...doc.querySelectorAll('[data-lyrics-container="true"]')].map((el) => {
    el.querySelectorAll('br').forEach((br) => br.replaceWith('\n'));
    el.querySelectorAll('[data-exclude-from-selection="true"]').forEach((x) => x.remove());
    return el.textContent ?? '';
  });
  const plain = parts.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  return plain ? { synced: null, plain } : null;
}

const PROVIDERS: Record<LyricsSourceId, (q: Query) => Promise<Lyrics | null>> = { lyricsplus: lyricsPlus, paxsenix: paxSenix, kugou, lrclib, musixmatch, genius };

// ---------- repository ----------
export async function getLyricsPrefs(): Promise<LyricsPrefs> {
  const r = await chrome.storage.local.get('lyricsPrefs');
  const p = (r.lyricsPrefs as Partial<LyricsPrefs>) ?? {};
  const order = [...(p.order ?? []).filter((x) => DEFAULT_LYRICS_ORDER.includes(x)), ...DEFAULT_LYRICS_ORDER.filter((x) => !(p.order ?? []).includes(x))];
  return { order, enabled: p.enabled ?? [...DEFAULT_LYRICS_ORDER], preferSyllable: p.preferSyllable ?? true };
}
export async function setLyricsPrefs(p: LyricsPrefs) {
  await chrome.storage.local.set({ lyricsPrefs: p });
  cache.clear();
}

const cache = new Map<string, Lyrics>();
export const lyricsDebug: string[] = [];

export async function getLyrics(title: string, artist: string, duration?: number, album?: string): Promise<Lyrics> {
  const key = `${title}|${artist}`;
  if (cache.has(key)) return cache.get(key)!;
  const prefs = await getLyricsPrefs();
  const q: Query = { title, artist, album, duration: duration && duration > 0 ? duration : toSeconds(undefined) };
  const order = prefs.order.filter((id) => prefs.enabled.includes(id));
  // Ask everyone at once; take answers in priority order.
  const started = order.map((id) => {
    const t0 = performance.now();
    return PROVIDERS[id](q)
      .catch(() => null)
      .then((r) => { lyricsDebug.push(`${id}: ${r ? (r.wordSynced ? 'word-synced' : r.synced ? 'line-synced' : 'plain') : 'none'} in ${Math.round(performance.now() - t0)} ms`); if (lyricsDebug.length > 40) lyricsDebug.shift(); return r; });
  });
  let lineHit: Lyrics | null = null;
  let plainHit: Lyrics | null = null;
  for (let i = 0; i < order.length; i++) {
    const r = await started[i];
    if (!r) continue;
    const tagged = { ...r, source: LYRICS_SOURCES.find((s) => s.id === order[i])!.label };
    if (r.synced?.length) {
      if (r.wordSynced || !prefs.preferSyllable) { cache.set(key, tagged); return tagged; }
      lineHit ??= tagged;
    } else if (r.plain) plainHit ??= tagged;
  }
  const result = lineHit ?? plainHit ?? { synced: null, plain: null };
  cache.set(key, result);
  return result;
}
