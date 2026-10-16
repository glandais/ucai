# UCAI — Spike 1 design

Status: draft v2 (after review), to be validated before implementation.
Scope: see `SPIKE.md` — 3 monuments, API v1, kernel v1, team v1, batch race runner, replay frontend.

---

## 1. Architecture

```
                 ┌──────────────────────── kernel (Kotlin, Ktor) ───────────────────────────┐
                 │                                                                           │
 races/raw/*.gpx │  Course importer ──► Course store ──► Race engine (tick loop, physics,     │
 races/raw/*.kml │                                       groups, events, recorder)           │
                 │                                             │                             │
                 │                       REST /api/v1  +  SSE streams                        │
                 └───────────────▲──────────────────────────────▲────────────────────────────┘
                                 │ HTTP (public, read-only)     │ HTTP (team token)
                         ┌───────┴────────┐            ┌────────┴─────────┐
                         │ frontend (React│            │ controllers      │
                         │ Vite, MapLibre)│            │  - team v1 bots  │
                         │ replay         │            │  - humans (later)│
                         └────────────────┘            └──────────────────┘
```

Rules:
- The kernel is the **only** authority on race state. Controllers and the frontend only see the API.
- The kernel knows nothing about bots. Team bots v1 live in the same repo (and, in batch mode, the same JVM) but **only talk HTTP** to the kernel — so replacing a bot with an external program or a human UI changes nothing on the kernel side.
- Information asymmetry, like real racing: the public view shows what TV shows (positions, speeds, groups, gaps, rider archetypes and coarse ratings). A team sees its own riders' physiology (exact numbers, W′ balance, energy, power) and nothing private about other teams.

### Tech stack

Backend in **Kotlin, "pure" Kotlin Multiplatform style**: the code that matters (API model, engine, bot) is written in `commonMain` with no JVM-only dependency, so it can later run on other targets (native, JS/Wasm, serverless). **Only the JVM target is built for now.**

| Concern | Choice | Notes |
|---------|--------|-------|
| Build | Gradle 9 (Kotlin DSL) + version catalog, Kotlin 2.3 | KMP plugin on common modules, `jvm()` target only |
| HTTP server | **Ktor 3** server (CIO engine), `ktor-server-sse`, `ktor-server-auth` (bearer), `ktor-server-content-negotiation` | CIO is pure Kotlin (Netty would tie us to the JVM) |
| HTTP client | Ktor client (CIO) + SSE client plugin | used by the bot |
| JSON | kotlinx.serialization | DTOs are `@Serializable` data classes |
| Concurrency | kotlinx.coroutines | tick loop and lockstep waits are `suspend` code, no threads |
| Time | `kotlin.time` / injected clock | the engine never reads the wall clock |
| Randomness | `kotlin.random.Random(seed)` | deterministic on every platform |
| Math | `kotlin.math` | |
| Logging | a tiny logger interface in common, slf4j/logback in the JVM app | |
| Tests | `kotlin.test` in common, Ktor `testApplication` for the API | |

Rules for common code: no `java.*` imports, no `System.currentTimeMillis`, no JVM reflection; file and XML access stay at the edges (importer, server `main`, runner).

### Repository layout

```
ucai/
  settings.gradle.kts     root build, gradle/libs.versions.toml
  api/                    KMP common: shared DTOs (@Serializable), API constants
  engine/                 KMP common: course model, physics, groups, fatigue, events, recorder — no I/O
  kernel/                 KMP: Ktor server — races registry, REST, SSE, auth, lockstep (jvmMain: main + CIO bootstrap)
  team/                   KMP common: team v1 bot, Ktor client, depends on api/ only
  importer/               JVM tool: GPX/KML → races/courses/*.json (XML parsing, runs once)
  runner/                 JVM app: batch race runner, starts kernel + bots
  frontend/               React + Vite + TypeScript + MapLibre
  races/raw/              source tracks (given)
  races/courses/          generated course files (committed, reproducible from raw)
  replays/                batch output (git-ignored)
```

