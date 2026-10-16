import type { CoursePoint } from '../../api/types';
import { positionAt } from '../../replay/course';

/** Elevation (m) on the course at `d`, linear between points, clamped to the course. */
export function elevationAt(points: readonly CoursePoint[], d: number): number {
  return positionAt(points, d).eleM;
}

/**
 * Smoothed gradient (fraction, 0.05 = 5 %) at `d`: elevation difference over a window of `windowM` centered on `d`
 * (shifted inside the course near the start and the finish). 0 on an empty or zero-length course.
 */
export function gradeAt(points: readonly CoursePoint[], d: number, windowM = 300): number {
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return 0;
  const len = last.distM - first.distM;
  if (len <= 0) return 0;
  const w = Math.min(windowM, len);
  let a = d - w / 2;
  let b = d + w / 2;
  if (a < first.distM) {
    a = first.distM;
    b = a + w;
  } else if (b > last.distM) {
    b = last.distM;
    a = b - w;
  }
  return (elevationAt(points, b) - elevationAt(points, a)) / (b - a);
}

export interface GradeBucket {
  /** Lower bound (fraction, inclusive); the first bucket has `-Infinity`. */
  min: number;
  color: string;
  label: string;
}

/** TV-style gradient colors, from flat/descent to steep. */
export const GRADE_BUCKETS: readonly GradeBucket[] = [
  { min: -Infinity, color: '#4d5b70', label: '< 2 %' },
  { min: 0.02, color: '#7cb342', label: '2–4 %' },
  { min: 0.04, color: '#fdd835', label: '4–6 %' },
  { min: 0.06, color: '#fb8c00', label: '6–8 %' },
  { min: 0.08, color: '#e53935', label: '8–10 %' },
  { min: 0.1, color: '#a3162b', label: '≥ 10 %' },
];

/** Index in `GRADE_BUCKETS` of the gradient `g` (fraction). */
export function gradeBucket(g: number): number {
  let i = 0;
  for (let k = 1; k < GRADE_BUCKETS.length; k++) if (g >= GRADE_BUCKETS[k]!.min) i = k;
  return i;
}

export function gradeColor(g: number): string {
  return GRADE_BUCKETS[gradeBucket(g)]!.color;
}
