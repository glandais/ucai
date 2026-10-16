import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { formatKm } from '../replay/format';

/**
 * Elevation profile slot (canvas): the course profile, climbs shaded, groups as markers — the race's
 * "TV graphic". Fills its parent box.
 */
export interface ProfileViewProps {
  meta: RaceMeta;
  snapshot: RaceSnapshot;
}

export function ProfileView({ meta, snapshot }: ProfileViewProps) {
  return (
    <div className="placeholder">
      <strong>Profile</strong>
      <span>
        {formatKm(meta.course.distanceM)}, {Math.round(meta.course.elevationGainM)} m gain, {meta.climbs.length} climbs —
        head at {formatKm(snapshot.headDistM)}
      </span>
    </div>
  );
}