Dependencies: `engine → api`, `kernel → engine, api`, `team → api`, `runner → kernel, team`, `importer → engine`. Never `kernel → team`.

The engine is a separate module from the server so it can run without any HTTP (calibration harness, future serverless or in-browser simulation).

---

## 2. Domain model

| Concept   | Meaning |
|-----------|---------|
| `Course`  | A route definition: Milano-Sanremo, Liège-Bastogne-Liège, Il Lombardia. Static. |
| `Race`    | One run of a course: start list, clock, seed, state. Many races per course. |
| `Team`    | 7 riders + a controller (bot or human) holding a team token. 10 teams per race (70 riders). |
| `Rider`   | Physiological profile + live state. |
| `Group`   | Riders riding together (same speed, sharing draft). Emerges from the simulation. |
| `Order`   | An instruction from a controller to one of its riders. |
| `Event`   | Something notable: attack, split, catch, dropped, summit, finish… |

### Course

Import pipeline (run once, output committed in `races/courses/<id>.json`):
1. Parse the source track:
   - GPX (Milano-Sanremo, Il Lombardia): all `trkpt`.
   - KML (Liège-Bastogne-Liège): only the `LineString`s with styles `#styleRace1` then `#styleRace2`, concatenated. The `#styleLineRouge` line is a direction arrow and is ignored. Named `#styleClimb` placemarks give climb names, `#styleDepartReel` (`km 0`) gives the real start, `#styleFinish` the finish.
2. **Cut at km 0** (the neutral start is not simulated):
   - LBL: cut at the track point nearest to the `km 0` placemark.
   - GPX: cut the start so the remaining distance matches the official race distance (configured per course).
3. Cumulative distance (haversine), **resample every 10 m**.
4. Smooth elevation (≈100 m window) — raw GPS/DEM elevation gives unrealistic ±30 % spikes.
5. Gradient and heading per segment; gradient clamped to ±25 %.
6. Climbs: no auto-detection in v1.
   - LBL: KML climb placemarks, snapped to the nearest track point; climb start/end from the gradient around that point.
   - GPX: a short hand-written list per course (Cipressa, Poggio, Sormano, Passo di Ganda…) with start/end km.
7. Course metadata: id, name, distance, total elevation gain, start/finish names.

```json
{ "id": "milano-sanremo", "name": "Milano-Sanremo", "distanceM": 298700,
  "points": [[lat, lon, eleM, distM], ...],
  "climbs": [{ "name": "Poggio", "startM": 292100, "endM": 295800, "avgGrade": 0.037, "maxGrade": 0.08 }] }
```

### Rider profile (fixed for a race)

| Field | Typical | Use |
|-------|---------|-----|
| `massKg` (rider + bike) | 62–88 | gravity, rolling resistance, inertia |
| `cp` W (critical power) | 330–440 | sustainable power |
| `wPrime` J | 15–35 k | anaerobic reserve (attacks, closing gaps, short climbs) |
| `pMax` W | 900–1700 | sprint ceiling |
| `cda` m² | 0.30–0.38 | aero drag alone at the front |
| `durability` 0..1 | | how much CP and W′ drop as work accumulates (kJ) — this decides 260 km races |
| `descending` 0..1 | | max safe speed on descents |

Archetypes generated from a seed: climber, puncheur, sprinter, rouleur, all-rounder, domestique. The rider pool is **fictional** (generated names, fictional teams).

Public profile: name, team, archetype and **ratings from 1 to 5** (climbing, punch, sprint, endurance, descending). Exact numbers are only given to the rider's own team.

### Rider live state

`distM`, `speed`, `power`, `wPrimeBal`, `cpEff`, `wPrimeEff`, `energyKj` (work done), `groupId`, `rankInGroup`, `mode`, `status` (`racing` | `dropped` | `finished` | `abandoned`).

`abandoned` is not used in v1 (no crashes or mechanicals) but is part of the model, and the engine uses a seeded RNG so incidents can be added in v2.

---

## 3. Simulation (kernel v1)

