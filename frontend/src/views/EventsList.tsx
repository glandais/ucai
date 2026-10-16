import type { RaceEvent } from '../api/types';
import type { RaceMeta } from '../replay/meta';
import { describeEvent } from '../replay/events';
import { formatRaceTime } from '../replay/format';

/** Events slot: events up to the playback time, most recent first (not clickable in v1). */
export interface EventsListProps {
  meta: RaceMeta;
  /** Events with `tick <= t`, chronological (same array instance while unchanged). */
  events: readonly RaceEvent[];
  /** Current race time, s. */
  t: number;
}

export function EventsList({ meta, events }: EventsListProps) {
  const recent = events.slice(-8).reverse();
  return (
    <div className="placeholder">
      <strong>Events ({events.length})</strong>
      {recent.map((e, i) => (
        <span key={events.length - i}>
          {formatRaceTime(e.tick)} {describeEvent(meta, e)}
        </span>
      ))}
    </div>
  );
}
