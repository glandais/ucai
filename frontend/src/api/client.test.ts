import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { syntheticReplayWire } from '../replay/fixtures';
import { isGzip, parseReplayBytes } from './client';
import { ReplayFormatError, decodeFrameRider, decodeReplay, decodeSnapshotRider } from './decode';

describe('replay decoding', () => {
  const json = JSON.stringify(syntheticReplayWire());

  it('parses plain JSON bytes', async () => {
    const r = await parseReplayBytes(new TextEncoder().encode(json));
    expect(r.course.points[1]).toEqual({ lat: 45.01, lon: 7, eleM: 110, distM: 1000 });
    expect(r.riders).toHaveLength(4);
  });

  it('parses gzip bytes', async () => {
    const gz = new Uint8Array(gzipSync(json));
    expect(isGzip(gz)).toBe(true);
    const r = await parseReplayBytes(gz.buffer);
    expect(r.frames).toHaveLength(126);
    expect(r.events[0]!.type).toBe('race_started');
  });

  it('reports truncated gzip and invalid JSON as format errors', async () => {
    const gz = new Uint8Array(gzipSync(json));
    await expect(parseReplayBytes(gz.slice(0, Math.floor(gz.length / 2)))).rejects.toThrow('replay: corrupt or truncated gzip data');
    await expect(parseReplayBytes(new TextEncoder().encode('{"version":'))).rejects.toThrow('replay: not valid JSON');
  });

  it('decodes compact arrays, ignoring extra elements', () => {
    expect(decodeFrameRider([1, 2, 3, 4, 99])).toEqual({ distM: 1, speed: 2, groupId: 3, rankInGroup: 4 });
    expect(decodeSnapshotRider([7, 1, 2, 3, 4])).toEqual({ riderId: 7, distM: 1, speed: 2, groupId: 3, rankInGroup: 4 });
  });

  it('maps the course on its track, or on its points without one', () => {
    const plain = decodeReplay(syntheticReplayWire());
    expect(plain.course.track).toBe(plain.course.points);
    const wire = syntheticReplayWire();
    wire.course.track = [[45, 7, 100, 0], [45.004, 7.001, 104, 420], [45.1, 7, 200, 10_000]];
    const course = decodeReplay(wire).course;
    expect(course.points).toHaveLength(11);
    expect(course.track[1]).toEqual({ lat: 45.004, lon: 7.001, eleM: 104, distM: 420 });
  });

  it('rejects other versions and inconsistent frames', () => {
    expect(() => decodeReplay({ ...syntheticReplayWire(), version: 2 })).toThrow(ReplayFormatError);
    const bad = syntheticReplayWire();
    bad.frames[3]!.r.pop();
    expect(() => decodeReplay(bad)).toThrow(/frame t=30/);
  });
});
