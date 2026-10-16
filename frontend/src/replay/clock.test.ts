import { describe, expect, it } from 'vitest';
import { PlaybackClock } from './clock';

describe('PlaybackClock', () => {
  it('advances race time by wall time × speed only while playing', () => {
    const c = new PlaybackClock(0, 1000, 10);
    c.advance(1);
    expect(c.getState().t).toBe(0);
    c.play();
    c.advance(1);
    expect(c.getState().t).toBe(10);
    c.setSpeed(60);
    c.advance(2);
    expect(c.getState().t).toBe(130);
    c.pause();
    c.advance(5);
    expect(c.getState().t).toBe(130);
  });

  it('seeks within range and stops at the end', () => {
    const c = new PlaybackClock(100, 200, 1);
    c.seek(50);
    expect(c.getState().t).toBe(100);
    c.seek(190);
    c.play();
    c.setSpeed(30);
    c.advance(1);
    expect(c.getState()).toMatchObject({ t: 200, playing: false });
    c.play(); // from the end: restarts
    expect(c.getState()).toMatchObject({ t: 100, playing: true });
  });

  it('notifies subscribers with a new state object', () => {
    const c = new PlaybackClock(0, 10);
    const seen: number[] = [];
    const before = c.getState();
    const unsubscribe = c.subscribe(() => seen.push(c.getState().t));
    c.seek(3);
    c.toggle();
    unsubscribe();
    c.seek(4);
    expect(seen).toEqual([3, 3]);
    expect(c.getState()).not.toBe(before);
  });
});
