import { ReplayFormatError, decodeCourse, decodeReplay } from './decode';
import type { Classification, CourseDetail, CourseDetailWire, CourseSummary, RaceDetail, RaceSummary, Replay } from './types';

/** API base; the Vite dev server proxies `/api` to the kernel. */
export const API_BASE = '/api/v1';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function fail(res: Response): Promise<never> {
  let message = `${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: string };
    if (body.error) message = `${res.status}: ${body.error}`;
  } catch {
    // not a JSON error body
  }
  throw new ApiError(res.status, message);
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(API_BASE + path, { signal, headers: { Accept: 'application/json' } });
  if (!res.ok) await fail(res);
  return (await res.json()) as T;
}

export const listCourses = (signal?: AbortSignal) => getJson<CourseSummary[]>('/courses', signal);

export const fetchCourse = async (courseId: string, signal?: AbortSignal): Promise<CourseDetail> =>
  decodeCourse(await getJson<CourseDetailWire>(`/courses/${encodeURIComponent(courseId)}`, signal));

export const listRaces = (signal?: AbortSignal) => getJson<RaceSummary[]>('/races', signal);

export const fetchRace = (raceId: string, signal?: AbortSignal) =>
  getJson<RaceDetail>(`/races/${encodeURIComponent(raceId)}`, signal);

export const fetchResults = (raceId: string, signal?: AbortSignal) =>
  getJson<Classification>(`/races/${encodeURIComponent(raceId)}/results`, signal);

// --- Replay ------------------------------------------------------------------------------------------

/** True when the bytes start with the gzip magic number. */
export function isGzip(bytes: Uint8Array): boolean {
  return bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
}

/** Decodes replay bytes: gzip (sniffed from the magic number) or plain JSON. */
export async function parseReplayBytes(buf: ArrayBuffer | Uint8Array): Promise<Replay> {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let text: string;
  if (isGzip(bytes)) {
    try {
      const stream = new Blob([bytes as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new DecompressionStream('gzip'));
      text = await new Response(stream).text();
    } catch {
      throw new ReplayFormatError('replay: corrupt or truncated gzip data');
    }
  } else {
    text = new TextDecoder().decode(bytes);
  }
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new ReplayFormatError('replay: not valid JSON');
  }
  return decodeReplay(json);
}

/**
 * `GET /races/{raceId}/replay`. The browser sends `Accept-Encoding: gzip` and inflates a
 * `Content-Encoding: gzip` body itself; a body that is still gzip (served as a raw file) is inflated here.
 * 409 before the race has finished.
 */
export async function fetchReplay(raceId: string, signal?: AbortSignal): Promise<Replay> {
  const res = await fetch(`${API_BASE}/races/${encodeURIComponent(raceId)}/replay`, { signal });
  if (!res.ok) await fail(res);
  return parseReplayBytes(await res.arrayBuffer());
}

/** Loads a local replay file (`.json` or `.json.gz`, e.g. the runner's `replays/<course>-<seed>.json.gz`). */
export async function loadReplayFile(file: Blob): Promise<Replay> {
  return parseReplayBytes(await file.arrayBuffer());
}
