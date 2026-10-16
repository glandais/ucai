import { riderColor, riderName, riderShortName, riderTeam, textColorOn, type RaceMeta } from '../../replay/meta';
import './panels.css';

/** Rider name with a team-color bar (inline in sentences); the tooltip gives full name and team. */
export function RiderName({ meta, riderId, withTeam = false }: { meta: RaceMeta; riderId: number; withTeam?: boolean }) {
  const team = riderTeam(meta, riderId);
  return (
    <>
      <span className="rider-name" style={{ borderColor: riderColor(meta, riderId) }} title={`${riderName(meta, riderId)}${team ? ` — ${team.name}` : ''}`}>
        {riderShortName(meta, riderId)}
      </span>
      {withTeam && team && <span className="rider-name-team"> ({team.name})</span>}
    </>
  );
}

/** Team-colored chip with the rider's short name (groups panel). */
export function RiderChip({ meta, riderId }: { meta: RaceMeta; riderId: number }) {
  const color = riderColor(meta, riderId);
  const team = riderTeam(meta, riderId);
  return (
    <span className="rider-chip" style={{ background: color, color: textColorOn(color) }} title={`${riderName(meta, riderId)}${team ? ` — ${team.name}` : ''}`}>
      {riderShortName(meta, riderId)}
    </span>
  );
}
