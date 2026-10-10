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
assert.equal(storage.recentPlayWindowMs('other-device'), 2_000,
  'cross-device imports use a 2-second window to preserve independent rapid replays');

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
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 7_000, 'remote-B', 'event-B1'), false,
  'an independent cross-device replay seven seconds later must survive');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000, 'remote-B', 'event-B1'), true,
  'captures within two seconds can reconcile across sources');
assert.deepEqual(localRecords[0].reconciledOrigins, [{ deviceId: 'remote-B', eventId: 'event-B1' }],
  'matched producer/event ID must be durable before acknowledging the duplicate');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 27_000, 'remote-B', 'event-B2'), false,
  'another replay from the same producer must not be swallowed by the first alias');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-C', 'event-C1'), false,
  'a different device replaying the same song 25s later must remain a separate play');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-A'), false,
  'distinct quick replays from one origin must be preserved');
assert.equal(await storage.hasRecentPlay('Different Song', 'Artist', baseTime + 2_000, 'remote-B'), false);

// Full-history restore must recover OWN local IDs before importing cloud data.
// A previously uploaded local play has no originEventId field, so checking
// only stored remote aliases used to duplicate the same device's whole archive.
localRecords = [
  { id: 14, title: ' Local ', artist: ' Artist ', timestampUtc: baseTime, driveAccountSubject: 'google-a' },
  { id: 15, title: 'Cloud', artist: 'Artist', timestampUtc: baseTime, driveImported: true, originEventId: 'external' },
  { id: 16, title: 'Own with alias', artist: 'Artist', timestampUtc: baseTime, originEventId: 'known' },
  { id: 17, title: 'Second local', artist: 'Band', timestampUtc: baseTime + 25_000, driveAccountSubject: 'google-b' },
];
database.transaction = () => {
  const tx = {
    objectStore: () => ({
      openCursor() {
        const req = {};
        let i = 0;
        const next = () => {
          const value = localRecords[i++];
          req.result = value ? { value, continue: () => queueMicrotask(next) } : null;
          req.onsuccess?.();
          if (!value) queueMicrotask(() => tx.oncomplete?.());
        };
        queueMicrotask(next);
        return req;
      },
    }),
  };
  return tx;
};
const ownInputs = await storage.getOwnPlayIdentityInputs();
assert.deepEqual(ownInputs, [
  { id: 14, title: ' Local ', artist: ' Artist ', timestampUtc: baseTime },
  { id: 17, title: 'Second local', artist: 'Band', timestampUtc: baseTime + 25_000 },
], 'restore recovers original locally-owned IDs, not imported or already-aliased rows');
assert.deepEqual((await storage.getOwnPlayIdentityInputs('google-a')).map(p => p.id),
  [14], 'full restore in A must not treat B-owned local plays as its own history');


// A cloud-upload acknowledgement must set the stable event ID on the exact
// IndexedDB rows in one write transaction, without scanning the entire history.
database.transaction = () => {
  let pendingReads = 0;
  const tx = {};
  const store = {
    get(id) {
      pendingReads++;
      const request = { result: localRecords.find(play => play.id === id) };
      queueMicrotask(() => {
        request.onsuccess?.();
        if (--pendingReads === 0) queueMicrotask(() => tx.oncomplete?.());
      });
      return request;
    },
    put(play) {
      const index = localRecords.findIndex(row => row.id === play.id);
      if (index >= 0) localRecords[index] = play;
    },
  };
  tx.objectStore = () => store;
  return tx;
};
await storage.markDriveUploaded([
  { id: 14, originEventId: 'owned-14' },
  { id: 15, originEventId: 'should-not-overwrite-imported' },
  { id: 17, originEventId: 'owned-17' },
]);
assert.equal(localRecords[0].originEventId, 'owned-14');
assert.equal(localRecords[1].originEventId, 'external',
  'imported plays must never be reclassified as locally-owned uploads');
assert.equal(localRecords[3].originEventId, 'owned-17');
assert.ok(localRecords[0].driveUploadedAt > 0 && localRecords[3].driveUploadedAt > 0,
  'verified cloud upload sets its acknowledgement together with stable identity');

// LAN-first delivery must persist its origin before returning a payload, so
// later title edits, retries and Drive sync cannot advertise a second ID.
localRecords.push({ id: 99, title: 'Original', artist: 'Artist', timestampUtc: baseTime });
const firstOrigin = 'a'.repeat(64);
const pinned = await storage.ensureLocalOriginEventId(99, firstOrigin);
assert.equal(pinned, firstOrigin);
assert.equal(localRecords.find(play => play.id === 99)?.originEventId, firstOrigin);
localRecords.find(play => play.id === 99).title = 'Corrected title';
const retryId = await storage.ensureLocalOriginEventId(99, 'b'.repeat(64));
assert.equal(retryId, firstOrigin, 'LAN-first origin survives later metadata changes');
assert.equal(localRecords.find(play => play.id === 99)?.originEventId, firstOrigin);


