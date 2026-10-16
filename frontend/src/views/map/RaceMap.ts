import { Map as MapLibreMap, Marker, NavigationControl, type GeoJSONSource, type StyleSpecification } from 'maplibre-gl';
import { positionAt } from '../../replay/course';
import { formatGrade, formatKm } from '../../replay/format';
import { riderColor, type RaceMeta } from '../../replay/meta';
import type { GroupState, RaceSnapshot } from '../../replay/source';
import { courseBounds, courseSlice, metersPerPixel } from './geo';
import { pelotonId } from '../../replay/peloton';
import { declutter, followedGroup, FOLLOW_NONE, groupLabelText, labelPriority, visibleBoxes, type FollowTarget, type LabelBox } from './labels';
import { DOT_RADIUS_STOPS, dotSpacingPx, spacingFor, spreadRiders, type PlacedRider } from './spread';

/**
 * Imperative MapLibre side of the map view: React never renders markers. `update(snapshot)` is called on
 * every animation frame while playing and only pushes GeoJSON (rider dots) and moves a handful of HTML
 * labels (one per group).
 */

// The MapLibre worker URL is set once in main.tsx (shared by every map).

export const STYLE_URL ='https://tiles.openfreemap.org/styles/liberty';

/** Plain background used when the OpenFreeMap style cannot be loaded (offline): the race still shows. */
const FALLBACK_STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#dde3ea' } }],
};

const SRC_COURSE = 'ucai-course';
const SRC_CLIMBS = 'ucai-climbs';
const SRC_RIDERS = 'ucai-riders';

/** Label sits this far right of the group head, px. */
const LABEL_DX = 12;
/** Zoom the camera goes to when it starts following, unless already closer. */
const FOLLOW_ZOOM = 12;
/** A group label that would be pushed further than this from its group is hidden. */
const MAX_LABEL_SHIFT_PX = 70;

export interface RaceMapCallbacks {
  /** A group label was clicked: follow that group (identified by its head rider). */
  onSelectRider: (riderId: number) => void;
  /** The user dragged the map: stop following. */
  onUserMove: () => void;
}

interface GroupLabel {
  marker: Marker;
  el: HTMLDivElement;
  size: HTMLSpanElement;
  title: HTMLSpanElement;
  gap: HTMLSpanElement;
  /** Cached text key and measured size (measuring forces a layout, so only when the text changes). */
  key: string;
  w: number;
  h: number;
  headRiderId: number;
}

interface ClimbLabel {
  marker: Marker;
  name: HTMLDivElement;
  lon: number;
  lat: number;
  w: number;
  h: number;
}

interface RiderFeature {
  type: 'Feature';
  geometry: { type: 'Point'; coordinates: [number, number] };
  properties: { color: string; row: number; sel: boolean };
}

export class RaceMap {
  private readonly map: MapLibreMap;
  private readonly meta: RaceMeta;
  private readonly cb: RaceMapCallbacks;
  private ready = false;
  private fellBack = false;
  private last: RaceSnapshot | null = null;
  private follow: FollowTarget = FOLLOW_NONE;
  /** True while the camera eases to a newly followed group (per-frame jumps would cancel it). */
  private easing = false;
  private readonly groupLabels = new Map<number, GroupLabel>();
  private readonly climbLabels: ClimbLabel[] = [];
  private finish: { marker: Marker; count: HTMLSpanElement } | null = null;
  private readonly riderColors: string[];
  private readonly riderFeatures: RiderFeature[];
  private destroyed = false;

