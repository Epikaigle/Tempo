use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use chrono::{DateTime, SecondsFormat, Utc};
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use once_cell::sync::Lazy;
use rand::RngCore;
use reqwest::{header, Client, RequestBuilder, Response, StatusCode};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_shell::ShellExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use url::Url;
use uuid::Uuid;

use crate::AppState;

const AUTH_ENDPOINT: &str = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_ENDPOINT: &str = "https://oauth2.googleapis.com/token";
const USERINFO_ENDPOINT: &str = "https://openidconnect.googleapis.com/v1/userinfo";
const DRIVE_API: &str = "https://www.googleapis.com/drive/v3";
const DRIVE_UPLOAD_API: &str = "https://www.googleapis.com/upload/drive/v3";
const DRIVE_SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const KEYRING_SERVICE: &str = "me.avinas.tempo.desktop.google-drive";

const FILE_PREFIX: &str = "tempo_history_v1_";
const DISABLE_MARKER_NAME: &str = "tempo_history_control_v1.json";
const APP_PROPERTY_GENERATION: &str = "tempo_generation";
const APP_PROPERTY_SHA256: &str = "tempo_sha256";
const MAX_WIRE_INTEGER: i64 = 9_007_199_254_740_991;
const SCHEMA_VERSION: i32 = 1;
const BATCH_SIZE: usize = 50;
const MAX_LOCAL_SCAN: usize = 5000;
const MAX_BATCH_BYTES: usize = 10 * 1024 * 1024;
const DOWNLOAD_OVERLAP_MS: i64 = 24 * 60 * 60 * 1000;
const DRIVE_REQUEST_RETRIES: usize = 3;
const LEGACY_UNVERIFIED_ACCOUNT: &str = "legacy-unverified";
// Cross-producer temporal reconciliation is a fallback, not a proof of origin.
// A narrow two-second window reduces false merges of independently replayed
// short tracks; exact origin IDs remain the primary deduplication mechanism.
const TEMPORAL_DEDUP_MS: i64 = 2_000;

