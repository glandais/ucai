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
4. Smooth elevation (≈100 m window; LBL: spike filter capping each 10 m step at 2 m, then a 200 m window) — raw GPS/DEM elevation gives unrealistic ±30 % spikes. Max 100 m gradient ≤ 22 % on every course.
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

Archetypes generated from a seed: climber, puncheur, sprinter, rouleur, all-rounder, domestique. The rider pool is **fictional** (generated names, fictional teams). Sprinters keep the lowest CP/kg of the field but at 4.5–4.95 W/kg (rider + bike; first 4.3–4.75), so the best of them can survive the Cipressa and the Poggio.

Public profile: name, team, archetype and **ratings from 1 to 5** (climbing, punch, sprint, endurance, descending). Exact numbers are only given to the rider's own team.

### Rider live state

`distM`, `speed`, `power`, `wPrimeBal`, `cpEff`, `wPrimeEff`, `energyKj` (work done), `groupId`, `rankInGroup`, `mode`, `status` (`racing` | `dropped` | `finished` | `abandoned`).

`dropped`: a rider who loses contact with his group — alone or in a pair splitting off the back, the `rider_dropped` event — is `dropped` until his group merges with another one (he bridges back, or a group from behind catches him), then `racing` again. A dropped rider is still on the road: he rides, takes orders and finishes like the others (`RiderStatus.onRoad` is `racing` or `dropped`). Controllers must not read `status == racing` as "still in the race".

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
- Stall guard: a racing rider never goes below a crawl speed of **1.5 m/s** (≈ 5 km/h, walking the bike). On a 17–22 % ramp a cooked rider's power can be below gravity even at the 1 m/s floor, and he would otherwise stop for good.
- η = 0.976 drivetrain efficiency, g = 9.81, Crr = 0.0032 (race tyres on good asphalt, `Tuning.CRR`; 0.004 until the final calibration pass), ρ from elevation (≈1.225 at sea level, −10 % per 1000 m).
- Wind: one optional constant vector per race (direction + speed, 0 by default). `v_wind_axial` is its component along each segment's heading.
- The aero time constant (≈10 s at race speed) keeps dt = 1 s stable.

**Drafting** `k_draft` by rank in the group: 1st 1.00 alone, down to 0.95 at the front of a big group (the riders behind push the air: 1.4 % with one rider behind, 3.2 % in a group of 4, a little under Blocken 2013's 2.6 % and the ≈ 5 % of a team-pursuit line), 2nd 0.64, 3rd 0.55, then decreasing to a floor of ≈0.50 in a tiny group and ≈0.25 deep in a large peloton (CFD of a 121-rider peloton finds 5–10 % of the solo drag in its middle; the first value, 0.35, kept domestiques sitting in at 13–15 % CP lost and 5–16 riders more than 20 min down). No special case on climbs: aero drag is already small at climbing speeds, so drafting fades naturally. The group's pace setter rides with the front factor.

### Fatigue

- **W′ balance** (Skiba differential model): above `cpEff`, W′bal drains by (P − cpEff)·dt; below, it recovers exponentially.
- **Durability**: accumulated work lowers both CP and W′, and slows W′ recovery:

  ```
  loss       = (1 − durability) · (max(0, energyKj / massKg − E0) / K)^N    E0 = 5 kJ/kg, K = 220 kJ/kg, N = 0.7, capped at 0.35
  cpEff      = cp · (1 − loss)
  wPrimeEff  = wPrime · (1 − loss)
  τ_recovery = τ_base · (1 + loss)
  ```

  Calibration targets: a domestique (durability 0.5) who has done 2000 kJ has lost 10–15 % of CP (10.4 %); a protected leader (0.8) ~5 % at 1500 kJ (3.2 %; 4 % at durability 0.75). The final pass moved E0 from 3 to 5 kJ/kg and K from 185 to 220 within these targets: the controlling domestiques' fading CP was what slowed the whole race over the day. The first form, `(energyKj / (massKg · K))²`, could not hit both, and a monument costs every rider 4000–6000 kJ (≈ 170 W average for 7 h, like real power files), where it put the whole field at 25–35 %. The concave form keeps growing slowly after the targets: at the finish leaders are at 6–10 %, domestiques at 9–13 % on average (19 % for a 0.55 domestique at 5000 kJ). The onset E0 (~350 kJ) means the first half hour or so costs nothing. This is the core mechanic of the monuments.
- When W′bal ≈ 0, a rider can't produce more than `cpEff`.
- **Uphill power**: on a climb a rider holds more power than on the flat for the same effort (lower speed and inertia, more torque per pedal stroke; field and lab studies find a few % to ~10 %). The engine multiplies the CP a rider is measured against by a terrain factor, 1 below 2 % and rising linearly to 1.08 at 6 %: intensities, caps and the W′ balance use `cpEff × terrain` (`cpNow`), while the `cpEff` reported to teams stays the flat value. Without it the peloton climbed at 4.2–4.5 W/kg behind a domestique riding tempo, and LBL and Lombardia were 1–3 km/h under target.
- No nutrition/bonk model in v1.

### Groups

Riders are 1D (distance along the course). `rankInGroup` gives a stable order inside a group; lateral position is cosmetic and derived by the frontend from it.

Each tick:
1. **Group pace**:
   - If some riders in the group are in `pull` mode, they ride at the front at their intensity and set the speed.
   - Otherwise, **default rotation**: riders in `follow` take turns at the front at a default intensity, changing every 30–60 s (seeded). A rider who can't hold that pace goes to the back of the rotation.
     - Intensity **1.03 × `cpEff`**, **1.1** on climbs (gradient ≥ 3 %: a 10–30 min climb is ridden near the 20-min power), configurable per race; never above the rider's own `maxIntensity` (1.0 when not ordered). The first value, 0.65, gave races at 31–37 km/h.
     - Only the stronger **half** of the eligible riders take turns: ranked by `cpEff` over the power the front costs them at the group's speed on the current gradient (W/kg on climbs, W/CdA on the flat), recomputed at each turn change. The others sit in.
     - **Shared turns**: k riders taking turns each spend 1/k of the time in the wind and recover in the wheels, so at the same effort a turn is harder: the intensity is multiplied by 1 + 0.2 · aero · (1 − 1/k) (on the flat × 1.09 for a pair, × 1.16 for 10 riders), then capped by `maxIntensity` and W′. A big organized group rides faster than a pair. `aero` is the aero share of the front rider's resistance at the group's speed (≈ 0.9 on the flat at 45 km/h, ≈ 0.1 on a 7 % climb): the wheels only save the aero part, so on a climb a group rides little faster than one rider. Without it (batch-5 review), groups climbed at 1.3 × `cpEff` until their W′ ran out, which equalised everyone at the top of every climb. `pull` intensities are not scaled: an ordered pull is the power at the front, and strategies already pick higher values when several riders share a chase (scaling them too let every break go to the line and the chases drop half the field).
     - Not eligible: `recover` riders, `follow` riders with `maxIntensity` < 0.9, riders whose W′bal is below 30 %, and `follow` riders marking a rider of their own group (`targetRiderId` in the same group: they sit on that wheel).
     - A group with nobody eligible rides at the pace of its front rider: `recover` at 0.7 × `cpEff` (was 0.55), `follow` at min(default, `maxIntensity`).
   - **Descents** (gradient ≤ −3 %): group speed = the faster of the power-derived speed and the freewheeling speed of a tucked rider (CdA × 0.8) on the 2nd wheel — downhill the wheels come past a slower front rider — then capped by the speed limit below for the leading rider's `descending`. Followers freewheel. No splits on descents in v1.
   - **Speed limits** (`SpeedLimits`, every terrain, every rider and group): straight descents 75–90 km/h by `descending`, 1 km/h less per % below −3 %; turns at a lateral grip of 0.65–0.9 g by `descending` (lean 33–42°) on the radius measured from the course headings plus 3 m of racing line, so a 10 m hairpin is taken at 33–39 km/h; riders brake before a turn at 0.6 × their grip less the slope's pull. The radius is the distance between the midpoints of two segments over their heading change, the smallest over windows of 40, 80 and 160 m (course tracks are OSM polylines with a node every 25–100 m, so a 20 m window sees every node of a smooth bend as a hairpin).
2. **Followers** compute the power needed to hold the group speed with their draft factor, bounded by their mode (`follow` with `maxIntensity`, `recover` at 0.75 × `cpEff`); with `maxIntensity` above 1 a follower digs into W′ to hang on. If they can't produce it, they slow down and a gap opens.
3. **Split** (with hysteresis): a rider or sub-group more than **3 s** behind the rider ahead **for 3 consecutive ticks** becomes a new group.
4. **Merge**: a group that comes within **1 s** of the group ahead merges into it.
5. **Attack**: a rider in `attack` mode leaves with a burst; they become a new group once the split rule fires. Other riders only respond if their controller tells them to (`follow` with `targetRiderId`); otherwise the group keeps its pace.

**Time gaps.** `GroupSummary.gapS` (snapshots, decisions) is a real time gap, computed by the engine (`PassTimes`): it keeps when the head of the race passed each course point (10 m resolution, interpolated between ticks) and a group's gap is how long ago the head passed the group's front rider. The head is the furthest rider ever, finished riders included, so after the winner's finish gaps are to the winner, like the results. Strategies, kernel, harness (`RaceView.gapAt`) and the frontend agree: the frontend computes its own from the frames, the same way (the per-frame head distance, a running max over all riders, and its passage at the group's front rider, interpolated between frames); `frontend/src/replay/engineGaps.test.ts` checks it against the engine on a real race just after a break was caught, where the gap to the current leader's own passage differs by 1–20 s. The first version, distance ÷ the group's current speed, inflated gaps when the group was climbing (and shrank them on descents).

