import type { DriveSyncStatus, Play } from '../shared/types';
import * as storage from './storage';
import {
  disconnectDriveAuth,
  getDriveAuthSession,
  hasFirefoxDriveDataConsent,
  isDriveOAuthConfigured,
  isFirefoxBuild,
} from './drive-auth';
import {
  PermanentBatchError,
  assertDriveResponse,
  driveFetch,
  fetchWithDeadline,
  isDriveRequestRetrySafe,
  delayMs,
  readStreamBytesWithLimit,
} from './drive-transport';
import {
  WireEvent,
  WireBatch,
  SCHEMA_VERSION,
  MAX_BATCH_BYTES,
  DEVICE_ID_PATTERN,
  playToWire,
  localVolumeFromWire,
  eventId,
  deterministicBatchId,
  sha256Bytes,
  gzipJson,
  ungzipJson,
  isValidBatch,
  isValidEvent,
  clamp,
} from './drive-protocol';

const DRIVE_API = 'https://www.googleapis.com/drive/v3';
const DRIVE_UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3';
const FILE_PREFIX = 'tempo_history_v1_';
const DISABLE_MARKER_NAME = 'tempo_history_control_v1.json';
const APP_PROPERTY_GENERATION = 'tempo_generation';
const APP_PROPERTY_SHA256 = 'tempo_sha256';
const BATCH_SIZE = 50;
const MAX_LOCAL_SCAN = 5000;
const DOWNLOAD_OVERLAP_MS = 24 * 60 * 60 * 1000;
const STATE_KEY = 'tempoDriveHistoryState';
const DEVICE_KEY = 'tempoDriveDeviceId';
export const DRIVE_SYNC_ALARM_NAME = 'tempo-drive-history-sync';

export interface AccountProgress {
  downloadCreatedCursor: number;
  acceptedDisableVersion: number;
}

export interface RejectedBatchRecord {
  fileId: string;
  fileName: string;
  reason: string;
  recordedAt: number;
}

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
  lastAuthorizedAccountSubject: string | null;
  accountProgress?: Record<string, AccountProgress>;
  rejectedBatches?: Record<string, RejectedBatchRecord[]>;
}

interface DriveFileRecord {
  id: string;
  name: string;
  size?: string;
  createdTime?: string;
  modifiedTime?: string;
  md5Checksum?: string;
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
  lastAuthorizedAccountSubject: null,
  accountProgress: {},
  rejectedBatches: {},
};

function markRejectedBatchRecord(
  batches: Record<string, RejectedBatchRecord[]> | undefined,
  accountSubject: string,
  fileId: string,
  fileName: string,
  reason: string,
): Record<string, RejectedBatchRecord[]> {
  const result: Record<string, RejectedBatchRecord[]> = { ...(batches ?? {}) };
  const currentList = (result[accountSubject] ?? []).filter(item => item.fileId !== fileId);
  currentList.push({
    fileId,
    fileName,
    reason,
    recordedAt: Date.now(),
  });
  result[accountSubject] = currentList;
  return result;
}

function clearRejectedBatchRecord(
  batches: Record<string, RejectedBatchRecord[]> | undefined,
  accountSubject: string,
  fileId: string,
): Record<string, RejectedBatchRecord[]> {
  if (!batches || !batches[accountSubject]) return batches ?? {};
  const currentList = batches[accountSubject].filter(item => item.fileId !== fileId);
  return { ...batches, [accountSubject]: currentList };
}

function clearMissingRejectedBatchRecords(
  batches: Record<string, RejectedBatchRecord[]> | undefined,
  accountSubject: string,
  liveFileIds: Set<string>,
): Record<string, RejectedBatchRecord[]> {
  if (!batches || !batches[accountSubject]) return batches ?? {};
  const currentList = batches[accountSubject].filter(item => liveFileIds.has(item.fileId));
  return { ...batches, [accountSubject]: currentList };
}

let syncPromise: Promise<{ uploaded: number; imported: number; duplicates: number }> | null = null;
let deviceIdPromise: Promise<string> | null = null;
let driveOperationTail: Promise<void> = Promise.resolve();

/**
 * Serialize sync, connect, disconnect, and cloud deletion. Chrome alarms and
 * popup commands can arrive in the same service-worker turn; without a shared
 * queue an upload from the old generation could finish after deletion and be
 * marked locally as current-generation data even though readers must ignore it.
 */
function serializeDriveOperation<T>(operation: () => Promise<T>): Promise<T> {
  const result = driveOperationTail.then(operation, operation);
  driveOperationTail = result.then(() => undefined, () => undefined);
  return result;
}

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

function verifiedAccountChanged(previous: string | null, current: string | null): boolean {
  return !!previous && !!current && previous !== current;
}

/**
 * Explicit opt-in. A shared cloud-deletion marker is acknowledged only here,
 * never silently by an alarm. If the marker advanced while this browser was
 * offline/disabled, suppress old local plays; reconnecting alone is not
 * permission to republish a deleted Drive archive.
 *
 * Drive cursors are also account-scoped. Switching Google accounts resets all
 * Drive-only cursors/flags before the new account is accepted explicitly.
 */
export function connectDrive(): Promise<DriveSyncStatus> {
  return serializeDriveOperation(connectDriveUnlocked);
}

