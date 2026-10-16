/**
 * TypeScript mirror of the api DTOs (api/src/commonMain/kotlin/ucai/api/), limited to what the public
 * frontend reads. JSON shapes are the source of truth; compact arrays are typed as tuples and decoded in
 * `decode.ts`.
 */

// --- Courses -----------------------------------------------------------------------------------------

export interface CourseSummary {
  id: string;
  name: string;
  distanceM: number;
  elevationGainM: number;
  startName?: string;
  finishName?: string;
}

/** Wire form of a course point: `[lat, lon, eleM, distM]`. */
export type CoursePointTuple = [lat: number, lon: number, eleM: number, distM: number];

export interface CoursePoint {
  lat: number;
  lon: number;
  eleM: number;
  distM: number;
}

/** A named climb; `endM` is the summit. Grades are fractions (0.037 = 3.7 %). */
export interface Climb {
  name: string;
  startM: number;
  endM: number;
  avgGrade: number;
  maxGrade: number;
}

export interface CourseDetailWire extends CourseSummary {
  points: CoursePointTuple[];
  climbs?: Climb[];
}

/** Decoded course: points as objects, sorted by `distM`. */
export interface CourseDetail extends CourseSummary {
  points: CoursePoint[];
  climbs: Climb[];
}

// --- Races, teams, riders ----------------------------------------------------------------------------

export type RaceState = 'registration' | 'running' | 'finished';
export type ClockMode = 'lockstep' | 'realtime';

export interface Wind {
  /** Direction it blows from, degrees, 0 = north, clockwise. */
  fromDeg: number;
  /** m/s */
  speed: number;
}

/** `color` is a CSS hex color such as `#d62728`. */
export interface TeamInfo {
  id: string;
  name: string;
  color: string;
}

export interface RaceSummary {
  id: string;
  courseId: string;
  courseName: string;
  state: RaceState;
  teamCount: number;
  startedAt?: string;
}

export type Archetype = 'climber' | 'puncheur' | 'sprinter' | 'rouleur' | 'all_rounder' | 'domestique';

/** Coarse public ratings, 1..5. */
export interface RiderRatings {
  climbing: number;
  punch: number;
  sprint: number;
  endurance: number;
  descending: number;
}

export interface PublicRider {
  id: number;
  name: string;
  teamId: string;
  archetype: Archetype;
  ratings: RiderRatings;
}

export interface RaceDetail {
  id: string;
  courseId: string;
  courseName: string;
  state: RaceState;
  clock: { mode: ClockMode };
  wind?: Wind;
  teams: TeamInfo[];
  riders: PublicRider[];
  tick: number;
  startedAt?: string;
  seed?: number;
}

// --- Events ------------------------------------------------------------------------------------------

interface EventBase {
  /** Race time in seconds (= tick). */
  tick: number;
}

export interface RaceStartedEvent extends EventBase {
  type: 'race_started';
}
export interface AttackEvent extends EventBase {
  type: 'attack';
  riderId: number;
  fromGroupId: number;
  distM: number;
}
/** `riderIds` left `groupId` and now form `newGroupId`, `gapS` behind (negative = ahead). */
export interface GroupSplitEvent extends EventBase {
  type: 'group_split';
  groupId: number;
  newGroupId: number;
  riderIds: number[];
  gapS: number;
}
/** `groupId` was caught by (merged into) `intoGroupId`. */
export interface GroupMergeEvent extends EventBase {
  type: 'group_merge';
  groupId: number;
  intoGroupId: number;
  riderIds: number[];
}
export interface RiderDroppedEvent extends EventBase {
  type: 'rider_dropped';
  riderId: number;
  fromGroupId: number;
  distM: number;
}
/** `riderIds` in passage order at the top (first riders only). */
export interface ClimbSummitEvent extends EventBase {
  type: 'climb_summit';
  climbName: string;
  distM: number;
  riderIds: number[];
}
/** The head of the race passes 50, 20, 10, 5 and 1 km to go. */
export interface KmToGoEvent extends EventBase {
  type: 'km_to_go';
  km: number;
  leadGroupId: number;
}
export interface RiderFinishedEvent extends EventBase {
  type: 'rider_finished';
  riderId: number;
  position: number;
  timeS: number;
}
export interface RaceFinishedEvent extends EventBase {
  type: 'race_finished';
  winnerRiderId: number;
  timeS: number;
}

export type RaceEvent =
  | RaceStartedEvent
  | AttackEvent
  | GroupSplitEvent
  | GroupMergeEvent
  | RiderDroppedEvent
  | ClimbSummitEvent
  | KmToGoEvent
  | RiderFinishedEvent
  | RaceFinishedEvent;

export type RaceEventType = RaceEvent['type'];

// --- Results -----------------------------------------------------------------------------------------

export type RiderStatus = 'racing' | 'dropped' | 'finished' | 'abandoned';

/** Finished riders have `position`, `timeS` and `gapS` (group time); others only a `status`. */
export interface ResultEntry {
  riderId: number;
  teamId: string;
  status: RiderStatus;
  position?: number;
  timeS?: number;
  gapS?: number;
}

export interface Classification {
  raceId: string;
  final: boolean;
  entries: ResultEntry[];
}

// --- Replay ------------------------------------------------------------------------------------------

/**
 * Wire form of a frame rider: `[distM, speed, groupId, rankInGroup]`. A finished rider has speed 0,
 * groupId `FINISHED_GROUP` and rankInGroup = finishing position. Extra trailing elements are allowed.
 */
export type FrameRiderTuple = [distM: number, speed: number, groupId: number, rankInGroup: number, ...rest: number[]];

/** `groupId` of a rider who has finished (group ids start at 1). */
export const FINISHED_GROUP = 0;

export interface FrameRider {
  distM: number;
  speed: number;
  groupId: number;
  rankInGroup: number;
}

/** `t` is race time in seconds; `r` has one entry per replay rider, in `Replay.riders` order. */
export interface ReplayFrame {
  t: number;
  r: FrameRiderTuple[];
}

export interface ReplayWire {
  version: number;
  race: RaceDetail;
  course: CourseDetailWire;
  teams: TeamInfo[];
  riders: PublicRider[];
  frames: ReplayFrame[];
  events: RaceEvent[];
  results: Classification;
}

/**
 * Decoded replay. Course points are objects; frames keep their compact tuples (≈ 500 k of them, read by
 * the replay model into typed arrays) — use `decodeFrameRider` to read one. Events are sorted by tick.
 */
export interface Replay extends Omit<ReplayWire, 'course'> {
  course: CourseDetail;
}

export const REPLAY_VERSION = 1;

// --- Live (SSE, later) -------------------------------------------------------------------------------

/** Wire form of a snapshot rider: `[riderId, distM, speed, groupId, rankInGroup]`. */
export type SnapshotRiderTuple = [riderId: number, distM: number, speed: number, groupId: number, rankInGroup: number, ...rest: number[]];

export interface SnapshotRider extends FrameRider {
  riderId: number;
}

/** `gapS` is the time gap to the head of the race (0 for the leading group). */
export interface GroupSummary {
  id: number;
  headDistM: number;
  size: number;
  gapS: number;
}

export interface SnapshotWire {
  tick: number;
  raceTimeS: number;
  riders: SnapshotRiderTuple[];
  groups: GroupSummary[];
}

/** Kernel error body (`{"error": "..."}`). */
export interface ApiErrorBody {
  error: string;
}
