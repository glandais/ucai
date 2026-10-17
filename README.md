# UCAI

A cycling race simulator for one-day monuments: Milano-Sanremo, Liège-Bastogne-Liège and Il Lombardia. Teams are controllers that talk to the kernel over HTTP. The kernel is power-based and deterministic. A React frontend replays the races.

- Spike goals: [docs/SPIKE.md](docs/SPIKE.md)
- Design and calibration: [docs/DESIGN.md](docs/DESIGN.md)
- Batch reviews: [docs/reviews/](docs/reviews/)

## Modules

| Module | Role |
|--------|------|
| `api/` | Shared DTOs and API constants (KMP common) |
| `engine/` | Courses, physics, fatigue, groups, race, replay recorder. No I/O (KMP common) |
| `kernel/` | Ktor server: REST `/api/v1`, SSE, auth, lockstep |
| `team/` | Team v1 bot and strategies. Depends on `api/` only |
| `importer/` | GPX/KML → `races/courses/*.json` |
| `runner/` | Batch race runner and calibration harness |
| `frontend/` | React + Vite + MapLibre replay viewer |

Requirements: JDK 25 and Node 22+.

## Usage

```sh
./gradlew build                                   # build + all JVM tests

# Run the 3 races over HTTP, write replays/, then serve them for the frontend
./gradlew :runner:run --args="--courses milano-sanremo,liege-bastogne-liege,il-lombardia --seed 42 --out replays/ --serve"

cd frontend && npm install && npm run dev         # http://localhost:5173 (proxies /api to :8080)
```

Other commands:

```sh
./gradlew :runner:calibrate --args="--seeds 1..32"   # realism report + score (lower is better)
UCAI_ADMIN_TOKEN=secret ./gradlew :kernel:run --args="--port 8080"   # standalone kernel
./gradlew :importer:run                              # regenerate races/courses from races/raw
```

Kernel environment variables: `UCAI_ADMIN_TOKEN`, `UCAI_SEED_SECRET`, `UCAI_HOST`, `UCAI_PORT`, `UCAI_COURSES`.