static SYNC_LOCK: Lazy<tokio::sync::Mutex<()>> = Lazy::new(|| tokio::sync::Mutex::new(()));

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DriveSyncStatus {
    pub enabled: bool,
    pub configured: bool,
    pub connected: bool,
    pub account_email: Option<String>,
    pub last_sync_time: Option<i64>,
    pub last_error: Option<String>,
    pub last_uploaded: i64,
    pub last_imported: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DriveSyncResult {
    pub uploaded: usize,
    pub imported: usize,
    pub duplicates: usize,
    pub disabled_by_remote_delete: bool,
}

#[derive(Debug, Clone)]
struct StoredDriveState {
    enabled: bool,
    device_id: String,
    access_token: Option<String>,
    refresh_token: Option<String>,
    token_expires_at: i64,
    account_email: Option<String>,
    account_subject: Option<String>,
    last_verified_account_subject: Option<String>,
    download_cursor: i64,
    accepted_disable_version: i64,
    last_sync_time: Option<i64>,
    last_error: Option<String>,
    last_uploaded: i64,
    last_imported: i64,
}

#[derive(Debug, Deserialize)]
struct OAuthTokenResponse {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
    #[serde(default = "default_expires_in")]
    expires_in: i64,
}

fn default_expires_in() -> i64 {
    3600
}

#[derive(Debug, Deserialize)]
struct GoogleUserInfo {
    email: Option<String>,
    sub: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct WireEvent {
    event_id: String,
    title: String,
    artist: String,
    #[serde(deserialize_with = "deserialize_required_option")]
    album: Option<String>,
    timestamp_utc: i64,
    duration_ms: i64,
    listened_ms: i64,
    source_app: String,
    source: String,
    skipped: bool,
    replay_count: i64,
    completion_percentage: i64,
    pause_count: i64,
    seek_count: i64,
    #[serde(deserialize_with = "deserialize_required_option")]
    session_id: Option<String>,
    #[serde(deserialize_with = "deserialize_required_option")]
    site: Option<String>,
    content_type: String,
    #[serde(deserialize_with = "deserialize_required_option")]
    volume_level: Option<i64>,
    total_pause_duration_ms: i64,
    position_updates_count: i64,
}

fn deserialize_required_option<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    // Protocol-v1 nullable fields must be present, even when their value is null.
    Option::<T>::deserialize(deserializer)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct WireBatch {
    schema_version: i32,
    batch_id: String,
    source_device_id: String,
    source_device_name: String,
    source_platform: String,
    created_at_utc: i64,
    events: Vec<WireEvent>,
}

#[derive(Debug, Clone, Deserialize)]
struct DriveFileRecord {
    id: String,
    name: String,
    #[serde(default)]
    size: Option<String>,
    #[serde(rename = "createdTime", default)]
    created_time: Option<String>,
    #[serde(rename = "modifiedTime", default)]
    modified_time: Option<String>,
    #[serde(rename = "appProperties", default)]
    app_properties: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct DriveListResponse {
    #[serde(rename = "nextPageToken")]
    next_page_token: Option<String>,
    #[serde(default)]
    files: Vec<DriveFileRecord>,
}

#[derive(Debug)]
struct LocalPlay {
    id: i64,
    /// Preserve the first published canonical identity even if title/artist
    /// metadata is corrected before a cloud-delete retry or account change.
    origin_event_id: Option<String>,
    title: String,
    artist: String,
    album: String,
    duration_ms: i64,
    timestamp_utc: i64,
    source_app: String,
    listened_ms: i64,
    skipped: bool,
    replay_count: i64,
    is_muted: bool,
    completion_percentage: f64,
    pause_count: i64,
    seek_count: i64,
    session_id: String,
    site: String,
    content_type: String,
    volume_level: f64,
}

fn oauth_client_id() -> Option<String> {
    option_env!("TEMPO_GOOGLE_OAUTH_CLIENT_ID_DESKTOP")
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

fn db_path(app_data_dir: &Path) -> std::path::PathBuf {
    app_data_dir.join("tempo.db")
}

fn open_sync_db(app_data_dir: &Path) -> Result<Connection, String> {
    let conn = Connection::open(db_path(app_data_dir)).map_err(|e| e.to_string())?;
    conn.execute_batch(
        "
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS drive_sync_state (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            enabled INTEGER NOT NULL DEFAULT 0,
            device_id TEXT NOT NULL DEFAULT '',
            access_token TEXT,
            refresh_token TEXT,
            token_expires_at INTEGER NOT NULL DEFAULT 0,
            account_email TEXT,
            account_subject TEXT,
            last_verified_account_subject TEXT,
            download_cursor INTEGER NOT NULL DEFAULT 0,
            accepted_disable_version INTEGER NOT NULL DEFAULT 0,
            last_sync_time INTEGER,
            last_error TEXT,
            last_uploaded INTEGER NOT NULL DEFAULT 0,
            last_imported INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS drive_event_state (
            scrobble_id INTEGER PRIMARY KEY,
            origin_event_id TEXT UNIQUE,
            origin_device_id TEXT,
            drive_imported INTEGER NOT NULL DEFAULT 0,
            drive_uploaded_at INTEGER,
            owner_account_subject TEXT,
            cloud_suppressed INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_drive_origin_event ON drive_event_state(origin_event_id);
        CREATE TABLE IF NOT EXISTS drive_event_aliases (
            origin_event_id TEXT PRIMARY KEY,
            source_device_id TEXT NOT NULL,
            scrobble_id INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_drive_alias_scrobble_device
            ON drive_event_aliases(scrobble_id, source_device_id);
        -- Keep provenance consistent without rescanning 100k+ event rows every
        -- time the UI requests sync status. SQLite applies both deletes inside
        -- the same transaction as any local scrobble removal.
        CREATE TRIGGER IF NOT EXISTS tempo_drive_scrobble_delete
        AFTER DELETE ON scrobbles
        BEGIN
            DELETE FROM drive_event_aliases WHERE scrobble_id = OLD.id;
            DELETE FROM drive_event_state WHERE scrobble_id = OLD.id;
        END;
        CREATE TABLE IF NOT EXISTS drive_sync_migrations (
            name TEXT PRIMARY KEY
        );
        INSERT OR IGNORE INTO drive_sync_state (id) VALUES (1);
        ",
    )
    .map_err(|e| e.to_string())?;

    // An existing email-only credential cannot prove the Google account.
    let has_subject = {
        let mut stmt = conn.prepare("PRAGMA table_info(drive_sync_state)").map_err(|e| e.to_string())?;
        let names = stmt.query_map([], |row| row.get::<_, String>(1)).map_err(|e| e.to_string())?;
        names.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?
            .iter().any(|name| name == "account_subject")
    };
    if !has_subject {
        conn.execute("ALTER TABLE drive_sync_state ADD COLUMN account_subject TEXT", [])
            .map_err(|e| e.to_string())?;
    }
    // Preserve the last verified subject even if authorization subsequently
    // fails. The active subject is intentionally cleared while replacing
    // credentials; the durable value retains event ownership across crashes.
    let has_verified_column = {
        let mut stmt = conn.prepare("PRAGMA table_info(drive_sync_state)")
            .map_err(|e| e.to_string())?;
        let names = stmt.query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?;
        names.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?
            .iter().any(|name| name == "last_verified_account_subject")
    };
    if !has_verified_column {
        conn.execute(
            "ALTER TABLE drive_sync_state ADD COLUMN last_verified_account_subject TEXT", []
        ).map_err(|e| e.to_string())?;
    }
    conn.execute(
        "UPDATE drive_sync_state SET last_verified_account_subject = account_subject
         WHERE last_verified_account_subject IS NULL AND account_subject IS NOT NULL", []
    ).map_err(|e| e.to_string())?;

    let has_owner_column = {
        let mut stmt = conn.prepare("PRAGMA table_info(drive_event_state)")
            .map_err(|e| e.to_string())?;
        let names = stmt.query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?;
        names.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?
            .iter().any(|name| name == "owner_account_subject")
    };
    if !has_owner_column {
        conn.execute("ALTER TABLE drive_event_state ADD COLUMN owner_account_subject TEXT", [])
            .map_err(|e| e.to_string())?;
    }
    // Existing databases need the suppression flag before the table migration.
    let has_suppression = {
        let mut stmt = conn.prepare("PRAGMA table_info(drive_event_state)")
            .map_err(|e| e.to_string())?;
        let columns = stmt.query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?;
        columns.contains(&"cloud_suppressed".to_string())
    };
    if !has_suppression {
        conn.execute(
            "ALTER TABLE drive_event_state ADD COLUMN cloud_suppressed INTEGER NOT NULL DEFAULT 0", []
        ).map_err(|e| e.to_string())?;
    }

    // After a prior Google connection, unowned captures that were left behind
    // during a disconnected session must not be claimed by the next account.
    conn.execute(
        "INSERT OR IGNORE INTO drive_event_state (scrobble_id, drive_imported, owner_account_subject)
         SELECT id, 0, ?1 FROM scrobbles
         WHERE (SELECT enabled FROM drive_sync_state WHERE id = 1) = 0
           AND (SELECT last_verified_account_subject FROM drive_sync_state WHERE id = 1) IS NOT NULL",
        [LEGACY_UNVERIFIED_ACCOUNT],
    ).map_err(|e| e.to_string())?;

    // Email-only installations cannot prove who owns previously collected
    // scrobbles. Quarantine them rather than uploading them to another account.
    let unverified_account: bool = conn.query_row(
        "SELECT (account_subject IS NULL OR account_subject = '')
            AND (last_verified_account_subject IS NULL OR last_verified_account_subject = '')
            AND (account_email IS NOT NULL OR enabled != 0)
         FROM drive_sync_state WHERE id = 1", [], |row| row.get(0)
    ).map_err(|e| e.to_string())?;
    if unverified_account {
        conn.execute(
            "INSERT OR IGNORE INTO drive_event_state (scrobble_id, drive_imported, owner_account_subject)
             SELECT id, 0, ?1 FROM scrobbles", [LEGACY_UNVERIFIED_ACCOUNT]
        ).map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_event_state SET owner_account_subject = ?1
             WHERE drive_imported = 0 AND owner_account_subject IS NULL",
            [LEGACY_UNVERIFIED_ACCOUNT]
        ).map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_sync_state SET enabled = 0, access_token = NULL,
             refresh_token = NULL, token_expires_at = 0, account_email = NULL WHERE id = 1", []
        ).map_err(|e| e.to_string())?;
    }

    migrate_legacy_import_account_and_aliases(&conn)?;
    requeue_unverified_prototype_uploads(&conn)?;
    rescan_reconciled_origins(&conn)?;
    migrate_account_scoped_origin_keys(&conn)?;

    let current: String = conn
        .query_row(
            "SELECT device_id FROM drive_sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    if current.trim().is_empty() {
        conn.execute(
            "UPDATE drive_sync_state SET device_id = ?1 WHERE id = 1",
            [Uuid::new_v4().to_string()],
        )
        .map_err(|e| e.to_string())?;
    }

    Ok(conn)
}

// Run this historical backfill once, rather than doing a full alias-table
// INSERT/SELECT on every settings read or background sync.
fn migrate_legacy_import_account_and_aliases(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction.execute(
        "INSERT OR IGNORE INTO drive_sync_migrations (name)
         VALUES ('legacy_aliases_and_account_v1')", []
    ).map_err(|e| e.to_string())?;
    if applied == 1 {
        // Before introducing deletion triggers, old installations could have
        // orphaned provenance. Prune it once, then the trigger maintains it.
        transaction.execute(
            "DELETE FROM drive_event_aliases WHERE scrobble_id NOT IN (SELECT id FROM scrobbles)",
            []
        ).map_err(|e| e.to_string())?;
        transaction.execute(
            "DELETE FROM drive_event_state WHERE scrobble_id NOT IN (SELECT id FROM scrobbles)",
            []
        ).map_err(|e| e.to_string())?;
        let scoped = {
            let mut stmt = transaction.prepare("PRAGMA table_info(drive_event_aliases)")
                .map_err(|e| e.to_string())?;
            let names = stmt.query_map([], |row| row.get::<_, String>(1))
                .map_err(|e| e.to_string())?;
            names.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())?
                .contains(&"account_subject".to_string())
        };
        if scoped {
            transaction.execute(
                "INSERT OR IGNORE INTO drive_event_aliases
                  (account_subject, origin_event_id, source_device_id, scrobble_id)
                 SELECT COALESCE(NULLIF(owner_account_subject, ''), 'legacy-unverified'),
                        origin_event_id, origin_device_id, scrobble_id
                 FROM drive_event_state
                 WHERE origin_event_id IS NOT NULL AND origin_device_id IS NOT NULL
                       AND origin_device_id <> ''", []
            ).map_err(|e| e.to_string())?;
        } else {
            transaction.execute(
                "INSERT OR IGNORE INTO drive_event_aliases
                  (origin_event_id, source_device_id, scrobble_id)
                 SELECT origin_event_id, origin_device_id, scrobble_id
                 FROM drive_event_state
                 WHERE origin_event_id IS NOT NULL AND origin_device_id IS NOT NULL
                       AND origin_device_id <> ''", []
            ).map_err(|e| e.to_string())?;
        }
        // Older releases did not record the Google owner of imported events.
        // No current-account guess can safely prove which account provided
        // them; quarantine their temporal matches until explicitly restored.
        transaction.execute(
            "UPDATE drive_event_state SET owner_account_subject = ?1
             WHERE drive_imported != 0 AND owner_account_subject IS NULL",
            [LEGACY_UNVERIFIED_ACCOUNT],
        ).map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

fn requeue_unverified_prototype_uploads(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction.execute(
        "INSERT OR IGNORE INTO drive_sync_migrations (name) VALUES ('verified_batches_v1')",
        [],
    ).map_err(|e| e.to_string())?;
    if applied == 1 {
        // Earlier Desktop batches omitted tempo_sha256 and were rejected by
        // Android/browser readers. Re-send local events once with verified metadata.
        transaction.execute(
            "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
            [],
        ).map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

fn rescan_reconciled_origins(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction.execute(
        "INSERT OR IGNORE INTO drive_sync_migrations (name) VALUES ('reconciled_origins_v1')",
        [],
    ).map_err(|e| e.to_string())?;
    if applied == 1 {
        // Older temporal reconciliation overwrote origins. Re-read retained
        // cloud batches once to recover their identities and any skipped replays.
        transaction.execute("UPDATE drive_sync_state SET download_cursor = 0 WHERE id = 1", [])
            .map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

/// Rebuild old globally-unique producer IDs as (Google subject, event ID).
/// Preserve every listening row; unverified legacy identities stay quarantined.
/// This runs once and commits the schema change and migration flag together.
fn migrate_account_scoped_origin_keys(conn: &Connection) -> Result<(), String> {
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = tx.execute(
        "INSERT OR IGNORE INTO drive_sync_migrations (name)
         VALUES ('account_scoped_origin_keys_v1')", []
    ).map_err(|e| e.to_string())?;
    if applied == 1 {
        tx.execute_batch(
            "DROP TRIGGER IF EXISTS tempo_drive_scrobble_delete;
             DROP INDEX IF EXISTS idx_drive_origin_event;
             ALTER TABLE drive_event_state RENAME TO drive_event_state_legacy;
             CREATE TABLE drive_event_state (
                 scrobble_id INTEGER PRIMARY KEY,
                 origin_event_id TEXT,
                 origin_device_id TEXT,
                 drive_imported INTEGER NOT NULL DEFAULT 0,
                 drive_uploaded_at INTEGER,
                 owner_account_subject TEXT,
                 cloud_suppressed INTEGER NOT NULL DEFAULT 0
             );
             INSERT INTO drive_event_state
                 (scrobble_id, origin_event_id, origin_device_id,
                  drive_imported, drive_uploaded_at, owner_account_subject, cloud_suppressed)
             SELECT scrobble_id, origin_event_id, origin_device_id,
                    drive_imported, drive_uploaded_at, owner_account_subject, cloud_suppressed
             FROM drive_event_state_legacy;
             DROP TABLE drive_event_state_legacy;
             CREATE INDEX idx_drive_origin_event ON drive_event_state(origin_event_id);
             ALTER TABLE drive_event_aliases RENAME TO drive_event_aliases_legacy;
             CREATE TABLE drive_event_aliases (
                 account_subject TEXT NOT NULL,
                 origin_event_id TEXT NOT NULL,
                 source_device_id TEXT NOT NULL,
                 scrobble_id INTEGER NOT NULL,
                 PRIMARY KEY (account_subject, origin_event_id)
             );
             INSERT INTO drive_event_aliases
                 (account_subject, origin_event_id, source_device_id, scrobble_id)
             SELECT COALESCE(NULLIF(d.owner_account_subject, ''), 'legacy-unverified'),
                    a.origin_event_id, a.source_device_id, a.scrobble_id
             FROM drive_event_aliases_legacy a
             JOIN drive_event_state d ON d.scrobble_id = a.scrobble_id;
             DROP TABLE drive_event_aliases_legacy;
             CREATE INDEX idx_drive_alias_scrobble_device
                 ON drive_event_aliases(scrobble_id, source_device_id);
             CREATE TRIGGER tempo_drive_scrobble_delete
             AFTER DELETE ON scrobbles
             BEGIN
                 DELETE FROM drive_event_aliases WHERE scrobble_id = OLD.id;
                 DELETE FROM drive_event_state WHERE scrobble_id = OLD.id;
             END;"
        ).map_err(|e| e.to_string())?;
    }
    tx.commit().map_err(|e| e.to_string())
}

fn load_state(conn: &Connection) -> Result<StoredDriveState, String> {
    conn.query_row(
        "SELECT enabled, device_id, access_token, refresh_token, token_expires_at,
                account_email, download_cursor, accepted_disable_version,
                last_sync_time, last_error, last_uploaded, last_imported, account_subject,
                last_verified_account_subject
         FROM drive_sync_state WHERE id = 1",
        [],
        |row| {
            Ok(StoredDriveState {
                enabled: row.get::<_, i64>(0)? != 0,
                device_id: row.get(1)?,
                access_token: row.get(2)?,
                refresh_token: row.get(3)?,
                token_expires_at: row.get(4)?,
                account_email: row.get(5)?,
                account_subject: row.get(12)?,
                last_verified_account_subject: row.get(13)?,
                download_cursor: row.get(6)?,
                accepted_disable_version: row.get(7)?,
                last_sync_time: row.get(8)?,
                last_error: row.get(9)?,
                last_uploaded: row.get(10)?,
                last_imported: row.get(11)?,
            })
        },
    )
    .map_err(|e| e.to_string())
}

fn set_last_error(conn: &Connection, error: Option<&str>) -> Result<(), String> {
    conn.execute(
        "UPDATE drive_sync_state SET last_error = ?1 WHERE id = 1",
        [error],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

fn http_client() -> Result<Client, String> {
    Client::builder()
        .user_agent("Tempo-Desktop-DriveSync/1.0")
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())
}

// The OS keyring is keyed by device, so the entry itself must also carry
// a verified Google subject. Otherwise a crash while switching accounts can
// leave account B's refresh token behind A's SQLite identity.
#[derive(Debug, Serialize, Deserialize)]
struct SubjectBoundRefreshToken {
    sub: String,
    refresh_token: String,
}

fn decode_subject_bound_token(value: &str, expected_subject: &str) -> Result<String, String> {
    let stored: SubjectBoundRefreshToken = serde_json::from_str(value)
        .map_err(|_| "Google credential format is outdated. Reconnect Google securely.".to_string())?;
    if stored.sub != expected_subject || stored.refresh_token.is_empty() {
        return Err("Google credential belongs to a different account. Reconnect securely.".to_string());
    }
    Ok(stored.refresh_token)
}

async fn secure_refresh_token_get(
    device_id: &str,
    expected_subject: &str,
) -> Result<Option<String>, String> {
    let username = device_id.to_string();
    let expected_subject = expected_subject.to_string();
    tokio::task::spawn_blocking(move || {
        let entry = keyring::Entry::new(KEYRING_SERVICE, &username)
            .map_err(|e| format!("Could not open the OS credential store: {e}"))?;
        match entry.get_password() {
            Ok(value) if !value.is_empty() => {
                Ok(Some(decode_subject_bound_token(&value, &expected_subject)?))
            }
            Ok(_) | Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(format!(
                "Could not read the Google Drive credential from the OS credential store: {e}"
            )),
        }
    })
    .await
    .map_err(|e| format!("OS credential store task failed: {e}"))?
}

async fn secure_refresh_token_set(device_id: &str, subject: &str, refresh_token: &str) -> Result<(), String> {
    if refresh_token.is_empty() || subject.trim().is_empty() {
        return Err("Refusing to store a Google credential without a verified subject and token".to_string());
    }
    let username = device_id.to_string();
    let secret = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: subject.to_string(),
        refresh_token: refresh_token.to_string(),
    }).map_err(|e| e.to_string())?;
    tokio::task::spawn_blocking(move || {
        let entry = keyring::Entry::new(KEYRING_SERVICE, &username)
            .map_err(|e| format!("Could not open the OS credential store: {e}"))?;
        entry.set_password(&secret).map_err(|e| {
            format!(
                "Could not protect the Google Drive refresh token in the OS credential store: {e}"
            )
        })
    })
    .await
    .map_err(|e| format!("OS credential store task failed: {e}"))?
}

async fn secure_refresh_token_delete(device_id: &str) -> Result<(), String> {
    let username = device_id.to_string();
    tokio::task::spawn_blocking(move || {
        let entry = keyring::Entry::new(KEYRING_SERVICE, &username)
            .map_err(|e| format!("Could not open the OS credential store: {e}"))?;
        match entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(format!(
                "Could not remove the Google Drive credential from the OS credential store: {e}"
            )),
        }
    })
    .await
    .map_err(|e| format!("OS credential store task failed: {e}"))?
}

async fn refresh_token_for_state(
    _app_data_dir: &Path,
    state: &StoredDriveState,
) -> Result<String, String> {
    let subject = state.account_subject.as_deref()
        .filter(|subject| !subject.trim().is_empty())
        .ok_or("Google account identity is unavailable. Connect Google again.")?;
    // Old plaintext SQLite/keyring credentials carry no cryptographic account
    // binding. Never adopt one into a new identity without re-authenticating.
    if state.refresh_token.as_deref().is_some_and(|value| !value.is_empty()) {
        return Err("Legacy Google credential is unverified. Reconnect Google securely.".into());
    }
    secure_refresh_token_get(&state.device_id, subject).await?
        .ok_or_else(|| "Google Drive needs you to reconnect".to_string())
}

async fn access_token(app_data_dir: &Path) -> Result<String, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    if state.account_subject.as_deref().map(str::trim).filter(|subject| !subject.is_empty()).is_none() {
        return Err("Google account identity is unavailable. Connect Google again.".into());
    }
    if let Some(token) = state.access_token.clone() {
        if !token.is_empty() && state.token_expires_at > now_ms() + 60_000 {
            return Ok(token);
        }
    }
    drop(conn);

    let refresh_token = refresh_token_for_state(app_data_dir, &state).await?;
    let client_id = oauth_client_id()
        .ok_or_else(|| "Google Drive OAuth is not configured in this Desktop build".to_string())?;

    let body = {
        let mut serializer = url::form_urlencoded::Serializer::new(String::new());
        serializer.append_pair("client_id", &client_id);
        serializer.append_pair("refresh_token", &refresh_token);
        serializer.append_pair("grant_type", "refresh_token");
        serializer.finish()
    };

    let response = http_client()?
        .post(TOKEN_ENDPOINT)
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(body)
        .send()
        .await
        .map_err(|e| format!("Google token refresh failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "Google token refresh failed (HTTP {})",
            response.status()
        ));
    }
    let token: OAuthTokenResponse = response.json().await.map_err(|e| e.to_string())?;
    // A successfully refreshed OAuth token must still belong to the expected
    // Google subject. A mismatched/corrupt native credential cannot silently
    // move history into a different Drive account.
    let user_info = http_client()?
        .get(USERINFO_ENDPOINT)
        .bearer_auth(&token.access_token)
        .send().await
        .map_err(|e| format!("Could not verify refreshed Google account: {e}"))?;
    if !user_info.status().is_success() {
        return Err(format!("Could not verify refreshed Google account (HTTP {})", user_info.status()));
    }
    let info: GoogleUserInfo = user_info.json().await.map_err(|e| e.to_string())?;
    if info.sub.as_deref() != state.account_subject.as_deref() {
        return Err("Google refreshed a token for a different account. Reconnect Google securely.".into());
    }
    if let Some(rotated) = token.refresh_token.as_deref().filter(|value| !value.is_empty()) {
        secure_refresh_token_set(&state.device_id,
            state.account_subject.as_deref().unwrap_or_default(), rotated).await?;
    }
    let expires_at = now_ms() + token.expires_in.max(60) * 1000;
    let conn = open_sync_db(app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET access_token = ?1, token_expires_at = ?2,
         refresh_token = NULL WHERE id = 1",
        params![token.access_token, expires_at],
    )
    .map_err(|e| e.to_string())?;
    Ok(token.access_token)
}

fn random_pkce_verifier() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn pkce_challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

fn prepare_oauth_credentials(conn: &Connection, reset_drive_state: bool) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    if reset_drive_state {
        let previous_subject: Option<String> = transaction.query_row(
            "SELECT COALESCE(account_subject, last_verified_account_subject)
             FROM drive_sync_state WHERE id = 1",
            [], |row| row.get(0)
        ).map_err(|e| e.to_string())?;
        if let Some(previous_subject) = previous_subject {
            // Mark even never-uploaded local plays as belonging to the old
            // account BEFORE the new account can enable its first sync.
            transaction.execute(
                "INSERT OR IGNORE INTO drive_event_state
                 (scrobble_id, drive_imported, owner_account_subject)
                 SELECT id, 0, ?1 FROM scrobbles",
                [&previous_subject]
            ).map_err(|e| e.to_string())?;
            transaction.execute(
                "UPDATE drive_event_state SET owner_account_subject = ?1
                 WHERE drive_imported = 0 AND owner_account_subject IS NULL",
                [&previous_subject]
            ).map_err(|e| e.to_string())?;
        }
        // Preserve account-owned uploads across switches. Resetting them here
        // could resurrect an archive deliberately deleted from Drive.
        transaction.execute(
            "UPDATE drive_sync_state SET download_cursor = 0, accepted_disable_version = 0,
             last_sync_time = NULL, last_uploaded = 0, last_imported = 0 WHERE id = 1", []
        ).map_err(|e| e.to_string())?;
    }
    // SQLite and the OS credential store cannot share a transaction. Commit an
    // inert, account-unknown state before replacing the keyring credential. If
    // either the keyring write or the final SQLite write fails, no command can
    // use that credential until account identity is verified and saved again.
    transaction.execute(
        "UPDATE drive_sync_state SET enabled = 0, access_token = NULL, refresh_token = NULL,
         token_expires_at = 0, account_email = NULL, account_subject = NULL, last_error = ?1 WHERE id = 1",
        ["Google sign-in did not finish. Connect Google again."]
    ).map_err(|e| e.to_string())?;
    transaction.commit().map_err(|e| e.to_string())
}

fn parse_oauth_callback(line: &str, expected_state: &str) -> Option<Result<String, String>> {
    let mut parts = line.split_whitespace();
    if parts.next()? != "GET" { return None; }
    let target = parts.next()?;
    if !matches!(parts.next()?, "HTTP/1.0" | "HTTP/1.1") || parts.next().is_some()
        || !target.starts_with('/') || target.starts_with("//") {
        return None;
    }
    let callback = Url::parse(&format!("http://127.0.0.1{target}")).ok()?;
    if callback.path() != "/oauth2/callback" || callback.fragment().is_some() { return None; }
    let mut values = HashMap::new();
    for (key, value) in callback.query_pairs() {
        if matches!(key.as_ref(), "state" | "code" | "error")
            && values.insert(key.into_owned(), value.into_owned()).is_some() {
            return None;
        }
    }
    if values.get("state").map(String::as_str) != Some(expected_state) { return None; }
    if let Some(error) = values.get("error") {
        return Some(Err(format!("Google sign-in failed: {error}")));
    }
    Some(values.remove("code").filter(|value| !value.trim().is_empty())
        .ok_or_else(|| "Google sign-in did not return an authorization code".to_string()))
}

async fn read_oauth_request(stream: &mut tokio::net::TcpStream) -> Result<String, String> {
    let mut request = Vec::new();
    let mut buffer = [0u8; 1024];
    loop {
        let count = stream.read(&mut buffer).await.map_err(|e| e.to_string())?;
        if count == 0 { return Err("Incomplete OAuth callback".into()); }
        request.extend_from_slice(&buffer[..count]);
        if request.len() > 16 * 1024 { return Err("OAuth callback is too large".into()); }
        if request.windows(4).any(|bytes| bytes == b"\r\n\r\n") {
            return String::from_utf8(request).map_err(|_| "Invalid OAuth callback encoding".into());
        }
    }
}

async fn wait_for_oauth_callback(
    listener: &tokio::net::TcpListener, expected_state: &str, deadline: Duration,
) -> Result<String, String> {
    tokio::time::timeout(deadline, async {
        loop {
            let (mut stream, _) = listener.accept().await
                .map_err(|e| format!("OAuth callback failed: {e}"))?;
            let callback = match tokio::time::timeout(Duration::from_secs(10), read_oauth_request(&mut stream)).await {
                Ok(Ok(request)) => parse_oauth_callback(request.lines().next().unwrap_or_default(), expected_state),
                _ => None,
            };
            let (status, body) = match &callback {
                Some(Ok(_)) => ("200 OK", "Google authorization received. Return to Tempo Desktop to finish connecting."),
                Some(Err(_)) => ("200 OK", "Google sign-in was not completed. Return to Tempo Desktop and try again."),
                None => ("400 Bad Request", "This is not a valid Tempo sign-in callback. Continue in the Google sign-in tab."),
            };
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
            if let Some(result) = callback { return result; }
            // An unrelated request, probe or wrong nonce must not consume the
            // valid browser callback. The shared deadline never restarts.
        }
    }).await.map_err(|_| "Google sign-in timed out".to_string())?
}

async fn interactive_oauth(app: &AppHandle, app_data_dir: &Path) -> Result<(), String> {
    let client_id = oauth_client_id()
        .ok_or_else(|| "Google Drive OAuth is not configured in this Desktop build".to_string())?;

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .map_err(|e| format!("Could not open local OAuth callback port: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let redirect_uri = format!("http://127.0.0.1:{port}/oauth2/callback");

    let verifier = random_pkce_verifier();
    let challenge = pkce_challenge(&verifier);
    let state_nonce = Uuid::new_v4().to_string();

    let mut auth_url = Url::parse(AUTH_ENDPOINT).map_err(|e| e.to_string())?;
    auth_url
        .query_pairs_mut()
        .append_pair("client_id", &client_id)
        .append_pair("redirect_uri", &redirect_uri)
        .append_pair("response_type", "code")
        .append_pair("scope", &format!("openid email {DRIVE_SCOPE}"))
        .append_pair("access_type", "offline")
        .append_pair("include_granted_scopes", "true")
        .append_pair("prompt", "consent")
        .append_pair("code_challenge", &challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("state", &state_nonce);

    app.shell()
        .open(auth_url.to_string(), None)
        .map_err(|e| format!("Could not open Google sign-in in your browser: {e}"))?;

    let code = wait_for_oauth_callback(&listener, &state_nonce, Duration::from_secs(180)).await?;

    let token_body = {
        let mut serializer = url::form_urlencoded::Serializer::new(String::new());
        serializer.append_pair("client_id", &client_id);
        serializer.append_pair("code", &code);
        serializer.append_pair("code_verifier", &verifier);
        serializer.append_pair("grant_type", "authorization_code");
        serializer.append_pair("redirect_uri", &redirect_uri);
        serializer.finish()
    };

    let token_response = http_client()?
        .post(TOKEN_ENDPOINT)
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(token_body)
        .send()
        .await
        .map_err(|e| format!("Google token exchange failed: {e}"))?;
    if !token_response.status().is_success() {
        return Err(format!(
            "Google token exchange failed (HTTP {})",
            token_response.status()
        ));
    }
    let token: OAuthTokenResponse = token_response.json().await.map_err(|e| e.to_string())?;

    let user_info = http_client()?
        .get(USERINFO_ENDPOINT)
        .bearer_auth(&token.access_token)
        .send()
        .await
        .map_err(|e| format!("Could not read Google account info: {e}"))?;
    if !user_info.status().is_success() {
        return Err(format!(
            "Could not verify the Google account identity (HTTP {})",
            user_info.status()
        ));
    }
    let info = user_info.json::<GoogleUserInfo>().await
        .map_err(|e| format!("Could not parse Google account info: {e}"))?;
    let email = info.email.map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
        .ok_or("Google account email is unavailable; refusing Drive sync state reuse")?;
    // OIDC subject, not the editable email, identifies a Google account.
    let subject = info.sub.map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
        .ok_or("Google account subject is unavailable; reconnect securely")?;

    let conn = open_sync_db(app_data_dir)?;
    let existing = load_state(&conn)?;
    let previous_account_matches = existing.last_verified_account_subject
        .as_deref() == Some(subject.as_str());
    prepare_oauth_credentials(&conn, !previous_account_matches)?;
    let device_id = existing.device_id.clone();
    let legacy_refresh_token = existing
        .refresh_token
        .clone()
        .filter(|value| !value.is_empty());
    drop(conn);

    let new_refresh_token = token
        .refresh_token
        .clone()
        .filter(|value| !value.is_empty());
    let refresh_token = if let Some(value) = new_refresh_token {
        value
    } else if previous_account_matches {
        if let Some(value) = legacy_refresh_token {
            value
        } else {
            secure_refresh_token_get(&device_id, &subject).await?.ok_or_else(|| {
                "Google did not issue a refresh token. Disconnect and connect again.".to_string()
            })?
        }
    } else {
        // The verified current account is either different from the stored one,
        // or this installation has no trustworthy account identity for the old
        // keyring/SQLite credential. Never reuse an account-unknown secret.
        secure_refresh_token_delete(&device_id).await?;
        let conn = open_sync_db(app_data_dir)?;
        conn.execute(
            "UPDATE drive_sync_state SET enabled = 0, access_token = NULL, refresh_token = NULL, token_expires_at = 0, last_error = ?1 WHERE id = 1",
            ["Google did not issue a fresh refresh token for the verified account. Connect again."],
        )
        .map_err(|e| e.to_string())?;
        return Err(
            "Google did not issue a fresh refresh token for the verified account. Connect again."
                .to_string(),
        );
    };

    secure_refresh_token_set(&device_id, &subject, &refresh_token).await?;
    let expires_at = now_ms() + token.expires_in.max(60) * 1000;
    let conn = open_sync_db(app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET access_token = ?1, refresh_token = NULL, token_expires_at = ?2, account_email = ?3, account_subject = ?4,
          last_verified_account_subject = ?4, last_error = NULL WHERE id = 1",
        params![token.access_token, expires_at, email, subject],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

fn parse_time_ms(value: Option<&str>) -> i64 {
    value
        .and_then(|text| DateTime::parse_from_rfc3339(text).ok())
        .map(|time| time.timestamp_millis())
        .unwrap_or(0)
}

fn require_server_time(value: Option<&str>, label: &str) -> Result<i64, String> {
    let parsed = parse_time_ms(value);
    if parsed <= 0 {
        Err(format!(
            "Google Drive did not return a valid {label} timestamp"
        ))
    } else {
        Ok(parsed)
    }
}

fn transient_drive_response(status: StatusCode) -> bool {
    matches!(status.as_u16(), 429 | 500 | 502 | 503 | 504)
}

// Retry only idempotent requests: GET, DELETE, and overwrite-by-ID PATCH.
// Retrying a create/upload POST blindly can leave duplicate cloud objects.
async fn send_drive_idempotent(request: RequestBuilder) -> Result<Response, String> {
    for attempt in 0..DRIVE_REQUEST_RETRIES {
        let replay = request.try_clone().ok_or("Drive request cannot be retried safely")?;
        match replay.send().await {
            Ok(response) if transient_drive_response(response.status()) &&
                    attempt + 1 < DRIVE_REQUEST_RETRIES => {
                let seconds = response.headers().get(header::RETRY_AFTER)
                    .and_then(|h| h.to_str().ok())
                    .and_then(|s| s.parse::<u64>().ok())
                    .map(|s| s.min(5));
                tokio::time::sleep(seconds.map(Duration::from_secs)
                    .unwrap_or_else(|| Duration::from_millis(500u64 * (1 << attempt)))).await;
            }
            Ok(response) => return Ok(response),
            Err(error) if attempt + 1 < DRIVE_REQUEST_RETRIES &&
                 (error.is_timeout() || error.is_connect()) => {
                tokio::time::sleep(Duration::from_millis(500u64 * (1 << attempt))).await;
            }
            Err(error) => return Err(format!("Google Drive request failed: {error}")),
        }
    }
    Err("Google Drive retry budget exhausted".into())
}

async fn drive_json<T: for<'de> Deserialize<'de>>(
    access_token: &str,
    url: Url,
) -> Result<T, String> {
    let response = send_drive_idempotent(
        http_client()?.get(url).bearer_auth(access_token)
    ).await?;
    if !response.status().is_success() {
        return Err(format!(
            "Google Drive request failed (HTTP {})",
            response.status()
        ));
    }
    response.json::<T>().await.map_err(|e| e.to_string())
}

async fn list_files(
    access_token: &str,
    query: &str,
    order_by: Option<&str>,
) -> Result<Vec<DriveFileRecord>, String> {
    let mut files = Vec::new();
    let mut page_token: Option<String> = None;
    loop {
        let mut url = Url::parse(&format!("{DRIVE_API}/files")).map_err(|e| e.to_string())?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("spaces", "appDataFolder");
            pairs.append_pair("q", query);
            pairs.append_pair("pageSize", "1000");
            pairs.append_pair(
                "fields",
                "nextPageToken,files(id,name,size,createdTime,modifiedTime,appProperties)",
            );
            if let Some(order) = order_by {
                pairs.append_pair("orderBy", order);
            }
            if let Some(token) = page_token.as_deref() {
                pairs.append_pair("pageToken", token);
            }
        }
        let page: DriveListResponse = drive_json(access_token, url).await?;
        files.extend(page.files);
        page_token = page.next_page_token;
        if page_token.is_none() {
            break;
        }
    }
    Ok(files)
}

async fn list_batches(
    access_token: &str,
    created_after: Option<i64>,
) -> Result<Vec<DriveFileRecord>, String> {
    let mut query = format!("name contains '{FILE_PREFIX}' and trashed = false");
    if let Some(after) = created_after.filter(|value| *value > 0) {
        if let Some(time) = DateTime::<Utc>::from_timestamp_millis(after) {
            query.push_str(&format!(
                " and createdTime > '{}'",
                time.to_rfc3339_opts(SecondsFormat::Millis, true)
            ));
        }
    }
    Ok(list_files(access_token, &query, Some("createdTime asc")).await?
        .into_iter()
        .filter(|file| file.name.starts_with(FILE_PREFIX) && file.name.ends_with(".json.gz"))
        .collect())
}

async fn find_exact_files(
    access_token: &str,
    name: &str,
) -> Result<Vec<DriveFileRecord>, String> {
    let escaped = name.replace('\\', "\\\\").replace('\'', "\\'");
    let query = format!("name = '{escaped}' and trashed = false");
    list_files(access_token, &query, None).await
}

fn latest_marker_version(files: &[DriveFileRecord]) -> Result<i64, String> {
    files.iter().try_fold(0, |latest, file| {
        Ok(latest.max(require_server_time(file.modified_time.as_deref(), "deletion marker")?))
    })
}

async fn get_disable_marker_version(access_token: &str) -> Result<i64, String> {
    latest_marker_version(&find_exact_files(access_token, DISABLE_MARKER_NAME).await?)
}

fn batch_generation(file: &DriveFileRecord) -> Option<i64> {
    match file.app_properties.get(APP_PROPERTY_GENERATION) {
        None => Some(0),
        Some(value) if !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit()) => {
            value.parse::<i64>().ok().filter(|value| (0..=MAX_WIRE_INTEGER).contains(value))
        }
        _ => None,
    }
}

fn batch_file_name(generation: i64, device_id: &str, batch_id: &str) -> String {
    format!("{FILE_PREFIX}g{generation}_{device_id}_{batch_id}.json.gz")
}

async fn download_bytes(access_token: &str, file: &DriveFileRecord) -> Result<Vec<u8>, String> {
    if let Some(size) = file
        .size
        .as_deref()
        .and_then(|value| value.parse::<usize>().ok())
    {
        if size > MAX_BATCH_BYTES {
            return Err(format!("Drive batch {} is too large", file.name));
        }
    }
    let url = format!("{DRIVE_API}/files/{}?alt=media", file.id);
    let response = send_drive_idempotent(
        http_client()?.get(url).bearer_auth(access_token)
    ).await?;
    if !response.status().is_success() {
        return Err(format!(
            "Drive download failed (HTTP {})",
            response.status()
        ));
    }
    let mut response = response;
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
        if bytes.len().saturating_add(chunk.len()) > MAX_BATCH_BYTES {
            return Err(format!("Drive batch {} is too large", file.name));
        }
        bytes.extend_from_slice(&chunk);
    }
    validate_compressed_metadata(file, &bytes)?;
    Ok(bytes)
}

fn validate_compressed_metadata(file: &DriveFileRecord, bytes: &[u8]) -> Result<(), String> {
    let declared_size = file.size.as_deref().and_then(|value| value.parse::<usize>().ok());
    let expected_hash = file.app_properties.get(APP_PROPERTY_SHA256);
    if declared_size != Some(bytes.len())
        || !expected_hash.is_some_and(|hash| valid_sha256(hash) && *hash == hex::encode(Sha256::digest(bytes)))
    {
        return Err("Malformed Drive history batch: size or SHA-256 does not match metadata".to_string());
    }
    Ok(())
}

fn encode_batch(batch: &WireBatch) -> Result<Vec<u8>, String> {
    validate_batch(batch)?;
    let json = serde_json::to_vec(batch).map_err(|e| e.to_string())?;
    if json.len() > MAX_BATCH_BYTES {
        return Err("Tempo Drive history batch exceeds the decompressed size limit".to_string());
    }
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(&json).map_err(|e| e.to_string())?;
    let compressed = encoder.finish().map_err(|e| e.to_string())?;
    if compressed.len() > MAX_BATCH_BYTES {
        return Err("Tempo Drive history batch exceeds the compressed size limit".to_string());
    }
    Ok(compressed)
}

fn decode_batch(bytes: &[u8]) -> Result<WireBatch, String> {
    if bytes.len() > MAX_BATCH_BYTES {
        return Err("Tempo Drive history batch exceeds the compressed size limit".to_string());
    }
    let decoder = GzDecoder::new(bytes);
    let mut decoded = Vec::new();
    decoder
        .take((MAX_BATCH_BYTES + 1) as u64)
        .read_to_end(&mut decoded)
        .map_err(|e| e.to_string())?;
    if decoded.len() > MAX_BATCH_BYTES {
        return Err("Drive history batch expands beyond the safe size limit".to_string());
    }
    let batch: WireBatch = serde_json::from_slice(&decoded).map_err(|e| e.to_string())?;
    validate_batch(&batch)?;
    Ok(batch)
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_identifier(value: &str, max_length: usize) -> bool {
    !value.is_empty() && value.len() <= max_length
        && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
}

fn valid_text(value: &str) -> bool {
    !value.trim().is_empty() && value.encode_utf16().count() <= 1000
}

fn valid_optional_text(value: &Option<String>) -> bool {
    value.as_deref().map_or(true, |value| value.encode_utf16().count() <= 1000)
}

fn validate_batch(batch: &WireBatch) -> Result<(), String> {
    if batch.schema_version != SCHEMA_VERSION
        || !valid_sha256(&batch.batch_id)
        || !valid_identifier(&batch.source_device_id, 200)
        || !valid_text(&batch.source_device_name)
        || !valid_identifier(&batch.source_platform, 100)
        || !(1..=MAX_WIRE_INTEGER).contains(&batch.created_at_utc)
        || batch.events.is_empty() || batch.events.len() > 1000
        || !batch.events.iter().all(valid_event)
        || batch.batch_id != batch_id(&batch.events)
    {
        return Err("Unsupported or malformed Tempo Drive history batch".to_string());
    }
    Ok(())
}

fn matches_batch_metadata(file: &DriveFileRecord, batch: &WireBatch) -> bool {
    batch_generation(file).is_some_and(|generation| {
        file.name == batch_file_name(generation, &batch.source_device_id, &batch.batch_id)
    }) && file.app_properties.get("tempo_kind").map(String::as_str) == Some("history_batch")
        && file.app_properties.get("tempo_schema").map(String::as_str) == Some("1")
        && file.app_properties.get("source_device_id") == Some(&batch.source_device_id)
        && file.app_properties.get("source_platform") == Some(&batch.source_platform)
}

fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn event_id(device_id: &str, play: &LocalPlay) -> String {
    if let Some(origin) = play.origin_event_id.as_deref().filter(|value| valid_sha256(value)) {
        return origin.to_string();
    }
    sha256_hex(&format!(
        "tempo-history-v1|{}|{}|{}|{}|{}",
        device_id,
        play.id,
        play.timestamp_utc,
        play.title.trim().to_lowercase(),
        play.artist.trim().to_lowercase()
    ))
}

/// Stable provenance shared by the LAN and Google Drive transports.
 /// A paired Android phone can use this exact origin to avoid re-uploading
 /// a Desktop event under a new Android-owned identity.
/// Fetch previously published IDs alongside the producer ID in one DB read,
/// so LAN and Drive agree even after a local title/artist correction.
pub(crate) fn lan_origin_metadata(
    app_data_dir: &Path,
    local_ids: &[i64],
) -> Result<(String, HashMap<i64, String>), String> {
    let conn = open_sync_db(app_data_dir)?;
    let device_id = load_state(&conn)?.device_id;
    let mut known = HashMap::new();
    let mut stmt = conn.prepare(
        "SELECT origin_event_id FROM drive_event_state
         WHERE scrobble_id = ?1 AND drive_imported = 0",
    ).map_err(|e| e.to_string())?;
    for id in local_ids {
        let origin: Option<String> = stmt.query_row([id], |row| row.get(0))
            .optional().map_err(|e| e.to_string())?;
        if let Some(origin) = origin.filter(|value| valid_sha256(value)) {
            known.insert(*id, origin);
        }
    }
    Ok((device_id, known))
}

/// Pin a local play's producer identity in SQLite before either LAN or
/// Drive transmits it. This survives retries, metadata edits and process death.
fn pin_local_origin(
    conn: &Connection,
    device_id: &str,
    id: i64,
    timestamp_utc: i64,
    title: &str,
    artist: &str,
) -> Result<String, String> {
    let generated = lan_play_origin(device_id, id, timestamp_utc, title, artist);
    let state = load_state(conn)?;
    let subject = if state.enabled {
        state.account_subject
    } else {
        Some(LEGACY_UNVERIFIED_ACCOUNT.to_string())
    };
    conn.execute(
        "INSERT OR IGNORE INTO drive_event_state
         (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at, owner_account_subject)
         VALUES (?1, ?2, ?3, 0, NULL, ?4)",
        params![id, generated, device_id, subject],
    ).map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE drive_event_state SET owner_account_subject = ?2
         WHERE scrobble_id = ?1 AND drive_imported = 0
           AND owner_account_subject IS NULL AND ?2 IS NOT NULL",
        params![id, subject],
    ).map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE drive_event_state SET origin_event_id = ?2, origin_device_id = ?3
         WHERE scrobble_id = ?1 AND drive_imported = 0 AND origin_event_id IS NULL
           AND (origin_device_id IS NULL OR origin_device_id = ?3)",
        params![id, generated, device_id],
    ).map_err(|e| e.to_string())?;
    let (origin, owner, imported): (Option<String>, Option<String>, i64) = conn
        .query_row(
            "SELECT origin_event_id, origin_device_id, drive_imported
             FROM drive_event_state WHERE scrobble_id = ?1",
            [id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        ).map_err(|e| e.to_string())?;
    let origin = origin.ok_or("Tempo could not persist the original Desktop event ID")?;
    if imported != 0 || owner.as_deref() != Some(device_id) || !valid_sha256(&origin) {
        return Err("A remote or invalid event cannot be sent as a local Desktop origin".into());
    }
    remember_origin(conn, id, device_id, &origin)?;
    Ok(origin)
}

/// Read each queued LAN playback's verified Google owner. Do not substitute
/// the account selected *today* for a recording owned by a previous account.
pub(crate) fn lan_play_account_owners(
    app_data_dir: &Path,
    play_ids: &[i64],
) -> Result<HashMap<i64, String>, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    // An explicit Drive disconnect disables cloud publication, including a
    // relay through an otherwise paired Android client.
    if !state.enabled || state.account_subject.is_none() {
        return Ok(HashMap::new());
    }
    let current_subject = state.account_subject.unwrap();
    let mut stmt = conn.prepare(
        "SELECT owner_account_subject FROM drive_event_state
         WHERE scrobble_id = ?1 AND drive_imported = 0
           AND COALESCE(cloud_suppressed, 0) = 0",
    ).map_err(|e| e.to_string())?;
    let mut owners = HashMap::new();
    for id in play_ids {
        let owner: Option<String> = stmt.query_row([id], |row| row.get(0))
            .optional().map_err(|e| e.to_string())?.flatten();
        if let Some(owner) = owner.filter(|v|
            !v.is_empty() && v != LEGACY_UNVERIFIED_ACCOUNT && !v.contains('@')
        ) {
            if owner == current_subject {
                owners.insert(*id, owner);
            }
        }
    }
    Ok(owners)
}

/// Ensure LAN-first captures have the exact identity later used by Drive.
/// The identity is saved atomically even if LAN delivery fails or is retried.
pub(crate) fn persist_lan_origin_metadata(
    app_data_dir: &Path,
    captures: &[(i64, i64, String, String)],
) -> Result<(String, HashMap<i64, String>), String> {
    let conn = open_sync_db(app_data_dir)?;
    let device_id = load_state(&conn)?.device_id;
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let mut origins = HashMap::new();
    for (id, timestamp_utc, title, artist) in captures {
        let origin = pin_local_origin(
            &tx, &device_id, *id, *timestamp_utc, title, artist
        )?;
        origins.insert(*id, origin);
    }
    tx.commit().map_err(|e| e.to_string())?;
    Ok((device_id, origins))
}

pub(crate) fn lan_play_origin(
    device_id: &str,
    local_id: i64,
    timestamp_utc: i64,
    title: &str,
    artist: &str,
) -> String {
    sha256_hex(&format!(
        "tempo-history-v1|{}|{}|{}|{}|{}",
        device_id,
        local_id,
        timestamp_utc,
        title.trim().to_lowercase(),
        artist.trim().to_lowercase()
    ))
}

fn batch_id(events: &[WireEvent]) -> String {
    let mut canonical = String::from("tempo-batch-v1");
    for event in events {
        canonical.push('|');
        canonical.push_str(&event.event_id);
    }
    sha256_hex(&canonical)
}

fn protocol_volume(play: &LocalPlay) -> Option<i64> {
    if play.is_muted {
        return Some(0);
    }
    if !play.volume_level.is_finite() || play.volume_level < 0.0 {
        return None;
    }
    if play.volume_level == 0.0 { return Some(0); }
    // Desktop stores fractional volume. Positive gain must never become mute;
    // MPRIS amplification above 1.0 is capped at the protocol's 100 percent.
    Some(((play.volume_level.clamp(0.0, 1.0) * 100.0).round() as i64).max(1))
}

fn local_to_wire(device_id: &str, play: &LocalPlay) -> WireEvent {
    WireEvent {
        event_id: event_id(device_id, play),
        title: play.title.clone(),
        artist: play.artist.clone(),
        album: (!play.album.is_empty()).then(|| play.album.clone()),
        timestamp_utc: play.timestamp_utc,
        duration_ms: play.duration_ms.max(0),
        listened_ms: play.listened_ms.max(0),
        source_app: if play.source_app.is_empty() {
            "desktop".to_string()
        } else {
            play.source_app.clone()
        },
        source: format!(
            "desktop:{}",
            if play.source_app.is_empty() {
                "unknown"
            } else {
                &play.source_app
            }
        ),
        skipped: play.skipped,
        replay_count: play.replay_count.max(0),
        completion_percentage: play.completion_percentage.round().clamp(0.0, 100.0) as i64,
        pause_count: play.pause_count.max(0),
        seek_count: play.seek_count.max(0),
        session_id: (!play.session_id.is_empty()).then(|| play.session_id.clone()),
        site: (!play.site.is_empty()).then(|| play.site.clone()),
        content_type: if play.content_type.is_empty() {
            "MUSIC".to_string()
        } else {
            play.content_type.clone()
        },
        volume_level: protocol_volume(play),
        total_pause_duration_ms: 0,
        position_updates_count: 0,
    }
}

fn pending_local_plays(conn: &Connection) -> Result<Vec<LocalPlay>, String> {
    pending_local_plays_page(conn, None, MAX_LOCAL_SCAN)
}

// The ordered keyset cursor keeps scanning after invalid records without
// relying on OFFSET (the pending set shrinks as good records are uploaded).
fn pending_local_plays_page(
    conn: &Connection,
    after: Option<(i64, i64)>,
    limit: usize,
) -> Result<Vec<LocalPlay>, String> {
    let subject = load_state(conn)?.account_subject;
    let mut stmt = conn
        .prepare(
            "SELECT s.id, s.title, s.artist, s.album, s.duration_ms, s.timestamp_utc,
                    s.source_app, s.listened_ms, s.skipped, s.replay_count, s.is_muted,
                    s.completion_percentage, s.pause_count, s.seek_count, s.session_id,
                    s.site, s.content_type, s.volume_level, d.origin_event_id
             FROM scrobbles s
             LEFT JOIN drive_event_state d ON d.scrobble_id = s.id
             WHERE COALESCE(d.drive_imported, 0) = 0 AND d.drive_uploaded_at IS NULL
               AND COALESCE(d.cloud_suppressed, 0) = 0
               AND (?2 IS NULL OR d.owner_account_subject IS NULL OR d.owner_account_subject = ?2)
               AND (?3 IS NULL OR s.timestamp_utc > ?3
                    OR (s.timestamp_utc = ?3 AND s.id > ?4))
             ORDER BY s.timestamp_utc ASC, s.id ASC LIMIT ?1",
        )
        .map_err(|e| e.to_string())?;
    let rows = stmt
        .query_map(params![limit as i64, subject, after.map(|v| v.0), after.map(|v| v.1)], |row| {
            Ok(LocalPlay {
                id: row.get(0)?,
                origin_event_id: row.get(18)?,
                title: row.get(1)?,
                artist: row.get(2)?,
                // Older local SQLite schemas permitted NULL in fields with
                // defaults. Treat NULL as an absent optional value, rather than
                // allowing one legacy row to abort all history scanning.
                album: row.get::<_, Option<String>>(3)?.unwrap_or_default(),
                duration_ms: row.get::<_, Option<i64>>(4)?.unwrap_or(0),
                timestamp_utc: row.get(5)?,
                source_app: row.get::<_, Option<String>>(6)?.unwrap_or_default(),
                listened_ms: row.get::<_, Option<i64>>(7)?.unwrap_or(0),
                skipped: row.get::<_, Option<i64>>(8)?.unwrap_or(0) != 0,
                replay_count: row.get::<_, Option<i64>>(9)?.unwrap_or(0),
                is_muted: row.get::<_, Option<i64>>(10)?.unwrap_or(0) != 0,
                completion_percentage: row.get::<_, Option<f64>>(11)?.unwrap_or(0.0),
                pause_count: row.get::<_, Option<i64>>(12)?.unwrap_or(0),
                seek_count: row.get::<_, Option<i64>>(13)?.unwrap_or(0),
                session_id: row.get::<_, Option<String>>(14)?.unwrap_or_default(),
                site: row.get::<_, Option<String>>(15)?.unwrap_or_default(),
                content_type: row.get::<_, Option<String>>(16)?.unwrap_or_default(),
                volume_level: row.get::<_, Option<f64>>(17)?.unwrap_or(-1.0),
            })
        })
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

fn verified_upload(file: &DriveFileRecord, name: &str, device: &str, generation: i64, bytes: &[u8]) -> bool {
    file.name == name && validate_compressed_metadata(file, bytes).is_ok()
        && file.app_properties.get("tempo_kind").map(String::as_str) == Some("history_batch")
        && file.app_properties.get("tempo_schema").map(String::as_str) == Some("1")
        && file.app_properties.get("source_device_id").map(String::as_str) == Some(device)
        && file.app_properties.get("source_platform").map(String::as_str) == Some("desktop")
        && batch_generation(file) == Some(generation)
}

async fn upload_batch(
    access_token: &str,
    file_name: &str,
    device_id: &str,
    generation: i64,
    compressed: &[u8],
) -> Result<(), String> {
    let existing = find_exact_files(access_token, file_name).await?;
    if existing.iter().any(|file| verified_upload(file, file_name, device_id, generation, compressed)) {
        return Ok(());
    }
    // Invalid same-name objects are not proof that the upload succeeded.
    // Event IDs deduplicate a verified replacement if an old upload also exists.


    let boundary = format!("tempo_{}", Uuid::new_v4().simple());
    let metadata = serde_json::json!({
        "name": file_name,
        "parents": ["appDataFolder"],
        "appProperties": {
            "tempo_kind": "history_batch",
            "tempo_schema": SCHEMA_VERSION.to_string(),
            "source_device_id": device_id,
            "source_platform": "desktop",
            APP_PROPERTY_GENERATION: generation.to_string(),
            APP_PROPERTY_SHA256: hex::encode(Sha256::digest(compressed))
        }
    });
    let mut body = Vec::new();
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{}\r\n",
            metadata
        )
        .as_bytes(),
    );
    body.extend_from_slice(
        format!("--{boundary}\r\nContent-Type: application/gzip\r\n\r\n").as_bytes(),
    );
    body.extend_from_slice(compressed);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

    let response = http_client()?
        .post(format!("{DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,size,createdTime,appProperties"))
        .bearer_auth(access_token)
        .header(header::CONTENT_TYPE, format!("multipart/related; boundary={boundary}"))
        .body(body)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("Drive upload failed (HTTP {})", response.status()));
    }
    let uploaded: DriveFileRecord = response.json().await.map_err(|e| e.to_string())?;
    if !verified_upload(&uploaded, file_name, device_id, generation, compressed) {
        return Err("Google Drive returned mismatched metadata for the uploaded history batch".into());
    }
    Ok(())
}

#[derive(Default)]
struct DriveUploadProgress {
    uploaded: usize,
    rejected: usize,
    first_rejected_id: Option<i64>,
}

async fn upload_local_history(
    app_data_dir: &Path,
    access_token: &str,
    device_id: &str,
) -> Result<DriveUploadProgress, String> {
    let conn = open_sync_db(app_data_dir)?;
    let generation = load_state(&conn)?.accepted_disable_version.max(0);
    let mut progress = DriveUploadProgress::default();
    let mut after: Option<(i64, i64)> = None;
    loop {
        let pending = pending_local_plays_page(&conn, after, MAX_LOCAL_SCAN)?;
        if pending.is_empty() {
            break;
        }
        after = pending.last().map(|play| (play.timestamp_utc, play.id));
        let count = pending.len();
        let mut valid_plays = Vec::new();
        for play in &pending {
            let wire = local_to_wire(device_id, play);
            if valid_event(&wire) {
                valid_plays.push(play);
            } else {
                progress.rejected += 1;
                progress.first_rejected_id.get_or_insert(play.id);
                log::warn!(
                    "Cannot upload Desktop play {}: metadata exceeds protocol-v1 limits or required data is missing",
                    play.id
                );
            }
        }
        // An invalid legacy local record must not poison the other events in
        // its batch. The record stays in SQLite for repair and future retries.
        for chunk in valid_plays.chunks(BATCH_SIZE) {
            let pinned = {
                let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
                let mut pinned = HashMap::new();
                for play in chunk {
                    pinned.insert(play.id, pin_local_origin(
                        &tx, device_id, play.id, play.timestamp_utc, &play.title, &play.artist
                    )?);
                }
                tx.commit().map_err(|e| e.to_string())?;
                pinned
            };
            let events: Vec<WireEvent> = chunk.iter().map(|play| {
                let mut wire = local_to_wire(device_id, play);
                wire.event_id = pinned[&play.id].clone();
                wire
            }).collect();
            let id = batch_id(&events);
            let batch = WireBatch {
                schema_version: SCHEMA_VERSION,
                batch_id: id.clone(),
                source_device_id: device_id.to_string(),
                source_device_name: "Tempo Desktop".to_string(),
                source_platform: "desktop".to_string(),
                created_at_utc: events.iter().map(|event| event.timestamp_utc).max().unwrap_or(1),
                events,
            };
            let compressed = encode_batch(&batch)?;
            let file_name = batch_file_name(generation, device_id, &id);
            upload_batch(access_token, &file_name, device_id, generation, &compressed).await?;

            let uploaded_at = now_ms();
            let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
            for play in chunk {
                let origin = &pinned[&play.id];
                tx.execute(
                    "INSERT INTO drive_event_state
                     (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
                     VALUES (?1, ?2, ?3, 0, ?4)
                     ON CONFLICT(scrobble_id) DO UPDATE SET origin_event_id = excluded.origin_event_id,
                        origin_device_id = excluded.origin_device_id, drive_imported = 0,
                        drive_uploaded_at = excluded.drive_uploaded_at",
                    params![play.id, origin, device_id, uploaded_at],
                ).map_err(|e| e.to_string())?;
                remember_origin(&tx, play.id, device_id, &origin)?;
            }
            tx.commit().map_err(|e| e.to_string())?;
            progress.uploaded += chunk.len();
        }
        // Preserve the original per-run upload budget. A very large backlog
        // must not postpone incoming cloud history indefinitely; future syncs
        // resume. Invalid rows do not consume this useful-upload budget.
        if count < MAX_LOCAL_SCAN || progress.uploaded >= MAX_LOCAL_SCAN {
            break;
        }
    }
    Ok(progress)
}

fn valid_event(event: &WireEvent) -> bool {
    valid_sha256(&event.event_id)
        && valid_text(&event.title) && valid_text(&event.artist)
        && valid_text(&event.source_app) && valid_text(&event.source) && valid_text(&event.content_type)
        && valid_optional_text(&event.album) && valid_optional_text(&event.session_id) && valid_optional_text(&event.site)
        && (1..=MAX_WIRE_INTEGER).contains(&event.timestamp_utc)
        && [event.duration_ms, event.listened_ms, event.total_pause_duration_ms]
            .iter().all(|value| (0..=MAX_WIRE_INTEGER).contains(value))
        && [event.replay_count, event.pause_count, event.seek_count, event.position_updates_count]
            .iter().all(|value| (0..=i32::MAX as i64).contains(value))
        && (0..=100).contains(&event.completion_percentage)
        && event.volume_level.map_or(true, |value| (0..=100).contains(&value))
}

fn remember_origin(
    conn: &Connection,
    scrobble_id: i64,
    source_device_id: &str,
    origin_event_id: &str,
) -> Result<(), String> {
    let state_owner: Option<Option<String>> = conn.query_row(
        "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
        [scrobble_id], |row| row.get(0)
    ).optional().map_err(|e| e.to_string())?;
    let owner = state_owner.ok_or("Tempo cannot alias an event with no stored playback state")?
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| LEGACY_UNVERIFIED_ACCOUNT.to_string());
    let affected = conn.execute(
        "INSERT INTO drive_event_aliases
            (account_subject, origin_event_id, source_device_id, scrobble_id)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(account_subject, origin_event_id)
         DO UPDATE SET origin_event_id = excluded.origin_event_id
           WHERE scrobble_id = excluded.scrobble_id
             AND source_device_id = excluded.source_device_id",
        params![owner, origin_event_id, source_device_id, scrobble_id],
    )
    .map_err(|e| e.to_string())?;
    if affected != 1 {
        return Err("Tempo Drive event identity already belongs to another local play".to_string());
    }
    Ok(())
}

fn insert_remote_event_for_account(
    conn: &Connection,
    source_device_id: &str,
    event: &WireEvent,
    account_subject: &str,
) -> Result<bool, String> {
    let existing_origin: Option<i64> = conn
        .query_row(
            "SELECT scrobble_id FROM drive_event_aliases
             WHERE origin_event_id = ?1 AND account_subject = ?2
             UNION ALL SELECT scrobble_id FROM drive_event_state
             WHERE origin_event_id = ?1 AND owner_account_subject = ?2 LIMIT 1",
            params![event.event_id, account_subject],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?;
    if existing_origin.is_some() {
        return Ok(false);
    }

    // Narrow ±2s fallback for near-simultaneous captures from different
    // producers, matching the Android and browser importers. Exact origin
    // IDs remain authoritative; session IDs are source-local and cannot
    // establish independence between two separate capture applications.
    // Distinct IDs from the same device must never be merged temporally.
    let title = event.title.trim().to_lowercase();
    let artist = event.artist.trim().to_lowercase();
    let existing_temporal = {
        // SQLite lower() is ASCII-only. Normalize bounded candidates in Rust so
        // accented names and whitespace use the same comparison as other clients.
        let mut stmt = conn.prepare(
            "SELECT s.id, s.title, s.artist FROM scrobbles s
             LEFT JOIN drive_event_state d ON d.scrobble_id = s.id
             WHERE s.timestamp_utc BETWEEN ?1 AND ?2
               AND (d.origin_device_id IS NULL OR d.origin_device_id <> ?4)
               AND (CASE WHEN COALESCE(d.drive_imported, 0) != 0
                         THEN d.owner_account_subject = ?5
                         ELSE (d.owner_account_subject IS NULL
                               OR d.owner_account_subject = ?5) END)
               AND NOT EXISTS (SELECT 1 FROM drive_event_aliases a
                   WHERE a.scrobble_id = s.id AND a.source_device_id = ?4)
             ORDER BY abs(s.timestamp_utc - ?3) ASC, s.id ASC",
        ).map_err(|e| e.to_string())?;
        let candidates = stmt.query_map(
            params![
                event.timestamp_utc - TEMPORAL_DEDUP_MS,
                event.timestamp_utc + TEMPORAL_DEDUP_MS,
                event.timestamp_utc,
                source_device_id,
                account_subject
            ],
            |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?, row.get::<_, String>(2)?)),
        ).map_err(|e| e.to_string())?;
        let mut found = None;
        for candidate in candidates {
            let (id, candidate_title, candidate_artist) = candidate.map_err(|e| e.to_string())?;
            if candidate_title.trim().to_lowercase() == title
                && candidate_artist.trim().to_lowercase() == artist
            {
                found = Some(id);
                break;
            }
        }
        found
    };

    if let Some(id) = existing_temporal {
        // A local capture without a cloud owner may be reconciled with the
        // verified active Google account. Pin it before inserting an alias;
        // imported A rows never match B because of the temporal owner filter.
        conn.execute("UPDATE drive_event_state SET owner_account_subject = ?2
                      WHERE scrobble_id = ?1 AND owner_account_subject IS NULL",
            params![id, account_subject]).map_err(|e| e.to_string())?;
        // Legacy local rows can predate Drive origin-state initialization.
        // Claim their original Desktop identity BEFORE recording the remote
        // alias, otherwise an early incoming copy would classify the original
        // local play as an import and suppress its future Drive upload.
        let has_state: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM drive_event_state WHERE scrobble_id = ?1)",
            [id], |row| row.get(0),
        ).map_err(|e| e.to_string())?;
        if !has_state {
            let (local_title, local_artist, local_timestamp): (String, String, i64) =
                conn.query_row(
                    "SELECT title, artist, timestamp_utc FROM scrobbles WHERE id = ?1",
                    [id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                ).map_err(|e| e.to_string())?;
            let own_device = load_state(conn)?.device_id;
            pin_local_origin(conn, &own_device, id, local_timestamp, &local_title, &local_artist)?;
        }
        conn.execute(
            // A matching Desktop-origin play must remain Desktop-owned and
            // eligible for its own eventual Drive upload. Turning it into an
            // imported row here would silently strand the local producer event.
            "INSERT OR IGNORE INTO drive_event_state
             (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at, owner_account_subject)
             VALUES (?1, ?2, ?3, 1, ?4, ?5)",
            params![id, event.event_id, source_device_id, now_ms(), account_subject],
        )
        .map_err(|e| e.to_string())?;
        // The local row may have been created without a Drive session. Now
        // that it matches a verified account, claim its existing aliases too;
        // otherwise the same producer replay can evade exact-id deduplication.
        conn.execute("UPDATE drive_event_state SET owner_account_subject = ?2
                      WHERE scrobble_id = ?1 AND owner_account_subject IS NULL",
            params![id, account_subject]).map_err(|e| e.to_string())?;
        conn.execute("UPDATE drive_event_aliases SET account_subject = ?2
                      WHERE scrobble_id = ?1 AND account_subject = ?3",
            params![id, account_subject, LEGACY_UNVERIFIED_ACCOUNT])
            .map_err(|e| e.to_string())?;
        remember_origin(conn, id, source_device_id, &event.event_id)?;
        return Ok(false);
    }

    let volume = event
        .volume_level
        .map(|value| (value.clamp(0, 100) as f64) / 100.0)
        .unwrap_or(-1.0);
    conn.execute(
        "INSERT INTO scrobbles
         (title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms,
          skipped, replay_count, is_muted, completion_percentage, pause_count, seek_count,
          session_id, site, content_type, volume_level)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'synced', ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17)",
        params![
            event.title,
            event.artist,
            event.album.clone().unwrap_or_default(),
            event.duration_ms.max(0),
            event.timestamp_utc,
            if event.source_app.is_empty() { "Drive" } else { &event.source_app },
            event.listened_ms.max(0),
            event.skipped as i64,
            event.replay_count.max(0),
            event.volume_level == Some(0),
            event.completion_percentage.clamp(0, 100) as f64,
            event.pause_count.max(0),
            event.seek_count.max(0),
            event.session_id.clone().unwrap_or_default(),
            event.site.clone().unwrap_or_default(),
            if event.content_type.is_empty() { "MUSIC" } else { &event.content_type },
            volume,
        ],
    )
    .map_err(|e| e.to_string())?;
    let scrobble_id = conn.last_insert_rowid();
    conn.execute(
        "INSERT INTO drive_event_state
         (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at, owner_account_subject)
         VALUES (?1, ?2, ?3, 1, ?4, ?5)",
        params![scrobble_id, event.event_id, source_device_id, now_ms(), account_subject],
    )
    .map_err(|e| e.to_string())?;
    remember_origin(conn, scrobble_id, source_device_id, &event.event_id)?;
    Ok(true)
}

