import { useEffect, useRef, useState } from 'react';
import type { RaceMeta } from '../replay/meta';
import { riderShortName } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { followedGroup, FOLLOW_LEAD, FOLLOW_NONE, type FollowTarget } from './map/labels';
import { pelotonId } from '../replay/peloton';
import { RaceMap } from './map/RaceMap';
import './map/MapView.css';

/**
 * Map slot (DESIGN §7): course line, climbs marked, one dot per rider colored by team, group labels.
 * MapLibre GL + OpenFreeMap (`https://tiles.openfreemap.org/styles/liberty`). Fills its parent box.
 * React only renders the container and the camera buttons; `RaceMap` pushes each snapshot to MapLibre.
 */
export interface MapViewProps {
  meta: RaceMeta;
  /** State at the current playback time; changes every animation frame while playing. */
  snapshot: RaceSnapshot;
}

export function MapView({ meta, snapshot }: MapViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<RaceMap | null>(null);
  const snapshotRef = useRef(snapshot);
  snapshotRef.current = snapshot;
  // TV style: the camera follows the head of the race until the user picks something else.
  const [follow, setFollow] = useState<FollowTarget>(FOLLOW_LEAD);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const map = new RaceMap(el, meta, {
      onSelectRider: (riderId) => setFollow({ kind: 'rider', riderId }),
      onUserMove: () => setFollow(FOLLOW_NONE),
    });
    mapRef.current = map;
    map.update(snapshotRef.current);
    return () => {
      mapRef.current = null;
      map.destroy();
    };
  }, [meta]);

  useEffect(() => {
    mapRef.current?.update(snapshot);
  }, [snapshot]);

  useEffect(() => {
    mapRef.current?.setFollow(follow);
  }, [follow]);

  const followed = followedGroup(snapshot, follow);
  const leadActive = follow.kind === 'lead';
  let followLabel: string | null = null;
  if (follow.kind === 'rider') {
    if (!followed) followLabel = `${riderShortName(meta, follow.riderId)} (finished)`;
    else if (followed.size === 1) followLabel = riderShortName(meta, followed.headRiderId);
    else if (followed.id === pelotonId(snapshot.groups)) followLabel = `Peloton (${followed.size})`;
    else followLabel = `Group of ${riderShortName(meta, follow.riderId)} (${followed.size})`;
  }

  return (
    <div className="mapview">
      <div ref={containerRef} className="mapview-map" />
      <div className="mapview-controls">
        <button
          type="button"
          onClick={() => {
            setFollow(FOLLOW_NONE);
            mapRef.current?.fitCourse();
          }}
        >
          Whole course
        </button>
        <button type="button" className={leadActive ? 'active' : ''} aria-pressed={leadActive} onClick={() => setFollow(leadActive ? FOLLOW_NONE : FOLLOW_LEAD)}>
          Follow leaders
        </button>
        {followLabel && (
          <span className="mapview-following">
            <span className="muted">Following</span> {followLabel}
            <button type="button" aria-label="Stop following" onClick={() => setFollow(FOLLOW_NONE)}>
              ×
            </button>
          </span>
        )}
        {follow.kind === 'none' && <span className="mapview-hint">Click a group label to follow it</span>}
      </div>
    </div>
  );
}
