import type { Climb, CourseDetail, PublicRider, RaceDetail, TeamInfo } from '../api/types';

/**
 * Static race information shared by every view: race, course, teams, riders and lookups.
 * Built once per race by the source (`buildMeta`), never changes during playback.
 */
export interface RaceMeta {
  race: RaceDetail;
  course: CourseDetail;
  teams: readonly TeamInfo[];
  /** In replay order (the order of frame rider arrays). */
  riders: readonly PublicRider[];
  climbs: readonly Climb[];
  teamById: ReadonlyMap<string, TeamInfo>;
  riderById: ReadonlyMap<number, PublicRider>;
  /** Rider id → index in `riders`. */
  riderIndex: ReadonlyMap<number, number>;
}

export const UNKNOWN_COLOR = '#888888';

export function buildMeta(race: RaceDetail, course: CourseDetail, teams: readonly TeamInfo[], riders: readonly PublicRider[]): RaceMeta {
  return {
    race,
    course,
    teams,
    riders,
    climbs: course.climbs,
    teamById: new Map(teams.map((t) => [t.id, t])),
    riderById: new Map(riders.map((r) => [r.id, r])),
    riderIndex: new Map(riders.map((r, i) => [r.id, i])),
  };
}

export function team(meta: RaceMeta, teamId: string): TeamInfo | undefined {
  return meta.teamById.get(teamId);
}

export function rider(meta: RaceMeta, riderId: number): PublicRider | undefined {
  return meta.riderById.get(riderId);
}

export function teamColor(meta: RaceMeta, teamId: string): string {
  return meta.teamById.get(teamId)?.color ?? UNKNOWN_COLOR;
}

export function riderTeam(meta: RaceMeta, riderId: number): TeamInfo | undefined {
  const r = meta.riderById.get(riderId);
  return r ? meta.teamById.get(r.teamId) : undefined;
}

export function riderColor(meta: RaceMeta, riderId: number): string {
  return riderTeam(meta, riderId)?.color ?? UNKNOWN_COLOR;
}

/** Full name, or `#id` for an unknown rider. */
export function riderName(meta: RaceMeta, riderId: number): string {
  return meta.riderById.get(riderId)?.name ?? `#${riderId}`;
}

/** TV-style short name: `G. VELOVIC`. */
export function riderShortName(meta: RaceMeta, riderId: number): string {
  const name = meta.riderById.get(riderId)?.name;
  if (!name) return `#${riderId}`;
  const parts = name.trim().split(/\s+/);
  if (parts.length < 2) return name.toUpperCase();
  return `${parts[0]![0]}. ${parts.slice(1).join(' ').toUpperCase()}`;
}

/** Black or white, whichever reads better on the CSS hex color `bg`. */
export function textColorOn(bg: string): '#000000' | '#ffffff' {
  const m = /^#?([0-9a-f]{6})$/i.exec(bg);
  if (!m) return '#ffffff';
  const n = parseInt(m[1]!, 16);
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  const l = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
  return l > 0.179 ? '#000000' : '#ffffff';
}
