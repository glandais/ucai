# UCAI — notes for Claude

See README.md for the commands and docs/DESIGN.md for the spec. Keep DESIGN.md in sync when you change behaviour.

## Rules

- **KMP common code** in `api`, `engine`, `kernel` and `team`: no `java.*`, no wall clock, no JVM reflection. Use only seeded `kotlin.random.Random`. Only the JVM target is built. The root build rejects `jvmMain`/`jvmTest` dependencies, except in kernel's `jvmMain`.
- **Dependencies**: `engine → api`, `kernel → engine, api`, `team → api`, `runner → kernel, team`. Never `kernel → team`.
- **Information asymmetry**: the public API and replays never expose exact physiology, private live state, modes or the seed. A team only sees its own riders' private state.
- **Determinism**: same course and seed give the same result hash. `:runner:run --compare` must match the calibration harness.
- **Tuning**: numbers live in `engine/…/physics/Tuning.kt` and `team/…/strategy/Tactics`, not inline.
- **Calibration**: tune on seeds 1..32 and check on 33..64. Seeds 1..8 alone overfit. `CalibrationTest` covers seeds 1..40.

## After engine or strategy changes

- `./gradlew build` and `./gradlew :runner:calibrate --args="--seeds 1..32"`. Update the DESIGN §8 tables if the numbers move.
- Regenerate the frontend gap fixture: `UCAI_WRITE_FIXTURES=1 ./gradlew :runner:test --tests '*GapFixtureTest*'`.
- Screenshots (`docs/screenshots/`): run `:runner:run … --serve`, start Vite, then run `npm run screenshots` in `frontend/`.

## Frontend

From `frontend/`: `npm run typecheck`, `npm test`, `npm run build`. The `RaceSource` seam keeps replay and live interchangeable.

## Workflow

- Commit on `develop`, one commit per logical unit, short conventional messages (`feat: x`, `fix: review`, `docs: …`). Never push.
- Development runs in batches. Each batch gets a review and a fix commit, and the review goes in `docs/reviews/batch-N.md`.
