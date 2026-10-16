/** A label's horizontal extent, CSS px. */
export interface Span {
  x0: number;
  x1: number;
}

/**
 * Greedy lane assignment for labels that must not overlap horizontally: items are placed in the given order
 * (priority first), each in the lowest lane where it keeps at least `gap` px from the items already there.
 * Returns the lane of each item (same order as `items`). At most `maxLanes` lanes; when all are full the item
 * goes to the lane where it overlaps the least, or gets -1 when `drop` is set.
 */
export function assignLanes(items: readonly Span[], gap = 4, maxLanes = Infinity, drop = false): number[] {
  const lanes: Span[][] = [];
  return items.map((it) => {
    let best = -1;
    let bestOverlap = Infinity;
    for (let l = 0; l < lanes.length; l++) {
      const overlap = Math.max(0, ...lanes[l]!.map((o) => Math.min(it.x1, o.x1) - Math.max(it.x0, o.x0) + gap));
      if (overlap <= 0) {
        lanes[l]!.push(it);
        return l;
      }
      if (overlap < bestOverlap) {
        bestOverlap = overlap;
        best = l;
      }
    }
    if (lanes.length < maxLanes) {
      lanes.push([it]);
      return lanes.length - 1;
    }
    if (drop) return -1;
    lanes[best]!.push(it);
    return best;
  });
}

/** Shifts a box of width `w` anchored at `x` so that it fits in `[min, max]` (left-aligned when it can't). */
export function clampBox(x: number, w: number, min: number, max: number): number {
  return Math.max(min, Math.min(x, max - w));
}