#[cfg(test)]
fn insert_remote_event(
    conn: &Connection,
    source_device_id: &str,
    event: &WireEvent,
) -> Result<bool, String> {
    let subject = load_state(conn)?.account_subject
        .unwrap_or_else(|| "test-account".to_string());
    insert_remote_event_for_account(conn, source_device_id, event, &subject)
}

async fn download_remote_history(
    app_data_dir: &Path,
    access_token: &str,
    device_id: &str,
    include_own_device_batches: bool,
) -> Result<(usize, usize), String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    let account_subject = state.account_subject.as_deref()
        .filter(|subject| !subject.trim().is_empty())
        .ok_or("Google account identity is missing; reconnect securely")?;
    let accepted_generation = state.accepted_disable_version.max(0);
    let after =
        (state.download_cursor > 0).then_some((state.download_cursor - DOWNLOAD_OVERLAP_MS).max(0));
    let files = list_batches(access_token, after).await?;
    let mut imported = 0usize;
    let mut duplicates = 0usize;
    let mut max_created = state.download_cursor;

    for (index, file) in files.into_iter().enumerate() {
        // Incremental checkpoints make ten-year history recovery resumable
        // after a process crash, without advancing past an uncommitted batch.
        if index > 0 && index % 50 == 0 && max_created > state.download_cursor {
            conn.execute(
                "UPDATE drive_sync_state SET download_cursor = ?1 WHERE id = 1",
                [max_created],
            ).map_err(|e| e.to_string())?;
        }
        let created = parse_time_ms(file.created_time.as_deref());
        let Some(generation) = batch_generation(&file) else {
            log::warn!("Skipping Drive history batch with invalid generation: {}", file.name);
            max_created = max_created.max(created);
            continue;
        };
        if generation < accepted_generation {
            // Never allow a pre-delete batch (including an upload that completed
            // after the deletion request) to resurrect in a newly accepted
            // generation. Cleanup is best-effort so current history can proceed.
            if let Err(err) = delete_file(access_token, &file.id).await {
                log::warn!(
                    "Could not remove stale Drive history batch {}: {}",
                    file.name,
                    err
                );
            }
            max_created = max_created.max(created);
            continue;
        }

        if file
            .app_properties
            .get("source_device_id")
            .map(String::as_str)
            == Some(device_id)
            && !include_own_device_batches
        {
            max_created = max_created.max(created);
            continue;
        }

        if file
            .size
            .as_deref()
            .and_then(|value| value.parse::<usize>().ok())
            .is_some_and(|size| size > MAX_BATCH_BYTES)
        {
            log::warn!("Skipping oversized Drive history batch {}", file.name);
            max_created = max_created.max(created);
            continue;
        }

        let bytes = match download_bytes(access_token, &file).await {
            Ok(bytes) => bytes,
            Err(err) if err.contains("too large") || err.starts_with("Malformed Drive history batch:") => {
                // Permanently malformed/hostile payload. Consume this one file so
                // it cannot block every later batch forever.
                log::warn!(
                    "Skipping invalid Drive history batch {}: {}",
                    file.name,
                    err
                );
                max_created = max_created.max(created);
                continue;
            }
            Err(err) => {
                return Err(format!(
                    "Could not download Drive history batch {}: {}",
                    file.name, err
                ));
            }
        };
        let batch = match decode_batch(&bytes) {
            Ok(batch) => batch,
            Err(err) => {
                log::warn!(
                    "Skipping malformed Drive history batch {}: {}",
                    file.name,
                    err
                );
                max_created = max_created.max(created);
                continue;
            }
        };
        if !matches_batch_metadata(&file, &batch) {
            log::warn!("Skipping history batch whose payload does not match Drive metadata: {}", file.name);
            max_created = max_created.max(created);
            continue;
        }
        if !include_own_device_batches && batch.source_device_id == device_id {
            max_created = max_created.max(created);
            continue;
        }
        let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
        for event in &batch.events {
            if insert_remote_event_for_account(&transaction, &batch.source_device_id, event, account_subject)? {
                imported += 1;
            } else {
                duplicates += 1;
            }
        }
        transaction.commit().map_err(|e| e.to_string())?;
        max_created = max_created.max(created);
    }

    if max_created > state.download_cursor {
        conn.execute(
            "UPDATE drive_sync_state SET download_cursor = ?1 WHERE id = 1",
            [max_created],
        )
        .map_err(|e| e.to_string())?;
    }
    Ok((imported, duplicates))
}

