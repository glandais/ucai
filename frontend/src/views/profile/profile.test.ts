import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../../api/decode';
import type { CoursePoint } from '../../api/types';
import { syntheticReplayWire } from '../../replay/fixtures';
import { createReplaySource } from '../../replay/model';
import { GRADE_BUCKETS, gradeAt, gradeBucket, gradeColor } from './grade';
import { shortClimbName } from './draw';
import { assignLanes, clampBox } from './layout';
import { climbAt, groupMarkers, hoverInfo } from './markers';
import { ProfileScale, elevationRange, gradeSegmentM, kmStep, niceStep, profileColumns, ticks } from './scale';

/** 0–1000 m flat at 100 m, 1000–2000 m climbing 10 % (to 200 m), 2000–3000 m descending 5 % (to 150 m). */
const hill: CoursePoint[] = [0, 500, 1000, 1500, 2000, 2500, 3000].map((d) => ({
  lat: 0,
  lon: 0,
  distM: d,
  eleM: d <= 1000 ? 100 : d <= 2000 ? 100 + (d - 1000) * 0.1 : 200 - (d - 2000) * 0.05,
}));

describe('grade', () => {
  it('measures the smoothed gradient and shifts the window inside the course', () => {
    expect(gradeAt(hill, 500)).toBeCloseTo(0);
    expect(gradeAt(hill, 1500)).toBeCloseTo(0.1);
    expect(gradeAt(hill, 2500)).toBeCloseTo(-0.05);
    expect(gradeAt(hill, 0)).toBeCloseTo(0);
    expect(gradeAt(hill, 3000)).toBeCloseTo(-0.05);
    // Window straddling the foot of the climb: half flat, half 10 %.
    expect(gradeAt(hill, 1000, 400)).toBeCloseTo(0.05);
    expect(gradeAt([], 10)).toBe(0);
  });

  it('buckets gradients into colors', () => {
    expect(gradeBucket(-0.08)).toBe(0);
    expect(gradeBucket(0.019)).toBe(0);
    expect(gradeBucket(0.02)).toBe(1);
    expect(gradeBucket(0.065)).toBe(3);
    expect(gradeBucket(0.2)).toBe(GRADE_BUCKETS.length - 1);
    expect(gradeColor(0.05)).toBe(GRADE_BUCKETS[2]!.color);
  });
});

describe('scale', () => {
  it('picks nice steps and ticks', () => {
    expect(niceStep(7)).toBe(10);
    expect(niceStep(0.3)).toBe(0.5);
    expect(niceStep(21)).toBe(25);
    expect(niceStep(200)).toBe(200);
    expect(ticks(0, 297.3, 50)).toEqual([0, 50, 100, 150, 200, 250]);
    expect(ticks(130, 260, 100)).toEqual([200]);
    expect(ticks(0, 10, 0)).toEqual([]);
  });

  it('spaces km labels by the available width', () => {
    expect(kmStep(297_000, 1500)).toBe(20); // 297 km × 70 px / 1500 px ≈ 13.9 km → 20
    expect(kmStep(297_000, 600)).toBe(50);
    expect(kmStep(10_000, 1000)).toBe(1);
  });

  it('colors 1 km segments unless a km is too narrow', () => {
    expect(gradeSegmentM(297_000, 1400)).toBe(1000);
    expect(gradeSegmentM(297_000, 300)).toBe(5000);
  });

  it('frames the elevation range', () => {
    const r = elevationRange(4.5, 523);
    expect(r.min).toBe(0);
    expect(r.max).toBeGreaterThanOrEqual(523 * 1.05 - 1);
    expect(r.max % r.step).toBe(0);
    const high = elevationRange(1200, 2400);
    expect(high.min).toBeGreaterThan(0);
    expect(high.min).toBeLessThan(1200);
    const flat = elevationRange(10, 12);
    expect(flat.max - flat.min).toBeGreaterThanOrEqual(300);
  });

  it('maps distance and elevation to the plot and back', () => {
    const s = new ProfileScale({ left: 40, top: 10, width: 1000, height: 100 }, 200_000, { min: 0, max: 500, step: 100 });
    expect(s.x(0)).toBe(40);
    expect(s.x(100_000)).toBe(540);
    expect(s.x(-5)).toBe(40);
    expect(s.x(300_000)).toBe(1040);
    expect(s.y(0)).toBe(110);
    expect(s.y(500)).toBe(10);
    expect(s.dist(540)).toBe(100_000);
    expect(s.dist(0)).toBe(0);
    expect(s.bottom).toBe(110);
    expect(s.right).toBe(1040);
  });

  it('downsamples the course into columns keeping the summits', () => {
    const c = profileColumns(hill, 3000, 3, 1000);
    expect(Array.from(c.ele)).toEqual([100, 200, 200]);
    expect(c.grade[0]).toBeCloseTo(0);
    expect(c.grade[1]).toBeCloseTo(0.1);
    expect(c.grade[2]).toBeCloseTo(-0.05);
    // Narrow columns between points are interpolated; the gradient is per segment.
    const fine = profileColumns(hill, 3000, 30, 1500);
    expect(fine.ele[15]).toBeCloseTo(160);
    expect(fine.grade[0]).toBeCloseTo(50 / 1500);
    expect(fine.grade[14]).toBeCloseTo(50 / 1500);
    expect(fine.grade[15]).toBeCloseTo(0); // 150 m → 150 m
    expect(profileColumns([], 1000, 10).ele.every((e) => e === 0)).toBe(true);
  });
});

