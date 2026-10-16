import type { CoursePoint } from '../../api/types';
import { positionAt } from '../../replay/course';
import type { RaceSnapshot, RiderState } from '../../replay/source';
import { headingAt, offsetLatLon } from './geo';

/**
 * Peloton layout. The frames put every rider of a group at the same distance, so the map spreads a group
 * from `rankInGroup`: the front is a point (the rider on the front), rows widen up to `maxWidth(size)`
 * riders side by side, and rows stack behind the head along the road. Spacing is in screen pixels (so a
 * peloton reads as a peloton at any zoom), never tighter than real riders on the road.
 */

/** Slot of a rider in its group: `row` 0 = front, `lane` = lateral offset in rider widths (0 = center). */
export interface GroupSlot {
  row: number;
  lane: number;
}

/** Widest row for a group of `size` riders (1 for a pair, 7 for a 70-rider peloton, at most 8). */
export function maxWidth(size: number): number {
  return Math.max(1, Math.min(8, Math.round(Math.sqrt(size) * 0.8)));
}

/** Row and lane of the rider ranked `rank` (1 = front) in a group of `size`. */
export function groupSlot(rank: number, size: number): GroupSlot {
  const w = maxWidth(size);
  const n = Math.max(1, Math.min(size, Math.floor(rank)));
  let first = 1; // rank of the first rider of the current row
  let row = 0;
  for (;;) {
    const width = Math.min(w, row + 1);
    const remaining = size - first + 1;
    const inRow = Math.min(width, remaining);
    if (n < first + inRow) return { row, lane: n - first - (inRow - 1) / 2 };
    first += inRow;
    row++;
  }
}

export interface SpreadSpacing {
  /** Lateral distance between two riders, m. */
  laneM: number;
  /** Distance between two rows, m. */
  rowM: number;
}

/** Real riders: ~0.8 m side by side, ~2 m wheel to wheel. */
export const MIN_SPACING: SpreadSpacing = { laneM: 0.8, rowM: 2 };

/** Spacing at `mpp` meters per pixel for dots `spacingPx` apart, never under the real one. */
export function spacingFor(mpp: number, spacingPx: number): SpreadSpacing {
  return { laneM: Math.max(MIN_SPACING.laneM, mpp * spacingPx), rowM: Math.max(MIN_SPACING.rowM, mpp * spacingPx) };
}

/** Rider dot radius (px) by zoom: `[zoom, radius]` stops, linear in between (also the map layer's paint). */
export const DOT_RADIUS_STOPS: readonly (readonly [number, number])[] = [
  [6, 2],
  [10, 3.2],
  [13, 4.5],
  [16, 6.5],
];

export function dotRadiusPx(zoom: number): number {
  const stops = DOT_RADIUS_STOPS;
  const first = stops[0]!;
  const last = stops[stops.length - 1]!;
  if (zoom <= first[0]) return first[1];
  if (zoom >= last[0]) return last[1];
  for (let i = 1; i < stops.length; i++) {
    const [z1, r1] = stops[i]!;
    const [z0, r0] = stops[i - 1]!;
    if (zoom <= z1) return r0 + ((r1 - r0) * (zoom - z0)) / (z1 - z0);
  }
  return last[1];
}

/** Center-to-center distance between two neighbouring dots, px (they touch slightly, like a bunch). */
export function dotSpacingPx(zoom: number): number {
  return dotRadiusPx(zoom) * 1.7;
}

export interface PlacedRider {
  rider: RiderState;
  lon: number;
  lat: number;
  /** Row in its group (0 = front); draw order key. */
  row: number;
}

/**
 * Display positions of the riders still on the road (finished riders are left out). A rider is drawn at
 * `row × rowM` behind the head of its group, or at its real distance when it is further back (a group
 * stretched on the road keeps its real shape), and `lane × laneM` to the side of the road.
 */
export function spreadRiders(points: readonly CoursePoint[], snapshot: RaceSnapshot, spacing: SpreadSpacing): PlacedRider[] {
  const groups = new Map(snapshot.groups.map((g) => [g.id, g]));
  const out: PlacedRider[] = [];
  for (const r of snapshot.riders) {
    if (r.finished) continue;
    const g = groups.get(r.groupId);
    if (!g) {
      out.push({ rider: r, lon: r.lon, lat: r.lat, row: 0 });
      continue;
    }
    const slot = groupSlot(r.rankInGroup, g.size);
    const behind = Math.max(g.headDistM - r.distM, slot.row * spacing.rowM);
    const d = Math.max(0, g.headDistM - behind);
    const h = headingAt(points, d);
    const side = slot.lane * spacing.laneM;
    // Right of the direction of travel = (north, -east).
    const base = d === r.distM ? { lat: r.lat, lon: r.lon } : positionAt(points, d);
    const [lon, lat] = offsetLatLon(base.lat, base.lon, h.north * side, -h.east * side);
    out.push({ rider: r, lon, lat, row: slot.row });
  }
  return out;
}

