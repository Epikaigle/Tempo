import type { DriveSyncStatus, Play } from '../shared/types';
import * as storage from './storage';
import {
  disconnectDriveAuth,
  getDriveAuthSession,
  hasFirefoxDriveDataConsent,
  isDriveOAuthConfigured,
  isFirefoxBuild,
} from './drive-auth';

const DRIVE_API = 'https://www.googleapis.com/drive/v3';
const DRIVE_UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3';
const FILE_PREFIX = 'tempo_history_v1_';
const DISABLE_MARKER_NAME = 'tempo_history_control_v1.json';
const APP_PROPERTY_GENERATION = 'tempo_generation';
const SCHEMA_VERSION = 1;
const BATCH_SIZE = 50;
const MAX_LOCAL_SCAN = 5000;
const DOWNLOAD_OVERLAP_MS = 24 * 60 * 60 * 1000;
const MAX_BATCH_BYTES = 10 * 1024 * 1024;

const STATE_KEY = 'tempoDriveHistoryState';
const DEVICE_KEY = 'tempoDriveDeviceId';
export const DRIVE_SYNC_ALARM_NAME = 'tempo-drive-history-sync';

interface DriveRuntimeState {
  downloadCreatedCursor: number;
  acceptedDisableVersion: number;
  lastSyncTime: number | null;
  lastError: string | null;
  lastUploaded: number;
  lastImported: number;
  /** Account shown in the UI while connected. Cleared on disconnect. */
  accountEmail: string | null;
  /** Last Google account that owned these Drive cursors; survives disconnect. */
  lastAuthorizedAccountEmail: string | null;
}

interface WireEvent {
  event_id: string;
  title: string;
  artist: string;
  album: string | null;
  timestamp_utc: number;
  duration_ms: number;
  listened_ms: number;
  source_app: string;
  source: string;
  skipped: boolean;
  replay_count: number;
  completion_percentage: number;
  pause_count: number;
  seek_count: number;
  session_id: string | null;
  site: string | null;
  content_type: string;
  /** 0 = muted, positive = audible, null = unknown. */
  volume_level: number | null;
  total_pause_duration_ms: number;
  position_updates_count: number;
}

interface WireBatch {
  schema_version: number;
  batch_id: string;
  source_device_id: string;
  source_device_name: string;
  source_platform: string;
  created_at_utc: number;
  events: WireEvent[];
}

interface DriveFileRecord {
  id: string;
  name: string;
  size?: string;
  createdTime?: string;
  modifiedTime?: string;
  appProperties?: Record<string, string>;
}

const DEFAULT_STATE: DriveRuntimeState = {
  downloadCreatedCursor: 0,
  acceptedDisableVersion: 0,
  lastSyncTime: null,
  lastError: null,
  lastUploaded: 0,
  lastImported: 0,
  accountEmail: null,
  lastAuthorizedAccountEmail: null,
};

let syncPromise: Promise<{ uploaded: number; imported: number; duplicates: number }> | null = null;

export async function initDriveHistorySync(): Promise<void> {
  const settings = await storage.getSettings();
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
  if (!settings.driveSyncEnabled || !isDriveOAuthConfigured()) return;

  const interval = Math.max(15, Math.min(settings.syncIntervalMinutes || 30, 360));
  chrome.alarms.create(DRIVE_SYNC_ALARM_NAME, { periodInMinutes: interval });
}

function normalizeAccountEmail(value: string | null | undefined): string | null {
  const normalized = value?.trim().toLowerCase();
  return normalized ? normalized : null;
}

/**
 * Explicit opt-in. A shared cloud-deletion marker is acknowledged only here,
 * never silently by an alarm. If the marker advanced while this browser was
 * offline/disabled, clear local Drive-upload flags so the user's deliberate
 * re-enable can seed the now-empty cloud again from locally-owned history.
 *
 * Drive cursors are also account-scoped. Switching Google accounts resets all
 * Drive-only cursors/flags before the new account is accepted explicitly.
 */
