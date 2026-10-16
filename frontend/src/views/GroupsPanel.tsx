import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { formatGap } from '../replay/format';

/**
 * Groups slot (TV style): head of race, time gaps, riders of each group with their team color, km to go.
 * Fills its parent box (scrolls inside).
 */
export interface GroupsPanelProps {
  meta: RaceMeta;
  /** `snapshot.groups` are front to back with `gapToHeadS`, `gapToAheadS`, `kmToGo`. */
  snapshot: RaceSnapshot;
}

export function GroupsPanel({ snapshot }: GroupsPanelProps) {
  return (
    <div className="placeholder">
      <strong>Groups</strong>
      {snapshot.groups.map((g) => (
        <span key={g.id}>
          Group {g.id}: {g.size} riders {formatGap(g.gapToHeadS)} — {g.kmToGo.toFixed(1)} km to go
        </span>
      ))}
      {snapshot.finishedRiderIds.length > 0 && <span>{snapshot.finishedRiderIds.length} finished</span>}
    </div>
  );
}