async function connectDriveUnlocked(): Promise<DriveSyncStatus> {
  if (!isDriveOAuthConfigured()) throw new Error('Google Drive OAuth is not configured in this build');
  if (isFirefoxBuild() && !(await hasFirefoxDriveDataConsent())) {
    throw new Error('Allow the optional Firefox data-sharing permission before connecting Google Drive');
  }

  const session = await getDriveAuthSession(true);
  if (!session) throw new Error('Google Drive sign-in was cancelled or unavailable');

  const state = await getRuntimeState();
  const currentAccount = session.accountSubject;
  const previousAccount = state.lastAuthorizedAccountSubject;
  const previousEmail = normalizeAccountEmail(state.lastAuthorizedAccountEmail);
  const accountChanged = verifiedAccountChanged(previousAccount, currentAccount) ||
    (!previousAccount && !!previousEmail && previousEmail !== normalizeAccountEmail(session.accountEmail));
  // Attribute existing unowned rows to their last verified identity before switch.
  await storage.claimUnownedDrivePlays(previousAccount ??
    (!previousEmail || previousEmail === normalizeAccountEmail(session.accountEmail)
      ? currentAccount : 'legacy-unverified'));

  const progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
  if (previousAccount) {
    progressMap[previousAccount] = {
      downloadCreatedCursor: state.downloadCreatedCursor,
      acceptedDisableVersion: state.acceptedDisableVersion,
    };
  }

  const currentProgress = progressMap[currentAccount] ?? { downloadCreatedCursor: 0, acceptedDisableVersion: 0 };
  let acceptedDisableVersion = currentProgress.acceptedDisableVersion;
  let downloadCreatedCursor = currentProgress.downloadCreatedCursor;
  // Preserve previous account's upload status when switching identities.

  const markerVersion = await getDisableMarkerVersion(session.accessToken);
  if (markerVersion > acceptedDisableVersion) {
    await storage.suppressDeletedDriveHistory(currentAccount, markerVersion);
    downloadCreatedCursor = 0;
  }
  acceptedDisableVersion = Math.max(acceptedDisableVersion, markerVersion);
  progressMap[currentAccount] = { downloadCreatedCursor, acceptedDisableVersion };

  await saveRuntimeState({
    ...state,
    acceptedDisableVersion,
    downloadCreatedCursor,
    accountProgress: progressMap,
    accountEmail: session.accountEmail,
    lastAuthorizedAccountEmail: session.accountEmail,
    lastAuthorizedAccountSubject: currentAccount,
    lastError: null,
    lastUploaded: accountChanged ? 0 : state.lastUploaded,
    lastImported: accountChanged ? 0 : state.lastImported,
  });

  const settings = await storage.getSettings();
  await storage.saveSettings({ ...settings, driveSyncEnabled: true });
  await initDriveHistorySync();
  // We already own the lifecycle queue; calling the public serialized function
  // here would enqueue behind ourselves and deadlock.
  await runSync({ interactiveAuth: false });
  return getDriveSyncStatus();
}

export function disconnectDrive(): Promise<DriveSyncStatus> {
  return serializeDriveOperation(disconnectDriveUnlocked);
}

async function disconnectDriveUnlocked(): Promise<DriveSyncStatus> {
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

  const currentSubject = state.lastAuthorizedAccountSubject;
  const rejectedList: RejectedBatchRecord[] = (currentSubject && state.rejectedBatches?.[currentSubject])
    ? state.rejectedBatches[currentSubject]
    : [];
  let visibleError = state.lastError;
  if (!visibleError && rejectedList.length > 0) {
    const sample = rejectedList[0];
    visibleError = `${rejectedList.length} invalid Google Drive history batch(es) were skipped (e.g. ${sample.fileName}: ${sample.reason}). Repair or remove the affected files, then use Restore all history.`;
  }

  return {
    enabled: !!settings.driveSyncEnabled,
    configured,
    connected,
    accountEmail: visibleAccountEmail,
    lastSyncTime: state.lastSyncTime,
    lastError: visibleError,
    lastUploaded: state.lastUploaded,
    lastImported: state.lastImported,
    needsInteractiveAuth,
  };
}

/** Explicit recovery mode: re-enumerate all appDataFolder history batches.
 * The normal created-time cursor remains inexpensive for periodic sync, while
 * this operation deliberately recovers old files after local data loss.
 * The cursor reset is committed before the scan so an interrupted restoration
 * is retried rather than marking old history as consumed.
 */
export function restoreDriveHistory(): Promise<{ uploaded: number; imported: number; duplicates: number }> {
  return serializeDriveOperation(async () => {
    const state = await getRuntimeState();
    const subject = state.lastAuthorizedAccountSubject;
    const progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
    if (subject) {
      progressMap[subject] = {
        downloadCreatedCursor: 0,
        acceptedDisableVersion: state.acceptedDisableVersion,
      };
    }
    await patchRuntimeState({ downloadCreatedCursor: 0, accountProgress: progressMap });
    return runSync({ interactiveAuth: true, fullRestore: true });
  });
}

export async function syncDriveHistory(
  options: { interactiveAuth?: boolean } = {},
): Promise<{ uploaded: number; imported: number; duplicates: number }> {
  if (syncPromise) return syncPromise;
  syncPromise = serializeDriveOperation(() => runSync(options))
    .finally(() => { syncPromise = null; });
  return syncPromise;
}

