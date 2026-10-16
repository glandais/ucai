import { describe, expect, it } from 'vitest';
import type { CoursePoint } from '../../api/types';
import { courseBounds, courseSlice, headingAt, metersPerPixel, M_PER_DEG_LAT, offsetLatLon } from './geo';

/** North for 1 km, then east for 1 km (at the equator, so degrees are isotropic). */
const L: CoursePoint[] = [
  { lat: 0, lon: 0, eleM: 0, distM: 0 },
  { lat: 1000 / M_PER_DEG_LAT, lon: 0, eleM: 0, distM: 1000 },
  { lat: 1000 / M_PER_DEG_LAT, lon: 1000 / M_PER_DEG_LAT, eleM: 0, distM: 2000 },
];

describe('map geometry', () => {
  it('meters per pixel halves with each zoom level and shrinks with latitude', () => {
    expect(metersPerPixel(0, 0)).toBeCloseTo(78271.5, 0);
    expect(metersPerPixel(0, 10)).toBeCloseTo(metersPerPixel(0, 9) / 2, 6);
    expect(metersPerPixel(60, 10)).toBeCloseTo(metersPerPixel(0, 10) / 2, 6);
  });

  it('gives the direction of travel, including at the start and the finish', () => {
    const n = headingAt(L, 500);
    expect(n.north).toBeCloseTo(1, 6);
    expect(n.east).toBeCloseTo(0, 6);
    const e = headingAt(L, 1500);
    expect(e.east).toBeCloseTo(1, 6);
    expect(headingAt(L, 0).north).toBeCloseTo(1, 6);
    expect(headingAt(L, 2000).east).toBeCloseTo(1, 6);
    // At the corner: halfway between north and east.
    const c = headingAt(L, 1000);
    expect(c.east).toBeCloseTo(Math.SQRT1_2, 6);
    expect(c.north).toBeCloseTo(Math.SQRT1_2, 6);
    expect(headingAt([], 10)).toEqual({ east: 0, north: 1 });
  });

  it('offsets a position in meters', () => {
    const [lon, lat] = offsetLatLon(60, 10, 100, -50);
    expect((lon - 10) * M_PER_DEG_LAT * 0.5).toBeCloseTo(100, 6);
    expect((lat - 60) * M_PER_DEG_LAT).toBeCloseTo(-50, 6);
  });

  it('computes bounds and course slices', () => {
    expect(courseBounds([])).toBeNull();
    const b = courseBounds(L)!;
    expect(b[0]).toEqual([0, 0]);
    expect(b[1][0]).toBeCloseTo(1000 / M_PER_DEG_LAT, 12);
    const s = courseSlice(L, 500, 1500);
    expect(s).toHaveLength(3);
    expect(s[0]![1]).toBeCloseTo(500 / M_PER_DEG_LAT, 12);
    expect(s[1]).toEqual([0, 1000 / M_PER_DEG_LAT]);
    expect(s[2]![0]).toBeCloseTo(500 / M_PER_DEG_LAT, 12);
    expect(courseSlice(L, 10, 5)).toEqual([]);
  });
});