export async function connectDrive(): Promise<DriveSyncStatus> {
  if (!isDriveOAuthConfigured()) throw new Error('Google Drive OAuth is not configured in this build');
  if (isFirefoxBuild() && !(await hasFirefoxDriveDataConsent())) {
    throw new Error('Allow the optional Firefox data-sharing permission before connecting Google Drive');
  }

  const session = await getDriveAuthSession(true);
  if (!session) throw new Error('Google Drive sign-in was cancelled or unavailable');

  const state = await getRuntimeState();
  const currentAccount = normalizeAccountEmail(session.accountEmail);
  const previousAccount = normalizeAccountEmail(state.lastAuthorizedAccountEmail);
  const accountChanged = !!previousAccount && !!currentAccount && previousAccount !== currentAccount;

  let acceptedDisableVersion = accountChanged ? 0 : state.acceptedDisableVersion;
  let downloadCreatedCursor = accountChanged ? 0 : state.downloadCreatedCursor;
  if (accountChanged) {
    await storage.clearDriveUploadedFlags();
  }

  const markerVersion = await getDisableMarkerVersion(session.accessToken);
  if (markerVersion > acceptedDisableVersion) {
    await storage.clearDriveUploadedFlags();
    downloadCreatedCursor = 0;
  }
  acceptedDisableVersion = markerVersion;

  await saveRuntimeState({
    ...state,
    acceptedDisableVersion,
    downloadCreatedCursor,
    accountEmail: session.accountEmail,
    lastAuthorizedAccountEmail: currentAccount ?? previousAccount,
    lastError: null,
    lastUploaded: accountChanged ? 0 : state.lastUploaded,
    lastImported: accountChanged ? 0 : state.lastImported,
  });

  const settings = await storage.getSettings();
  await storage.saveSettings({ ...settings, driveSyncEnabled: true });
  await initDriveHistorySync();
  await syncDriveHistory({ interactiveAuth: false });
  return getDriveSyncStatus();
}

export async function disconnectDrive(): Promise<DriveSyncStatus> {
  // Serialize user disconnect after an already-running sync so that no completed
  // background task can rewrite account/status state after disconnect returns.
  if (syncPromise) await syncPromise.catch(() => undefined);
  const settings = await storage.getSettings();
  await storage.saveSettings({ ...settings, driveSyncEnabled: false });
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
  await disconnectDriveAuth();
  const state = await getRuntimeState();
  // Preserve lastAuthorizedAccountEmail so a later explicit sign-in with a
  // different account cannot accidentally reuse this account's Drive cursors.
  await saveRuntimeState({ ...state, accountEmail: null, lastError: null });
  return getDriveSyncStatus();
}

export async function getDriveSyncStatus(): Promise<DriveSyncStatus> {
  const [settings, state] = await Promise.all([storage.getSettings(), getRuntimeState()]);
  const configured = isDriveOAuthConfigured();
  let connected = false;
  let needsInteractiveAuth = false;
  let visibleAccountEmail = state.accountEmail;

  if (settings.driveSyncEnabled && configured) {
    if (isFirefoxBuild() && !(await hasFirefoxDriveDataConsent())) {
      needsInteractiveAuth = true;
    } else {
      const session = await getDriveAuthSession(false).catch(() => null);
      connected = !!session;
      needsInteractiveAuth = !session;
      visibleAccountEmail = session?.accountEmail ?? visibleAccountEmail;
      // Do not mutate the account-boundary identity here. A status read must
      // never silently approve a Google-account change.
    }
  }

  return {
    enabled: !!settings.driveSyncEnabled,
    configured,
    connected,
    accountEmail: visibleAccountEmail,
    lastSyncTime: state.lastSyncTime,
    lastError: state.lastError,
    lastUploaded: state.lastUploaded,
    lastImported: state.lastImported,
    needsInteractiveAuth,
  };
}

