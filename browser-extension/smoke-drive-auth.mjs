// Exercise public account authorization through a stalled and a normal userinfo response.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-auth-')), 'auth.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-auth.ts'], bundle: true, format: 'esm', platform: 'browser',
  target: 'es2022', outfile: out, logLevel: 'silent', define: {
    __TEMPO_BROWSER_TARGET__: JSON.stringify('chrome'),
    __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: JSON.stringify('test-client.apps.googleusercontent.com'),
  },
});
const auth = await import(pathToFileURL(out).href);
let invalidated = 0;
globalThis.chrome = {
  runtime: {}, identity: {
    getAuthToken(_options, callback) { callback('test-token'); },
    removeCachedAuthToken(_options, callback) { invalidated++; callback(); },
  },
};
const nativeTimeout = globalThis.setTimeout;
globalThis.setTimeout = (callback, milliseconds, ...args) =>
  nativeTimeout(callback, milliseconds === 30_000 ? 20 : milliseconds, ...args);
globalThis.fetch = async (_url, options) => new Response(new ReadableStream({
  start(stream) {
    options.signal.addEventListener('abort', () => stream.error(new DOMException('Aborted', 'AbortError')));
  },
}));
assert.equal(await auth.getDriveAuthSession(false), null);
assert.equal(invalidated, 1);
console.log('  ✓ stalled identity response times out and cannot authorize an unknown account');

globalThis.fetch = async () => Response.json({ email: ' verified@example.com ' });
assert.deepEqual(await auth.getDriveAuthSession(false), {
  accessToken: 'test-token', accountEmail: 'verified@example.com',
});
assert.equal(invalidated, 1);
globalThis.setTimeout = nativeTimeout;
console.log('  ✓ successful identity verification retains the authorized token and normalized email');
console.log('\n2 Drive authorization scenarios passed');