describe('layout', () => {
  it('stacks overlapping labels into lanes in priority order', () => {
    expect(
      assignLanes([
        { x0: 0, x1: 20 },
        { x0: 10, x1: 30 },
        { x0: 50, x1: 70 },
        { x0: 15, x1: 25 },
        { x0: 22, x1: 40 },
      ], 0),
    ).toEqual([0, 1, 0, 2, 0]);
    // Within the gap counts as an overlap.
    expect(assignLanes([{ x0: 0, x1: 10 }, { x0: 12, x1: 20 }], 4)).toEqual([0, 1]);
  });

  it('reuses the least overlapping lane once all lanes are taken', () => {
    expect(
      assignLanes(
        [
          { x0: 0, x1: 20 },
          { x0: 5, x1: 30 },
          { x0: 18, x1: 40 },
        ],
        0,
        2,
      ),
    ).toEqual([0, 1, 0]);
    expect(assignLanes([{ x0: 0, x1: 20 }, { x0: 5, x1: 30 }], 0, 1, true)).toEqual([0, -1]);
  });

  it('keeps boxes inside the bounds', () => {
    expect(clampBox(-5, 20, 0, 100)).toBe(0);
    expect(clampBox(90, 20, 0, 100)).toBe(80);
    expect(clampBox(40, 20, 0, 100)).toBe(40);
  });
});

describe('markers', () => {
  const source = createReplaySource(decodeReplay(syntheticReplayWire()));
  const { meta } = source;
  const scale = new ProfileScale({ left: 0, top: 0, width: 1000, height: 100 }, 10_000, { min: 0, max: 300, step: 100 });

  it('places one marker per group with the lead rider team color', () => {
    const snap = source.snapshotAt(500); // A at 5 km, B at 4 km
    const m = groupMarkers(meta, snap, scale);
    expect(m.map((g) => [g.groupId, g.label, g.color, g.lead])).toEqual([
      [1, '2', '#d62728', true],
      [2, '2', '#ffff00', false],
    ]);
    expect(m[0]!.x).toBeCloseTo(500);
    expect(m[1]!.x).toBeCloseTo(400);
    expect(m[1]!.textColor).toBe('#000000');
    expect(m.every((g) => g.lane === 0)).toBe(true);
    expect(m[0]!.curveY).toBeCloseTo(scale.y(150));
  });

  it('stacks groups that are too close to tell apart', () => {
    const snap = source.snapshotAt(20); // 200 m vs 160 m apart: 4 px
    expect(groupMarkers(meta, snap, scale).map((g) => g.lane)).toEqual([0, 1]);
  });

  it('describes the hovered point', () => {
    const climbMeta = { ...meta, climbs: [{ name: 'Col', startM: 2000, endM: 4000, avgGrade: 0.01, maxGrade: 0.01 }] };
    const h = hoverInfo(climbMeta, 3000);
    expect(h.distM).toBe(3000);
    expect(h.toGoM).toBe(7000);
    expect(h.eleM).toBeCloseTo(130);
    expect(h.grade).toBeCloseTo(0.01);
    expect(h.climb?.name).toBe('Col');
    expect(hoverInfo(climbMeta, 20_000).distM).toBe(10_000);
    expect(climbAt(climbMeta.climbs, 4500)).toBeUndefined();
  });
});

describe('labels', () => {
  it('drops parenthesized climb subtitles', () => {
    expect(shortClimbName('Côte de Stockeu (Stèle Eddy Merckx)')).toBe('Côte de Stockeu');
    expect(shortClimbName('Cipressa')).toBe('Cipressa');
    expect(shortClimbName('(only)')).toBe('(only)');
  });
});
