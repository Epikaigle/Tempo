// Exercise public Drive operations with deterministic storage/auth/network fixtures.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-lifecycle-')), 'drive.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-history.ts'], bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
  outfile: out, logLevel: 'silent',
  plugins: [{ name: 'drive-fixtures', setup(build) {
    build.onResolve({ filter: /^\.\/(storage|drive-auth)$/ }, args => ({ path: args.path, namespace: 'fixture' }));
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path === './storage' ? `
      export const getSettings = async () => ({...globalThis.fixture.settings});
      export const saveSettings = async value => { globalThis.fixture.settings = {...value}; };
      export const clearDriveUploadedFlags = async () => { globalThis.fixture.cleared++; };
      export const getDrivePendingPlays = async () => [];
      export const getAllPlays = async () => [];
      export const markDriveUploaded = async () => {};
      export const insertPlay = async () => {};
    ` : `
      export const getDriveAuthSession = async () => globalThis.fixture.session;
      export const isDriveOAuthConfigured = () => true;
      export const isFirefoxBuild = () => false;
      export const hasFirefoxDriveDataConsent = async () => true;
      export const invalidateDriveAccessToken = async () => {};
      export const disconnectDriveAuth = async () => {};
    ` }));
  } }],
});
const drive = await import(pathToFileURL(out).href);
const stateKey = 'tempoDriveHistoryState';
function reset() {
  globalThis.fixture = {
    settings: { driveSyncEnabled: true, syncIntervalMinutes: 30 },
    session: { accessToken: 'token-a', accountEmail: 'a@example.com' }, cleared: 0, marker: 100,
    writes: 0, listCalls: 0, paginated: false,
    stored: { [stateKey]: { acceptedDisableVersion: 100, downloadCreatedCursor: 90,
      lastUploaded: 5, lastImported: 6, lastAuthorizedAccountEmail: 'a@example.com', accountEmail: 'a@example.com' } },
  };
}
globalThis.chrome = {
  storage: { local: {
    get: async key => ({ [key]: globalThis.fixture.stored[key] }),
    set: async values => Object.assign(globalThis.fixture.stored, values),
  } },
  alarms: { clear: async () => true, create: () => {} },
};
globalThis.fetch = async (rawUrl, options = {}) => {
  const url = new URL(rawUrl);
  if (options.method === 'PATCH') {
    fixture.writes++; fixture.marker = 200;
    return Response.json({ id: 'marker', name: 'tempo_history_control_v1.json', modifiedTime: new Date(200).toISOString() });
  }
  if (url.searchParams.get('q')?.startsWith('name =')) {
    fixture.listCalls++;
    const isSecondPage = url.searchParams.has('pageToken');
    return Response.json({
      files: [{ id: isSecondPage ? 'newer' : 'marker', name: 'tempo_history_control_v1.json',
        modifiedTime: new Date(fixture.paginated && isSecondPage ? 300 : fixture.marker).toISOString() }],
      ...(fixture.paginated && !isSecondPage ? { nextPageToken: 'page-2' } : {}),
    });
  }
  // A permanent cleanup failure must not re-enable sync or advance upload state.
  return Response.json({ error: { message: 'Cleanup forbidden' } }, { status: 403 });
};

reset();
fixture.marker = 200;
await assert.rejects(drive.syncDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.settings.driveSyncEnabled, false);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 200);
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 0);
assert.equal(fixture.cleared, 1);
console.log('  ✓ remote deletion stays disabled after cleanup failure');

reset();
await assert.rejects(drive.deleteDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.settings.driveSyncEnabled, false);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 200);
assert.equal(fixture.stored[stateKey].lastUploaded, 0);
assert.match(fixture.stored[stateKey].lastError, /Cleanup forbidden/);
assert.ok(fixture.writes > 0);
console.log('  ✓ explicit deletion persists the stop and surfaces cleanup errors');

reset();
fixture.session.accountEmail = 'b@example.com';
await assert.rejects(drive.deleteDriveHistory(), /account changed/);
assert.equal(fixture.writes, 0);
assert.equal(fixture.listCalls, 0);
assert.equal(fixture.settings.driveSyncEnabled, false);
console.log('  ✓ account switching prevents destructive cloud requests');

reset();
fixture.paginated = true;
await assert.rejects(drive.syncDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 300);
assert.equal(fixture.listCalls, 2);
console.log('  ✓ newest control marker is read across all list pages');
console.log('\n4 Drive lifecycle scenarios passed');
