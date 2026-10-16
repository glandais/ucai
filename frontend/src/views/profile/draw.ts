import { formatKm, formatGrade } from '../../replay/format';
import type { RaceMeta } from '../../replay/meta';
import type { RaceSnapshot } from '../../replay/source';
import { gradeColor, GRADE_BUCKETS, elevationAt } from './grade';
import { assignLanes, clampBox } from './layout';
import { BUBBLE_H, LANE_PITCH, groupMarkers, hoverInfo } from './markers';
import { ProfileScale, elevationRange, gradeSegmentM, kmStep, profileColumns, ticks, type ProfileColumns } from './scale';

/** Canvas drawing for the profile: a static layer (redrawn on resize) and an overlay (every frame). */

export interface Theme {
  bg: string;
  panel: string;
  line: string;
  text: string;
  muted: string;
  accent: string;
  accentText: string;
  font: string;
}

export function readTheme(el: Element): Theme {
  const s = getComputedStyle(el);
  const v = (name: string, fallback: string) => s.getPropertyValue(name).trim() || fallback;
  return {
    bg: v('--bg', '#0b0f17'),
    panel: v('--panel', '#131a26'),
    line: v('--line', '#2a3547'),
    text: v('--text', '#f2f5fa'),
    muted: v('--muted', '#8d99ab'),
    accent: v('--accent', '#ffd400'),
    accentText: v('--accent-text', '#111111'),
    font: v('--font', 'system-ui, sans-serif'),
  };
}

export interface Size {
  /** CSS px */
  w: number;
  h: number;
  dpr: number;
}

/** Plot margins, CSS px: room for elevation labels (left), the finish label (right), labels and markers (top). */
export const PAD = { left: 44, right: 16, top: 46, bottom: 20 };

export interface Geometry {
  size: Size;
  scale: ProfileScale;
  /** One column per device pixel of the plot width. */
  columns: ProfileColumns;
  /** Profile area (under the curve) in CSS px. */
  area: Path2D;
}

export function buildGeometry(meta: RaceMeta, size: Size): Geometry {
  const { points, distanceM } = meta.course;
  const total = distanceM > 0 ? distanceM : (points[points.length - 1]?.distM ?? 0);
  let lo = Infinity;
  let hi = -Infinity;
  for (const p of points) {
    lo = Math.min(lo, p.eleM);
    hi = Math.max(hi, p.eleM);
  }
  const rect = {
    left: PAD.left,
    top: PAD.top,
    width: Math.max(1, size.w - PAD.left - PAD.right),
    height: Math.max(1, size.h - PAD.top - PAD.bottom),
  };
  const scale = new ProfileScale(rect, total, elevationRange(lo, hi, rect.height > 90 ? 3 : 2));
  const n = Math.max(1, Math.round(rect.width * size.dpr));
  const columns = profileColumns(points, total, n, gradeSegmentM(total, rect.width));
  const area = new Path2D();
  area.moveTo(rect.left, scale.bottom);
  for (let i = 0; i < n; i++) {
    const x0 = rect.left + (i / n) * rect.width;
    const y = scale.y(columns.ele[i]!);
    area.lineTo(x0, y);
    area.lineTo(rect.left + ((i + 1) / n) * rect.width, y);
  }
  area.lineTo(scale.right, scale.bottom);
  area.closePath();
  return { size, scale, columns, area };
}

const font = (t: Theme, px: number, weight = 400) => `${weight} ${px}px ${t.font}`;

/** Resizes the canvas backing store to the size × dpr and returns a context drawing in CSS px. */
export function prepareCanvas(canvas: HTMLCanvasElement, size: Size): CanvasRenderingContext2D | null {
  const w = Math.max(1, Math.round(size.w * size.dpr));
  const h = Math.max(1, Math.round(size.h * size.dpr));
  if (canvas.width !== w) canvas.width = w;
  if (canvas.height !== h) canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.setTransform(size.dpr, 0, 0, size.dpr, 0, 0);
  ctx.clearRect(0, 0, size.w, size.h);
  return ctx;
}

