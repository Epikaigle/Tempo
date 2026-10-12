use chrono::{DateTime, SecondsFormat, Utc};
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use reqwest::{header, Method, RequestBuilder, Response, StatusCode};
use rusqlite::params;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;
use url::Url;
use uuid::Uuid;

// Pure serialization, identity and integrity rules live in a separate module.
mod protocol;
pub(crate) use protocol::*;

use super::db::{
    accept_deletion_marker, clear_missing_rejected_batches, clear_rejected_batch,
    insert_remote_event_for_account, load_state, mark_rejected_batch, open_sync_db,
    pending_local_plays_page, pin_local_origin, remember_origin,
};
use super::models::{
    http_client, now_ms, sha256_hex, valid_sha256, DriveFileRecord, DriveListResponse, LocalPlay,
    WireBatch, WireEvent, APP_PROPERTY_GENERATION, APP_PROPERTY_SHA256, BATCH_SIZE,
    DISABLE_MARKER_NAME, DOWNLOAD_OVERLAP_MS, DRIVE_API, DRIVE_REQUEST_RETRIES, DRIVE_UPLOAD_API,
    FILE_PREFIX, MAX_BATCH_BYTES, MAX_LOCAL_SCAN, MAX_WIRE_INTEGER, SCHEMA_VERSION,
};

pub(crate) fn parse_time_ms(value: Option<&str>) -> i64 {
    value
        .and_then(|text| DateTime::parse_from_rfc3339(text).ok())
        .map(|time| time.timestamp_millis())
        .unwrap_or(0)
}

pub(crate) fn require_server_time(value: Option<&str>, label: &str) -> Result<i64, String> {
    let parsed = parse_time_ms(value);
    if parsed <= 0 {
        Err(format!(
            "Google Drive did not return a valid {label} timestamp"
        ))
    } else {
        Ok(parsed)
    }
}

pub(crate) fn transient_drive_response(status: StatusCode) -> bool {
    matches!(status.as_u16(), 429 | 500 | 502 | 503 | 504)
}

// Only operations that cannot mint a new Drive object or a new deletion
// generation may be retried. Even a PATCH of the *same* marker body updates
// modifiedTime, so treating PATCH as idempotent would be unsafe.
pub(crate) fn is_drive_retry_safe(method: &Method) -> bool {
    matches!(method.as_str(), "GET" | "HEAD" | "DELETE")
}

pub(crate) async fn send_drive_idempotent(request: RequestBuilder) -> Result<Response, String> {
    let method = request
        .try_clone()
        .ok_or("Drive request cannot be inspected safely")?
        .build()
        .map_err(|e| e.to_string())?
        .method()
        .clone();
    if !is_drive_retry_safe(&method) {
        // Fail closed against future callers accidentally submitting a POST,
        // PATCH or PUT to the reusable retry path.
        return Err(format!("Unsafe Google Drive request cannot be retried: {method}"));
    }
    for attempt in 0..DRIVE_REQUEST_RETRIES {
        let replay = request
            .try_clone()
            .ok_or("Drive request cannot be retried safely")?;
        match replay.send().await {
            Ok(response)
                if transient_drive_response(response.status())
                    && attempt + 1 < DRIVE_REQUEST_RETRIES =>
            {
                let seconds = response
                    .headers()
                    .get(header::RETRY_AFTER)
                    .and_then(|h| h.to_str().ok())
                    .and_then(|s| s.parse::<u64>().ok())
                    .map(|s| s.min(5));
                tokio::time::sleep(
                    seconds
                        .map(Duration::from_secs)
                        .unwrap_or_else(|| Duration::from_millis(500u64 * (1 << attempt))),
                )
                .await;
            }
            Ok(response) => return Ok(response),
            Err(error)
                if attempt + 1 < DRIVE_REQUEST_RETRIES
                    && (error.is_timeout() || error.is_connect()) =>
            {
                tokio::time::sleep(Duration::from_millis(500u64 * (1 << attempt))).await;
            }
            Err(error) => return Err(format!("Google Drive request failed: {error}")),
        }
    }
    Err("Google Drive retry budget exhausted".into())
}

pub(crate) async fn drive_json<T: for<'de> Deserialize<'de>>(
    access_token: &str,
    url: Url,
) -> Result<T, String> {
    let response = send_drive_idempotent(http_client()?.get(url).bearer_auth(access_token)).await?;
    if !response.status().is_success() {
        let status = response.status();
        let detail = if status.as_u16() == 403 {
            response.text().await.unwrap_or_default()
        } else {
            String::new()
        };
        if status.as_u16() == 403
            && (detail.contains("insufficientPermissions") || detail.contains("authError"))
        {
            return Err(format!(
                "Google Drive authorization failed (HTTP {status}). Reconnect Google."
            ));
        }
        return Err(format!("Google Drive request failed (HTTP {status})"));
    }
    response.json::<T>().await.map_err(|e| e.to_string())
}

