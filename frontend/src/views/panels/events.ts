import type { RaceEvent, RaceEventType } from '../../api/types';
import { EVENT_COLORS, EVENT_LABELS } from '../../replay/events';
import { formatGap, formatRaceTime } from '../../replay/format';
import { riderShortName, riderTeam, type RaceMeta } from '../../replay/meta';

/** A piece of an event sentence: plain text, or a rider (rendered as a team-colored name). */
export type Segment = string | { riderId: number; withTeam?: boolean };

/** One line of the events list (several finishes in the same group are folded into one item). */
export interface EventItem {
  key: string;
  tick: number;
  type: RaceEventType;
  label: string;
  color: string;
  /** The sentence. */
  parts: Segment[];
  /** Km to go where the event happened, when known. */
  kmToGo?: number;
  /** Passage order at a summit (first riders only). */
  ranking?: number[];
  /** Milestones and the race result stand out. */
  emphasis?: 'milestone' | 'result';
}

/** A group of at least this size is "the peloton" in sentences. */
const PELOTON_SIZE = 20;
/** Finishes from this position on are folded by group (same group time). */
const FOLD_FINISHES_FROM = 4;
/** Riders named in a sentence before "+N". */
const MAX_NAMES = 3;

/**
 * Group membership and road order rebuilt from the events: everyone starts in group 1; a split moves
 * riders to a new group just ahead of (gapS < 0) or just behind its parent; a merge moves a whole group
 * into another (the kernel keeps the bigger group, ahead or behind); finishes remove riders. Lets
 * sentences name the group a split comes from, and tell a catch from a bridge.
 */
export class GroupTracker {
  private readonly groups = new Map<number, Set<number>>();
  private readonly riderGroup = new Map<number, number>();
  /** Group ids on the road, front to back. */
  private readonly order: number[] = [1];

  constructor(riderIds: readonly number[]) {
    this.groups.set(1, new Set(riderIds));
    for (const id of riderIds) this.riderGroup.set(id, 1);
  }

  /** True when `a` rides ahead of `b` (unknown groups: false). */
  isAhead(a: number, b: number): boolean {
    const ia = this.order.indexOf(a);
    const ib = this.order.indexOf(b);
    return ia >= 0 && ib >= 0 && ia < ib;
  }

  members(groupId: number): number[] {
    return [...(this.groups.get(groupId) ?? [])];
  }

  size(groupId: number): number {
    return this.groups.get(groupId)?.size ?? 0;
  }

  apply(e: RaceEvent): void {
    switch (e.type) {
      case 'group_split': {
        this.move(e.riderIds, e.newGroupId);
        this.remove(e.newGroupId);
        const at = this.order.indexOf(e.groupId);
        if (at < 0) this.order.push(e.newGroupId);
        else this.order.splice(e.gapS < 0 ? at : at + 1, 0, e.newGroupId);
        break;
      }
      case 'group_merge':
        this.move(e.riderIds, e.intoGroupId);
        this.move(this.members(e.groupId), e.intoGroupId);
        this.groups.delete(e.groupId);
        this.remove(e.groupId);
        if (!this.order.includes(e.intoGroupId)) this.order.push(e.intoGroupId);
        break;
      case 'rider_finished': {
        const g = this.riderGroup.get(e.riderId);
        if (g != null) this.groups.get(g)?.delete(e.riderId);
        this.riderGroup.delete(e.riderId);
        break;
      }
      default:
        break;
    }
  }

  private remove(groupId: number): void {
    const at = this.order.indexOf(groupId);
    if (at >= 0) this.order.splice(at, 1);
  }

  private move(riderIds: readonly number[], to: number): void {
    let target = this.groups.get(to);
    if (!target) this.groups.set(to, (target = new Set()));
    for (const id of riderIds) {
      const from = this.riderGroup.get(id);
      if (from != null && from !== to) this.groups.get(from)?.delete(id);
      target.add(id);
      this.riderGroup.set(id, to);
    }
  }
}

/** `a, b and c` / `a, b, c +4` as segments. */
export function riderList(ids: readonly number[], max = MAX_NAMES, withTeam = false): Segment[] {
  const out: Segment[] = [];
  const shown = ids.slice(0, max);
  shown.forEach((id, i) => {
    if (i > 0) out.push(i === shown.length - 1 && ids.length <= max ? ' and ' : ', ');
    out.push({ riderId: id, withTeam: withTeam && ids.length === 1 });
  });
  if (ids.length > max) out.push(` +${ids.length - max}`);
  return out;
}

