import { describe, expect, it } from 'vitest';
import type { Classification, RaceEvent } from '../../api/types';
import { decodeReplay } from '../../api/decode';
import { syntheticReplayWire } from '../../replay/fixtures';
import { createReplaySource } from '../../replay/model';
import { buildEventItems, GroupTracker, ordinal, riderList, segmentsText } from './events';
import { groupLabels, groupSpeed, pelotonIndex, teamCounts } from './groups';
import { resultRows } from './results';

const source = () => createReplaySource(decodeReplay(syntheticReplayWire()));
const meta = source().meta;
const sizes = (...s: number[]) => s.map((size) => ({ size }));
const texts = (events: RaceEvent[]) => buildEventItems(meta, events).map((it) => segmentsText(meta, it.parts));

describe('groups panel helpers', () => {
  it('finds the peloton as the largest big-enough group', () => {
    expect(pelotonIndex(sizes(3, 50, 4))).toBe(1);
    expect(pelotonIndex(sizes(5, 4))).toBe(-1); // too small
    expect(pelotonIndex(sizes(10, 10, 10, 10, 10, 10))).toBe(-1); // 17 %: fragmented race
    expect(pelotonIndex([])).toBe(-1);
  });

  it('labels head of race, chasers, peloton and groups behind', () => {
    expect(groupLabels(sizes(2, 50, 3), 0).map((l) => l.title)).toEqual(['Head of race', 'Peloton', 'Group 3']);
    expect(groupLabels(sizes(2, 4, 50), 0).map((l) => l.title)).toEqual(['Head of race', 'Chasers', 'Peloton']);
    expect(groupLabels(sizes(2, 4, 3, 50), 0).map((l) => l.title)).toEqual(['Head of race', 'Chase 1', 'Chase 2', 'Peloton']);
    expect(groupLabels(sizes(60, 2), 0)).toEqual([{ title: 'Peloton', tag: 'Head of race' }, { title: 'Group 2' }]);
    expect(groupLabels(sizes(70), 0)).toEqual([{ title: 'Peloton' }]);
    expect(groupLabels(sizes(2, 3), 0).map((l) => l.title)).toEqual(['Head of race', 'Group 2']);
  });

  it('numbers groups once riders have finished', () => {
    expect(groupLabels(sizes(2, 4, 50), 5).map((l) => l.title)).toEqual(['Group 1', 'Group 2', 'Peloton']);
  });

  it('counts riders per team, most first', () => {
    expect(teamCounts(meta, [8, 1, 9, 2, 9]).map((c) => [c.teamId, c.count, c.color])).toEqual([
      ['team-b', 3, '#ffff00'],
      ['team-a', 2, '#d62728'],
    ]);
  });

  it('averages the speed of a group', () => {
    const s = source();
    expect(groupSpeed(s.snapshotAt(500), s.meta, [1, 2])).toBeCloseTo(10);
    expect(groupSpeed(s.snapshotAt(500), s.meta, [])).toBe(0);
  });
});

