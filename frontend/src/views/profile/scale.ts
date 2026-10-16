import type { CoursePoint } from '../../api/types';
import { elevationAt } from './grade';

/** Smallest "nice" step (1, 2, 2.5, 5 × 10^n) that is at least `raw`. */
export function niceStep(raw: number): number {
  if (!(raw > 0) || !Number.isFinite(raw)) return 1;
  const p = 10 ** Math.floor(Math.log10(raw));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= raw * (1 - 1e-9)) return m * p;
  return 10 * p;
}

/** Multiples of `step` in `[from, to]`. */
export function ticks(from: number, to: number, step: number): number[] {
  const out: number[] = [];
  if (!(step > 0) || to < from) return out;
  const n0 = Math.ceil(from / step - 1e-9);
  for (let n = n0; n * step <= to + 1e-9; n++) out.push(Math.round(n * step * 1e6) / 1e6 + 0);
  return out;
}

/** Km label step so that labels are at least `minSpacingPx` apart on a `widthPx` wide axis. */
export function kmStep(totalM: number, widthPx: number, minSpacingPx = 70): number {
  const km = totalM / 1000;
  if (!(km > 0) || !(widthPx > 0)) return 10;
  return niceStep((km * minSpacingPx) / widthPx);
}

export interface EleRange {
  min: number;
  max: number;
  step: number;
}

/**
 * Elevation axis: from 0 (or a round value under the lowest point for high courses) up to a round value above
 * the highest point, with a tick step giving about `targetTicks` grid lines. Never flatter than `minSpanM`.
 */
export function elevationRange(minEle: number, maxEle: number, targetTicks = 3, minSpanM = 300): EleRange {
  const lo0 = Number.isFinite(minEle) ? minEle : 0;
  const hi0 = Number.isFinite(maxEle) ? Math.max(maxEle, lo0) : lo0;
  let lo = lo0 < 400 ? 0 : lo0 - Math.max(100, (hi0 - lo0) * 0.25);
  let hi = Math.max(hi0, lo + minSpanM);
  const step = niceStep((hi - lo) / targetTicks);
  lo = Math.floor(lo / step) * step;
  hi = Math.ceil((hi + (hi - lo) * 0.05) / step) * step;
  return { min: lo, max: hi, step };
}

/** The plot area inside the canvas, CSS px. */
export interface PlotRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** Linear mappings between course distance / elevation and plot coordinates. */
export class ProfileScale {
  constructor(
    readonly rect: PlotRect,
    readonly totalM: number,
    readonly ele: EleRange,
  ) {}

  x(distM: number): number {
    const f = this.totalM > 0 ? distM / this.totalM : 0;
    return this.rect.left + Math.max(0, Math.min(1, f)) * this.rect.width;
  }

  y(eleM: number): number {
    const span = this.ele.max - this.ele.min;
    const f = span > 0 ? (eleM - this.ele.min) / span : 0;
    return this.rect.top + (1 - f) * this.rect.height;
  }

  /** Course distance at plot x, clamped to the course. */
  dist(x: number): number {
    const f = this.rect.width > 0 ? (x - this.rect.left) / this.rect.width : 0;
    return Math.max(0, Math.min(1, f)) * this.totalM;
  }

  get bottom(): number {
    return this.rect.top + this.rect.height;
  }

  get right(): number {
    return this.rect.left + this.rect.width;
  }
}

export interface ProfileColumns {
  /** Highest elevation in each column (m), so that summits survive downsampling. */
  ele: Float64Array;
  /** Gradient of the course segment holding the column center (fraction). */
  grade: Float64Array;
}

/**
 * Samples the course into `n` equal-width columns over `[0, totalM]`. Each column's elevation is the highest of
 * the points inside it and the interpolated elevations at its edges; its gradient is the average gradient of the
 * `segmentM` long course segment (counted from the start) that holds the column center, giving the blocky TV look.
 */
export function profileColumns(points: readonly CoursePoint[], totalM: number, n: number, segmentM = 1000): ProfileColumns {
  const cols = Math.max(0, Math.floor(n));
  const ele = new Float64Array(cols);
  const grade = new Float64Array(cols);
  if (cols === 0 || points.length === 0) return { ele, grade };
  const w = totalM / cols;
  const seg = segmentM > 0 ? segmentM : totalM;
  let segIndex = -1;
  let segGrade = 0;
  let p = 0;
  for (let i = 0; i < cols; i++) {
    const a = i * w;
    const b = (i + 1) * w;
    let m = Math.max(elevationAt(points, a), elevationAt(points, b));
    while (p < points.length && points[p]!.distM < a) p++;
    for (let k = p; k < points.length && points[k]!.distM <= b; k++) m = Math.max(m, points[k]!.eleM);
    ele[i] = m;
    const k = Math.floor((a + w / 2) / seg);
    if (k !== segIndex) {
      segIndex = k;
      const s0 = k * seg;
      const s1 = Math.min(totalM, s0 + seg);
      segGrade = s1 > s0 ? (elevationAt(points, s1) - elevationAt(points, s0)) / (s1 - s0) : 0;
    }
    grade[i] = segGrade;
  }
  return { ele, grade };
}

/** Length of the uniformly colored profile segments: 1 km, longer when a km gets narrower than `minPx`. */
export function gradeSegmentM(totalM: number, widthPx: number, minPx = 4): number {
  if (!(totalM > 0) || !(widthPx > 0)) return 1000;
  return Math.max(1000, niceStep((totalM / widthPx) * minPx));
}
