// Data models, ported in spirit from BitChord shared/commonMain data/model/Models.kt

export interface Track {
  videoId: string;
  title: string;
  artists: string;
  album?: string;
  thumbnail: string;
  duration?: string; // "3:24"
}

export type CardKind = 'album' | 'playlist' | 'artist' | 'video' | 'song';

export interface Card {
  kind: CardKind;
  title: string;
  subtitle: string;
  thumbnail: string;
  videoId?: string;
  playlistId?: string;
  browseId?: string;
}

export interface MoodChip {
  title: string;
  color: string;
  browseId: string;
  params: string;
}

export interface Shelf {
  title: string;
  tracks: Track[];
  cards: Card[];
}

export interface PlayerState {
  videoId: string | null;
  track: Track | null;
  playing: boolean;
  position: number; // seconds
  duration: number; // seconds
  volume: number; // 0..100
  helperReady: boolean;
  needsActivation: boolean; // autoplay blocked in helper tab
  queue: Track[];
  index: number;
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  engine: 'direct' | 'tab' | null; // offscreen <audio> or helper YouTube Music tab
  stream: string | null; // "Stats for nerds": e.g. "JioSaavn · 320 kbps AAC"
  resolving: boolean;
  error: string | null;
}

export type SourceOrder = 'saavn-first' | 'youtube-first' | 'youtube-only' | 'tab-only';
export type DirectSource = 'jiosaavn' | 'youtube';
export type AccountId = 'youtube-music' | 'google' | 'apple-music';
export type Quality = 'high' | 'normal' | 'low';
export type Theme = 'system' | 'light' | 'dark';
export interface Settings {
  // Sources (BitChord's "Sources, tried in this order")
  sources: DirectSource[]; // order
  jiosaavn: boolean; // enabled
  tabFallback: boolean; // YouTube Music tab as last resort
  quality: Quality;
  // Accounts
  useAccount: boolean;
  defaultAccount: AccountId;
  authUser: number;
  listenbrainzToken: string;
  // Playback
  autoRadio: boolean;
  hideVolume: boolean;
  // Appearance
  theme: Theme;
  reduceMotion: boolean;
  reduceBlur: boolean;
  syncedLyrics: boolean;
  blurLyrics: boolean;
  // Advanced
  showStats: boolean;
  lyricsDebug: boolean;
  /** @deprecated v0.2 setting, migrated into sources/jiosaavn/tabFallback */
  sourceOrder?: SourceOrder;
}
export const DEFAULT_SETTINGS: Settings = {
  sources: ['jiosaavn', 'youtube'], jiosaavn: true, tabFallback: true, quality: 'high',
  useAccount: true, defaultAccount: 'youtube-music', authUser: 0, listenbrainzToken: '',
  autoRadio: true, hideVolume: false,
  theme: 'dark', reduceMotion: false, reduceBlur: false, syncedLyrics: true, blurLyrics: true,
  showStats: false, lyricsDebug: false,
};

export interface HomeChip { title: string; params?: string }
export interface Suggestion { kind: 'query' | 'song' | 'artist' | 'album' | 'playlist' | 'video'; text: string; sub?: string; thumbnail?: string; track?: Track; card?: Card }

// Messages between side panel <-> background <-> helper tab
export type UiCommand =
  | { type: 'get-state' }
  | { type: 'play-queue'; tracks: Track[]; index: number }
  | { type: 'play-index'; index: number }
  | { type: 'add-next'; track: Track }
  | { type: 'add-queue'; track: Track }
  | { type: 'append'; tracks: Track[] }
  | { type: 'remove-index'; index: number }
  | { type: 'toggle' }
  | { type: 'next' }
  | { type: 'prev' }
  | { type: 'seek'; seconds: number }
  | { type: 'volume'; value: number }
  | { type: 'shuffle' }
  | { type: 'repeat' }
  | { type: 'activate-helper' }
  | { type: 'open-tab' };

export type PageCommand =
  | { cmd: 'load'; videoId: string }
  | { cmd: 'play' }
  | { cmd: 'pause' }
  | { cmd: 'toggle' }
  | { cmd: 'seek'; seconds: number }
  | { cmd: 'volume'; value: number };

export interface PageState {
  videoId: string | null;
  state: number; // YT player state: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
  position: number;
  duration: number;
  volume: number;
  title?: string;
  author?: string;
  /** an ad is on screen (the helper tab skips it) */
  ad?: boolean;
}
