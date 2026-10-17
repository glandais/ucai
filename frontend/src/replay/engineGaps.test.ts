import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { ReplayWire } from '../api/types';
import { decodeReplay } from '../api/decode';
import { ReplayModel } from './model';

/**
 * Written by the runner's `GapFixtureTest` from a real race (Il Lombardia, harness seed 1): a window of frames a few
 * km after the early break was caught, and the engine's `GroupSummary.gapS` of each group at its last frame.
 */
interface EngineGapsFixture {
  t: number;
  groups: { id: number; size: number; headDistM: number; gapS: number }[];
  replay: ReplayWire;
}

const fixture = JSON.parse(
  readFileSync(new URL('./engine-gaps.fixture.json', import.meta.url), 'utf8'),
) as EngineGapsFixture;

describe('time gaps against the engine', () => {
  const model = new ReplayModel(decodeReplay(fixture.replay));
  const snapshot = model.snapshotAt(fixture.t);

  it('gives every group the engine gap after a catch', () => {
    expect(fixture.groups.length).toBeGreaterThan(3);
    for (const g of fixture.groups) {
      const mine = snapshot.groups.find((s) => s.id === g.id);
      expect(mine, `group ${g.id}`).toBeDefined();
      expect(mine!.size).toBe(g.size);
      // Frames are 1–5 s apart and rounded to 0.1 m; the engine interpolates every tick.
      expect(Math.abs(mine!.gapToHeadS - g.gapS), `group ${g.id}: ${mine!.gapToHeadS} vs ${g.gapS}`).toBeLessThan(1);
    }
  });

  it('differs from the gap to the current leader once the break is caught', () => {
    // The old definition (the current head rider's own passage) gave smaller gaps behind a caught break.
    const head = snapshot.groups[0]!;
    const leaderIndex = model.meta.riderIndex.get(head.headRiderId)!;
    const differs = snapshot.groups.slice(1).some((g) => {
      const tp = model.passTime(leaderIndex, g.headDistM);
      return tp !== null && Math.abs(fixture.t - tp - g.gapToHeadS) > 1;
    });
    expect(differs).toBe(true);
  });
});
