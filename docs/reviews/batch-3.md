# Batch 3 review

Date: 2026-10-16
Scope: commits `d7c60c6` (dropped status) → `8e12c2e` (batch runner), reviewed on `develop`.
Fixes: `c8a33a6 fix: review`.
Reviewer evidence: `./gradlew build`, kernel/team/runner tests, `:runner:run` with `--compare`, a kernel started by hand and probed with curl (with and without tokens).

## What batch 3 delivered

| Commit | Content |
|--------|---------|
| `d7c60c6 feat: dropped rider status` | The engine sets `DROPPED` with the `rider_dropped` event and back to `RACING` when the rider's group merges. `RiderStatus.onRoad` in api. |
| `d50a5a6 feat: kernel api` | Kernel server: every §4 endpoint, admin and team bearer tokens, public/team SSE streams (≤ 10 snapshots per wall second), lockstep coordinator, realtime mode, replay recording, `startKernelServer(config, port = 0)`. |
| `2980b6c feat: http team bot` | `TeamBot`: Ktor CIO client + SSE, drives the existing `TeamStrategy`, loud failures, one reconnection. |
| `5a6764b merge` | Both branches merged without conflicts. |
| `8e12c2e feat: batch race runner` | `./gradlew :runner:run`: embedded kernel, 10 bots over real HTTP per course, replays + results + SHA-256 result hash, `--serve`, `--compare` against the calibration harness. |

### Contracts worth knowing

- **Lockstep start:** a team that has never connected its stream is waited for with no timeout; the race is `running` but held at the current decision, and the team gets the pending decision when it connects. A team that connected then closed its stream is auto-acked at each decision until it reconnects. `POST /start?wait=false` auto-acks teams that never connected.
- **Orders:** 409 for a missing or stale `decisionId` or a race not running. After the ack, only the order already in effect is accepted again; anything else gets `decision_acked`.
- **Streams:** open with `state`, then `snapshot`, then (team stream) `team` and the pending `decision` if not acked. 1 s heartbeat comments. A client 4096 events behind is disconnected, so a slow public client can't stall a race.
- **HTTP codes:** create race and register team answer 201; `GET /state` is 409 before the start and `GET /replay` is 409 before the finish; replay is gzip only with `Accept-Encoding: gzip`.
- **Harness = kernel:** the HTTP run and the calibration harness give the same hash for the same course and seed. Two things had to be aligned: ratings on `RatingScale.REFERENCE` (the strategy picks roles from ratings) and the 50 m course points served by the API (the strategy reads the terrain from them).
- **Kernel streaming tests** run on a real embedded CIO server: Ktor 3.6's test engine only delivers a streamed response once the handler ends.

## Findings

### 1. Major — the race seed could be recovered without any token

`kernel/src/commonMain/kotlin/ucai/kernel/KernelRace.kt`

- Riders were generated only from the public race seed and the team index, and the public start list shows every rider's name. A low-entropy seed (42 in the runner) can be brute-forced by matching names, which gives back every team's exact profiles and the engine's `Random(seed)`.
- Keeping `RaceDetail.seed` null therefore did not hide the seed, which defeats the information asymmetry of DESIGN §4.

### 2. Minor — a stale order reported as accepted after the ack

`kernel/src/commonMain/kotlin/ucai/kernel/KernelRace.kt`

On decision 0: pull 0.8 (no ack), pull 0.9 with `ack: true`, then pull 0.8 again → answered `accepted` (200), while 0.9 is the order applied. The check used the set of every order ever sent, not the order in effect.

### 3. Minor — wrong error codes

`kernel/src/commonMain/kotlin/ucai/kernel/Kernel.kt`

An unknown `courseId` on `POST /races` answered 400 instead of 404, and 401 answers had an empty body instead of `{"error": "..."}`.

No issue was found on robustness (bot disconnecting mid-race, two races at once, slow public client) or determinism.

## Fixes applied (`c8a33a6`)

| # | Resolution |
|---|------------|
| 1 | The kernel derives the generation and engine seed as `raceSeed(seed, seedSecret)` (first 64 bits of SHA-1 over secret + seed). `KernelConfig.seedSecret` comes from `UCAI_SEED_SECRET`, random by default. The runner uses a fixed `BatchRunner.SEED_SECRET` unless the env var is set, so a batch can be rebuilt; `Calibration.run`/`field` take the same optional secret, so `--compare` still matches. Team names, colors and strategy params still come from the public seed. Test: riders are not generated from the bare seed. |
| 2 | After the ack, an order is accepted only if it equals the order in effect for that rider; otherwise `decision_acked`. Test added. |
| 3 | Unknown course → 404. Every 401 carries `{"error":"missing or unknown token"}` and `WWW-Authenticate: Bearer`, through a small custom `bearerWithErrorBody` provider (Ktor 3.6's `bearer {}` has no `challenge {}`). |

## Results (seed 42, after fixes)

Two runs with `--compare` give the same 3 hashes, equal to the harness.

| Course | Avg speed | Hash (prefix) |
|---|---|---|
| Milano-Sanremo | 41.0 km/h | `e00d3477` |
| Liège-Bastogne-Liège | 38.1 km/h | `542937c2` |
| Il Lombardia | 36.9 km/h | `3de94b6b` |

Before the seed fix (different riders): Milano-Sanremo won by a sprinter in a bunch sprint, Liège-Bastogne-Liège by a puncheur, Il Lombardia by an all-rounder; run times 4–9 s per race, ≈ 1 000 decisions and 10 × decisions + 55 HTTP requests per race, gzip replays 1.2–1.4 MB. The whole batch takes ≈ 18 s.

## Open issues

- **Speeds on Liège-Bastogne-Liège and Il Lombardia** remain 1–3 km/h under target (carried from batch 2, final calibration pass).
- **Seed and restarts:** a kernel started without `UCAI_SEED_SECRET` gets a new secret at each start, so the same seed gives different riders after a restart.
- **Replay seed:** the replay keeps `seed: null` even after the finish; revealing it then (reviewer's alternative) was not done.
- **Runner needs the embedded kernel:** it calls `awaitFinish()` before downloading, because `race_finished` reaches the bots before the replay is encoded. A remote kernel would need polling.
- **Liège-Bastogne-Liège seed 8:** the break won and 16 riders finished more than 20 min down; `CalibrationTest` only checks seeds 1..3.
