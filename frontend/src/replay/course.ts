import type { CoursePoint } from '../api/types';

export interface CoursePosition {
  lat: number;
  lon: number;
  eleM: number;
  distM: number;
}

/**
 * Index of the last point with `distM <= d` (0 when `d` is before the start). Points are sorted by `distM`.
 */
export function pointIndexAt(points: readonly CoursePoint[], d: number): number {
  let lo = 0;
  let hi = points.length - 1;
  if (hi < 0 || d <= points[0]!.distM) return 0;
  if (d >= points[hi]!.distM) return hi;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (points[mid]!.distM <= d) lo = mid;
    else hi = mid;
  }
  return lo;
}

/** Position on the course at distance `d` (m), linear between points, clamped to the start and finish. */
export function positionAt(points: readonly CoursePoint[], d: number): CoursePosition {
  const i = pointIndexAt(points, d);
  const a = points[i];
  if (!a) return { lat: 0, lon: 0, eleM: 0, distM: d };
  const b = points[i + 1];
  if (!b || d <= a.distM) return { lat: a.lat, lon: a.lon, eleM: a.eleM, distM: a.distM };
  const span = b.distM - a.distM;
  const f = span > 0 ? Math.min(1, (d - a.distM) / span) : 0;
  return {
    lat: a.lat + (b.lat - a.lat) * f,
    lon: a.lon + (b.lon - a.lon) * f,
    eleM: a.eleM + (b.eleM - a.eleM) * f,
    distM: d,
  };
}
