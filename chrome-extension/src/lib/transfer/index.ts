// Turns tracks from another service into playable YouTube Music songs and
// saves them: as a BitChord playlist (or into Liked songs), and optionally as
// a private playlist / likes in your YouTube Music account.
import type { Track } from '../types';
import { createPlaylist, likeOnYouTubeMusic, searchSongs } from '../innertube';
import { score, titleParts, toSeconds } from '../matcher';
import { getLiked } from '../history';
import type { SourceTrack } from './sources';

export interface ImportedPlaylist {
  id: string; title: string; source: string; image?: string; tracks: Track[];
  missed: SourceTrack[]; ytmPlaylistId?: string; at: number;
}
const KEY = 'importedPlaylists';
export async function getImported(): Promise<ImportedPlaylist[]> {
  return ((await chrome.storage.local.get(KEY))[KEY] as ImportedPlaylist[]) ?? [];
}
export async function deleteImported(id: string) {
  await chrome.storage.local.set({ [KEY]: (await getImported()).filter((p) => p.id !== id) });
}

const norm = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9\u0900-\u0dff ]+/g, ' ').replace(/\s+/g, ' ').trim();
/** Spotify/Apple decorations YouTube Music doesn't use. */
const clean = (t: string) => t.replace(/\s+-\s+(\d{4}\s+)?(remaster(ed)?|single version|mono|stereo)(\s+\d{4})?$/i, '').replace(/\s*\((feat|ft|with)\.? [^)]*\)/i, '').trim();
const leadArtist = (a: string) => a.split(/,|&| x | feat\.? | ft\.? | and /i)[0].trim();

const memo = new Map<string, Track | null>();
/** Best YouTube Music song for a track from another service, or null when nothing is a safe match. */
export async function matchTrack(t: SourceTrack): Promise<Track | null> {
  const k = norm(`${t.title}|${t.artist}|${t.durationSec ?? ''}`);
  if (memo.has(k)) return memo.get(k)!;
  const title = clean(t.title);
  let found: Track | null = null;
  for (const q of [`${title} ${leadArtist(t.artist)}`, `${title} ${t.artist}`, title]) {
    let rows: Track[] = [];
    for (let tries = 0; tries < 4; tries++) {
      try { rows = await searchSongs(q); break; }
      catch (e: any) {
        // YouTube Music rate-limits bursts; back off instead of calling it a miss.
        if (/\(429\)|too long|connection/i.test(String(e?.message))) { await new Promise((r) => setTimeout(r, 1500 * (tries + 1))); continue; }
        break;
      }
    }
    const target = { title, artist: t.artist, durationSec: t.durationSec, tolerance: 6 };
    let top: Track | null = null, topScore = 0;
    for (const r of rows.slice(0, 10)) {
      const c = { title: clean(r.title), artist: r.artists, durationSec: toSeconds(r.duration) };
      let s = score(target, c);
      // Looser fallback: same lead artist, one title contains the other, same length.
      if (!s) {
        const a = titleParts(title).words, b = titleParts(c.title).words;
        const sameLen = !t.durationSec || !c.durationSec || Math.abs(t.durationSec - c.durationSec) <= 3;
        const artistOk = norm(c.artist).includes(norm(leadArtist(t.artist))) || norm(t.artist).includes(norm(leadArtist(c.artist)));
        if (a && b && (a.includes(b) || b.includes(a)) && sameLen && artistOk && t.durationSec && c.durationSec) s = 30;
      }
      if (t.album && r.album && norm(r.album) === norm(t.album)) s += s ? 5 : 0;
      if (s > topScore) { top = r; topScore = s; }
    }
    if (top) { found = top; break; }
  }
  memo.set(k, found);
  return found;
}

export interface Progress { done: number; total: number; matched: number; phase: 'reading' | 'matching' | 'saving' | 'done'; note?: string }

async function pool<T, R>(items: T[], n: number, fn: (x: T, i: number) => Promise<R>, tick: () => void): Promise<R[]> {
  const out = new Array<R>(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k], k); tick(); }
  }));
  return out;
}

export async function runImport(opts: {
  title: string; source: string; image?: string; tracks: SourceTrack[]; asLiked: boolean; toYouTube: boolean;
  onProgress: (p: Progress) => void; signal?: { cancelled: boolean };
}): Promise<{ matched: Track[]; missed: SourceTrack[]; ytm?: string; ytmError?: string }> {
  const { tracks, onProgress } = opts;
  let done = 0, matched = 0;
  const res = await pool(tracks, 4, async (t) => {
    if (opts.signal?.cancelled) return null;
    const m = await matchTrack(t);
    if (m) matched++;
    return m;
  }, () => { done++; onProgress({ done, total: tracks.length, matched, phase: 'matching' }); });
  if (opts.signal?.cancelled) throw new Error('Import cancelled');
  const seen = new Set<string>();
  const ok: Track[] = [];
  const missed: SourceTrack[] = [];
  res.forEach((m, i) => { if (!m) missed.push(tracks[i]); else if (!seen.has(m.videoId)) { seen.add(m.videoId); ok.push(m); } });

  onProgress({ done, total: tracks.length, matched, phase: 'saving' });
  let ytm: string | undefined, ytmError: string | undefined;
  if (opts.asLiked) {
    const liked = await getLiked();
    const have = new Set(liked.map((x) => x.videoId));
    await chrome.storage.local.set({ liked: [...ok.filter((x) => !have.has(x.videoId)), ...liked] });
    if (opts.toYouTube && ok.length) {
      try {
        let n = 0;
        await pool(ok, 3, (x) => likeOnYouTubeMusic(x.videoId), () => onProgress({ done: ++n, total: ok.length, matched, phase: 'saving', note: 'Liking on YouTube Music' }));
        ytm = 'LM';
      } catch (e: any) { ytmError = String(e?.message ?? e); }
    }
  } else {
    if (opts.toYouTube && ok.length) {
      try { ytm = await createPlaylist(opts.title, `Moved from ${opts.source} with BitChord`, ok.map((x) => x.videoId)); }
      catch (e: any) { ytmError = String(e?.message ?? e); }
    }
    // Importing the same playlist again replaces the old copy.
    const list = (await getImported()).filter((p) => !(p.source === opts.source && p.title === opts.title));
    const id = `${opts.source.toLowerCase().replace(/\s+/g, '-')}:${Date.now()}`;
    list.unshift({ id, title: opts.title, source: opts.source, image: opts.image ?? ok[0]?.thumbnail, tracks: ok, missed, ytmPlaylistId: ytm, at: Date.now() });
    await chrome.storage.local.set({ [KEY]: list });
  }
  onProgress({ done, total: tracks.length, matched, phase: 'done' });
  return { matched: ok, missed, ytm, ytmError };
}
