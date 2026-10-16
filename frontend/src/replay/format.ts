/** Shared display formatting (English, TV style). */

const pad2 = (n: number) => String(n).padStart(2, '0');

/** Race time `h:mm:ss` (or `m:ss` under an hour). */
export function formatRaceTime(s: number): string {
  const total = Math.max(0, Math.floor(s));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(sec)}` : `${m}:${pad2(sec)}`;
}

/** Time gap `+m:ss` / `+h:mm:ss`; `''` for no gap (under half a second). */
export function formatGap(s: number): string {
  if (!Number.isFinite(s) || s < 0.5) return '';
  return `+${formatRaceTime(Math.round(s))}`;
}

/** Kilometers with one decimal: `12.3 km`. */
export function formatKm(m: number, digits = 1): string {
  return `${(m / 1000).toFixed(digits)} km`;
}

/** Speed in km/h from m/s: `42.1 km/h`. */
export function formatSpeed(ms: number): string {
  return `${(ms * 3.6).toFixed(1)} km/h`;
}

/** Grade fraction as percent: `7.1 %`. */
export function formatGrade(g: number): string {
  return `${(g * 100).toFixed(1)} %`;
}
