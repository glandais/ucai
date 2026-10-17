// Measures the replay loading and playback numbers of frontend/README.md "Measures", on the seed-42 races.
//
// Same setup as scripts/screenshots.mjs: from the repository root
//   ./gradlew :runner:run --args="--courses milano-sanremo,liege-bastogne-liege,il-lombardia --seed 42 --out replays/ --serve"
// and, in frontend/, `npm run dev` (Vite on :5173). Then `npm run measure`. The loading steps (fetch, decode,
// `ReplayModel`, `snapshotAt`) import the app's own modules from the dev server; for the "open" and playback
// rows of a production build, also run `npm run build && npx vite preview` (:4173, same /api proxy) and set
// PROD (default http://localhost:4173/; PROD=none skips them).
//
// Env: BASE (default http://localhost:5173/), PROD, CHROME (default /usr/bin/google-chrome), REPLAYS (default
// ../replays, for the gzip sizes on disk).
import { chromium } from 'playwright-core';
import { statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE ?? 'http://localhost:5173/';
const PROD = process.env.PROD ?? 'http://localhost:4173/';
const CHROME = process.env.CHROME ?? '/usr/bin/google-chrome';
const REPLAYS = resolve(process.env.REPLAYS ?? resolve(here, '../../replays'));
const COURSES = ['milano-sanremo', 'liege-bastogne-liege', 'il-lombardia'];
const RUNS = 3;

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  // A real GPU: with SwiftShader (the headless default) MapLibre alone caps playback at 13–17 fps.
  args: ['--ignore-gpu-blocklist', '--enable-gpu', '--use-gl=angle', '--use-angle=gl-egl', '--enable-precise-memory-info'],
});
const mb = (b) => (b / 1e6).toFixed(1);
const ms = (x) => Math.round(x);
const rows = {};
try {
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await page.goto(BASE);
  await page.waitForSelector('text=Watch replay');
  const races = await page.evaluate(async () => (await fetch('/api/v1/races')).json());
  const raceId = Object.fromEntries(races.map((r) => [r.courseId, r.id]));

  for (const course of COURSES) {
    // Loading, best of RUNS: fetch (bytes in), gunzip/JSON.parse/decode, ReplayModel; then snapshotAt over the race.
    const m = await page.evaluate(
      async ({ id, runs }) => {
        const { parseReplayBytes } = await import('/src/api/client.ts');
        const { ReplayModel } = await import('/src/replay/model.ts');
        let best = null;
        for (let i = 0; i < runs; i++) {
          const t0 = performance.now();
          const res = await fetch(`/api/v1/races/${id}/replay`, { cache: 'no-store' });
          const buf = await res.arrayBuffer();
          const t1 = performance.now();
          const replay = await parseReplayBytes(buf);
          const t2 = performance.now();
          const model = new ReplayModel(replay);
          const t3 = performance.now();
          const run = { fetch: t1 - t0, decode: t2 - t1, model: t3 - t2, json: buf.byteLength, model_: model };
          if (!best || run.fetch + run.decode + run.model < best.fetch + best.decode + best.model) best = run;
        }
        const model = best.model_;
        const n = 2000;
        const s0 = performance.now();
        for (let k = 0; k < n; k++) model.snapshotAt(model.startT + ((model.endT - model.startT) * k) / n);
        const snapshot = ((performance.now() - s0) / n) * 1000;
        return { fetch: best.fetch, decode: best.decode, model: best.model, json: best.json, snapshot };
      },
      { id: raceId[course], runs: RUNS },
    );
    const gz = statSync(`${REPLAYS}/${course}-42.json.gz`).size;
    rows[course] = { ...m, gz };
    console.log(course, JSON.stringify(rows[course]));
  }

  if (PROD !== 'none') {
    for (const course of COURSES) {
      // Click "Watch replay" → playback bar and map canvas there; then ×60 in the finale for 10 s of rAF.
      await page.goto(PROD);
      await page.reload();
      await page.waitForSelector('text=Watch replay');
      const item = page.locator('tr', { hasText: races.find((r) => r.courseId === course).courseName });
      const t0 = Date.now();
      await item.locator('text=Watch replay').first().click();
      await page.waitForSelector('.playback-track');
      await page.waitForSelector('canvas');
      const open = Date.now() - t0;
      await page.waitForTimeout(2000);
      await page.locator('.playback-track').focus();
      await page.keyboard.press('End');
      for (let i = 0; i < 6; i++) await page.keyboard.press('Shift+ArrowLeft'); // 1 h before the end
      await page.click('text=×60');
      await page.keyboard.press('Space');
      const play = await page.evaluate(
        () =>
          new Promise((done) => {
            const dts = [];
            let last = performance.now();
            const end = last + 10_000;
            const frame = (now) => {
              dts.push(now - last);
              last = now;
              if (now < end) requestAnimationFrame(frame);
              else {
                dts.sort((a, b) => a - b);
                const heap = performance.memory ? performance.memory.usedJSHeapSize : 0;
                done({ fps: dts.length / 10, p95: dts[Math.floor(dts.length * 0.95)], heap });
              }
            };
            requestAnimationFrame(frame);
          }),
      );
      await page.keyboard.press('Space');
      rows[course] = { ...rows[course], open, ...play };
      console.log(course, JSON.stringify({ open, ...play }));
    }
  }
} finally {
  await browser.close();
}

console.log('\n| | Milano-Sanremo | Liège-Bastogne-Liège | Il Lombardia |\n|---|---|---|---|');
const line = (label, f) => console.log(`| ${label} | ${COURSES.map((c) => f(rows[c])).join(' | ')} |`);
line('Replay download (gzip on the wire → JSON)', (r) => `${mb(r.gz)} MB → ${mb(r.json)} MB`);
line('Fetch / gunzip + JSON.parse + decode / `ReplayModel`', (r) => `${ms(r.fetch)} / ${ms(r.decode)} / ${ms(r.model)} ms`);
if (PROD !== 'none') line('Click "Watch replay" → replay view rendered (production build)', (r) => `${r.open} ms`);
line('`snapshotAt` (mean over the race)', (r) => `${Math.round(r.snapshot)} µs`);
if (PROD !== 'none') {
  line('×60 playback, production build (10 s of rAF, finale)', (r) => `${Math.round(r.fps)} fps, p95 ${r.p95.toFixed(1)} ms`);
  line('JS heap while playing', (r) => `${Math.round(r.heap / 1e6)} MB`);
}
