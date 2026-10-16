import type { Climb } from '../../api/types';
import { riderColor, textColorOn, type RaceMeta } from '../../replay/meta';
import type { RaceSnapshot } from '../../replay/source';
import { elevationAt, gradeAt } from './grade';
import { assignLanes } from './layout';
import type { ProfileScale } from './scale';

export interface GroupMarker {
  groupId: number;
  /** Plot x of the group head and the profile y under it. */
  x: number;
  curveY: number;
  /** Bubble width (CSS px) and lane (0 = closest to the profile). */
  w: number;
  lane: number;
  label: string;
  color: string;
  textColor: string;
  lead: boolean;
}

/** Bubble height and lane pitch, CSS px. */
export const BUBBLE_H = 18;
export const LANE_PITCH = BUBBLE_H + 3;

/** Approximate width of a bubble showing `label` (bold 11 px digits). */
export function bubbleWidth(label: string): number {
  return Math.max(BUBBLE_H, 8 + label.length * 7);
}

/**
 * One marker per group on the road (front to back): at its head, colored like the team of its leading rider,
 * labelled with its size, stacked in lanes when groups are too close to be told apart. The lead group gets
 * lane priority.
 */
export function groupMarkers(meta: RaceMeta, snapshot: RaceSnapshot, scale: ProfileScale, maxLanes = 4): GroupMarker[] {
  const points = meta.course.points;
  const base = snapshot.groups.map((g, i) => {
    const label = String(g.size);
    const w = bubbleWidth(label);
    const x = scale.x(g.headDistM);
    const color = riderColor(meta, g.headRiderId);
    return {
      groupId: g.id,
      x,
      curveY: scale.y(elevationAt(points, g.headDistM)),
      w,
      lane: 0,
      label,
      color,
      textColor: textColorOn(color),
      lead: i === 0,
    };
  });
  const lanes = assignLanes(
    base.map((m) => ({ x0: m.x - m.w / 2, x1: m.x + m.w / 2 })),
    2,
    maxLanes,
  );
  return base.map((m, i) => ({ ...m, lane: lanes[i]! }));
}

/** The climb containing `distM` (start inclusive, summit inclusive), if any. */
export function climbAt(climbs: readonly Climb[], distM: number): Climb | undefined {
  return climbs.find((c) => distM >= c.startM && distM <= c.endM);
}

export interface HoverInfo {
  distM: number;
  toGoM: number;
  eleM: number;
  grade: number;
  climb: Climb | undefined;
}

/** What the hover tooltip shows at course distance `distM`. */
export function hoverInfo(meta: RaceMeta, distM: number): HoverInfo {
  const total = meta.course.distanceM;
  const d = Math.max(0, Math.min(total, distM));
  return {
    distM: d,
    toGoM: total - d,
    eleM: elevationAt(meta.course.points, d),
    grade: gradeAt(meta.course.points, d, 200),
    climb: climbAt(meta.climbs, d),
  };
}
