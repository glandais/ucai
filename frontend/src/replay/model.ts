import type { Classification, RaceEvent, Replay } from '../api/types';
import { FINISHED_GROUP } from '../api/types';
import { positionAt } from './course';
import { buildMeta, type RaceMeta } from './meta';
import type { GroupState, RaceSnapshot, RaceSource, RiderState } from './source';

/**
 * Replay data source: frames unpacked into typed arrays (frame-major, `k * riderCount + riderIndex`),
 * `distM` and `speed` linear between frames, `groupId` / `rankInGroup` as steps (value of the last frame
 * at or before `t`).
 */
export class ReplayModel implements RaceSource {
  readonly kind = 'replay' as const;
  readonly meta: RaceMeta;
  readonly events: readonly RaceEvent[];
  readonly results: Classification | null;
  readonly startT: number;
  readonly endT: number;
  readonly frameCount: number;
  readonly riderCount: number;

  private readonly times: Float64Array;
  private readonly dist: Float64Array;
  private readonly speed: Float32Array;
  private readonly group: Int32Array;
  private readonly rank: Int32Array;
  /** Head of the race per frame: the furthest any rider has ever been (running max, finished riders included). */
  private readonly headDist: Float64Array;
  private eventsCache: { count: number; list: readonly RaceEvent[] } = { count: 0, list: [] };

  constructor(replay: Replay) {
    this.meta = buildMeta(replay.race, replay.course, replay.teams, replay.riders);
    this.events = [...replay.events].sort((a, b) => a.tick - b.tick);
    this.results = replay.results;
    const F = replay.frames.length;
    const R = replay.riders.length;
    if (F === 0) throw new Error('replay has no frames');
    this.frameCount = F;
    this.riderCount = R;
    this.times = new Float64Array(F);
    this.dist = new Float64Array(F * R);
    this.speed = new Float32Array(F * R);
    this.group = new Int32Array(F * R);
    this.rank = new Int32Array(F * R);
    replay.frames.forEach((f, k) => {
      this.times[k] = f.t;
      const base = k * R;
      for (let i = 0; i < R; i++) {
        const r = f.r[i]!;
        this.dist[base + i] = r[0];
        this.speed[base + i] = r[1];
        this.group[base + i] = r[2];
        this.rank[base + i] = r[3];
      }
    });
    this.startT = this.times[0]!;
    this.endT = this.times[F - 1]!;
    this.headDist = new Float64Array(F);
    let head = 0;
    for (let k = 0; k < F; k++) {
      const base = k * R;
      for (let i = 0; i < R; i++) head = Math.max(head, this.dist[base + i]!);
      this.headDist[k] = head;
    }
  }