async fn delete_file(access_token: &str, id: &str) -> Result<(), String> {
    let response = send_drive_idempotent(
        http_client()?.delete(format!("{DRIVE_API}/files/{id}"))
            .bearer_auth(access_token)
    ).await?;
    if response.status().is_success() || response.status().as_u16() == 404 {
        Ok(())
    } else {
        Err(format!("Drive delete failed (HTTP {})", response.status()))
    }
}

// Owner-initiated cleanup removes invalid-generation Tempo history files.
// A stale client may only delete provably older generations, never unknowns.
fn should_delete_history_batch(file: &DriveFileRecord, generation: i64, owner_delete: bool) -> bool {
    if !file.name.starts_with(FILE_PREFIX) {
        return false;
    }
    if !file.name.ends_with(".json.gz") {
        return owner_delete;
    }
    match batch_generation(file) {
        Some(file_generation) => file_generation < generation,
        None => owner_delete,
    }
}

async fn delete_batches_before_generation(
    access_token: &str,
    generation: i64,
    owner_delete: bool,
) -> Result<usize, String> {
    // Include even malformed filenames in Tempo's private history namespace:
    // explicit cloud deletion must not strand an invalid old upload.
    let files = list_files(access_token,
        &format!("name contains '{FILE_PREFIX}' and trashed = false"), None).await?;
    let mut deleted = 0usize;
    for file in files {
        if !should_delete_history_batch(&file, generation, owner_delete) {
            continue;
        }
        delete_file(access_token, &file.id).await?;
        deleted += 1;
    }
    Ok(deleted)
}