async function runSync(
  options: { interactiveAuth?: boolean; fullRestore?: boolean },
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
    const currentAccount = session.accountSubject;
    const previousAccount = state.lastAuthorizedAccountSubject;

    if (verifiedAccountChanged(previousAccount, currentAccount)) {
      // Never write to a different account automatically. Clear only Drive-side
      // bookkeeping; the user's local listening history is untouched.
      await storage.claimUnownedDrivePlays(previousAccount!);
      await storage.saveSettings({ ...settings, driveSyncEnabled: false });
      await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
      const progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
      if (previousAccount) {
        progressMap[previousAccount] = {
          downloadCreatedCursor: state.downloadCreatedCursor,
          acceptedDisableVersion: state.acceptedDisableVersion,
        };
      }
      const currentProgress = progressMap[currentAccount] ?? { downloadCreatedCursor: 0, acceptedDisableVersion: 0 };
      const message = 'Google account changed. Cross-device sync was turned off; connect Google again to use the new Drive account.';
      await saveRuntimeState({
        ...state,
        downloadCreatedCursor: currentProgress.downloadCreatedCursor,
        acceptedDisableVersion: currentProgress.acceptedDisableVersion,
        accountProgress: progressMap,
        accountEmail: session.accountEmail,
        lastAuthorizedAccountEmail: session.accountEmail,
        lastAuthorizedAccountSubject: currentAccount,
        lastUploaded: 0,
        lastImported: 0,
        lastError: message,
      });
      throw new Error(message);
    }

    if (!previousAccount && currentAccount) {
      const legacyEmail = normalizeAccountEmail(state.lastAuthorizedAccountEmail);
      const legacyMatches = !legacyEmail ||
        legacyEmail === normalizeAccountEmail(session.accountEmail);
      await storage.claimUnownedDrivePlays(legacyMatches ? currentAccount : 'legacy-unverified');
      await patchRuntimeState({
        accountEmail: session.accountEmail,
        lastAuthorizedAccountEmail: session.accountEmail,
        lastAuthorizedAccountSubject: currentAccount,
      });
    }

    if (await honorRemoteDisableIfNeeded(session.accessToken)) {
      return { uploaded: 0, imported: 0, duplicates: 0 };
    }

    const deviceId = await getDeviceId();
    const upload = await uploadLocalPlays(session.accessToken, deviceId, currentAccount)
      .then(uploaded => ({ uploaded, error: null as Error | null }))
      .catch(error => ({ uploaded: 0, error: error instanceof Error ? error : new Error(String(error)) }));
    const download = await downloadRemotePlays(session.accessToken, deviceId,
      options.fullRestore === true, currentAccount);
    if (upload.error) throw upload.error;
    await patchRuntimeState({
      lastSyncTime: Date.now(),
      lastError: null,
      lastUploaded: upload.uploaded,
      lastImported: download.imported,
      accountEmail: session.accountEmail,
      lastAuthorizedAccountEmail: session.accountEmail,
      lastAuthorizedAccountSubject: currentAccount,
    });
    return { uploaded: upload.uploaded, imported: download.imported, duplicates: download.duplicates };
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

  const owner = state.lastAuthorizedAccountSubject;
  if (!owner) throw new Error('No verified Google account is bound to this Drive sync');
  await stopForDeletionMarker(state, markerVersion,
    'Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history.', owner);
  await deleteBatchesBeforeGeneration(accessToken, markerVersion);
  return true;
}

async function stopForDeletionMarker(
  state: DriveRuntimeState, markerVersion: number, message: string | null,
  accountSubject: string,
): Promise<void> {
  const settings = await storage.getSettings();
  await storage.saveSettings({ ...settings, driveSyncEnabled: false });
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);
  await storage.suppressDeletedDriveHistory(accountSubject, markerVersion);
  const progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
  progressMap[accountSubject] = {
    downloadCreatedCursor: 0,
    acceptedDisableVersion: markerVersion,
  };
  await saveRuntimeState({
    ...state,
    acceptedDisableVersion: markerVersion,
    downloadCreatedCursor: 0,
    accountProgress: progressMap,
    lastUploaded: 0,
    lastImported: 0,
    lastError: message,
  });
}

/**
 * Drive ownership is independent from the existing LAN status flag. A play can
 * already be marked `synced` to the phone and still need its first Drive upload.
 */
async function uploadLocalPlays(accessToken: string, deviceId: string, accountSubject: string): Promise<number> {
  const state = await getRuntimeState();
  const generation = Math.max(0, state.acceptedDisableVersion || 0);
  let uploaded = 0;
  let skipped = 0;
  let cursor: { timestampUtc: number; id: number } | undefined;
  // Read bounded keyset pages. Invalid rows stay in IndexedDB; scanning can
  // advance past them without losing legitimate plays recorded afterwards.
  while (uploaded < MAX_LOCAL_SCAN) {
    const pending = (await storage.getDrivePendingPlays(MAX_LOCAL_SCAN, accountSubject, cursor))
      .sort((a, b) => a.timestampUtc - b.timestampUtc || (a.id ?? 0) - (b.id ?? 0));
    if (pending.length === 0) break;
    const last = pending[pending.length - 1];
    cursor = { timestampUtc: last.timestampUtc, id: last.id! };

    for (let offset = 0; offset < pending.length; offset += BATCH_SIZE) {
      const chunk = pending.slice(offset, offset + BATCH_SIZE);
      const events: WireEvent[] = [];
      const valid: Play[] = [];
      for (const play of chunk) {
        try {
          if (play.id == null) throw new Error('A local play lacks its persistent ID');
          const candidate = play.originEventId ?? await eventId(deviceId, play);
          const pinnedId = await storage.ensureLocalOriginEventId(play.id, candidate, accountSubject);
          const wire = await playToWire({ ...play, originEventId: pinnedId }, deviceId);
          events.push(wire);
          valid.push(play);
        } catch (err) {
          skipped++;
          console.warn('[Tempo] Retaining invalid local Drive play', play.id, err);
        }
      }
      if (events.length === 0) continue;
      const batchId = await deterministicBatchId(events);
      const batch: WireBatch = {
        schema_version: SCHEMA_VERSION,
        batch_id: batchId,
        source_device_id: deviceId,
        source_device_name: isFirefoxBuild() ? 'Firefox extension' : 'Chrome extension',
        source_platform: isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension',
        created_at_utc: Math.max(...events.map(event => event.timestamp_utc)),
        events,
      };
      if (!isValidBatch(batch)) throw new Error('Unexpected malformed browser Drive batch');
      const gzip = await gzipJson(batch);
      const fileName = `${FILE_PREFIX}g${generation}_${deviceId}_${batchId}.json.gz`;
      await uploadBatch(accessToken, fileName, gzip, deviceId, generation);
      await storage.markDriveUploaded(valid.map((play, index) => ({
        id: play.id!, originEventId: events[index].event_id,
      })));
      uploaded += valid.length;
    }
    if (pending.length < MAX_LOCAL_SCAN) break;
  }
  if (skipped > 0) {
    console.warn(`[Tempo] ${skipped} invalid local plays were preserved for metadata repair`);
  }
  return uploaded;
}

