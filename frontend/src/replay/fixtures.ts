import type { FrameRiderTuple, RaceEvent, ReplayFrame, ReplayWire } from '../api/types';

/**
 * Small synthetic replay for unit tests (any view may use it).
 *
 * Straight 10 km course along a meridian (lat = 45 + distM × 1e-5, lon = 7), points every 1 km.
 * Team A riders 1, 2 and team B riders 8, 9 start together in group 1; at t = 10 s A goes clear at
 * 10 m/s (group 1) while B rides at 8 m/s (group 2). A finishes at t = 1000 s (positions 1, 2),
 * B at t = 1250 s (positions 3, 4). Frames every 10 s from 0 to 1250 s.
 */
export function syntheticReplayWire(): ReplayWire {
  const courseM = 10_000;
  const points: [number, number, number, number][] = [];
  for (let d = 0; d <= courseM; d += 1000) points.push([45 + d * 1e-5, 7, 100 + d / 100, d]);
  const teams = [
    { id: 'team-a', name: 'Team A', color: '#d62728' },
    { id: 'team-b', name: 'Team B', color: '#ffff00' },
  ];
  const ratings = { climbing: 3, punch: 3, sprint: 3, endurance: 3, descending: 3 };
  const riders = [
    { id: 1, name: 'Anna Alpha', teamId: 'team-a', archetype: 'climber' as const, ratings },
    { id: 2, name: 'Bea Beta', teamId: 'team-a', archetype: 'rouleur' as const, ratings },
    { id: 8, name: 'Cleo Gamma', teamId: 'team-b', archetype: 'sprinter' as const, ratings },
    { id: 9, name: 'Dora Delta', teamId: 'team-b', archetype: 'puncheur' as const, ratings },
  ];
  const frames: ReplayFrame[] = [];
  for (let t = 0; t <= 1250; t += 10) {
    const a = Math.min(courseM, 10 * t);
    const b = Math.min(courseM, 8 * t);
    const aDone = a >= courseM;
    const bDone = b >= courseM;
    const r: FrameRiderTuple[] =
      t === 0
        ? [
            [0, 0, 1, 1],
            [0, 0, 1, 2],
            [0, 0, 1, 3],
            [0, 0, 1, 4],
          ]
        : [
            aDone ? [a, 0, 0, 1] : [a, 10, 1, 1],
            aDone ? [a, 0, 0, 2] : [a, 10, 1, 2],
            bDone ? [b, 0, 0, 3] : [b, 8, 2, 1],
            bDone ? [b, 0, 0, 4] : [b, 8, 2, 2],
          ];
    frames.push({ t, r });
  }
  const events: RaceEvent[] = [
    { type: 'race_finished', tick: 1250, winnerRiderId: 1, timeS: 1000 },
    { type: 'race_started', tick: 0 },
    { type: 'attack', tick: 5, riderId: 1, fromGroupId: 1, distM: 50 },
    { type: 'group_split', tick: 10, groupId: 1, newGroupId: 2, riderIds: [8, 9], gapS: 2 },
    { type: 'rider_finished', tick: 1000, riderId: 1, position: 1, timeS: 1000 },
    { type: 'rider_finished', tick: 1000, riderId: 2, position: 2, timeS: 1000 },
    { type: 'rider_finished', tick: 1250, riderId: 8, position: 3, timeS: 1250 },
    { type: 'rider_finished', tick: 1250, riderId: 9, position: 4, timeS: 1250 },
  ];
  const race = {
    id: 'race-test',
    courseId: 'test',
    courseName: 'Test Course',
    state: 'finished' as const,
    clock: { mode: 'lockstep' as const },
    teams,
    riders,
    tick: 1250,
  };
  return {
    version: 1,
    race,
    course: { id: 'test', name: 'Test Course', distanceM: courseM, elevationGainM: 100, points, climbs: [] },
    teams,
    riders,
    frames,
    events,
    results: {
      raceId: 'race-test',
      final: true,
      entries: [
        { riderId: 1, teamId: 'team-a', status: 'finished', position: 1, timeS: 1000, gapS: 0 },
        { riderId: 2, teamId: 'team-a', status: 'finished', position: 2, timeS: 1000, gapS: 0 },
        { riderId: 8, teamId: 'team-b', status: 'finished', position: 3, timeS: 1250, gapS: 250 },
        { riderId: 9, teamId: 'team-b', status: 'finished', position: 4, timeS: 1250, gapS: 250 },
      ],
    },
  };
}
