# Batch 2 review

Date: 2026-10-16
Scope: commits `c9fd6ed` (riders) → `0c96ced` (calibration), reviewed on `develop`.
Fixes: `19c6ca2 fix: review`.
Reviewer evidence: `./gradlew build`, engine tests, `:runner:calibrate --seeds 1..3` (9 races), replay files and `--trace` output.

## What batch 2 delivered

| Commit | Content |
|--------|---------|
| `c9fd6ed feat: riders` | Seeded generator of 10 fictional teams × 7 riders (`ucai.engine.riders`), public 1–5 ratings, course kinds and leader choice. |
| `72383cc feat: race engine` | Race engine (`ucai.engine.race`): group pace and rotation, descents, split/merge hysteresis, modes, sprint, events, results, lockstep decision points. 70 riders over a full course in ≈ 0.8 s. |
| `74bf3bf feat: replay recorder` | Replay v1 recorder with adaptive frames. |
| `62651be feat: calibration harness` | Scripted team strategies (`team/…/strategy`) run directly against the engine, `./gradlew :runner:calibrate`. |
| `0c96ced feat: calibration` | Tuning in `engine/…/physics/Tuning.kt` and the strategies; new durability formula; crawl-speed guard. |

Information asymmetry check: `TeamStrategy` lives in `team/` (depends on `api/` only) and receives the public riders, its own riders and, at each decision point, exactly the `DecisionEvent` the kernel will stream to that team. It cannot read other teams' private state.

## Findings

### 1. Major — no early breakaway ever survived

`team/src/commonMain/kotlin/ucai/team/strategy/TeamStrategy.kt`

- In 7 of 9 races the break's maximum gap was 5–10 s. Best case: Milano-Sanremo seed 2, 79 s at km 84, caught at km 119 of 298. A real break gets 5–10 min and is caught after km 250.
- Cause: before the break is established, the control teams pulled at `CONTROL_TEMPO` 0.95 × cpEff — about the break's own pace (`BREAK_PULL` 0.95). Once established, `control()` fell through to `else -> return` while the gap was between 0.6 × target and target: nobody pulled, the peloton fell back to the engine's default rotation at 1.03 × cpEff and caught the break.
- The calibration report did not show it, because it only looked at the top 10.

Requested fix: a domestique holds the gap at steady tempo instead of nobody pulling, lower early control tempo, report the break's max gap and catch point, and assert them in `CalibrationTest`.

### 2. Major — the field broke apart early and stragglers finished absurdly far behind

`team/src/commonMain/kotlin/ucai/team/strategy/TeamStrategy.kt`

- Seeds 1..3: in Liège-Bastogne-Liège and Il Lombardia, 37–48 of 70 riders finished more than 10 min down and 14–22 more than 30 min down. The last finisher was 84–115 min behind. 24–42 finish groups, mostly riders alone.
- Example: Il Lombardia seed 3, 13 domestiques dropped on the Selvino (km 24–33), peloton down to 37 riders by km 95.
- Causes: control pulls at 1.03 × cpEff on early climbs (0.95 + a +0.1 terrain bonus), and dropped riders riding home alone in `pull` at 0.8 × an already reduced cpEff, never regrouping.

Requested fix: terrain bonus only in the final ~60 km, early tempo 0.8–0.85, dropped riders wait for the group behind, report riders more than 10/20 min down and the last finisher's gap, and assert a time-cut-like bound.

### 3. Minor — finished riders still looked like they were racing

`engine/src/commonMain/kotlin/ucai/engine/race/Race.kt`

Finished riders kept their last speed, `groupId` and `rankInGroup`, so frames showed them moving at the line and a frontend would count them in a racing group.

### 4. Minor — 1 s replay frames after the winner finished

`engine/src/commonMain/kotlin/ucai/engine/replay/ReplayRecorder.kt`

The head of the race included finished riders, so every frame after the winner's finish was a 1 s frame: in Milano-Sanremo seed 1, 3854 of 4576 one-second frames came after the winner finished (≈ 40 % of the file).

### 5. Minor — orders could be applied twice for the same decision

`engine/src/commonMain/kotlin/ucai/engine/race/Race.kt`