async function downloadRemotePlays(
  accessToken: string,
  deviceId: string,
  includeOwnDeviceBatches = false,
  accountSubject?: string,
): Promise<{ imported: number; duplicates: number }> {
  const state = await getRuntimeState();
  const acceptedGeneration = Math.max(0, state.acceptedDisableVersion || 0);
  const after = state.downloadCreatedCursor > 0
    ? Math.max(0, state.downloadCreatedCursor - DOWNLOAD_OVERLAP_MS)
    : null;
  const files = await listBatches(accessToken, after);

  // Exact origin IDs are the idempotency key. The plays store may temporarily
  // exceed the normal 5k retention cap while locally-owned rows are waiting for
  // their first Drive upload, so sample-based dedup can miss an older imported
  // event. Scan the whole local store once per Drive download pass instead.
  if (!accountSubject) throw new Error('Verified Google subject is required to import Drive history');
  // A createdTime-filtered listing cannot prove an old rejected batch was
  // deleted. Retain its warning until a full scan or successful reimport.
  let rejectedMap = after == null || after <= 0
    ? clearMissingRejectedBatchRecords(state.rejectedBatches, accountSubject, new Set(files.map(f => f.id)))
    : (state.rejectedBatches ?? {});
  let progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
  // Own-device full restore may need to reconstruct IDs for older unpinned
  // local captures. Normal sync uses the durable IDB provenance index.
  const seenOwnOrigins = new Set<string>();
  if (includeOwnDeviceBatches) {
    // Locally recorded plays usually do NOT persist their own hashed origin ID.
    // Reconstruct those IDs before a full restore so reading this device's
    // cloud batches cannot import a second copy of every surviving local play.
    // Do this only for explicit recovery, not for each periodic background sync.
    const ownPlays = await storage.getOwnPlayIdentityInputs(accountSubject);
    for (let offset = 0; offset < ownPlays.length; offset += 256) {
      const chunk = ownPlays.slice(offset, offset + 256);
      const origins = await Promise.all(chunk.map(play => eventId(deviceId, play)));
      for (const originId of origins) seenOwnOrigins.add(originId);
    }
  }
  let imported = 0;
  let duplicates = 0;
  let maxCreated = state.downloadCreatedCursor;

  for (const [index, file] of files.entries()) {
    // Full-history restores can span thousands of files. Persist progress every
    // 50 consumed batches so worker suspension/restart resumes near the point
    // of interruption instead of re-reading ten years from zero.
    if (index > 0 && index % 50 === 0 && maxCreated > state.downloadCreatedCursor) {
      progressMap[accountSubject] = {
        downloadCreatedCursor: maxCreated,
        acceptedDisableVersion: acceptedGeneration,
      };
      await patchRuntimeState({
        downloadCreatedCursor: maxCreated,
        accountProgress: progressMap,
        rejectedBatches: rejectedMap,
      });
    }
    const createdRaw = file.createdTime ? Date.parse(file.createdTime) : 0;
    const created = Number.isFinite(createdRaw) ? createdRaw : 0;
    const generation = batchGeneration(file);
    if (generation == null) {
      console.warn(`[Tempo] Skipping Drive history batch with invalid generation ${file.name}`);
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, 'Invalid or missing generation');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }
    if (generation < acceptedGeneration) {
      if (!shouldDeleteHistoryBatch(file, acceptedGeneration, false)) {
        rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name,
          'Generation metadata conflicts with newer batch filename');
        maxCreated = Math.max(maxCreated, created);
        continue;
      }
      // A pre-delete/in-flight stale batch must never resurrect after generation
      // N was accepted. Cleanup is best effort so current-generation sync can
      // continue even if this stale file races another client's deletion.
      await deleteDriveFileStrict(accessToken, file.id).catch(err => {
        console.warn(`[Tempo] Could not remove stale Drive history batch ${file.name}`, err);
      });
      rejectedMap = clearRejectedBatchRecord(rejectedMap, accountSubject, file.id);
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    const sourceDeviceId = file.appProperties?.source_device_id;
    const sourcePlatform = file.appProperties?.source_platform;
    const checksum = file.appProperties?.[APP_PROPERTY_SHA256];
    const metadataValid = file.appProperties?.tempo_kind === 'history_batch' &&
      file.appProperties?.tempo_schema === String(SCHEMA_VERSION) &&
      typeof sourceDeviceId === 'string' && DEVICE_ID_PATTERN.test(sourceDeviceId) &&
      typeof sourcePlatform === 'string' && PLATFORM_PATTERN.test(sourcePlatform) &&
      typeof checksum === 'string' && SHA256_PATTERN.test(checksum);
    if (!metadataValid) {
      console.warn(`[Tempo] Skipping Drive history batch with invalid metadata ${file.name}`);
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, 'Invalid Tempo metadata');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    if (!includeOwnDeviceBatches && sourceDeviceId === deviceId) {
      rejectedMap = clearRejectedBatchRecord(rejectedMap, accountSubject, file.id);
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    const declaredSize = Number(file.size ?? '0');
    if (!Number.isSafeInteger(declaredSize) || declaredSize <= 0 || declaredSize > MAX_BATCH_BYTES) {
      console.warn(`[Tempo] Skipping Drive history batch with invalid size ${file.name}`);
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, 'Invalid file size');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    // Fetch errors are retryable and must abort without advancing the cursor.
    // Otherwise a temporarily unavailable batch can fall outside the 24h overlap
    // and disappear from future scans even though it was never imported.
    let bytes: Uint8Array;
    try {
      bytes = await driveRequestBytes(accessToken, file);
    } catch (err) {
      if (err instanceof PermanentBatchError) {
        console.warn(`[Tempo] Skipping corrupt Drive history batch ${file.name}`, err);
        rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, err.message || 'Corrupt batch payload');
        maxCreated = Math.max(maxCreated, created);
        continue;
      }
      throw err;
    }

    let batch: unknown;
    try {
      batch = await ungzipJson(bytes);
    } catch (err) {
      // Malformed content is permanent: record this file as consumed so one bad
      // payload cannot block every later valid batch forever.
      console.warn(`[Tempo] Skipping malformed Drive history batch ${file.name}`, err);
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, err instanceof Error ? err.message : 'Malformed gzip or JSON');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }
    if (!isValidBatch(batch) || await deterministicBatchId(batch.events) !== batch.batch_id) {
      console.warn(`[Tempo] Skipping invalid Drive history batch ${file.name}`);
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, 'Batch payload verification failed');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }
    const expectedName = `${FILE_PREFIX}g${generation}_${sourceDeviceId}_${batch.batch_id}.json.gz`;
    if (batch.source_device_id !== sourceDeviceId ||
      batch.source_platform !== sourcePlatform ||
      file.name !== expectedName
    ) {
      rejectedMap = markRejectedBatchRecord(rejectedMap, accountSubject, file.id, file.name, 'Batch payload does not match Drive metadata');
      maxCreated = Math.max(maxCreated, created);
      continue;
    }
    if (!includeOwnDeviceBatches && batch.source_device_id === deviceId) {
      rejectedMap = clearRejectedBatchRecord(rejectedMap, accountSubject, file.id);
      maxCreated = Math.max(maxCreated, created);
      continue;
    }

    for (const event of batch.events) {
      if (!isValidEvent(event)) continue;
      if (seenOwnOrigins.has(event.event_id) ||
          await storage.hasDriveOriginEventId(event.event_id, accountSubject)) {
        duplicates++;
        continue;
      }

      // The wide temporal fallback is only for two different capture origins
      // observing the same physical play. Two distinct event IDs from the same
      // originating device are legitimate replays and must both survive.
      // On own-device recovery, an exact event ID is the only reliable match:
      // two independent rapid replays from this producer must not disappear
      // merely because another producer reported a similar track nearby.
      const temporalDupe = batch.source_device_id !== deviceId &&
        await storage.hasRecentPlay(
          event.title,
          event.artist,
          event.timestamp_utc,
          batch.source_device_id,
          event.event_id,
          accountSubject,
          event.duration_ms,
        );
      if (temporalDupe) {
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
        volumeLevel: localVolumeFromWire(event.volume_level),
        anomalies: [],
        totalPauseDurationMs: Math.max(0, event.total_pause_duration_ms || 0),
        positionUpdatesCount: Math.max(0, event.position_updates_count || 0),
        driveImported: true,
        driveAccountSubject: accountSubject,
        originEventId: event.event_id,
        originDeviceId: batch.source_device_id,
        driveUploadedAt: Date.now(),
      };
      await storage.insertPlay(play);
      imported++;
    }
    rejectedMap = clearRejectedBatchRecord(rejectedMap, accountSubject, file.id);
    maxCreated = Math.max(maxCreated, created);
  }

  progressMap[accountSubject] = {
    downloadCreatedCursor: maxCreated,
    acceptedDisableVersion: acceptedGeneration,
  };
  await patchRuntimeState({
    downloadCreatedCursor: maxCreated,
    accountProgress: progressMap,
    rejectedBatches: rejectedMap,
  });
  return { imported, duplicates };
}