Only stable groups produce events (`group_split`, `group_merge`), which avoids event storms and useless decision points.

Cost: O(riders) per tick. With 70 riders and ~25 000 ticks per race, a race takes well under a second of CPU time without controllers. HTTP round-trips at decision points dominate batch time.

### Rider modes (what orders set)

| Mode | Parameters | Behavior |
|------|------------|----------|
| `follow` (default) | `targetRiderId?`, `maxIntensity?` (fraction of `cpEff`, default 1.0) | Stays in the group at minimal power, takes part in the default rotation. With `targetRiderId`, tries to stay with that rider (closes gaps, follows attacks) but never above `maxIntensity` — beyond that the gap opens and the controller gets a decision point. With a `targetRiderId` in the rider's own group he marks that rider: sits on his wheel and never takes a turn. |
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
- **Public**: read-only race state, public stream, replays. No auth. Never exposes exact rider numbers (`cp`, `wPrime`, `durability`, `power`, `wPrimeBal`), private live state, riders' modes or the race seed (`RaceDetail.seed` is always null in kernel responses, replays included). The seed alone must not rebuild the profiles either: the kernel mixes it with a kernel-side secret (`KernelConfig.seedSecret`, `UCAI_SEED_SECRET`, random when unset) into `raceSeed(seed, secret)` (SHA-1 of both, 64 bits) before generating riders and seeding the engine, so brute-forcing a small public seed against the public rider names gives nothing.
- A missing or unknown token is a `401` (`WWW-Authenticate: Bearer`, body `{"error": "missing or unknown token"}`); a valid team token on another team's (or another race's) route is a `403`. The admin token is not a team token and vice versa.
- Validation of every order: the rider belongs to the team, values in range, `decisionId` is the current one.
- Errors are `{"error": "..."}` with `400` (bad body or value), `404` (unknown course, race or team), `409` (wrong race state: registration closed, already started, no snapshot before the start, no replay before the finish).
- v1 leaves out rate limiting and CORS: the frontend uses the Vite dev proxy. TLS is left to a reverse proxy.

### Courses (public)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/courses` | List courses (id, name, distance, elevation gain). |
| GET | `/courses/{courseId}` | Full course: points decimated to one every 50 m (finish kept), profile, climbs, and `track`, the map geometry: the 10 m course simplified by Douglas-Peucker within 1 m, each point with its `distM` (one point every 50 m cut hairpins by up to 13 m). |

### Races

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/races` | admin | Create a race: `{courseId, seed, clock: {mode}, wind?: {fromDeg, speed}}` → `{raceId}` in state `registration`. |
| GET | `/races` | public | List races (state, course, start time). |
| GET | `/races/{raceId}` | public | Metadata, public start list (archetypes and ratings), current state. |
| POST | `/races/{raceId}/teams` | admin | Register a team: `{name, color, riders?: [...]}` (riders generated if omitted) → `{teamId, token, riders}` with the riders' **full** profiles. |
| POST | `/races/{raceId}/start[?wait=false]` | admin | Start → the public race detail. See "Start" below. |
| GET | `/races/{raceId}/state` | public | Current public snapshot. |
| GET | `/races/{raceId}/stream` | public | **SSE** public stream (see below). |
| GET | `/races/{raceId}/results` | public | Classification (partial while racing). |
| GET | `/races/{raceId}/replay` | public | Full recording (finished races). |

Registration (kernel v1): teams get ids `team-01`, `team-02`… in registration order (at most 10, unique names, `#rrggbb` colors) and rider ids `index × 7 + 1…7`. Generated riders come from `raceSeed(seed, secret)` and the team index (independent streams, so a team's riders never depend on the others), explicit riders must be 7 and pass the profile limits. Public ratings are computed against `RatingScale.REFERENCE` (a fixed reference field), so they do not depend on the other teams nor on the registration order.

Start: in `lockstep`, a team whose stream has **never** connected is waited for — the race is `running` but held at decision 0 (or any later decision) until that team connects and acks, with no timeout. So a runner can register teams, start its bots and call start in any order: a bot that connects after the start receives the pending decision on connect. A team that connected and whose stream then closed is auto-acked with no orders at every decision until it reconnects (logged once per absence). `?wait=false` auto-acks never-connected teams too (a race with absent teams, e.g. for tests or demos).

