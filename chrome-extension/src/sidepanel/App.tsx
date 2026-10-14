import { useEffect, useRef, useState } from 'react';
import type { Card, MoodChip } from '../lib/types';
import { getAccountInfo } from '../lib/innertube';
import { send, useArtColor, useCached, usePlayer, useSession, useSettings } from './hooks';
import { Icon } from './components/Icon';
import { CollectionView, ExplorePage, LibraryPage, ListenNow, MoodView, SearchPage } from './components/Views';
import { MiniBar, NowPlaying } from './components/NowPlaying';
import { SettingsPage } from './components/Settings';
import { Arcs } from './components/Logo';

export type Tab = 'listen' | 'explore' | 'library' | 'search';
const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'listen', label: 'Play', icon: 'play' },
  { id: 'explore', label: 'Explore', icon: 'compass' },
  { id: 'library', label: 'Library', icon: 'library' },
  { id: 'search', label: 'Search', icon: 'search' },
];

const isTab = new URLSearchParams(location.search).get('mode') === 'tab';

type Overlay = { kind: 'coll'; card: Card } | { kind: 'mood'; chip: MoodChip } | null;

export default function App() {
  const player = usePlayer();
  const settings = useSettings();
  const color = useArtColor(player?.track?.thumbnail);
  const [tab, setTab] = useState<Tab>('listen');
  const [query, setQuery] = useState<string | null>(null);
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [expanded, setExpanded] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState<false | true | 'transfer'>(false);
  const main = useRef<HTMLDivElement>(null);
  const session = useSession();
  const live = settings.useAccount && session && session !== 'out';
  const account = useCached(live ? `account:${settings.authUser}:${session}` : null, getAccountInfo, 6 * 60 * 60 * 1000);
  const me = live ? account.data ?? null : null;
  const signedIn = !!live; // session cookies present = signed in, even before the profile loads

  const top = () => main.current?.scrollTo({ top: 0 });
  const go = (t: Tab) => { setTab(t); setOverlay(null); if (t !== 'search') setQuery(null); top(); };
  const onSearch = (q: string) => { setTab('search'); setQuery(q); setOverlay(null); top(); };
  const onArtist = (name: string) => onSearch(name);
  const onMood = (chip: MoodChip) => { setOverlay({ kind: 'mood', chip }); top(); };
  const onOpen = (card: Card) => { setOverlay({ kind: 'coll', card }); top(); };
  const ctx = { activeId: player?.videoId ?? null, onArtist, onOpen };

  useEffect(() => {
    const fn = (e: KeyboardEvent) => {
      const typing = ['INPUT', 'TEXTAREA'].includes((document.activeElement as HTMLElement)?.tagName);
      if (e.code === 'Space' && !typing && (document.activeElement as HTMLElement)?.tagName !== 'BUTTON') { e.preventDefault(); send({ type: 'toggle' }); }
      if (!typing && ((e.key === '/') || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k')) && tab !== 'search') { e.preventDefault(); go('search'); }
    };
    window.addEventListener('keydown', fn);
    return () => window.removeEventListener('keydown', fn);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  // Theme + motion/blur preferences
  const [sysDark, setSysDark] = useState(() => matchMedia('(prefers-color-scheme: dark)').matches);
  useEffect(() => {
    const m = matchMedia('(prefers-color-scheme: dark)');
    const fn = () => setSysDark(m.matches);
    m.addEventListener('change', fn);
    return () => m.removeEventListener('change', fn);
  }, []);
  const theme = settings.theme === 'system' ? (sysDark ? 'dark' : 'light') : settings.theme;

  const titles: Record<Tab, string> = { listen: 'Listen Now', explore: 'Explore', library: 'Library', search: 'Search' };

  return (
    <div
      className={`app ${isTab ? 'tab-mode' : ''} ${expanded ? 'expanded' : ''} ${settings.reduceMotion ? 'reduce-motion' : ''} ${settings.reduceBlur ? 'reduce-blur' : ''}`}
      data-theme={theme}
      style={{ ['--art' as string]: color }}
    >
      <div className="backdrop" />
      <div className="layout">
        <main className="main" ref={main}>
          <header className="appbar">
            <button className="arcs-btn" onClick={() => go('listen')} aria-label="BitChord, Listen Now"><Arcs size={34} /></button>
            <div className="appbar-title">{overlay ? '' : ''}</div>
            <div className="appbar-actions">
              {!isTab && <button className="round-btn" title="Open in a full tab" onClick={() => send({ type: 'open-tab' })}><Icon name="external" size={18} /></button>}
              <button className="round-btn avatar" title="Settings" onClick={() => setSettingsOpen(true)}>
                {me?.photo ? <img src={me.photo} alt="" referrerPolicy="no-referrer" /> : <Icon name="person" size={20} />}
              </button>
            </div>
          </header>

          <div className="content">
            {overlay?.kind === 'coll' ? (
              <CollectionView card={overlay.card} ctx={ctx} onBack={() => setOverlay(null)} />
            ) : overlay?.kind === 'mood' ? (
              <MoodView chip={overlay.chip} ctx={ctx} onBack={() => setOverlay(null)} />
            ) : (
              <>
                <h1 className="big-title">{titles[tab]}</h1>
                {tab === 'listen' && <ListenNow ctx={ctx} me={me} signedIn={signedIn || session === null} session={session ?? ''} onSignIn={() => setSettingsOpen(true)} onMood={onMood} goExplore={() => go('explore')} />}
                {tab === 'explore' && <ExplorePage ctx={ctx} onMood={onMood} />}
                {tab === 'library' && <LibraryPage ctx={ctx} me={me} signedIn={signedIn} session={session ?? ''} onSignIn={() => setSettingsOpen(true)} onTransfer={() => setSettingsOpen('transfer')} />}
                {tab === 'search' && <SearchPage ctx={ctx} query={query} onSearch={(q) => setQuery(q || null)} onMood={onMood} />}
              </>
            )}
          </div>
        </main>

        <aside className="side">
          {player && <NowPlaying s={player} settings={settings} onArtist={(n) => { setExpanded(false); onArtist(n); }} />}
          <button className="collapse" onClick={() => setExpanded(false)} aria-label="Close Now Playing"><Icon name="chevron" /></button>
        </aside>
      </div>

      <div className="dock">
        {player && <MiniBar s={player} onExpand={() => setExpanded(true)} />}
        <nav className="tabbar" aria-label="Main">
          {TABS.map((t) => (
            <button key={t.id} className={`tab ${tab === t.id && !overlay ? 'on' : ''}`} onClick={() => go(t.id)} aria-current={tab === t.id}>
              <Icon name={t.icon} size={22} />
              <span>{t.label}</span>
            </button>
          ))}
        </nav>
      </div>

      {settingsOpen && <SettingsPage me={me} initial={settingsOpen === 'transfer' ? 'transfer' : undefined} onClose={() => { setSettingsOpen(false); account.retry(); }} />}
    </div>
  );
}
