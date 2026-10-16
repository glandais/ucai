import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../../api/decode';
import { syntheticReplayWire } from '../../replay/fixtures';
import { ReplayModel } from '../../replay/model';
import type { GroupState } from '../../replay/source';
import { declutter, followedGroup, groupLabelText, labelPriority, pelotonId, visibleBoxes } from './labels';

const model = new ReplayModel(decodeReplay(syntheticReplayWire()));

const g = (id: number, size: number): GroupState => ({
  id,
  size,
  riderIds: [],
  headDistM: 0,
  tailDistM: 0,
  headRiderId: id,
  lat: 0,
  lon: 0,
  eleM: 0,
  gapToHeadS: 0,
  gapToAheadS: 0,
  kmToGo: 0,
});

describe('group labels', () => {
  it('names the peloton only when it is a real bunch', () => {
    expect(pelotonId([g(1, 5), g(2, 60), g(3, 5)])).toBe(2);
    expect(pelotonId([g(1, 5), g(2, 4)])).toBeNull();
    expect(pelotonId([g(1, 12), g(2, 12), g(3, 12), g(4, 12), g(5, 12)])).toBeNull();
    expect(pelotonId([])).toBeNull();
  });

  it('labels leaders, chasers and gaps', () => {
    const s = model.snapshotAt(500); // A at 5000 m, B at 4000 m (100 s behind)
    expect(groupLabelText(model.meta, s, 0, null)).toEqual({ title: 'LEADERS', size: 2, gap: '' });
    expect(groupLabelText(model.meta, s, 1, null)).toEqual({ title: 'GROUP', size: 2, gap: '+1:40' });
    expect(groupLabelText(model.meta, s, 1, s.groups[1]!.id).title).toBe('PELOTON');
    // After the winner finished, the first group on the road shows its gap to the winner.
    const late = model.snapshotAt(1100);
    expect(late.groups).toHaveLength(1);
    expect(groupLabelText(model.meta, late, 0, null).gap).toMatch(/^\+/);
  });

  it('shows the rider name for a lone rider', () => {
    const s = model.snapshotAt(500);
    const lone = { ...s, groups: [{ ...s.groups[0]!, size: 1, headRiderId: 1 }] };
    expect(groupLabelText(model.meta, lone, 0, null).title).toBe('A. ALPHA');
  });
});

describe('declutter', () => {
  it('leaves separate labels alone and lifts overlapping ones above', () => {
    expect(declutter([{ x: 0, y: 100, w: 50, h: 20 }, { x: 0, y: 200, w: 50, h: 20 }])).toEqual([0, 0]);
    expect(declutter([{ x: 0, y: 100, w: 50, h: 20 }, { x: 100, y: 100, w: 50, h: 20 }])).toEqual([0, 0]);
    // Same spot: the second goes right above the first (2 px apart), the third above the second.
    expect(
      declutter([
        { x: 0, y: 100, w: 50, h: 20 },
        { x: 10, y: 100, w: 50, h: 20 },
        { x: 5, y: 105, w: 50, h: 20 },
      ]),
    ).toEqual([0, -22, -49]);
  });

  it('hides a label rather than pushing it too far', () => {
    const b = { x: 0, y: 100, w: 50, h: 20 };
    expect(declutter([b, b, b], 2, 30)).toEqual([0, -22, null]);
  });

  it('orders labels: head of the race, followed group, then bigger groups', () => {
    const groups = [g(1, 3), g(2, 40), g(3, 2), g(4, 40), g(5, 1)];
    expect(labelPriority(groups, null)).toEqual([0, 1, 3, 2, 4]);
    expect(labelPriority(groups, 5)).toEqual([0, 4, 1, 3, 2]);
    expect(labelPriority(groups, 1)).toEqual([0, 1, 3, 2, 4]);
  });

  it('hides lower-priority boxes that collide', () => {
    const r = (x: number, y: number) => ({ x0: x, y0: y, x1: x + 50, y1: y + 10 });
    expect(visibleBoxes([r(0, 0), r(10, 5), r(200, 0), r(40, 0)])).toEqual([true, false, true, false]);
  });
});

describe('followedGroup', () => {
  it('follows the lead group, or a rider through splits, until the rider finishes', () => {
    const s = model.snapshotAt(500);
    expect(followedGroup(s, { kind: 'none' })).toBeNull();
    expect(followedGroup(s, { kind: 'lead' })?.headRiderId).toBe(1);
    expect(followedGroup(s, { kind: 'rider', riderId: 9 })?.riderIds).toContain(9);
    expect(followedGroup(model.snapshotAt(1100), { kind: 'rider', riderId: 1 })).toBeNull();
    expect(followedGroup(s, { kind: 'rider', riderId: 404 })).toBeNull();
  });
});
