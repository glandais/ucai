# UCAI frontend (replay v1)

React + Vite + TypeScript (strict), MapLibre GL with OpenFreeMap tiles
(`https://tiles.openfreemap.org/styles/liberty`, no key). See `docs/DESIGN.md` §4 (replay format) and §7.

## Run

```sh
# 1. kernel + 3 races (from the repo root); keeps the kernel on :8080
./gradlew :runner:run --args="--courses milano-sanremo,liege-bastogne-liege,il-lombardia --seed 42 --out replays/ --serve"

# 2. frontend (from frontend/)
npm install
npm run dev          # http://localhost:5173, /api is proxied to http://localhost:8080
```

`UCAI_KERNEL=http://host:port npm run dev` points the proxy at another kernel. Without a kernel, use
**Open a local file** on the race list with `replays/<course>-42.json.gz` (or a plain `.json`).
`#/race/<raceId>` opens a kernel replay directly (e.g. `http://localhost:5173/#/race/race-1`).

| Script | |
|---|---|
| `npm run dev` | Vite dev server with the `/api` proxy |
| `npm run build` | typecheck + production build into `dist/` |
| `npm run typecheck` | `tsc` (no emit) |
| `npm test` | Vitest unit tests (`src/**/*.test.ts`) |
| `npm run screenshots` | regenerates `docs/screenshots/` (see **Screenshots**) |
| `npm run measure` | the **Measures** table (`scripts/measure.mjs`, same setup as the screenshots, plus `vite preview`) |

Playback keys: space = play/pause (also with a button focused); on the focused timeline ←/→ = ∓1 min (shift: 10 min), Home/End.

Map camera: follows the head of the race by default (TV style); **Whole course** fits the course, a click on a
group label follows that group, dragging the map stops following. Profile: **Full course** / **Last 50 km**
toggle (top left); in the default auto mode it zooms on the last 50 km once the head of the race is 40 km
from the line, groups behind the window are counted at its left edge.

## Screenshots

Seed 42 replays from the runner, 1440×900, served by the kernel (`--serve`) through the Vite proxy. To regenerate
them, start the runner with `--serve` and `npm run dev` as in **Run**, then `npm run screenshots`
(`scripts/screenshots.mjs`: headless Chrome through `playwright-core`, `CHROME=/path/to/chrome` if it is not
`/usr/bin/google-chrome`; it reads `replays/*-42.json.gz` to find the moments and writes `docs/screenshots/`).

| | |
|---|---|
| ![Milano-Sanremo, breakaway](../docs/screenshots/milano-sanremo-breakaway.png) | ![Milano-Sanremo, Poggio](../docs/screenshots/milano-sanremo-last-climb.png) |
| Milano-Sanremo, breakaway established (camera following the leaders, peloton at +5:10) | Milano-Sanremo, Poggio (profile zoomed on the last 50 km) |
| ![Liège-Bastogne-Liège, Roche-aux-Faucons](../docs/screenshots/liege-bastogne-liege-last-climb.png) | ![Il Lombardia, finish](../docs/screenshots/il-lombardia-finish.png) |
| Liège-Bastogne-Liège, Roche-aux-Faucons, 12 groups | Il Lombardia, just after the winner crossed the line |
| ![Il Lombardia, results](../docs/screenshots/il-lombardia-results.png) | ![Local file](../docs/screenshots/local-file-liege-bastogne-liege.png) |
| Il Lombardia, final classification (an all-rounder wins a 6-rider sprint) | `replays/liege-bastogne-liege-42.json.gz` opened as a local file |

Race list: [`race-list.png`](../docs/screenshots/race-list.png).

## Measures

Seed-42 replays of the final calibration (batch 5 review), `npm run measure` (`scripts/measure.mjs`): headless Chrome
(Playwright), 1440×900, AMD Renoir iGPU through ANGLE/GL, kernel, Vite dev server and `vite preview` on the same
machine. Loading steps are the best of 3, timed in the page with the app's own modules (dev server); opening and
playback on the production build.

