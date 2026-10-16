import { riderShortName, type RaceMeta } from '../../replay/meta';
import { formatGap } from '../../replay/format';
import type { GroupState, RaceSnapshot } from '../../replay/source';

/** Text of a group label on the map. */
export interface GroupLabelText {
  /** `LEADERS`, `PELOTON`, `GROUP`, or the rider's short name for a lone rider. */
  title: string;
  /** Rider count. */
  size: number;
  /** `+1:23`, `s.t.` (same time), or `''` for the head of the race. */
  gap: string;
}

/** The peloton: the largest group on the road with at least 10 riders and a quarter of the riders on the road. */
export function pelotonId(groups: readonly GroupState[]): number | null {
  let best: GroupState | null = null;
  let onRoad = 0;
  for (const g of groups) {
    onRoad += g.size;
    if (!best || g.size > best.size) best = g;
  }
  return best && best.size >= 10 && best.size >= onRoad / 4 ? best.id : null;
}

export function groupLabelText(meta: RaceMeta, snapshot: RaceSnapshot, index: number, peloton: number | null): GroupLabelText {
  const g = snapshot.groups[index]!;
  const someoneFinished = snapshot.finishedRiderIds.length > 0;
  let title: string;
  if (g.size === 1) title = riderShortName(meta, g.headRiderId);
  else if (g.id === peloton) title = 'PELOTON';
  else if (index === 0 && !someoneFinished) title = 'LEADERS';
  else title = 'GROUP';
  let gap: string;
  if (index === 0 && !someoneFinished) gap = '';
  else gap = formatGap(g.gapToHeadS) || 's.t.';
  return { title, size: g.size, gap };
}

/** A label box in screen pixels; `x`, `y` = anchor (the group head), the box extends right of it. */
export interface LabelBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Vertical shifts (px, negative = up) so that no two label boxes overlap. Boxes are placed in order
 * (the first one never moves); each later box moves up past the boxes it hits. Each box is centered
 * vertically on its anchor before shifting. A box that would move more than `maxShiftPx` is hidden
 * (`null`) instead, so a zoomed-out map does not grow a tower of labels.
 */
export function declutter(boxes: readonly LabelBox[], gapPx = 2, maxShiftPx = Infinity): (number | null)[] {
  const placed: { x0: number; x1: number; y0: number; y1: number }[] = [];
  const shifts: (number | null)[] = [];
  for (const b of boxes) {
    let y0 = b.y - b.h / 2;
    for (let guard = 0; guard <= placed.length; guard++) {
      const hit = placed.find((p) => b.x < p.x1 && b.x + b.w > p.x0 && y0 < p.y1 + gapPx && y0 + b.h > p.y0 - gapPx);
      if (!hit) break;
      y0 = hit.y0 - gapPx - b.h;
    }
    const shift = y0 - (b.y - b.h / 2);
    if (-shift > maxShiftPx) {
      shifts.push(null);
      continue;
    }
    placed.push({ x0: b.x, x1: b.x + b.w, y0, y1: y0 + b.h });
    shifts.push(shift);
  }
  return shifts;
}

/**
 * Label priority (indices into `groups`, most important first): the head of the race, the followed
 * group, then the bigger groups, then road order.
 */
export function labelPriority(groups: readonly GroupState[], followedId: number | null): number[] {
  const rank = (i: number) => (i === 0 ? 0 : groups[i]!.id === followedId ? 1 : 2);
  return groups
    .map((_, i) => i)
    .sort((a, b) => rank(a) - rank(b) || groups[b]!.size - groups[a]!.size || a - b);
}

/** Generic screen box (px) for `visibleBoxes`. */
export interface Rect {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

/** Greedy collision hiding: `true` for each box kept, in priority order (earlier boxes win). */
export function visibleBoxes(boxes: readonly Rect[], gapPx = 4): boolean[] {
  const kept: Rect[] = [];
  return boxes.map((b) => {
    const hit = kept.some((k) => b.x0 < k.x1 + gapPx && b.x1 + gapPx > k.x0 && b.y0 < k.y1 + gapPx && b.y1 + gapPx > k.y0);
    if (!hit) kept.push(b);
    return !hit;
  });
}

/** What the camera follows: nothing, the head of the race, or the group of one rider. */
export type FollowTarget = { kind: 'none' } | { kind: 'lead' } | { kind: 'rider'; riderId: number };

export const FOLLOW_NONE: FollowTarget = { kind: 'none' };
export const FOLLOW_LEAD: FollowTarget = { kind: 'lead' };

/**
 * The group the camera follows, or null (nothing to follow, the rider has finished, or the whole race
 * has finished). A followed rider's group is found again after splits and merges.
 */
export function followedGroup(snapshot: RaceSnapshot, target: FollowTarget): GroupState | null {
  if (target.kind === 'none') return null;
  if (target.kind === 'lead') return snapshot.groups[0] ?? null;
  const r = snapshot.riders.find((x) => x.riderId === target.riderId);
  if (!r || r.finished) return null;
  return snapshot.groups.find((g) => g.id === r.groupId) ?? null;
}