/**
 * Publish a new server-timestamped generation first, then remove only older
 * generations and turn Drive sync off locally. A client deliberately re-enabled
 * after the marker update can safely seed the new generation immediately.
 */
/** Releasing old local captures to Drive is a distinct user-confirmed action.
 * Require a verified active session and check for remote deletion first.
 */
export function shareOlderLocalHistory(): Promise<{ uploaded: number; imported: number; duplicates: number }> {
  return serializeDriveOperation(async () => {
    const settings = await storage.getSettings();
    if (!settings.driveSyncEnabled) throw new Error('Connect Google Drive first');
    const session = await getDriveAuthSession(true);
    if (!session) throw new Error('Google authorization is required');
    const state = await getRuntimeState();
    if (session.accountSubject !== state.lastAuthorizedAccountSubject) {
      throw new Error('Google account changed; reconnect before uploading old history');
    }
    if (await honorRemoteDisableIfNeeded(session.accessToken)) {
      throw new Error('Cloud history was deleted by another device; reconnect before uploading');
    }
    await storage.authorizeOlderDriveHistory(session.accountSubject);
    return runSync({ interactiveAuth: false });
  });
}

export function deleteDriveHistory(): Promise<number> {
  return serializeDriveOperation(deleteDriveHistoryUnlocked);
}

