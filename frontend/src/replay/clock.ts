import { useEffect, useState, useSyncExternalStore } from 'react';

export const PLAYBACK_SPEEDS = [1, 10, 30, 60] as const;
export type PlaybackSpeed = (typeof PLAYBACK_SPEEDS)[number];

export interface PlaybackState {
  /** Current race time, s. */
  t: number;
  playing: boolean;
  speed: PlaybackSpeed;
  startT: number;
  endT: number;
}

/**
 * Playback clock: a tiny external store (for `useSyncExternalStore`). Race time advances by
 * `wall seconds × speed` while playing, driven by `requestAnimationFrame` in the browser, or by
 * `advance()` (tests). Stops at `endT`.
 */
export class PlaybackClock {
  private state: PlaybackState;
  private readonly listeners = new Set<() => void>();
  private raf: number | null = null;
  private lastWall: number | null = null;

  constructor(startT: number, endT: number, speed: PlaybackSpeed = 10) {
    this.state = { t: startT, playing: false, speed, startT, endT };
  }

  getState = (): PlaybackState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private set(patch: Partial<PlaybackState>): void {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }

  play = (): void => {
    if (this.state.playing) return;
    const t = this.state.t >= this.state.endT ? this.state.startT : this.state.t; // replay from the start
    this.set({ playing: true, t });
    this.startLoop();
  };

  pause = (): void => {
    if (!this.state.playing) return;
    this.stopLoop();
    this.set({ playing: false });
  };

  toggle = (): void => (this.state.playing ? this.pause() : this.play());

  setSpeed = (speed: PlaybackSpeed): void => this.set({ speed });

  seek = (t: number): void => this.set({ t: this.clamp(t) });

  /** Changes the available range (live source growing); keeps `t` inside it. */
  setRange = (startT: number, endT: number): void => this.set({ startT, endT, t: Math.min(endT, Math.max(startT, this.state.t)) });

  /** Advances race time by `wallSeconds × speed` if playing; pauses at the end. */
  advance = (wallSeconds: number): void => {
    if (!this.state.playing || wallSeconds <= 0) return;
    const t = this.clamp(this.state.t + wallSeconds * this.state.speed);
    if (t >= this.state.endT) {
      this.stopLoop();
      this.set({ t, playing: false });
    } else this.set({ t });
  };

  private clamp(t: number): number {
    return Math.min(this.state.endT, Math.max(this.state.startT, t));
  }

  private startLoop(): void {
    if (typeof requestAnimationFrame === 'undefined' || this.raf !== null) return;
    this.lastWall = null;
    const step = (now: number) => {
      if (this.lastWall !== null) this.advance(Math.min(0.25, (now - this.lastWall) / 1000)); // cap tab-switch jumps
      this.lastWall = now;
      if (this.state.playing) this.raf = requestAnimationFrame(step);
      else this.raf = null;
    };
    this.raf = requestAnimationFrame(step);
  }

  private stopLoop(): void {
    if (this.raf !== null && typeof cancelAnimationFrame !== 'undefined') cancelAnimationFrame(this.raf);
    this.raf = null;
    this.lastWall = null;
  }
}

/**
 * Creates a clock for [startT, endT] once per component instance (key the component by race to get a new
 * clock) and pauses it on unmount.
 */
export function usePlaybackClock(startT: number, endT: number): PlaybackClock {
  const [clock] = useState(() => new PlaybackClock(startT, endT));
  useEffect(() => () => clock.pause(), [clock]);
  return clock;
}

/** Subscribes to a clock's state. */
export function usePlayback(clock: PlaybackClock): PlaybackState {
  return useSyncExternalStore(clock.subscribe, clock.getState, clock.getState);
}