### Clock

- Fixed tick **dt = 1 s** of simulated time. The engine is deterministic given (course, start list, seed, orders per decision point).
- Clock modes in v1:
  - `lockstep` (batch): runs as fast as possible and pauses at **decision points** until **every** controller has acknowledged. No timeout: a controller whose stream closes is auto-acked (with no orders) and the race logs it; in batch mode the runner then fails the run loudly.
  - `realtime`: 1 tick per wall second, orders applied at the next tick. Kept trivial, used later for humans and live races.
  - `accelerated`, pause/resume: later.
- Decision points: every 30 s of race time, plus after stable race events (attack, split, catch, summit, 10 km / 1 km to go). Minimum spacing between two decision points: **5 s** of race time — events in between are batched into the next decision point.

### Physics (per rider, per tick)

Resistive force at speed `v` on gradient `θ`:

```
F = m·g·(sin θ + Crr·cos θ) + ½·ρ·CdA·k_draft·(v + v_wind_axial)²
```

Integration (explicit Euler): `a = (P·η / max(v, 1 m/s) − F) / m`, capped at **+1.5 m/s²**; `v += a·dt`; `dist += v·dt`.
- η = 0.976 drivetrain efficiency, g = 9.81, Crr = 0.004, ρ from elevation (≈1.225 at sea level, −10 % per 1000 m).
- Wind: one optional constant vector per race (direction + speed, 0 by default). `v_wind_axial` is its component along each segment's heading.
- The aero time constant (≈10 s at race speed) keeps dt = 1 s stable.

**Drafting** `k_draft` by rank in the group: 1st 1.00, 2nd 0.64, 3rd 0.55, then decreasing to ≈0.35 deep in a large peloton. No special case on climbs: aero drag is already small at climbing speeds, so drafting fades naturally.

### Fatigue

- **W′ balance** (Skiba differential model): above `cpEff`, W′bal drains by (P − cpEff)·dt; below, it recovers exponentially.
- **Durability**: accumulated work lowers both CP and W′, and slows W′ recovery:

  ```
  loss       = (1 − durability) · (energyKj / (massKg · K))²      K calibrated, loss capped at 0.35
  cpEff      = cp · (1 − loss)
  wPrimeEff  = wPrime · (1 − loss)
  τ_recovery = τ_base · (1 + loss)
  ```

  Calibration target: a domestique who has done 2000 kJ by the finale has lost 10–15 % of CP; a protected leader 5 %. This is the core mechanic of the monuments.
- When W′bal ≈ 0, a rider can't produce more than `cpEff`.
- No nutrition/bonk model in v1.

### Groups

Riders are 1D (distance along the course). `rankInGroup` gives a stable order inside a group; lateral position is cosmetic and derived by the frontend from it.