pub(crate) async fn list_files(
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

// Every batch must be processed oldest-first before we checkpoint a createdTime
// cursor. Sorting by the file id gives stable ordering for equal timestamps.
pub(crate) fn sort_batches_oldest_first(files: &mut [DriveFileRecord]) {
    files.sort_by(|a, b| {
        parse_time_ms(a.created_time.as_deref())
            .cmp(&parse_time_ms(b.created_time.as_deref()))
            .then_with(|| a.id.cmp(&b.id))
    });
}

pub(crate) async fn list_batches(
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
    // Google Drive can time out on ordered listings of large appDataFolder
    // archives. List without server-side ordering, then order in memory so
    // incremental download checkpoints never skip an older file after a crash.
    let mut files: Vec<DriveFileRecord> = list_files(access_token, &query, None)
        .await?
        .into_iter()
        .filter(|file| file.name.starts_with(FILE_PREFIX) && file.name.ends_with(".json.gz"))
        .collect();
    sort_batches_oldest_first(&mut files);
    Ok(files)
}

pub(crate) async fn find_exact_files(
    access_token: &str,
    name: &str,
) -> Result<Vec<DriveFileRecord>, String> {
    let escaped = name.replace('\\', "\\\\").replace('\'', "\\'");
    let query = format!("name = '{escaped}' and trashed = false");
    list_files(access_token, &query, None).await
}

pub(crate) fn latest_marker_version(files: &[DriveFileRecord]) -> Result<i64, String> {
    files.iter().try_fold(0, |latest, file| {
        Ok(latest.max(require_server_time(
            file.modified_time.as_deref(),
            "deletion marker",
        )?))
    })
}

pub(crate) async fn get_disable_marker_version(access_token: &str) -> Result<i64, String> {
    latest_marker_version(&find_exact_files(access_token, DISABLE_MARKER_NAME).await?)
}

pub(crate) fn batch_generation(file: &DriveFileRecord) -> Option<i64> {
    match file.app_properties.get(APP_PROPERTY_GENERATION) {
        None => Some(0),
        Some(value) if !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit()) => {
            value
                .parse::<i64>()
                .ok()
                .filter(|value| (0..=MAX_WIRE_INTEGER).contains(value))
        }
        _ => None,
    }
}

pub(crate) fn batch_file_name(generation: i64, device_id: &str, batch_id: &str) -> String {
    format!("{FILE_PREFIX}g{generation}_{device_id}_{batch_id}.json.gz")
}

pub(crate) async fn download_bytes(
    access_token: &str,
    file: &DriveFileRecord,
) -> Result<Vec<u8>, String> {
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
    let response = send_drive_idempotent(http_client()?.get(url).bearer_auth(access_token)).await?;
    if !response.status().is_success() {
        return Err(format!(
            "Drive download failed (HTTP {})",
            response.status()
        ));
    }
    let mut response = response;
    let expected_capacity = file
        .size
        .as_deref()
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(0)
        .min(MAX_BATCH_BYTES);
    let mut bytes = Vec::with_capacity(expected_capacity);
    while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
        if bytes.len().saturating_add(chunk.len()) > MAX_BATCH_BYTES {
            return Err(format!("Drive batch {} is too large", file.name));
        }
        bytes.extend_from_slice(&chunk);
    }
    validate_compressed_metadata(file, &bytes)?;
    Ok(bytes)
}

pub(crate) async fn upload_batch(
    access_token: &str,
    file_name: &str,
    device_id: &str,
    generation: i64,
    compressed: &[u8],
) -> Result<(), String> {
    let existing = find_exact_files(access_token, file_name).await?;
    if existing
        .iter()
        .any(|file| verified_upload(file, file_name, device_id, generation, compressed))
    {
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
    let header = format!(
        "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: application/gzip\r\n\r\n"
    );
    let footer = format!("\r\n--{boundary}--\r\n");
    let mut body = Vec::with_capacity(header.len() + compressed.len() + footer.len());
    body.extend_from_slice(header.as_bytes());
    body.extend_from_slice(compressed);
    body.extend_from_slice(footer.as_bytes());

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
        return Err(
            "Google Drive returned mismatched metadata for the uploaded history batch".into(),
        );
    }
    Ok(())
}

#[derive(Default)]
pub(crate) struct DriveUploadProgress {
    pub(crate) uploaded: usize,
    pub(crate) rejected: usize,
    pub(crate) first_rejected_id: Option<i64>,
}