  constructor(container: HTMLElement, meta: RaceMeta, cb: RaceMapCallbacks) {
    this.meta = meta;
    this.cb = cb;
    this.riderColors = meta.riders.map((r) => riderColor(meta, r.id));
    this.riderFeatures = meta.riders.map((_, i) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [0, 0] },
      properties: { color: this.riderColors[i]!, row: 0, sel: false },
    }));
    const bounds = courseBounds(meta.course.points);
    this.map = new MapLibreMap({
      container,
      style: STYLE_URL,
      ...(bounds ? { bounds, fitBoundsOptions: { padding: 48 } } : { center: [0, 0], zoom: 2 }),
      attributionControl: { compact: true },
      dragRotate: false,
      pitchWithRotate: false,
      touchPitch: false,
    });
    this.map.touchZoomRotate.disableRotation();
    this.map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    this.map.on('style.load', () => this.onStyleLoad());
    this.map.on('error', () => {
      // The style itself failed (no network): fall back to a plain background once.
      if (!this.ready && !this.fellBack) {
        this.fellBack = true;
        this.map.setStyle(FALLBACK_STYLE);
      }
    });
    this.map.on('zoom', () => this.render());
    this.map.on('zoomend', () => this.layoutClimbs());
    this.map.on('dragstart', () => {
      if (this.follow.kind !== 'none') this.cb.onUserMove();
    });
    this.addStaticMarkers();
  }

  destroy(): void {
    this.destroyed = true;
    this.map.remove();
  }

  /** Fits the camera to the whole course. */
  fitCourse(animate = true): void {
    const bounds = courseBounds(this.meta.course.points);
    if (bounds) this.map.fitBounds(bounds, { padding: 48, duration: animate ? 700 : 0 });
  }

  setFollow(target: FollowTarget): void {
    const same =
      target.kind === this.follow.kind && (target.kind !== 'rider' || (this.follow.kind === 'rider' && this.follow.riderId === target.riderId));
    this.follow = target;
    if (same) return;
    // While following, zoom around the followed group rather than the mouse pointer.
    this.map.scrollZoom.enable(target.kind === 'none' ? undefined : { around: 'center' });
    this.easing = false;
    const center = this.last && this.followCenter(this.last, spreadRiders(this.meta.course.points, this.last, this.spacing()));
    if (center) {
      this.easing = true;
      this.map.easeTo({ center, zoom: Math.max(this.map.getZoom(), FOLLOW_ZOOM), duration: 700 });
      this.map.once('moveend', () => {
        this.easing = false;
      });
    }
    this.render();
  }

  update(snapshot: RaceSnapshot): void {
    this.last = snapshot;
    this.render();
  }

  // --- rendering ---------------------------------------------------------------------------------------

  private spacing() {
    const lat = this.last?.groups[0]?.lat ?? this.meta.course.points[0]?.lat ?? 45;
    const zoom = this.map.getZoom();
    return spacingFor(metersPerPixel(lat, zoom), dotSpacingPx(zoom));
  }

  private render(): void {
    const snapshot = this.last;
    if (!snapshot || !this.ready || this.destroyed) return;
    const placed = spreadRiders(this.meta.course.points, snapshot, this.spacing());
    const followed = followedGroup(snapshot, this.follow);
    this.renderRiders(placed, followed);
    this.renderGroups(snapshot, followed);
    this.renderFinish(snapshot);
    // Never fight the user's (or the camera's own) zoom animation; the next frame recenters.
    if (followed && !this.easing && !this.map.isZooming()) {
      const center = this.followCenter(snapshot, placed);
      if (center) this.map.jumpTo({ center });
    }
  }

  private renderRiders(placed: readonly PlacedRider[], followed: GroupState | null): void {
    const features: RiderFeature[] = [];
    for (const p of placed) {
      const f = this.riderFeatures[p.rider.index]!;
      f.geometry.coordinates[0] = p.lon;
      f.geometry.coordinates[1] = p.lat;
      f.properties.row = p.row;
      f.properties.sel = followed !== null && p.rider.groupId === followed.id;
      features.push(f);
    }
    const src = this.map.getSource<GeoJSONSource>(SRC_RIDERS);
    void src?.setData({ type: 'FeatureCollection', features });
  }

  private renderGroups(snapshot: RaceSnapshot, followed: GroupState | null): void {
    const seen = new Set<number>();
    const peloton = pelotonId(snapshot.groups);
    const boxes: LabelBox[] = [];
    const labels: GroupLabel[] = [];
    snapshot.groups.forEach((g, i) => {
      seen.add(g.id);
      let label = this.groupLabels.get(g.id);
      if (!label) {
        label = this.createGroupLabel(g);
        this.groupLabels.set(g.id, label);
      }
      label.headRiderId = g.headRiderId;
      const text = groupLabelText(this.meta, snapshot, i, peloton);
      const lead = i === 0 && snapshot.finishedRiderIds.length === 0;
      const key = `${text.title}|${text.size}|${text.gap}|${lead}`;
      if (key !== label.key) {
        label.key = key;
        label.size.textContent = String(text.size);
        label.title.textContent = text.title;
        label.gap.textContent = text.gap;
        label.gap.hidden = text.gap === '';
        label.el.classList.toggle('lead', lead);
        label.w = label.el.offsetWidth;
        label.h = label.el.offsetHeight;
      }
      label.el.classList.toggle('followed', followed?.id === g.id);
      label.marker.setLngLat([g.lon, g.lat]);
      const pt = this.map.project([g.lon, g.lat]);
      boxes.push({ x: pt.x + LABEL_DX, y: pt.y, w: label.w || 80, h: label.h || 22 });
      labels.push(label);
    });
    const order = labelPriority(snapshot.groups, followed?.id ?? null);
    const shifts = declutter(
      order.map((i) => boxes[i]!),
      2,
      MAX_LABEL_SHIFT_PX,
    );
    order.forEach((gi, k) => {
      const l = labels[gi]!;
      const shift = shifts[k];
      const hidden = shift === null || shift === undefined;
      const vis = hidden ? 'hidden' : '';
      if (l.el.style.visibility !== vis) l.el.style.visibility = vis;
      if (!hidden) l.marker.setOffset([LABEL_DX, shift]);
    });
    for (const [id, l] of this.groupLabels) {
      if (!seen.has(id)) {
        l.marker.remove();
        this.groupLabels.delete(id);
      }
    }
  }

  private createGroupLabel(g: GroupState): GroupLabel {
    const el = document.createElement('div');
    el.className = 'mv-group';
    el.title = 'Follow this group';
    const size = document.createElement('span');
    size.className = 'mv-group-size';
    const title = document.createElement('span');
    title.className = 'mv-group-title';
    const gap = document.createElement('span');
    gap.className = 'mv-group-gap';
    el.append(size, title, gap);
    const marker = new Marker({ element: el, anchor: 'left', offset: [LABEL_DX, 0] }).setLngLat([g.lon, g.lat]).addTo(this.map);
    const label: GroupLabel = { marker, el, size, title, gap, key: '', w: 0, h: 0, headRiderId: g.headRiderId };
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      this.cb.onSelectRider(label.headRiderId);
    });
    return label;
  }

  private renderFinish(snapshot: RaceSnapshot): void {
    if (!this.finish) return;
    const n = snapshot.finishedRiderIds.length;
    const text = n > 0 ? `${n}/${this.meta.riders.length}` : '';
    if (this.finish.count.textContent !== text) {
      this.finish.count.textContent = text;
      this.finish.count.hidden = text === '';
    }
  }

  /** Middle of the followed group as drawn (between its head and its last row), or the finish line. */
  private followCenter(snapshot: RaceSnapshot, placed: readonly PlacedRider[]): [number, number] | null {
    const g = followedGroup(snapshot, this.follow);
    if (!g) {
      if (this.follow.kind === 'none' || snapshot.finishedRiderIds.length === 0) return null;
      const last = this.meta.course.points[this.meta.course.points.length - 1];
      return last ? [last.lon, last.lat] : null;
    }
    let tail: PlacedRider | null = null;
    for (const p of placed) if (p.rider.groupId === g.id && (!tail || p.row > tail.row)) tail = p;
    if (!tail) return [g.lon, g.lat];
    return [(g.lon + tail.lon) / 2, (g.lat + tail.lat) / 2];
  }

  // --- setup -------------------------------------------------------------------------------------------

  private onStyleLoad(): void {
    if (this.destroyed) return;
    const map = this.map;
    const { course } = this.meta;
    if (!map.getSource(SRC_COURSE)) {
      map.addSource(SRC_COURSE, {
        type: 'geojson',
        data: {
          type: 'Feature',
          properties: {},
          geometry: { type: 'LineString', coordinates: course.points.map((p) => [p.lon, p.lat]) },
        },
      });
      map.addSource(SRC_CLIMBS, {
        type: 'geojson',
        data: {
          type: 'FeatureCollection',
          features: this.meta.climbs.map((c) => ({
            type: 'Feature',
            properties: { name: c.name },
            geometry: { type: 'LineString', coordinates: courseSlice(course.points, c.startM, c.endM) },
          })),
        },
      });
      map.addSource(SRC_RIDERS, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      const lineLayout = { 'line-cap': 'round', 'line-join': 'round' } as const;
      map.addLayer({ id: 'ucai-course-casing', type: 'line', source: SRC_COURSE, layout: lineLayout, paint: { 'line-color': '#ffffff', 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 5, 14, 10] } });
      map.addLayer({ id: 'ucai-course', type: 'line', source: SRC_COURSE, layout: lineLayout, paint: { 'line-color': '#d0103a', 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 2.5, 14, 5] } });
      map.addLayer({ id: 'ucai-climbs', type: 'line', source: SRC_CLIMBS, layout: lineLayout, paint: { 'line-color': '#ff9500', 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 3, 14, 6] } });
      const radius: unknown[] = ['interpolate', ['linear'], ['zoom']];
      for (const [z, r] of DOT_RADIUS_STOPS) radius.push(z, r);
      map.addLayer({
        id: 'ucai-riders',
        type: 'circle',
        source: SRC_RIDERS,
        layout: { 'circle-sort-key': ['-', 0, ['get', 'row']] },
        paint: {
          'circle-radius': radius as never,
          'circle-color': ['get', 'color'],
          'circle-stroke-color': ['case', ['get', 'sel'], '#ffd400', '#101010'],
          'circle-stroke-width': ['case', ['get', 'sel'], 1.6, 0.8],
          'circle-pitch-alignment': 'map',
        },
      });
    }
    if (!this.ready) {
      // The slot may have been resized by the grid since the map was created.
      map.resize();
      const center = this.follow.kind !== 'none' && this.last ? this.followCenter(this.last, spreadRiders(course.points, this.last, this.spacing())) : null;
      if (center) map.jumpTo({ center, zoom: Math.max(map.getZoom(), FOLLOW_ZOOM) });
      else this.fitCourse(false);
    }
    this.ready = true;
    this.layoutClimbs();
    this.render();
  }

  private addStaticMarkers(): void {
    const { points } = this.meta.course;
    const first = points[0];
    const last = points[points.length - 1];
    if (!first || !last) return;
    const start = flag('START', 'mv-flag mv-start');
    new Marker({ element: start.el, anchor: 'bottom-left', offset: [-2, 0] }).setLngLat([first.lon, first.lat]).addTo(this.map);
    const fin = flag('FINISH', 'mv-flag mv-finish');
    const finMarker = new Marker({ element: fin.el, anchor: 'bottom-left', offset: [-2, 0] }).setLngLat([last.lon, last.lat]).addTo(this.map);
    this.finish = { marker: finMarker, count: fin.count };

    for (const c of this.meta.climbs) {
      const top = positionAt(points, c.endM);
      const el = document.createElement('div');
      el.className = 'mv-climb';
      el.title = `${c.name}: ${formatKm(c.endM - c.startM)} at ${formatGrade(c.avgGrade)} (max ${formatGrade(c.maxGrade)}), summit at km ${(c.endM / 1000).toFixed(1)}`;
      const name = document.createElement('div');
      name.className = 'mv-climb-name';
      name.textContent = c.name;
      const tri = document.createElement('div');
      tri.className = 'mv-climb-tri';
      el.append(name, tri);
      const marker = new Marker({ element: el, anchor: 'bottom' }).setLngLat([top.lon, top.lat]).addTo(this.map);
      this.climbLabels.push({ marker, name, lon: top.lon, lat: top.lat, w: 0, h: 0 });
    }
  }

  /** Hides climb names that would overlap (later climbs, closer to the finale, win). */
  private layoutClimbs(): void {
    if (this.destroyed) return;
    const order = this.climbLabels.map((_, i) => i).reverse();
    const rects = order.map((i) => {
      const c = this.climbLabels[i]!;
      if (!c.w) {
        c.name.hidden = false;
        c.w = c.name.offsetWidth;
        c.h = c.name.offsetHeight;
      }
      const p = this.map.project([c.lon, c.lat]);
      const w = c.w || 100;
      const h = c.h || 16;
      return { x0: p.x - w / 2, x1: p.x + w / 2, y0: p.y - 10 - h, y1: p.y - 10 };
    });
    const vis = visibleBoxes(rects);
    order.forEach((i, k) => {
      this.climbLabels[i]!.name.hidden = !vis[k];
    });
  }
}

function flag(text: string, className: string): { el: HTMLDivElement; count: HTMLSpanElement } {
  const el = document.createElement('div');
  el.className = className;
  const label = document.createElement('span');
  label.textContent = text;
  const count = document.createElement('span');
  count.className = 'mv-flag-count';
  count.hidden = true;
  el.append(label, count);
  return { el, count };
}

export type { FollowTarget };