Each tick:
1. **Group pace**:
   - If some riders in the group are in `pull` mode, they ride at the front at their intensity and set the speed.
   - Otherwise, **default rotation**: all riders not in `recover` take turns at the front at a default intensity (0.65 × `cpEff`, configurable per race), changing every 30–60 s (seeded). A rider who can't hold that pace goes to the back of the rotation.
   - **Descents**: group speed = min(power-derived speed, speed cap from the leading rider's `descending` and the gradient). Followers freewheel. No splits on descents in v1.
2. **Followers** compute the power needed to hold the group speed with their draft factor, bounded by their mode (`follow` with `maxIntensity`, `recover`). If they can't produce it, they slow down and a gap opens.
3. **Split** (with hysteresis): a rider or sub-group more than **3 s** behind the rider ahead **for 3 consecutive ticks** becomes a new group.
4. **Merge**: a group that comes within **1 s** of the group ahead merges into it.
5. **Attack**: a rider in `attack` mode leaves with a burst; they become a new group once the split rule fires. Other riders only respond if their controller tells them to (`follow` with `targetRiderId`); otherwise the group keeps its pace.

Only stable groups produce events (`group_split`, `group_merge`), which avoids event storms and useless decision points.

Cost: O(riders) per tick. With 70 riders and ~25 000 ticks per race, a race takes well under a second of CPU time without controllers. HTTP round-trips at decision points dominate batch time.

### Rider modes (what orders set)

| Mode | Parameters | Behavior |
|------|------------|----------|
| `follow` (default) | `targetRiderId?`, `maxIntensity?` (fraction of `cpEff`, default 1.0) | Stays in the group at minimal power, takes part in the default rotation. With `targetRiderId`, tries to stay with that rider (closes gaps, follows attacks) but never above `maxIntensity` — beyond that the gap opens and the controller gets a decision point. |
| `pull` | `intensity` (fraction of `cpEff`, e.g. 0.9) | Rides at the front at that intensity — to control a breakaway, set up a climb, or ride alone at a set pace. |
| `attack` | `intensity` (fraction of the W′-allowed max), `durationS` | Burst for `durationS`, then switches to `pull` at 0.9. |
| `sprint` | — | All-out effort, see the sprint model below. Meant for the last 200–300 m. |
| `recover` | — | Sits at the back, never takes part in the rotation, never chases: accepts being dropped to save energy. |

Kernel safeguards: at W′bal ≈ 0 a rider can't hold `attack`/`sprint`; a rider in `follow` without `targetRiderId` doesn't chase alone; a rider without orders keeps `follow`.

### Sprint model

So the best `pMax` doesn't win every time:
- Sprint power = `pMax · f(W′bal / wPrimeEff) · (1 − loss)`: a sprinter who emptied their W′ on the climbs or is tired sprints weaker.
- Lead-out: a rider keeps the draft of a teammate in `pull` until ~200 m to go, then loses it. A team that pulled in the last km gives its sprinter an advantage.
- Positioning: a seeded factor of ±10 % effective power per sprinter, weighted by whether a teammate pulled in the last km.

### Events

`race_started`, `attack`, `group_split`, `group_merge` (catch), `rider_dropped`, `climb_summit` (passage order at the top), `km_to_go` (50, 20, 10, 5, 1), `rider_finished`, `race_finished`.

### Results

Finish order by crossing time, interpolated between ticks (`t = t0 + (finishM − dist0) / v`). Time gaps by group (UCI rule: riders in the same group get the group time). Output: classification with times and gaps.

---

## 4. API v1

Base path `/api/v1`, JSON, distances in meters, speeds in m/s, times in seconds of race time unless stated.

### Security

- **Admin token** (env var `UCAI_ADMIN_TOKEN`): creates and controls races.
- **Team token**: returned once at team registration, `Authorization: Bearer <token>`. Scoped to one team in one race. Used for the team view, the team stream and orders.
- **Public**: read-only race state, public stream, replays. No auth. Never exposes exact rider numbers (`cp`, `wPrime`, `durability`, `power`, `wPrimeBal`).
- Validation of every order: the rider belongs to the team, values in range, `decisionId` is the current one.
- v1 leaves out rate limiting and CORS: the frontend uses the Vite dev proxy. TLS is left to a reverse proxy.

### Courses (public)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/courses` | List courses (id, name, distance, elevation gain). |
| GET | `/courses/{courseId}` | Full course: decimated points, profile, climbs. |

### Races

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/races` | admin | Create a race: `{courseId, seed, clock: {mode}, wind?: {fromDeg, speed}}` → `{raceId}` in state `registration`. |
| GET | `/races` | public | List races (state, course, start time). |
| GET | `/races/{raceId}` | public | Metadata, public start list (archetypes and ratings), current state. |
| POST | `/races/{raceId}/teams` | admin | Register a team: `{name, color, riders?: [...]}` (riders generated if omitted) → `{teamId, token, riders}` with the riders' **full** profiles. |
| POST | `/races/{raceId}/start` | admin | Start. |
| GET | `/races/{raceId}/state` | public | Current public snapshot. |
| GET | `/races/{raceId}/stream` | public | **SSE** public stream (see below). |
| GET | `/races/{raceId}/results` | public | Classification (partial while racing). |
| GET | `/races/{raceId}/replay` | public | Full recording (finished races). |

### Team (controller)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/races/{raceId}/teams/{teamId}` | Team private view: own riders with full state. |
| GET | `/races/{raceId}/teams/{teamId}/stream` | **SSE** private stream: public events + own riders' state + `decision` events. |
| POST | `/races/{raceId}/teams/{teamId}/orders` | `{decisionId, orders: [{riderId, mode, intensity?, durationS?, targetRiderId?, maxIntensity?}], ack: true}` |

Order semantics:
- In `lockstep`, orders are only accepted for the **current** `decisionId`; stale ones are rejected. All orders of a decision point are applied together when every team has acked, in a canonical order (`teamId`, then `riderId`; for the same rider, the last order sent wins). Arrival order never matters.
- `ack: true` closes the team's decision. Sending orders without `ack` is allowed; sending the same body twice is idempotent.
- In `realtime`, `decisionId` is optional and orders apply at the next tick.
- The response lists accepted and rejected orders with a reason.

### SSE streams

Standard `text/event-stream`, event `id` = tick. No resume buffer in v1 (`Last-Event-ID` ignored): a reconnecting client starts with a fresh `snapshot`.

| Event | Payload | Rate |
|-------|---------|------|
| `snapshot` | `{tick, raceTimeS, riders: [[riderId, distM, speed, groupId, rankInGroup]], groups: [{id, headDistM, size, gapS}]}` | every tick, but at most ~10 per wall second |
| `event` | `{tick, type, ...}` | when it happens |
| `state` | `{state: registration|running|finished}` | on change |
| `decision` (team stream only) | `{decisionId, tick, reasons, snapshot, team, events}` — the public snapshot, own riders' full state, and the events since the previous decision | at each decision point |
| `team` (team stream only) | own riders: `power, wPrimeBal, cpEff, wPrimeEff, energyKj, mode` | with snapshots |

A bot can make all its decisions from the `decision` event alone, with no extra GET.

### Replay format

`GET /races/{raceId}/replay` (gzip):

```json
{ "version": 1, "race": {...}, "course": {...}, "teams": [...], "riders": [...],
  "frames": [ { "t": 0, "r": [[distM, speed, groupId, rankInGroup], ...] }, ... ],   // rider order = "riders" order
  "events": [ ... ], "results": [ ... ] }
```

Adaptive frame step: one frame every **5 s** of race time, every **1 s** in the last 3 km and for 60 s after an attack. The frontend interpolates `distM` linearly between frames; `groupId` and `rankInGroup` change as steps.

---

## 5. Team v1 (bot)

Pure Kotlin common code using the API only (Ktor client + SSE). One instance per team, each in its own coroutine. It decides only from `decision` events. Simple, readable strategy driven by rider archetypes and the course:

- **Roles** at start: 1 leader (best fit for the course: climber for Lombardia, puncheur for LBL, sprinter/puncheur for Sanremo), 1–2 lieutenants, domestiques.
- **Early race**: some teams (seeded) send a rider in the breakaway; the strongest teams put one domestique in `pull` at moderate intensity to control the gap (the target gap shrinks as the finish gets closer).
- **Climbs in the finale** (named climbs in the last ~60 km): domestiques `pull` hard at the foot, the leader `follow`s, attacks happen on the last 1–2 climbs depending on W′bal and the leader's profile (puncheurs attack late, climbers earlier on long climbs).
- **Response**: when a rival leader attacks, the leader or a lieutenant follows (`follow` + `targetRiderId` + `maxIntensity`) if their W′bal allows it.
- **Finale**: a teammate `pull`s in the last km for the sprinter, sprinters `sprint` in the last 250 m; others follow the best wheel.
- **Spent riders**: domestiques with low `cpEff` switch to `recover`.

Strategy parameters (aggressiveness, risk) come from the team config, so a batch can compare strategies.

---

## 6. Batch race runner

The `runner` module:

```
./gradlew :runner:run --args="\
     --courses milano-sanremo,liege-bastogne-liege,il-lombardia \
     --seed 42 --out replays/"
```

1. Starts the kernel (embedded Ktor server, CIO) on a local port.
2. For each course: creates a race (`lockstep` clock), registers 10 teams, starts **10 team bots in the same process** (coroutines) that connect over HTTP with their tokens.
3. Runs to the finish, writes `replays/<course>-<seed>.json.gz` and `results.json`, prints the top 10, the key events and a **result hash**.
4. Exits (or keeps serving the replays with `--serve`, for the frontend).

Targets: one race in < 1 min, and the same seed gives the same result hash.

---

## 7. Frontend v1 (replay)

React + Vite + TypeScript, MapLibre GL with OpenFreeMap vector tiles (free, no key). The Vite dev server proxies `/api` to the kernel.

- **Race list**: finished races from the kernel API, or a replay file loaded locally.
- **Replay view**:
  - Map: course line, climbs marked, one dot per rider colored by team, group labels.
  - **Elevation profile** (canvas) below: groups as markers on the profile, climbs shaded — the "TV graphic" of the race.
  - **Groups panel** (TV-style): head of race, gaps in time, riders in each group with their team color, km to go.
  - Event list (not clickable in v1).
  - Playback: play/pause, a few speeds (×1 → ×60), scrubbing on a timeline.
  - Results table at the end.
- Code split between a **data source** (replay file now, SSE later — same snapshot shape) and the views, so live mode is a new source and no view rewrite.

Later: camera following a group, higher playback speeds, clickable events.

---

## 8. Testing

- Physics unit tests: steady speeds against known values (e.g. 400 W, 70 kg, flat, CdA 0.32 → ≈ 43 km/h solo; 6 % climb ≈ 21 km/h), drafting gain, W′bal drain/recovery, durability loss.
- Group logic tests: split/merge with synthetic riders, including hysteresis (no oscillation around the threshold), a group with no `pull` rider, descents.
- Course import tests: distance within 1 % of the official one after the km 0 cut, elevation gain plausible, LBL built from the two race lines only.
- API tests (Ktor `testApplication`): auth scopes, no private numbers on public endpoints, order validation, stale `decisionId` rejected.
- **Calibration harness** (no HTTP): scripted strategies run directly against the engine, to tune durability, default rotation and sprint before the bots exist.
- Batch determinism: 2 runs with the same seed → identical result hash.
- Sanity on results: winning average speed in a realistic range (40–44 km/h Sanremo, 39–42 LBL/Lombardia), finale gaps of seconds to a minute or two, not 20 min; different seeds give different winners.

---

## 9. Implementation order

0. Gradle KMP skeleton (version catalog, `jvm()` target, common modules compiling with no JVM dependency).
1. `api` + `importer` + 3 course files (check distances and profiles).
2. Engine: physics, fatigue, groups, sprint, events, recorder — plus the **calibration harness** with scripted strategies (no HTTP). First calibration pass here.
3. REST + SSE + security + lockstep protocol.
4. Team v1 bot + runner → first full replays, determinism test.
5. Frontend replay.
6. Final calibration pass on the 3 races (speeds, gaps, realistic finales).

## 10. Decisions

- Backend: **Kotlin + Ktor**, pure KMP style (common code, JVM target only for now). Replaces the earlier Java/Quarkus choice.
- Field size: **10 teams × 7 riders (70)**.
- Neutral start: not simulated — the track is **cut at km 0** at import.
- Wind: **optional constant wind** per race (direction + speed, 0 by default), applied to aero drag using each segment's heading.
- Crashes, mechanicals, punctures: **v2**. The engine keeps the `abandoned` status and a seeded RNG so they can be added later.
- Out of the spike: `accelerated` clock and pause/resume, SSE resume buffer, rate limiting, CORS, climb auto-detection, frontend extras (camera follow, ×600, clickable events).
- Modes: `tempo` is dropped (covered by `pull`); `recover` is kept (letting a spent rider go is a real tactical choice).
