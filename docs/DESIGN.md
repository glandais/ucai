# UCAI — Spike 1 design

Status: draft, to be validated before implementation.
Scope: see `SPIKE.md` — 3 monuments, API v1, kernel v1, team v1, batch runner, replay frontend.

---

## 1. Architecture

```
                 ┌──────────────────────── kernel (Quarkus, Java 25) ───────────────────────┐
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
                         │ replay / live  │            │  - humans (later)│
                         └────────────────┘            └──────────────────┘
```

Rules:
- The kernel is the **only** authority on race state. Controllers and the frontend only see the API.
- Team bots v1 live in the same repo (and, in batch mode, the same JVM) but **only talk HTTP** to the kernel — so swapping a bot for an external program or a human UI changes nothing on the kernel side.
- Information asymmetry, like real racing: the public stream shows what TV shows (positions, speeds, groups, gaps). A team sees its own riders' physiology (W′ balance, energy, power) and nothing private about other teams.

### Repository layout

```
ucai/
  pom.xml                 parent (Java 25)
  api/                    shared DTOs (Java records) + OpenAPI-friendly docs
  kernel/                 Quarkus app: courses, engine, REST, SSE, recorder, batch mode
  team/                   team v1 bot: plain Java, java.net.http client, depends on api/ only
  frontend/               React + Vite + TypeScript + MapLibre
  races/raw/              source tracks (given)
  races/courses/          generated course files (committed, reproducible from raw)
  replays/                batch output (git-ignored)
```

---

## 2. Domain model

| Concept   | Meaning |
|-----------|---------|
| `Course`  | A route definition: Milano-Sanremo, Liège-Bastogne-Liège, Il Lombardia. Static. |
| `Race`    | One run of a course: start list, clock, seed, state. Many races per course. |
| `Team`    | 7 riders + a controller (bot or human) holding a team token. |
| `Rider`   | Physiological profile + live state. |
| `Group`   | Riders riding together (same speed, sharing draft). Emerges from the simulation. |
| `Order`   | An instruction from a controller to one of its riders. |
| `Event`   | Something notable: attack, split, catch, dropped, summit, finish… |

### Course

Import pipeline (run once, output committed in `races/courses/<id>.json`):
1. Parse GPX `trkpt` / KML `LineString` (LBL has 3 linestrings to concatenate, plus named `Point` placemarks).
2. Cumulative distance (haversine), **resample every 10 m**.
3. Smooth elevation (≈100 m window) — raw GPS/DEM elevation gives unrealistic ±30 % spikes.
4. Gradient per segment, clamped to ±25 %.
5. Climbs: auto-detection (≥ 3 % average over ≥ 500 m, merged when separated by short false flats) + names from KML placemarks snapped to the nearest track point. GPX climbs get names from a small hand-written list per course (Cipressa, Poggio, Redoute, Roche-aux-Faucons, Sormano, …).
6. Course metadata: id, name, distance, total elevation gain, start/finish names.

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
| `durability` 0..1 | | how much CP drops as work accumulates (kJ) — this decides 260 km races |
| `descending` 0..1 | | max safe speed on descents |

Archetypes generated from a seed: climber, puncheur, sprinter, rouleur, all-rounder, domestique. The rider pool is **fictional** (generated names, fictional teams).

### Rider live state

`distM`, `speed`, `power`, `wPrimeBal`, `energyKj` (work done), `groupId`, `mode`, `status` (`racing` | `dropped` | `finished` | `abandoned`).

---

## 3. Simulation (kernel v1)

### Clock

- Fixed tick **dt = 1 s** of simulated time. The engine is deterministic given (course, start list, seed, ordered inputs).
- Clock modes per race:
  - `realtime`: 1 tick per wall second (live races, humans).
  - `accelerated(x)`: x ticks per wall second.
  - `lockstep`: as fast as possible, but pauses at **decision points** until every controller has acknowledged (or a wall-clock timeout of 2 s passes). This is the batch mode: fast and reproducible.
