import { describe, expect, it } from 'vitest';
import { pelotonId, pelotonIndex } from './peloton';

const sizes = (...s: number[]) => s.map((size, i) => ({ id: i + 1, size }));

describe('peloton rule', () => {
  it('finds the largest big-enough group', () => {
    expect(pelotonIndex(sizes(3, 50, 4))).toBe(1);
    expect(pelotonId(sizes(3, 50, 4))).toBe(2);
    expect(pelotonIndex(sizes(5, 4))).toBe(-1); // too small
    expect(pelotonIndex(sizes(12, 12, 12, 12, 12))).toBe(-1); // 20 %: fragmented race
    expect(pelotonIndex([])).toBe(-1);
    expect(pelotonId([])).toBeNull();
  });

  it('accepts a 20-rider group out of 70 (Liège finale)', () => {
    expect(pelotonIndex(sizes(5, 20, 15, 15, 15))).toBe(1);
  });
});
