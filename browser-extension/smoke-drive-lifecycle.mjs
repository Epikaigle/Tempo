// Exercise public Drive operations with deterministic storage/auth/network fixtures.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';

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
      export const getDriveOriginEventIds = async () => new Set();
       export const hasDriveOriginEventId = async () => false;
       export const claimUnownedDrivePlays = async () => {};
      export const getOwnPlayIdentityInputs = async () => globalThis.fixture.ownLocalPlays ?? [];
      export const markDriveUploaded = async () => {};
      export const insertPlay = async value => { globalThis.fixture.imported.push(value); };
      export const hasRecentPlay = async () => false;
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
    session: { accessToken: 'token-a', accountEmail: 'a@example.com', accountSubject: 'google-a' }, cleared: 0, marker: 100,
    writes: 0, listCalls: 0, paginated: false, imported: [],
    stored: { [stateKey]: { acceptedDisableVersion: 100, downloadCreatedCursor: 90,
      lastUploaded: 5, lastImported: 6, lastAuthorizedAccountEmail: 'a@example.com', lastAuthorizedAccountSubject: 'google-a', accountEmail: 'a@example.com' } },
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
fixture.session.accountSubject = 'google-b';
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

for (const marker of [0, -1]) {
  reset();
  fixture.marker = marker;
  await assert.rejects(drive.syncDriveHistory(), /valid deletion marker/);
  assert.equal(fixture.writes, 0);
  assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 100);
  assert.equal(fixture.cleared, 0);
}
console.log('  ✓ invalid server marker timestamps cannot authorize uploads or alter accepted state');

reset();
fixture.stored.tempoDriveDeviceId = 'local-device';
const event = {
  event_id: 'a'.repeat(64), title: 'Valid song', artist: 'Artist', album: null,
  timestamp_utc: 1_700_000_000_000, duration_ms: 180_000, listened_ms: 170_000,
  source_app: 'test', source: 'browser:test', skipped: false, replay_count: 0,
  completion_percentage: 94, pause_count: 0, seek_count: 0, session_id: null,
  site: null, content_type: 'MUSIC', volume_level: 50, total_pause_duration_ms: 0,
  position_updates_count: 10,
};
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const batchId = sha256(`tempo-batch-v1|${event.event_id}`);
const payloads = [null, {
  schema_version: 1, batch_id: batchId, source_device_id: 'remote-device',
  source_device_name: 'Remote browser', source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_000, events: [event],
}].map(value => gzipSync(JSON.stringify(value)));
const files = payloads.map((bytes, index) => ({
  id: `batch-${index}`, name: `tempo_history_v1_g100_remote-device_${batchId}.json.gz`,
  size: String(bytes.length), createdTime: new Date(1_700_000_000_000 + index).toISOString(),
  appProperties: { tempo_kind: 'history_batch', tempo_schema: '1', source_device_id: 'remote-device',
    source_platform: 'chrome_extension', tempo_generation: '100', tempo_sha256: sha256(bytes) },
}));
globalThis.fetch = async rawUrl => {
  const url = new URL(rawUrl);
  if (url.searchParams.get('alt') === 'media') {
    return new Response(payloads[Number(url.pathname.split('-').at(-1))]);
  }
  if (url.searchParams.get('q')?.startsWith('name =')) {
    return Response.json({ files: [{ id: 'marker', modifiedTime: new Date(100).toISOString() }] });
  }
  return Response.json({ files });
};
const result = await drive.syncDriveHistory();
assert.equal(result.imported, 1);
assert.equal(fixture.imported[0].title, 'Valid song');
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 1_700_000_000_001);
assert.equal(fixture.stored[stateKey].lastError, null);
console.log('  ✓ a JSON null batch is consumed and does not block a later valid import');
reset();
// The module caches a stable local device ID. Make the fixture batch belong to
// that SAME device, rather than pretending the device identity changed mid-run.
fixture.stored.tempoDriveDeviceId = 'local-device';
const ownBytes = gzipSync(JSON.stringify({
  schema_version: 1, batch_id: batchId, source_device_id: 'local-device',
  source_device_name: 'This browser', source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_000, events: [event],
}));
payloads[1] = ownBytes;
files[1] = {
  ...files[1],
  name: `tempo_history_v1_g100_local-device_${batchId}.json.gz`,
  size: String(ownBytes.length),
  appProperties: {
    ...files[1].appProperties,
    source_device_id: 'local-device',
    tempo_sha256: sha256(ownBytes),
  },
};
const normalOwn = await drive.syncDriveHistory();
assert.equal(normalOwn.imported, 0, 'normal sync does not replay our own Drive uploads');
const restoredOwn = await drive.restoreDriveHistory();
assert.equal(restoredOwn.imported, 1, 'explicit restoration recovers own deleted local records');
console.log('  ✓ explicit restore recovers own producer batches while incremental sync skips them');
console.log('\n7 Drive lifecycle scenarios passed');
