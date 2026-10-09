// Deliver IndexedDB success and transaction events separately to test durability.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-storage-')), 'storage.mjs');
await esbuild.build({ entryPoints: ['src/background/storage.ts'], bundle: true, format: 'esm',
  platform: 'browser', target: 'es2022', outfile: out, logLevel: 'silent' });
let transaction;
let request;
const database = { transaction() {
  request = { result: 42 };
  transaction = { objectStore: () => ({ add: () => request }) };
  return transaction;
} };
globalThis.indexedDB = { open() {
  const opening = { result: database };
  queueMicrotask(() => opening.onsuccess());
  return opening;
} };
const storage = await import(pathToFileURL(out).href);
assert.equal(storage.recentPlayWindowMs(), 5_000,
  'local detector callbacks must use a short tolerance so real replays survive');
assert.equal(storage.recentPlayWindowMs(''), 5_000);
assert.equal(storage.recentPlayWindowMs('other-device'), 60_000,
  'cross-device imports still reconcile a minute of timestamp drift');

for (const abort of [false, true]) {
  let settled = false;
  const insertion = storage.insertPlay({ title: 'Song', artist: 'Artist' });
  const observed = insertion.then(value => { settled = true; return { value }; },
    error => { settled = true; return { error }; });
  // Let the asynchronous openDb continuation create its write transaction.
  await new Promise(resolve => setImmediate(resolve));
  request.onsuccess?.();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(settled, false, 'a successful add request is not a durable commit');
  if (abort) {
    transaction.error = new DOMException('Storage commit failed', 'AbortError');
    transaction.onabort();
    assert.equal((await observed).error.name, 'AbortError');
  } else {
    transaction.oncomplete();
    assert.equal((await observed).value, 42);
  }
}

// Exercise the actual IndexedDB query: local callbacks and remote overlap use
// different windows, while two distinct IDs from one remote device survive.
globalThis.IDBKeyRange = { bound: (lower, upper) => ({ lower, upper }) };
const baseTime = 1_700_000_000_000;
let localRecords = [{
  title: 'Song', artist: 'Artist', timestampUtc: baseTime,
  originDeviceId: 'remote-A',
}];
database.transaction = () => {
  const tx = {
    objectStore: () => ({
      index: () => ({
        openCursor(range) {
          const entries = localRecords.filter(item =>
            item.timestampUtc >= range.lower && item.timestampUtc <= range.upper);
          const lookup = {};
          let index = 0;
          const next = () => {
            const item = entries[index++];
            lookup.result = item ? {
              value: item,
              continue: () => queueMicrotask(next),
              update(newValue) {
                const position = localRecords.indexOf(item);
                localRecords[position] = newValue;
                queueMicrotask(() => tx.oncomplete?.());
              },
            } : null;
            lookup.onsuccess?.();
          };
          queueMicrotask(next);
          return lookup;
        },
      }),
    }),
  };
  return tx;
};
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 4_000), true);
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000), false,
  'a real local short-track replay must not be dropped');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-B', 'event-B1'), true,
  'different devices capturing the same play must reconcile');
assert.deepEqual(localRecords[0].reconciledOrigins, [{ deviceId: 'remote-B', eventId: 'event-B1' }],
  'matched producer/event ID must be durable before acknowledging the duplicate');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 27_000, 'remote-B', 'event-B2'), false,
  'another replay from the same producer must not be swallowed by the first alias');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-A'), false,
  'distinct quick replays from one origin must be preserved');
assert.equal(await storage.hasRecentPlay('Different Song', 'Artist', baseTime + 2_000, 'remote-B'), false);

console.log('\n10 IndexedDB durability and dedup scenarios passed');