| | Milano-Sanremo | Liège-Bastogne-Liège | Il Lombardia |
|---|---|---|---|
| Replay download (gzip on the wire → JSON) | 1.1 MB → 10.1 MB | 1.2 MB → 9.6 MB | 1.2 MB → 9.7 MB |
| Fetch / gunzip + JSON.parse + decode / `ReplayModel` | 52 / 80 / 12 ms | 57 / 57 / 8 ms | 49 / 55 / 10 ms |
| Click "Watch replay" → replay view rendered (production build) | 416 ms | 350 ms | 307 ms |
| `snapshotAt` (mean over the race) | 23 µs | 19 µs | 21 µs |
| ×60 playback, production build (10 s of rAF, finale) | 60 fps, p95 16.8 ms | 60 fps, p95 16.7 ms | 60 fps, p95 16.7 ms |
| JS heap while playing | 68 MB | 80 MB | 58 MB |

With software WebGL (SwiftShader, the headless default) MapLibre alone caps playback at 13–17 fps: measure with a
real GPU (`--ignore-gpu-blocklist --use-angle=gl-egl`, as the script does). The batch-4 replays (before the final
calibration) were 1.2–1.4 MB gzipped, 11–12 MB of JSON.

## Layout

```
src/
  api/        types.ts (DTO mirror), decode.ts (compact arrays), client.ts (REST + replay loading)
  replay/     source.ts (RaceSource seam + snapshot types), model.ts (ReplayModel), clock.ts (playback),
              live.ts (source → clock wiring, data version), peloton.ts (the one peloton rule),
              meta.ts (RaceMeta, team colors, rider lookups), course.ts (position on course),
              events.ts (event labels/colors/descriptions), format.ts (time, gap, km), fixtures.ts (test replay)
  app/        RaceList.tsx, ReplayView.tsx (fixed layout, one slot per view)
  views/      one component per slot (see below): MapView.tsx, ProfileView.tsx, GroupsPanel.tsx (+ .css),
              EventsList.tsx (+ .css), ResultsTable.tsx (+ .css), PlaybackBar.tsx (+ .css)
    map/      MapView.css, RaceMap.ts (MapLibre wrapper), labels.ts (group labels, declutter, follow),
              spread.ts (riders spread inside a group), geo.ts (bounds, course slices)
    profile/  ProfileView.css, scale.ts, draw.ts, markers.ts, layout.ts, grade.ts
    panels/   panels.css, groups.ts, events.ts, results.ts (GroupsPanel / EventsList / ResultsTable helpers),
              RiderName.tsx
  App.tsx     race list → replay view
  styles.css  theme variables (--bg, --panel, --panel-2, --line, --text, --muted, --accent, --accent-text) and layout
```

## Data source seam

Views never see frames: they get a `RaceMeta` (static) and a `RaceSnapshot` (state at playback time `t`) from a
`RaceSource`. `ReplayModel` is the replay implementation; a live SSE source will implement the same interface.

```ts
interface RaceSource {
  kind: 'replay' | 'live';
  meta: RaceMeta;
  startT: number; endT: number;            // race-time range, s
  events: readonly RaceEvent[];            // all known, sorted by tick
  results: Classification | null;
  snapshotAt(t: number): RaceSnapshot;     // clamped to [startT, endT]
  eventsUntil(t: number): readonly RaceEvent[]; // tick <= t; same array instance while unchanged
  subscribe(listener: () => void): () => void;  // live data arrival (replay: never fires)
}

interface RaceSnapshot {
  t: number;
  riders: RiderState[];        // meta.riders order
  groups: GroupState[];        // on the road, front to back
  finishedRiderIds: number[];  // by finishing position
  headDistM: number;           // leading rider (finished or not)
  kmToGo: number;
  raceFinished: boolean;
}

interface RiderState {
  riderId: number; teamId: string; index: number;  // index in meta.riders
  distM: number; speed: number;                    // linear between frames (m, m/s)
  groupId: number; rankInGroup: number;            // steps; groupId 0 = finished, rank = position
  finished: boolean; position: number | null;
  lat: number; lon: number; eleM: number;          // on the course polyline at distM
}

interface GroupState {
  id: number; riderIds: number[] /* front to back */; size: number;
  headDistM: number; tailDistM: number; headRiderId: number;
  lat: number; lon: number; eleM: number;          // head of the group
  gapToHeadS: number;   // s since the head of the race (furthest rider ever) passed headDistM: GroupSummary.gapS
  gapToAheadS: number;  // s since the head of the group ahead passed headDistM
  kmToGo: number;
}
```

