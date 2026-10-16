import type { Archetype, Classification, RiderStatus } from '../../api/types';
import { formatGap, formatRaceTime } from '../../replay/format';

export const ARCHETYPE_LABELS: Record<Archetype, string> = {
  climber: 'Climber',
  puncheur: 'Puncheur',
  sprinter: 'Sprinter',
  rouleur: 'Rouleur',
  all_rounder: 'All-rounder',
  domestique: 'Domestique',
};

const STATUS_LABELS: Record<RiderStatus, string> = {
  racing: 'DNF',
  dropped: 'DNF',
  finished: '',
  abandoned: 'DNF',
};

/** One row of the results table. */
export interface ResultRow {
  riderId: number;
  teamId: string;
  /** Finishing position, null for a rider who did not finish. */
  position: number | null;
  /** Classified time (winner time + group gap), `h:mm:ss`; status label for a non-finisher. */
  time: string;
  /** `+m:ss`, `s.t.` (same time) or `''` for the winner and non-finishers. */
  gap: string;
}

export interface ResultRows {
  rows: ResultRow[];
  /** Finishers listed so far / all finishers in the classification. */
  shown: number;
  total: number;
  /** Every finisher is listed (non-finishers are then appended). */
  complete: boolean;
}

/**
 * Rows of the classification visible at race time `t`: finishers with `timeS <= t` by position; once
 * every finisher is listed, the riders who did not finish follow. Times follow the UCI group rule: the
 * winner's time plus the rider's group gap.
 */
export function resultRows(results: Classification | null, t: number): ResultRows {
  const entries = results?.entries ?? [];
  const finishers = entries
    .filter((e) => e.position != null && e.timeS != null)
    .sort((a, b) => a.position! - b.position!);
  const visible = finishers.filter((e) => e.timeS! <= t);
  const winnerTimeS = finishers[0]?.timeS ?? 0;
  const rows: ResultRow[] = visible.map((e) => {
    const gapS = e.gapS ?? e.timeS! - winnerTimeS;
    return {
      riderId: e.riderId,
      teamId: e.teamId,
      position: e.position!,
      time: formatRaceTime(e.position === 1 ? e.timeS! : winnerTimeS + gapS),
      gap: e.position === 1 ? '' : formatGap(gapS) || 's.t.',
    };
  });
  const complete = visible.length === finishers.length && (results?.final ?? false);
  if (complete) {
    for (const e of entries) {
      if (e.position != null && e.timeS != null) continue;
      rows.push({ riderId: e.riderId, teamId: e.teamId, position: null, time: STATUS_LABELS[e.status] || 'DNF', gap: '' });
    }
  }
  return { rows, shown: visible.length, total: finishers.length, complete };
}