// Clearing the Drive deletion marker must not invalidate another Google
// account's upload acknowledgements or locally imported records.
const scopedRows = [
  { id: 1, driveAccountSubject: 'google-a', driveUploadedAt: 111 },
  { id: 2, driveAccountSubject: 'google-b', driveUploadedAt: 222 },
  { id: 3, driveAccountSubject: 'google-a', driveUploadedAt: 333, driveImported: true },
  { id: 4, driveUploadedAt: 444 },
];
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    openCursor() {
      const req = {};
      let position = 0;
      const next = () => {
        const row = scopedRows[position++];
        req.result = row ? {
          value: row,
          update(updated) {
            scopedRows[position - 1] = updated;
          },
          continue: () => queueMicrotask(next),
        } : null;
        req.onsuccess?.();
        if (!row) queueMicrotask(() => tx.oncomplete?.());
      };
      queueMicrotask(next);
      return req;
    },
  });
  return tx;
};
await assert.rejects(storage.clearDriveUploadedFlags(''), /verified Google subject/);
await storage.clearDriveUploadedFlags('google-a');
assert.equal(scopedRows[0].driveUploadedAt, undefined);
assert.equal(scopedRows[1].driveUploadedAt, 222,
  'Google B must keep its upload acknowledgement when Google A deletes history');
assert.equal(scopedRows[2].driveUploadedAt, 333,
  'cloud-imported rows are never re-uploaded by clearing flags');
assert.equal(scopedRows[3].driveUploadedAt, 444,
  'unowned legacy rows must not be silently claimed or invalidated');


// The regular Drive upload lookup must use the IndexedDB owner/time index,
// not read every already-uploaded play in a multi-year local archive.
const pendingRows = [
  { id: 1, title: 'A', timestampUtc: baseTime, driveAccountSubject: 'google-a',
    drivePendingIndexKey: ['google-a', baseTime] },
  { id: 2, title: 'B', timestampUtc: baseTime + 1000, driveAccountSubject: 'google-b',
    drivePendingIndexKey: ['google-b', baseTime + 1000] },
  { id: 3, title: 'New', timestampUtc: baseTime + 2000,
    drivePendingIndexKey: ['tempo-unowned', baseTime + 2000] },
  { id: 4, title: 'Done', timestampUtc: baseTime - 1000,
    driveAccountSubject: 'google-a', driveUploadedAt: 123 },
  { id: 5, title: 'A2', timestampUtc: baseTime + 3000,
    driveAccountSubject: 'google-a', drivePendingIndexKey: ['google-a', baseTime + 3000] },
];
let indexedReads = 0;
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    index(name) {
      assert.equal(name, 'drivePendingIndexKey', 'pending sync must not scan timestamps globally');
      return {
        openCursor(range) {
          const owner = range.lower[0];
          const entries = pendingRows.filter(row => row.drivePendingIndexKey &&
            row.drivePendingIndexKey[0] === owner &&
            row.drivePendingIndexKey[1] >= range.lower[1] &&
            row.drivePendingIndexKey[1] <= range.upper[1])
            .sort((a, b) => a.timestampUtc - b.timestampUtc || a.id - b.id);
          const req = {};
          let at = 0;
          const next = () => {
            const value = entries[at++];
            if (value) indexedReads++;
            req.result = value ? { value, continue: () => queueMicrotask(next) } : null;
            req.onsuccess?.();
            if (!value) queueMicrotask(() => tx.oncomplete?.());
          };
          queueMicrotask(next);
          return req;
        },
      };
    },
  });
  return tx;
};
const pendingA = await storage.getDrivePendingPlays(10, 'google-a');
assert.deepEqual(pendingA.map(row => row.id), [1, 3, 5],
  'Google A receives own pending plays and new unclaimed plays, never B');
assert.ok(indexedReads <= 3, 'already-synced archive must not be scanned');
assert.deepEqual((await storage.getDrivePendingPlays(10, 'google-a',
  { timestampUtc: baseTime + 2000, id: 3 })).map(row => row.id), [5],
  'composite indexed pagination must resume after the last submitted row');

console.log('\n16 IndexedDB durability, replay, account isolation and indexed queue scenarios passed');