  /**
   * Race time at which the head of the race (the furthest rider ever, like the engine's `PassTimes`) first reached
   * distance `d`, linear between frames; `null` if it has not by time `t` (the group at `d` leads the race).
   */
  headPassTime(d: number, t: number): number | null {
    const hd = this.headDist;
    const k = this.frameIndexAt(t);
    const headNow = k >= this.frameCount - 1 ? hd[k]! : hd[k]! + (hd[k + 1]! - hd[k]!) * this.alpha(k, t);
    if (d >= headNow) return null;
    if (d <= hd[0]!) return this.times[0]!;
    // First frame whose head is at or beyond d (headDist is non-decreasing): invariant hd[lo] < d <= hd[hi].
    let lo = 0;
    let hi = k + 1 < this.frameCount ? k + 1 : k;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (hd[mid]! >= d) hi = mid;
      else lo = mid;
    }
    const h0 = hd[lo]!;
    const h1 = hd[hi]!;
    const t0 = this.times[lo]!;
    const t1 = this.times[hi]!;
    return h1 <= h0 ? t1 : t0 + ((d - h0) / (h1 - h0)) * (t1 - t0);
  }

  subscribe(): () => void {
    return () => {};
  }

  /** Index of the last frame with `t_k <= t` (0 before the first frame). */
  frameIndexAt(t: number): number {
    const times = this.times;
    let lo = 0;
    let hi = times.length - 1;
    if (t <= times[0]!) return 0;
    if (t >= times[hi]!) return hi;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (times[mid]! <= t) lo = mid;
      else hi = mid;
    }
    return lo;
  }

  /** Interpolated distance of rider `i` (replay index) at race time `t`. */
  distAt(i: number, t: number): number {
    const k = this.frameIndexAt(t);
    return this.lerp(this.dist, k, i, this.alpha(k, t));
  }

  /**
   * Race time at which rider `i` first reached distance `d`, linear between frames;
   * `null` if the rider never reaches it in the replay. Distances are non-decreasing per rider.
   */
  passTime(i: number, d: number): number | null {
    const R = this.riderCount;
    const dist = this.dist;
    if (dist[i]! >= d) return this.times[0]!;
    let lo = 0; // dist < d
    let hi = this.frameCount - 1;
    if (dist[hi * R + i]! < d) return null;
    // invariant: dist[lo] < d <= dist[hi]
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (dist[mid * R + i]! >= d) hi = mid;
      else lo = mid;
    }
    const d0 = dist[lo * R + i]!;
    const d1 = dist[hi * R + i]!;
    const t0 = this.times[lo]!;
    const t1 = this.times[hi]!;
    return t0 + ((d - d0) / (d1 - d0)) * (t1 - t0);
  }

  private alpha(k: number, t: number): number {
    if (k >= this.frameCount - 1) return 0;
    const t0 = this.times[k]!;
    const t1 = this.times[k + 1]!;
    if (t <= t0 || t1 <= t0) return 0;
    return Math.min(1, (t - t0) / (t1 - t0));
  }

  private lerp(arr: Float64Array | Float32Array, k: number, i: number, a: number): number {
    const v0 = arr[k * this.riderCount + i]!;
    if (a === 0) return v0;
    const v1 = arr[(k + 1) * this.riderCount + i]!;
    return v0 + (v1 - v0) * a;
  }

  snapshotAt(tIn: number): RaceSnapshot {
    const t = Math.min(this.endT, Math.max(this.startT, tIn));
    const k = this.frameIndexAt(t);
    const a = this.alpha(k, t);
    const R = this.riderCount;
    const base = k * R;
    const points = this.meta.course.points;
    const courseM = this.meta.course.distanceM;

    const riders: RiderState[] = new Array(R);
    const byGroup = new Map<number, RiderState[]>();
    const finished: RiderState[] = [];
    let leader: RiderState | null = null;
    for (let i = 0; i < R; i++) {
      const meta = this.meta.riders[i]!;
      const distM = this.lerp(this.dist, k, i, a);
      const groupId = this.group[base + i]!;
      const rankInGroup = this.rank[base + i]!;
      const isFinished = groupId === FINISHED_GROUP;
      const pos = positionAt(points, distM);
      const s: RiderState = {
        riderId: meta.id,
        teamId: meta.teamId,
        index: i,
        distM,
        speed: this.lerp(this.speed, k, i, a),
        groupId,
        rankInGroup,
        finished: isFinished,
        position: isFinished ? rankInGroup : null,
        lat: pos.lat,
        lon: pos.lon,
        eleM: pos.eleM,
      };
      riders[i] = s;
      if (isFinished) finished.push(s);
      else {
        let list = byGroup.get(groupId);
        if (!list) byGroup.set(groupId, (list = []));
        list.push(s);
      }
      if (!leader || distM > leader.distM) leader = s;
    }
    finished.sort((x, y) => x.rankInGroup - y.rankInGroup);

    const groups: GroupState[] = [];
    for (const [id, members] of byGroup) {
      members.sort((x, y) => x.rankInGroup - y.rankInGroup || y.distM - x.distM);
      let head = members[0]!;
      let tailDistM = head.distM;
      for (const m of members) {
        if (m.distM > head.distM) head = m;
        if (m.distM < tailDistM) tailDistM = m.distM;
      }
      groups.push({
        id,
        riderIds: members.map((m) => m.riderId),
        size: members.length,
        headDistM: head.distM,
        tailDistM,
        headRiderId: head.riderId,
        lat: head.lat,
        lon: head.lon,
        eleM: head.eleM,
        gapToHeadS: 0,
        gapToAheadS: 0,
        kmToGo: Math.max(0, (courseM - head.distM) / 1000),
      });
    }
    groups.sort((x, y) => y.headDistM - x.headDistM || x.id - y.id);

    // Time gaps, the engine's `GroupSummary.gapS` (DESIGN §3 "Time gaps"): how long ago the head of the race (the
    // furthest rider ever, so after the winner's finish the winner) passed the front of this group. To the group
    // ahead: how long ago that group's front rider passed it.
    const gapBehind = (refIndex: number, d: number): number => {
      const tp = this.passTime(refIndex, d);
      return tp === null ? 0 : Math.max(0, t - tp);
    };
    groups.forEach((g, gi) => {
      const tp = this.headPassTime(g.headDistM, t);
      g.gapToHeadS = tp === null ? 0 : Math.max(0, t - tp);
      const ahead = groups[gi - 1];
      g.gapToAheadS = ahead ? gapBehind(this.meta.riderIndex.get(ahead.headRiderId)!, g.headDistM) : g.gapToHeadS;
    });

    const headDistM = leader?.distM ?? 0;
    return {
      t,
      riders,
      groups,
      finishedRiderIds: finished.map((f) => f.riderId),
      headDistM,
      kmToGo: Math.max(0, (courseM - headDistM) / 1000),
      raceFinished: finished.length === R || t >= this.endT,
    };
  }

  eventsUntil(t: number): readonly RaceEvent[] {
    const ev = this.events;
    let lo = 0;
    let hi = ev.length; // first index with tick > t
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (ev[mid]!.tick <= t) lo = mid + 1;
      else hi = mid;
    }
    if (this.eventsCache.count !== lo) this.eventsCache = { count: lo, list: ev.slice(0, lo) };
    return this.eventsCache.list;
  }
}

export function createReplaySource(replay: Replay): ReplayModel {
  return new ReplayModel(replay);
}
