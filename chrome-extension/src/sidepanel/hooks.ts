import { useCallback, useEffect, useState } from 'react';
import { isFresh, readCache, refresh } from '../lib/cache';
import type { PlayerState, UiCommand } from '../lib/types';
import { artworkColor } from '../lib/theme';
import { AUTH_COOKIE_NAMES, sessionId } from '../lib/accounts';

/** Live session fingerprint: re-renders the moment you sign in/out or switch accounts in any Chrome tab. */
export function useSession(): string | null {
  const [sid, setSid] = useState<string | null>(null);
  useEffect(() => {
    let t: ReturnType<typeof setTimeout> | null = null;
    const check = () => sessionId().then(setSid);
    check();
    const fn = (c: chrome.cookies.CookieChangeInfo) => {
      if (!AUTH_COOKIE_NAMES.has(c.cookie.name)) return;
      if (t) clearTimeout(t);
      t = setTimeout(check, 800);
    };
    chrome.cookies.onChanged.addListener(fn);
    const vis = () => document.visibilityState === 'visible' && check();
    document.addEventListener('visibilitychange', vis);
    return () => { chrome.cookies.onChanged.removeListener(fn); document.removeEventListener('visibilitychange', vis); if (t) clearTimeout(t); };
  }, []);
  return sid;
}

export const send = (cmd: UiCommand) => chrome.runtime.sendMessage(cmd).catch(() => null);

export function usePlayer(): PlayerState | null {
  const [s, setS] = useState<PlayerState | null>(null);
  useEffect(() => {
    send({ type: 'get-state' }).then((r) => r && setS(r));
    const fn = (msg: any) => { if (msg?.type === 'state') setS(msg.state); };
    chrome.runtime.onMessage.addListener(fn);
    return () => chrome.runtime.onMessage.removeListener(fn);
  }, []);
  return s;
}

// Smoothly ticking position between 500 ms state pushes
export function useLivePosition(s: PlayerState | null): number {
  const [pos, setPos] = useState(0);
  useEffect(() => {
    if (!s) return;
    setPos(s.position);
    if (!s.playing) return;
    const start = performance.now();
    const id = setInterval(() => setPos(Math.min(s.position + (performance.now() - start) / 1000, s.duration || Infinity)), 100);
    return () => clearInterval(id);
  }, [s?.position, s?.playing, s?.duration]);
  return pos;
}

export function useArtColor(url?: string): string {
  const [c, setC] = useState('#23232c');
  useEffect(() => { if (url) artworkColor(url).then(setC); }, [url]);
  return c;
}

export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean } {
  const [st, setSt] = useState<{ data: T | null; error: string | null; loading: boolean }>({ data: null, error: null, loading: true });
  useEffect(() => {
    let alive = true;
    setSt((p) => ({ ...p, loading: true, error: null }));
    fn().then((data) => alive && setSt({ data, error: null, loading: false }))
      .catch((e) => alive && setSt({ data: null, error: String(e?.message ?? e), loading: false }));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return st;
}

export const fmt = (s: number) => {
  if (!isFinite(s) || s < 0) s = 0;
  const m = Math.floor(s / 60), sec = Math.floor(s % 60);
  return `${m}:${sec.toString().padStart(2, '0')}`;
};

/**
 * Cached data: paints the last copy at once, refreshes it when older than
 * `ttlMs`, and only shows an error when there is nothing to show.
 */
export function useCached<T>(key: string | null, fetcher: () => Promise<T>, ttlMs = 10 * 60 * 1000) {
  const [st, setSt] = useState<{ data: T | null; error: string | null; loading: boolean; refreshing: boolean }>({ data: null, error: null, loading: true, refreshing: false });
  const [nonce, setNonce] = useState(0);
  const retry = useCallback(() => setNonce((n) => n + 1), []);
  useEffect(() => {
    if (!key) return;
    let alive = true;
    setSt({ data: null, error: null, loading: true, refreshing: false });
    (async () => {
      const hit = await readCache<T>(key);
      if (!alive) return;
      if (hit) setSt({ data: hit.data, error: null, loading: false, refreshing: !isFresh(hit, ttlMs) || nonce > 0 });
      if (hit && isFresh(hit, ttlMs) && nonce === 0) return;
      try {
        const data = await refresh(key, fetcher);
        if (alive) setSt({ data, error: null, loading: false, refreshing: false });
      } catch (e: any) {
        if (alive) setSt((p) => ({ data: p.data, error: p.data ? null : String(e?.message ?? e), loading: false, refreshing: false }));
      }
    })();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, nonce]);
  return { ...st, retry };
}

// Live settings: re-renders when Settings change anywhere (panel, tab, background).
import { DEFAULT_SETTINGS, type Settings } from '../lib/types';
import { getSettings } from '../lib/settings';
export function useSettings(): Settings {
  const [s, setS] = useState<Settings>(DEFAULT_SETTINGS);
  useEffect(() => {
    getSettings().then(setS);
    const fn = (c: Record<string, unknown>) => { if ('settings' in c) getSettings().then(setS); };
    chrome.storage.onChanged.addListener(fn);
    return () => chrome.storage.onChanged.removeListener(fn);
  }, []);
  return s;
}
