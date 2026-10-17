# Batch 5 review

Date: 2026-10-17
Scope: commits `bcce959` (real time gaps) → `b24cde0` (screenshots), reviewed on `develop`.
Fixes: `4d73994 fix: review`.
Reviewer evidence: `./gradlew build`, `:runner:calibrate` on seeds 1..8, 9..40 and 100..103 (fresh seeds, never used for tuning), `--terrain true` climb splits, `--trace`, seed-1 replays compared with the engine's gaps, `:runner:run --compare` on seed 42.

## What batch 5 delivered

| Step | Commits | Content |
|------|---------|---------|
| Baseline | `bcce959`, `90767d7` | Real time gaps: `PassTimes` records when the head of the race passed each course point, and `GroupSummary.gapS` is now a gap in time, used by the strategies, kernel and harness. Calibration score (`Score.kt`, lower is better), documented in DESIGN §8. Baseline on seeds 1..8: **44.96**. |
| Engine pace | `af2536e`, `380403a`, `6ff9906` | Peloton draft floor 0.25, some shelter for the front rider, more power held on climbs (`cpNow`), later and slower durability loss, shared turns in the default rotation. Score 12.03 alone. |
| Tactics | `37cc33c`, `f715d4e`, `12ceb12` | Gruppetto, domestiques sitting in up to 1.25 × CP, break and hold pulls capped on climbs, late attack, rouleurs in the break. Score 26.29 alone. |
| Terrain | `52d698a`, `bd0d7c4`, `11f010f`, `9e5f19e` | Speed capped by turn radius, tucked descents, Crr 0.0032, `--terrain` climb splits. Score 35.03 alone. |
| Select | `45eb2c5`, `9763140`, `6c9f293`, `6544bd8` | All three merged; uphill gain 10 → 8 %, front shelter 8 → 5 %. Score **7.55** on 1..8, 8.41 on 1..16. |
| Verify | `47836bc`, `ce53890`, `1f8c382`, `b24cde0` | `CalibrationTest` over 3 courses × seeds 1..8, DESIGN §8 final state, Playwright screenshot script committed (`npm run screenshots`), screenshots regenerated. |

Runner and harness give the same hash for seed 42 on all three courses, at every step.

## Findings

| # | Severity | File | Problem |
|---|----------|------|---------|
| 1 | Major | `runner/…/calibration/Score.kt` | Almost no race won solo or with a gap: 2nd place at +0 s in 93 of 96 fresh races. `top10Gap` takes the largest gap in the top 10, so a sprint of 8 climbers scores a perfect 0. |
| 2 | Major | `team/…/strategy/TeamStrategy.kt` | Break gap and catch point were set by constants: max gap 233–244 s in 33 of 36 races (clamped by `CONTROL_MAX_GAP_S` = 240 s, then a full chase even 200 km out), Lombardia caught at km 209–211 in 28 of 44 races (`CONTROL_CATCH_KM`). |
| 3 | Major | `team/…/strategy/TeamStrategy.kt` | A break slow to build its lead was reeled in 100–150 km from the line: after km 80 the hold floor (0.92) was harder than the tempo (0.85). E.g. Sanremo seed 28 caught at km 147 of 297. |
| 4 | Major | `runner/…/calibration/CalibrationTest.kt` | Overfitting: on seeds 9..40 the score is 42 % worse (10.74 against 7.55), Lombardia's twice as bad, and 12 races break the bounds the test locks in. |
| 5 | Minor | `team/…/strategy/TeamStrategy.kt` | Milano-Sanremo was a bunch sprint every time (41 of 44): Cipressa 10:25 and Poggio 6:29, too slow to be selective. The Sanremo score could not see it. |
| 6 | Minor | `frontend/src/replay/model.ts` | Frontend gaps used the current head rider, the engine the furthest rider ever: 1–23 s apart after a catch. |
| 7 | Minor | `engine/…/physics/Physics.kt` | Tuned numbers outside `Tuning`/`Tactics`: `CRR`, and inline tactic numbers (attack intensity, `maxFollow`, chase thresholds, a duplicate of `CONTROL_CATCH_KM`). |
| 8 | Minor | `frontend/README.md` | "Measures" table still had batch-4 numbers. |

## Fixes applied (`4d73994`)

