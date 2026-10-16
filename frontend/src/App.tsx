import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchReplay, loadReplayFile } from './api/client';
import type { Replay } from './api/types';
import { ReplayView } from './app/ReplayView';
import { RaceList } from './app/RaceList';
import { createReplaySource } from './replay/model';
import type { RaceSource } from './replay/source';

interface Opened {
  key: string;
  source: RaceSource;
}

const HASH_PREFIX = '#/race/';

/** Race list (kernel races + local file) → replay view. `#/race/<raceId>` opens a kernel replay directly. */
export function App() {
  const [opened, setOpened] = useState<Opened | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const open = useCallback(async (key: string, load: () => Promise<Replay>) => {
    setBusy(true);
    setError(null);
    try {
      const replay = await load();
      setOpened({ key, source: createReplaySource(replay) });
    } catch (e) {
      setError(`Could not open the replay: ${e instanceof Error ? e.message : String(e)}`);
      if (location.hash.startsWith(HASH_PREFIX)) history.replaceState(null, '', location.pathname);
    } finally {
      setBusy(false);
    }
  }, []);

  const openRace = useCallback(
    (raceId: string) => {
      history.replaceState(null, '', HASH_PREFIX + encodeURIComponent(raceId));
      void open(`race:${raceId}`, () => fetchReplay(raceId));
    },
    [open],
  );

  const fromHash = useRef(false);
  useEffect(() => {
    if (fromHash.current) return; // StrictMode runs effects twice
    fromHash.current = true;
    if (location.hash.startsWith(HASH_PREFIX)) openRace(decodeURIComponent(location.hash.slice(HASH_PREFIX.length)));
  }, [openRace]);

  const back = () => {
    history.replaceState(null, '', location.pathname);
    setOpened(null);
  };

  if (opened) return <ReplayView key={opened.key} source={opened.source} onBack={back} />;
  return (
    <RaceList
      onOpenRace={openRace}
      onOpenFile={(file) => void open(`file:${file.name}:${file.lastModified}`, () => loadReplayFile(file))}
      error={error}
      busy={busy}
    />
  );
}