### Team (controller)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/races/{raceId}/teams/{teamId}` | Team private view: own riders with full state. |
| GET | `/races/{raceId}/teams/{teamId}/stream` | **SSE** private stream: public events + own riders' state + `decision` events. |
| POST | `/races/{raceId}/teams/{teamId}/orders` | `{decisionId, orders: [{riderId, mode, intensity?, durationS?, targetRiderId?, maxIntensity?}], ack: true}` |

Order semantics:
- In `lockstep`, orders are only accepted for the **current** `decisionId`; stale ones are rejected. All orders of a decision point are applied together when every team has acked, in a canonical order (`teamId`, then `riderId`; for the same rider, the last order sent wins). Arrival order never matters.
- `ack: true` closes the team's decision. Sending orders without `ack` is allowed; sending the same body twice is idempotent. After the ack the team's orders are frozen: a repeat of an order in effect (the last one sent for that rider) is accepted (no-op), any other, including an order overwritten before the ack, is rejected with `decision_acked`.
- A missing `decisionId` (`missing_decision`), a stale one (`stale_decision`, no ack) or a race that is not running (`race_not_running`) answer `409` with every order rejected; otherwise `200`.
- In `realtime`, `decisionId` is optional (ignored) and orders apply at the next tick.
- The response lists accepted and rejected orders with a reason.

### SSE streams

Standard `text/event-stream`, event `id` = tick. No resume buffer in v1 (`Last-Event-ID` ignored): a reconnecting client starts with a fresh `snapshot`. A stream opens with `state`, then (once started) a `snapshot`, and on a team stream the `team` state and the pending `decision` if the team has not acked it. When the race finishes the kernel sends the final `snapshot` and `state: finished`, then closes every stream. A comment heartbeat (1 s) lets the kernel notice a closed connection while the race waits. A client more than 4096 events behind is disconnected (it reconnects with a fresh snapshot); the tick loop never waits for a client.

| Event | Payload | Rate |
|-------|---------|------|
| `snapshot` | `{tick, raceTimeS, riders: [[riderId, distM, speed, groupId, rankInGroup]], groups: [{id, headDistM, size, gapS}]}` (`gapS`: real time gap to the head of the race, §3 "Time gaps") | every tick, but at most ~10 per wall second |
| `event` | `{tick, type, ...}` | when it happens |
| `state` | `{state: registration|running|finished}` | on change |
| `decision` (team stream only) | `{decisionId, tick, reasons, snapshot, team, events}` — the public snapshot, own riders' full state, and the events since the previous decision | at each decision point |
| `team` (team stream only) | own riders: `power, wPrimeBal, cpEff, wPrimeEff, energyKj, mode` | with snapshots |

A bot can make all its decisions from the `decision` event alone, with no extra GET. `realtime` races (1 tick per wall second) send no `decision` events in v1: controllers follow `snapshot` / `team` and post orders at any time.

### Replay format

`GET /races/{raceId}/replay` (`Content-Encoding: gzip` when the client sends `Accept-Encoding: gzip`, plain JSON otherwise; encoded once at the finish). `course` is the decimated public course, `race.seed` is null:

```json
{ "version": 1, "race": {...}, "course": {...}, "teams": [...], "riders": [...],
  "frames": [ { "t": 0, "r": [[distM, speed, groupId, rankInGroup], ...] }, ... ],   // rider order = "riders" order
  "events": [ ... ], "results": [ ... ] }
```

Adaptive frame step: one frame every **5 s** of race time, every **1 s** while the head of the riders still racing is in the last 3 km and for 60 s after an attack. The frontend interpolates `distM` linearly between frames; `groupId` and `rankInGroup` change as steps. A finished rider stands at the line: `speed` 0, `groupId` 0 (no group), `rankInGroup` = finishing position.

---

## 5. Team v1 (bot)

Pure Kotlin common code using the API only (Ktor client + SSE). One instance per team, each in its own coroutine. It decides only from `decision` events. Simple, readable strategy driven by rider archetypes and the course:

- **Roles** at start: 1 leader (best fit for the course: climber for Lombardia, puncheur for LBL, sprinter/puncheur for Sanremo), 1–2 lieutenants, domestiques.
- **Early race**: some teams (seeded) send a rider in the breakaway (their strongest rouleur or pure domestique by CP: climbers and puncheurs are kept for the finale; sending the strongest domestique of any archetype let climber domestiques win LBL and Lombardia from the break); break riders take turns in `pull` at 1.05 (1.08 in the last 30 km), capped at 0.95 on climbs before the finale: the valleys are ridden hard, the climbs at tempo. The strongest teams (3 by default) control: one domestique each rides tempo (`pull` 0.85) while the break goes and builds its lead, since a peloton without pullers rides the faster default rotation and would never let a break go. The race decides how much the break gets: the most the peloton allows is a race-level draw of 4–8 min (`StrategyParams.breakGapS`, from the seed in the batch, the same for every team), held down to 150 km to go and then shrinking linearly towards the finish. Once the gap has first reached that target (not at a fixed km: a break slow to build its lead keeps the tempo), one domestique each holds it with a pull following the gap (0.8–1.15, 0.95 at most on climbs; the floor is under the tempo, so the hold never reels in a slow break 150 km out). From a race-level 60–80 km to go (`StrategyParams.chaseFromKm`) they chase it down: 2 domestiques each at 1.15, 3 at 1.25 while the break is still more than 5 s per km to go ahead. There is no catch point: the break is caught where its gap, its legs and the climbs say (5–70 km from the line on seeds 1..64). With no break ahead before the finale (caught early, or none went) they still ride tempo at 0.95, else the peloton rides the default rotation for hours and the field explodes.
- **Climbs in the finale** (named climbs in the last ~60 km): domestiques `pull` hard at the foot (1.15, 1.1 at Sanremo: above the default rotation on climbs, or the pull would slow the group), the leader `follow`s, attacks happen on the last 1–2 climbs depending on W′bal and the leader's profile (puncheurs attack late, 300–2500 m before the summit, climbers earlier on long climbs), from a group of 2 or more. A leader at its attack point waits for the next decision point half of the time, never attacks at the decision point that reports a rival's attack from its group (it follows first, counters later), and tries again at the next decision points while on the climb if its W′bal is short (50 %, 25 % on the last climb: whoever has more left goes); on the last climb only the leaders that look strongest in their group (fewer than 2 rivals with a better public course fit) attack, the others follow if they can.
- **Terrain**: break, control and chase pulls get +0.1 where the next 500 m climb at 3 % or more, like the default rotation.
- **Sitting in**: riders without another order `follow` with `maxIntensity` 1.3 (leader, lieutenant, sprinter) or 1.25 (domestiques; 1.1 was too low to survive a 4-min ramp like the Côte de Saint-Roch), so they use W′ to stay in the group on short climbs.
- **Response**: when a rival leader attacks, the leader or a lieutenant follows (`follow` + `targetRiderId` + `maxIntensity` 1.15–1.35) if their W′bal allows it (45 %), and lets the wheel go once it is under 30 %.
- **Chase**: within 50 km, a team whose leader is behind (≥ 10 s) chases with 2 fresh helpers, domestiques at 1.1; in the last 8 km, from 3 s, with up to 3 helpers who still have W′, domestiques at 1.15, or 1.3 for a team whose leader is a sprinter (a sprint is its only chance). A lieutenant or sprinter helping takes turns at 0.95, and a leader chasing without helpers at 0.95 too: a group of leaders chases poorly. This decides whether a Poggio attack sticks or the sprinters' teams, while they have domestiques left, bring it back. A team never chases a move with one of its own contenders in it.
- **Sitting on** (in the last 50 km): the riders of a team with a contender in the move ahead, and the contenders of every group behind a move, mark the strongest rival of their group (`follow` with that `targetRiderId`), so they never take turns in the default rotation; the chase is left to the helpers' explicit pulls.
- **Finale**: a teammate `pull`s in the last km for the sprinter, contenders (leader, lieutenant, sprinter) `sprint` in every group in the last ~1 km, everyone in a small front group; others follow the best wheel.
- **Late attack**: after the last climb, between 8 and 1.5 km to go, a leader (climber, puncheur, all-rounder) in a front group of 2–30 that holds a rival with a better public sprint rating attacks once if his W′bal is at least 35 % (chance 0.25 × (0.5 + aggressiveness) per decision point, shared by the leaders of the group so they don't all go together, 30 s). In a small front group the leader's turns are at 1.05 in the last 8 km (0.92 before); alone in front he rides at 1.15, W′ permitting. Without it, LBL nearly always ended in a sprint of a group of 10–25.
- **Spent riders** (`cpEff` < 85 % of CP or W′bal < 15 %): domestiques and lieutenants hang on in the wheels (`follow` 1.1) while they have W′, else `recover`; once dropped behind the peloton (more than 120 s, or in a group of 3 or less) they soft-pedal (`follow` 0.8) while a group is less than 120 s behind, so the stragglers gather, and the last of them ride home in `pull` at 1.15 (they take turns, so the group goes at about their CP).

Every rider on the road gets orders, `dropped` ones included (`status.onRoad`, §2).

Strategy parameters (aggressiveness, breakaway, control, and the most the peloton gives the break and where it starts chasing, which the batch draws once per race) come from the team config, so a batch can compare strategies.

---

## 6. Batch race runner

The `runner` module:

```
./gradlew :runner:run --args="\
     --courses milano-sanremo,liege-bastogne-liege,il-lombardia \
     --seed 42 --out replays/"
```

1. Starts the kernel (embedded Ktor server, CIO) on a local port: `startKernelServer(KernelConfig(adminToken, CourseCatalog.of(courses)), port = 0)` returns the bound port and the `Kernel` (an optional `configure` block runs on the Ktor application first: the runner installs a request counter there). Standalone: `UCAI_ADMIN_TOKEN=… ./gradlew :kernel:run --args="--port 8080"` loads `races/courses/`.
2. For each course: creates a race (`lockstep` clock) with the admin token, registers 10 teams (names and colors of `RiderGenerator.teamIdentities(seed)`, riders generated by the kernel), starts **10 team bots in the same process** (coroutines, one shared CIO client engine) that connect over HTTP with their tokens, waits until every bot's stream is open, then starts the race. Strategy parameters per team are the calibration harness's `Calibration.teamParams(seed, teamIds)`. A failing bot fails the run (the race would otherwise go on with that team auto-acked).
3. Runs to the finish, downloads the results and the replay through the API (`Accept-Encoding: gzip`, written as served), writes `replays/<course>-<seed>.json.gz`, `replays/<course>-<seed>.results.json` and `replays/results.json` (one line per race: winner, time, average speed, decisions, HTTP requests, run time, hash), prints the top 10, the key events (summits, attacks of the last 60 km, finish) and a **result hash**: SHA-256 of the classification entries in the API JSON (race id left out).
4. Exits (or keeps serving the replays with `--serve`, for the frontend, on `--port`, 8080 by default with `--serve`). `--compare` also runs the calibration harness on the same course and seed and checks that the hashes are equal. The admin token is `UCAI_ADMIN_TOKEN`, random when unset. The embedded kernel's seed secret is `UCAI_SEED_SECRET`, or the fixed `BatchRunner.SEED_SECRET` when unset, so a batch is reproducible from its seed; `--compare` passes the same secret to the harness (`Calibration.run(course, seed, secret = …)`).

Targets: one race in < 1 min, and the same seed gives the same result hash.

Measured (seed 42, 3 monuments): 4–9 s per race over HTTP, ≈ 950–1000 decision points, ≈ 10 000 HTTP requests per race (10 order POSTs per decision point, + 55: admin calls, each bot's 3 GETs and its stream, the runner's downloads), 1.0–1.2 MB gzipped replay (1.2–1.4 MB before the final calibration). Two runs give the same hashes, and so does the calibration harness.

**Harness = kernel.** The calibration harness runs exactly the race the kernel runs for the same course and seed: same start list (`Calibration.field(seed, secret)`: riders from `raceSeed(seed, secret)`, the generated field rated against `RatingScale.REFERENCE`, like kernel registration), same course detail given to the strategies (`decimatedDetail()`, the 50 m points of `GET /courses/{id}`), same strategy parameters. Both matter: the strategy picks roles from public ratings, and reads the terrain ahead from the course points; with field-relative ratings or the full 10 m course the harness raced a different race (different hashes on all 3 courses).

---

## 7. Frontend v1 (replay)

React + Vite + TypeScript, MapLibre GL with OpenFreeMap vector tiles (free, no key). The Vite dev server proxies `/api` to the kernel.

- **Race list**: finished races from the kernel API, or a replay file loaded locally.
- **Replay view**:
  - Map: course line, climbs marked, one dot per rider colored by team, group labels. Line and dots use the course `track` (the `points` when a course has none, e.g. the full 10 m course of harness replays); the profile uses the `points`.
  - **Elevation profile** (canvas) below: groups as markers on the profile, climbs shaded — the "TV graphic" of the race.
  - **Groups panel** (TV-style): head of race, gaps in time, riders in each group with their team color, km to go.
  - Event list (not clickable in v1).
  - Playback: play/pause, a few speeds (×1 → ×60), scrubbing on a timeline.
  - Results table at the end.
- Code split between a **data source** (replay file now, SSE later — same snapshot shape) and the views, so live mode is a new source and no view rewrite.

Later: camera following a group, higher playback speeds, clickable events.

---

## 8. Testing

- Physics unit tests: steady speeds against known values (e.g. 400 W, 70 kg, flat, CdA 0.32 → ≈ 43 km/h solo; 6 % climb: 400 W → ≈ 24 km/h, 21 km/h at ≈ 335 W), drafting gain, W′bal drain/recovery, durability loss.
- Group logic tests: split/merge with synthetic riders, including hysteresis (no oscillation around the threshold), a group with no `pull` rider, descents.
- Course import tests: distance within 1 % of the official one after the km 0 cut, elevation gain plausible, LBL built from the two race lines only.
- API tests (Ktor `testApplication`): auth scopes, no private numbers on public endpoints, order validation, stale `decisionId` rejected. The streaming tests (a full lockstep race driven by minimal in-test bots over HTTP + SSE, the `decision` payload, auto-ack of a closed stream, privacy of both streams) run on the real embedded CIO server on a free port, as the runner starts it: Ktor's test engine only delivers a streamed response once it ends.
- **Calibration harness** (no HTTP): scripted strategies run directly against the engine, to tune durability, default rotation and sprint before the bots exist.
- Batch determinism (`BatchRunnerTest`): Il Lombardia run end to end over HTTP twice, on two fresh embedded kernels (10 real bots, HTTP + SSE) → identical result hash, frames and events, and identical to the calibration harness for the same seed (≈ 20 s).
- Sanity on results (`CalibrationTest`, seeds 1..40 of the harness, bounds in §8 "Locked in"): winning average speed in a realistic range (40–44 km/h Sanremo, 39–42 LBL/Lombardia), finale gaps of seconds to a minute or two, not 20 min; winners that fit the course, and different seeds give different winners; physically plausible speeds and powers.
- Time gaps, engine = frontend (`GapFixtureTest` in `runner`, `engineGaps.test.ts` in `frontend`): the runner writes a window of a real race just after a break was caught with the engine's `GroupSummary.gapS` of each group, checks the committed fixture is current, and the frontend test checks its own gaps against it.

### Calibration (harness, default `RaceConfig`)

`./gradlew :runner:calibrate --args="--seeds 1..32"` prints one report per race (with its score and nonzero terms), a summary per course (with the course score, the mean penalty of each term, the winner's margin and group, the head of the race at 10/5/2 km to go and where the break was caught) and the score per course and in total at the end. Races run in parallel (`--threads`, default the processors; the output does not depend on it); `--out none` writes no replay, `--reports false` prints the summaries only, `--csv file` writes one line of metrics per race (`RaceRow`), `--trace true` adds a line every 10 km (`--trace-head 15`: the riders of the first 2 groups in the last 15 km, `--watch 12,34`: those riders). The tuned numbers live in `ucai.engine.physics.Tuning` (engine, Crr included) and `ucai.team.strategy.Tactics` (strategy); the race-level draws (the break's gap, where the chase starts) in `Calibration.teamParams`.

Changes are tuned and accepted on seeds 1..32 and reported on the held-out seeds 33..64: one constant moves an 8-seed score by ±1–3, and the batch-4 state, tuned on seeds 1..8, scored 42 % worse on fresh seeds.

Final state (batch-5 review: break control decided by the race, aero-weighted shared turns, sitting on and marking, chases by role; score with the `winnerMargin` and `selectiveShare` terms): seeds 1..32 score 9.85 (Sanremo 0.20, LBL 4.55, Lombardia 5.10); held-out seeds 33..64 8.42 (0.07, 4.91, 3.45); seeds 1..8 13.29 (2.13, 5.75, 5.41: Sanremo's 8 races had no Poggio finish, `selectiveShare` 1.78). The same score on the batch-4 state: 14.39 on 1..32, 14.80 on 33..64, 15.44 on 1..8.

| Course (seeds 1..32) | Winning avg speed | Winners | Finale | Early break: max gap, caught | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|--------|------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 42.1–43.0 km/h | puncheur 23, sprinter 9 (16 different riders; favourite 5/32) | top 10 within 0–35 s; decided by 5 riders or fewer in 6 of 32, else a sprint of up to 20 | 3.2–7.6 min, 12–66 km from the line, never won | +7–31 min, 0–1 | 93–96 % | 8.0–9.7 / 8.7–10.2 % | 0.20 |
| Liège-Bastogne-Liège | 39.3–40.3 km/h | puncheur 16, climber 9, all-rounder 7 (20; favourite 5/32) | top 10 within 0–47 s; won solo or from 3 riders or fewer in 11 of 32, by 3 s or more in 3 | 3.6–7.6 min, 23–66 km, never won | +13–41 min, 0–13 | 94–96 % | 5.8–7.9 / 10.7–12.7 % | 4.55 |
| Il Lombardia | 37.9–38.9 km/h | climber 20, all-rounder 11, rouleur 1 (17; favourite 9/32) | top 10 within 4–82 s; won solo or from 3 riders or fewer in 17 of 32, by 3 s or more in 1 | 3.9–8.1 min, 16–67 km, won 1/32 | +17–42 min, 0–15 | 91–96 % | 5.8–7.3 / 10.9–12.9 % | 5.10 |

Held-out seeds 33..64: Sanremo 42.1–43.2 km/h, puncheur 23, sprinter 7, all-rounder 2 (18 riders), 5 riders or fewer in 13 of 32, break caught 22–65 km out; LBL 39.5–40.5 km/h, puncheur 14, climber 12, all-rounder 6, 3 riders or fewer in 10, caught 24–70 km out, last finisher +15–43 min; Lombardia 38.2–39.0 km/h, climber 23, all-rounder 9, 3 riders or fewer in 17, caught 13–66 km out, last finisher +15–36 min. Everywhere (192 races): top speed 76–83 km/h, best 20-min power ≤ 6.35 W/kg, best 60-min power ≤ 413 W (≤ 5.3 W/kg), every rider finishes, no order is rejected, the break is never caught more than 70 km from the line and won 1 of 192. The HTTP runner gives the same hashes as the harness. Seed 42 through the runner: Sanremo 42.42 km/h, won by a puncheur from a pair after the Poggio (top 10 within 12 s); LBL 39.85 km/h, a puncheur from a group of 9; Lombardia 38.15 km/h, an all-rounder from a group of 6, top 10 within 23 s.

Score by term (mean penalty over seeds 1..32; every other term is 0): Sanremo last finisher 0.15, domestique CP lost 0.03, `selectiveShare` 0.01; LBL last finisher 2.01, `winnerMargin` 0.88, top-10 gap 0.71, riders > 20 min 0.69, winner 0.25; Lombardia riders > 20 min 1.49, last finisher 1.47, speed 0.97, `winnerMargin` 0.92, winner 0.23.

What changed, and what each step gave (seeds 1..32, score with the new terms, from 14.39):
- **Break control** (the review's findings on the clamped gap and the early catch): the target gap and the start of the chase are race-level draws (4–8 min, 60–80 km to go), the break is established when its gap first reaches the target, the hold's floor (0.8) is under the tempo (0.85), more than 60–80 km out the control holds with one rider per team instead of chasing, and there is no catch point (`CONTROL_CATCH_KM` is gone): 11.45. Max gaps now spread over 3–8 min and the catch over 5–70 km to go; before, 33 of 36 races had a 233–244 s gap and Lombardia was caught at km 209–211 in 28 of 44.
- **Shared turns weighted by the aero share** (engine): groups no longer climb at 1.3 × CP until their W′ runs out, so climbs select by W/kg; Sanremo's Cipressa and Poggio became selective (puncheurs win about 3 in 4, 5 riders or fewer at the line in 20–40 %), 12.83 with the first finale changes.
- **Finale**: teammates of a contender in the move sit on (an engine rule: a `follow` rider marking someone of his own group takes no turn), contenders behind a move sit on and leave the chase to the helpers' pulls, chases by role (domestiques 1.1 / 1.15 in the last 8 km, sprinters' teams 1.3, protected riders and leaders 0.95), attacks from groups of 2, retried while W′ is short, the last climb's attacks only by the strongest-looking leaders of the group, a shared late-attack chance, responders letting go under 30 % W′: 9.85.
- Tried and dropped: a leader on the last climb setting the pace for the whole group (no effect), only the best sprinter left answering a late move (no effect), a solo leader easing to keep a kick (no effect), break and control climb caps at 1.0 (Lombardia +0.5 km/h, score 7.7 / 9.5 for LBL / Lombardia: riders > 20 min), tempo 0.9 while the break goes (breaks that never get away, Lombardia 11.86), the default rotation at 1.15 on climbs (within noise), Sanremo climb pulls at 1.0 instead of 1.1 (same score, fewer different winners).
- Harness: the early break also counts as caught when a rider of the peloton reaches the head group in the last 60 km (in a finale where the biggest group is a chase group behind the race, the break was reported caught at the line).

**Locked in** by `CalibrationTest.realismTargetsOnSeeds1to40` (`runner`, the 120 harness races on all processors, ≈ 20 s), with bounds from the percentiles of seeds 1..40 rather than per-race maxima of 8 seeds. Every race: no rejected order, 70 finishers, top speed ≤ 90 km/h, best 20-min ≤ 6.8 W/kg, best 60-min ≤ 6.0 W/kg and ≤ 450 W, the break never caught more than 80 km from the line, the winner's archetype fit or acceptable unless the break won, the winning speed within 39–45 / 38–43 / 37–43 km/h (Sanremo / LBL / Lombardia), the last finisher within 60 min, ≥ 85 % of the riders on the road in the peloton at half distance. At least 90 % of the races of each course: winning speed in 40–44 / 39–42 / 38–42 km/h, break max gap 3–10 min, caught 5–70 km from the line unless it wins, top-10 gap ≤ 30 s / ≤ 45 s / 5–90 s, last finisher ≤ 20 / 40 / 35 min, riders > 20 min ≤ 1 / 8 / 11, leaders' CP lost 6–11 / 5–10 / 5–10 %, domestiques' 8–13 / 10–16 / 10–16 %. Per course: ≥ 85 / 45 / 50 % of winners that fit, ≥ 10 different winners, the break winning ≤ 20 % and the favourite ≤ 50 %, a share of races decided by a small group in 10–60 % / ≥ 15 % / ≥ 20 %, domestiques losing more CP than leaders on average, and the course score ≤ 0.6 / 6.5 / 6.5.

**Open** (the remaining gaps, as measured on seeds 1..64):
- The hilly monuments are seldom won solo: LBL and Lombardia end with 2–9 riders together in most races and the winner's margin is 3 s or more in 5 of 64 LBL and 5 of 64 Lombardia races (`winnerMargin` ≈ 0.9 per race). Small groups do form (3 riders or fewer in a third of LBL and half of Lombardia), but in the engine everyone reaches the last climb with little W′ left, attackers and followers are within the 5 % that drafting gives back on a climb, and a group of fresher chasers outsprints a soloist with an empty W′ in the last 300 m. Real LBL and Lombardia are usually won solo.
- Il Lombardia is ridden at 38–39 km/h, under the 39 km/h floor (`speed` ≈ 0.9; the test accepts 38). Its speed is set by the pace on the early climbs; riding them faster drops the weaker domestiques, who then finish 30–60 min down (climb caps at 1.0, again).
- LBL's and Lombardia's last finishers are 15–43 min down with up to 15 riders > 20 min: domestiques who did their work and were dropped on the mid-race climbs, at 75–83 % of CP, ride home. Real ones would abandon or make a gruppetto inside the time cut; there are no abandons in v1. This is most of their score.
- LBL is won by a puncheur in half the races (climbers and all-rounders the rest, acceptable for the score).
- The break wins 1 of 192 races, at the bottom of "may win sometimes".

Before the batch-5 review (tuned on seeds 1..8, score without `winnerMargin` and `selectiveShare`: 7.55 on 1..8, Sanremo 0.04, LBL 5.41, Lombardia 2.11):

| Course | Winning avg speed | Winners | Largest gap in the top 10 | Early break: max gap, caught at | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|---------------------------|---------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 42.4–42.9 km/h | sprinter 6, puncheur 2 (5 different riders; favourite 0/8) | 0–2 s, a bunch sprint | 4 min, km 257–270 of 297, never won | +9–14 min, 0 | 66–67 of 70 | 8.2–9.3 / 9.0–10.0 % | 0.04 |
| Liège-Bastogne-Liège | 39.9–40.5 km/h | puncheur 7, climber 1 (6 different riders; favourite 2/8) | 0–15 s | 2.8–4 min, km 179–222 of 257, never won | +20–45 min, 0–9 | 65–67 of 70 | 6.6–7.8 / 10.7–12.2 % | 5.41 |
| Il Lombardia | 38.5–38.8 km/h | climber 5, all-rounder 3 (7 different riders; favourite 2/8) | 13–63 s | 3.9–4 min, km 176–211 of 238, never won | +17–29 min, 0–7 | 66 of 70 | 6.2–7.3 / 11.0–12.5 % | 2.11 |

Everywhere: top speed 77–83 km/h (straight descents; hairpins are slower), best 20-min power ≤ 6.3 W/kg, best 60-min power ≤ 391 W (≤ 4.7 W/kg). Every rider finishes, no order is rejected, a race takes about 1 s in the harness. The HTTP runner gives the same hashes as the harness, run after run. Seed 42 through the runner (rider generation and engine RNG from the kernel's seed secret, so not the harness's seed 42 field): Sanremo 42.56 km/h, bunch sprint won by a sprinter; LBL 40.34 km/h, an all-rounder from a group of 4, top 10 within 53 s; Lombardia 38.41 km/h, an all-rounder from a group of 5, top 10 within 32 s.

Score by term (mean penalty over seeds 1..8; every other term is 0): Sanremo domestique CP lost 0.04 (9.0–9.7 % in 7 of 8 races, just under the 10 % band); LBL last finisher 3.75, top-10 gap 0.89 (the top 10 within 0–4 s in 6 of 8), riders > 20 min 0.48, caught to go 0.21, winner 0.06, break gap 0.01; Lombardia speed 0.50, last finisher 1.09, riders > 20 min 0.33, winner 0.19.

How it got there: the three approaches below were tuned separately against the baseline, then merged in order and re-measured (seeds 1..8, then 1..16 for the decisions, since a constant change moves the score by ±1–3): engine pace alone 12.03; + tactics 9.22 (kept); + terrain (turns, tucked descents, Crr 0.0032) 9.77 on 1..8 but 19.72 vs 20.22 summed over 1..8 and 9..16 (kept, and descents and hairpins are now realistic; Crr 0.004 instead scored the same within noise, so the measured race-tyre value stayed); then, the combined passes being faster, the uphill gain back from +10 % to +8 % (16 seeds 9.86 → 9.40) and the front shelter of a big group from 8 % to 5 % (→ 8.41), both back inside the literature. Tried on the combined state and dropped: a gruppetto pull of 1.25 (8.83), break and control climb caps at 1.0 (14.02: Lombardia +0.3 km/h but many more riders > 20 min), a front shelter that saturates faster (1.5 riders scale, 9.43).

Engine-pace pass alone (seeds 1..8, score 12.03):

| Course | Winning avg speed | Winners | Largest gap in the top 10 | Early break: max gap, caught at | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|---------------------------|---------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 41.7–42.8 km/h | sprinter 8 (7 different riders) | 0 s, a bunch sprint | 3–4 min, km 257–270 of 297, never won | +10–27 min, 0–1 | 66–67 of 70 | 8.4–9.5 / 9.2–10.1 % | 0.21 |
| Liège-Bastogne-Liège | 39.4–40.4 km/h | puncheur 7, all-rounder 1 | 0–6 s | 2.2–4.1 min, km 209–223 of 257, never won | +24–53 min, 1–12 | 60–66 of 70 | 6.6–7.9 / 11.0–12.9 % | 7.89 |
| Il Lombardia | 37.9–39.0 km/h | climber 8 (6 different riders) | 0–56 s | 3.6–4.5 min, km 208–219 of 238, never won | +21–44 min, 1–7 | 65–67 of 70 | 6.3–7.3 / 11.0–12.6 % | 3.94 |

Everywhere: top speed 70–73 km/h (descents), best 20-min power ≤ 6.3 W/kg, best 60-min power ≤ 412 W (≤ 4.9 W/kg). Every rider finishes, no order is rejected, about 1.2 s per race; the HTTP runner gives the same hashes as the harness. What the pass changed, all in the engine (`Tuning`), by how much each helped (score on seeds 1..8, each step on top of the previous ones): draft floor 0.35 → 0.25 deep in a large peloton (44.96 → 38.37), front-rider shelter (→ 35.57), uphill power (→ 23.28 at +6 %), durability onset and scale (→ 13.90 at +8 %), uphill +10 % and a stronger front shelter (→ 13.50), shared turns in the default rotation (→ 12.03). Tried and dropped: scaling `pull` intensities by shared turns (34–112), a softer or harder default rotation (± 0.05: within noise), faster descents (within noise). Seeds 9..16 score 14.90 with the same settings. What is left is mostly strategy: LBL's top 10 still finish together in 5 of 8 races (the leaders' attacks on the last climb are chased down by the teams whose leader is behind, then nobody attacks on the run-in), Lombardia is still up to 1 km/h under 39 (the race rides at the break's pace, `pull` 0.95, for 200 km), and domestiques dropped alone on an early climb (Saint-Roch, Valpiana) ride home alone 25–50 min down.

Tactics pass alone (team tactics only, engine unchanged; seeds 1..8, score 26.29):

| Course | Winning avg speed | Winners | Largest gap in the top 10 | Early break: max gap, caught at | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|---------------------------|---------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 41.1–41.8 km/h | sprinter 6, puncheur 2 | 0–9 s, a bunch sprint | 4 min, km 256–269 of 297, never won | +12–31 min, 0–2 | 66–67 of 70 | 10.7–12.1 / 11.5–12.8 % | 0.43 |
| Liège-Bastogne-Liège | 38.0–38.6 km/h | puncheur 5, climber 2, all-rounder 1 | 0–10 s | 3.5–4 min, km 179–210 of 257, never won | +21–39 min, 2–16 | 60–67 of 70 | 8.1–9.5 / 13.0–14.7 % | 9.26 |
| Il Lombardia | 36.6–37.2 km/h | climber 8 | 4–50 s | 4 min, km 209–217 of 238, never won | +23–55 min, 3–13 | 61–66 of 70 | 7.6–8.9 / 13.4–15.3 % | 16.60 |

Everywhere: top speed 68–73 km/h (descents), best 20-min power ≤ 5.9 W/kg, best 60-min power ≤ 383 W (≤ 4.6 W/kg). Every rider finishes, no order is rejected. Over seeds 1..16 the score is 27.58. What helped most (seeds 1..8 unless noted): dropped riders riding home together and harder (44.96 → 38.8), domestiques sitting in up to 1.25 (→ 36.9), valleys ridden harder by the break and the hold with the chase always above the hold (→ 34.1), the late attack and the leader's harder turns in the finale (16 seeds 36.7 → 33.8), rouleurs in the break (16 seeds 33.8 → 27.6). Open: LBL and Lombardia stay 0.5–2.4 km/h under the target: their speed is set by how fast the climbs are ridden, and riding them faster (break and control caps 1.0–1.05, a wider finale window) gains 0.5–1 km/h but drops the weaker domestiques (CP 4.55–5.15 W/kg with the bike), who then lose 20–25 % of CP and finish 30–60 min down, which costs more than the speed gains. The last finishers are 20–55 min down: no abandons yet (v2). Harness artifact: when the early break is never caught by the biggest group and a gruppetto of a third of the riders still racing forms after the winner's finish, `RaceMetrics` takes that gruppetto for the peloton and reports a 20–35 min break gap (LBL seed 8 in some variants).

Baseline before the final pass (real time gaps, seeds 1..8, score 44.96):

| Course | Winning avg speed | Winners | Largest gap in the top 10 | Early break: max gap, caught at | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|---------------------------|---------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 40.6–41.7 km/h | sprinter 7, puncheur 1 | 0–1 s, a bunch sprint | 3.2–4 min, km 257–272 of 297, never won | +13–46 min, 0–4 | 64–67 of 70 | 10.5–12.0 / 11.3–12.6 % | 2.36 |
| Liège-Bastogne-Liège | 37.7–38.8 km/h | puncheur 7, climber 1 | 0 s (215 s when the break won) | 2.6–6 min, km 209–222 of 257, won 1/8 | +32–54 min, 5–16 | 55–66 of 70 | 8.0–9.6 / 13.3–15.3 % | 16.81 |
| Il Lombardia | 36.2–37.0 km/h | climber 4, all-rounder 2, puncheur 2 | 0–62 s | 3.9–5.1 min, km 208–209 of 238, won 2/8 | +30–51 min, 4–10 | 62–66 of 70 | 7.6–8.7 / 13.5–15.1 % | 25.78 |

Everywhere: top speed 68–73 km/h (descents), best 20-min power ≤ 5.9 W/kg, best 60-min power ≤ 400 W (≤ 4.7 W/kg). Every rider finishes, no order is rejected, a race takes about 1 s including the strategies. With the old `gapS` (distance ÷ speed) the same harness scored 47.58 (Sanremo 4.44, LBL 16.49, Lombardia 26.64). Open: LBL and Lombardia are 1–3 km/h under the target range; no abandons yet (v2), so the last riders still finish 30–50 min down where real ones would abandon.

Terrain pass alone (turns, tucked descents, Crr 0.0032; seeds 1..8, score 35.03):

| Course | Winning avg speed | Winners | Largest gap in the top 10 | Early break: max gap, caught at | Last finisher, riders > 20 min | Peloton at half distance | CP lost, leaders / domestiques | Score |
|--------|-------------------|---------|---------------------------|---------------------------------|--------------------------------|--------------------------|--------------------------------|-------|
| Milano-Sanremo | 40.8–41.9 km/h | sprinter 7, puncheur 1 | 0 s, a bunch sprint | 3.2–4 min, km 257–271 of 297, never won | +12–39 min, 0–8 | 66–67 of 70 | 10.3–11.8 / 11.2–12.3 % | 1.58 |
| Liège-Bastogne-Liège | 38.2–39.3 km/h | puncheur 6, all-rounder 1, climber 1 | 0–6 s (194 s when the break was caught 3 km out) | 2.7–5.8 min, km 209–254 of 257, never won | +32–56 min, 2–16 | 56–66 of 70 | 7.9–9.4 / 13.1–15.2 % | 13.22 |
| Il Lombardia | 36.5–37.4 km/h | climber 5, puncheur 2, all-rounder 1 | 9–74 s | 3.8–5.1 min, km 208–210 of 238, won 2/8 | +25–49 min, 3–10 | 64–67 of 70 | 7.5–8.6 / 13.3–15.0 % | 20.23 |

Everywhere: top speed 77–84 km/h (straight descents), best 20-min power ≤ 6.0 W/kg, best 60-min power ≤ 386 W (≤ 4.7 W/kg). Score of each step: turns and tucked descents 42.69, then Crr 0.0032 35.03 (without the turns it would be 30.00, but with Lombardia's hairpins taken at 70+ km/h).

Where the time goes (`--terrain true`, winner of seed 1): descents are now close to real splits (Poggio descent 3.2 km in 3:36, 53 km/h; Cipressa descent 62 km/h; Lombardia descents ≤ −5 % at 62 km/h, LBL's at 67 km/h). The deficit is on the climbs and the flat, i.e. in the pace the strategies ride: Lombardia's early climbs at VAM 1130–1350 (Selvino 26:27, Ghisallo 24:15; a real peloton climbs them near VAM 1500), the finale climbs at VAM 1600–1950 (Civiglio 12:21), and 116 km of rolling road at 43 km/h behind domestiques pulling at 0.82–0.85 × CP. The course profiles are clean (10 m vs 100 m gradients differ by 0.2–0.3 % rms, no fake ramp outside the named climbs, the batch-1 tunnel bridge and LBL step limit already removed the DEM spikes).

#### Score

One number, lower is better (`runner/…/calibration/Score.kt`), to compare calibration approaches objectively. Each term is a metric with a target band [lo, hi] and a tolerance below / above; its penalty is

```
penalty = weight · min(9, (distance outside the band / tolerance)²)     (0 inside the band)
race    = Σ race terms
course  = mean of its races + Σ course terms (over the seeds)
total   = Σ courses
```

| Term | Metric | Band | Tolerance | Weight |
|------|--------|------|-----------|--------|
| `speed` | winning average speed, km/h | Sanremo 40–44, LBL and Lombardia 39–42 | 1 | 3 |
| `winner` | winner's archetype: 0 if it fits (Sanremo sprinter/puncheur, LBL puncheur, Lombardia climber), 0.5 if acceptable (Sanremo all-rounder/rouleur, LBL climber/all-rounder, Lombardia puncheur/all-rounder), else 1 | 0 | 1 | 2 |
| `breakGap` | early break's max real time gap, s | 180–600 | 60 / 180 | 2 |
| `caughtToGo` | km to go when the early break is caught (0 when it wins): the peloton is at the head again, or in the last 60 km a rider of the peloton reaches the head group | 5–60 | 20 | 2 |
| `top10Gap` | largest gap inside the top 10, s | Sanremo 0–120, LBL and Lombardia 5–120 | 5 / 60 | 2 |
| `winnerMargin` | gap from the winner to 2nd, s (real LBL and Lombardia are mostly won solo, by a few seconds to a minute and a half) | LBL and Lombardia 3–90, Sanremo any | 3 / 60 | 1 |
| `pelotonAtHalf` | share of the riders on the road in the peloton when its head reaches half distance | ≥ 0.75 | 0.1 | 2 |
| `lastFinisherMin` | last finisher's gap, min | ≤ 15 | 10 | 1 |
| `over20min` | riders more than 20 min down | ≤ 2 | 4 | 1 |
| `leaderCpLost` | mean CP lost by the leaders at the finish, % | 5–12 | 2 | 1 |
| `domestiqueCpLost` | same for the domestiques, % | 10–20 | 3 | 1 |
| `maxKmh` | top speed of any racing rider, km/h | ≤ 90 | 5 | 2 |
| `max20WKg` | best 20-min mean power of any rider, W/kg | ≤ 6.8 | 0.3 | 1 |
| `max60WKg` | best 60-min mean power, W/kg | ≤ 6.0 | 0.3 | 1 |
| `max60W` | best 60-min mean power, W | ≤ 500 | 30 | 1 |
| `breakWinShare` (course) | share of seeds won from the early break (winner in the break, not caught before the last km) | ≤ 0.2 | 0.15 | 2 |
| `favouriteWinShare` (course) | share of seeds won by the favourite (best public course fit, `FinaleKind.fit`, of the field) | ≤ 0.5 | 0.25 | 1 |
| `selectiveShare` (course) | share of seeds decided by a small group: the winner's group at the line (riders with his time) of at most 5 riders at Sanremo (a Poggio move, as in 2019 and 2021–2025), 3 at LBL and Lombardia (solo or 2–3 riders) | Sanremo 0.2–0.5, LBL and Lombardia ≥ 0.3 | 0.15 | 1 |

The cap keeps one broken metric from hiding all the others; the weights put the headline targets (speed, then the shape of the race and plausibility) first. A perfect race scores 0; one km/h under the speed band costs 3, the whole field together at Lombardia's line costs 2, a sprint of the front group at LBL or Lombardia (0 s margin) costs 1. The batch-4 `top10Gap` alone could not see a sprint of 8 climbers with 9th place 30 s back, nor a Sanremo that ends the same way every year; `winnerMargin` and `selectiveShare` (batch-5 review) do.

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