async fn bump_disable_marker(access_token: &str) -> Result<i64, String> {
    let previous = get_disable_marker_version(access_token).await?;
    let marker_body = serde_json::json!({
        "schema_version": 1, "history_sync_disabled": true, "revision": Uuid::new_v4().to_string()
    }).to_string();
    if find_exact_files(access_token, DISABLE_MARKER_NAME).await?.is_empty() {
        let boundary = format!("tempo_{}", Uuid::new_v4().simple());
        let metadata = serde_json::json!({
            "name": DISABLE_MARKER_NAME,
            "parents": ["appDataFolder"],
            "appProperties": {
                "tempo_kind": "history_sync_control",
                "tempo_schema": "1"
            }
        });
        let body = format!(
            "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: application/json\r\n\r\n{marker_body}\r\n--{boundary}--\r\n"
        );
        let response = http_client()?
            .post(format!(
                "{DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,modifiedTime"
            ))
            .bearer_auth(access_token)
            .header(
                header::CONTENT_TYPE,
                format!("multipart/related; boundary={boundary}"),
            )
            .body(body)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!(
                "Could not create Drive disable marker (HTTP {})",
                response.status()
            ));
        }
        let _: DriveFileRecord = response.json().await.map_err(|e| e.to_string())?;
    }
    // Concurrent first-use clients may create multiple same-name markers. Read
    // and update all of them so every client observes the newest server version.
    for attempt in 0..3 {
        let markers = find_exact_files(access_token, DISABLE_MARKER_NAME).await?;
        let mut updated = Vec::new();
        for marker in markers {
            let response = send_drive_idempotent(
                http_client()?
                    .patch(format!("{DRIVE_UPLOAD_API}/files/{}?uploadType=media&fields=id,name,modifiedTime", marker.id))
                    .bearer_auth(access_token)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(marker_body.clone())
            ).await?;
            if !response.status().is_success() {
                return Err(format!("Could not update Drive disable marker (HTTP {})", response.status()));
            }
            updated.push(response.json::<DriveFileRecord>().await.map_err(|e| e.to_string())?);
        }
        let version = latest_marker_version(&updated)?;
        if version > previous { return Ok(version); }
        if attempt < 2 { tokio::time::sleep(Duration::from_millis(5)).await; }
    }
    Err("Google Drive did not advance the deletion marker version".into())
}

fn accept_deletion_marker(conn: &Connection, marker_version: i64, message: Option<&str>) -> Result<(), String> {
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let subject: String = tx.query_row(
        "SELECT account_subject FROM drive_sync_state WHERE id = 1",
        [], |row| row.get::<_, Option<String>>(0)
    ).map_err(|e| e.to_string())?
        .filter(|value| !value.trim().is_empty())
        .ok_or("Cannot apply a Drive deletion without a verified Google account")?;

    // Deleting the cloud archive must not silently cause its restoration.
    // Keep uploaded_at unchanged and suppress all older local captures.
    tx.execute(
        "INSERT OR IGNORE INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject, cloud_suppressed)
         SELECT s.id, 0, ?1, 1 FROM scrobbles s
         WHERE NOT EXISTS (SELECT 1 FROM drive_event_state d WHERE d.scrobble_id = s.id)",
        [&subject],
    ).map_err(|e| e.to_string())?;
    tx.execute(
        "UPDATE drive_event_state SET
             owner_account_subject = COALESCE(owner_account_subject, ?1),
             cloud_suppressed = 1
         WHERE drive_imported = 0
           AND (owner_account_subject IS NULL OR owner_account_subject = ?1)",
        [&subject],
    ).map_err(|e| e.to_string())?;
    tx.execute(
        "UPDATE drive_sync_state SET enabled = 0, accepted_disable_version = ?1,
         download_cursor = 0, last_uploaded = 0, last_imported = 0, last_error = ?2 WHERE id = 1",
        params![marker_version, message],
    ).map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())
}

async fn honor_remote_disable_if_needed(
    app_data_dir: &Path,
    access_token: &str,
) -> Result<bool, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    let marker_version = get_disable_marker_version(access_token).await?;
    if marker_version <= state.accepted_disable_version {
        return Ok(false);
    }

    accept_deletion_marker(&conn, marker_version, Some(
        "Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history."
    ))?;
    // Cleanup is allowed to fail after the local stop has been committed.
    delete_batches_before_generation(access_token, marker_version, false).await?;
    Ok(true)
}

async fn run_sync(app_data_dir: &Path) -> Result<DriveSyncResult, String> {
    let _guard = SYNC_LOCK.lock().await;
    run_sync_locked(app_data_dir).await
}

async fn run_sync_locked(app_data_dir: &Path) -> Result<DriveSyncResult, String> {
    run_sync_locked_with_restore(app_data_dir, false).await
}

async fn run_sync_locked_with_restore(
    app_data_dir: &Path,
    include_own_device_batches: bool,
) -> Result<DriveSyncResult, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    if !state.enabled {
        return Ok(DriveSyncResult {
            uploaded: 0,
            imported: 0,
            duplicates: 0,
            disabled_by_remote_delete: false,
        });
    }
    drop(conn);

    let token = access_token(app_data_dir).await?;
    if honor_remote_disable_if_needed(app_data_dir, &token).await? {
        return Ok(DriveSyncResult {
            uploaded: 0,
            imported: 0,
            duplicates: 0,
            disabled_by_remote_delete: true,
        });
    }

    let conn = open_sync_db(app_data_dir)?;
    let device_id = load_state(&conn)?.device_id;
    drop(conn);

    // A transient upload error must not prevent receiving other devices'
    // history. Both transports are attempted before surfacing an error.
    let upload = upload_local_history(app_data_dir, &token, &device_id).await;
    let download = download_remote_history(
        app_data_dir, &token, &device_id, include_own_device_batches
    ).await;
    let (imported, duplicates) = download?;
    let progress = upload?;
    let warning = progress.first_rejected_id.map(|first_id| format!(
        "{} local Desktop play(s) could not be exported (first local ID {}). They remain saved locally. Correct their metadata to retry.",
        progress.rejected, first_id
    ));
    let conn = open_sync_db(app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET last_sync_time = ?1, last_error = ?4,
         last_uploaded = ?2, last_imported = ?3 WHERE id = 1",
        params![now_ms(), progress.uploaded as i64, imported as i64, warning],
    ).map_err(|e| e.to_string())?;
    Ok(DriveSyncResult {
        uploaded: progress.uploaded,
        imported,
        duplicates,
        disabled_by_remote_delete: false,
    })
}

async fn status_for(app_data_dir: &Path) -> Result<DriveSyncStatus, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    drop(conn);

    let has_valid_access = state
        .access_token
        .as_deref()
        .is_some_and(|value| !value.is_empty())
        && state.token_expires_at > now_ms() + 60_000;
    let has_legacy_refresh = state
        .refresh_token
        .as_deref()
        .is_some_and(|value| !value.is_empty());
    let has_secure_refresh = if state.enabled && !has_legacy_refresh {
        secure_refresh_token_get(&state.device_id,
            state.account_subject.as_deref().unwrap_or_default())
            .await
            .unwrap_or(None)
            .is_some()
    } else {
        false
    };
    let has_account_identity = state
        .account_email
        .as_deref()
        .is_some_and(|value| !value.trim().is_empty());
    let connected = state.enabled
        && has_account_identity
        && state.account_subject.as_deref().is_some_and(|v| !v.trim().is_empty())
        && (has_valid_access || has_legacy_refresh || has_secure_refresh);

    Ok(DriveSyncStatus {
        enabled: state.enabled,
        configured: oauth_client_id().is_some(),
        connected,
        account_email: if connected { state.account_email } else { None },
        last_sync_time: state.last_sync_time,
        last_error: state.last_error,
        last_uploaded: state.last_uploaded,
        last_imported: state.last_imported,
    })
}

#[tauri::command]
pub async fn drive_get_status(state: State<'_, AppState>) -> Result<DriveSyncStatus, String> {
    status_for(&state.app_data_dir).await
}

#[tauri::command]
pub async fn drive_connect(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<DriveSyncStatus, String> {
    // Account selection, credential replacement and the first sync share the
    // same lifecycle lock as disconnect/delete/background sync.
    let _guard = SYNC_LOCK.lock().await;
    interactive_oauth(&app, &state.app_data_dir).await?;
    let token = access_token(&state.app_data_dir).await?;
    let marker_version = get_disable_marker_version(&token).await?;

    let conn = open_sync_db(&state.app_data_dir)?;
    // Reconnection never implicitly republishes history suppressed by deletion.
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, accepted_disable_version = ?1,
         download_cursor = CASE WHEN ?1 > accepted_disable_version THEN 0 ELSE download_cursor END,
         last_error = NULL WHERE id = 1",
        [marker_version],
    )
    .map_err(|e| e.to_string())?;
    drop(conn);

    if let Err(err) = run_sync_locked(&state.app_data_dir).await {
        let conn = open_sync_db(&state.app_data_dir)?;
        let _ = set_last_error(&conn, Some(&err));
        return Err(err);
    }
    status_for(&state.app_data_dir).await
}

