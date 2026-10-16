import type { Classification } from '../api/types';
import type { RaceMeta } from '../replay/meta';
import { formatGap, formatRaceTime } from '../replay/format';
import { riderName } from '../replay/meta';

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
  const rows = (results?.entries ?? []).filter((e) => e.position != null && (e.timeS ?? Infinity) <= t).slice(0, 10);
  return (
    <div className="placeholder">
      <strong>Results</strong>
      {rows.map((e) => (
        <span key={e.riderId}>
          {e.position}. {riderName(meta, e.riderId)} {e.gapS ? formatGap(e.gapS) : formatRaceTime(e.timeS ?? 0)}
        </span>
      ))}
      <button type="button" onClick={onClose}>
        Close
      </button>
    </div>
  );
}