/** "the peloton" / "the 7-rider group" / "A. NAME" for a group of the given members. */
function groupPhrase(members: readonly number[]): Segment[] {
  if (members.length >= PELOTON_SIZE) return ['the peloton'];
  if (members.length === 1) return [{ riderId: members[0]! }];
  if (members.length <= 2) return riderList(members);
  return [`the ${members.length}-rider group`];
}

const kmToGoAt = (meta: RaceMeta, distM: number) => Math.max(0, meta.course.distanceM - distM) / 1000;
const verb = (n: number, one: string, many: string) => (n === 1 ? one : many);

/**
 * Event list items, chronological: readable sentences with rider and team names. `rider_dropped` events
 * repeat the `group_split` that accompanies them and are only kept when no split names that rider;
 * finishes after the podium are folded by group time.
 */
export function buildEventItems(meta: RaceMeta, events: readonly RaceEvent[]): EventItem[] {
  const tracker = new GroupTracker(meta.riders.map((r) => r.id));
  const splitRiders = new Map<number, number[]>(); // riderId -> ticks of splits naming them
  for (const e of events) {
    if (e.type !== 'group_split') continue;
    for (const id of e.riderIds) splitRiders.set(id, [...(splitRiders.get(id) ?? []), e.tick]);
  }

  const items: EventItem[] = [];
  const base = (e: RaceEvent, i: number) => ({
    key: `${i}`,
    tick: e.tick,
    type: e.type,
    label: EVENT_LABELS[e.type],
    color: EVENT_COLORS[e.type],
  });
  let winnerTimeS: number | null = null;
  let fold: Fold | null = null;

  // `race_finished` closes the race: keep it after the finishes of the same tick.
  const ordered = events.map((e, i) => ({ e, i }));
  ordered.sort((a, b) => a.e.tick - b.e.tick || Number(a.e.type === 'race_finished') - Number(b.e.type === 'race_finished') || a.i - b.i);

  for (const { e, i } of ordered) {
    switch (e.type) {
      case 'race_started':
        items.push({ ...base(e, i), parts: [`The race is under way: ${meta.course.name}`] });
        break;
      case 'attack': {
        const from = tracker.members(e.fromGroupId);
        const parts: Segment[] = [{ riderId: e.riderId, withTeam: true }, ' attacks'];
        const rest = from.filter((id) => id !== e.riderId);
        if (rest.length > 0) parts.push(' from ', ...groupPhrase(rest));
        items.push({ ...base(e, i), parts, kmToGo: kmToGoAt(meta, e.distM) });
        break;
      }
      case 'group_split': {
        const from = tracker.members(e.groupId).filter((id) => !e.riderIds.includes(id));
        const n = e.riderIds.length;
        // A big group is counted first, its names listed at the end.
        const many = n > MAX_NAMES;
        const gap = formatGap(Math.abs(e.gapS));
        const parts: Segment[] = many ? [`${n} riders`] : riderList(e.riderIds, MAX_NAMES, true);
        parts.push(e.gapS < 0 ? verb(n, ' goes clear', ' go clear') : verb(n, ' loses contact', ' lose contact'));
        if (from.length > 0) parts.push(e.gapS < 0 ? ' of ' : ' with ', ...groupPhrase(from));
        if (gap) parts.push(` (${gap})`);
        if (many) parts.push(': ', ...riderList(e.riderIds));
        items.push({ ...base(e, i), parts });
        break;
      }
      case 'group_merge': {
        // `riderIds` (group `groupId`) join the bigger group `intoGroupId`: caught if they were ahead
        // of it, bridging across if they were behind.
        const gone = e.riderIds;
        const into = tracker.members(e.intoGroupId).filter((id) => !gone.includes(id));
        let parts: Segment[];
        let label = EVENT_LABELS.group_merge;
        if (into.length === 0) parts = [...riderList(gone), ' regroup'];
        else if (tracker.isAhead(e.groupId, e.intoGroupId))
          parts = [...riderList(gone, MAX_NAMES, true), verb(gone.length, ' is', ' are'), ' caught by ', ...groupPhrase(into)];
        else {
          label = 'Bridge';
          parts = [...(gone.length >= PELOTON_SIZE ? ['The peloton'] : riderList(gone, MAX_NAMES, true)), verb(gone.length, ' bridges', ' bridge'), ' across to ', ...groupPhrase(into)];
        }
        items.push({ ...base(e, i), label, parts });
        break;
      }
      case 'rider_dropped': {
        const near = splitRiders.get(e.riderId)?.some((t) => Math.abs(t - e.tick) <= 10);
        if (!near) items.push({ ...base(e, i), parts: [{ riderId: e.riderId, withTeam: true }, ' is dropped'], kmToGo: kmToGoAt(meta, e.distM) });
        break;
      }
      case 'climb_summit':
        items.push({
          ...base(e, i),
          parts: [`${e.climbName}: `, { riderId: e.riderIds[0] ?? -1, withTeam: true }, ' first over the top'],
          kmToGo: kmToGoAt(meta, e.distM),
          ranking: e.riderIds,
        });
        break;
      case 'km_to_go':
        items.push({ ...base(e, i), parts: [`${e.km} km to go`], kmToGo: e.km, emphasis: 'milestone' });
        break;
      case 'rider_finished': {
        if (e.position === 1) winnerTimeS = e.timeS;
        const gapS = winnerTimeS == null ? 0 : e.timeS - winnerTimeS;
        if (e.position < FOLD_FINISHES_FROM) {
          fold = null;
          const ord = ['', 'wins', 'is second', 'is third'][e.position] ?? 'finishes';
          const parts: Segment[] = [{ riderId: e.riderId, withTeam: true }, ` ${ord}`];
          const gap = formatGap(gapS);
          parts.push(e.position === 1 ? ` in ${formatRaceTime(e.timeS)}` : gap ? ` at ${gap}` : ', same time');
          items.push({ ...base(e, i), parts, emphasis: e.position === 1 ? 'result' : undefined });
          break;
        }
        // Fold into the previous line while riders cross the line within 1 s of each other (same group).
        if (fold && fold.index === items.length - 1 && Math.abs(e.timeS - fold.timeS) <= 1) {
          fold.riderIds.push(e.riderId);
          fold.timeS = e.timeS;
        } else {
          fold = { index: items.length, timeS: e.timeS, firstPos: e.position, riderIds: [e.riderId], gapS };
          items.push({ ...base(e, i), parts: [] });
        }
        items[fold.index] = { ...items[fold.index]!, parts: finishParts(fold, e.position), ranking: [...fold.riderIds] };
        break;
      }
      case 'race_finished':
        items.push({
          ...base(e, i),
          parts: ['Race over: ', { riderId: e.winnerRiderId, withTeam: true }, ` wins ${meta.course.name} in ${formatRaceTime(e.timeS)}`],
          emphasis: 'result',
        });
        break;
    }
    tracker.apply(e);
  }
  return items;
}

