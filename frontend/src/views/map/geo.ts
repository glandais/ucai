import type { CoursePoint } from '../../api/types';
import { positionAt } from '../../replay/course';

/** Meters per degree of latitude (spherical approximation, good enough for display offsets). */
export const M_PER_DEG_LAT = 111_320;

const EARTH_CIRCUMFERENCE_M = 40_075_016.686;

/** Ground meters per CSS pixel at `lat` and MapLibre `zoom` (512 px tiles). */
export function metersPerPixel(lat: number, zoom: number): number {
  return (EARTH_CIRCUMFERENCE_M * Math.cos((lat * Math.PI) / 180)) / (512 * 2 ** zoom);
}

/** Unit direction of travel on the course at `distM`, as local east/north components. */
export interface Heading {
  east: number;
  north: number;
}

/**
 * Direction of travel at `distM`, from the course positions `windowM` before and after (smooths the
 * corners of the polyline). Falls back to north on a degenerate course.
 */
export function headingAt(points: readonly CoursePoint[], distM: number, windowM = 25): Heading {
  const last = points[points.length - 1];
  if (!last) return { east: 0, north: 1 };
  const total = last.distM;
  let d0 = Math.max(0, distM - windowM);
  let d1 = Math.min(total, distM + windowM);
  if (d1 - d0 < windowM) {
    // At the start or the finish: keep a full window inside the course.
    if (d0 <= 0) d1 = Math.min(total, 2 * windowM);
    else d0 = Math.max(0, total - 2 * windowM);
  }
  const a = positionAt(points, d0);
  const b = positionAt(points, d1);
  const east = (b.lon - a.lon) * M_PER_DEG_LAT * Math.cos((a.lat * Math.PI) / 180);
  const north = (b.lat - a.lat) * M_PER_DEG_LAT;
  const len = Math.hypot(east, north);
  return len > 1e-9 ? { east: east / len, north: north / len } : { east: 0, north: 1 };
}

/** Moves (`lat`, `lon`) by `eastM` / `northM` meters. */
export function offsetLatLon(lat: number, lon: number, eastM: number, northM: number): [lon: number, lat: number] {
  const cos = Math.max(1e-6, Math.cos((lat * Math.PI) / 180));
  return [lon + eastM / (M_PER_DEG_LAT * cos), lat + northM / M_PER_DEG_LAT];
}

/** `[[minLon, minLat], [maxLon, maxLat]]` of the course, or null without points. */
export function courseBounds(points: readonly CoursePoint[]): [[number, number], [number, number]] | null {
  if (points.length === 0) return null;
  let minLon = Infinity;
  let minLat = Infinity;
  let maxLon = -Infinity;
  let maxLat = -Infinity;
  for (const p of points) {
    if (p.lon < minLon) minLon = p.lon;
    if (p.lon > maxLon) maxLon = p.lon;
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
  }
  return [
    [minLon, minLat],
    [maxLon, maxLat],
  ];
}

/** `[lon, lat]` coordinates of the course between `fromM` and `toM` (interpolated ends included). */
export function courseSlice(points: readonly CoursePoint[], fromM: number, toM: number): [number, number][] {
  const out: [number, number][] = [];
  if (points.length === 0 || toM < fromM) return out;
  const a = positionAt(points, fromM);
  out.push([a.lon, a.lat]);
  for (const p of points) {
    if (p.distM > fromM && p.distM < toM) out.push([p.lon, p.lat]);
  }
  const b = positionAt(points, toM);
  out.push([b.lon, b.lat]);
  return out;
}