export function drawStatic(ctx: CanvasRenderingContext2D, meta: RaceMeta, g: Geometry, t: Theme): void {
  const { scale, columns, size } = g;
  const { rect } = scale;
  const { dpr } = size;

  ctx.fillStyle = t.panel;
  ctx.fillRect(0, 0, size.w, size.h);

  // Elevation grid.
  ctx.font = font(t, 10);
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  ctx.lineWidth = 1;
  for (const e of ticks(scale.ele.min, scale.ele.max, scale.ele.step)) {
    const y = Math.round(scale.y(e)) + 0.5;
    if (y < rect.top - 12) continue;
    ctx.strokeStyle = t.line;
    ctx.beginPath();
    ctx.moveTo(rect.left, y);
    ctx.lineTo(scale.right, y);
    ctx.stroke();
    ctx.fillStyle = t.muted;
    ctx.fillText(`${e} m`, rect.left - 6, y);
  }

  // Climb bands.
  ctx.fillStyle = 'rgba(255, 255, 255, 0.06)';
  for (const c of meta.climbs) {
    const x0 = scale.x(c.startM);
    ctx.fillRect(x0, rect.top - 8, Math.max(1, scale.x(c.endM) - x0), scale.bottom - rect.top + 8);
  }

  // Gradient-colored profile, one device-pixel column at a time (crisp at any dpr).
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  const left = Math.round(rect.left * dpr);
  const bottom = Math.round(scale.bottom * dpr);
  let runStart = 0;
  let runColor = '';
  const flush = (end: number) => {
    if (end <= runStart) return;
    ctx.fillStyle = runColor;
    ctx.beginPath();
    ctx.moveTo(left + runStart, bottom);
    for (let i = runStart; i < end; i++) {
      const y = scale.y(columns.ele[i]!) * dpr;
      ctx.lineTo(left + i, y);
      ctx.lineTo(left + i + 1, y);
    }
    ctx.lineTo(left + end, bottom);
    ctx.closePath();
    ctx.fill();
  };
  for (let i = 0; i < columns.ele.length; i++) {
    const c = gradeColor(columns.grade[i]!);
    if (c !== runColor) {
      flush(i);
      runStart = i;
      runColor = c;
    }
  }
  flush(columns.ele.length);
  ctx.restore();

  // Outline.
  ctx.save();
  ctx.clip(g.area);
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)';
  ctx.lineWidth = 2;
  ctx.lineJoin = 'round';
  ctx.stroke(g.area);
  ctx.restore();

  // Km axis.
  ctx.strokeStyle = t.line;
  ctx.beginPath();
  ctx.moveTo(rect.left, Math.round(scale.bottom) + 0.5);
  ctx.lineTo(scale.right, Math.round(scale.bottom) + 0.5);
  ctx.stroke();
  const step = kmStep(scale.totalM, rect.width);
  ctx.font = font(t, 10);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (const km of ticks(0, scale.totalM / 1000, step)) {
    const x = Math.round(scale.x(km * 1000)) + 0.5;
    if (scale.right - x < 40) continue;
    ctx.strokeStyle = t.muted;
    ctx.beginPath();
    ctx.moveTo(x, scale.bottom);
    ctx.lineTo(x, scale.bottom + 4);
    ctx.stroke();
    ctx.fillStyle = t.muted;
    ctx.fillText(km === 0 ? '0 km' : String(km), x, scale.bottom + 6);
  }

  // Finish: checkered flag over a white post.
  const fx = Math.round(scale.right) + 0.5;
  const fTop = rect.top - 30;
  ctx.strokeStyle = t.text;
  ctx.beginPath();
  ctx.moveTo(fx, fTop);
  ctx.lineTo(fx, scale.bottom + 4);
  ctx.stroke();
  const sq = 4;
  for (let r = 0; r < 3; r++)
    for (let c = 0; c < 3; c++) {
      ctx.fillStyle = (r + c) % 2 === 0 ? '#ffffff' : '#000000';
      ctx.fillRect(fx - 3 * sq + c * sq - 0.5, fTop + r * sq, sq, sq);
    }
  ctx.fillStyle = t.text;
  ctx.font = font(t, 10, 700);
  ctx.textAlign = 'right';
  ctx.fillText(formatKm(scale.totalM), fx, scale.bottom + 6);

  drawClimbLabels(ctx, meta, scale, t);
}

/** Width of the checkered flag left of the finish post, CSS px. */
const FLAG_W = 12;

/** Climb name without its parenthesized subtitle: `Côte de Stockeu (Stèle Eddy Merckx)` → `Côte de Stockeu`. */
export function shortClimbName(name: string): string {
  return name.replace(/\s*\([^)]*\)/g, '').trim() || name;
}