- Decision points: every 30 s of race time, plus immediately after a race event (attack, split, catch, summit, 10 km / 1 km to go).

### Physics (per rider, per tick)

Resistive force at speed `v` on gradient `θ`:

```
F = m·g·(sin θ + Crr·cos θ) + ½·ρ·CdA·k_draft·(v + v_wind)²
```

Integration: `m·dv/dt = P·η / v − F` (η = 0.976 drivetrain efficiency; `v` floored at 1 m/s), then `dist += v·dt`.
Constants: g = 9.81, Crr = 0.004, ρ from course elevation (≈1.225 at sea level, −10 % per 1000 m). Wind: one constant vector per race (optional, 0 by default in v1).
Descents: speed capped by `descending` and gradient (no braking simulation in v1); riders freewheel when the target power isn't needed.

**Drafting** `k_draft` by position in the group: 1st 1.00, 2nd 0.64, 3rd 0.55, then decreasing to ≈0.35 deep in a large peloton. On steep climbs (> 6 %) the benefit fades linearly with speed (no draft below ~15 km/h).

### Fatigue

- **W′ balance** (Skiba differential model): above CP, W′ drains by (P − CP)·dt; below CP it recovers exponentially. At W′bal ≈ 0 a rider can't go above ~CP.
- **Durability**: the effective CP drops with accumulated kJ: `CP_eff = CP · (1 − durabilityLoss(energyKj))`. A domestique who pulls for 200 km is spent in the finale. That's the core mechanic of the monuments.
- No nutrition/bonk model in v1 (later: carbohydrates and feeding zones).

### Groups

Riders are 1D (distance along the course). Lateral position is cosmetic and derived by the frontend.

Each tick:
1. **Group speed** is set by the riders at the front (those in `pull` mode, otherwise by rotation among available riders): solve for `v` from their target power.
2. **Followers** compute the power needed to hold `v` with their draft factor. If that power is above what they can produce (`CP_eff` + what W′ allows), they slow down: a gap opens.
3. **Split**: a rider (or a sub-group) more than **2 s** behind the rider ahead forms a new group. Same rule as UCI timing (1 s gap = time gap; we use 2 s for stable groups).
4. **Merge**: a group that comes within 2 s of the group ahead merges into it.
5. **Attack**: a rider in `attack` mode leaves with a burst (target power for a duration). They form a new group as soon as the gap is > 2 s. Other riders only follow if their controller tells them to (`follow` with a target rider) — otherwise the group reacts with its current pace.

Cost: O(riders) per tick. With ~50–80 riders and ~25 000 ticks per race, a race takes well under a second of CPU time without controllers. HTTP round-trips dominate batch time.

### Rider modes (what orders set)

| Mode | Behavior |
|------|----------|
| `follow` (default) | Stays in its group at minimal power. Optional `targetRiderId`: tries to stay with that rider (closes gaps, follows attacks). |
| `pull` | Rides at the front at `intensity` (fraction of `CP_eff`, e.g. 0.9). |
| `tempo` | Rides alone or at the front at a target power, never above it (for a dropped rider limiting losses, or a breakaway). |
| `attack` | Burst at `intensity` × `pMax`-bounded power for `durationS`, then switches to `tempo`. |
| `sprint` | All-out effort (up to `pMax`, drains W′) — meant for the last 200–300 m. |
| `recover` | Sits at the back, lets the group go if needed — saves energy, accepts losing contact. |

Automatic safeguards in the kernel (no controller needed): at W′bal ≈ 0 the rider can't hold `attack`/`sprint`; a rider in `follow` with no `targetRiderId` doesn't chase alone; a rider without orders keeps `follow`.

### Events

`race_started`, `attack`, `group_split`, `group_merge` (catch), `rider_dropped`, `climb_summit` (passage order at the top), `km_to_go` (50, 20, 10, 5, 1), `rider_finished`, `race_finished`.

### Results

Finish order by crossing time (interpolated between ticks). Time gaps by group (UCI rule: riders in the same group get the group time). Output: classification with times and gaps.

---

