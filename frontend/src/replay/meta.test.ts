import { describe, expect, it } from 'vitest';
import { decodeReplay } from '../api/decode';
import { syntheticReplayWire } from './fixtures';
import { ReplayModel } from './model';
import { UNKNOWN_COLOR, riderColor, riderName, riderShortName, teamColor, textColorOn } from './meta';

describe('meta lookups', () => {
  const { meta } = new ReplayModel(decodeReplay(syntheticReplayWire()));

  it('finds riders and team colors', () => {
    expect(riderName(meta, 8)).toBe('Cleo Gamma');
    expect(riderShortName(meta, 8)).toBe('C. GAMMA');
    expect(riderName(meta, 42)).toBe('#42');
    expect(riderColor(meta, 2)).toBe('#d62728');
    expect(teamColor(meta, 'nope')).toBe(UNKNOWN_COLOR);
    expect(meta.riderIndex.get(9)).toBe(3);
  });

  it('picks a readable text color', () => {
    expect(textColorOn('#ffff00')).toBe('#000000');
    expect(textColorOn('#393b79')).toBe('#ffffff');
  });
});