export async function syncDriveHistory(
  options: { interactiveAuth?: boolean } = {},
): Promise<{ uploaded: number; imported: number; duplicates: number }> {
  if (syncPromise) return syncPromise;
  syncPromise = runSync(options).finally(() => { syncPromise = null; });
  return syncPromise;
}

async function runSync(
  options: { interactiveAuth?: boolean },
): Promise<{ uploaded: number; imported: number; duplicates: number }> {
  const settings = await storage.getSettings();
  if (!settings.driveSyncEnabled) return { uploaded: 0, imported: 0, duplicates: 0 };
  if (!isDriveOAuthConfigured()) throw new Error('Google Drive OAuth is not configured in this build');

  const session = await getDriveAuthSession(options.interactiveAuth === true);
  if (!session) {
    const message = 'Google Drive needs you to reconnect';
    await patchRuntimeState({ lastError: message });
    throw new Error(message);
  }

  try {
    const state = await getRuntimeState();
    const currentAccount = normalizeAccountEmail(session.accountEmail);
    const previousAccount = normalizeAccountEmail(state.lastAuthorizedAccountEmail);

    if (previousAccount && currentAccount && previousAccount !== currentAccount) {
      // Never write to a different account automatically. Clear only Drive-side
      // bookkeeping; the user's local listening history is untouched.
      await storage.clearDriveUploadedFlags();
      await storage.saveSettings({ ...settings, driveSyncEnabled: false });
      await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
      const message = 'Google account changed. Cross-device sync was turned off; connect Google again to use the new Drive account.';
      await saveRuntimeState({
        ...state,
        downloadCreatedCursor: 0,
        acceptedDisableVersion: 0,
        accountEmail: session.accountEmail,
        lastAuthorizedAccountEmail: currentAccount,
        lastUploaded: 0,
        lastImported: 0,
        lastError: message,
      });
      throw new Error(message);
    }

    if (!previousAccount && currentAccount) {
      // One-time migration for users who enabled Drive before account-boundary
      // state existed. Recording the current identity does not alter any cursor.
      await patchRuntimeState({
        accountEmail: session.accountEmail,
        lastAuthorizedAccountEmail: currentAccount,
      });
    }

    if (await honorRemoteDisableIfNeeded(session.accessToken)) {
      return { uploaded: 0, imported: 0, duplicates: 0 };
    }

    const deviceId = await getDeviceId();
    const uploaded = await uploadLocalPlays(session.accessToken, deviceId);
    const download = await downloadRemotePlays(session.accessToken, deviceId);
    await patchRuntimeState({
      lastSyncTime: Date.now(),
      lastError: null,
      lastUploaded: uploaded,
      lastImported: download.imported,
      accountEmail: session.accountEmail,
      lastAuthorizedAccountEmail: currentAccount ?? previousAccount,
    });
    return { uploaded, imported: download.imported, duplicates: download.duplicates };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    await patchRuntimeState({ lastError: message });
    throw err;
  }
}

/**
 * Stop before any upload when another linked Tempo client has bumped the shared
 * Drive deletion marker. Only pre-marker generations are removed: an explicitly
 * re-enabled client may already be publishing the new generation and a stale
 * browser must never delete that new history while honoring the old delete.
 */
async function honorRemoteDisableIfNeeded(accessToken: string): Promise<boolean> {
  const state = await getRuntimeState();
  const markerVersion = await getDisableMarkerVersion(accessToken);
  if (markerVersion <= state.acceptedDisableVersion) return false;

  await deleteBatchesBeforeGeneration(accessToken, markerVersion);
  const settings = await storage.getSettings();
  await storage.saveSettings({ ...settings, driveSyncEnabled: false });
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
  await storage.clearDriveUploadedFlags();
  await saveRuntimeState({
    ...state,
    acceptedDisableVersion: markerVersion,
    downloadCreatedCursor: 0,
    lastUploaded: 0,
    lastImported: 0,
    lastError: 'Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history.',
  });
  return true;
}

