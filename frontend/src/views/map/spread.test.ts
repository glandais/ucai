import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../../api/decode';
import { syntheticReplayWire } from '../../replay/fixtures';
import { ReplayModel } from '../../replay/model';
import { M_PER_DEG_LAT } from './geo';
import { dotRadiusPx, groupSlot, maxWidth, MIN_SPACING, spacingFor, spreadRiders } from './spread';

describe('group slots', () => {
  it('keeps small groups single file and widens big ones', () => {
    expect(maxWidth(1)).toBe(1);
    expect(maxWidth(2)).toBe(1);
    expect(maxWidth(5)).toBe(2);
    expect(maxWidth(70)).toBe(7);
    expect(maxWidth(500)).toBe(8);
    expect([1, 2, 3].map((r) => groupSlot(r, 3))).toEqual([
      { row: 0, lane: 0 },
      { row: 1, lane: 0 },
      { row: 2, lane: 0 },
    ]);
  });

  it('builds a tapered, centered peloton with one slot per rider', () => {
    const size = 70;
    const slots = Array.from({ length: size }, (_, i) => groupSlot(i + 1, size));
    expect(slots[0]).toEqual({ row: 0, lane: 0 });
    expect(slots[1]).toEqual({ row: 1, lane: -0.5 });
    expect(slots[2]).toEqual({ row: 1, lane: 0.5 });
    const keys = new Set(slots.map((s) => `${s.row}:${s.lane}`));
    expect(keys.size).toBe(size);
    const rows = new Map<number, number[]>();
    for (const s of slots) rows.set(s.row, [...(rows.get(s.row) ?? []), s.lane]);
    for (const lanes of rows.values()) {
      expect(lanes.length).toBeLessThanOrEqual(7);
      // Each row is centered on the road.
      expect(lanes.reduce((a, b) => a + b, 0)).toBeCloseTo(0, 9);
    }
    // Rows never get narrower before the last one.
    const widths = [...rows.values()].map((l) => l.length);
    for (let i = 1; i < widths.length - 1; i++) expect(widths[i]!).toBeGreaterThanOrEqual(widths[i - 1]!);
    // Out-of-range ranks are clamped.
    expect(groupSlot(0, 4)).toEqual(groupSlot(1, 4));
    expect(groupSlot(9, 4)).toEqual(groupSlot(4, 4));
  });
});

describe('spacing', () => {
  it('follows the dot size, never tighter than real riders', () => {
    expect(dotRadiusPx(0)).toBe(2);
    expect(dotRadiusPx(11.5)).toBeCloseTo(3.85, 6);
    expect(dotRadiusPx(20)).toBe(6.5);
    expect(spacingFor(100, 5)).toEqual({ laneM: 500, rowM: 500 });
    expect(spacingFor(0.01, 5)).toEqual(MIN_SPACING);
  });
});

describe('spreadRiders', () => {
  const model = new ReplayModel(decodeReplay(syntheticReplayWire()));

  it('stacks a group behind its head and spreads it across the road', () => {
    // t = 5: all four riders together in group 1 at 50 m, course heading north.
    const s = model.snapshotAt(5);
    const placed = spreadRiders(model.meta.course.points, s, { laneM: 3, rowM: 10 });
    expect(placed).toHaveLength(4);
    const [a, b, c, d] = placed.map((p) => ({
      row: p.row,
      northM: (p.lat - 45) * 1e5, // fixture: 1e-5 degree per course meter
      eastM: (p.lon - 7) * M_PER_DEG_LAT * Math.cos((45 * Math.PI) / 180),
    }));
    // maxWidth(4) = 2: rows of 1, 2, 1.
    expect([a!.row, b!.row, c!.row, d!.row]).toEqual([0, 1, 1, 2]);
    expect(a!.northM).toBeCloseTo(50, 1);
    expect(a!.eastM).toBeCloseTo(0, 3);
    expect(b!.northM).toBeCloseTo(40, 1);
    expect(c!.northM).toBeCloseTo(40, 1);
    // Heading north: positive lanes go east (right of the direction of travel).
    expect(b!.eastM).toBeCloseTo(-1.5, 3);
    expect(c!.eastM).toBeCloseTo(1.5, 3);
    expect(d!.northM).toBeCloseTo(30, 1);
  });

  it('never draws a rider before the start and leaves finished riders out', () => {
    const start = spreadRiders(model.meta.course.points, model.snapshotAt(0), { laneM: 3, rowM: 100 });
    for (const p of start) expect(p.lat).toBeGreaterThanOrEqual(45 - 1e-9);
    const late = spreadRiders(model.meta.course.points, model.snapshotAt(1100), MIN_SPACING);
    expect(late.map((p) => p.rider.riderId)).toEqual([8, 9]);
  });
});
