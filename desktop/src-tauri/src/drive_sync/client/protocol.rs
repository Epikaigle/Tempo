//! Immutable Tempo Drive wire format, origin identity and integrity checks.
//! Kept separate from the network and sync lifecycle to avoid interleaving
//! serialization rules with mutable Google Drive operations.
use super::*;

pub(crate) fn validate_compressed_metadata(
    file: &DriveFileRecord,
    bytes: &[u8],
) -> Result<(), String> {
    let declared_size = file
        .size
        .as_deref()
        .and_then(|value| value.parse::<usize>().ok());
    let expected_hash = file.app_properties.get(APP_PROPERTY_SHA256);
    if declared_size != Some(bytes.len())
        || !expected_hash
            .is_some_and(|hash| valid_sha256(hash) && *hash == hex::encode(Sha256::digest(bytes)))
    {
        return Err(
            "Malformed Drive history batch: size or SHA-256 does not match metadata".to_string(),
        );
    }
    Ok(())
}

pub(crate) fn encode_batch(batch: &WireBatch) -> Result<Vec<u8>, String> {
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

pub(crate) fn decode_batch(bytes: &[u8]) -> Result<WireBatch, String> {
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

pub(crate) fn valid_identifier(value: &str, max_length: usize) -> bool {
    !value.is_empty()
        && value.len() <= max_length
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
}

pub(crate) fn valid_text(value: &str) -> bool {
    !value.trim().is_empty() && value.encode_utf16().count() <= 1000
}

pub(crate) fn valid_optional_text(value: &Option<String>) -> bool {
    value
        .as_deref()
        .map_or(true, |value| value.encode_utf16().count() <= 1000)
}

pub(crate) fn validate_batch(batch: &WireBatch) -> Result<(), String> {
    if batch.schema_version != SCHEMA_VERSION
        || !valid_sha256(&batch.batch_id)
        || !valid_identifier(&batch.source_device_id, 200)
        || !valid_text(&batch.source_device_name)
        || !valid_identifier(&batch.source_platform, 100)
        || !(1..=MAX_WIRE_INTEGER).contains(&batch.created_at_utc)
        || batch.events.is_empty()
        || batch.events.len() > 1000
        || !batch.events.iter().all(valid_event)
        || batch.batch_id != batch_id(&batch.events)
    {
        return Err("Unsupported or malformed Tempo Drive history batch".to_string());
    }
    Ok(())
}

pub(crate) fn matches_batch_metadata(file: &DriveFileRecord, batch: &WireBatch) -> bool {
    batch_generation(file).is_some_and(|generation| {
        file.name == batch_file_name(generation, &batch.source_device_id, &batch.batch_id)
    }) && file.app_properties.get("tempo_kind").map(String::as_str) == Some("history_batch")
        && file.app_properties.get("tempo_schema").map(String::as_str) == Some("1")
        && file.app_properties.get("source_device_id") == Some(&batch.source_device_id)
        && file.app_properties.get("source_platform") == Some(&batch.source_platform)
}

pub(crate) fn event_id(device_id: &str, play: &LocalPlay) -> String {
    if let Some(origin) = play
        .origin_event_id
        .as_deref()
        .filter(|value| valid_sha256(value))
    {
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

pub(crate) fn batch_id(events: &[WireEvent]) -> String {
    let mut canonical = String::with_capacity(14 + events.len() * 65);
    canonical.push_str("tempo-batch-v1");
    for event in events {
        canonical.push('|');
        canonical.push_str(&event.event_id);
    }
    sha256_hex(&canonical)
}

pub(crate) fn protocol_volume(play: &LocalPlay) -> Option<i64> {
    if play.is_muted {
        return Some(0);
    }
    if !play.volume_level.is_finite() || play.volume_level < 0.0 {
        return None;
    }
    if play.volume_level == 0.0 {
        return Some(0);
    }
    // Desktop stores fractional volume. Positive gain must never become mute;
    // MPRIS amplification above 1.0 is capped at the protocol's 100 percent.
    Some(((play.volume_level.clamp(0.0, 1.0) * 100.0).round() as i64).max(1))
}

pub(crate) fn local_to_wire(device_id: &str, play: &LocalPlay) -> WireEvent {
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

pub(crate) fn verified_upload(
    file: &DriveFileRecord,
    name: &str,
    device: &str,
    generation: i64,
    bytes: &[u8],
) -> bool {
    file.name == name
        && validate_compressed_metadata(file, bytes).is_ok()
        && file.app_properties.get("tempo_kind").map(String::as_str) == Some("history_batch")
        && file.app_properties.get("tempo_schema").map(String::as_str) == Some("1")
        && file
            .app_properties
            .get("source_device_id")
            .map(String::as_str)
            == Some(device)
        && file
            .app_properties
            .get("source_platform")
            .map(String::as_str)
            == Some("desktop")
        && batch_generation(file) == Some(generation)
}

