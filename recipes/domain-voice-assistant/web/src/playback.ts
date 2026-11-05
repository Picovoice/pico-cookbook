import { OrcaStreamWorker } from '@picovoice/orca-web';

import { AudioStream } from './audio_stream';

const PLAYBACK_START_BUFFER_SEC = 0.5;
const PLAYBACK_CHUNK_SEC = 2.0;
const PLAYBACK_REFILL_SEC = 0.5;

type Player = {
  audioStream: AudioStream;
  sampleRate: number;
  onSpeaking: () => void;
};

type Segment = {
  text: string;
  synthesizedSamples: number;
  isComplete: boolean;
};

type Turn = {
  question: string;
  segments: Segment[];
  stream: OrcaStreamWorker | null;
  audio: Int16Array[];
  bufferedSamples: number;
  handedChunks: number;
  handedSamples: number;
  playbackEndMs: number;
  isPlaying: boolean;
  isPaused: boolean;
  isTextDone: boolean;
  isInterrupted: boolean;
  resumeOffset: number;
  feedTimeout: ReturnType<typeof setTimeout> | null;
};

const createTurn = (question: string): Turn => ({
  question,
  segments: [],
  stream: null,
  audio: [],
  bufferedSamples: 0,
  handedChunks: 0,
  handedSamples: 0,
  playbackEndMs: 0,
  isPlaying: false,
  isPaused: false,
  isTextDone: false,
  isInterrupted: false,
  resumeOffset: 0,
  feedTimeout: null,
});

const heardSamples = (current: Turn, player: Player): number => {
  const leftMs = Math.max(0, current.playbackEndMs - performance.now());
  return Math.round(current.handedSamples - (leftMs / 1000) * player.sampleRate);
};

const spokenText = (current: Turn, player: Player): string => {
  let heard = heardSamples(current, player);

  const parts: string[] = [];
  for (const segment of current.segments) {
    if (segment.isComplete && heard >= segment.synthesizedSamples) {
      parts.push(segment.text);
      heard -= segment.synthesizedSamples;
      continue;
    }
    if (segment.synthesizedSamples > 0 && heard > 0) {
      const words = segment.text.trim().split(/\s+/);
      const ratio = heard / segment.synthesizedSamples;
      parts.push(words.slice(0, Math.floor(words.length * ratio)).join(' '));
    }
    break;
  }
  return parts.join('').replace(/\s+/g, ' ').trim();
};

const clearFeedTimeout = (current: Turn): void => {
  if (current.feedTimeout !== null) {
    clearTimeout(current.feedTimeout);
    current.feedTimeout = null;
  }
};

const feedPlayer = (current: Turn, player: Player): void => {
  const { audioStream, sampleRate } = player;
  clearFeedTimeout(current);

  const pending = current.audio.slice(current.handedChunks);
  if (pending.length === 0) {
    return;
  }
  pending[0] = pending[0].subarray(current.resumeOffset);
  const length = pending.reduce((sum, chunk) => sum + chunk.length, 0);

  const leftMs = current.playbackEndMs - performance.now();
  const refillMs = PLAYBACK_REFILL_SEC * 1000;
  if (!current.isTextDone && length < sampleRate * PLAYBACK_CHUNK_SEC && leftMs > refillMs) {
    current.feedTimeout = setTimeout(() => {
      current.feedTimeout = null;
      if (!current.isInterrupted && !current.isPaused) {
        feedPlayer(current, player);
      }
    }, leftMs - refillMs);
    return;
  }

  const pcm = new Int16Array(length);
  let offset = 0;
  for (const chunk of pending) {
    pcm.set(chunk, offset);
    offset += chunk.length;
  }
  audioStream.stream(pcm);
  current.playbackEndMs = Math.max(current.playbackEndMs, performance.now()) + (length / sampleRate) * 1000;
  current.handedSamples += length;
  current.handedChunks = current.audio.length;
  current.resumeOffset = 0;
  audioStream.play();
};

const startPlayback = (current: Turn, player: Player): void => {
  if (current.isPaused) {
    return;
  }
  if (current.isPlaying || current.isTextDone || current.bufferedSamples >= player.sampleRate * PLAYBACK_START_BUFFER_SEC) {
    if (!current.isPlaying) {
      current.isPlaying = true;
      player.onSpeaking();
    }
    feedPlayer(current, player);
  }
};

const pausePlayback = (current: Turn, player: Player): void => {
  if (current.isPaused) {
    return;
  }
  current.isPaused = true;
  clearFeedTimeout(current);
  if (!current.isPlaying) {
    return;
  }

  const { audioStream } = player;
  const heard = heardSamples(current, player);
  audioStream.clear();
  audioStream.waitPlayback(() => true);

  let start = 0;
  let chunks = 0;
  while (chunks < current.audio.length && start + current.audio[chunks].length <= heard) {
    start += current.audio[chunks].length;
    chunks++;
  }
  current.handedChunks = chunks;
  current.resumeOffset = heard - start;
  current.handedSamples = heard;
  current.playbackEndMs = performance.now();
};

const resumePlayback = (current: Turn, player: Player): void => {
  if (!current.isPaused) {
    return;
  }
  current.isPaused = false;
  startPlayback(current, player);
};

const playPcm = (current: Turn, segment: Segment, pcm: Int16Array | null, player: Player): void => {
  if (pcm === null || current.isInterrupted) {
    return;
  }

  segment.synthesizedSamples += pcm.length;
  current.audio.push(pcm);
  current.bufferedSamples += pcm.length;
  startPlayback(current, player);
};

export {
  Player,
  Segment,
  Turn,
  createTurn,
  spokenText,
  clearFeedTimeout,
  startPlayback,
  pausePlayback,
  resumePlayback,
  playPcm,
};