/**
 * Drive ownership is independent from the existing LAN status flag. A play can
 * already be marked `synced` to the phone and still need its first Drive upload.
 */
async function uploadLocalPlays(accessToken: string, deviceId: string): Promise<number> {
  const state = await getRuntimeState();
  const generation = Math.max(0, state.acceptedDisableVersion || 0);
  const pending = (await storage.getDrivePendingPlays(MAX_LOCAL_SCAN))
    .sort((a, b) => a.timestampUtc - b.timestampUtc || (a.id ?? 0) - (b.id ?? 0));

  let uploaded = 0;
  for (let offset = 0; offset < pending.length; offset += BATCH_SIZE) {
    const chunk = pending.slice(offset, offset + BATCH_SIZE);
    const events: WireEvent[] = [];
    for (const play of chunk) events.push(await playToWire(play, deviceId));

    const batchId = await deterministicBatchId(events);
    const batch: WireBatch = {
      schema_version: SCHEMA_VERSION,
      batch_id: batchId,
      source_device_id: deviceId,
      source_device_name: isFirefoxBuild() ? 'Firefox extension' : 'Chrome extension',
      source_platform: isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension',
      created_at_utc: Date.now(),
      events,
    };
    const gzip = await gzipJson(batch);
    const fileName = `${FILE_PREFIX}g${generation}_${deviceId}_${batchId}.json.gz`;
    await uploadBatch(accessToken, fileName, gzip, deviceId, generation);

    await storage.markDriveUploaded(chunk.map(p => p.id!).filter(Number.isFinite));
    uploaded += chunk.length;
  }
  return uploaded;
}

