// Smoke test for the YTMusic tracking fixes.
// Bundles tracker.ts (pure, no chrome deps) and runs behavioral assertions.
import * as esbuild from 'esbuild';
import { mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { pathToFileURL } from 'url';

const dir = mkdtempSync(join(tmpdir(), 'tempo-smoke-'));
const out = join(dir, 'tracker.mjs');
const typesOut = join(dir, 'types.mjs');

await esbuild.build({
  entryPoints: ['src/background/tracker.ts'],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node18',
  outfile: out,
  logLevel: 'silent',
});

await esbuild.build({
  entryPoints: ['src/shared/types.ts'],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node18',
  outfile: typesOut,
  logLevel: 'silent',
});

const { PlaybackTracker } = await import(pathToFileURL(out).href);
const { TrackEventType } = await import(pathToFileURL(typesOut).href);

let pass = 0, fail = 0;
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${detail ? ' — ' + detail : ''}`); }
}

function sample(over) {
  return {
    url: 'https://music.youtube.com/watch?v=x',
    title: 'Song A', artist: 'Artist', album: 'Album',
    duration: 180, position: 0, isPlaying: true,
    volume: 1, isMuted: false, playbackRate: 1.0,
    tabId: 1, timestamp: Date.now(),
    ...over,
  };
}

console.log('\n[1] Position reset while eligible finalizes previous session (YTMusic 15-min bug)');
{
  const t = new PlaybackTracker();
  let now = 1_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    for (let pos = 10; pos <= 120; pos += 10) {
      now += 10_000;
      t.process(sample({ position: pos }));
    }
    // YTMusic advances to next song but metadata lags → SAME key, position resets to ~0.
    now += 10_000;
    const ev = t.process(sample({ position: 0.5 }));
    check('position reset emits ReadyToLog for previous session', ev.type === TrackEventType.ReadyToLog, `got ${ev.type}`);
    if (ev.type === TrackEventType.ReadyToLog) {
      const listened = ev.nowPlaying.listenedMs;
      check('previous session listened ~120s (not 15min)', listened >= 115_000 && listened <= 135_000, `listened=${listened}`);
    }
    check('new session starts at ~0 accumulated', t.currentListenMs <= 1_000, `current=${t.currentListenMs}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[2] Duration cap prevents wall-clock overcount when position is stuck');
{
  const t = new PlaybackTracker();
  let now = 5_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0, duration: 180 }));
    // Position stuck at 0 but "playing" for ~16 minutes of wall-clock time.
    for (let i = 0; i < 100; i++) { now += 10_000; t.process(sample({ position: 0, duration: 180 })); }
    const listened = t.currentListenMs;
    check('stuck-position accrual bounded by 3x duration cap', listened <= 180_000 * 3 + 1_000, `listened=${listened}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[3] sweepStaleSessions finalizes silent playing sessions');
{
  const t = new PlaybackTracker();
  let now = 9_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    for (let pos = 10; pos <= 120; pos += 10) { now += 10_000; t.process(sample({ position: pos })); }
    // Session goes silent (worker hibernated / tab killed).
    now += 200_000;
    const swept = t.sweepStaleSessions(150_000);
    check('one stale session swept', swept.length === 1, `got ${swept.length}`);
    if (swept.length === 1) {
      check('swept session belongs to tab 1', swept[0].tabId === 1);
      check('swept event is ReadyToLog', swept[0].event.type === TrackEventType.ReadyToLog, `got ${swept[0].event.type}`);
      if (swept[0].event.type === TrackEventType.ReadyToLog) {
        const listened = swept[0].event.nowPlaying.listenedMs;
        check('swept listened time ~120s + grace, not runaway', listened >= 115_000 && listened <= 140_000, `listened=${listened}`);
      }
    }
    check('tracker map emptied after sweep', t.activeTabCount === 0, `count=${t.activeTabCount}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[3b] sweep excludes tabs confirmed still alive (audible)');
{
  const t = new PlaybackTracker();
  let now = 14_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    for (let pos = 10; pos <= 120; pos += 10) { now += 10_000; t.process(sample({ position: pos })); }
    now += 200_000; // silent past threshold
    const candidates = t.listStaleSessionTabIds(150_000);
    check('listStaleSessionTabIds finds the silent session', candidates.length === 1 && candidates[0] === 1, `got ${JSON.stringify(candidates)}`);
    check('listing does NOT finalize (session still tracked)', t.activeTabCount === 1, `count=${t.activeTabCount}`);
    // Tab is still audible (throttled, not dead) → excluded from sweep.
    const swept = t.sweepStaleSessions(150_000, new Set([1]));
    check('audible tab excluded from sweep', swept.length === 0, `got ${swept.length}`);
    check('excluded session keeps accruing state', t.activeTabCount === 1, `count=${t.activeTabCount}`);
    // Once the tab is gone, sweep finalizes it.
    const swept2 = t.sweepStaleSessions(150_000, new Set());
    check('session swept once tab no longer alive', swept2.length === 1, `got ${swept2.length}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[4] sweepStaleSessions leaves paused sessions alone');
{
  const t = new PlaybackTracker();
  let now = 20_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    for (let pos = 10; pos <= 60; pos += 10) { now += 10_000; t.process(sample({ position: pos })); }
    now += 10_000; t.process(sample({ position: 60, isPlaying: false })); // pause
    now += 500_000; // long silence while paused
    const swept = t.sweepStaleSessions(150_000);
    check('paused session NOT swept', swept.length === 0, `got ${swept.length}`);
    check('paused session still tracked', t.activeTabCount === 1, `count=${t.activeTabCount}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[5] Track change finalizes previous and resets counters');
{
  const t = new PlaybackTracker();
  let now = 30_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    for (let pos = 10; pos <= 120; pos += 10) { now += 10_000; t.process(sample({ position: pos })); }
    now += 10_000;
    const ev = t.process(sample({ title: 'Song B', position: 0 }));
    check('track change emits ReadyToLog for Song A', ev.type === TrackEventType.ReadyToLog, `got ${ev.type}`);
    check('fresh session for Song B', t.currentListenMs <= 1_000, `current=${t.currentListenMs}`);
  } finally {
    Date.now = realNow;
  }
}

console.log('\n[6] Early position reset (not eligible) counts as replay, keeps accruing');
{
  const t = new PlaybackTracker();
  let now = 40_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  try {
    t.process(sample({ position: 0 }));
    now += 5_000; t.process(sample({ position: 5 }));   // 5s accrued — below 15s eligibility
    now += 5_000; t.process(sample({ position: 0.5 })); // reset too early → replay, not boundary
    check('early reset does not finalize', t.activeTabCount === 1);
    now += 20_000; t.process(sample({ position: 20 }));
    check('accrual continues after early reset', t.currentListenMs >= 20_000, `current=${t.currentListenMs}`);
  } finally {
    Date.now = realNow;
  }
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
