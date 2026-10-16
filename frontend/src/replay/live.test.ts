import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../api/decode';
import { PlaybackClock } from './clock';
import { syntheticReplayWire } from './fixtures';
import { connectSource } from './live';
import { createReplaySource } from './model';
import type { RaceSource } from './source';

/** A replay source that pretends to be live: its end grows and it notifies on demand. */
function fakeLiveSource() {
  const base = createReplaySource(decodeReplay(syntheticReplayWire()));
  const listeners = new Set<() => void>();
  let endT = base.startT + 100;
  const source: RaceSource = {
    kind: 'live',
    meta: base.meta,
    get startT() {
      return base.startT;
    },
    get endT() {
      return endT;
    },
    get events() {
      return base.events;
    },
    get results() {
      return base.results;
    },
    snapshotAt: (t) => base.snapshotAt(Math.min(t, endT)),
    eventsUntil: (t) => base.eventsUntil(Math.min(t, endT)),
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
  };
  const grow = (to: number) => {
    endT = to;
    for (const l of listeners) l();
  };
  return { source, grow, listeners };
}

describe('live source wiring', () => {
  it('follows the source range and reports each notification', () => {
    const { source, grow, listeners } = fakeLiveSource();
    const clock = new PlaybackClock(source.startT, source.startT + 50);
    let notified = 0;
    const off = connectSource(source, clock, () => notified++);
    expect(clock.getState().endT).toBe(source.startT + 100); // synced on connect

    clock.seek(source.startT + 1000);
    expect(clock.getState().t).toBe(source.startT + 100);
    grow(source.startT + 300);
    expect(notified).toBe(1);
    expect(clock.getState().endT).toBe(source.startT + 300);
    clock.seek(source.startT + 250);
    expect(clock.getState().t).toBe(source.startT + 250);

    grow(source.startT + 300); // new data at an unchanged range still notifies
    expect(notified).toBe(2);

    off();
    expect(listeners.size).toBe(0);
  });
});
