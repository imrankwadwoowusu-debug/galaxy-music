import { memo, useState } from 'react';
import type { Card, Track } from '../../lib/types';
import { getCardTracks, getQueue } from '../../lib/innertube';
import { send } from '../hooks';
import { Icon } from './Icon';

// Play a single song: start it now, then fill the queue with its radio.
// Every new queue bumps this, so a slow radio for an older song is dropped.
let queueSeq = 0;
export function playQueue(tracks: Track[], index: number) {
  queueSeq++;
  return send({ type: 'play-queue', tracks, index });
}
export async function playSongWithRadio(t: Track) {
  // Fetch the radio while the song starts, instead of after its stream resolves.
  const started = playQueue([t], 0);
  const seq = queueSeq;
  const radioP = getQueue({ videoId: t.videoId }).catch(() => [] as Track[]);
  try {
    await started;
    const radio = await radioP;
    if (seq !== queueSeq) return; // the user started something else meanwhile
    const rest = radio.filter((x) => x.videoId !== t.videoId);
    await send({ type: 'append', tracks: rest.slice(0, 40) });
  } catch { /* radio is a bonus */ }
}

export const TrackRow = memo(function TrackRow({ t, list, i, activeId, onArtist, meta, rank }: {
  t: Track; list?: Track[]; i?: number; activeId?: string | null; onArtist?: (name: string, id?: string) => void; meta?: string; rank?: number;
}) {
  const active = activeId === t.videoId;
  // One activation per gesture: the second click of a double-click is ignored.
  const play = (e?: React.MouseEvent) => {
    if (e && e.detail > 1) return;
    return list && i != null ? playQueue(list, i) : playSongWithRadio(t);
  };
  return (
    <div className={`track ${active ? 'active' : ''}`}>
      {rank != null && <span className="track-rank">{rank}</span>}
      <button className="track-art" onClick={play} aria-label={`Play ${t.title}`}>
        <img src={t.thumbnail} alt="" loading="lazy" />
        <span className="track-play"><Icon name={active ? 'eq' : 'play'} /></span>
      </button>
      <div className="track-meta" onClick={play}>
        <div className="track-title" title={t.title}>{t.title}</div>
        <div className="track-sub">
          {onArtist
            ? <button type="button" className="link" onClick={(e) => { e.stopPropagation(); onArtist(t.artists, t.artistId); }}>{t.artists}</button>
            : <span>{t.artists}</span>}
          {meta ? '' : t.album ? ` · ${t.album}` : ''}
        </div>
        {meta && <div className="track-meta2">{meta}</div>}
      </div>
      {t.duration && <span className="track-dur">{t.duration}</span>}
      <div className="track-actions" onClick={(e) => e.stopPropagation()}>
        <button className="icon-btn" title="Play next" onClick={() => send({ type: 'add-next', track: t })}><Icon name="next-up" /></button>
        <button className="icon-btn" title="Add to queue" onClick={() => send({ type: 'add-queue', track: t })}><Icon name="queue-add" /></button>
      </div>
    </div>
  );
});

export const CardTile = memo(function CardTile({ c, onArtist, onOpen }: { c: Card; onArtist?: (name: string, id?: string) => void; onOpen?: (c: Card) => void }) {
  const [busy, setBusy] = useState(false);
  const wide = c.kind === 'video';
  const play = async () => {
    setBusy(true);
    try {
      const tracks = await getCardTracks(c);
      if (tracks.length) await playQueue(tracks, 0);
    } finally { setBusy(false); }
  };
  const open = () => {
    if (c.kind === 'artist') return onArtist?.(c.title, c.browseId);
    if ((c.kind === 'album' || c.kind === 'playlist') && onOpen) return onOpen(c);
    return play();
  };
  return (
    // The play control is a sibling of the open button (no button inside a button).
    <div className={`card ${wide ? 'wide' : ''} ${c.kind === 'artist' ? 'round' : ''}`} title={c.title}>
      <button type="button" className="card-hit" onClick={open} aria-label={c.title}>
        <div className="card-art"><img src={c.thumbnail} alt="" loading="lazy" /></div>
        <div className="card-title">{c.title}</div>
        <div className="card-sub">{c.subtitle}</div>
      </button>
      {c.kind !== 'artist' && (
        <button type="button" className="card-play" aria-label={`Play ${c.title}`} onClick={(e) => { e.stopPropagation(); if (!busy) play(); }}>
          {busy ? <span className="spinner" /> : <Icon name="play" />}
        </button>
      )}
    </div>
  );
});