Calling `applyOrders` again for the same decision re-applied the orders, re-emitting attack events and resetting attack timers, contrary to the §4 lockstep contract.

## Fixes applied (`19c6ca2`)

All 5 findings were addressed.

| # | Resolution |
|---|------------|
| 1 | Control teams ride tempo at 0.85 while the break goes away; once established, one domestique holds the gap with an effort following the gap (0.82–1.05); teams chase above target and always in the last 30 km. Target gap 1.3 s per km to go (was 1.6). |
| 2 | No climbing bonus before the last 60 km, control pulls on climbs capped at 0.95. Spent domestiques stay in the wheels while they have W′; once dropped they wait at 1.0 while a group is within 2 min behind, and ride home in `pull` at 1.0 only when nobody is behind. |
| 3 | Finished riders have speed 0, `groupId` = `FrameRider.FINISHED_GROUP` (0) and `rankInGroup` = finishing position. Documented in `FrameRider` and DESIGN §4. |
| 4 | Frame step computed from the head of riders still racing. New test. Milano-Sanremo seed 1 replay: 8067 → 7421 frames. |
| 5 | A repeated `applyOrders` for the same lockstep decision only validates and applies nothing (no-op rather than an error, to keep the "same body twice is idempotent" contract). New test. |

Beyond the findings, tuning also needed: solo break riders easing off so others can join, spent break riders still taking turns at 0.9, domestiques working down to 75 % of CP, strongest first.

Harness: the report now includes the break's maximum gap (real time gap) and catch point, the number of riders more than 10/20/30 min down and the last finisher's gap (`runner/…/calibration/RaceMetrics.kt`). `CalibrationTest` asserts, for 3 courses × seeds 1..3: break ≥ 120 s, caught after 70 % of the distance, last finisher within 60 min, at most 14 riders more than 20 min down.

## Results after fixes (seeds 1..3)

| | Milano-Sanremo | Liège-Bastogne-Liège | Il Lombardia |
|---|---|---|---|
| Break max gap | 180 / 223 / 202 s (was 6 / 82 / 8) | 153 / 191 / 172 s (was 7–10) | 173 / 186 / 206 s (was 9–33) |
| Break caught at | km 258 / 270 / 257 of 297 | km 210 / 210 / 209 of 257 | km 209 / 209 / 209 of 238 |
| Last finisher | +28–44 min (was +52–69) | +39–52 min (was +90–106) | +35–49 min (was +100–116) |
| Riders > 20 min down | 1–6 | 7–10 | 4–9 |
| Finish groups | 10–13 | 18–30 | 28–34 |
| Winning average speed | 40.6–41.7 km/h | 37.7–38.0 km/h | 36.4–36.8 km/h |
| Target speed (§8) | 40–44 ✓ | 39–42 ✗ | 39–42 ✗ |
| Winners | sprinter ×3 | climber, all-rounder, puncheur | climber ×2, all-rounder |

Over seeds 1..8 the break won twice (Liège-Bastogne-Liège 8, Il Lombardia 7). Every rider finishes, no order is rejected, a race takes ≈ 1 s.

## Open issues

- **Speeds on Liège-Bastogne-Liège and Il Lombardia are 1–3 km/h under target.** Before the fixes they were in range, but only because nobody controlled the race and the peloton rode the engine's fast default pace — which is what killed the break and dropped riders. In this model the peloton's speed ends up close to the break's; raising the break's effort to 1.0 gained 0.8 km/h but let the break win by 8 min. Left for the final calibration pass (DESIGN §9 step 6).
- **Time cut:** the last finisher is within 60 min, not the 25 min asked. Dropped domestiques have lost 20–30 % of CP by the finish and the engine has no abandons yet (v2).
- **`GroupSummary.gapS`** (distance ÷ current speed) is inflated on climbs. The harness uses a real time gap, but the strategy still reads `gapS` from the API.
- **Carried over to the kernel (batch 3):**
  - public ratings returned at team registration are computed on an incomplete field — use `RatingScale.REFERENCE` or re-rate once all teams are in;
  - the `DROPPED` rider status is never set;
  - `RaceDetail.seed` must stay null on public endpoints;
  - real course files can't be tested from `commonTest` (`jvmTest` is rejected by the build rule).