interface Fold {
  /** Index of the folded line in the items. */
  index: number;
  timeS: number;
  firstPos: number;
  riderIds: number[];
  gapS: number;
}

function finishParts(fold: Fold, lastPos: number): Segment[] {
  const gap = formatGap(fold.gapS);
  const ids = fold.riderIds;
  const parts: Segment[] =
    ids.length === 1
      ? [{ riderId: ids[0]!, withTeam: true }, ` finishes ${ordinal(fold.firstPos)}`]
      : [`${ids.length} riders finish ${ordinal(fold.firstPos)} to ${ordinal(lastPos)}: `, ...riderList(ids)];
  parts.push(gap ? ` (${gap})` : ' (same time)');
  return parts;
}

/** 1st, 2nd, 3rd, 4th, 11th, 21st… */
export function ordinal(n: number): string {
  const s = n % 100 >= 11 && n % 100 <= 13 ? 'th' : (['th', 'st', 'nd', 'rd'][n % 10] ?? 'th');
  return `${n}${s}`;
}

/** Plain text of a sentence (tests, titles): riders as `A. NAME`, with ` (Team)` when `withTeam`. */
export function segmentsText(meta: RaceMeta, parts: readonly Segment[]): string {
  return parts
    .map((p) => {
      if (typeof p === 'string') return p;
      const name = riderShortName(meta, p.riderId);
      const team = p.withTeam ? riderTeam(meta, p.riderId)?.name : undefined;
      return team ? `${name} (${team})` : name;
    })
    .join('');
}
