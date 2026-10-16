import { useEffect, useState } from 'react';
import type { PlaybackClock } from './clock';
import type { RaceSource } from './source';

/**
 * Wires a source to a clock: on each source notification (live data), the clock range follows
 * `source.startT/endT` and `onData` runs. Also syncs once right away, in case the source grew before
 * the subscription. Returns the unsubscribe function.
 */
export function connectSource(source: RaceSource, clock: PlaybackClock, onData: () => void): () => void {
  const syncRange = () => {
    const s = clock.getState();
    if (s.startT !== source.startT || s.endT !== source.endT) clock.setRange(source.startT, source.endT);
  };
  syncRange();
  return source.subscribe(() => {
    syncRange();
    onData();
  });
}

/**
 * Subscribes to a source for the life of the component; returns a data version that changes on each
 * notification, to add to the deps of anything derived from the source (snapshot, events, results).
 */
export function useSourceVersion(source: RaceSource, clock: PlaybackClock): number {
  const [version, setVersion] = useState(0);
  useEffect(() => connectSource(source, clock, () => setVersion((v) => v + 1)), [source, clock]);
  return version;
}
