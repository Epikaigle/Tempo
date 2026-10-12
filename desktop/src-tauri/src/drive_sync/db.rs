use rusqlite::{params, Connection, OptionalExtension};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use uuid::Uuid;

mod migrations;
pub(crate) use migrations::*;

use super::models::{
    now_ms, sha256_hex, valid_sha256, DriveFileRecord, LocalPlay, StoredDriveState, WireEvent,
    LEGACY_UNVERIFIED_ACCOUNT, TEMPORAL_DEDUP_MS,
};

pub(crate) fn db_path(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join("tempo.db")
}

pub(crate) fn open_sync_db(app_data_dir: &Path) -> Result<Connection, String> {
    let conn = Connection::open(db_path(app_data_dir)).map_err(|e| e.to_string())?;
    conn.execute_batch(
        "
        PRAGMA busy_timeout = 5000;
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
            offline_capture_account_subject TEXT,
            download_cursor INTEGER NOT NULL DEFAULT 0,
            accepted_disable_version INTEGER NOT NULL DEFAULT 0,
            last_sync_time INTEGER,
            last_error TEXT,
            last_uploaded INTEGER NOT NULL DEFAULT 0,
            last_imported INTEGER NOT NULL DEFAULT 0
        );
        -- Progress belongs to a verified Google identity, not the active device
        -- session. Switching A -> B -> A must never replay an already accepted
        -- deletion marker or silently suppress A's new-generation uploads.
        CREATE TABLE IF NOT EXISTS drive_account_progress (
            account_subject TEXT PRIMARY KEY NOT NULL,
            download_cursor INTEGER NOT NULL DEFAULT 0,
            accepted_disable_version INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS drive_rejected_files (
            account_subject TEXT NOT NULL,
            file_id TEXT NOT NULL,
            file_name TEXT NOT NULL,
            rejection_reason TEXT NOT NULL,
            recorded_at INTEGER NOT NULL,
            PRIMARY KEY (account_subject, file_id)
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
        let mut stmt = conn
            .prepare("PRAGMA table_info(drive_sync_state)")
            .map_err(|e| e.to_string())?;
        let names = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?;
        names
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?
            .iter()
            .any(|name| name == "account_subject")
    };
    if !has_subject {
        conn.execute(
            "ALTER TABLE drive_sync_state ADD COLUMN account_subject TEXT",
            [],
        )
        .map_err(|e| e.to_string())?;
    }
    // Preserve the last verified subject even if authorization subsequently
    // fails. The active subject is intentionally cleared while replacing
    // credentials; the durable value retains event ownership across crashes.
    let has_verified_column = {
        let mut stmt = conn
            .prepare("PRAGMA table_info(drive_sync_state)")
            .map_err(|e| e.to_string())?;
        let names = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?;
        names
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?
            .iter()
            .any(|name| name == "last_verified_account_subject")
    };
    if !has_verified_column {
        conn.execute(
            "ALTER TABLE drive_sync_state ADD COLUMN last_verified_account_subject TEXT",
            [],
        )
        .map_err(|e| e.to_string())?;
    }
    conn.execute(
        "UPDATE drive_sync_state SET last_verified_account_subject = account_subject
         WHERE last_verified_account_subject IS NULL AND account_subject IS NOT NULL",
        [],
    )
    .map_err(|e| e.to_string())?;

    let has_offline_owner = {
        let mut stmt = conn
            .prepare("PRAGMA table_info(drive_sync_state)")
            .map_err(|e| e.to_string())?;
        let columns = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        columns.contains(&"offline_capture_account_subject".to_string())
    };
    if !has_offline_owner {
        conn.execute(
            "ALTER TABLE drive_sync_state ADD COLUMN offline_capture_account_subject TEXT",
            [],
        )
        .map_err(|e| e.to_string())?;
    }

    let has_owner_column = {
        let mut stmt = conn
            .prepare("PRAGMA table_info(drive_event_state)")
            .map_err(|e| e.to_string())?;
        let names = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?;
        names
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?
            .iter()
            .any(|name| name == "owner_account_subject")
    };
    if !has_owner_column {
        conn.execute(
            "ALTER TABLE drive_event_state ADD COLUMN owner_account_subject TEXT",
            [],
        )
        .map_err(|e| e.to_string())?;
    }
    // Existing databases need the suppression flag before the table migration.
    let has_suppression = {
        let mut stmt = conn
            .prepare("PRAGMA table_info(drive_event_state)")
            .map_err(|e| e.to_string())?;
        let columns = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        columns.contains(&"cloud_suppressed".to_string())
    };
    if !has_suppression {
        conn.execute(
            "ALTER TABLE drive_event_state ADD COLUMN cloud_suppressed INTEGER NOT NULL DEFAULT 0",
            [],
        )
        .map_err(|e| e.to_string())?;
    }

    // After a prior Google connection, unowned captures that were left behind
    // during a disconnected session must not be claimed by the next account.
    let should_quarantine_disconnected: bool = conn
        .query_row(
            "SELECT enabled = 0 AND last_verified_account_subject IS NOT NULL
             FROM drive_sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )
        .unwrap_or(false);
    if should_quarantine_disconnected {
        // Legacy rows are quarantined only once. Modern insert_play tags every
        // new capture atomically; routine status calls must not scan all history.
        let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
        let first = tx.execute(
            "INSERT OR IGNORE INTO drive_sync_migrations (name) VALUES ('disconnected_legacy_quarantine_v1')",
            [],
        ).map_err(|e| e.to_string())?;
        if first == 1 {
            tx.execute(
                "INSERT OR IGNORE INTO drive_event_state (scrobble_id, drive_imported, owner_account_subject)
                 SELECT id, 0, ?1 FROM scrobbles",
                [LEGACY_UNVERIFIED_ACCOUNT],
            ).map_err(|e| e.to_string())?;
        }
        tx.commit().map_err(|e| e.to_string())?;
    }

    // Email-only installations cannot prove who owns previously collected
    // scrobbles. Quarantine them rather than uploading them to another account.
    let unverified_account: bool = conn
        .query_row(
            "SELECT (account_subject IS NULL OR account_subject = '')
            AND (last_verified_account_subject IS NULL OR last_verified_account_subject = '')
            AND (account_email IS NOT NULL OR enabled != 0)
         FROM drive_sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    if unverified_account {
        conn.execute(
            "INSERT OR IGNORE INTO drive_event_state (scrobble_id, drive_imported, owner_account_subject)
             SELECT id, 0, ?1 FROM scrobbles", [LEGACY_UNVERIFIED_ACCOUNT]
        ).map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_event_state SET owner_account_subject = ?1
             WHERE drive_imported = 0 AND owner_account_subject IS NULL",
            [LEGACY_UNVERIFIED_ACCOUNT],
        )
        .map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_sync_state SET enabled = 0, access_token = NULL,
             refresh_token = NULL, token_expires_at = 0, account_email = NULL WHERE id = 1",
            [],
        )
        .map_err(|e| e.to_string())?;
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

pub(crate) fn load_state(conn: &Connection) -> Result<StoredDriveState, String> {
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

pub(crate) fn set_last_error(conn: &Connection, error: Option<&str>) -> Result<(), String> {
    conn.execute(
        "UPDATE drive_sync_state SET last_error = ?1 WHERE id = 1",
        [error],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

pub(crate) fn prepare_oauth_credentials(
    conn: &Connection,
    reset_drive_state: bool,
) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    // Snapshot the currently verified account BEFORE the active identity is
    // cleared. Never overwrite a snapshot with zeroes after an interrupted
    // OAuth attempt: account_subject is NULL during that interval.
    transaction
        .execute(
            "INSERT INTO drive_account_progress
             (account_subject, download_cursor, accepted_disable_version)
         SELECT account_subject, download_cursor, accepted_disable_version
         FROM drive_sync_state WHERE id = 1
           AND account_subject IS NOT NULL AND account_subject != ''
         ON CONFLICT(account_subject) DO UPDATE SET
             download_cursor = excluded.download_cursor,
             accepted_disable_version = excluded.accepted_disable_version",
            [],
        )
        .map_err(|e| e.to_string())?;
    if reset_drive_state {
        let previous_subject: Option<String> = transaction
            .query_row(
                "SELECT COALESCE(account_subject, last_verified_account_subject)
             FROM drive_sync_state WHERE id = 1",
                [],
                |row| row.get(0),
            )
            .map_err(|e| e.to_string())?;
        if let Some(previous_subject) = previous_subject {
            // Mark even never-uploaded local plays as belonging to the old
            // account BEFORE the new account can enable its first sync.
            transaction
                .execute(
                    "INSERT OR IGNORE INTO drive_event_state
                 (scrobble_id, drive_imported, owner_account_subject)
                 SELECT id, 0, ?1 FROM scrobbles",
                    [&previous_subject],
                )
                .map_err(|e| e.to_string())?;
            transaction
                .execute(
                    "UPDATE drive_event_state SET owner_account_subject = ?1
                 WHERE drive_imported = 0 AND owner_account_subject IS NULL",
                    [&previous_subject],
                )
                .map_err(|e| e.to_string())?;
        }
        // Preserve account-owned uploads across switches. Resetting them here
        // could resurrect an archive deliberately deleted from Drive.
        transaction
            .execute(
                "UPDATE drive_sync_state SET download_cursor = 0, accepted_disable_version = 0,
             last_sync_time = NULL, last_uploaded = 0, last_imported = 0 WHERE id = 1",
                [],
            )
            .map_err(|e| e.to_string())?;
    }
    // SQLite and the OS credential store cannot share a transaction. Commit an
    // inert, account-unknown state before replacing the keyring credential. If
    // either the keyring write or the final SQLite write fails, no command can
    // use that credential until account identity is verified and saved again.
    transaction
        .execute(
            "UPDATE drive_sync_state SET enabled = 0, access_token = NULL, refresh_token = NULL,
         token_expires_at = 0, account_email = NULL, account_subject = NULL,
         offline_capture_account_subject = NULL, last_error = ?1 WHERE id = 1",
            ["Google sign-in did not finish. Connect Google again."],
        )
        .map_err(|e| e.to_string())?;
    transaction.commit().map_err(|e| e.to_string())
}

// Called in the same SQLite transaction as confirming the new OAuth subject.
// An unknown account starts from zero; a returning account resumes its own
// receive cursor and previously accepted deletion generation.
pub(crate) fn restore_account_progress(conn: &Connection, subject: &str) -> Result<(), String> {
    let progress: Option<(i64, i64)> = conn
        .query_row(
            "SELECT download_cursor, accepted_disable_version
         FROM drive_account_progress WHERE account_subject = ?1",
            [subject],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
        .map_err(|e| e.to_string())?;
    let (cursor, generation) = progress.unwrap_or((0, 0));
    conn.execute(
        "UPDATE drive_sync_state SET download_cursor = ?1,
         accepted_disable_version = ?2 WHERE id = 1",
        params![cursor, generation],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
pub(crate) fn lan_origin_metadata(
    app_data_dir: &Path,
    local_ids: &[i64],
) -> Result<(String, HashMap<i64, String>), String> {
    let conn = open_sync_db(app_data_dir)?;
    let device_id = load_state(&conn)?.device_id;
    let mut known = HashMap::new();
    let mut stmt = conn
        .prepare(
            "SELECT origin_event_id FROM drive_event_state
         WHERE scrobble_id = ?1 AND drive_imported = 0",
        )
        .map_err(|e| e.to_string())?;
    for id in local_ids {
        let origin: Option<String> = stmt
            .query_row([id], |row| row.get(0))
            .optional()
            .map_err(|e| e.to_string())?;
        if let Some(origin) = origin.filter(|value| valid_sha256(value)) {
            known.insert(*id, origin);
        }
    }
    Ok((device_id, known))
}

/// Pin a local play's producer identity in SQLite before either LAN or
/// Drive transmits it. This survives retries, metadata edits and process death.
pub(crate) fn pin_local_origin(
    conn: &Connection,
    device_id: &str,
    id: i64,
    timestamp_utc: i64,
    title: &str,
    artist: &str,
) -> Result<String, String> {
    let generated = lan_play_origin(device_id, id, timestamp_utc, title, artist);
    // Keep an unowned legacy row unclaimed while pinning its local origin:
    // reconciliation will assign a *verified* account after matching. Real
    // newly captured offline plays are already tagged local-only atomically by
    // Database::insert_play; this function never overrides that owner.
    let (enabled, subject): (i64, Option<String>) = conn
        .query_row(
            "SELECT enabled, account_subject FROM drive_sync_state WHERE id = 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|e| e.to_string())?;
    let subject = if enabled != 0 { subject } else { None };
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
    )
    .map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE drive_event_state SET origin_event_id = ?2, origin_device_id = ?3
         WHERE scrobble_id = ?1 AND drive_imported = 0 AND origin_event_id IS NULL
           AND (origin_device_id IS NULL OR origin_device_id = ?3)",
        params![id, generated, device_id],
    )
    .map_err(|e| e.to_string())?;
    let (origin, owner, imported): (Option<String>, Option<String>, i64) = conn
        .query_row(
            "SELECT origin_event_id, origin_device_id, drive_imported
             FROM drive_event_state WHERE scrobble_id = ?1",
            [id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .map_err(|e| e.to_string())?;
    let origin = origin.ok_or("Tempo could not persist the original Desktop event ID")?;
    if imported != 0 || owner.as_deref() != Some(device_id) || !valid_sha256(&origin) {
        return Err("A remote or invalid event cannot be sent as a local Desktop origin".into());
    }
    remember_origin(conn, id, device_id, &origin)?;
    Ok(origin)
}

/// Read each queued LAN playback's verified Google owner. Do not substitute
/// the account selected *today* for a recording owned by a previous account.
pub fn lan_play_account_owners(
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
    let mut stmt = conn
        .prepare(
            "SELECT owner_account_subject FROM drive_event_state
         WHERE scrobble_id = ?1 AND drive_imported = 0
           AND COALESCE(cloud_suppressed, 0) = 0",
        )
        .map_err(|e| e.to_string())?;
    let mut owners = HashMap::new();
    for id in play_ids {
        let owner: Option<String> = stmt
            .query_row([id], |row| row.get(0))
            .optional()
            .map_err(|e| e.to_string())?
            .flatten();
        if let Some(owner) =
            owner.filter(|v| !v.is_empty() && v != LEGACY_UNVERIFIED_ACCOUNT && !v.contains('@'))
        {
            if owner == current_subject {
                owners.insert(*id, owner);
            }
        }
    }
    Ok(owners)
}

/// Ensure LAN-first captures have the exact identity later used by Drive.
/// The identity is saved atomically even if LAN delivery fails or is retried.
pub fn persist_lan_origin_metadata(
    app_data_dir: &Path,
    captures: &[(i64, i64, String, String)],
) -> Result<(String, HashMap<i64, String>), String> {
    let conn = open_sync_db(app_data_dir)?;
    let device_id = load_state(&conn)?.device_id;
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let mut origins = HashMap::new();
    for (id, timestamp_utc, title, artist) in captures {
        let origin = pin_local_origin(&tx, &device_id, *id, *timestamp_utc, title, artist)?;
        origins.insert(*id, origin);
    }
    tx.commit().map_err(|e| e.to_string())?;
    Ok((device_id, origins))
}

pub fn lan_play_origin(
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

#[cfg(test)]
pub(crate) fn pending_local_plays(conn: &Connection) -> Result<Vec<LocalPlay>, String> {
    use super::models::MAX_LOCAL_SCAN;
    pending_local_plays_page(conn, None, MAX_LOCAL_SCAN)
}

// The ordered keyset cursor keeps scanning after invalid records without
// relying on OFFSET (the pending set shrinks as good records are uploaded).
pub(crate) fn pending_local_plays_page(
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
        .query_map(
            params![
                limit as i64,
                subject,
                after.map(|v| v.0),
                after.map(|v| v.1)
            ],
            |row| {
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
            },
        )
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

pub(crate) fn remember_origin(
    conn: &Connection,
    scrobble_id: i64,
    source_device_id: &str,
    origin_event_id: &str,
) -> Result<(), String> {
    let state_owner: Option<Option<String>> = conn
        .query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [scrobble_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?;
    let owner = state_owner
        .ok_or("Tempo cannot alias an event with no stored playback state")?
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| LEGACY_UNVERIFIED_ACCOUNT.to_string());
    let affected = conn
        .execute(
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

pub(crate) fn insert_remote_event_for_account(
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
        let mut stmt = conn
            .prepare_cached(
                "SELECT s.id, s.title, s.artist, s.duration_ms FROM scrobbles s
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
            )
            .map_err(|e| e.to_string())?;
        let candidates = stmt
            .query_map(
                params![
                    event.timestamp_utc - TEMPORAL_DEDUP_MS,
                    event.timestamp_utc + TEMPORAL_DEDUP_MS,
                    event.timestamp_utc,
                    source_device_id,
                    account_subject
                ],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, Option<i64>>(3)?,
                    ))
                },
            )
            .map_err(|e| e.to_string())?;
        let mut found = None;
        for candidate in candidates {
            let (id, candidate_title, candidate_artist, candidate_duration) =
                candidate.map_err(|e| e.to_string())?;
            // Different versions of a same-titled track can start within two
            // seconds. If both devices know the duration and it differs by
            // more than ten seconds, this cannot be the same playback.
            let duration_matches = match (candidate_duration, event.duration_ms) {
                (Some(local), remote) if local > 0 && remote > 0 => {
                    local.abs_diff(remote) <= 10_000
                }
                _ => true,
            };
            if duration_matches
                && candidate_title.trim().to_lowercase() == title
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
        conn.execute(
            "UPDATE drive_event_state SET owner_account_subject = ?2
                      WHERE scrobble_id = ?1 AND owner_account_subject IS NULL",
            params![id, account_subject],
        )
        .map_err(|e| e.to_string())?;
        // Legacy local rows can predate Drive origin-state initialization.
        // Claim their original Desktop identity BEFORE recording the remote
        // alias, otherwise an early incoming copy would classify the original
        // local play as an import and suppress its future Drive upload.
        let has_state: bool = conn
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM drive_event_state WHERE scrobble_id = ?1)",
                [id],
                |row| row.get(0),
            )
            .map_err(|e| e.to_string())?;
        if !has_state {
            let (local_title, local_artist, local_timestamp): (String, String, i64) = conn
                .query_row(
                    "SELECT title, artist, timestamp_utc FROM scrobbles WHERE id = ?1",
                    [id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .map_err(|e| e.to_string())?;
            let own_device = load_state(conn)?.device_id;
            pin_local_origin(
                conn,
                &own_device,
                id,
                local_timestamp,
                &local_title,
                &local_artist,
            )?;
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
        conn.execute(
            "UPDATE drive_event_state SET owner_account_subject = ?2
                      WHERE scrobble_id = ?1 AND owner_account_subject IS NULL",
            params![id, account_subject],
        )
        .map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_event_aliases SET account_subject = ?2
                      WHERE scrobble_id = ?1 AND account_subject = ?3",
            params![id, account_subject, LEGACY_UNVERIFIED_ACCOUNT],
        )
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
pub(crate) fn insert_remote_event(
    conn: &Connection,
    source_device_id: &str,
    event: &WireEvent,
) -> Result<bool, String> {
    let subject = load_state(conn)?
        .account_subject
        .unwrap_or_else(|| "test-account".to_string());
    insert_remote_event_for_account(conn, source_device_id, event, &subject)
}

// Keep a durable per-account record of corrupt batches even after advancing
// the receive cursor. A successful later sync must not hide skipped history.

pub(crate) fn mark_rejected_batch(
    conn: &Connection,
    account_subject: &str,
    file: &DriveFileRecord,
    reason: &str,
) -> Result<(), String> {
    let short_reason: String = reason.chars().take(300).collect();
    conn.execute(
        "INSERT INTO drive_rejected_files
            (account_subject, file_id, file_name, rejection_reason, recorded_at)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(account_subject, file_id) DO UPDATE SET
            file_name = excluded.file_name,
            rejection_reason = excluded.rejection_reason,
            recorded_at = excluded.recorded_at",
        params![account_subject, file.id, file.name, short_reason, now_ms()],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

pub(crate) fn clear_rejected_batch(
    conn: &Connection,
    account_subject: &str,
    file_id: &str,
) -> Result<(), String> {
    conn.execute(
        "DELETE FROM drive_rejected_files WHERE account_subject = ?1 AND file_id = ?2",
        params![account_subject, file_id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

pub(crate) fn count_rejected_batches(conn: &Connection) -> Result<i64, String> {
    let subject = load_state(conn)?
        .account_subject
        .ok_or("Google account identity is unavailable")?;
    conn.query_row(
        "SELECT COUNT(*) FROM drive_rejected_files WHERE account_subject = ?1",
        [subject],
        |row| row.get(0),
    )
    .map_err(|e| e.to_string())
}

pub(crate) fn sample_rejected_batch(conn: &Connection) -> Result<Option<(String, String)>, String> {
    let subject = load_state(conn)?
        .account_subject
        .ok_or("Google account identity is unavailable")?;
    conn.query_row(
        "SELECT file_name, rejection_reason FROM drive_rejected_files
         WHERE account_subject = ?1 ORDER BY recorded_at DESC, file_id ASC LIMIT 1",
        [subject],
        |row| Ok((row.get(0)?, row.get(1)?)),
    )
    .optional()
    .map_err(|e| e.to_string())
}

// Full-history scans can retire a warning when the broken file was deleted.
// Incremental scans cannot infer deletion for files outside their time window.
pub(crate) fn clear_missing_rejected_batches(
    conn: &Connection,
    account_subject: &str,
    listed_files: &[DriveFileRecord],
) -> Result<(), String> {
    let known: std::collections::HashSet<&str> =
        listed_files.iter().map(|f| f.id.as_str()).collect();
    let mut stmt = conn
        .prepare("SELECT file_id FROM drive_rejected_files WHERE account_subject = ?1")
        .map_err(|e| e.to_string())?;
    let existing = stmt
        .query_map([account_subject], |row| row.get::<_, String>(0))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    drop(stmt);
    for id in existing {
        if !known.contains(id.as_str()) {
            clear_rejected_batch(conn, account_subject, &id)?;
        }
    }
    Ok(())
}

pub(crate) fn accept_deletion_marker(
    conn: &Connection,
    marker_version: i64,
    message: Option<&str>,
) -> Result<(), String> {
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let subject: String = tx
        .query_row(
            "SELECT account_subject FROM drive_sync_state WHERE id = 1",
            [],
            |row| row.get::<_, Option<String>>(0),
        )
        .map_err(|e| e.to_string())?
        .filter(|value| !value.trim().is_empty())
        .ok_or("Cannot apply a Drive deletion without a verified Google account")?;

    // Keep uploaded_at intact for old captures, but retain new recordings
    // made offline after the server-side deletion marker. A stale reconnect
    // must not fence those recordings merely because they existed locally.
    tx.execute(
        "INSERT OR IGNORE INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject, cloud_suppressed)
         SELECT s.id, 0, ?1, 1 FROM scrobbles s
         WHERE s.timestamp_utc <= ?2
           AND NOT EXISTS (SELECT 1 FROM drive_event_state d WHERE d.scrobble_id = s.id)",
        params![subject, marker_version],
    )
    .map_err(|e| e.to_string())?;
    tx.execute(
        "UPDATE drive_event_state SET
             owner_account_subject = COALESCE(owner_account_subject, ?1),
             cloud_suppressed = 1
         WHERE drive_imported = 0
           AND (owner_account_subject IS NULL OR owner_account_subject = ?1)
           AND (drive_uploaded_at IS NOT NULL OR scrobble_id IN (
               SELECT id FROM scrobbles WHERE timestamp_utc <= ?2
           ))",
        params![subject, marker_version],
    )
    .map_err(|e| e.to_string())?;
    tx.execute(
        "DELETE FROM drive_rejected_files WHERE account_subject = ?1",
        [subject.as_str()],
    )
    .map_err(|e| e.to_string())?;
    tx.execute(
        "UPDATE drive_sync_state SET enabled = 0, accepted_disable_version = ?1,
         download_cursor = 0, last_uploaded = 0, last_imported = 0,
         offline_capture_account_subject = ?3, last_error = ?2 WHERE id = 1",
        params![marker_version, message, subject],
    )
    .map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())
}

// An explicit opt-in can assign older local captures to the currently linked
// account. Imported cloud events are never rebroadcast as new local origins.
pub(crate) fn authorize_existing_local_history(
    conn: &Connection,
    subject: &str,
) -> Result<usize, String> {
    if subject.trim().is_empty() {
        return Err("Google account identity is missing".into());
    }
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    tx.execute(
        "INSERT OR IGNORE INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject)
         SELECT id, 0, ?1 FROM scrobbles",
        [subject],
    )
    .map_err(|e| e.to_string())?;
    let reassigned = tx
        .execute(
            "UPDATE drive_event_state SET owner_account_subject = ?1, drive_uploaded_at = NULL,
             cloud_suppressed = 0
         WHERE drive_imported = 0",
            [subject],
        )
        .map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(reassigned)
}