async function deleteDriveHistoryUnlocked(): Promise<number> {
  await chrome.alarms.clear(DRIVE_SYNC_ALARM_NAME);

  try {
    const session = await getDriveAuthSession(true);
    if (!session) throw new Error('Google Drive sign-in is required');
    const state = await getRuntimeState();
    const currentAccount = session.accountSubject;
    const previousAccount = state.lastAuthorizedAccountSubject;
    if (verifiedAccountChanged(previousAccount, currentAccount)) {
      // A delete is destructive and must never silently cross an account
      // boundary. Reset Drive-only bookkeeping and require an explicit connect
      // before the user can target data in the newly authorized account.
      const settings = await storage.getSettings();
      await storage.saveSettings({ ...settings, driveSyncEnabled: false });
      await storage.claimUnownedDrivePlays(previousAccount!);
      const progressMap: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
      if (previousAccount) {
        progressMap[previousAccount] = {
          downloadCreatedCursor: state.downloadCreatedCursor,
          acceptedDisableVersion: state.acceptedDisableVersion,
        };
      }
      const currentProgress = progressMap[currentAccount] ?? { downloadCreatedCursor: 0, acceptedDisableVersion: 0 };
      await saveRuntimeState({
        ...state,
        downloadCreatedCursor: currentProgress.downloadCreatedCursor,
        acceptedDisableVersion: currentProgress.acceptedDisableVersion,
        accountProgress: progressMap,
        accountEmail: session.accountEmail,
        lastAuthorizedAccountEmail: session.accountEmail,
        lastAuthorizedAccountSubject: currentAccount,
        lastUploaded: 0,
        lastImported: 0,
        lastError: 'Google account changed. Connect Google again before deleting cloud history.',
      });
      throw new Error('Google account changed. Connect Google again before deleting cloud history.');
    }

    const markerVersion = await bumpDisableMarker(session.accessToken);
    const cleanRejected: Record<string, RejectedBatchRecord[]> = { ...(state.rejectedBatches ?? {}) };
    delete cleanRejected[currentAccount];
    const cleanProgress: Record<string, AccountProgress> = { ...(state.accountProgress ?? {}) };
    cleanProgress[currentAccount] = {
      downloadCreatedCursor: 0,
      acceptedDisableVersion: markerVersion,
    };
    await stopForDeletionMarker(
      { ...state, rejectedBatches: cleanRejected, accountProgress: cleanProgress },
      markerVersion,
      null,
      currentAccount,
    );
    return await deleteBatchesBeforeGeneration(session.accessToken, markerVersion, true);
  } catch (err) {
    // A confirmed marker leaves sync off even if cleanup failed. If publishing
    // the marker itself failed, retain the previous opt-in/scheduling state.
    await patchRuntimeState({ lastError: err instanceof Error ? err.message : String(err) });
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
  const checksum = await sha256Bytes(gzip);
  const existing = await findFilesByExactName(
    accessToken,
    fileName,
    'files(id,name,size,md5Checksum,modifiedTime,appProperties)',
  );
  const platform = isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension';
  const verified = existing.find(file => verifiedUpload(file, fileName, deviceId, platform, generation, checksum, gzip.byteLength));
  if (verified) return;
  // Preserve pre-existing same-name files until a replacement has been
  // validated. Unverified legacy payloads may still hold the only cloud copy;
  // deleting them first would risk permanent data loss on upload failure.
  // Origin event IDs make an eventual second copy harmless on import.

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
      [APP_PROPERTY_SHA256]: checksum,
    },
  });
  const body = new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n`,
    `--${boundary}\r\nContent-Type: application/gzip\r\n\r\n`,
    gzip as BlobPart,
    `\r\n--${boundary}--\r\n`,
  ]);
  const response = await driveFetch(
    accessToken,
    `${DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,size,md5Checksum,createdTime,appProperties`,
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
  const uploaded = await response.json() as DriveFileRecord;
  if (!verifiedUpload(uploaded, fileName, deviceId, platform, generation, checksum, gzip.byteLength)) {
    throw new Error('Google Drive returned mismatched metadata for the uploaded history batch');
  }
}

function verifiedUpload(file: DriveFileRecord, name: string, device: string, platform: string,
  generation: number, checksum: string, size: number): boolean {
  const properties = file.appProperties;
  return file.name === name && Number(file.size) === size && properties?.tempo_sha256 === checksum &&
    properties?.tempo_kind === 'history_batch' && properties?.tempo_schema === '1' &&
    properties?.source_device_id === device && properties?.source_platform === platform &&
    batchGeneration(file) === generation;
}

async function findFilesByExactName(
  accessToken: string,
  fileName: string,
  fields = 'files(id,name,size,md5Checksum,modifiedTime,appProperties)',
): Promise<DriveFileRecord[]> {
  const escaped = fileName.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  const params = new URLSearchParams({
    spaces: 'appDataFolder',
    q: `name = '${escaped}' and trashed = false`,
    pageSize: '1000',
    fields,
  });
  params.set('fields', `nextPageToken,${fields}`);
  const files: DriveFileRecord[] = [];
  let pageToken: string | undefined;
  do {
    if (pageToken) params.set('pageToken', pageToken);
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    await assertDriveResponse(response);
    const data = await response.json() as { files?: DriveFileRecord[]; nextPageToken?: string };
    files.push(...(data.files ?? []));
    pageToken = data.nextPageToken;
  } while (pageToken);
  return files;
}

async function getDisableMarkerVersion(accessToken: string): Promise<number> {
  const files = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
  if (files.length === 0) return 0;
  const versions = files.map(file => file.modifiedTime ? Date.parse(file.modifiedTime) : NaN);
  if (versions.some(version => !Number.isSafeInteger(version) || version <= 0)) {
    throw new Error('Google Drive did not return a valid deletion marker version');
  }
  return Math.max(...versions);
}

async function bumpDisableMarker(accessToken: string): Promise<number> {
  const previousVersion = await getDisableMarkerVersion(accessToken);
  let markers = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
  if (markers.length === 0) {
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?fields=id,name,modifiedTime,appProperties`, {
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
    await assertDriveResponse(response);
  }

  let markerVersion = 0;
  for (let attempt = 0; attempt < 3; attempt++) {
    markers = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
    for (const marker of markers) {
      const response = await driveFetch(
        accessToken,
        `${DRIVE_API}/files/${encodeURIComponent(marker.id)}?fields=id,name,modifiedTime,appProperties`,
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
      await assertDriveResponse(response);
      const updated = await response.json() as DriveFileRecord;
      const parsed = updated.modifiedTime ? Date.parse(updated.modifiedTime) : NaN;
      if (!Number.isSafeInteger(parsed) || parsed <= 0) {
        throw new Error('Google Drive did not return a valid deletion marker version');
      }
      markerVersion = Math.max(markerVersion, parsed);
    }
    if (markerVersion > previousVersion) return markerVersion;
    if (attempt < 2) await delayMs(10);
  }
  throw new Error('Google Drive did not return a newer deletion marker version');
}

async function listBatches(accessToken: string, createdAfter: number | null): Promise<DriveFileRecord[]> {
  // Search by name, not appProperties: invalid metadata must still be visible
  // so recovery can report the file instead of silently overlooking it.
  const clauses = [
    `name contains '${FILE_PREFIX}'`,
    'trashed = false',
  ];
  if (createdAfter != null && createdAfter > 0) {
    clauses.push(`createdTime > '${new Date(createdAfter).toISOString()}'`);
  }

  const files: DriveFileRecord[] = [];
  let pageToken: string | null = null;
  do {
    const params = new URLSearchParams({
      spaces: 'appDataFolder',
      q: clauses.join(' and '),
      pageSize: '1000',
      fields: 'nextPageToken,files(id,name,size,md5Checksum,createdTime,modifiedTime,appProperties)',
    });
    if (pageToken) params.set('pageToken', pageToken);
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    await assertDriveResponse(response);
    const data = await response.json() as { nextPageToken?: string; files?: DriveFileRecord[] };
    files.push(...(data.files ?? []));
    pageToken = data.nextPageToken ?? null;
  } while (pageToken);
  // Server-side createdTime sorting can time out on very large Drive archives.
  // Locally sort all enumerated pages before advancing an import checkpoint.
  files.sort((a, b) => {
    const at = a.createdTime ? Date.parse(a.createdTime) : 0;
    const bt = b.createdTime ? Date.parse(b.createdTime) : 0;
    return (Number.isFinite(at) ? at : 0) - (Number.isFinite(bt) ? bt : 0) ||
      a.id.localeCompare(b.id);
  });
  return files;
}

function batchGeneration(file: DriveFileRecord): number | null {
  const value = file.appProperties?.[APP_PROPERTY_GENERATION];
  if (value != null && !/^\d+$/.test(value)) return null;
  const raw = Number(value ?? '0');
  return Number.isSafeInteger(raw) && raw >= 0 ? raw : null;
}

/** Conservative cleanup decision shared by owner deletion and stale clients.
 * Never remove current-generation files if their filename is trustworthy but
 * their Drive appProperties have been stripped or corrupted.
 */
export function shouldDeleteHistoryBatch(
  file: DriveFileRecord, markerGeneration: number, ownerDelete: boolean,
): boolean {
  if (!file.name.startsWith(FILE_PREFIX)) return false;
  if (!file.name.endsWith('.json.gz')) return ownerDelete;
  const match = /^g(\d+)_/.exec(file.name.slice(FILE_PREFIX.length));
  const namedGeneration = match ? Number(match[1]) : null;
  if (namedGeneration != null && Number.isSafeInteger(namedGeneration) &&
      namedGeneration >= markerGeneration) return false;
  const declaredGeneration = batchGeneration(file);
  return declaredGeneration == null ? ownerDelete : declaredGeneration < markerGeneration;
}

async function deleteDriveFileStrict(accessToken: string, fileId: string): Promise<void> {
  const response = await driveFetch(accessToken, `${DRIVE_API}/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (response.status !== 404) await assertDriveResponse(response);
}

async function deleteBatchesBeforeGeneration(
  accessToken: string, generation: number, ownerDelete = false,
): Promise<number> {
  const files = await listBatches(accessToken, null);
  let deleted = 0;
  for (const file of files) {
    if (!shouldDeleteHistoryBatch(file, generation, ownerDelete)) continue;
    await deleteDriveFileStrict(accessToken, file.id);
    deleted++;
  }
  return deleted;
}

async function driveRequestBytes(accessToken: string, file: DriveFileRecord): Promise<Uint8Array> {
  const response = await driveFetch(
    accessToken,
    `${DRIVE_API}/files/${encodeURIComponent(file.id)}?alt=media`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  await assertDriveResponse(response);
  const bytes = await readStreamBytesWithLimit(response.body, MAX_BATCH_BYTES, 'compressed');
  const declaredSize = Number(file.size ?? '0');
  const expectedChecksum = file.appProperties?.[APP_PROPERTY_SHA256];
  if (bytes.byteLength !== declaredSize || !expectedChecksum || await sha256Bytes(bytes) !== expectedChecksum) {
    throw new PermanentBatchError('Tempo Drive history batch does not match its integrity metadata');
  }
  return bytes;
}

/** LAN and Drive must advertise the same locally-owned event identity.
 * No Google account or network permission is required to compute this ID.
 */
export async function getDriveLanOrigin(play: Play): Promise<{ origin_device_id: string; origin_event_id: string; origin_account_subject?: string }> {
  const origin_device_id = await getDeviceId();
  if (play.id == null) throw new Error('A queued LAN play has no persistent ID');
  const candidate = play.originEventId ?? await eventId(origin_device_id, play);
  const origin_event_id = await storage.ensureLocalOriginEventId(play.id, candidate);
  // A paired Android device must not override the sender's Drive opt-out.
  // Only emit a cloud owner while THIS extension explicitly has Drive enabled
  // for that verified account. LAN-only plays remain available on the phone.
  const [settings, state] = await Promise.all([storage.getSettings(), getRuntimeState()]);
  const relaySubject = settings.driveSyncEnabled && state.accountEmail &&
    !play.cloudSuppressed && state.lastAuthorizedAccountSubject === play.driveAccountSubject
      ? play.driveAccountSubject : undefined;
  return { origin_device_id, origin_event_id,
    ...(relaySubject ? { origin_account_subject: relaySubject } : {}) };
}

async function getDeviceId(): Promise<string> {
  if (deviceIdPromise) return deviceIdPromise;
  deviceIdPromise = loadOrCreateDeviceId().catch(err => {
    deviceIdPromise = null;
    throw err;
  });
  return deviceIdPromise;
}

async function loadOrCreateDeviceId(): Promise<string> {
  const result = await chrome.storage.local.get(DEVICE_KEY);
  const existing = result[DEVICE_KEY];
  if (typeof existing === 'string' && DEVICE_ID_PATTERN.test(existing)) return existing;
  const created = crypto.randomUUID();
  await chrome.storage.local.set({ [DEVICE_KEY]: created });
  return created;
}

function parseAccountProgress(raw: unknown): Record<string, AccountProgress> {
  if (!raw || typeof raw !== 'object') return {};
  const result: Record<string, AccountProgress> = {};
  for (const [key, val] of Object.entries(raw as Record<string, unknown>)) {
    if (val && typeof val === 'object') {
      const p = val as Partial<AccountProgress>;
      result[key] = {
        downloadCreatedCursor: safeNonNegativeStateNumber(p.downloadCreatedCursor),
        acceptedDisableVersion: safeNonNegativeStateNumber(p.acceptedDisableVersion),
      };
    }
  }
  return result;
}

function parseRejectedBatches(raw: unknown): Record<string, RejectedBatchRecord[]> {
  if (!raw || typeof raw !== 'object') return {};
  const result: Record<string, RejectedBatchRecord[]> = {};
  for (const [key, val] of Object.entries(raw as Record<string, unknown>)) {
    if (Array.isArray(val)) {
      result[key] = val.filter(item =>
        item && typeof item === 'object' &&
        typeof item.fileId === 'string' &&
        typeof item.fileName === 'string' &&
        typeof item.reason === 'string',
      ).map(item => ({
        fileId: String(item.fileId).slice(0, 100),
        fileName: String(item.fileName).slice(0, 200),
        reason: String(item.reason).slice(0, 500),
        recordedAt: safeNonNegativeStateNumber(item.recordedAt),
      }));
    }
  }
  return result;
}

async function getRuntimeState(): Promise<DriveRuntimeState> {
  const result = await chrome.storage.local.get(STATE_KEY);
  const raw = result[STATE_KEY] as Partial<DriveRuntimeState> | undefined;
  if (!raw || typeof raw !== 'object') return { ...DEFAULT_STATE };
  return {
    downloadCreatedCursor: safeNonNegativeStateNumber(raw.downloadCreatedCursor),
    acceptedDisableVersion: safeNonNegativeStateNumber(raw.acceptedDisableVersion),
    lastSyncTime: safeNullableStateNumber(raw.lastSyncTime),
    lastError: typeof raw.lastError === 'string' ? raw.lastError.slice(0, 2000) : null,
    lastUploaded: safeNonNegativeStateNumber(raw.lastUploaded),
    lastImported: safeNonNegativeStateNumber(raw.lastImported),
    accountEmail: typeof raw.accountEmail === 'string' ? raw.accountEmail : null,
    lastAuthorizedAccountEmail: typeof raw.lastAuthorizedAccountEmail === 'string'
      ? raw.lastAuthorizedAccountEmail
      : null,
    lastAuthorizedAccountSubject: typeof raw.lastAuthorizedAccountSubject === 'string'
      ? raw.lastAuthorizedAccountSubject : null,
    accountProgress: parseAccountProgress(raw.accountProgress),
    rejectedBatches: parseRejectedBatches(raw.rejectedBatches),
  };
}

async function saveRuntimeState(state: DriveRuntimeState): Promise<void> {
  await chrome.storage.local.set({ [STATE_KEY]: state });
}

async function patchRuntimeState(patch: Partial<DriveRuntimeState>): Promise<void> {
  const current = await getRuntimeState();
  await saveRuntimeState({ ...current, ...patch });
}

function safeNonNegativeStateNumber(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function safeNullableStateNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/** Pure protocol hooks used by the Node smoke test; unused exports are removed from production bundles. */
export const driveProtocolTest = {
  eventId,
  deterministicBatchId,
  gzipJson,
  ungzipJson,
  isValidBatch,
  isValidEvent,
  isDriveRequestRetrySafe,
  serializeDriveOperation,
  verifiedAccountChanged,
  verifiedUpload,
  localVolumeFromWire,
  playToWire,
  fetchWithDeadline,
};
