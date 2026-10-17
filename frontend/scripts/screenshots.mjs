// Regenerates docs/screenshots/ (frontend/README.md "Screenshots") from the seed-42 races.
//
// Needs, from the repository root:
//   ./gradlew :runner:run --args="--courses milano-sanremo,liege-bastogne-liege,il-lombardia --seed 42 --out replays/ --serve"
// and, in frontend/, `npm run dev` (Vite on :5173, /api proxied to the kernel). Then `npm run screenshots`.
//
// Env: BASE (default http://localhost:5173/), CHROME (default /usr/bin/google-chrome; playwright-core does not
// download browsers), REPLAYS (default ../replays, the runner's --out, read to find the moments to shoot),
// OUT (default ../docs/screenshots).
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE ?? 'http://localhost:5173/';
const CHROME = process.env.CHROME ?? '/usr/bin/google-chrome';
const REPLAYS = resolve(process.env.REPLAYS ?? resolve(here, '../../replays'));
const OUT = resolve(process.env.OUT ?? resolve(here, '../../docs/screenshots'));
const W = 1440;
const H = 900;
const COURSES = ['milano-sanremo', 'liege-bastogne-liege', 'il-lombardia'];

/** Race times (s) worth a picture, from the replay frames (`r` = [distM, speed, groupId, ...] per rider). */
function moments(course) {
  const file = `${REPLAYS}/${course}-42.json.gz`;
  const r = JSON.parse(gunzipSync(readFileSync(file)).toString());
  // Breakaway: the first frame after 30 min where a group of at most 12 is 1.5 km ahead of the biggest group.
  let breakaway = null;
  for (const f of r.frames) {
    if (f.t <= 1800) continue;
    const g = new Map();
    for (const x of f.r) {
      if (x[2] === 0) continue;
      const e = g.get(x[2]) ?? { n: 0, d: 0 };
      e.n++;
      e.d = Math.max(e.d, x[0]);
      g.set(x[2], e);
    }
    const gs = [...g.values()].sort((a, b) => b.d - a.d);
    if (gs.length < 2) continue;
    const big = gs.reduce((a, b) => (b.n > a.n ? b : a));
    if (gs[0] !== big && gs[0].n <= 12 && gs[0].d - big.d > 1500) {
      breakaway = f.t;
      break;
    }
  }
  const last = r.course.climbs.at(-1);
  let lastClimb = null;
  let finish = null;
  for (const f of r.frames) {
    const lead = Math.max(...f.r.map((x) => x[0]));
    if (lastClimb == null && lead >= (last.startM + last.endM) / 2) lastClimb = f.t;
    if (finish == null && f.r.some((x) => x[2] === 0)) finish = f.t;
  }
  return { breakaway, lastClimb, lastClimbName: last.name, finish: finish + 8, end: r.frames.at(-1).t };
}

const m = Object.fromEntries(COURSES.map((c) => [c, moments(c)]));
for (const c of COURSES) console.log(c, JSON.stringify(m[c]));

/** [course, file name, race time (s)] */
const shots = [
  ['milano-sanremo', 'breakaway', m['milano-sanremo'].breakaway + 2400],
  ['milano-sanremo', 'last-climb', m['milano-sanremo'].lastClimb],
  ['liege-bastogne-liege', 'last-climb', m['liege-bastogne-liege'].lastClimb],
  ['il-lombardia', 'finish', m['il-lombardia'].finish],
  ['il-lombardia', 'results', m['il-lombardia'].end],
];
const LOCAL = ['liege-bastogne-liege', 4 * 3600 + 22 * 60];

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--ignore-gpu-blocklist', '--enable-gpu', '--use-gl=angle', '--use-angle=gl-egl'],
});
const errors = [];
try {
  const page = await (await browser.newContext({ viewport: { width: W, height: H } })).newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });

  /** Seek with the timeline keys (Home, shift+→ = 10 min, → = 1 min), then let the map settle. */
  async function seek(t) {
    await page.locator('.playback-track').focus();
    await page.keyboard.press('Home');
    const tens = Math.floor(t / 600);
    for (let i = 0; i < tens; i++) await page.keyboard.press('Shift+ArrowRight');
    for (let i = 0; i < Math.round((t - tens * 600) / 60); i++) await page.keyboard.press('ArrowRight');
    await page.mouse.move(1, 1);
    await page.waitForTimeout(2500);
  }

  await page.goto(BASE);
  await page.waitForSelector('text=Watch replay');
  await page.screenshot({ path: `${OUT}/race-list.png` });
  const races = await page.evaluate(async () => (await fetch('/api/v1/races')).json());
  const raceId = Object.fromEntries(races.map((r) => [r.courseId, r.id]));

  let current = null;
  for (const [course, name, t] of shots) {
    if (current !== course) {
      await page.goto(`${BASE}#/race/${raceId[course]}`);
      await page.reload();
      await page.waitForSelector('.playback-track');
      await page.waitForTimeout(3000);
      current = course;
    }
    await seek(t);
    if (name === 'results' && !(await page.locator('.results').count())) {
      await page.click('text=Results');
      await page.waitForTimeout(400);
    }
    await page.screenshot({ path: `${OUT}/${course}-${name}.png` });
    if (name !== 'results' && (await page.locator('.results').count())) await page.keyboard.press('Escape');
    console.log(`${course}-${name}.png at ${t} s`);
  }

  // A replay file opened from the race list, without the kernel.
  await page.goto(BASE);
  await page.reload();
  await page.waitForSelector('text=Open a local file');
  await page.setInputFiles('input[type=file]', `${REPLAYS}/${LOCAL[0]}-42.json.gz`);
  await page.waitForSelector('.playback-track', { timeout: 60000 });
  await page.waitForTimeout(3000);
  await seek(LOCAL[1]);
  await page.screenshot({ path: `${OUT}/local-file-${LOCAL[0]}.png` });
  console.log(`local-file-${LOCAL[0]}.png at ${LOCAL[1]} s`);
} finally {
  await browser.close();
}
console.log('page errors:', errors.join(' | ') || 'none');
if (errors.length) process.exitCode = 1;
