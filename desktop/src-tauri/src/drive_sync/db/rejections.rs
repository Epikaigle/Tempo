//! Durable corrupt-batch diagnostics for verified Google accounts.
use super::*;
use rusqlite::OptionalExtension;
use super::super::models::DriveFileRecord;

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

