import { useEffect, useMemo, useRef, useState } from 'react';
import { usePlayback, usePlaybackClock } from '../replay/clock';
import { formatKm, formatRaceTime } from '../replay/format';
import { useSourceVersion } from '../replay/live';
import type { RaceSource } from '../replay/source';
import { EventsList } from '../views/EventsList';
import { GroupsPanel } from '../views/GroupsPanel';
import { MapView } from '../views/MapView';
import { PlaybackBar } from '../views/PlaybackBar';
import { ProfileView } from '../views/ProfileView';
import { ResultsTable } from '../views/ResultsTable';

export interface ReplayViewProps {
  source: RaceSource;
  onBack: () => void;
}

/** Fixed TV layout; one slot per view. Mount with `key` = race so each race gets a fresh clock. */
export function ReplayView({ source, onBack }: ReplayViewProps) {
  const { meta } = source;
  const clock = usePlaybackClock(source.startT, source.endT);
  const state = usePlayback(clock);
  // Bumped on each source notification (live data); a replay never notifies.
  const dataVersion = useSourceVersion(source, clock);
  const snapshot = useMemo(() => source.snapshotAt(state.t), [source, state.t, dataVersion]);
  const events = source.eventsUntil(state.t);
  const [showResults, setShowResults] = useState(false);

  // Open the results when the race reaches its end during playback.
  const wasFinished = useRef(snapshot.raceFinished);
  useEffect(() => {
    if (snapshot.raceFinished && !wasFinished.current) setShowResults(true);
    wasFinished.current = snapshot.raceFinished;
  }, [snapshot.raceFinished]);

  // Space bar toggles playback, even with a button focused (its click is suppressed); not while typing.
  useEffect(() => {
    const typing = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      return !!el && (['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName) || el.isContentEditable);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || typing(e)) return;
      e.preventDefault();
      if (!e.repeat) clock.toggle();
    };
    // Buttons activate on Space keyup: cancel it so the focused button is not clicked again.
    const onKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !typing(e)) e.preventDefault();
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, [clock]);

  return (
    <div className="replay">
      <header className="replay-header">
        <button type="button" onClick={onBack}>
          ← Races
        </button>
        <h1>{meta.course.name}</h1>
        {meta.course.startName && meta.course.finishName && (
          <span className="muted">
            {meta.course.startName} → {meta.course.finishName}
          </span>
        )}
        <span className="spacer" />
        <span className="badge">{formatRaceTime(snapshot.t)}</span>
        <span className="badge accent">{snapshot.kmToGo > 0 ? `${formatKm(snapshot.kmToGo * 1000)} to go` : 'Finish'}</span>
        <button type="button" onClick={() => setShowResults((s) => !s)}>
          Results
        </button>
      </header>
      <main className="slot slot-map">
        <MapView meta={meta} snapshot={snapshot} />
        {showResults && (
          <div className="slot-results">
            <ResultsTable meta={meta} results={source.results} t={snapshot.t} onClose={() => setShowResults(false)} />
          </div>
        )}
      </main>
      <aside className="slot slot-groups">
        <GroupsPanel meta={meta} snapshot={snapshot} />
      </aside>
      <aside className="slot slot-events">
        <EventsList meta={meta} events={events} t={snapshot.t} />
      </aside>
      <section className="slot slot-profile">
        <ProfileView meta={meta} snapshot={snapshot} />
      </section>
      <footer className="slot slot-playback">
        <PlaybackBar meta={meta} clock={clock} state={state} events={source.events} />
      </footer>
    </div>
  );
}