async function downloadRemotePlays(
  accessToken: string,
  deviceId: string,
): Promise<{ imported: number; duplicates: number }> {
  const state = await getRuntimeState();
  const acceptedGeneration = Math.max(0, state.acceptedDisableVersion || 0);
  const after = state.downloadCreatedCursor > 0
    ? Math.max(0, state.downloadCreatedCursor - DOWNLOAD_OVERLAP_MS)
    : null;
  const files = await listBatches(accessToken, after);

  const existing = await storage.getAllPlays(MAX_LOCAL_SCAN);
  const seenOriginIds = new Set(existing.map(p => p.originEventId).filter((x): x is string => !!x));
  let imported = 0;
  let duplicates = 0;
  let maxCreated = state.downloadCreatedCursor;

  for (const file of files) {
    const createdRaw = file.createdTime ? Date.parse(file.createdTime) : 0;
    const created = Number.isFinite(createdRaw) ? createdRaw : 0;
    if (batchGeneration(file) < acceptedGeneration) {
      // A pre-delete/in-flight stale batch must never resurrect after generation
      // N was accepted. Cleanup is best effort so current-generation sync can
      // continue even if this stale file races another client's deletion.
      await deleteDriveFileStrict(accessToken, file.id).catch(err => {
        console.warn(`[Tempo] Could not remove stale Drive history batch ${file.name}`, err);
      });
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    if (file.appProperties?.source_device_id === deviceId) {
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    const declaredSize = Number(file.size ?? '0');
    if (Number.isFinite(declaredSize) && declaredSize > MAX_BATCH_BYTES) {
      console.warn(`[Tempo] Skipping oversized Drive history batch ${file.name}`);
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    // Fetch errors are retryable and must abort without advancing the cursor.
    // Otherwise a temporarily unavailable batch can fall outside the 24h overlap
    // and disappear from future scans even though it was never imported.
    const bytes = await driveRequestBytes(
      accessToken,
      `${DRIVE_API}/files/${encodeURIComponent(file.id)}?alt=media`,
    );

    let batch: WireBatch;
    try {
      batch = await ungzipJson(bytes);
    } catch (err) {
      // Malformed content is permanent: record this file as consumed so one bad
      // payload cannot block every later valid batch forever.
      console.warn(`[Tempo] Skipping malformed Drive history batch ${file.name}`, err);
      maxCreated = Math.max(maxCreated, created);
      continue;
    }
    if (!isValidBatch(batch) || batch.source_device_id === deviceId) {
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    for (const event of batch.events) {
      if (!isValidEvent(event)) continue;
      if (seenOriginIds.has(event.event_id)) {
        duplicates++;
        continue;
      }

      // The wide temporal fallback is only for two different capture origins
      // observing the same physical play. Two distinct event IDs from the same
      // originating device are legitimate replays and must both survive.
      const temporalDupe = await storage.hasRecentPlay(
        event.title,
        event.artist,
        event.timestamp_utc,
        batch.source_device_id,
      );
      if (temporalDupe) {
        seenOriginIds.add(event.event_id);
        duplicates++;
        continue;
      }

      const play: Omit<Play, 'id'> = {
        title: event.title,
        artist: event.artist,
        album: event.album ?? '',
        durationMs: Math.max(0, event.duration_ms || 0),
        timestampUtc: event.timestamp_utc,
        sourceApp: event.source_app || event.source || 'Drive',
        status: 'synced',
        listenedMs: Math.max(0, event.listened_ms || 0),
        skipped: !!event.skipped,
        replayCount: Math.max(0, event.replay_count || 0),
        isMuted: event.volume_level === 0,
        completionPercentage: clamp(event.completion_percentage || 0, 0, 100),
        pauseCount: Math.max(0, event.pause_count || 0),
        seekCount: Math.max(0, event.seek_count || 0),
        sessionId: event.session_id ?? '',
        site: event.site ?? '',
        contentType: event.content_type || 'MUSIC',
        volumeLevel: event.volume_level ?? -1,
        anomalies: [],
        totalPauseDurationMs: Math.max(0, event.total_pause_duration_ms || 0),
        positionUpdatesCount: Math.max(0, event.position_updates_count || 0),
        driveImported: true,
        originEventId: event.event_id,
        originDeviceId: batch.source_device_id,
        driveUploadedAt: Date.now(),
      };
      await storage.insertPlay(play);
      seenOriginIds.add(event.event_id);
      imported++;
    }
    maxCreated = Math.max(maxCreated, created);
  }

  if (maxCreated > state.downloadCreatedCursor) {
    await patchRuntimeState({ downloadCreatedCursor: maxCreated });
  }
  return { imported, duplicates };
}

/**
 * Publish a new server-timestamped generation first, then remove only older
 * generations and turn Drive sync off locally. A client deliberately re-enabled
 * after the marker update can safely seed the new generation immediately.
 */
export async function deleteDriveHistory(): Promise<number> {
  if (syncPromise) await syncPromise.catch(() => undefined);
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);

  try {
    const session = await getDriveAuthSession(true);
    if (!session) throw new Error('Google Drive sign-in is required');
    const markerVersion = await bumpDisableMarker(session.accessToken);
    const deleted = await deleteBatchesBeforeGeneration(session.accessToken, markerVersion);

    const settings = await storage.getSettings();
    await storage.saveSettings({ ...settings, driveSyncEnabled: false });
    const state = await getRuntimeState();
    await saveRuntimeState({
      ...state,
      acceptedDisableVersion: markerVersion,
      downloadCreatedCursor: 0,
      lastError: null,
      lastUploaded: 0,
      lastImported: 0,
    });
    await storage.clearDriveUploadedFlags();
    return deleted;
  } catch (err) {
    // If the marker was already published but deletion failed, leave the feature
    // enabled and scheduled. The next run will see the newer marker, finish the
    // cleanup, and then disable itself.
    await initDriveHistorySync().catch(() => undefined);
    throw err;
  }
}

async function uploadBatch(
  accessToken: string,
  fileName: string,
  gzip: Uint8Array,
  deviceId: string,
  generation: number,
): Promise<void> {
  if (await findBatchByExactName(accessToken, fileName)) return;

  const boundary = `tempo_${crypto.randomUUID().replace(/-/g, '')}`;
  const metadata = JSON.stringify({
    name: fileName,
    parents: ['appDataFolder'],
    appProperties: {
      tempo_kind: 'history_batch',
      tempo_schema: String(SCHEMA_VERSION),
      source_device_id: deviceId,
      source_platform: isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension',
      [APP_PROPERTY_GENERATION]: String(generation),
    },
  });
  const body = new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n`,
    `--${boundary}\r\nContent-Type: application/gzip\r\n\r\n`,
    gzip as BlobPart,
    `\r\n--${boundary}--\r\n`,
  ]);
  const response = await fetch(
    `${DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,createdTime,appProperties`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': `multipart/related; boundary=${boundary}`,
      },
      body,
    },
  );
  await assertDriveResponse(response);
}

async function findBatchByExactName(accessToken: string, fileName: string): Promise<boolean> {
  return !!(await findFileByExactName(accessToken, fileName, 'files(id)'));
}

async function findFileByExactName(
  accessToken: string,
  fileName: string,
  fields = 'files(id,name,modifiedTime,appProperties)',
): Promise<DriveFileRecord | null> {
  const escaped = fileName.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  const params = new URLSearchParams({
    spaces: 'appDataFolder',
    q: `name = '${escaped}' and trashed = false`,
    pageSize: '1',
    fields,
  });
  const response = await fetch(`${DRIVE_API}/files?${params.toString()}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  await assertDriveResponse(response);
  const data = await response.json() as { files?: DriveFileRecord[] };
  return data.files?.[0] ?? null;
}

async function getDisableMarkerVersion(accessToken: string): Promise<number> {
  const file = await findFileByExactName(accessToken, DISABLE_MARKER_NAME);
  if (!file) return 0;
  const parsed = file.modifiedTime ? Date.parse(file.modifiedTime) : NaN;
  if (!Number.isFinite(parsed)) {
    throw new Error('Google Drive did not return a valid deletion marker version');
  }
  return parsed;
}

async function bumpDisableMarker(accessToken: string): Promise<number> {
  const existing = await findFileByExactName(accessToken, DISABLE_MARKER_NAME);
  let response: Response;
  if (existing) {
    response = await fetch(
      `${DRIVE_API}/files/${encodeURIComponent(existing.id)}?fields=id,name,modifiedTime,appProperties`,
      {
        method: 'PATCH',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          appProperties: {
            tempo_kind: 'history_sync_control',
            revision: crypto.randomUUID(),
          },
        }),
      },
    );
  } else {
    response = await fetch(`${DRIVE_API}/files?fields=id,name,modifiedTime,appProperties`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        name: DISABLE_MARKER_NAME,
        parents: ['appDataFolder'],
        appProperties: {
          tempo_kind: 'history_sync_control',
          revision: crypto.randomUUID(),
        },
      }),
    });
  }
  await assertDriveResponse(response);
  const file = await response.json() as DriveFileRecord;
  const parsed = file.modifiedTime ? Date.parse(file.modifiedTime) : NaN;
  if (!Number.isFinite(parsed)) throw new Error('Google Drive did not return a valid deletion marker version');
  return parsed;
}

