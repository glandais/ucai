import type { Classification, RaceEvent } from '../api/types';
import type { RaceMeta } from './meta';

/** One rider at race time `t`. */
export interface RiderState {
  riderId: number;
  teamId: string;
  /** Index in `meta.riders`. */
  index: number;
  /** Interpolated distance from the start, m. */
  distM: number;
  /** m/s, interpolated. */
  speed: number;
  /** Group id (≥ 1) or `FINISHED_GROUP` (0) once finished. Step value (no interpolation). */
  groupId: number;
  /** 1 = front of the group; finishing position once finished. Step value. */
  rankInGroup: number;
  finished: boolean;
  /** Finishing position, null while on the road. */
  position: number | null;
  lat: number;
  lon: number;
  eleM: number;
}

/** A group on the road at race time `t`. */
export interface GroupState {
  id: number;
  /** Rider ids, front to back (by `rankInGroup`). */
  riderIds: number[];
  size: number;
  /** Distance of the front of the group, m. */
  headDistM: number;
  /** Distance of the back of the group, m. */
  tailDistM: number;
  headRiderId: number;
  /** Course position of the head of the group. */
  lat: number;
  lon: number;
  eleM: number;
  /**
   * Time gap to the head of the race, s, the engine's `GroupSummary.gapS`: how long ago the head of the race
   * (the furthest any rider has ever been, so a caught break still counts, and the winner once someone has
   * finished) passed `headDistM`. 0 for the leading group while nobody has finished.
   */
  gapToHeadS: number;
  /** Time gap to the group just ahead, s (the same as `gapToHeadS` for the first group on the road). */
  gapToAheadS: number;
  /** (course distance − headDistM) / 1000, ≥ 0. */
  kmToGo: number;
}

/** Everything the views need at one race time. Same shape for a replay and (later) a live SSE source. */
export interface RaceSnapshot {
  /** Race time, s. */
  t: number;
  /** Every rider, in `meta.riders` order. */
  riders: RiderState[];
  /** Groups on the road, front to back (finished riders are in no group). */
  groups: GroupState[];
  /** Finished rider ids by finishing position. */
  finishedRiderIds: number[];
  /** Distance of the head of the race (the leading rider, finished or not), m. */
  headDistM: number;
  /** Km to go for the head of the race. */
  kmToGo: number;
  /** True once every rider has finished or the time is at/after the end of the source. */
  raceFinished: boolean;
}

/**
 * The data source seam (DESIGN §7): views only see a `RaceSource`. A replay file is one implementation
 * (`ReplayModel`); a live SSE source will be another, with the same shape.
 */
export interface RaceSource {
  readonly kind: 'replay' | 'live';
  readonly meta: RaceMeta;
  /** First race time available, s. */
  readonly startT: number;
  /** Last race time available, s (grows for a live source). */
  readonly endT: number;
  /** Every event known so far, sorted by tick. */
  readonly events: readonly RaceEvent[];
  /** Final (replay) or partial (live) classification, null if unknown. */
  readonly results: Classification | null;
  /** State at race time `t`, clamped to [startT, endT]. */
  snapshotAt(t: number): RaceSnapshot;
  /** Events with `tick <= t`, chronological. Returns the same array instance while the count is unchanged. */
  eventsUntil(t: number): readonly RaceEvent[];
  /** Notified when new data arrives (live); a replay never notifies. Returns an unsubscribe function. */
  subscribe(listener: () => void): () => void;
}
