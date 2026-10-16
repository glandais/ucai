import { useMemo, useRef, type KeyboardEvent, type PointerEvent } from 'react';
import type { RaceEvent } from '../api/types';
import { PLAYBACK_SPEEDS, type PlaybackClock, type PlaybackState } from '../replay/clock';
import { EVENT_COLORS, EVENT_LABELS, TIMELINE_EVENT_TYPES, describeEvent } from '../replay/events';
import { formatRaceTime } from '../replay/format';
import type { RaceMeta } from '../replay/meta';
import './PlaybackBar.css';

/** Playback slot: play/pause, speeds, timeline scrub with event marks. */
export interface PlaybackBarProps {
  meta: RaceMeta;
  clock: PlaybackClock;
  /** `usePlayback(clock)`. */
  state: PlaybackState;
  /** Every event of the race (marks are drawn for `TIMELINE_EVENT_TYPES`). */
  events: readonly RaceEvent[];
}

export function PlaybackBar({ meta, clock, state, events }: PlaybackBarProps) {
  const { t, playing, speed, startT, endT } = state;
  const span = Math.max(1, endT - startT);
  const frac = (x: number) => Math.min(1, Math.max(0, (x - startT) / span));
  const trackRef = useRef<HTMLDivElement>(null);
  const marks = useMemo(() => events.filter((e) => TIMELINE_EVENT_TYPES.has(e.type)), [events]);

  const seekFromPointer = (e: PointerEvent<HTMLDivElement>) => {
    const box = trackRef.current?.getBoundingClientRect();
    if (!box || box.width === 0) return;
    clock.seek(startT + ((e.clientX - box.left) / box.width) * span);
  };

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 600 : 60;
    if (e.key === 'ArrowLeft') clock.seek(t - step);
    else if (e.key === 'ArrowRight') clock.seek(t + step);
    else if (e.key === 'Home') clock.seek(startT);
    else if (e.key === 'End') clock.seek(endT);
    else return;
    e.preventDefault();
  };

  return (
    <div className="playback">
      <button type="button" className="playback-play" onClick={clock.toggle} aria-label={playing ? 'Pause' : 'Play'}>
        {playing ? '❚❚' : '▶'}
      </button>
      <div className="playback-speeds" role="group" aria-label="Playback speed">
        {PLAYBACK_SPEEDS.map((s) => (
          <button
            key={s}
            type="button"
            className={s === speed ? 'active' : undefined}
            aria-pressed={s === speed}
            onClick={() => clock.setSpeed(s)}
          >
            ×{s}
          </button>
        ))}
      </div>
      <span className="playback-time">{formatRaceTime(t)}</span>
      <div
        ref={trackRef}
        className="playback-track"
        role="slider"
        tabIndex={0}
        aria-label="Race time"
        aria-valuemin={startT}
        aria-valuemax={endT}
        aria-valuenow={Math.round(t)}
        aria-valuetext={formatRaceTime(t)}
        onKeyDown={onKey}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          seekFromPointer(e);
        }}
        onPointerMove={(e) => {
          if (e.currentTarget.hasPointerCapture(e.pointerId)) seekFromPointer(e);
        }}
      >
        <div className="playback-rail" />
        <div className="playback-fill" style={{ width: `${frac(t) * 100}%` }} />
        {marks.map((e, i) => (
          <div
            key={i}
            className={`playback-mark mark-${e.type}`}
            style={{ left: `${frac(e.tick) * 100}%`, background: EVENT_COLORS[e.type] }}
            title={`${formatRaceTime(e.tick)} ${EVENT_LABELS[e.type]}: ${describeEvent(meta, e)}`}
          />
        ))}
        <div className="playback-thumb" style={{ left: `${frac(t) * 100}%` }} />
      </div>
      <span className="playback-time end">{formatRaceTime(endT)}</span>
    </div>
  );
}
