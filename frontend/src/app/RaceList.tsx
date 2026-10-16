import { useEffect, useState, type ChangeEvent } from 'react';
import { listRaces } from '../api/client';
import type { RaceSummary } from '../api/types';

export interface RaceListProps {
  onOpenRace: (raceId: string) => void;
  onOpenFile: (file: File) => void;
  /** Error from the last open attempt. */
  error: string | null;
  busy: boolean;
}

type Load = { status: 'loading' } | { status: 'ok'; races: RaceSummary[] } | { status: 'error'; message: string };

export function RaceList({ onOpenRace, onOpenFile, error, busy }: RaceListProps) {
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoad({ status: 'loading' });
    listRaces(ctrl.signal)
      .then((races) => setLoad({ status: 'ok', races }))
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted) setLoad({ status: 'error', message: e instanceof Error ? e.message : String(e) });
      });
    return () => ctrl.abort();
  }, [attempt]);

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) onOpenFile(file);
    e.target.value = '';
  };

  return (
    <div className="race-list">
      <h1>
        UCAI <span>race replays</span>
      </h1>
      {error && <p className="error">{error}</p>}
      {busy && <p className="muted">Loading replay…</p>}
      <section>
        <h2>Races on the kernel</h2>
        {load.status === 'loading' && <p className="muted">Loading races…</p>}
        {load.status === 'error' && (
          <p className="error">
            Kernel unreachable ({load.message}).{' '}
            <button type="button" onClick={() => setAttempt((a) => a + 1)}>
              Retry
            </button>
          </p>
        )}
        {load.status === 'ok' && load.races.length === 0 && <p className="muted">No race yet.</p>}
        {load.status === 'ok' && load.races.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Race</th>
                <th>Course</th>
                <th>Teams</th>
                <th>State</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {load.races.map((r) => (
                <tr key={r.id}>
                  <td>{r.id}</td>
                  <td>{r.courseName}</td>
                  <td>{r.teamCount}</td>
                  <td>{r.state}</td>
                  <td>
                    <button type="button" disabled={busy || r.state !== 'finished'} onClick={() => onOpenRace(r.id)}>
                      Watch replay
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section>
        <h2>Local replay file</h2>
        <label className="file-button">
          Open a local file (.json or .json.gz)
          <input type="file" accept=".json,.gz,application/json,application/gzip" onChange={onFile} disabled={busy} />
        </label>
      </section>
    </div>
  );
}
