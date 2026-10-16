import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent, type RefObject } from 'react';
import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { buildGeometry, drawOverlay, drawStatic, prepareCanvas, readTheme, type Size, type Theme } from './profile/draw';
import './profile/ProfileView.css';

/**
 * Elevation profile slot (canvas): the course profile, climbs shaded, groups as markers — the race's
 * "TV graphic". Fills its parent box.
 *
 * Two stacked canvases: the course (gradient-colored profile, climbs, km marks, finish) is drawn only when the
 * size or the race changes; the overlay (covered part, race head, groups, hover) is redrawn on every render.
 * Both follow the box size (ResizeObserver) and the device pixel ratio.
 */
export interface ProfileViewProps {
  meta: RaceMeta;
  snapshot: RaceSnapshot;
}

export function ProfileView({ meta, snapshot }: ProfileViewProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const baseRef = useRef<HTMLCanvasElement>(null);
  const overRef = useRef<HTMLCanvasElement>(null);
  const themeRef = useRef<Theme | null>(null);
  const size = useBoxSize(wrapRef);
  const [hoverX, setHoverX] = useState<number | null>(null);

  const geometry = useMemo(() => (size && size.w > 0 && size.h > 0 ? buildGeometry(meta, size) : null), [meta, size]);

  useEffect(() => {
    const canvas = baseRef.current;
    const wrap = wrapRef.current;
    if (!geometry || !canvas || !wrap) return;
    const theme = readTheme(wrap);
    themeRef.current = theme;
    const ctx = prepareCanvas(canvas, geometry.size);
    if (ctx) drawStatic(ctx, meta, geometry, theme);
  }, [meta, geometry]);

  useEffect(() => {
    const canvas = overRef.current;
    const theme = themeRef.current;
    if (!geometry || !canvas || !theme) return;
    const ctx = prepareCanvas(canvas, geometry.size);
    if (ctx) drawOverlay(ctx, meta, snapshot, geometry, theme, hoverX);
  }, [meta, snapshot, geometry, hoverX]);

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - r.left;
    const plot = geometry?.scale.rect;
    setHoverX(plot && x >= plot.left - 4 && x <= plot.left + plot.width + 4 ? x : null);
  };

  return (
    <div
      ref={wrapRef}
      className="profile-view"
      onPointerMove={onPointerMove}
      onPointerLeave={() => setHoverX(null)}
      role="img"
      aria-label={`Elevation profile of ${meta.course.name}: ${(meta.course.distanceM / 1000).toFixed(0)} km, ${meta.climbs.length} climbs`}
    >
      <canvas ref={baseRef} />
      <canvas ref={overRef} />
    </div>
  );
}

/** CSS size of the element and the device pixel ratio, updated on resize and on dpr changes (zoom, screen move). */
function useBoxSize(ref: RefObject<HTMLElement | null>): Size | null {
  const [size, setSize] = useState<Size | null>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      const r = el.getBoundingClientRect();
      const next = { w: Math.round(r.width), h: Math.round(r.height), dpr: window.devicePixelRatio || 1 };
      setSize((s) => (s && s.w === next.w && s.h === next.h && s.dpr === next.dpr ? s : next));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    // A dpr change fires the media query for the current ratio; re-arm it for the new one each time.
    let mq: MediaQueryList | null = null;
    const onDpr = () => {
      measure();
      arm();
    };
    const arm = () => {
      mq?.removeEventListener('change', onDpr);
      mq = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
      mq.addEventListener('change', onDpr);
    };
    arm();
    return () => {
      ro.disconnect();
      mq?.removeEventListener('change', onDpr);
    };
  }, [ref]);

  return size;
}