async function listBatches(accessToken: string, createdAfter: number | null): Promise<DriveFileRecord[]> {
  const clauses = [`name contains '${FILE_PREFIX}'`, 'trashed = false'];
  if (createdAfter != null && createdAfter > 0) {
    clauses.push(`createdTime > '${new Date(createdAfter).toISOString()}'`);
  }

  const files: DriveFileRecord[] = [];
  let pageToken: string | null = null;
  do {
    const params = new URLSearchParams({
      spaces: 'appDataFolder',
      q: clauses.join(' and '),
      orderBy: 'createdTime asc',
      pageSize: '1000',
      fields: 'nextPageToken,files(id,name,size,createdTime,modifiedTime,appProperties)',
    });
    if (pageToken) params.set('pageToken', pageToken);
    const response = await fetch(`${DRIVE_API}/files?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    await assertDriveResponse(response);
    const data = await response.json() as { nextPageToken?: string; files?: DriveFileRecord[] };
    files.push(...(data.files ?? []));
    pageToken = data.nextPageToken ?? null;
  } while (pageToken);
  return files;
}

function batchGeneration(file: DriveFileRecord): number {
  const raw = Number(file.appProperties?.[APP_PROPERTY_GENERATION] ?? '0');
  return Number.isFinite(raw) && raw >= 0 ? raw : 0;
}

async function deleteDriveFileStrict(accessToken: string, fileId: string): Promise<void> {
  const response = await fetch(`${DRIVE_API}/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (response.status !== 404) await assertDriveResponse(response);
}

async function deleteBatchesBeforeGeneration(accessToken: string, generation: number): Promise<number> {
  const files = await listBatches(accessToken, null);
  let deleted = 0;
  for (const file of files) {
    if (batchGeneration(file) >= generation) continue;
    await deleteDriveFileStrict(accessToken, file.id);
    deleted++;
  }
  return deleted;
}

async function driveRequestBytes(accessToken: string, url: string): Promise<Uint8Array> {
  const response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  await assertDriveResponse(response);
  return readStreamBytesWithLimit(response.body, MAX_BATCH_BYTES, 'compressed');
}

async function readStreamBytesWithLimit(
  stream: ReadableStream<Uint8Array> | null,
  maxBytes: number,
  label: string,
): Promise<Uint8Array> {
  if (!stream) throw new Error(`Google Drive returned an empty ${label} history stream`);

  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => undefined);
        throw new Error(`Tempo Drive history batch exceeds the ${label} size limit`);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

async function assertDriveResponse(response: Response): Promise<void> {
  if (response.ok) return;
  let detail = '';
  try {
    const json = await response.json() as any;
    detail = json?.error?.message ?? '';
  } catch { /* response body may not be JSON */ }

  if (response.status === 401 || response.status === 403) {
    throw new Error(`Google Drive authorization failed${detail ? `: ${detail}` : ''}`);
  }
  if (response.status === 429 || response.status >= 500) {
    throw new Error(`Google Drive is temporarily unavailable (HTTP ${response.status})`);
  }
  throw new Error(`Google Drive request failed (HTTP ${response.status})${detail ? `: ${detail}` : ''}`);
}

async function playToWire(play: Play, deviceId: string): Promise<WireEvent> {
  const id = play.originEventId ?? await eventId(deviceId, play);
  return {
    event_id: id,
    title: play.title,
    artist: play.artist,
    album: play.album || null,
    timestamp_utc: play.timestampUtc,
    duration_ms: Math.max(0, Math.round(play.durationMs || 0)),
    listened_ms: Math.max(0, Math.round(play.listenedMs || 0)),
    source_app: play.sourceApp || 'browser',
    source: `browser:${play.sourceApp || 'unknown'}`,
    skipped: !!play.skipped,
    replay_count: Math.max(0, Math.round(play.replayCount || 0)),
    completion_percentage: Math.round(clamp(play.completionPercentage || 0, 0, 100)),
    pause_count: Math.max(0, Math.round(play.pauseCount || 0)),
    seek_count: Math.max(0, Math.round(play.seekCount || 0)),
    session_id: play.sessionId || null,
    site: play.site || null,
    content_type: play.contentType || 'MUSIC',
    volume_level: protocolVolumeLevel(play),
    total_pause_duration_ms: Math.max(0, Math.round(play.totalPauseDurationMs || 0)),
    position_updates_count: Math.max(0, Math.round(play.positionUpdatesCount || 0)),
  };
}

function protocolVolumeLevel(play: Play): number | null {
  if (play.isMuted) return 0;
  const value = play.volumeLevel;
  if (!Number.isFinite(value) || value < 0) return null;
  if (value <= 1) return value <= 0.01 ? 0 : Math.max(1, Math.round(value * 100));
  return Math.max(1, Math.round(value));
}

async function eventId(deviceId: string, play: Play): Promise<string> {
  const canonical = [
    'tempo-history-v1',
    deviceId,
    String(play.id ?? 0),
    String(play.timestampUtc),
    play.title.trim().toLowerCase(),
    play.artist.trim().toLowerCase(),
  ].join('|');
  return sha256(canonical);
}

async function deterministicBatchId(events: WireEvent[]): Promise<string> {
  if (events.length === 0) throw new Error('A history batch cannot be empty');
  return sha256(`tempo-batch-v1|${events.map(event => event.event_id).join('|')}`);
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
}

async function gzipJson(value: unknown): Promise<Uint8Array> {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  if (bytes.byteLength > MAX_BATCH_BYTES) {
    throw new Error('Tempo Drive history batch exceeds the decompressed size limit');
  }
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new CompressionStream('gzip'));
  const compressed = new Uint8Array(await new Response(stream).arrayBuffer());
  if (compressed.byteLength > MAX_BATCH_BYTES) {
    throw new Error('Tempo Drive history batch exceeds the compressed size limit');
  }
  return compressed;
}

async function ungzipJson(bytes: Uint8Array): Promise<WireBatch> {
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
  const decoded = await readStreamBytesWithLimit(stream, MAX_BATCH_BYTES, 'decompressed');
  const text = new TextDecoder().decode(decoded);
  return JSON.parse(text) as WireBatch;
}

function isValidBatch(value: unknown): value is WireBatch {
  if (!value || typeof value !== 'object') return false;
  const batch = value as Partial<WireBatch>;
  return batch.schema_version === SCHEMA_VERSION &&
    typeof batch.batch_id === 'string' && batch.batch_id.length > 0 &&
    typeof batch.source_device_id === 'string' && batch.source_device_id.length > 0 &&
    Array.isArray(batch.events) && batch.events.length <= 1000;
}

function isValidEvent(value: unknown): value is WireEvent {
  if (!value || typeof value !== 'object') return false;
  const event = value as Partial<WireEvent>;
  return typeof event.event_id === 'string' && event.event_id.length > 0 &&
    typeof event.title === 'string' && event.title.trim().length > 0 && event.title.length <= 1000 &&
    typeof event.artist === 'string' && event.artist.trim().length > 0 && event.artist.length <= 1000 &&
    typeof event.timestamp_utc === 'number' && Number.isFinite(event.timestamp_utc) && event.timestamp_utc > 0;
}

async function getDeviceId(): Promise<string> {
  const result = await chrome.storage.local.get(DEVICE_KEY);
  const existing = result[DEVICE_KEY];
  if (typeof existing === 'string' && existing) return existing;
  const created = crypto.randomUUID();
  await chrome.storage.local.set({ [DEVICE_KEY]: created });
  return created;
}

async function getRuntimeState(): Promise<DriveRuntimeState> {
  const result = await chrome.storage.local.get(STATE_KEY);
  const raw = result[STATE_KEY] as Partial<DriveRuntimeState> | undefined;
  return { ...DEFAULT_STATE, ...(raw ?? {}) };
}

async function saveRuntimeState(state: DriveRuntimeState): Promise<void> {
  await chrome.storage.local.set({ [STATE_KEY]: state });
}

async function patchRuntimeState(patch: Partial<DriveRuntimeState>): Promise<void> {
  const current = await getRuntimeState();
  await saveRuntimeState({ ...current, ...patch });
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
