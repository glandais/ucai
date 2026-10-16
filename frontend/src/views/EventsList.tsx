import { memo, useMemo } from 'react';
import type { RaceEvent } from '../api/types';
import type { RaceMeta } from '../replay/meta';
import { formatRaceTime } from '../replay/format';
import { buildEventItems, segmentsText, type EventItem, type Segment } from './panels/events';
import { RiderName } from './panels/RiderName';
import './EventsList.css';

/** Events slot: events up to the playback time, most recent first (not clickable in v1). */
export interface EventsListProps {
  meta: RaceMeta;
  /** Events with `tick <= t`, chronological (same array instance while unchanged). */
  events: readonly RaceEvent[];
  /** Current race time, s. */
  t: number;
}

/** Riders listed in a summit's passage order. */
const SUMMIT_RANKING = 5;

/** Re-renders only when an event arrives (not on every playback frame: `t` is not displayed). */
export const EventsList = memo(EventsListImpl, (a, b) => a.meta === b.meta && a.events === b.events);

function EventsListImpl({ meta, events }: EventsListProps) {
  // Rebuilt only when a new event arrives (same array instance otherwise).
  const items = useMemo(() => buildEventItems(meta, events).reverse(), [meta, events]);
  return (
    <div className="events-list">
      <div className="events-head">
        <span className="panel-title">Race events</span>
        <span className="muted">{items.length}</span>
      </div>
      {items.length === 0 ? (
        <p className="events-empty muted">Nothing yet.</p>
      ) : (
        <ol className="events-items">
          {items.map((it, i) => (
            <EventRow key={it.key} meta={meta} item={it} latest={i === 0} />
          ))}
        </ol>
      )}
    </div>
  );
}

function EventRow({ meta, item, latest }: { meta: RaceMeta; item: EventItem; latest: boolean }) {
  const cls = ['event', item.emphasis ? `event-${item.emphasis}` : '', latest ? 'event-latest' : ''].filter(Boolean).join(' ');
  return (
    <li className={cls} style={{ borderLeftColor: item.color }} title={segmentsText(meta, item.parts)}>
      <div className="event-meta">
        <span className="event-label" style={{ color: item.color }}>
          {item.label}
        </span>
        <span className="event-time">{formatRaceTime(item.tick)}</span>
        {item.kmToGo != null && item.type !== 'km_to_go' && <span className="event-km">{item.kmToGo.toFixed(1)} km to go</span>}
      </div>
      <div className="event-text">
        <Sentence meta={meta} parts={item.parts} />
      </div>
      {item.type === 'climb_summit' && item.ranking && item.ranking.length > 1 && (
        <ol className="event-ranking">
          {item.ranking.slice(0, SUMMIT_RANKING).map((id, i) => (
            <li key={id}>
              <span className="event-rank">{i + 1}</span>
              <RiderName meta={meta} riderId={id} />
            </li>
          ))}
        </ol>
      )}
    </li>
  );
}

function Sentence({ meta, parts }: { meta: RaceMeta; parts: readonly Segment[] }) {
  return (
    <>
      {parts.map((p, i) => (typeof p === 'string' ? <span key={i}>{p}</span> : <RiderName key={i} meta={meta} riderId={p.riderId} withTeam={p.withTeam} />))}
    </>
  );
}