/** Climb label rows (baselines above the plot, CSS px from the plot top). */
const CLIMB_ROWS = [10, 22, 34];

/**
 * Climb names in fixed rows above the plot, each with a leader line down to its summit. Harder climbs (height
 * gained) claim the lower rows first; a name that still doesn't fit is left out (the hover shows it).
 */
function drawClimbLabels(ctx: CanvasRenderingContext2D, meta: RaceMeta, scale: ProfileScale, t: Theme): void {
  const { rect } = scale;
  const nameFont = font(t, 11, 700);
  ctx.font = nameFont;
  const labels = meta.climbs
    .map((c) => {
      const name = shortClimbName(c.name);
      const w = ctx.measureText(name).width;
      const sx = scale.x(c.endM);
      return {
        name,
        w,
        sx,
        x0: clampBox(sx - w / 2, w, 2, scale.right - FLAG_W - 4),
        sy: scale.y(elevationAt(meta.course.points, c.endM)),
        gain: (c.endM - c.startM) * c.avgGrade,
      };
    })
    .sort((a, b) => b.gain - a.gain);
  const lanes = assignLanes(
    labels.map((l) => ({ x0: l.x0, x1: l.x0 + l.w })),
    10,
    CLIMB_ROWS.length,
    true,
  );
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  ctx.lineJoin = 'round';
  labels.forEach((l, i) => {
    const lane = lanes[i]!;
    // Summit marker.
    ctx.fillStyle = t.text;
    ctx.beginPath();
    ctx.moveTo(l.sx, l.sy - 1);
    ctx.lineTo(l.sx - 3.5, l.sy - 7);
    ctx.lineTo(l.sx + 3.5, l.sy - 7);
    ctx.closePath();
    ctx.fill();
    if (lane < 0) return;
    const baseY = rect.top - CLIMB_ROWS[lane]!;
    // Leader line from the label down to the summit.
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.35)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(Math.round(l.sx) + 0.5, baseY + 3);
    ctx.lineTo(Math.round(l.sx) + 0.5, l.sy - 7);
    ctx.stroke();
    // Name with a halo so it reads over the grid and the leader lines.
    ctx.font = nameFont;
    ctx.lineWidth = 3;
    ctx.strokeStyle = t.panel;
    ctx.strokeText(l.name, l.x0, baseY);
    ctx.fillStyle = t.text;
    ctx.fillText(l.name, l.x0, baseY);
  });
}

export function drawOverlay(
  ctx: CanvasRenderingContext2D,
  meta: RaceMeta,
  snapshot: RaceSnapshot,
  g: Geometry,
  t: Theme,
  hoverX: number | null,
): void {
  const { scale } = g;
  const { rect } = scale;

  // Dim the part of the course already covered by the head of the race.
  const hx = scale.x(snapshot.headDistM);
  if (hx > rect.left) {
    ctx.save();
    ctx.clip(g.area);
    ctx.fillStyle = 'rgba(11, 15, 23, 0.5)';
    ctx.fillRect(rect.left, rect.top - 40, hx - rect.left, scale.bottom - rect.top + 40);
    ctx.restore();
  }

  // Race head: accent line and km tag on the axis.
  if (!snapshot.raceFinished) {
    ctx.strokeStyle = t.accent;
    ctx.lineWidth = 1.5;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(hx, rect.top - 6);
    ctx.lineTo(hx, scale.bottom);
    ctx.stroke();
    ctx.setLineDash([]);
    const tag = (snapshot.headDistM / 1000).toFixed(1);
    ctx.font = font(t, 10, 700);
    let tw = ctx.measureText(tag).width + 10;
    let tx = clampBox(hx - tw / 2, tw, 0, g.size.w);
    // Near the finish, cover the finish distance label (right-aligned on the post) instead of overlapping it.
    const fx = Math.round(scale.right) + 0.5;
    const finishW = ctx.measureText(formatKm(scale.totalM)).width + 4;
    if (tx + tw > fx - finishW) {
      tw = Math.max(tw, finishW + 4);
      tx = Math.min(g.size.w - tw, fx + 2 - tw);
    }
    ctx.fillStyle = t.accent;
    roundRect(ctx, tx, scale.bottom + 2, tw, 15, 3);
    ctx.fill();
    ctx.fillStyle = t.accentText;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(tag, tx + tw / 2, scale.bottom + 10);
  }

  // Finished riders, on the finish post.
  const finished = snapshot.finishedRiderIds.length;
  if (finished > 0) {
    const label = `${finished} finished`;
    ctx.font = font(t, 10, 700);
    const w = ctx.measureText(label).width + 10;
    const x = scale.right - 4 - w;
    const y = rect.top + 2;
    ctx.fillStyle = t.text;
    roundRect(ctx, x, y, w, 15, 3);
    ctx.fill();
    ctx.fillStyle = t.bg;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, x + w / 2, y + 8);
  }

  // Groups on the road, back to front so the lead group is on top.
  const markers = groupMarkers(meta, snapshot, scale);
  ctx.font = font(t, 11, 700);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  for (let i = markers.length - 1; i >= 0; i--) {
    const m = markers[i]!;
    const cy = Math.max(BUBBLE_H / 2 + 1, m.curveY - 14 - m.lane * LANE_PITCH);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.7)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(m.x, cy + BUBBLE_H / 2);
    ctx.lineTo(m.x, m.curveY);
    ctx.stroke();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(m.x, m.curveY, 2.5, 0, Math.PI * 2);
    ctx.fill();
    roundRect(ctx, m.x - m.w / 2, cy - BUBBLE_H / 2, m.w, BUBBLE_H, BUBBLE_H / 2);
    ctx.fillStyle = m.color;
    ctx.fill();
    ctx.lineWidth = m.lead ? 2 : 1.25;
    ctx.strokeStyle = m.lead ? t.accent : 'rgba(255, 255, 255, 0.85)';
    ctx.stroke();
    ctx.fillStyle = m.textColor;
    ctx.fillText(m.label, m.x, cy + 0.5);
  }

  if (hoverX !== null) drawHover(ctx, meta, g, t, hoverX);
}