describe('events list helpers', () => {
  it('tracks group membership through splits, merges and finishes', () => {
    const g = new GroupTracker([1, 2, 8, 9]);
    g.apply({ type: 'group_split', tick: 1, groupId: 1, newGroupId: 2, riderIds: [1], gapS: -3 });
    g.apply({ type: 'group_split', tick: 2, groupId: 1, newGroupId: 3, riderIds: [2], gapS: -3 });
    expect(g.members(1)).toEqual([8, 9]);
    expect(g.isAhead(3, 1)).toBe(true); // split off the front
    expect(g.isAhead(2, 3)).toBe(true); // went clear first, still in front
    g.apply({ type: 'group_split', tick: 2, groupId: 1, newGroupId: 4, riderIds: [9], gapS: 3 });
    expect(g.isAhead(1, 4)).toBe(true);
    g.apply({ type: 'group_merge', tick: 2, groupId: 4, intoGroupId: 1, riderIds: [9] });
    g.apply({ type: 'group_merge', tick: 3, groupId: 3, intoGroupId: 2, riderIds: [2] });
    expect(g.members(2)).toEqual([1, 2]);
    expect(g.size(3)).toBe(0);
    g.apply({ type: 'rider_finished', tick: 4, riderId: 1, position: 1, timeS: 4 });
    expect(g.members(2)).toEqual([2]);
  });

  it('lists riders in prose', () => {
    expect(segmentsText(meta, riderList([1]))).toBe('A. ALPHA');
    expect(segmentsText(meta, riderList([1], 3, true))).toBe('A. ALPHA (Team A)');
    expect(segmentsText(meta, riderList([1, 2]))).toBe('A. ALPHA and B. BETA');
    expect(segmentsText(meta, riderList([1, 2, 8]))).toBe('A. ALPHA, B. BETA and C. GAMMA');
    expect(segmentsText(meta, riderList([1, 2, 8, 9]))).toBe('A. ALPHA, B. BETA, C. GAMMA +1');
  });

  it('writes ordinals', () => {
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 101, 111].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '101st', '111th']);
  });

  it('describes the synthetic race chronologically with names and teams', () => {
    const items = buildEventItems(meta, source().events);
    expect(items.map((i) => i.type)).toEqual(['race_started', 'attack', 'group_split', 'rider_finished', 'rider_finished', 'rider_finished', 'rider_finished', 'race_finished']);
    expect(items.map((i) => segmentsText(meta, i.parts))).toEqual([
      'The race is under way: Test Course',
      'A. ALPHA (Team A) attacks from the 3-rider group',
      'C. GAMMA and D. DELTA lose contact with A. ALPHA and B. BETA (+0:02)',
      'A. ALPHA (Team A) wins in 16:40',
      'B. BETA (Team A) is second, same time',
      'C. GAMMA (Team B) is third at +4:10',
      'D. DELTA (Team B) finishes 4th (+4:10)',
      'Race over: A. ALPHA (Team A) wins Test Course in 16:40',
    ]);
    expect(items[1]!.kmToGo).toBeCloseTo(9.95);
    expect(items[3]!.emphasis).toBe('result');
    expect(new Set(items.map((i) => i.key)).size).toBe(items.length);
  });

  it('folds finishes after the podium by group', () => {
    const fin = (riderId: number, position: number, timeS: number): RaceEvent => ({ type: 'rider_finished', tick: Math.ceil(timeS), riderId, position, timeS });
    const items = buildEventItems(meta, [fin(1, 1, 100), fin(2, 2, 101), fin(8, 3, 102), fin(9, 4, 150), fin(1, 5, 150.5), fin(2, 6, 151.2), fin(8, 7, 200)]);
    expect(items.map((i) => segmentsText(meta, i.parts)).slice(3)).toEqual([
      '3 riders finish 4th to 6th: D. DELTA, A. ALPHA and B. BETA (+0:50)',
      'C. GAMMA (Team B) finishes 7th (+1:40)',
    ]);
    expect(items[3]!.ranking).toEqual([9, 1, 2]);
  });

  it('names who is caught and who bridges across', () => {
    const big = Array.from({ length: 30 }, (_, i) => 100 + i);
    const m = { ...meta, riders: [...meta.riders, ...big.map((id) => ({ ...meta.riders[0]!, id, name: `Rider ${id}` }))] };
    const events: RaceEvent[] = [
      { type: 'group_split', tick: 1, groupId: 1, newGroupId: 2, riderIds: [1, 2], gapS: -4 },
      { type: 'group_split', tick: 2, groupId: 1, newGroupId: 3, riderIds: [8], gapS: -4 },
      { type: 'group_merge', tick: 3, groupId: 3, intoGroupId: 2, riderIds: [8] },
      { type: 'group_merge', tick: 4, groupId: 2, intoGroupId: 1, riderIds: [1, 2, 8] },
    ];
    const lines = buildEventItems(m, events).map((it) => segmentsText(m, it.parts));
    expect(lines).toEqual([
      'A. ALPHA and B. BETA go clear of the peloton (+0:04)',
      'C. GAMMA (Team B) goes clear of the peloton (+0:04)',
      'C. GAMMA (Team B) bridges across to A. ALPHA and B. BETA',
      'A. ALPHA, B. BETA and C. GAMMA are caught by the peloton',
    ]);
  });

  it('describes summits and km marks, and hides drops already told by a split', () => {
    const lines = texts([
      { type: 'group_split', tick: 10, groupId: 1, newGroupId: 2, riderIds: [9], gapS: 3.2 },
      { type: 'rider_dropped', tick: 10, riderId: 9, fromGroupId: 1, distM: 100 },
      { type: 'rider_dropped', tick: 50, riderId: 8, fromGroupId: 1, distM: 500 },
      { type: 'climb_summit', tick: 60, climbName: 'Col Test', distM: 6000, riderIds: [2, 1] },
      { type: 'km_to_go', tick: 70, km: 1, leadGroupId: 1 },
      { type: 'group_split', tick: 80, groupId: 1, newGroupId: 3, riderIds: [1, 2, 8, 9], gapS: -5 },
    ]);
    expect(lines).toEqual([
      'D. DELTA (Team B) loses contact with the 3-rider group (+0:03)',
      'C. GAMMA (Team B) is dropped',
      'Col Test: B. BETA (Team A) first over the top',
      '1 km to go',
      '4 riders go clear (+0:05): A. ALPHA, B. BETA, C. GAMMA +1',
    ]);
  });
});

describe('results table rows', () => {
  const results: Classification = {
    raceId: 'r',
    final: true,
    entries: [
      { riderId: 8, teamId: 'team-b', status: 'finished', position: 3, timeS: 1250.4, gapS: 250 },
      { riderId: 1, teamId: 'team-a', status: 'finished', position: 1, timeS: 1000.2, gapS: 0 },
      { riderId: 2, teamId: 'team-a', status: 'finished', position: 2, timeS: 1000.9, gapS: 0 },
      { riderId: 9, teamId: 'team-b', status: 'abandoned' },
    ],
  };

  it('lists finishers up to t, by position, with group times and gaps', () => {
    const r = resultRows(results, 1100);
    expect(r.rows.map((x) => [x.position, x.time, x.gap])).toEqual([
      [1, '16:40', ''],
      [2, '16:40', 's.t.'],
    ]);
    expect([r.shown, r.total, r.complete]).toEqual([2, 3, false]);
    expect(resultRows(results, 999).rows).toEqual([]);
  });

  it('appends non-finishers once the classification is complete', () => {
    const r = resultRows(results, 5000);
    expect(r.complete).toBe(true);
    expect(r.rows.map((x) => [x.riderId, x.position, x.time, x.gap])).toEqual([
      [1, 1, '16:40', ''],
      [2, 2, '16:40', 's.t.'],
      [8, 3, '20:50', '+4:10'],
      [9, null, 'DNF', ''],
    ]);
  });

  it('handles a missing classification', () => {
    expect(resultRows(null, 100)).toEqual({ rows: [], shown: 0, total: 0, complete: false });
  });
});
