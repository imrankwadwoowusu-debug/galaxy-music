// Stale-while-revalidate cache for InnerTube data. Memory first, then
// chrome.storage.local, so Home / Moods paint instantly on every open and
// only refresh in the background.
interface Entry<T> { at: number; data: T }
const mem = new Map<string, Entry<unknown>>();
const inflight = new Map<string, Promise<unknown>>();
const PREFIX = 'c:';
const MAX_KEYS = 40;

export async function readCache<T>(key: string): Promise<Entry<T> | null> {
  const m = mem.get(key) as Entry<T> | undefined;
  if (m) return m;
  try {
    const r = await chrome.storage.local.get(PREFIX + key);
    const e = r[PREFIX + key] as Entry<T> | undefined;
    if (e) mem.set(key, e);
    return e ?? null;
  } catch {
    return null;
  }
}

async function writeCache<T>(key: string, data: T) {
  const e = { at: Date.now(), data };
  mem.set(key, e);
  try {
    await chrome.storage.local.set({ [PREFIX + key]: e });
    const all = await chrome.storage.local.get(null);
    const keys = Object.keys(all).filter((k) => k.startsWith(PREFIX));
    if (keys.length > MAX_KEYS) {
      const old = keys.sort((a, b) => (all[a] as Entry<unknown>).at - (all[b] as Entry<unknown>).at).slice(0, keys.length - MAX_KEYS);
      await chrome.storage.local.remove(old);
    }
  } catch { /* quota: memory cache still works */ }
}

/** Fetch once per key at a time; store the result. */
export function refresh<T>(key: string, fetcher: () => Promise<T>): Promise<T> {
  const running = inflight.get(key) as Promise<T> | undefined;
  if (running) return running;
  const p = fetcher()
    .then(async (d) => { await writeCache(key, d); return d; })
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

export const isFresh = (e: { at: number } | null, ttlMs: number) => !!e && Date.now() - e.at < ttlMs;
export function clearMemory() { mem.clear(); }
