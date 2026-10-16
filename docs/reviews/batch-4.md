# Batch 4 review

Date: 2026-10-17
Scope: commits `a6cdf26` (frontend foundation) → `3c0ee0c` (screenshots), reviewed on `develop`.
Fixes: `eecb58d fix: review`.
Reviewer evidence: typecheck, vitest, `npm run build`, `./gradlew build`, the app run against `:runner:run --serve` in headless Chrome (Playwright), positions/groups/gaps compared with values computed from the replay JSON.

## What batch 4 delivered

| Commit | Content |
|--------|---------|
| `a6cdf26 feat: frontend foundation` | `frontend/`: Vite 8, React 19, TypeScript 7 strict, maplibre-gl 6, Vitest. API types and client (kernel or local `.json.gz`), `RaceSource` seam, `ReplayModel` (interpolation, groups, real time gaps), `PlaybackClock`, app shell, `PlaybackBar`. |
| `d7b0d25 feat: frontend map` | Course, climbs, team-colored riders laid out from `rankInGroup`, TV-style group labels, follow a group. One GeoJSON source updated per frame. |
| `a2e2db4 feat: frontend elevation profile` | Canvas profile: gradient colors, named climbs, group bubbles, hover, high-DPI. |
| `8bad7ff feat: frontend side panels and results table` | Groups panel, events as sentences, results overlay. |
| `548045d merge` | Octopus merge, no conflicts; MapLibre worker URL moved to `main.tsx`. |
| `df2928c` → `3c0ee0c` | Integration: camera follows the leaders by default, profile zooms on the last 50 km, no overlapping bubbles, overlay and timeline fixes, events list rendered only on new events, screenshots in `docs/screenshots/`. |

### Measures (headless Chrome, real GPU through ANGLE)

- Replay: 1.2–1.4 MB gzip, ≈ 11–12 MB of JSON. Opening a race takes 330–420 ms (production build); a local `.json.gz` ≈ 420 ms.
- `snapshotAt`: 14–86 µs. Playback at ×60: 60 fps, p95 16.7 ms, no long tasks (production build). JS heap 30–90 MB.
- With software WebGL (SwiftShader, the headless default) MapLibre caps playback at 13–17 fps: measurements need real-GPU flags.

### Things the backend should know

- `group_merge` keeps the bigger group's id, so the listed riders are not always the group behind (a caught break lists the break). `GroupTracker` in `views/panels/events.ts` rebuilds the order of groups to tell a catch from a bridge. An explicit "ahead" flag in the kernel event would be simpler.
- `race_finished` is emitted when the last rider finishes, not the winner.
- The MapLibre worker is an `.mjs` file: a production server must serve it as JavaScript.

## Findings

| # | Severity | File | Problem |
|---|----------|------|---------|
| 1 | Major | `frontend/src/app/ReplayView.tsx` | Space only toggled playback when no button had focus; after clicking a speed or map button, Space clicked that button again. |
| 2 | Minor | `frontend/src/views/map/labels.ts` | Map and groups panel used two different peloton rules (≥ 10 riders and 25 % vs ≥ 6 and 30 %): the same group could be "PELOTON" on the map and "Group 4" in the panel. |
| 3 | Minor | `frontend/src/app/ReplayView.tsx` | The live seam was declared but not wired: nothing subscribed to the source, the clock range was set once, so a live source would have needed container changes (against DESIGN §7). |
| 4 | Minor | `frontend/src/api/client.ts` | A corrupt `.json.gz` showed "Failed to fetch" instead of a file error. |
| 5 | Minor | `frontend/src/styles.css` | Dead placeholder CSS and two unused barrel files. |
| 6 | Minor | `frontend/README.md` | README still described the parallel-build process (ownership rules, "(implemented)") and an outdated layout. |

## Fixes applied (`eecb58d`)

1. Space toggles playback even with a button focused (keydown/keyup `preventDefault`, repeats ignored), still skipped in form fields.
2. One rule in `src/replay/peloton.ts` (largest group, ≥ 10 riders and ≥ 25 % of riders on the road), used by map and panel.
3. `src/replay/live.ts`: `connectSource` syncs the clock range on each notification and `useSourceVersion` re-renders on new data; tested with a fake live source.
4. `ReplayFormatError` for corrupt gzip or invalid JSON.
5. Dead code removed.
6. README rewritten for the current code.

Screenshots re-taken. Typecheck, 70 vitest tests, `npm run build` and `./gradlew build` pass.

## Open issues

- Il Lombardia finale: the course loops back on itself, so groups far apart on the road end up close on the map.
- Profile: group bubbles can cover a climb name (Civiglio, Côte de Wanne); climb name lines can cross on dense climbs (Capi of Milano-Sanremo).
- Map labels can partly cover groups a few seconds behind.
- Main JS chunk ≈ 1.3 MB (Vite chunk-size warning).
- Style warnings from OpenFreeMap tiles in the console.
- The Playwright scripts used for screenshots and measures are not committed (scratchpad only).
