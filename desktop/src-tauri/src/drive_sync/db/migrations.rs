//! Versioned legacy-history migrations, executed once when a local database opens.
use super::*;

// Run this historical backfill once, rather than doing a full alias-table
// INSERT/SELECT on every settings read or background sync.
pub(crate) fn migrate_legacy_import_account_and_aliases(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction
        .execute(
            "INSERT OR IGNORE INTO drive_sync_migrations (name)
         VALUES ('legacy_aliases_and_account_v1')",
            [],
        )
        .map_err(|e| e.to_string())?;
    if applied == 1 {
        // Before introducing deletion triggers, old installations could have
        // orphaned provenance. Prune it once, then the trigger maintains it.
        transaction.execute(
            "DELETE FROM drive_event_aliases WHERE scrobble_id NOT IN (SELECT id FROM scrobbles)",
            []
        ).map_err(|e| e.to_string())?;
        transaction
            .execute(
                "DELETE FROM drive_event_state WHERE scrobble_id NOT IN (SELECT id FROM scrobbles)",
                [],
            )
            .map_err(|e| e.to_string())?;
        let scoped = {
            let mut stmt = transaction
                .prepare("PRAGMA table_info(drive_event_aliases)")
                .map_err(|e| e.to_string())?;
            let names = stmt
                .query_map([], |row| row.get::<_, String>(1))
                .map_err(|e| e.to_string())?;
            names
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e| e.to_string())?
                .contains(&"account_subject".to_string())
        };
        if scoped {
            transaction
                .execute(
                    "INSERT OR IGNORE INTO drive_event_aliases
                  (account_subject, origin_event_id, source_device_id, scrobble_id)
                 SELECT COALESCE(NULLIF(owner_account_subject, ''), 'legacy-unverified'),
                        origin_event_id, origin_device_id, scrobble_id
                 FROM drive_event_state
                 WHERE origin_event_id IS NOT NULL AND origin_device_id IS NOT NULL
                       AND origin_device_id <> ''",
                    [],
                )
                .map_err(|e| e.to_string())?;
        } else {
            transaction
                .execute(
                    "INSERT OR IGNORE INTO drive_event_aliases
                  (origin_event_id, source_device_id, scrobble_id)
                 SELECT origin_event_id, origin_device_id, scrobble_id
                 FROM drive_event_state
                 WHERE origin_event_id IS NOT NULL AND origin_device_id IS NOT NULL
                       AND origin_device_id <> ''",
                    [],
                )
                .map_err(|e| e.to_string())?;
        }
        // Older releases did not record the Google owner of imported events.
        // No current-account guess can safely prove which account provided
        // them; quarantine their temporal matches until explicitly restored.
        transaction
            .execute(
                "UPDATE drive_event_state SET owner_account_subject = ?1
             WHERE drive_imported != 0 AND owner_account_subject IS NULL",
                [LEGACY_UNVERIFIED_ACCOUNT],
            )
            .map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

pub(crate) fn requeue_unverified_prototype_uploads(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction
        .execute(
            "INSERT OR IGNORE INTO drive_sync_migrations (name) VALUES ('verified_batches_v1')",
            [],
        )
        .map_err(|e| e.to_string())?;
    if applied == 1 {
        // Earlier Desktop batches omitted tempo_sha256 and were rejected by
        // Android/browser readers. Re-send local events once with verified metadata.
        transaction
            .execute(
                "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
                [],
            )
            .map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

pub(crate) fn rescan_reconciled_origins(conn: &Connection) -> Result<(), String> {
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = transaction
        .execute(
            "INSERT OR IGNORE INTO drive_sync_migrations (name) VALUES ('reconciled_origins_v1')",
            [],
        )
        .map_err(|e| e.to_string())?;
    if applied == 1 {
        // Older temporal reconciliation overwrote origins. Re-read retained
        // cloud batches once to recover their identities and any skipped replays.
        transaction
            .execute(
                "UPDATE drive_sync_state SET download_cursor = 0 WHERE id = 1",
                [],
            )
            .map_err(|e| e.to_string())?;
    }
    transaction.commit().map_err(|e| e.to_string())
}

/// Rebuild old globally-unique producer IDs as (Google subject, event ID).
/// Preserve every listening row; unverified legacy identities stay quarantined.
/// This runs once and commits the schema change and migration flag together.
pub(crate) fn migrate_account_scoped_origin_keys(conn: &Connection) -> Result<(), String> {
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    let applied = tx
        .execute(
            "INSERT OR IGNORE INTO drive_sync_migrations (name)
         VALUES ('account_scoped_origin_keys_v1')",
            [],
        )
        .map_err(|e| e.to_string())?;
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
             END;",
        )
        .map_err(|e| e.to_string())?;
    }
    tx.commit().map_err(|e| e.to_string())
}

