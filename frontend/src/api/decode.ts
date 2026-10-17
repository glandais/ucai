import type {
  CourseDetail,
  CourseDetailWire,
  CoursePoint,
  CoursePointTuple,
  FrameRider,
  FrameRiderTuple,
  Replay,
  ReplayWire,
  SnapshotRider,
  SnapshotRiderTuple,
} from './types';
import { REPLAY_VERSION } from './types';

export function decodeCoursePoint(p: CoursePointTuple): CoursePoint {
  return { lat: p[0], lon: p[1], eleM: p[2], distM: p[3] };
}

export function decodeFrameRider(r: FrameRiderTuple): FrameRider {
  return { distM: r[0], speed: r[1], groupId: r[2], rankInGroup: r[3] };
}

export function decodeSnapshotRider(r: SnapshotRiderTuple): SnapshotRider {
  return { riderId: r[0], distM: r[1], speed: r[2], groupId: r[3], rankInGroup: r[4] };
}

export function decodeCourse(c: CourseDetailWire): CourseDetail {
  const points = c.points.map(decodeCoursePoint);
  const track = c.track && c.track.length > 0 ? c.track.map(decodeCoursePoint) : points;
  return { ...c, points, climbs: c.climbs ?? [], track };
}

export class ReplayFormatError extends Error {}

/** Validates the top-level shape of a parsed replay and decodes it. Throws `ReplayFormatError`. */
export function decodeReplay(json: unknown): Replay {
  if (typeof json !== 'object' || json === null) throw new ReplayFormatError('replay: not a JSON object');
  const w = json as Partial<ReplayWire>;
  if (w.version !== REPLAY_VERSION) throw new ReplayFormatError(`replay: unsupported version ${String(w.version)}`);
  if (!w.race || !w.course || !Array.isArray(w.course.points) || !Array.isArray(w.riders) || !Array.isArray(w.frames)) {
    throw new ReplayFormatError('replay: missing race, course, riders or frames');
  }
  if (w.frames.length === 0) throw new ReplayFormatError('replay: no frames');
  const n = w.riders.length;
  for (const f of w.frames) {
    if (f.r.length !== n) throw new ReplayFormatError(`replay: frame t=${f.t} has ${f.r.length} riders, expected ${n}`);
  }
  const events = [...(w.events ?? [])].sort((a, b) => a.tick - b.tick); // stable: keeps same-tick order
  return {
    version: w.version,
    race: w.race,
    course: decodeCourse(w.course),
    teams: w.teams ?? w.race.teams ?? [],
    riders: w.riders,
    frames: w.frames,
    events,
    results: w.results ?? { raceId: w.race.id, final: false, entries: [] },
  };
}
