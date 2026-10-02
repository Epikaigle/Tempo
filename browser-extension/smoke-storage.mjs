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
console.log('\n2 IndexedDB durability scenarios passed');