## 4. API v1

Base path `/api/v1`, JSON, all distances in meters, speeds in m/s, times in seconds of race time unless stated.

### Security

- **Admin token** (from config / env var `UCAI_ADMIN_TOKEN`): creates and controls races.
- **Team token**: returned once at team registration, `Authorization: Bearer <token>`. Scoped to one team in one race. Used for the private stream and orders.
- **Public**: read-only race state, public stream, replays. No auth.
- Validation of all orders (rider belongs to the team, values in range), rate limit on orders (e.g. 20/s per team), CORS restricted to the frontend origin.
- TLS is left to the reverse proxy for v1.

### Courses (public)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/courses` | List courses (id, name, distance, elevation gain). |
| GET | `/courses/{courseId}` | Full course: decimated points, profile, climbs. |

### Races

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/races` | admin | Create a race: `{courseId, seed, clock: {mode, speed}, decisionTimeoutMs}` → `{raceId}` in state `registration`. |
| GET | `/races` | public | List races (state, course, start time). |
| GET | `/races/{raceId}` | public | Metadata, start list, current state. |
| POST | `/races/{raceId}/teams` | admin | Register a team: `{name, color, riders?: [...]}` (riders generated if omitted) → `{teamId, token, riders}`. |
| POST | `/races/{raceId}/start` | admin | Start. |
| POST | `/races/{raceId}/pause` · `/resume` | admin | Clock control. |
| GET | `/races/{raceId}/state` | public | Current public snapshot. |
| GET | `/races/{raceId}/stream` | public | **SSE** public stream (see below). |
| GET | `/races/{raceId}/results` | public | Classification (partial while racing). |
| GET | `/races/{raceId}/replay` | public | Full recording (finished races). |

### Team (controller)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/races/{raceId}/teams/{teamId}` | Team private view: own riders with full state. |
| GET | `/races/{raceId}/teams/{teamId}/stream` | **SSE** private stream: public events + own riders' state + `decision` events. |
| POST | `/races/{raceId}/teams/{teamId}/orders` | `{tick?, orders: [{riderId, mode, intensity?, durationS?, targetRiderId?}]}`. Orders apply at the next tick. Response lists accepted/rejected orders. |
| POST | `/races/{raceId}/teams/{teamId}/ack` | `{decisionId}` — "I'm done deciding" (lockstep). Orders can be sent with `ack: true` in the same call. |

### SSE streams

Standard `text/event-stream`, event `id` = tick, `Last-Event-ID` supported for resume (bounded buffer).

| Event | Payload | Rate |
|-------|---------|------|
| `snapshot` | `{tick, raceTimeS, riders: [[riderId, distM, speed, groupId]], groups: [{id, headDistM, size, gapS}]}` | every tick (realtime) or every 5 ticks (accelerated) |
| `event` | `{tick, type, ...}` | when it happens |
| `state` | `{state: registration|running|paused|finished}` | on change |
| `decision` (team stream only) | `{decisionId, tick, reason}` | at each decision point |
| `team` (team stream only) | own riders: `power, wPrimeBal, cpEff, energyKj, mode` | with snapshots |

Compact arrays in `snapshot` keep the stream small (80 riders × 1 Hz ≈ 3 kB/s).

### Replay format

`GET /races/{raceId}/replay` (gzip):

```json
{ "version": 1, "race": {...}, "course": {...}, "teams": [...], "riders": [...],
  "frameStepS": 5,
  "frames": [ { "t": 0, "r": [[distM, speed, groupId], ...] }, ... ],   // rider order = "riders" order
  "events": [ ... ], "results": [ ... ] }
```

One frame every 5 s of race time (~5 000 frames for a 7 h race, a few MB before gzip); the frontend interpolates between frames.

---

## 5. Team v1 (bot)

A plain Java client using the API only. One instance per team, its own thread. Simple, readable strategy driven by rider archetypes and the course:

- **Roles** at start: 1 leader (best fit for the course: climber for Lombardia, puncheur for LBL, sprinter/puncheur for Sanremo), 1–2 lieutenants, domestiques.
- **Early race**: 1–2 teams send a rider in the breakaway (random, seeded); the strongest teams put one domestique in `pull` at moderate intensity to control the gap (target gap shrinks as the finish gets closer).
- **Climbs in the finale** (named climbs in the last ~60 km): domestiques pull hard at the foot, the leader `follow`s, attacks happen on the last 1–2 climbs depending on W′bal and the leader's profile (punchers attack, climbers attack earlier on long climbs).
- **Response**: when a rival leader attacks, the leader or a lieutenant follows if their W′bal allows it.
- **Finale**: sprinters `sprint` in the last 250 m; others follow the best wheel.
- **Spent riders**: domestiques with low `cpEff` switch to `recover`.

Strategy parameters (aggressiveness, risk) come from the team config, so a batch can compare strategies.

---

## 6. Batch race runner

Kernel launched in `batch` mode (Quarkus profile / command):

```
java -jar kernel/target/quarkus-app/quarkus-run.jar --batch \
     --courses milano-sanremo,liege-bastogne-liege,il-lombardia \
     --teams 10 --seed 42 --out replays/
```

1. Starts the HTTP server on a local port.
2. For each course: creates a race (`lockstep` clock), registers N teams, starts **N team bots in the same JVM** that connect over HTTP with their tokens.
3. Runs to the finish, writes `replays/<course>-<seed>.json.gz` and `results.json`, prints the top 10 + key events.
4. Exits (or keeps serving the replays with `--serve`, handy for the frontend).

Targets: one race in < 1 min, and the same seed gives the same result (determinism test).

---

## 7. Frontend v1 (replay)

React + Vite + TypeScript, MapLibre GL with OpenFreeMap vector tiles (free, no key).

- **Race list**: finished races or replay files (from the kernel API, or a file loaded locally).
- **Replay view**:
  - Map: course line, climbs marked, one dot per rider colored by team, group labels; camera follows a selected group.
  - **Elevation profile** (canvas) below: groups shown as markers on the profile, climbs shaded — the "TV graphic" of the race.
  - **Groups panel** (TV-style): head of race, gaps in time, riders in each group with their team color, km to go.
  - Event ticker (attacks, splits, summits), clickable to jump to that moment.
  - Playback: play/pause, speed ×1 → ×600, scrubbing on a timeline with event marks.
  - Results table at the end.
- Code split between a **data source** (replay file now, SSE live later — same snapshot shape) and the views, so live mode is a new source and no view rewrite.

---

## 8. Testing

- Physics unit tests: steady speeds against known values (e.g. 400 W, 70 kg, flat, CdA 0.32 → ≈ 43 km/h solo; 6 % climb ≈ 21 km/h), drafting gain, W′bal drain/recovery.
- Group logic tests: split/merge with synthetic riders.
- Course import tests: distance within 1 % of the official one, elevation gain plausible.
- API tests (`@QuarkusTest`): auth scopes, order validation, SSE resume.
- Batch determinism: 2 runs with the same seed → identical results.
- Sanity on results: winning average speed in a realistic range (40–44 km/h Sanremo, 39–42 LBL/Lombardia), finale gaps of seconds to a minute or two, not 20 min.

---

## 9. Implementation order

1. `api` + course importer + 3 course files (check distances and profiles).
2. Engine: physics, fatigue, groups, events, recorder — tested without HTTP (unit tests, simple built-in behavior).
3. REST + SSE + security.
4. Team v1 bot + batch mode → first full replays.
5. Frontend replay.
6. Calibration pass on the 3 races (speeds, gaps, realistic finales).

## 10. Decisions

- Field size: **10 teams × 7 riders (70)**.
- Neutral start: **ignored** in v1 — the race starts at the first track point.
- Wind: **optional constant wind** per race (direction + speed, 0 by default), applied to aero drag using each segment's heading.
- Crashes, mechanicals, punctures: **v2**. The engine keeps the `abandoned` status and a seeded RNG so they can be added later.
