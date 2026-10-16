import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../api/decode';
import { syntheticReplayWire } from './fixtures';
import { ReplayModel } from './model';
import { positionAt } from './course';

const model = () => new ReplayModel(decodeReplay(syntheticReplayWire()));

describe('ReplayModel interpolation', () => {
  it('interpolates distM linearly between frames and clamps the range', () => {
    const m = model();
    expect(m.startT).toBe(0);
    expect(m.endT).toBe(1250);
    expect(m.distAt(0, 55)).toBeCloseTo(550);
    expect(m.distAt(2, 55)).toBeCloseTo(440);
    expect(m.distAt(0, 5)).toBeCloseTo(50); // between t=0 (0 m) and t=10 (100 m)
    const s = m.snapshotAt(-10);
    expect(s.t).toBe(0);
    expect(m.snapshotAt(99999).t).toBe(1250);
  });

  it('keeps groupId and rankInGroup as steps', () => {
    const m = model();
    const before = m.snapshotAt(9.9);
    expect(before.riders.map((r) => r.groupId)).toEqual([1, 1, 1, 1]);
    expect(before.riders.map((r) => r.rankInGroup)).toEqual([1, 2, 3, 4]);
    const after = m.snapshotAt(10);
    expect(after.riders.map((r) => r.groupId)).toEqual([1, 1, 2, 2]);
  });

  it('places riders on the course', () => {
    const s = model().snapshotAt(55);
    const r1 = s.riders[0]!;
    expect(r1.riderId).toBe(1);
    expect(r1.teamId).toBe('team-a');
    expect(r1.lat).toBeCloseTo(45 + 550e-5, 9);
    expect(r1.lon).toBeCloseTo(7, 9);
    expect(r1.eleM).toBeCloseTo(105.5, 6);
    expect(r1.speed).toBeCloseTo(10);
  });

  it('binary-searches course points', () => {
    const pts = model().meta.course.points;
    expect(positionAt(pts, -5).distM).toBe(0);
    expect(positionAt(pts, 10_500).lat).toBeCloseTo(45.1, 9);
    expect(positionAt(pts, 2500).lat).toBeCloseTo(45.025, 9);
  });
});

describe('ReplayModel groups and gaps', () => {
  it('orders groups front to back with real time gaps', () => {
    const s = model().snapshotAt(500);
    expect(s.groups.map((g) => g.id)).toEqual([1, 2]);
    const [g1, g2] = s.groups as [(typeof s.groups)[0], (typeof s.groups)[0]];
    expect(g1.riderIds).toEqual([1, 2]);
    expect(g1.size).toBe(2);
    expect(g1.headDistM).toBeCloseTo(5000);
    expect(g1.gapToHeadS).toBe(0);
    expect(g1.kmToGo).toBeCloseTo(5);
    expect(g2.riderIds).toEqual([8, 9]);
    expect(g2.headDistM).toBeCloseTo(4000);
    // the leaders passed 4000 m at t = 400 s
    expect(g2.gapToHeadS).toBeCloseTo(100);
    expect(g2.gapToAheadS).toBeCloseTo(100);
    expect(g2.kmToGo).toBeCloseTo(6);
    expect(s.headDistM).toBeCloseTo(5000);
    expect(s.kmToGo).toBeCloseTo(5);
    expect(s.raceFinished).toBe(false);
  });

  it('measures gaps between frames by interpolating the pass time', () => {
    const s = model().snapshotAt(505); // group 2 at 4040 m, passed by the leaders at t = 404 s
    expect(s.groups[1]!.gapToHeadS).toBeCloseTo(101);
  });

  it('takes the winner as reference once riders have finished', () => {
    const s = model().snapshotAt(1100);
    expect(s.finishedRiderIds).toEqual([1, 2]);
    expect(s.riders[0]!.finished).toBe(true);
    expect(s.riders[0]!.position).toBe(1);
    expect(s.groups.map((g) => g.id)).toEqual([2]);
    expect(s.groups[0]!.headDistM).toBeCloseTo(8800);
    expect(s.groups[0]!.gapToHeadS).toBeCloseTo(220);
    expect(s.groups[0]!.gapToAheadS).toBeCloseTo(220);
    expect(s.headDistM).toBe(10_000);
    expect(s.kmToGo).toBe(0);
  });

  it('ends with every rider finished', () => {
    const s = model().snapshotAt(1250);
    expect(s.groups).toEqual([]);
    expect(s.finishedRiderIds).toEqual([1, 2, 8, 9]);
    expect(s.raceFinished).toBe(true);
  });

  it('computes pass times', () => {
    const m = model();
    expect(m.passTime(0, 0)).toBe(0);
    expect(m.passTime(0, 4040)).toBeCloseTo(404);
    expect(m.passTime(2, 10_000)).toBeCloseTo(1250);
    expect(m.passTime(2, 10_001)).toBeNull();
  });
});

describe('ReplayModel events', () => {
  it('sorts events and returns those up to t', () => {
    const m = model();
    expect(m.events[0]!.type).toBe('race_started');
    expect(m.eventsUntil(-1)).toEqual([]);
    expect(m.eventsUntil(5).map((e) => e.type)).toEqual(['race_started', 'attack']);
    expect(m.eventsUntil(1000)).toHaveLength(5);
    expect(m.eventsUntil(1000)).toBe(m.eventsUntil(1100)); // same instance while unchanged
    expect(m.eventsUntil(2000)).toHaveLength(8);
  });
});
