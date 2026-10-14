import { useState } from 'react';
import type { Card, Track } from '../../lib/types';
import { getCardTracks, getQueue } from '../../lib/innertube';
import { send } from '../hooks';
import { Icon } from './Icon';

// Play a single song: start it now, then fill the queue with its radio.
export async function playSongWithRadio(t: Track) {
  await send({ type: 'play-queue', tracks: [t], index: 0 });
  try {
    const radio = await getQueue({ videoId: t.videoId });
    const rest = radio.filter((x) => x.videoId !== t.videoId);
    await send({ type: 'append', tracks: rest.slice(0, 40) });
  } catch { /* radio is a bonus */ }
}

export function TrackRow({ t, list, i, activeId, onArtist, meta, rank }: {
  t: Track; list?: Track[]; i?: number; activeId?: string | null; onArtist?: (name: string, id?: string) => void; meta?: string; rank?: number;
}) {
  const active = activeId === t.videoId;
  const play = () => (list && i != null ? send({ type: 'play-queue', tracks: list, index: i }) : playSongWithRadio(t));
  return (
    <div className={`track ${active ? 'active' : ''}`} onDoubleClick={play}>
      {rank != null && <span className="track-rank">{rank}</span>}
      <button className="track-art" onClick={play} aria-label={`Play ${t.title}`}>
        <img src={t.thumbnail} alt="" loading="lazy" />
        <span className="track-play"><Icon name={active ? 'eq' : 'play'} /></span>
      </button>
      <div className="track-meta" onClick={play}>
        <div className="track-title" title={t.title}>{t.title}</div>
        <div className="track-sub">
          <span className="link" onClick={(e) => { if (onArtist) { e.stopPropagation(); onArtist(t.artists, t.artistId); } }}>{t.artists}</span>
          {meta ? '' : t.album ? ` · ${t.album}` : ''}
        </div>
        {meta && <div className="track-meta2">{meta}</div>}
      </div>
      {t.duration && <span className="track-dur">{t.duration}</span>}
      <div className="track-actions">
        <button className="icon-btn" title="Play next" onClick={() => send({ type: 'add-next', track: t })}><Icon name="next-up" /></button>
        <button className="icon-btn" title="Add to queue" onClick={() => send({ type: 'add-queue', track: t })}><Icon name="queue-add" /></button>
      </div>
    </div>
  );
}

export function CardTile({ c, onArtist, onOpen }: { c: Card; onArtist?: (name: string, id?: string) => void; onOpen?: (c: Card) => void }) {
  const [busy, setBusy] = useState(false);
  const wide = c.kind === 'video';
  const play = async () => {
    setBusy(true);
    try {
      const tracks = await getCardTracks(c);
      if (tracks.length) await send({ type: 'play-queue', tracks, index: 0 });
    } finally { setBusy(false); }
  };
  const open = () => {
    if (c.kind === 'artist') return onArtist?.(c.title, c.browseId);
    if ((c.kind === 'album' || c.kind === 'playlist') && onOpen) return onOpen(c);
    return play();
  };
  return (
    <button className={`card ${wide ? 'wide' : ''} ${c.kind === 'artist' ? 'round' : ''}`} onClick={open} title={c.title}>
      <div className="card-art">
        <img src={c.thumbnail} alt="" loading="lazy" />
        {c.kind !== 'artist' && (
          <span className="card-play" role="button" aria-label={`Play ${c.title}`} onClick={(e) => { e.stopPropagation(); play(); }}>
            {busy ? <span className="spinner" /> : <Icon name="play" />}
          </span>
        )}
      </div>
      <div className="card-title">{c.title}</div>
      <div className="card-sub">{c.subtitle}</div>
    </button>
  );
}
