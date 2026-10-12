//! Durable provenance identities for local/LAN plays (independent of cloud upload).
use super::*;

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

