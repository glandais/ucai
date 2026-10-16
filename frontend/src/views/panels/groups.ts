import type { GroupState, RaceSnapshot } from '../../replay/source';
import type { RaceMeta } from '../../replay/meta';
import { teamColor } from '../../replay/meta';
import { pelotonIndex } from '../../replay/peloton';

/** Groups with more riders than this are shown collapsed (team breakdown + toggle). */
export const BIG_GROUP_SIZE = 12;

/** TV caption of a group on the road. */
export interface GroupLabel {
  /** "Head of race", "Chasers", "Chase 2", "Peloton", "Group 4". */
  title: string;
  /** Secondary tag, e.g. "Head of race" on a peloton that leads the race. */
  tag?: string;
}

/**
 * Captions of the groups on the road, front to back. While nobody has finished the first group is the
 * head of the race; groups between it and the peloton are the chasers; groups behind the peloton (and
 * every group once riders have finished) are numbered by road order.
 */
export function groupLabels(groups: readonly Pick<GroupState, 'size'>[], finishedCount: number): GroupLabel[] {
  const p = pelotonIndex(groups);
  const racing = finishedCount === 0;
  return groups.map((_, i): GroupLabel => {
    if (i === p) return racing && i === 0 && groups.length > 1 ? { title: 'Peloton', tag: 'Head of race' } : { title: 'Peloton' };
    if (racing && i === 0) return { title: 'Head of race' };
    if (racing && i < p) return { title: p === 2 ? 'Chasers' : `Chase ${i}` };
    return { title: `Group ${i + 1}` };
  });
}

/** Riders of one team in a group, for the collapsed team breakdown. */
export interface TeamCount {
  teamId: string;
  color: string;
  count: number;
}

/** Team breakdown of a group: one entry per team, most riders first (ties keep first-seen order). */
export function teamCounts(meta: RaceMeta, riderIds: readonly number[]): TeamCount[] {
  const counts = new Map<string, number>();
  for (const id of riderIds) {
    const teamId = meta.riderById.get(id)?.teamId ?? '?';
    counts.set(teamId, (counts.get(teamId) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([teamId, count]) => ({ teamId, color: teamColor(meta, teamId), count }))
    .sort((a, b) => b.count - a.count);
}

/** Mean speed of the riders of a group, m/s (0 for an empty group). */
export function groupSpeed(snapshot: RaceSnapshot, meta: RaceMeta, riderIds: readonly number[]): number {
  let sum = 0;
  let n = 0;
  for (const id of riderIds) {
    const i = meta.riderIndex.get(id);
    const r = i == null ? undefined : snapshot.riders[i];
    if (r) {
      sum += r.speed;
      n++;
    }
  }
  return n ? sum / n : 0;
}