pub(crate) async fn upload_local_history(
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
                    pinned.insert(
                        play.id,
                        pin_local_origin(
                            &tx,
                            device_id,
                            play.id,
                            play.timestamp_utc,
                            &play.title,
                            &play.artist,
                        )?,
                    );
                }
                tx.commit().map_err(|e| e.to_string())?;
                pinned
            };
            let events: Vec<WireEvent> = chunk
                .iter()
                .map(|play| {
                    let mut wire = local_to_wire(device_id, play);
                    wire.event_id = pinned[&play.id].clone();
                    wire
                })
                .collect();
            let id = batch_id(&events);
            let batch = WireBatch {
                schema_version: SCHEMA_VERSION,
                batch_id: id.clone(),
                source_device_id: device_id.to_string(),
                source_device_name: "Tempo Desktop".to_string(),
                source_platform: "desktop".to_string(),
                created_at_utc: events
                    .iter()
                    .map(|event| event.timestamp_utc)
                    .max()
                    .unwrap_or(1),
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
                remember_origin(&tx, play.id, device_id, origin)?;
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

pub(crate) fn valid_event(event: &WireEvent) -> bool {
    valid_sha256(&event.event_id)
        && valid_text(&event.title)
        && valid_text(&event.artist)
        && valid_text(&event.source_app)
        && valid_text(&event.source)
        && valid_text(&event.content_type)
        && valid_optional_text(&event.album)
        && valid_optional_text(&event.session_id)
        && valid_optional_text(&event.site)
        && (1..=MAX_WIRE_INTEGER).contains(&event.timestamp_utc)
        && [
            event.duration_ms,
            event.listened_ms,
            event.total_pause_duration_ms,
        ]
        .iter()
        .all(|value| (0..=MAX_WIRE_INTEGER).contains(value))
        && [
            event.replay_count,
            event.pause_count,
            event.seek_count,
            event.position_updates_count,
        ]
        .iter()
        .all(|value| (0..=i32::MAX as i64).contains(value))
        && (0..=100).contains(&event.completion_percentage)
        && event
            .volume_level
            .map_or(true, |value| (0..=100).contains(&value))
}

pub(crate) async fn download_remote_history(
    app_data_dir: &Path,
    access_token: &str,
    device_id: &str,
    include_own_device_batches: bool,
) -> Result<(usize, usize), String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    let account_subject = state
        .account_subject
        .as_deref()
        .filter(|subject| !subject.trim().is_empty())
        .ok_or("Google account identity is missing; reconnect securely")?;
    let accepted_generation = state.accepted_disable_version.max(0);
    let after =
        (state.download_cursor > 0).then_some((state.download_cursor - DOWNLOAD_OVERLAP_MS).max(0));
    let files = list_batches(access_token, after).await?;
    if after.is_none() {
        clear_missing_rejected_batches(&conn, account_subject, &files)?;
    }
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
            )
            .map_err(|e| e.to_string())?;
        }
        let created = parse_time_ms(file.created_time.as_deref());
        let Some(generation) = batch_generation(&file) else {
            log::warn!(
                "Skipping Drive history batch with invalid generation: {}",
                file.name
            );
            mark_rejected_batch(
                &conn,
                account_subject,
                &file,
                "Invalid or missing Drive generation",
            )?;
            max_created = max_created.max(created);
            continue;
        };
        if generation < accepted_generation {
            // A file with missing generation metadata can still be a newly
            // re-seeded batch: its immutable filename is the deletion guard.
            // Report conflicting metadata instead of deleting valid new history.
            if !should_delete_history_batch(&file, accepted_generation, false) {
                mark_rejected_batch(
                    &conn,
                    account_subject,
                    &file,
                    "Generation metadata conflicts with newer batch filename",
                )?;
                max_created = max_created.max(created);
                continue;
            }
            clear_rejected_batch(&conn, account_subject, &file.id)?;
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
            mark_rejected_batch(
                &conn,
                account_subject,
                &file,
                "Drive history batch exceeds the maximum size",
            )?;
            max_created = max_created.max(created);
            continue;
        }

        let bytes = match download_bytes(access_token, &file).await {
            Ok(bytes) => bytes,
            Err(err)
                if err.contains("too large")
                    || err.starts_with("Malformed Drive history batch:") =>
            {
                // Permanently malformed/hostile payload. Consume this one file so
                // it cannot block every later batch forever.
                log::warn!(
                    "Skipping invalid Drive history batch {}: {}",
                    file.name,
                    err
                );
                mark_rejected_batch(&conn, account_subject, &file, &err)?;
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
                mark_rejected_batch(&conn, account_subject, &file, &err)?;
                max_created = max_created.max(created);
                continue;
            }
        };
        if !matches_batch_metadata(&file, &batch) {
            log::warn!(
                "Skipping history batch whose payload does not match Drive metadata: {}",
                file.name
            );
            mark_rejected_batch(
                &conn,
                account_subject,
                &file,
                "Batch payload disagrees with verified Google Drive metadata",
            )?;
            max_created = max_created.max(created);
            continue;
        }
        if !include_own_device_batches && batch.source_device_id == device_id {
            max_created = max_created.max(created);
            continue;
        }
        let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
        for event in &batch.events {
            if insert_remote_event_for_account(
                &transaction,
                &batch.source_device_id,
                event,
                account_subject,
            )? {
                imported += 1;
            } else {
                duplicates += 1;
            }
        }
        transaction.commit().map_err(|e| e.to_string())?;
        clear_rejected_batch(&conn, account_subject, &file.id)?;
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

