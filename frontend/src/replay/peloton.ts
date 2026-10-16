import type { GroupState } from './source';

/** Smallest group called the peloton. */
export const PELOTON_MIN_RIDERS = 10;
/** Smallest share of the riders on the road the peloton must hold. */
export const PELOTON_MIN_SHARE = 0.25;

/**
 * The one peloton rule shared by the map and the panels. Index of the peloton in `groups` (front to
 * back): the largest group (the front-most one on a tie), if it holds at least `PELOTON_MIN_RIDERS`
 * riders and `PELOTON_MIN_SHARE` of the riders on the road; −1 otherwise (a fragmented race has no peloton).
 */
export function pelotonIndex(groups: readonly Pick<GroupState, 'size'>[]): number {
  let best = -1;
  let onRoad = 0;
  groups.forEach((g, i) => {
    onRoad += g.size;
    if (best < 0 || g.size > groups[best]!.size) best = i;
  });
  if (best < 0) return -1;
  const size = groups[best]!.size;
  return size >= PELOTON_MIN_RIDERS && size >= PELOTON_MIN_SHARE * onRoad ? best : -1;
}

/** Id of the peloton group, or null (see `pelotonIndex`). */
export function pelotonId(groups: readonly Pick<GroupState, 'id' | 'size'>[]): number | null {
  const i = pelotonIndex(groups);
  return i < 0 ? null : groups[i]!.id;
}