| # | Resolution |
|---|------------|
| 1 | New `winnerMargin` race term (3–90 s at LBL and Lombardia) and `selectiveShare` course term. Tactics: attacks from groups of 2, retried while W′ is short, only the strongest leaders attack on the last climb, responders let go below 30 % W′, riders mark a rival when a teammate is in the move. The engine keeps a marking rider out of the rotation. **Only partly effective**, see open issues. |
| 2 | The most gap the peloton allows (4–8 min) and the start of the chase (60–80 km to go) are drawn once per race from the seed. Further out, one rider per team holds the gap. `CONTROL_CATCH_KM` is gone. Max gaps now 2.7–8.1 min, catches 7–70 km from the line. |
| 3 | Hold floor 0.8 (under the 0.85 tempo); the break counts as established only once it reaches its target. Test: never caught more than 80 km out on seeds 1..40 (worst over 1..64: 69.5 km). |
| 4 | Tuning on seeds 1..32, reporting on 33..64. `CalibrationTest.realismTargetsOnSeeds1to40` (120 races, ≈ 14 s): hard per-race limits plus "at least 90 % of races in band". `calibrate` gained `--threads`, `--csv`, `--out none`, `--reports false`, `--trace-head`. Harness bug fixed: a break reported as caught at the line when the biggest group was behind. |
| 5 | Done in the engine instead of harder pulls: the shared-turn bonus is weighted by the aero share of the effort, so groups no longer climb at 1.3 × CP. Chases depend on role (domestiques 1.1/1.15, sprinter teams 1.3, leaders 0.95). Sanremo is now decided by ≤ 5 riders in 20–40 % of races. |
| 6 | `model.ts` uses the same running-max head as `PassTimes`. `GapFixtureTest` writes a real-race fixture; `engineGaps.test.ts` checks the frontend within 1 s of the engine. |
| 7 | `CRR` in `Tuning`, every inline tactic number named in `Tactics`. |
| 8 | Re-measured with a new committed script (`npm run measure`). |

## Results after fixes

New score terms: not comparable with the 7.55 above (the pre-fix state scores 15.44 on 1..8 with the new score).

| Seeds | Before fixes | After |
|---|---|---|
| 1..32 (tuning) | 14.39 | 9.85 |
| 33..64 (held out) | 14.80 | 8.42 |

| Seeds 1..32 [33..64] | Milano-Sanremo | Liège-Bastogne-Liège | Il Lombardia |
|---|---|---|---|
| Winning speed | 42.1–43.0 km/h ✓ | 39.3–40.3 ✓ | 37.9–38.9 ✗ (target 39–42) |
| Winners | puncheur 23, sprinter 9; 16 riders | puncheur 16, climber 9, all-rounder 7 | climber 20, all-rounder 11, rouleur 1 |
| Small-group finish | ≤ 5 riders in 6/32 [13/32] | ≤ 3 riders in 11/32 [10/32] | ≤ 3 riders in 17/32 [17/32] |
| Winner margin ≥ 3 s | 0 | 3 [2] | 1 [4] |
| Break max gap | 3.2–7.6 min | 3.6–7.6 min | 3.9–8.1 min |
| Break caught (km to go) | 12–66 | 23–66 | 16–67; won 1/32 |
| Last finisher | +7–31 min | +13–41 min | +17–42 min |
| Score | 0.20 [0.07] | 4.55 [4.91] | 5.10 [3.45] |

Everywhere (192 races): top speed 76–83 km/h, best 20-min power ≤ 6.35 W/kg, best 60-min ≤ 413 W, every rider finishes, no order rejected.
Seed 42 over HTTP (same hash as the harness): Sanremo 42.42 km/h, a puncheur from a pair after the Poggio; LBL 39.85 km/h, a puncheur from a group of 9; Lombardia 38.15 km/h, an all-rounder from a group of 6.

## Open issues

- **Solo wins stay rare** (margin ≥ 3 s in 5 of 64 races at LBL and at Lombardia). Everyone reaches the last climb with little W′, drafting on a climb gives back about 5 %, and fresher chasers outsprint a soloist. Four tactical variants had no measurable effect; this is likely an engine question (W′ model, climbing draft, sprint after a long effort).
- **Il Lombardia 0.2–1 km/h under 39 km/h** (test accepts 38.0). Harder climbs drop domestiques and cost more than they gain.
- **Stragglers 15–43 min down** at LBL and Lombardia: no abandons or time cut in v1.
- **The break almost never wins** (1 of 192).
- **Gap fixture** (`frontend/src/replay/engine-gaps.fixture.json`, ≈ 390 KB) must be regenerated after any engine or strategy change: `UCAI_WRITE_FIXTURES=1 ./gradlew :runner:test --tests '*GapFixtureTest*'`.
- Merge commits `45eb2c5` and `9763140` lack the session trailers (local, not rewritten).
- Carried over: seed secret changes on restart without `UCAI_SEED_SECRET`, replay seed stays null, runner needs the embedded kernel, `group_merge` has no "ahead" flag, Lombardia map clutter, 1.3 MB JS chunk.