pub(crate) async fn delete_file(access_token: &str, id: &str) -> Result<(), String> {
    let response = send_drive_idempotent(
        http_client()?
            .delete(format!("{DRIVE_API}/files/{id}"))
            .bearer_auth(access_token),
    )
    .await?;
    if response.status().is_success() || response.status().as_u16() == 404 {
        Ok(())
    } else {
        Err(format!("Drive delete failed (HTTP {})", response.status()))
    }
}

// A damaged/missing appProperty is NOT proof of legacy generation zero.
// A current-generation filename must survive cleanup from an out-of-date client,
// including deliberate owner deletion racing a newly re-enabled device.
fn batch_filename_generation(file_name: &str) -> Option<i64> {
    let suffix = file_name.strip_prefix(FILE_PREFIX)?.strip_prefix('g')?;
    let (digits, _) = suffix.split_once('_')?;
    if digits.is_empty() || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    digits.parse::<i64>().ok().filter(|value| (0..=MAX_WIRE_INTEGER).contains(value))
}

// Owner-initiated cleanup removes invalid-generation Tempo history files.
// A stale client may only delete provably older generations, never unknowns.
pub(crate) fn should_delete_history_batch(
    file: &DriveFileRecord,
    generation: i64,
    owner_delete: bool,
) -> bool {
    if !file.name.starts_with(FILE_PREFIX) {
        return false;
    }
    if !file.name.ends_with(".json.gz") {
        return owner_delete;
    }
    if batch_filename_generation(&file.name).is_some_and(|named| named >= generation) {
        return false;
    }
    match batch_generation(file) {
        Some(file_generation) => file_generation < generation,
        None => owner_delete,
    }
}

pub(crate) async fn delete_batches_before_generation(
    access_token: &str,
    generation: i64,
    owner_delete: bool,
) -> Result<usize, String> {
    // Include even malformed filenames in Tempo's private history namespace:
    // explicit cloud deletion must not strand an invalid old upload.
    let files = list_files(
        access_token,
        &format!("name contains '{FILE_PREFIX}' and trashed = false"),
        None,
    )
    .await?;
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

pub(crate) async fn bump_disable_marker(access_token: &str) -> Result<i64, String> {
    let previous = get_disable_marker_version(access_token).await?;
    let marker_body = serde_json::json!({
        "schema_version": 1, "history_sync_disabled": true, "revision": Uuid::new_v4().to_string()
    })
    .to_string();
    if find_exact_files(access_token, DISABLE_MARKER_NAME)
        .await?
        .is_empty()
    {
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
            // Updating a marker changes its server-side modifiedTime, which is
            // the deletion generation. Replaying an ambiguous PATCH after a
            // timeout/5xx could publish an unintended newer deletion fence.
            // The caller must inspect the remote marker on its next attempt.
            let response = http_client()?
                .patch(format!(
                    "{DRIVE_UPLOAD_API}/files/{}?uploadType=media&fields=id,name,modifiedTime",
                    marker.id
                ))
                .bearer_auth(access_token)
                .header(header::CONTENT_TYPE, "application/json")
                .body(marker_body.clone())
                .send()
                .await
                .map_err(|e| format!(
                    "Google Drive deletion marker update outcome uncertain; check the marker before retrying: {e}"
                ))?;
            if !response.status().is_success() {
                return Err(format!(
                    "Could not update Drive disable marker (HTTP {})",
                    response.status()
                ));
            }
            updated.push(
                response
                    .json::<DriveFileRecord>()
                    .await
                    .map_err(|e| e.to_string())?,
            );
        }
        let version = latest_marker_version(&updated)?;
        if version > previous {
            return Ok(version);
        }
        if attempt < 2 {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    }
    Err("Google Drive did not advance the deletion marker version".into())
}

pub(crate) async fn honor_remote_disable_if_needed(
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
