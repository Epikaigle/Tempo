pub mod client;
pub mod db;
pub mod models;
pub mod oauth;

#[cfg(test)]
mod tests;

use once_cell::sync::Lazy;
use rusqlite::params;
use std::path::Path;
use tauri::{AppHandle, State};

use crate::AppState;

pub use db::{lan_play_account_owners, lan_play_origin, persist_lan_origin_metadata};
pub use models::{DriveSyncResult, DriveSyncStatus};

pub(crate) use client::*;
pub(crate) use db::*;
pub(crate) use models::*;
pub(crate) use oauth::*;

static SYNC_LOCK: Lazy<tokio::sync::Mutex<()>> = Lazy::new(|| tokio::sync::Mutex::new(()));

pub(crate) async fn run_sync(app_data_dir: &Path) -> Result<DriveSyncResult, String> {
    let _guard = SYNC_LOCK.lock().await;
    let result = run_sync_locked(app_data_dir).await;
    // Scheduled and manual operations must persist auth failures identically,
    // otherwise the UI can keep showing stale "Connected" state for hours.
    if let Err(err) = &result {
        if let Ok(conn) = open_sync_db(app_data_dir) {
            let _ = set_last_error(&conn, Some(err));
        }
    }
    result
}

pub(crate) async fn run_sync_locked(app_data_dir: &Path) -> Result<DriveSyncResult, String> {
    run_sync_locked_with_restore(app_data_dir, false).await
}

pub(crate) async fn run_sync_locked_with_restore(
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
    let mut warnings = Vec::new();
    if let Some(first_id) = progress.first_rejected_id {
        warnings.push(format!(
            "{} local Desktop play(s) could not be exported (first local ID {}). They remain saved locally. Correct their metadata to retry.",
            progress.rejected, first_id
        ));
    }
    let warnings_conn = open_sync_db(app_data_dir)?;
    let rejected = count_rejected_batches(&warnings_conn)?;
    if rejected > 0 {
        let example = sample_rejected_batch(&warnings_conn)?
            .map(|(name, reason)| format!(
                " Example: {} ({})",
                name.chars().take(100).collect::<String>(),
                reason.chars().take(150).collect::<String>()
            )).unwrap_or_default();
        warnings.push(format!(
            "{rejected} invalid Google Drive history batch(es) were skipped.{example} Repair or remove the affected files, then use Restore all history."
        ));
    }
    let warning = (!warnings.is_empty()).then(|| warnings.join(" "));
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

// A cached token or refresh credential only proves that credentials exist,
 // not that Google still accepts them. Persisted auth failures must take
 // precedence until a successful reauthorization or sync clears the error.
pub(crate) fn google_reconnect_required(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("http 401")
        || lower.contains("authorization failed")
        || lower.contains("invalid_grant")
        || lower.contains("invalid_client")
        || lower.contains("insufficientpermissions")
        || lower.contains("autherror")
        || lower.contains("needs you to reconnect")
        || lower.contains("reconnect google")
        || lower.contains("reconnect securely")
        || lower.contains("different account")
}

pub(crate) async fn status_for(app_data_dir: &Path) -> Result<DriveSyncStatus, String> {
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
        && !state.last_error.as_deref().is_some_and(google_reconnect_required)
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
    let previous = load_state(&conn)?;
    if marker_version > previous.accepted_disable_version {
        // This computer may have been disconnected while Android or another
        // browser deleted the cloud archive. Apply the deletion fence BEFORE
        // accepting the new generation or starting any upload.
        accept_deletion_marker(&conn, marker_version, None)?;
    }
    // Reconnection never implicitly republishes history suppressed by deletion.
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, accepted_disable_version = ?1,
         download_cursor = CASE WHEN ?1 > accepted_disable_version THEN 0 ELSE download_cursor END,
         offline_capture_account_subject = NULL, last_error = NULL WHERE id = 1",
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
         token_expires_at = 0, offline_capture_account_subject = NULL,
         last_error = NULL WHERE id = 1",
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