Gaps are real time gaps from the frames, the engine's definition (DESIGN §3 "Time gaps"): `gapToHeadS` is how long
ago the head of the race, the furthest any rider has ever been (a caught break still counts; the winner once someone
has finished), passed the group's head distance, binary-searched in the per-frame head distances and interpolated
between frames. `gapToAheadS` uses the passage of the front rider of the group ahead. `replay/engineGaps.test.ts`
checks the group gaps of a real race against the engine's `GroupSummary.gapS` (fixture written by the runner's
`GapFixtureTest`: `UCAI_WRITE_FIXTURES=1 ./gradlew :runner:test --tests '*GapFixtureTest*'` after an engine change).

`ReplayModel` also exposes `frameIndexAt(t)`, `distAt(riderIndex, t)`, `passTime(riderIndex, distM)` and
`headPassTime(distM, t)`.

`RaceMeta`: `race`, `course` (decoded points `{lat, lon, eleM, distM}`, `climbs`), `teams`, `riders`, `climbs`,
`teamById`, `riderById`, `riderIndex`. Helpers in `replay/meta.ts`: `teamColor(meta, teamId)`,
`riderColor(meta, riderId)`, `riderTeam`, `rider`, `team`, `riderName`, `riderShortName` (`G. VELOVIC`),
`textColorOn(hex)` (black/white for text on a team color), `UNKNOWN_COLOR`.
`replay/course.ts`: `positionAt(points, distM)`, `pointIndexAt(points, distM)`.
`replay/events.ts`: `EVENT_LABELS`, `EVENT_COLORS`, `describeEvent(meta, e)`, `TIMELINE_EVENT_TYPES`.
`replay/format.ts`: `formatRaceTime`, `formatGap`, `formatKm`, `formatSpeed`, `formatGrade`.

### Playback clock

`PlaybackClock(startT, endT, speed = 10)`: `getState()` → `{t, playing, speed, startT, endT}`, `subscribe`,
`play` (restarts from `startT` when at the end), `pause`, `toggle`, `setSpeed(1|10|30|60)`, `seek(t)`,
`setRange(startT, endT)`, `advance(wallSeconds)` (driven by `requestAnimationFrame` while playing).
Hooks: `usePlaybackClock(startT, endT)` (one clock per component instance), `usePlayback(clock)`.
`useSourceVersion(source, clock)` (`replay/live.ts`) subscribes to the source: on each notification the clock
range follows `source.startT/endT` and the returned version changes, so a live source only needs to notify.
`PLAYBACK_SPEEDS = [1, 10, 30, 60]`.

## Views (props contract)

Every view fills its slot box (`position: relative`, `overflow: hidden`, sized by the grid) and must handle
being re-rendered every animation frame while playing (snapshot changes; `meta` never changes for a race).

| View | Props | Slot |
|---|---|---|
| `MapView` | `{ meta: RaceMeta; snapshot: RaceSnapshot }` | big left area |
| `ProfileView` | `{ meta: RaceMeta; snapshot: RaceSnapshot }` | full-width strip, 190 px high |
| `GroupsPanel` | `{ meta: RaceMeta; snapshot: RaceSnapshot }` | right column, top |
| `EventsList` | `{ meta: RaceMeta; events: readonly RaceEvent[] /* tick <= t, chronological */; t: number }` | right column, bottom |
| `ResultsTable` | `{ meta: RaceMeta; results: Classification \| null; t: number; onClose: () => void }` | overlay over the map (header "Results" button, opens itself when the race ends) |
| `PlaybackBar` | `{ meta: RaceMeta; clock: PlaybackClock; state: PlaybackState; events: readonly RaceEvent[] /* all */ }` | bottom bar |

`ResultsTable` should only list riders with `timeS <= t`, so scrubbing back hides later finishers.
