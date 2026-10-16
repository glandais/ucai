import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent, type RefObject } from 'react';
import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { buildGeometry, courseLengthM, drawOverlay, drawStatic, prepareCanvas, readTheme, type Size, type Theme } from './profile/draw';
import './profile/ProfileView.css';

/**
 * Elevation profile slot (canvas): the course profile, climbs shaded, groups as markers — the race's
 * "TV graphic". Fills its parent box.
 *
 * Two stacked canvases: the course (gradient-colored profile, climbs, km marks, finish) is drawn only when the
 * size or the race changes; the overlay (covered part, race head, groups, hover) is redrawn on every render.
 * Both follow the box size (ResizeObserver) and the device pixel ratio.
 *
 * Zoom: the whole course, or the last `FINALE_M` (where the groups bunch up on the final climbs). In `auto`
 * (default) the profile switches to the finale once the head of the race is `FINALE_SWITCH_M` from the line.
 */
export interface ProfileViewProps {
  meta: RaceMeta;
  snapshot: RaceSnapshot;
}

/** Length of the finale window, m. */
export const FINALE_M = 50_000;
/** In auto zoom, the finale window shows once the head of the race is this far from the finish, m. */
export const FINALE_SWITCH_M = 40_000;
/** Width reserved for the zoom toggle at the top left (climb names stay right of it), CSS px. */
const TOGGLE_W = 112;

type Zoom = 'auto' | 'full' | 'finale';

export function ProfileView({ meta, snapshot }: ProfileViewProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const baseRef = useRef<HTMLCanvasElement>(null);
  const overRef = useRef<HTMLCanvasElement>(null);
  const themeRef = useRef<Theme | null>(null);
  const size = useBoxSize(wrapRef);
  const [hoverX, setHoverX] = useState<number | null>(null);

  const [zoom, setZoom] = useState<Zoom>('auto');
  const totalM = courseLengthM(meta);
  const canZoom = totalM > FINALE_M * 1.5;
  const finale = canZoom && (zoom === 'finale' || (zoom === 'auto' && totalM - snapshot.headDistM <= FINALE_SWITCH_M));
  const startM = finale ? totalM - FINALE_M : 0;

  const geometry = useMemo(
    () => (size && size.w > 0 && size.h > 0 ? buildGeometry(meta, size, startM) : null),
    [meta, size, startM],
  );

  useEffect(() => {
    const canvas = baseRef.current;
    const wrap = wrapRef.current;
    if (!geometry || !canvas || !wrap) return;
    const theme = readTheme(wrap);
    themeRef.current = theme;
    const ctx = prepareCanvas(canvas, geometry.size);
    if (ctx) drawStatic(ctx, meta, geometry, theme, canZoom ? TOGGLE_W : 2);
  }, [meta, geometry, canZoom]);

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
      {canZoom && (
        <button
          type="button"
          className={`profile-zoom${finale ? ' active' : ''}`}
          style={{ maxWidth: TOGGLE_W - 12 }}
          onPointerMove={(e) => e.stopPropagation()}
          onClick={() => setZoom(finale ? 'full' : 'finale')}
          title={finale ? 'Show the whole course' : `Zoom on the last ${FINALE_M / 1000} km`}
        >
          {finale ? `Last ${FINALE_M / 1000} km` : 'Full course'}
        </button>
      )}
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
