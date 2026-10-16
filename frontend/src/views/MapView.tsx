import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';

/**
 * Map slot (DESIGN §7): course line, climbs marked, one dot per rider colored by team, group labels.
 * MapLibre GL + OpenFreeMap (`https://tiles.openfreemap.org/styles/liberty`). Fills its parent box.
 */
export interface MapViewProps {
  meta: RaceMeta;
  /** State at the current playback time; changes every animation frame while playing. */
  snapshot: RaceSnapshot;
}

export function MapView({ meta, snapshot }: MapViewProps) {
  const lead = snapshot.groups[0];
  return (
    <div className="placeholder">
      <strong>Map</strong>
      <span>
        {meta.course.name}: {meta.course.points.length} points, {snapshot.riders.length} riders
      </span>
      {lead && (
        <span>
          Head of race at {lead.lat.toFixed(4)}, {lead.lon.toFixed(4)}
        </span>
      )}
    </div>
  );
}