#[tauri::command]
pub async fn drive_disconnect(state: State<'_, AppState>) -> Result<DriveSyncStatus, String> {
    let _guard = SYNC_LOCK.lock().await;
    let conn = open_sync_db(&state.app_data_dir)?;
    let before = load_state(&conn)?;
    // Commit a local stop first, even if keyring cleanup subsequently fails.
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0, access_token = NULL, refresh_token = NULL,
         token_expires_at = 0, last_error = NULL WHERE id = 1",
        [],
    ).map_err(|e| e.to_string())?;
    drop(conn);

    // Google's OAuth revoke endpoint revokes the *project-wide* grant,
    // potentially disconnecting Android, Chrome and Firefox as well.
    // A Desktop-only disconnect therefore only deletes the local credential.
    if let Err(err) = secure_refresh_token_delete(&before.device_id).await {
        return Err(format!(
            "Google Drive was disabled locally, but Tempo could not remove its OS credential: {err}"
        ));
    }
    status_for(&state.app_data_dir).await
}

#[tauri::command]
pub async fn drive_sync_now(state: State<'_, AppState>) -> Result<DriveSyncResult, String> {
    let result = run_sync(&state.app_data_dir).await;
    if let Err(err) = &result {
        if let Ok(conn) = open_sync_db(&state.app_data_dir) {
            let _ = set_last_error(&conn, Some(err));
        }
    }
    result
}

/// Explicit full-history recovery. Normal synchronization keeps a cheap 24-hour
/// overlap; this command deliberately re-enumerates every historical Drive batch.
/// It is safe to retry: origin IDs and alias records make imports idempotent.
#[tauri::command]
pub async fn drive_restore_all_history(state: State<'_, AppState>) -> Result<DriveSyncResult, String> {
    let _guard = SYNC_LOCK.lock().await;
    let conn = open_sync_db(&state.app_data_dir)?;
    if !load_state(&conn)?.enabled {
        return Err("Connect Google Drive before restoring history".to_string());
    }
    conn.execute("UPDATE drive_sync_state SET download_cursor = 0 WHERE id = 1", [])
        .map_err(|e| e.to_string())?;
    drop(conn);
    let result = run_sync_locked_with_restore(&state.app_data_dir, true).await;
    if let Err(err) = &result {
        if let Ok(conn) = open_sync_db(&state.app_data_dir) {
            let _ = set_last_error(&conn, Some(err));
        }
    }
    result
}

// An explicit opt-in can assign older local captures to the currently linked
// account. Imported cloud events are never rebroadcast as new local origins.
fn authorize_existing_local_history(conn: &Connection, subject: &str) -> Result<usize, String> {
    if subject.trim().is_empty() {
        return Err("Google account identity is missing".into());
    }
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    tx.execute(
        "INSERT OR IGNORE INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject)
         SELECT id, 0, ?1 FROM scrobbles", [subject]
    ).map_err(|e| e.to_string())?;
    let reassigned = tx.execute(
        "UPDATE drive_event_state SET owner_account_subject = ?1, drive_uploaded_at = NULL,
             cloud_suppressed = 0
         WHERE drive_imported = 0", [subject]
    ).map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(reassigned)
}

#[tauri::command]
pub async fn drive_share_existing_local_history(state: State<'_, AppState>) -> Result<DriveSyncResult, String> {
    let _guard = SYNC_LOCK.lock().await;
    let conn = open_sync_db(&state.app_data_dir)?;
    let account = load_state(&conn)?;
    if !account.enabled {
        return Err("Connect Google Drive before choosing to upload existing local history".into());
    }
    let subject = account.account_subject
        .ok_or("Google account identity is missing; reconnect securely")?;
    drop(conn);

    let token = access_token(&state.app_data_dir).await?;
    if honor_remote_disable_if_needed(&state.app_data_dir, &token).await? {
        return Err("Cloud history was deleted by another device; reconnect Google before uploading".into());
    }

    let conn = open_sync_db(&state.app_data_dir)?;
    authorize_existing_local_history(&conn, &subject)?;
    drop(conn);
    run_sync_locked(&state.app_data_dir).await
}

