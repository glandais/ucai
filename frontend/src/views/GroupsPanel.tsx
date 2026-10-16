import { useState } from 'react';
import type { RaceMeta } from '../replay/meta';
import type { RaceSnapshot } from '../replay/source';
import { formatGap, formatKm, formatSpeed } from '../replay/format';
import { team } from '../replay/meta';
import { BIG_GROUP_SIZE, groupLabels, groupSpeed, teamCounts } from './panels/groups';
import { RiderChip, RiderName } from './panels/RiderName';
import './GroupsPanel.css';

/**
 * Groups slot (TV style): head of race, time gaps, riders of each group with their team color, km to go.
 * Fills its parent box (scrolls inside).
 */
export interface GroupsPanelProps {
  meta: RaceMeta;
  /** `snapshot.groups` are front to back with `gapToHeadS`, `gapToAheadS`, `kmToGo`. */
  snapshot: RaceSnapshot;
}

/** Riders shown at the front of a collapsed big group. */
const COLLAPSED_RIDERS = 6;

export function GroupsPanel({ meta, snapshot }: GroupsPanelProps) {
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set());
  const { groups, finishedRiderIds } = snapshot;
  const labels = groupLabels(groups, finishedRiderIds.length);
  const toggle = (id: number) =>
    setExpanded((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div className="groups-panel">
      <div className="groups-head">
        <span className="groups-title">Race situation</span>
        <span className="groups-head-info">
          {groups.length} {groups.length === 1 ? 'group' : 'groups'}
          {snapshot.kmToGo > 0 && <strong>{formatKm(snapshot.kmToGo * 1000)} to go</strong>}
        </span>
      </div>
      <div className="groups-list">
        {finishedRiderIds.length > 0 && (
          <section className="group-card group-finished">
            <div className="group-row">
              <span className="group-label">Finished</span>
              <span className="group-size">{finishedRiderIds.length}</span>
            </div>
            <div className="group-sub">
              Winner <RiderName meta={meta} riderId={finishedRiderIds[0]!} withTeam />
            </div>
          </section>
        )}
        {groups.map((g, i) => {
          const label = labels[i]!;
          const big = g.size > BIG_GROUP_SIZE;
          const open = !big || expanded.has(g.id);
          const shown = open ? g.riderIds : g.riderIds.slice(0, COLLAPSED_RIDERS);
          const lead = i === 0 && finishedRiderIds.length === 0;
          const gap = formatGap(g.gapToHeadS);
          const ahead = i > 0 ? formatGap(g.gapToAheadS) : '';
          const solo = g.size === 1 ? team(meta, meta.riderById.get(g.riderIds[0]!)?.teamId ?? '') : undefined;
          return (
            <section key={g.id} className={`group-card${lead ? ' group-lead' : ''}`}>
              <div className="group-row">
                <span className="group-label">{label.title}</span>
                {label.tag && <span className="group-tag">{label.tag}</span>}
                <span className="group-size" title={`${g.size} ${g.size === 1 ? 'rider' : 'riders'}`}>
                  {g.size}
                </span>
                <span className="group-spacer" />
                <span className={`group-gap${gap ? '' : ' none'}`}>{lead ? 'LEADER' : gap || 's.t.'}</span>
              </div>
              <div className="group-sub">
                <span>{formatKm(g.kmToGo * 1000)} to go</span>
                <span>{formatSpeed(groupSpeed(snapshot, meta, g.riderIds))}</span>
                {ahead && ahead !== gap && <span>{ahead} on group ahead</span>}
                {solo && <span className="group-solo-team">{solo.name}</span>}
              </div>
              {big && <TeamBar meta={meta} riderIds={g.riderIds} />}
              <div className="group-riders">
                {shown.map((id) => (
                  <RiderChip key={id} meta={meta} riderId={id} />
                ))}
                {big && (
                  <button type="button" className="group-more" onClick={() => toggle(g.id)} aria-expanded={open}>
                    {open ? 'Show less' : `+${g.size - shown.length} more`}
                  </button>
                )}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}

/** Team breakdown of a big group: one colored segment per team, width by rider count. */
function TeamBar({ meta, riderIds }: { meta: RaceMeta; riderIds: readonly number[] }) {
  const counts = teamCounts(meta, riderIds);
  return (
    <div className="group-teams" aria-label="Riders per team">
      {counts.map((c) => (
        <span key={c.teamId} className="group-team" style={{ flexGrow: c.count, background: c.color }} title={`${team(meta, c.teamId)?.name ?? c.teamId}: ${c.count}`} />
      ))}
    </div>
  );
}
