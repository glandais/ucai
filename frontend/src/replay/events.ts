import type { RaceEvent, RaceEventType } from '../api/types';
import { formatGap, formatKm, formatRaceTime } from './format';
import { riderShortName, type RaceMeta } from './meta';

/** Short English label per event type. */
export const EVENT_LABELS: Record<RaceEventType, string> = {
  race_started: 'Start',
  attack: 'Attack',
  group_split: 'Split',
  group_merge: 'Caught',
  rider_dropped: 'Dropped',
  climb_summit: 'Summit',
  km_to_go: 'Km to go',
  rider_finished: 'Finish',
  race_finished: 'Race finished',
};

/** Accent color per event type (timeline marks, event badges). */
export const EVENT_COLORS: Record<RaceEventType, string> = {
  race_started: '#9aa4b2',
  attack: '#ff4d4f',
  group_split: '#ffa940',
  group_merge: '#69c0ff',
  rider_dropped: '#8c8c8c',
  climb_summit: '#b37feb',
  km_to_go: '#fadb14',
  rider_finished: '#95de64',
  race_finished: '#ffffff',
};

/** One-line English description of an event. */
export function describeEvent(meta: RaceMeta, e: RaceEvent): string {
  const names = (ids: readonly number[], max = 3) => {
    const shown = ids.slice(0, max).map((id) => riderShortName(meta, id));
    return ids.length > max ? `${shown.join(', ')} +${ids.length - max}` : shown.join(', ');
  };
  const toGo = (distM: number) => formatKm(Math.max(0, meta.course.distanceM - distM));
  switch (e.type) {
    case 'race_started':
      return `The race is under way: ${meta.course.name}`;
    case 'attack':
      return `Attack by ${riderShortName(meta, e.riderId)}, ${toGo(e.distM)} to go`;
    case 'group_split': {
      const gap = formatGap(Math.abs(e.gapS));
      return `${names(e.riderIds)} ${e.gapS < 0 ? 'go clear' : 'lose contact'}${gap ? ` (${gap})` : ''}`;
    }
    case 'group_merge':
      return `${names(e.riderIds)} caught`;
    case 'rider_dropped':
      return `${riderShortName(meta, e.riderId)} is dropped`;
    case 'climb_summit':
      return `${e.climbName}: first over the top ${names(e.riderIds)}`;
    case 'km_to_go':
      return `${e.km} km to go`;
    case 'rider_finished':
      return `${e.position}. ${riderShortName(meta, e.riderId)} ${formatRaceTime(e.timeS)}`;
    case 'race_finished':
      return `${riderShortName(meta, e.winnerRiderId)} wins in ${formatRaceTime(e.timeS)}`;
  }
}

/** Event types worth a mark on the playback timeline. */
export const TIMELINE_EVENT_TYPES: ReadonlySet<RaceEventType> = new Set<RaceEventType>([
  'attack',
  'group_split',
  'climb_summit',
  'km_to_go',
  'race_finished',
]);
