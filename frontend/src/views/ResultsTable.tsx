import { useEffect, useMemo, useRef } from 'react';
import type { Classification } from '../api/types';
import type { RaceMeta } from '../replay/meta';
import { rider, riderColor, riderName, team } from '../replay/meta';
import { ARCHETYPE_LABELS, resultRows } from './panels/results';
import './ResultsTable.css';

/**
 * Results slot: the classification, shown over the map at the end of the race (or on demand).
 * A rider is only shown once finished at `t` (`timeS <= t`), so scrubbing back hides later finishers.
 */
export interface ResultsTableProps {
  meta: RaceMeta;
  results: Classification | null;
  /** Current race time, s. */
  t: number;
  /** Closes the overlay. */
  onClose: () => void;
}

export function ResultsTable({ meta, results, t, onClose }: ResultsTableProps) {
  const { rows, shown, total, complete } = useMemo(() => resultRows(results, t), [results, t]);

  // Escape closes (the latest onClose, without re-binding on every frame).
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close.current();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div className="results">
      <div className="results-head">
        <div>
          <div className="results-title">{meta.course.name}</div>
          <div className="results-sub">
            {complete ? 'Final classification' : results ? `Provisional classification · ${shown} of ${total} finished` : 'No classification available'}
          </div>
        </div>
        <button type="button" className="results-close" onClick={onClose} aria-label="Close results" title="Close (Esc)">
          ✕
        </button>
      </div>
      {rows.length === 0 ? (
        <p className="results-empty">{results ? 'No rider has finished yet.' : 'This race has no results.'}</p>
      ) : (
        <div className="results-body">
          <table className="results-table">
            <thead>
              <tr>
                <th className="num">Pos</th>
                <th>Rider</th>
                <th>Team</th>
                <th className="num">Time</th>
                <th className="num">Gap</th>
                <th>Type</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const p = rider(meta, r.riderId);
                return (
                  <tr key={r.riderId} className={r.position == null ? 'dnf' : r.position <= 3 ? `podium p${r.position}` : undefined}>
                    <td className="num pos">{r.position ?? '–'}</td>
                    <td className="rider">
                      <span className="results-swatch" style={{ background: riderColor(meta, r.riderId) }} />
                      {riderName(meta, r.riderId)}
                    </td>
                    <td className="team">{team(meta, r.teamId)?.name ?? r.teamId}</td>
                    <td className="num">{r.time}</td>
                    <td className="num gap">{r.gap}</td>
                    <td className="type">{p ? ARCHETYPE_LABELS[p.archetype] : ''}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