#[tauri::command]
pub async fn drive_delete_cloud_history(state: State<'_, AppState>) -> Result<usize, String> {
    let _guard = SYNC_LOCK.lock().await;
    let conn = open_sync_db(&state.app_data_dir)?;
    if !load_state(&conn)?.enabled {
        return Err("Connect Google Drive before deleting cloud history".to_string());
    }
    drop(conn);
    let token = access_token(&state.app_data_dir).await?;
    // Publish the shared generation marker first, then remove only older batches.
    // A client explicitly re-enabled after the marker update may safely publish
    // generation N while stale clients are still waking up and honoring deletion.
    let marker_version = bump_disable_marker(&token).await?;
    let conn = open_sync_db(&state.app_data_dir)?;
    accept_deletion_marker(&conn, marker_version, None)?;
    let result = delete_batches_before_generation(&token, marker_version, true).await;
    if let Err(err) = &result { let _ = set_last_error(&conn, Some(err)); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn history_storage_fixture() -> (std::path::PathBuf, Connection) {
        let directory = std::env::temp_dir().join(format!("tempo-drive-history-{}", Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        drop(crate::db::Database::new(&db_path(&directory)).unwrap());
        let conn = open_sync_db(&directory).unwrap();
        (directory, conn)
    }


    #[test]
    fn cross_account_imports_do_not_merge_same_title_and_timestamp() {
        let (directory, conn) = history_storage_fixture();
        let first = fixture_batch().events[0].clone();
        assert!(insert_remote_event_for_account(
            &conn, "remote-a", &first, "google-account-a").unwrap());
        let mut second = first.clone();
        second.event_id = "b".repeat(64);
        second.timestamp_utc += 500;
        assert!(insert_remote_event_for_account(
            &conn, "remote-b", &second, "google-account-b").unwrap(),
            "an imported play from account A cannot consume B's distinct event");
        let owners: Vec<String> = conn.prepare(
            "SELECT owner_account_subject FROM drive_event_state ORDER BY scrobble_id"
        ).unwrap().query_map([], |row| row.get(0)).unwrap()
            .collect::<Result<_, _>>().unwrap();
        assert_eq!(owners, vec!["google-account-a", "google-account-b"]);
        assert!(!insert_remote_event_for_account(
            &conn, "remote-b", &second, "google-account-b").unwrap());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn unknown_legacy_import_owner_is_quarantined_and_never_matches_new_account() {
        let (directory, conn) = history_storage_fixture();
        let first = fixture_batch().events[0].clone();
        assert!(insert_remote_event_for_account(
            &conn, "remote-a", &first, "google-account-a").unwrap());
        conn.execute("UPDATE drive_event_state SET owner_account_subject = NULL
             WHERE drive_imported = 1", []).unwrap();
        conn.execute("DELETE FROM drive_sync_migrations
             WHERE name = 'legacy_aliases_and_account_v1'", []).unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        let quarantined: String = conn.query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE drive_imported = 1",
            [], |row| row.get(0)
        ).unwrap();
        assert_eq!(quarantined, LEGACY_UNVERIFIED_ACCOUNT);
        let mut second = first.clone();
        second.event_id = "c".repeat(64);
        assert!(insert_remote_event_for_account(
            &conn, "remote-b", &second, "google-account-b").unwrap());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn the_same_producer_id_can_be_imported_into_two_google_accounts() {
        let (directory, conn) = history_storage_fixture();
        let original = fixture_batch().events[0].clone();
        assert!(insert_remote_event_for_account(
            &conn, "source-device", &original, "subject-a").unwrap());
        assert!(!insert_remote_event_for_account(
            &conn, "source-device", &original, "subject-a").unwrap());
        assert!(insert_remote_event_for_account(
            &conn, "source-device", &original, "subject-b").unwrap());
        let state_count: i64 = conn.query_row(
            "SELECT count(*) FROM drive_event_state
             WHERE origin_event_id = ?1",
            [&original.event_id], |row| row.get(0),
        ).unwrap();
        let alias_count: i64 = conn.query_row(
            "SELECT count(*) FROM drive_event_aliases
             WHERE origin_event_id = ?1",
            [&original.event_id], |row| row.get(0),
        ).unwrap();
        assert_eq!((state_count, alias_count), (2, 2));
        assert_eq!(conn.query_row(
            "SELECT count(DISTINCT account_subject) FROM drive_event_aliases
             WHERE origin_event_id = ?1",
            [&original.event_id], |row| row.get::<_, i64>(0),
        ).unwrap(), 2);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn scrobble_deletion_prunes_origin_rows_without_a_full_reopen_scan() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
        let id: i64 = conn.query_row("SELECT id FROM scrobbles", [],
            |row| row.get(0)).unwrap();
        conn.execute("DELETE FROM scrobbles WHERE id = ?1", [id]).unwrap();
        let (states, aliases): (i64, i64) = conn.query_row(
            "SELECT (SELECT count(*) FROM drive_event_state),
                    (SELECT count(*) FROM drive_event_aliases)",
            [], |row| Ok((row.get(0)?, row.get(1)?))
        ).unwrap();
        assert_eq!((states, aliases), (0, 0));
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn alias_backfill_runs_once_and_keeps_legacy_origin_mapping() {
        let (directory, conn) = history_storage_fixture();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
            VALUES ('Track', 'Artist', 1700000000000)", []).unwrap();
        let id = conn.last_insert_rowid();
        let origin = "d".repeat(64);
        conn.execute("INSERT INTO drive_event_state
            (scrobble_id, origin_event_id, origin_device_id, drive_imported)
            VALUES (?1, ?2, 'old-desktop', 0)", params![id, origin]).unwrap();
        conn.execute("DELETE FROM drive_sync_migrations
            WHERE name = 'legacy_aliases_and_account_v1'", []).unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        let mapped: i64 = conn.query_row(
            "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&origin], |row| row.get(0)
        ).unwrap();
        assert_eq!(mapped, id);
        conn.execute("DELETE FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&origin]).unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        let count: i64 = conn.query_row(
            "SELECT count(*) FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&origin], |row| row.get(0)
        ).unwrap();
        assert_eq!(count, 0, "backfill must not rescan all history on each open");
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn temporal_dedup_normalizes_accents_and_whitespace_and_skips_other_candidates() {
        let (directory, conn) = history_storage_fixture();
        let mut event = fixture_batch().events[0].clone();
        event.title = "été 🔊".into();
        event.artist = "björk".into();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('Other', 'Artist', ?1)",
            [event.timestamp_utc]).unwrap();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('  ÉTÉ 🔊  ', ' BJÖRK ', ?1)",
            [event.timestamp_utc + 500]).unwrap();
        let matching_id = conn.last_insert_rowid();
        assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
        let (count, recorded): (i64, i64) = conn.query_row(
            "SELECT (SELECT count(*) FROM scrobbles), scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&event.event_id], |row| Ok((row.get(0)?, row.get(1)?))
        ).unwrap();
        assert_eq!((count, recorded), (2, matching_id));
        assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn remote_copy_does_not_claim_a_legacy_unpinned_local_capture() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES (?1, ?2, ?3)",
            params![event.title, event.artist, event.timestamp_utc],
        ).unwrap();
        let local_id = conn.last_insert_rowid();
        assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
        let (imported, own_id, own_device): (i64, String, String) = conn.query_row(
            "SELECT drive_imported, origin_event_id, origin_device_id
             FROM drive_event_state WHERE scrobble_id = ?1",
            [local_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        ).unwrap();
        assert_eq!(imported, 0);
        assert_eq!(own_device, load_state(&conn).unwrap().device_id);
        assert_ne!(own_id, event.event_id);
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
        assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn remote_temporal_match_never_converts_local_capture_into_imported_play() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES (?1, ?2, ?3)",
            params![event.title, event.artist, event.timestamp_utc],
        ).unwrap();
        let local_id = conn.last_insert_rowid();
        let device_id = load_state(&conn).unwrap().device_id;
        let own_origin = pin_local_origin(
            &conn, &device_id, local_id, event.timestamp_utc,
            &event.title, &event.artist,
        ).unwrap();

        assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
        let (original_id, is_imported, sent_at): (String, i64, Option<i64>) =
            conn.query_row(
                "SELECT origin_event_id, drive_imported, drive_uploaded_at
                 FROM drive_event_state WHERE scrobble_id = ?1",
                [local_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            ).unwrap();
        assert_eq!(original_id, own_origin);
        assert_eq!(is_imported, 0, "a local capture cannot become a cloud import");
        assert_eq!(sent_at, None);
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1,
            "the Desktop producer must still be able to publish its own event");
        let alias_id: i64 = conn.query_row(
            "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&event.event_id], |row| row.get(0),
        ).unwrap();
        assert_eq!(alias_id, local_id);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }


    #[test]
    fn independent_cross_device_replay_seven_seconds_later_stays_distinct() {
        let (directory, conn) = history_storage_fixture();
        let original = fixture_batch().events[0].clone();
        let mut replay = original.clone();
        replay.event_id = "a".repeat(64);
        replay.timestamp_utc += 7_000;
        assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
        assert!(insert_remote_event(&conn, "device-two", &replay).unwrap(),
            "a 7-second independent replay must not be lost to temporal deduplication");
        let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
            |row| row.get(0)).unwrap();
        assert_eq!(count, 2);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn separate_capture_app_session_ids_do_not_hide_a_single_play() {
        let (directory, conn) = history_storage_fixture();
        let mut first = fixture_batch().events[0].clone();
        first.session_id = Some("session-one".into());
        let mut second = first.clone();
        second.session_id = Some("session-two".into());
        second.event_id = "a".repeat(64);
        second.timestamp_utc += 500;
        assert!(insert_remote_event(&conn, "device-one", &first).unwrap());
        assert!(!insert_remote_event(&conn, "device-two", &second).unwrap(),
            "source-local session IDs are not proof of distinct physical plays");
        let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
            |row| row.get(0)).unwrap();
        assert_eq!(count, 1);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn different_devices_playing_same_short_track_25_seconds_apart_stay_distinct() {
        let (directory, conn) = history_storage_fixture();
        let original = fixture_batch().events[0].clone();
        let mut second_play = original.clone();
        second_play.event_id = "a".repeat(64);
        second_play.timestamp_utc += 25_000;
        assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
        assert!(insert_remote_event(&conn, "device-two", &second_play).unwrap(),
            "a second 25-second replay on another device cannot be deduplicated");
        let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
            |row| row.get(0)).unwrap();
        assert_eq!(count, 2);
        assert!(!insert_remote_event(&conn, "device-two", &second_play).unwrap(),
            "the second play must still be idempotent by its exact event ID");
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn every_reconciled_origin_survives_restart_without_collapsing_rapid_replays() {
        let (directory, conn) = history_storage_fixture();
        let first = fixture_batch().events[0].clone();
        let mut second_capture = first.clone();
        second_capture.event_id = "b".repeat(64);
        second_capture.timestamp_utc += 50;
        let mut third_capture = first.clone();
        third_capture.event_id = "c".repeat(64);
        third_capture.timestamp_utc += 100;
        assert!(insert_remote_event(&conn, "device-a", &first).unwrap());
        assert!(!insert_remote_event(&conn, "device-b", &second_capture).unwrap());
        assert!(!insert_remote_event(&conn, "device-c", &third_capture).unwrap());
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        let mut replay = first.clone();
        replay.event_id = "d".repeat(64);
        replay.timestamp_utc += 1000;
        let mut replay_b = replay.clone();
        replay_b.event_id = "e".repeat(64);
        replay_b.timestamp_utc += 50;
        let mut replay_c = replay.clone();
        replay_c.event_id = "f".repeat(64);
        replay_c.timestamp_utc += 100;
        assert!(insert_remote_event(&conn, "device-a", &replay).unwrap());
        assert!(!insert_remote_event(&conn, "device-b", &replay_b).unwrap());
        assert!(!insert_remote_event(&conn, "device-c", &replay_c).unwrap());
        for (device, event) in [("device-a", &first), ("device-b", &second_capture),
            ("device-c", &third_capture), ("device-a", &replay), ("device-b", &replay_b), ("device-c", &replay_c)]
        {
            assert!(!insert_remote_event(&conn, device, event).unwrap());
        }
        let counts: (i64, i64) = conn.query_row(
            "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_aliases)",
            [], |row| Ok((row.get(0)?, row.get(1)?))
        ).unwrap();
        assert_eq!(counts, (2, 6));
        let primary_origins: Vec<String> = conn.prepare("SELECT origin_event_id FROM drive_event_state ORDER BY scrobble_id")
            .unwrap().query_map([], |row| row.get(0)).unwrap().collect::<Result<_, _>>().unwrap();
        assert_eq!(primary_origins, vec![first.event_id, replay.event_id]);
        assert!(pending_local_plays(&conn).unwrap().is_empty());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn legacy_origins_migrate_with_one_rescan_and_deleted_rows_release_their_aliases() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
        conn.execute_batch("DROP TABLE drive_event_aliases;
            DELETE FROM drive_sync_migrations WHERE name IN
                ('reconciled_origins_v1', 'legacy_aliases_and_account_v1');
            UPDATE drive_sync_state SET download_cursor = 123 WHERE id = 1;").unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        assert_eq!(load_state(&conn).unwrap().download_cursor, 0);
        let source: String = conn.query_row("SELECT source_device_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&event.event_id], |row| row.get(0)).unwrap();
        assert_eq!(source, "device-a");
        conn.execute("UPDATE drive_sync_state SET download_cursor = 999 WHERE id = 1", []).unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        assert_eq!(load_state(&conn).unwrap().download_cursor, 999);
        conn.execute("DELETE FROM scrobbles", []).unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        let counts: (i64, i64) = conn.query_row(
            "SELECT (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
            [], |row| Ok((row.get(0)?, row.get(1)?))
        ).unwrap();
        assert_eq!(counts, (0, 0));
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn origin_alias_failure_rolls_back_the_whole_import_transaction() {
        let (directory, conn) = history_storage_fixture();
        conn.execute_batch("CREATE TRIGGER fail_origin BEFORE INSERT ON drive_event_aliases
            BEGIN SELECT RAISE(ABORT, 'storage failure'); END;").unwrap();
        let transaction = conn.unchecked_transaction().unwrap();
        assert!(insert_remote_event(&transaction, "device-a", &fixture_batch().events[0]).is_err());
        drop(transaction);
        let counts: (i64, i64, i64) = conn.query_row(
            "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
            [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        ).unwrap();
        assert_eq!(counts, (0, 0, 0));
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rerecording_an_uploaded_origin_is_idempotent_and_cannot_move_its_identity() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
        let id: i64 = conn.query_row("SELECT id FROM scrobbles", [], |row| row.get(0)).unwrap();
        remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
        remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
        assert!(remember_origin(&conn, id + 1, "device-a", &event.event_id).is_err());
        assert!(remember_origin(&conn, id, "device-b", &event.event_id).is_err());
        let recorded: (i64, String) = conn.query_row("SELECT scrobble_id, source_device_id FROM drive_event_aliases",
            [], |row| Ok((row.get(0)?, row.get(1)?))).unwrap();
        assert_eq!(recorded, (id, "device-a".into()));
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    fn oauth_storage_fixture() -> (std::path::PathBuf, Connection) {
        let directory = std::env::temp_dir().join(format!("tempo-oauth-test-{}", Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let conn = Connection::open(db_path(&directory)).unwrap();
        conn.execute_batch("CREATE TABLE scrobbles (id INTEGER PRIMARY KEY); INSERT INTO scrobbles VALUES (1), (2);").unwrap();
        drop(conn);
        let conn = open_sync_db(&directory).unwrap();
        conn.execute_batch(
            "UPDATE drive_sync_state SET enabled = 1, account_email = 'old@example.com', account_subject = 'google-stable-123',
             access_token = 'old-access', refresh_token = 'old-refresh', token_expires_at = 9000000000000,
             download_cursor = 123, accepted_disable_version = 100 WHERE id = 1;
             INSERT INTO drive_event_state (scrobble_id, drive_imported, drive_uploaded_at) VALUES (1, 0, 200), (2, 1, 200);"
        ).unwrap();
        (directory, conn)
    }


    #[test]
    fn keyring_credentials_are_never_reused_across_google_subjects() {
        let account_a = serde_json::to_string(&SubjectBoundRefreshToken {
            sub: "google-a".into(), refresh_token: "secret-a".into(),
        }).unwrap();
        let account_b = serde_json::to_string(&SubjectBoundRefreshToken {
            sub: "google-b".into(), refresh_token: "secret-b".into(),
        }).unwrap();
        assert_eq!(decode_subject_bound_token(&account_a, "google-a").unwrap(), "secret-a");
        assert_eq!(decode_subject_bound_token(&account_b, "google-b").unwrap(), "secret-b");
        assert!(decode_subject_bound_token(&account_b, "google-a").is_err(),
            "an interrupted B sign-in must not later refresh account A with B's keyring token");
        assert!(decode_subject_bound_token("old-plaintext-refresh-token", "google-a").is_err(),
            "unbound legacy credentials require fresh consent");
    }

    #[tokio::test]
    async fn interrupted_credential_replacement_cannot_refresh_or_use_an_unknown_account() {
        let (directory, conn) = oauth_storage_fixture();
        prepare_oauth_credentials(&conn, true).unwrap();
        let state = load_state(&conn).unwrap();
        assert!(!state.enabled);
        assert!(state.account_email.is_none() && state.account_subject.is_none() && state.access_token.is_none() && state.refresh_token.is_none());
        assert_eq!((state.download_cursor, state.accepted_disable_version), (0, 0));
        drop(conn);
        assert!(access_token(&directory).await.unwrap_err().contains("identity is unavailable"));
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reconnecting_the_same_account_stops_before_replacement_and_preserves_cursors() {
        let (directory, conn) = oauth_storage_fixture();
        prepare_oauth_credentials(&conn, false).unwrap();
        let state = load_state(&conn).unwrap();
        assert!(!state.enabled && state.account_email.is_none() && state.account_subject.is_none());
        assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
        let uploaded: i64 = conn.query_row("SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)).unwrap();
        assert_eq!(uploaded, 200);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failure_to_prepare_credentials_rolls_back_cursors_before_any_keyring_write() {
        let (directory, conn) = oauth_storage_fixture();
        conn.execute_batch("CREATE TRIGGER fail_oauth_stop BEFORE UPDATE OF enabled ON drive_sync_state BEGIN SELECT RAISE(ABORT, 'storage failure'); END;").unwrap();
        assert!(prepare_oauth_credentials(&conn, true).is_err());
        let state = load_state(&conn).unwrap();
        assert!(state.enabled);
        assert_eq!(state.account_email.as_deref(), Some("old@example.com"));
        assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
        let uploaded: i64 = conn.query_row("SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)).unwrap();
        assert_eq!(uploaded, 200);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }


    #[test]
    fn switching_google_accounts_does_not_republish_previous_local_history() {
        let (directory, conn) = history_storage_fixture();
        conn.execute("UPDATE drive_sync_state SET account_subject = 'account-a',
            account_email = 'a@example.com', enabled = 1 WHERE id = 1", []).unwrap();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
            VALUES ('Old', 'Artist', 1700000000000)", []).unwrap();
        let old_id = conn.last_insert_rowid();
        let old_device = load_state(&conn).unwrap().device_id;
        pin_local_origin(&conn, &old_device, old_id, 1_700_000_000_000,
            "Old", "Artist").unwrap();
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
        // The real settings UI disconnects before selecting another account.
        // Keep only the last verified subject (no usable token) until the next
        // sign-in so previously owned tracks remain account-isolated.
        conn.execute("UPDATE drive_sync_state SET enabled = 0, access_token = NULL,
            refresh_token = NULL, token_expires_at = 0 WHERE id = 1", []).unwrap();
        assert_eq!(load_state(&conn).unwrap().account_subject.as_deref(), Some("account-a"));
        prepare_oauth_credentials(&conn, true).unwrap();
        conn.execute("UPDATE drive_sync_state SET enabled = 1,
            account_subject = 'account-b', account_email = 'b@example.com' WHERE id = 1", []).unwrap();
        assert!(pending_local_plays(&conn).unwrap().is_empty(),
            "account A plays must not upload to B");
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
            VALUES ('New', 'Artist', 1700000001000)", []).unwrap();
        let new_id = conn.last_insert_rowid();
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
        pin_local_origin(&conn, &old_device, new_id, 1_700_000_001_000,
            "New", "Artist").unwrap();
        let owner: String = conn.query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [new_id], |row| row.get(0)
        ).unwrap();
        assert_eq!(owner, "account-b");
        prepare_oauth_credentials(&conn, true).unwrap();
        conn.execute("UPDATE drive_sync_state SET enabled = 1,
            account_subject = 'account-a', account_email = 'a@example.com' WHERE id = 1", []).unwrap();
        let old_pending = pending_local_plays(&conn).unwrap();
        assert_eq!(old_pending.len(), 1, "returning to A must not upload B plays");
        assert_eq!(old_pending[0].id, old_id);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn explicit_reassignment_reuploads_local_only_without_relaying_imports() {
        let (directory, conn) = history_storage_fixture();
        conn.execute("UPDATE drive_sync_state SET account_subject = 'account-b',
            enabled = 1 WHERE id = 1", []).unwrap();
        let event = fixture_batch().events[0].clone();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
            VALUES ('Old', 'Artist', 1700000000000)", []).unwrap();
        let old_id = conn.last_insert_rowid();
        conn.execute("INSERT INTO drive_event_state
            (scrobble_id, drive_imported, owner_account_subject)
            VALUES (?1, 0, 'account-a')", [old_id]).unwrap();
        assert!(pending_local_plays(&conn).unwrap().is_empty());
        assert!(insert_remote_event(&conn, "remote-device", &event).unwrap());
        let reassigned = authorize_existing_local_history(&conn, "account-b").unwrap();
        assert_eq!(reassigned, 1, "one local-owned play may be explicitly transferred");
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
        assert_eq!(pending_local_plays(&conn).unwrap()[0].id, old_id);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }


    #[test]
    fn interrupted_oauth_keeps_verified_ownership_for_next_account_switch() {
        let (directory, conn) = history_storage_fixture();
        conn.execute(
            "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-a',
             last_verified_account_subject = 'account-a', account_email = 'a@example.com'",
            [],
        ).unwrap();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Old capture', 'Artist', 1700000000000)", [],
        ).unwrap();
        let old_id = conn.last_insert_rowid();
        let owner = load_state(&conn).unwrap().device_id;
        // Simulate a crash after prepare (before receiving the new OAuth identity).
        prepare_oauth_credentials(&conn, false).unwrap();
        assert_eq!(load_state(&conn).unwrap().last_verified_account_subject.as_deref(),
            Some("account-a"));
        assert!(load_state(&conn).unwrap().account_subject.is_none());
        // Next login is B: even though the active account is temporarily null,
        // the last verified A identity must still claim the original history.
        prepare_oauth_credentials(&conn, true).unwrap();
        conn.execute(
            "UPDATE drive_sync_state SET account_subject = 'account-b',
             last_verified_account_subject = 'account-b', enabled = 1", [],
        ).unwrap();
        assert!(pending_local_plays(&conn).unwrap().is_empty(),
            "previous owner's uncatalogued plays cannot leak to B");
        let tagged: String = conn.query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [old_id], |row| row.get(0),
        ).unwrap();
        assert_eq!(tagged, "account-a");
        assert!(!owner.is_empty());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn existing_active_subject_migrates_to_durable_last_verified_subject() {
        let (directory, conn) = oauth_storage_fixture();
        conn.execute("UPDATE drive_sync_state SET last_verified_account_subject = NULL WHERE id = 1",
            []).unwrap();
        drop(conn);
        let again = open_sync_db(&directory).unwrap();
        assert_eq!(load_state(&again).unwrap().last_verified_account_subject.as_deref(),
            Some("google-stable-123"));
        drop(again);
        std::fs::remove_dir_all(directory).unwrap();
    }



    #[test]
    fn nullable_legacy_metadata_does_not_block_local_export_scanning() {
        let (directory, conn) = history_storage_fixture();
        conn.execute(
            "INSERT INTO scrobbles
             (title, artist, timestamp_utc, album, session_id, volume_level)
             VALUES ('Valid title', 'Valid artist', 1700000000000, NULL, NULL, NULL)",
            [],
        ).unwrap();
        let play = pending_local_plays(&conn).unwrap().pop().unwrap();
        let producer = load_state(&conn).unwrap().device_id;
        assert!(valid_event(&local_to_wire(&producer, &play)));
        assert_eq!(play.album, "");
        assert_eq!(play.session_id, "");
        assert_eq!(play.volume_level, -1.0);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn nullable_legacy_session_does_not_abort_remote_duplicate_scan() {
        let (directory, conn) = history_storage_fixture();
        let event = fixture_batch().events[0].clone();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc, session_id)
             VALUES (?1, ?2, ?3, NULL)",
            params![event.title, event.artist, event.timestamp_utc],
        ).unwrap();
        assert!(!insert_remote_event(&conn, "other-source", &event).unwrap());
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn invalid_local_play_does_not_block_the_following_page() {
        let (directory, conn) = history_storage_fixture();
        conn.execute_batch(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('', 'Artist', 1700000000000);
             INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Valid', 'Artist', 1700000000001);
             INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Later', 'Artist', 1700000000002);",
        ).unwrap();
        let producer = load_state(&conn).unwrap().device_id;
        let first = pending_local_plays_page(&conn, None, 2).unwrap();
        assert_eq!(first.len(), 2);
        assert!(!valid_event(&local_to_wire(&producer, &first[0])));
        assert!(valid_event(&local_to_wire(&producer, &first[1])));
        let tail = pending_local_plays_page(
            &conn, Some((first[1].timestamp_utc, first[1].id)), 2
        ).unwrap();
        assert_eq!(tail.len(), 1);
        assert!(valid_event(&local_to_wire(&producer, &tail[0])));
        let still_pending: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles",
            [], |row| row.get(0)).unwrap();
        assert_eq!(still_pending, 3, "invalid local events must never be deleted");
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn oversized_local_metadata_is_rejected_without_mutating_sqlite() {
        let (directory, conn) = history_storage_fixture();
        let title = "A".repeat(1200);
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
            VALUES (?1, 'Artist', 1700000000000)", [&title]).unwrap();
        let producer = load_state(&conn).unwrap().device_id;
        let pending = pending_local_plays(&conn).unwrap();
        assert_eq!(pending.len(), 1);
        assert!(!valid_event(&local_to_wire(&producer, &pending[0])));
        let persisted: String = conn.query_row(
            "SELECT title FROM scrobbles", [], |row| row.get(0),
        ).unwrap();
        assert_eq!(persisted, title);
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn legacy_email_only_sessions_are_disabled_without_losing_cursors() {
        let (directory, conn) = oauth_storage_fixture();
        assert_eq!(load_state(&conn).unwrap().account_subject.as_deref(), Some("google-stable-123"));
        conn.execute("UPDATE drive_sync_state SET account_subject = NULL WHERE id = 1", []).unwrap();
        drop(conn);
        let reopened = open_sync_db(&directory).unwrap();
        let state = load_state(&reopened).unwrap();
        assert!(!state.enabled && state.access_token.is_none());
        assert!(state.account_email.is_none() && state.account_subject.is_none());
        assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
        drop(reopened);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn oauth_callback_requires_the_expected_route_nonce_and_unambiguous_parameters() {
        assert_eq!(parse_oauth_callback("GET /oauth2/callback?state=nonce&code=a%2Bb HTTP/1.1", "nonce").unwrap().unwrap(), "a+b");
        for line in [
            "GET /favicon.ico HTTP/1.1",
            "GET /oauth2/callback?state=wrong&code=x HTTP/1.1",
            "POST /oauth2/callback?state=nonce&code=x HTTP/1.1",
            "GET /oauth2/callback?state=nonce&state=nonce&code=x HTTP/1.1",
            "GET /oauth2/callback?state=nonce&code=x&code=y HTTP/1.1",
            "GET //other/oauth2/callback?state=nonce&code=x HTTP/1.1",
        ] { assert!(parse_oauth_callback(line, "nonce").is_none(), "{line}"); }
        assert!(parse_oauth_callback("GET /oauth2/callback?state=nonce&error=access_denied HTTP/1.1", "nonce").unwrap().is_err());
        assert!(parse_oauth_callback("GET /oauth2/callback?state=nonce&code= HTTP/1.1", "nonce").unwrap().is_err());
    }

    #[tokio::test]
    async fn oauth_listener_ignores_unrelated_connections_and_reads_fragmented_headers() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            wait_for_oauth_callback(&listener, "nonce", Duration::from_secs(5)).await
        });
        for target in ["/favicon.ico", "/oauth2/callback?state=wrong&code=x"] {
            let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
            client.write_all(format!("GET {target} HTTP/1.1\r\nHost: localhost\r\n\r\n").as_bytes()).await.unwrap();
            let mut response = String::new();
            client.read_to_string(&mut response).await.unwrap();
            assert!(response.starts_with("HTTP/1.1 400"));
        }
        let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
        client.write_all(b"GET /oauth2/callback?code=a%2Bb").await.unwrap();
        tokio::time::sleep(Duration::from_millis(10)).await;
        client.write_all(b"&state=nonce HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        assert!(response.starts_with("HTTP/1.1 200"));
        assert_eq!(server.await.unwrap().unwrap(), "a+b");
    }

    #[tokio::test]
    async fn oauth_callback_deadline_also_bounds_an_incomplete_connection() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let failure = wait_for_oauth_callback(&listener, "nonce", Duration::from_millis(20)).await.unwrap_err();
        assert!(failure.contains("timed out"));
        drop(client);
    }

    #[test]
    fn duplicate_control_markers_use_the_latest_valid_server_version() {
        let mut earlier = fixture_file(&fixture_batch(), b"fixture");
        earlier.modified_time = Some("2026-10-02T00:00:00Z".into());
        let mut later = earlier.clone();
        later.modified_time = Some("2026-10-02T00:01:00Z".into());
        let expected = parse_time_ms(later.modified_time.as_deref());
        assert_eq!(latest_marker_version(&[earlier.clone(), later.clone()]).unwrap(), expected);
        assert_eq!(latest_marker_version(&[later, earlier.clone()]).unwrap(), expected);
        assert_eq!(latest_marker_version(&[]).unwrap(), 0);
        earlier.modified_time = None;
        assert!(latest_marker_version(&[earlier]).is_err());
    }

    #[test]
    fn retry_requires_complete_upload_identity_not_only_matching_bytes() {
        let batch = fixture_batch();
        let bytes = encode_batch(&batch).unwrap();
        let file = fixture_file(&batch, &bytes);
        assert!(verified_upload(&file, &file.name, &batch.source_device_id, 42, &bytes));
        for key in ["tempo_kind", "tempo_schema", "source_device_id", "source_platform", APP_PROPERTY_GENERATION] {
            let mut wrong = file.clone();
            wrong.app_properties.insert(key.into(), "wrong".into());
            assert!(!verified_upload(&wrong, &file.name, &batch.source_device_id, 42, &bytes), "{key}");
        }
    }

    #[test]
    fn deletion_stop_and_cursors_are_committed_before_cloud_cleanup() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE scrobbles (id INTEGER PRIMARY KEY);
             INSERT INTO scrobbles VALUES (1), (2), (3);
             CREATE TABLE drive_event_state (
                 scrobble_id INTEGER PRIMARY KEY, drive_imported INTEGER,
                 drive_uploaded_at INTEGER, owner_account_subject TEXT,
                 cloud_suppressed INTEGER DEFAULT 0
             );
             INSERT INTO drive_event_state VALUES (1, 0, 100, 'google-a', 0),
                 (2, 1, 100, 'google-a', 0);
             CREATE TABLE drive_sync_state (
                id INTEGER PRIMARY KEY, enabled INTEGER, account_subject TEXT,
                accepted_disable_version INTEGER, download_cursor INTEGER,
                last_uploaded INTEGER, last_imported INTEGER, last_error TEXT
             );
             INSERT INTO drive_sync_state VALUES (1, 1, 'google-a', 0, 123, 4, 5, NULL);"
        ).unwrap();
        accept_deletion_marker(&conn, 200, Some("remote deletion")).unwrap();
        let state: (i64, i64, i64, i64, i64) = conn.query_row(
            "SELECT enabled, accepted_disable_version, download_cursor, last_uploaded, last_imported FROM drive_sync_state",
            [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?))
        ).unwrap();
        assert_eq!(state, (0, 200, 0, 0, 0));
        let rows: Vec<(i64, Option<i64>, i64)> = conn.prepare(
            "SELECT scrobble_id, drive_uploaded_at, cloud_suppressed FROM drive_event_state ORDER BY scrobble_id"
        ).unwrap().query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?))).unwrap()
            .collect::<Result<_, _>>().unwrap();
        assert_eq!(rows, vec![(1, Some(100), 1), (2, Some(100), 0), (3, None, 1)]);
    }

    #[test]
    fn deleted_cloud_archive_requires_explicit_opt_in_to_republish() {
        let (directory, conn) = history_storage_fixture();
        conn.execute("UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a' WHERE id = 1", []).unwrap();
        conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Earlier play', 'Artist', 1700000000000)", []).unwrap();
        let old_id = conn.last_insert_rowid();
        assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
        accept_deletion_marker(&conn, 200, None).unwrap();
        conn.execute("UPDATE drive_sync_state SET enabled = 1 WHERE id = 1", []).unwrap();
        assert!(pending_local_plays(&conn).unwrap().is_empty());
        assert!(lan_play_account_owners(&directory, &[old_id]).unwrap().is_empty(),
            "LAN must not relay a cloud-suppressed event back to Android Drive");
        assert_eq!(authorize_existing_local_history(&conn, "google-a").unwrap(), 1);
        assert_eq!(pending_local_plays(&conn).unwrap()[0].id, old_id);
        assert_eq!(lan_play_account_owners(&directory, &[old_id]).unwrap().get(&old_id),
            Some(&"google-a".to_string()));
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }



    fn fixture_batch() -> WireBatch {
        let event = WireEvent {
            event_id: "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4".into(),
            title: "Song".into(), artist: "Artist".into(), album: None,
            timestamp_utc: 1_700_000_000_000, duration_ms: 180_000, listened_ms: 170_000,
            source_app: "Spotify".into(), source: "desktop:Spotify".into(),
            skipped: false, replay_count: 0, completion_percentage: 94, pause_count: 0, seek_count: 0,
            session_id: None, site: None, content_type: "MUSIC".into(), volume_level: Some(50),
            total_pause_duration_ms: 0, position_updates_count: 0,
        };
        WireBatch {
            schema_version: 1, batch_id: batch_id(&[event.clone()]), source_device_id: "device-1".into(),
            source_device_name: "Tempo Desktop".into(), source_platform: "desktop".into(),
            created_at_utc: 1_700_000_000_000, events: vec![event],
        }
    }

    fn fixture_file(batch: &WireBatch, bytes: &[u8]) -> DriveFileRecord {
        DriveFileRecord {
            id: "file-1".into(), name: batch_file_name(42, &batch.source_device_id, &batch.batch_id),
            size: Some(bytes.len().to_string()), created_time: None, modified_time: None,
            app_properties: HashMap::from([
                ("tempo_kind".into(), "history_batch".into()),
                ("tempo_schema".into(), "1".into()),
                ("source_device_id".into(), batch.source_device_id.clone()),
                ("source_platform".into(), batch.source_platform.clone()),
                (APP_PROPERTY_GENERATION.into(), "42".into()),
                (APP_PROPERTY_SHA256.into(), hex::encode(Sha256::digest(bytes))),
            ]),
        }
    }

    fn compress_json(value: &serde_json::Value) -> Vec<u8> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&serde_json::to_vec(value).unwrap()).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn prototype_uploads_are_requeued_once_without_reuploading_imports() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE drive_sync_migrations (name TEXT PRIMARY KEY);
             CREATE TABLE drive_event_state (
                 scrobble_id INTEGER PRIMARY KEY, drive_imported INTEGER, drive_uploaded_at INTEGER
             );
             INSERT INTO drive_event_state VALUES (1, 0, 100), (2, 1, 100);"
        ).unwrap();
        requeue_unverified_prototype_uploads(&conn).unwrap();
        let timestamps: Vec<Option<i64>> = conn.prepare(
            "SELECT drive_uploaded_at FROM drive_event_state ORDER BY scrobble_id"
        ).unwrap().query_map([], |row| row.get(0)).unwrap()
            .collect::<Result<_, _>>().unwrap();
        assert_eq!(timestamps, vec![None, Some(100)]);
        conn.execute("UPDATE drive_event_state SET drive_uploaded_at = 200 WHERE scrobble_id = 1", []).unwrap();
        requeue_unverified_prototype_uploads(&conn).unwrap();
        let timestamp: Option<i64> = conn.query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)
        ).unwrap();
        assert_eq!(timestamp, Some(200));
    }

    #[test]
    fn protocol_batch_round_trip_and_integrity_metadata() {
        let batch = fixture_batch();
        let bytes = encode_batch(&batch).unwrap();
        let decoded = decode_batch(&bytes).unwrap();
        assert_eq!(decoded.batch_id, batch.batch_id);
        let file = fixture_file(&batch, &bytes);
        assert!(matches_batch_metadata(&file, &decoded));
        assert!(validate_compressed_metadata(&file, &bytes).is_ok());
        let mut corrupt = bytes.clone();
        corrupt[0] ^= 1;
        assert!(validate_compressed_metadata(&file, &corrupt).is_err());
        let mut wrong_size = file.clone();
        wrong_size.size = Some((bytes.len() + 1).to_string());
        assert!(validate_compressed_metadata(&wrong_size, &bytes).is_err());
        let mut missing_hash = file.clone();
        missing_hash.app_properties.remove(APP_PROPERTY_SHA256);
        assert!(validate_compressed_metadata(&missing_hash, &bytes).is_err());
    }

    #[test]
    fn payload_identity_must_match_drive_filename_and_properties() {
        let batch = fixture_batch();
        let bytes = encode_batch(&batch).unwrap();
        for property in ["source_device_id", "source_platform", "tempo_schema", "tempo_kind"] {
            let mut file = fixture_file(&batch, &bytes);
            file.app_properties.insert(property.into(), "wrong".into());
            assert!(!matches_batch_metadata(&file, &batch), "{property}");
        }
        let mut file = fixture_file(&batch, &bytes);
        file.name = batch_file_name(43, &batch.source_device_id, &batch.batch_id);
        assert!(!matches_batch_metadata(&file, &batch));
        for generation in ["-1", "+42", "", "NaN", "9007199254740992"] {
            file.app_properties.insert(APP_PROPERTY_GENERATION.into(), generation.into());
            assert_eq!(batch_generation(&file), None, "{generation}");
        }
    }

    #[test]
    fn malformed_batches_are_rejected_before_import() {
        let original = serde_json::to_value(fixture_batch()).unwrap();
        let mutations = [
            ("/batch_id", serde_json::json!("0".repeat(64))),
            ("/source_device_id", serde_json::json!("device/invalid")),
            ("/created_at_utc", serde_json::json!(MAX_WIRE_INTEGER + 1)),
            ("/events/0/event_id", serde_json::json!("not-a-hash")),
            ("/events/0/duration_ms", serde_json::json!(-1)),
            ("/events/0/listened_ms", serde_json::json!(12.5)),
            ("/events/0/replay_count", serde_json::json!(i32::MAX as i64 + 1)),
            ("/events/0/volume_level", serde_json::json!(101)),
            ("/events/0/title", serde_json::json!(" ")),
            ("/events/0/album", serde_json::json!("x".repeat(1001))),
        ];
        for (pointer, value) in mutations {
            let mut malformed = original.clone();
            *malformed.pointer_mut(pointer).unwrap() = value;
            assert!(decode_batch(&compress_json(&malformed)).is_err(), "{pointer}");
        }
        for field in ["album", "session_id", "site", "volume_level"] {
            let mut malformed = original.clone();
            malformed["events"][0].as_object_mut().unwrap().remove(field);
            assert!(decode_batch(&compress_json(&malformed)).is_err(), "missing {field}");
        }
        let mut empty = fixture_batch();
        empty.events.clear();
        assert!(encode_batch(&empty).is_err());
        let mut oversized = fixture_batch();
        oversized.events = vec![oversized.events[0].clone(); 1001];
        oversized.batch_id = batch_id(&oversized.events);
        assert!(encode_batch(&oversized).is_err());
    }

    #[test]
    fn oversized_compressed_and_expanded_payloads_are_rejected() {
        assert!(decode_batch(&vec![0; MAX_BATCH_BYTES + 1]).is_err());
        let expanded = serde_json::json!("x".repeat(MAX_BATCH_BYTES + 1));
        assert!(decode_batch(&compress_json(&expanded)).is_err());
    }

    #[test]
    fn lan_provenance_matches_drive_wire_identity() {
        let (dir, conn) = history_storage_fixture();
        let expected_device = load_state(&conn).unwrap().device_id;
        drop(conn);
        let (actual_device, known) = lan_origin_metadata(&dir, &[]).unwrap();
        assert!(known.is_empty());
        assert_eq!(actual_device, expected_device);
        assert_eq!(
            lan_play_origin("device-1", 42, 1_700_000_000_000, " Song ", " Artist "),
            "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4"
        );
    }

    #[test]
    fn lan_first_identity_is_pinned_before_google_drive_upload() {
        let (directory, conn) = history_storage_fixture();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Old title', 'Artist', 1700000000000)", [],
        ).unwrap();
        let scrobble_id = conn.last_insert_rowid();
        let producer = load_state(&conn).unwrap().device_id;
        drop(conn);

        let (_, first) = persist_lan_origin_metadata(
            &directory,
            &[(scrobble_id, 1_700_000_000_000, "Old title".into(), "Artist".into())],
        ).unwrap();
        let origin = first[&scrobble_id].clone();
        let conn = open_sync_db(&directory).unwrap();
        let uploaded: Option<i64> = conn.query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = ?1",
            [scrobble_id], |row| row.get(0),
        ).unwrap();
        assert_eq!(uploaded, None, "sending via LAN must not mark a Drive upload");
        conn.execute(
            "UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
            [scrobble_id],
        ).unwrap();
        drop(conn);

        let (again_device, later) = persist_lan_origin_metadata(
            &directory,
            &[(scrobble_id, 1_700_000_000_000, "Corrected title".into(), "Artist".into())],
        ).unwrap();
        assert_eq!(producer, again_device);
        assert_eq!(later[&scrobble_id], origin, "LAN retries must keep the first ID");
        let conn = open_sync_db(&directory).unwrap();
        let pending = pending_local_plays(&conn).unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(event_id(&producer, &pending[0]), origin,
            "subsequent Drive uploads must reuse the LAN-first origin ID");
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn pinned_drive_origin_survives_a_crash_before_server_acknowledgment() {
        let (directory, conn) = history_storage_fixture();
        conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Track title', 'Performer', 1700000000000)", [],
        ).unwrap();
        let id = conn.last_insert_rowid();
        let producer = load_state(&conn).unwrap().device_id;
        let original = pin_local_origin(
            &conn, &producer, id, 1_700_000_000_000, "Track title", "Performer"
        ).unwrap();
        drop(conn);
        let reopened = open_sync_db(&directory).unwrap();
        let later = pin_local_origin(
            &reopened, &producer, id, 1_700_000_000_000, "Renamed", "Performer"
        ).unwrap();
        assert_eq!(original, later);
        assert_eq!(pending_local_plays(&reopened).unwrap().len(), 1,
            "pinning the ID must not mark a pending Drive upload as completed");
        drop(reopened);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn previously_uploaded_origin_is_stable_after_track_metadata_changes() {
        let (directory, conn) = history_storage_fixture();
        let id = conn.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('Original', 'Artist', 1700000000000)", [],
        ).unwrap();
        assert_eq!(id, 1);
        let scrobble_id = conn.last_insert_rowid();
        let first_identity = lan_play_origin("test-device", scrobble_id, 1_700_000_000_000,
            "Original", "Artist");
        conn.execute(
            "INSERT INTO drive_event_state
             (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
             VALUES (?1, ?2, 'test-device', 0, NULL)",
            params![scrobble_id, first_identity],
        ).unwrap();
        conn.execute("UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
            [scrobble_id]).unwrap();
        let (_, known) = lan_origin_metadata(&directory, &[scrobble_id]).unwrap();
        assert_eq!(known.get(&scrobble_id), Some(&first_identity),
            "LAN must reuse the original Drive identity after metadata correction");
        let pending = pending_local_plays(&conn).unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(event_id("test-device", &pending[0]), first_identity,
            "a metadata correction must not change a previously uploaded event ID");
        drop(conn);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn stable_event_and_batch_ids_match_protocol_shape() {
        let play = LocalPlay {
            id: 42,
            origin_event_id: None,
            title: " Song ".to_string(),
            artist: " Artist ".to_string(),
            album: String::new(),
            duration_ms: 180_000,
            timestamp_utc: 1_700_000_000_000,
            source_app: "Spotify".to_string(),
            listened_ms: 170_000,
            skipped: false,
            replay_count: 0,
            is_muted: false,
            completion_percentage: 94.0,
            pause_count: 1,
            seek_count: 0,
            session_id: "session".to_string(),
            site: String::new(),
            content_type: "MUSIC".to_string(),
            volume_level: 0.5,
        };
        let first = event_id("device-1", &play);
        let second = event_id("device-1", &play);
        assert_eq!(first, second);
        assert_eq!(
            first,
            "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4"
        );
        let wire = local_to_wire("device-1", &play);
        let batch = batch_id(&[wire.clone()]);
        assert_eq!(batch, batch_id(&[wire]));
        assert_eq!(
            batch,
            "785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43"
        );
    }

    #[test]
    fn generation_filename_and_legacy_metadata_are_compatible() {
        assert_eq!(
            batch_file_name(1234, "device-1", "batch-1"),
            "tempo_history_v1_g1234_device-1_batch-1.json.gz"
        );
        let legacy = DriveFileRecord {
            id: "legacy".to_string(),
            name: "tempo_history_v1_device_batch.json.gz".to_string(),
            size: None,
            created_time: None,
            modified_time: None,
            app_properties: HashMap::new(),
        };
        assert_eq!(batch_generation(&legacy), Some(0));
    }


    #[test]
    fn user_delete_cleans_invalid_generations_but_stale_client_cannot() {
        let mut file = fixture_file(&fixture_batch(), b"fixture");
        file.name = batch_file_name(10, "device-1", &"a".repeat(64));
        file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "10".into());
        assert!(should_delete_history_batch(&file, 20, false));
        file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "20".into());
        assert!(!should_delete_history_batch(&file, 20, true));
        file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "21".into());
        assert!(!should_delete_history_batch(&file, 20, true));
        file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "invalid".into());
        assert!(!should_delete_history_batch(&file, 20, false));
        assert!(should_delete_history_batch(&file, 20, true));
        file.name = "tempo_history_v1_corrupt-without-extension".into();
        assert!(should_delete_history_batch(&file, 20, true));
        assert!(!should_delete_history_batch(&file, 20, false));
        file.name = "unrelated_tempo_history_v1_file.json.gz".into();
        assert!(!should_delete_history_batch(&file, 20, true));
    }

    #[tokio::test]
    async fn transient_drive_response_retries_then_succeeds() {
        // Loopback stub exercises the real reqwest request and response code.
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/test", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            for status in ["503 Service Unavailable", "200 OK"] {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut request = [0u8; 1024];
                socket.read(&mut request).await.unwrap();
                let response = format!(
                    "HTTP/1.1 {status}\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}"
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
        });
        let response = send_drive_idempotent(http_client().unwrap().get(url))
            .await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        server.await.unwrap();
    }

    #[test]
    fn retries_only_transient_drive_status_codes() {
        assert!(transient_drive_response(StatusCode::TOO_MANY_REQUESTS));
        assert!(transient_drive_response(StatusCode::SERVICE_UNAVAILABLE));
        assert!(transient_drive_response(StatusCode::GATEWAY_TIMEOUT));
        assert!(!transient_drive_response(StatusCode::BAD_REQUEST));
        assert!(!transient_drive_response(StatusCode::FORBIDDEN));
        assert!(!transient_drive_response(StatusCode::UNAUTHORIZED));
    }
    #[test]
    fn protocol_volume_uses_android_percent_scale() {
        let mut play = LocalPlay {
            id: 1,
            origin_event_id: None,
            title: "Song".to_string(),
            artist: "Artist".to_string(),
            album: String::new(),
            duration_ms: 0,
            timestamp_utc: 1,
            source_app: "test".to_string(),
            listened_ms: 0,
            skipped: false,
            replay_count: 0,
            is_muted: false,
            completion_percentage: 0.0,
            pause_count: 0,
            seek_count: 0,
            session_id: String::new(),
            site: String::new(),
            content_type: "MUSIC".to_string(),
            volume_level: 0.42,
        };
        assert_eq!(protocol_volume(&play), Some(42));
        for (volume, expected) in [(0.0, Some(0)), (0.001, Some(1)), (0.01, Some(1)),
            (0.5, Some(50)), (1.0, Some(100)), (1.5, Some(100)), (-1.0, None), (f64::NAN, None)] {
            play.volume_level = volume;
            assert_eq!(protocol_volume(&play), expected, "volume={volume}");
        }
        play.is_muted = true;
        assert_eq!(protocol_volume(&play), Some(0));
    }
}