function drawHover(ctx: CanvasRenderingContext2D, meta: RaceMeta, g: Geometry, t: Theme, hoverX: number): void {
  const { scale } = g;
  const { rect } = scale;
  const x = Math.max(rect.left, Math.min(scale.right, hoverX));
  const info = hoverInfo(meta, scale.dist(x));
  const y = scale.y(info.eleM);

  ctx.strokeStyle = 'rgba(255, 255, 255, 0.6)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(Math.round(x) + 0.5, rect.top - 10);
  ctx.lineTo(Math.round(x) + 0.5, scale.bottom);
  ctx.stroke();
  ctx.fillStyle = t.text;
  ctx.strokeStyle = t.bg;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(x, y, 4, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();

  const lines: { text: string; color: string; weight: number }[] = [
    { text: `km ${(info.distM / 1000).toFixed(1)}  ·  ${formatKm(info.toGoM)} to go`, color: t.text, weight: 700 },
    { text: `${Math.round(info.eleM)} m  ·  ${info.grade > 0 ? '+' : ''}${formatGrade(info.grade)}`, color: gradeTextColor(info.grade, t), weight: 400 },
  ];
  if (info.climb) {
    const c = info.climb;
    lines.push({
      text: `${c.name}  ·  ${formatKm(c.endM - c.startM)} at ${formatGrade(c.avgGrade)} (max ${formatGrade(c.maxGrade)})`,
      color: t.accent,
      weight: 700,
    });
  }
  let w = 0;
  for (const l of lines) {
    ctx.font = font(t, 11, l.weight);
    w = Math.max(w, ctx.measureText(l.text).width);
  }
  w += 16;
  const h = lines.length * 15 + 8;
  const bx = x + 10 + w <= g.size.w - 2 ? x + 10 : x - 10 - w;
  const by = Math.max(2, Math.min(rect.top - 10, y - h - 10));
  ctx.fillStyle = 'rgba(19, 26, 38, 0.95)';
  roundRect(ctx, bx, by, w, h, 4);
  ctx.fill();
  ctx.strokeStyle = t.line;
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  lines.forEach((l, i) => {
    ctx.font = font(t, 11, l.weight);
    ctx.fillStyle = l.color;
    ctx.fillText(l.text, bx + 8, by + 4 + 7.5 + i * 15);
  });
}

/** Gradient color for text (the flat bucket is too dark for text on the panel). */
function gradeTextColor(g: number, t: Theme): string {
  return g < GRADE_BUCKETS[1]!.min ? t.text : gradeColor(g);
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}
