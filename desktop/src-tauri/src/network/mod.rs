pub mod discovery;
pub mod mdns;
pub mod subnet;
pub mod wifi;

use crate::db::models::{SyncPayload, SyncPlay};
use crate::AppState;
use log::{error, info, warn};
use tauri::Emitter;
use tauri::Manager;

/// Maximum number of retry attempts for a sync operation.
const MAX_RETRIES: u32 = 3;
/// Initial retry delay (doubles each attempt via exponential backoff).
const INITIAL_RETRY_DELAY_MS: u64 = 1000;
/// Timeout for HTTP requests to the phone.
const REQUEST_TIMEOUT_SECS: u64 = 10;

#[derive(Debug, thiserror::Error)]
pub enum SyncError {
    #[error("Not paired with any device")]
    NotPaired,
    #[error("No plays in queue")]
    EmptyQueue,
    #[error("Phone unreachable: {0}")]
    Unreachable(String),
    #[error("Phone rejected payload: {0}")]
    Rejected(String),
    #[error("Network error: {0}")]
    Network(String),
    #[error("Database error: {0}")]
    DatabaseError(String),
    #[error("Phone battery is critically low")]
    BatteryCritical,
}

/// Drain a bounded number of LAN batches during one manual or background
/// action. Android accepts at most 100 plays per HTTP request; each invocation
/// of sync_to_phone loads only 50. Stop on errors without claiming the remaining
/// queued history was delivered. A future wakeup can resume safely.
static LAN_SYNC_LOCK: once_cell::sync::Lazy<tokio::sync::Mutex<()>> =
    once_cell::sync::Lazy::new(|| tokio::sync::Mutex::new(()));

pub async fn sync_pending_to_phone(
    app_handle: &tauri::AppHandle,
) -> Result<usize, SyncError> {
    // Serialize the whole backlog so manual and background jobs cannot send
    // the same rows simultaneously or race while storing rotated LAN tokens.
    let _guard = LAN_SYNC_LOCK.lock().await;
    let mut total = 0usize;
    for _ in 0..20 {
        match sync_to_phone(app_handle).await {
            Ok(count) => {
                total += count;
                if count < 50 {
                    break;
                }
            }
            Err(SyncError::EmptyQueue) if total > 0 => break,
            Err(error) => return Err(error),
        }
    }
    if total == 0 {
        Err(SyncError::EmptyQueue)
    } else {
        Ok(total)
    }
}

/// Compute HMAC-SHA256 signature for a payload using the auth token as key.
fn compute_hmac(auth_token: &str, payload_json: &str) -> String {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;

    type HmacSha256 = Hmac<Sha256>;

    let mut mac = HmacSha256::new_from_slice(auth_token.as_bytes())
        .expect("HMAC accepts any key length");
    mac.update(payload_json.as_bytes());
    let result = mac.finalize();
    hex::encode(result.into_bytes())
}

/// Detailed result returned from a sync to provide feedback to the UI.
#[derive(Debug, Clone, serde::Serialize)]
#[allow(dead_code)]
pub struct SyncResult {
    pub synced_count: usize,
    pub method: String, // "primary", "network_memory", "mdns", "hotspot", "subnet_scan"
    pub resolved_ip: String,
}

#[derive(Debug, Clone)]
pub struct ResolvedPhone {
    pub ip: String,
    pub port: u16,
    pub method: String,
    pub device_name: Option<String>,
}

#[derive(Debug, serde::Deserialize)]
struct PairConfirmResponse {
    ok: bool,
    device_name: Option<String>,
}

/// Discover a phone that has already scanned the desktop QR and confirm the shared token.
pub async fn discover_confirmed_phone(
    auth_token: &str,
    preferred_port: u16,
) -> Option<ResolvedPhone> {
    // Note: network-remembered IP lookup requires DB access and is handled by
    // the caller (sync_to_phone / connection_health_loop) before invoking this.

    // Strategy A: mDNS discovery
    if let Some(discovered) = mdns::discover_phone().await {
        if let Some(device_name) = confirm_pairing_token(&discovered.ip, discovered.port, auth_token).await {
            return Some(ResolvedPhone {
                ip: discovered.ip,
                port: discovered.port,
                method: "mdns".to_string(),
                device_name,
            });
        }
    }

    // Strategy B: Hotspot gateway fallback
    if let Some(gateway) = discovery::get_default_gateway() {
        if let Some(device_name) = confirm_pairing_token(&gateway, preferred_port, auth_token).await {
            return Some(ResolvedPhone {
                ip: gateway,
                port: preferred_port,
                method: "hotspot".to_string(),
                device_name,
            });
        }
    }

    // Strategy C: Subnet scan (finds phone even when mDNS is blocked)
    if let Some(ip) = subnet::scan_subnet_for_phone(preferred_port).await {
        if let Some(device_name) = confirm_pairing_token(&ip, preferred_port, auth_token).await {
            return Some(ResolvedPhone {
                ip,
                port: preferred_port,
                method: "subnet_scan".to_string(),
                device_name,
            });
        }
    }

    None
}

/// Sync queued plays to the paired phone with multi-strategy discovery and retry.
///
/// Strategy order:
/// 1. Primary stored IP
/// 1.5 Network-remembered IP for this WiFi SSID
/// 2. mDNS auto-discovery (handles DHCP IP changes)
/// 3. Hotspot gateway fallback (handles tethering)
/// 4. Subnet scan (handles multicast-blocked networks)
///
/// Each strategy uses exponential backoff retries.
pub async fn sync_to_phone(app_handle: &tauri::AppHandle) -> Result<usize, SyncError> {
    let state = app_handle.state::<AppState>();
    let (mut pairing, plays) = {
        let db = state.db.lock().await;
        let pairing = db
            .get_pairing()
            .map_err(|e| SyncError::DatabaseError(e.to_string()))?
            .ok_or(SyncError::NotPaired)?;
        let plays = db
            .get_queued_plays()
            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
        (pairing, plays)
    };

    if pairing.phone_ip.is_empty() || pairing.phone_ip == "pending" {
        let resolved = discover_confirmed_phone(&pairing.auth_token, pairing.phone_port)
            .await
            .ok_or(SyncError::NotPaired)?;
        pairing.phone_ip = resolved.ip;
        pairing.phone_port = resolved.port;
        if let Some(device_name) = resolved.device_name {
            pairing.device_name = device_name;
        }

        let db = state.db.lock().await;
        db.save_pairing(&pairing)
            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
        let _ = app_handle.emit("pairing-confirmed", ());
    }

    if plays.is_empty() {
        return Err(SyncError::EmptyQueue);
    }

    let count = plays.len();
    let ids: Vec<i64> = plays.iter().filter_map(|s| s.id).collect();

    // Build payload
    let device_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Desktop".to_string());

    // LAN and Drive must share the first stored producer ID and event ID,
    // including if LAN is sent before Google sign-in or the track is renamed.
    // A failed persistence step must not silently downgrade to an untracked
    // origin: such a LAN delivery could later be duplicated by Drive.
    let captures: Vec<(i64, i64, String, String)> = plays.iter()
        .map(|play| {
            play.id.map(|id| (id, play.timestamp_utc, play.title.clone(), play.artist.clone()))
                .ok_or_else(|| SyncError::DatabaseError("Queued Desktop play is missing its ID".into()))
        })
        .collect::<Result<_, _>>()?;
    let (local_device_id, known_origins) =
        crate::commands::drive_sync::persist_lan_origin_metadata(
            &state.app_data_dir, &captures
        ).map_err(SyncError::DatabaseError)?;
    let drive_provenance: Vec<Option<(String, String)>> = plays.iter()
        .map(|play| play.id.map(|id| (local_device_id.clone(), known_origins[&id].clone())))
        .collect();
    let verified_owners = crate::commands::drive_sync::lan_play_account_owners(
        &state.app_data_dir,
        &captures.iter().map(|(id, _, _, _)| *id).collect::<Vec<_>>(),
    ).map_err(SyncError::DatabaseError)?;
    let payload = SyncPayload {
        auth_token: pairing.auth_token.clone(),
        device_name,
        plays: plays
            .iter()
            .zip(drive_provenance.iter())
            .map(|(s, origin)| SyncPlay {
                origin_device_id: origin.as_ref().map(|value| value.0.clone()),
                origin_event_id: origin.as_ref().map(|value| value.1.clone()),
                origin_source: origin.as_ref().map(|_| format!("desktop:{}", s.source_app)),
                origin_account_subject: s.id.and_then(|id| verified_owners.get(&id).cloned()),
                title: s.title.clone(),
                artist: s.artist.clone(),
                album: s.album.clone(),
                timestamp_utc: s.timestamp_utc,
                duration_ms: s.duration_ms,
                source_app: s.source_app.clone(),
                listened_ms: s.listened_ms,
                skipped: s.skipped,
                replay_count: s.replay_count,
                is_muted: s.is_muted,
                completion_percentage: s.completion_percentage,
                pause_count: s.pause_count,
                seek_count: s.seek_count,
                session_id: s.session_id.clone(),
                site: s.site.clone(),
                content_type: s.content_type.clone(),
                volume_level: s.volume_level,
            })
            .collect(),
    };

    // Strategy 1: Try the known IP with retries
    let phone_url = format!("http://{}:{}/api/plays", pairing.phone_ip, pairing.phone_port);
    info!("Syncing {} plays to {} (primary)", count, phone_url);

    let current_network = wifi::get_current_network_id();
    let mut last_error_reason = format!("primary ({}): no response", pairing.phone_ip);

    match send_with_retry(&phone_url, &payload).await {
        Ok(next_token) => {
            let db = state.db.lock().await;
            if let Some(token) = next_token {
                let mut updated_pairing = pairing.clone();
                updated_pairing.auth_token = token;
                db.save_pairing(&updated_pairing)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
            }
            db.mark_plays_synced(&ids)
                .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
            db.record_sync(count as i64, "success", None)
                .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
            // Remember this IP for the current WiFi network
            if let Some(ref net_id) = current_network {
                let _ = db.upsert_network_ip(net_id, &pairing.phone_ip, pairing.phone_port);
            }
            return Ok(count);
        }
        Err(SyncError::BatteryCritical) => {
            return Err(SyncError::BatteryCritical);
        }
        // Phone responded but rejected the payload — a different IP won't help
        Err(e @ SyncError::Rejected(_)) => {
            error!("Phone rejected sync payload: {}", e);
            let db = state.db.lock().await;
            if let Err(db_err) = db.mark_plays_failed(&ids) {
                warn!("Failed to mark plays as failed: {}", db_err);
            }
            let err_str = e.to_string();
            db.record_sync(0, "failed", Some(&err_str))
                .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
            return Err(e);
        }
        Err(e) => {
            last_error_reason = format!("primary ({}): {}", pairing.phone_ip, e);
            info!("Primary IP failed ({}), trying network memory...", e);
        }
    }

    // Strategy 1.5: Try network-remembered IP (last known IP for this WiFi SSID)
    if let Some(ref net_id) = current_network {
        let remembered = {
            let db = state.db.lock().await;
            db.get_network_ip(net_id).ok().flatten()
        };
        if let Some((remembered_ip, remembered_port)) = remembered {
            // Only try if it's different from the primary IP we already tried
            if remembered_ip != pairing.phone_ip || remembered_port != pairing.phone_port {
                let net_url = format!("http://{}:{}/api/plays", remembered_ip, remembered_port);
                info!("Trying network-remembered IP for '{}': {}", net_id, net_url);

                match send_with_retry(&net_url, &payload).await {
                    Ok(next_token) => {
                        // Update stored primary IP too
                        let mut updated_pairing = pairing.clone();
                        updated_pairing.phone_ip = remembered_ip.clone();
                        updated_pairing.phone_port = remembered_port;
                        if let Some(token) = next_token { updated_pairing.auth_token = token; }
                        let db = state.db.lock().await;
                        db.save_pairing(&updated_pairing)
                            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                        let _ = db.upsert_network_ip(net_id, &remembered_ip, remembered_port);
                        db.mark_plays_synced(&ids)
                            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                        db.record_sync(count as i64, "success", None)
                            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                        info!("Sync via network memory succeeded ({})", remembered_ip);
                        return Ok(count);
                    }
                    Err(SyncError::BatteryCritical) => return Err(SyncError::BatteryCritical),
                    Err(e @ SyncError::Rejected(_)) => {
                        error!("Phone rejected via network memory: {}", e);
                        let db = state.db.lock().await;
                        let _ = db.mark_plays_failed(&ids);
                        let err_str = e.to_string();
                        db.record_sync(0, "failed", Some(&err_str))
                            .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                        return Err(e);
                    }
                    Err(e) => {
                        last_error_reason = format!("network_memory ({}): {}", remembered_ip, e);
                        info!("Network-remembered IP also failed ({}), trying mDNS...", e);
                    }
                }
            }
        }
    }

    // Strategy 2: mDNS auto-discovery (handles DHCP IP changes)
    if let Some(discovered) = mdns::discover_phone().await {
        let mdns_url = format!(
            "http://{}:{}/api/plays",
            discovered.ip, discovered.port
        );
        info!("mDNS discovered phone at {}, attempting sync...", mdns_url);

        match send_with_retry(&mdns_url, &payload).await {
            Ok(next_token) => {
                // Update the stored IP so future syncs use the new address directly
                let mut updated_pairing = pairing.clone();
                updated_pairing.phone_ip = discovered.ip.clone();
                updated_pairing.phone_port = discovered.port;
                if let Some(token) = next_token { updated_pairing.auth_token = token; }
                let db = state.db.lock().await;
                db.save_pairing(&updated_pairing)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;

                db.mark_plays_synced(&ids)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                db.record_sync(count as i64, "success", None)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                // Remember this IP for the current WiFi network
                if let Some(ref net_id) = current_network {
                    let _ = db.upsert_network_ip(net_id, &discovered.ip, discovered.port);
                }
                info!("Sync via mDNS succeeded, updated stored IP to {}", discovered.ip);
                return Ok(count);
            }
            Err(SyncError::BatteryCritical) => {
                return Err(SyncError::BatteryCritical);
            }
            Err(e @ SyncError::Rejected(_)) => {
                error!("Phone rejected sync payload via mDNS: {}", e);
                let db = state.db.lock().await;
                if let Err(db_err) = db.mark_plays_failed(&ids) {
                    warn!("Failed to mark plays as failed: {}", db_err);
                }
                let err_str = e.to_string();
                db.record_sync(0, "failed", Some(&err_str))
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                return Err(e);
            }
            Err(e) => {
                last_error_reason = format!("mDNS ({}): {}", discovered.ip, e);
                info!("mDNS sync also failed ({}), trying hotspot fallback...", e);
            }
        }
    } else {
        info!("No phone found via mDNS, trying hotspot fallback...");
    }

    // Strategy 3: Hotspot gateway fallback
    if let Some(gateway) = discovery::get_default_gateway() {
        let fallback_url = format!("http://{}:{}/api/plays", gateway, pairing.phone_port);
        info!("Trying hotspot fallback: {}", fallback_url);

        match send_with_retry(&fallback_url, &payload).await {
            Ok(next_token) => {
                // Update stored IP to gateway since that's where the phone is reachable
                let mut updated_pairing = pairing.clone();
                updated_pairing.phone_ip = gateway.clone();
                if let Some(token) = next_token { updated_pairing.auth_token = token; }
                let db = state.db.lock().await;
                db.save_pairing(&updated_pairing)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;

                db.mark_plays_synced(&ids)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                db.record_sync(count as i64, "success", None)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                // Remember this IP for the current WiFi network
                if let Some(ref net_id) = current_network {
                    let _ = db.upsert_network_ip(net_id, &gateway, pairing.phone_port);
                }
                info!("Sync via hotspot fallback succeeded (gateway: {})", gateway);
                return Ok(count);
            }
            Err(SyncError::BatteryCritical) => {
                return Err(SyncError::BatteryCritical);
            }
            Err(e @ SyncError::Rejected(_)) => {
                error!("Phone rejected sync payload via hotspot: {}", e);
                let db = state.db.lock().await;
                if let Err(db_err) = db.mark_plays_failed(&ids) {
                    warn!("Failed to mark plays as failed: {}", db_err);
                }
                let err_str = e.to_string();
                db.record_sync(0, "failed", Some(&err_str))
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                return Err(e);
            }
            Err(e) => {
                last_error_reason = format!("hotspot ({}): {}", gateway, e);
                error!("Hotspot fallback also failed: {}", e);
            }
        }
    }

    // Strategy 4: Subnet scan (finds phone even when mDNS/multicast is blocked)
    info!("Trying subnet scan as last resort...");
    if let Some(found_ip) = subnet::scan_subnet_for_phone(pairing.phone_port).await {
        let scan_url = format!("http://{}:{}/api/plays", found_ip, pairing.phone_port);
        info!("Subnet scan found phone at {}, attempting sync...", scan_url);

        match send_with_retry(&scan_url, &payload).await {
            Ok(next_token) => {
                let mut updated_pairing = pairing.clone();
                updated_pairing.phone_ip = found_ip.clone();
                if let Some(token) = next_token { updated_pairing.auth_token = token; }
                let db = state.db.lock().await;
                db.save_pairing(&updated_pairing)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                if let Some(ref net_id) = current_network {
                    let _ = db.upsert_network_ip(net_id, &found_ip, pairing.phone_port);
                }
                db.mark_plays_synced(&ids)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                db.record_sync(count as i64, "success", None)
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                info!("Sync via subnet scan succeeded ({})", found_ip);
                return Ok(count);
            }
            Err(SyncError::BatteryCritical) => return Err(SyncError::BatteryCritical),
            Err(e @ SyncError::Rejected(_)) => {
                error!("Phone rejected via subnet scan: {}", e);
                let db = state.db.lock().await;
                let _ = db.mark_plays_failed(&ids);
                let err_str = e.to_string();
                db.record_sync(0, "failed", Some(&err_str))
                    .map_err(|e| SyncError::DatabaseError(e.to_string()))?;
                return Err(e);
            }
            Err(e) => {
                last_error_reason = format!("subnet_scan ({}): {}", found_ip, e);
                error!("Subnet scan sync also failed: {}", e);
            }
        }
    }

    // All strategies failed — mark plays as failed (they'll be retried on next auto-sync cycle)
    let err_msg = if last_error_reason.is_empty() {
        format!(
            "Could not reach phone at {}:{} (tried primary IP, network memory, mDNS, hotspot gateway, and subnet scan)",
            pairing.phone_ip, pairing.phone_port
        )
    } else {
        format!(
            "Could not reach phone at {}:{} — {}",
            pairing.phone_ip, pairing.phone_port, last_error_reason
        )
    };
    let db = state.db.lock().await;
    if let Err(e) = db.mark_plays_failed(&ids) {
        warn!("Failed to mark plays as failed: {}", e);
    }
    db.record_sync(0, "failed", Some(&err_msg))
        .map_err(|e| SyncError::DatabaseError(e.to_string()))?;

    Err(SyncError::Unreachable(err_msg))
}

async fn confirm_pairing_token(ip: &str, port: u16, auth_token: &str) -> Option<Option<String>> {
    let url = format!("http://{}:{}/api/pair/confirm", ip, port);
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .ok()?;

    let response = client
        .post(url)
        .json(&serde_json::json!({ "auth_token": auth_token }))
        .send()
        .await
        .ok()?;

    if !response.status().is_success() {
        return None;
    }

    let parsed: PairConfirmResponse = response.json().await.ok()?;
    if parsed.ok {
        Some(parsed.device_name)
    } else {
        None
    }
}

/// Check if the phone is reachable at the given address via /api/ping.
pub async fn ping_phone(ip: &str, port: u16) -> bool {
    let url = format!("http://{}:{}/api/ping", ip, port);
    let client = match reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };
    client.get(&url).send().await.map(|r| r.status().is_success()).unwrap_or(false)
}

/// HTTP 200 is not an acknowledgment unless the paired phone confirms the
/// JSON body. Return the rotated LAN token, if any, so subsequent batches work.
fn parse_lan_acknowledgment(body: &str) -> Result<Option<String>, SyncError> {
    let value: serde_json::Value = serde_json::from_str(body)
        .map_err(|e| SyncError::Network(format!("Unreadable LAN acknowledgment: {e}")))?;
    if value.get("ok").and_then(serde_json::Value::as_bool) != Some(true) {
        return Err(SyncError::Network(
            "Phone did not confirm the LAN play batch".to_string()
        ));
    }
    match value.get("next_token") {
        None | Some(serde_json::Value::Null) => Ok(None),
        Some(serde_json::Value::String(token)) if (16..=128).contains(&token.len()) =>
            Ok(Some(token.clone())),
        _ => Err(SyncError::Network("Phone returned an invalid LAN token".into())),
    }
}

/// Send payload with exponential backoff retries and HMAC signature.
async fn send_with_retry(url: &str, payload: &SyncPayload) -> Result<Option<String>, SyncError> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(REQUEST_TIMEOUT_SECS))
        .build()
        .map_err(|e| SyncError::Network(e.to_string()))?;

    // Compute HMAC signature for payload integrity verification
    let payload_json = serde_json::to_string(payload)
        .map_err(|e| SyncError::Network(e.to_string()))?;
    let signature = compute_hmac(&payload.auth_token, &payload_json);

    let mut delay_ms = INITIAL_RETRY_DELAY_MS;
    let mut last_error = SyncError::Network("no attempts made".into());

    for attempt in 1..=MAX_RETRIES {
        match client
            .post(url)
            .header("X-Tempo-Signature", &signature)
            .header("Content-Type", "application/json")
            .body(payload_json.clone())
            .send()
            .await
        {
            Ok(response) => {
                let status = response.status();
                let body = response.text().await.unwrap_or_default();
                if status.is_success() {
                    match parse_lan_acknowledgment(&body) {
                        Ok(rotated_token) => return Ok(rotated_token),
                        Err(error) => last_error = error,
                    }
                } else {
                    if body.contains("battery_critical") {
                        return Err(SyncError::BatteryCritical);
                    }
                    if status.is_client_error() {
                        return Err(SyncError::Rejected(format!("HTTP {} - {}", status, body)));
                    }
                    last_error = SyncError::Network(format!("HTTP {} - {}", status, body));
                }
            }
            Err(e) => {
                last_error = SyncError::Unreachable(e.to_string());
            }
        }

        if attempt < MAX_RETRIES {
            info!(
                "Attempt {}/{} to {} failed, retrying in {}ms...",
                attempt, MAX_RETRIES, url, delay_ms
            );
            tokio::time::sleep(std::time::Duration::from_millis(delay_ms)).await;
            delay_ms *= 2; // exponential backoff
        }
    }

    Err(last_error)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn simultaneous_lan_batch_runs_share_one_lock() {
        let first = LAN_SYNC_LOCK.lock().await;
        assert!(LAN_SYNC_LOCK.try_lock().is_err());
        drop(first);
        assert!(LAN_SYNC_LOCK.try_lock().is_ok());
    }


    #[test]
    fn lan_ack_requires_confirmed_json_before_deleting_local_plays() {
        for invalid in [
            "", "not json", "{}", "{\"ok\":false}", "{\"ok\":\"true\"}",
            "{\"accepted\":10}", "{\"ok\":true,\"next_token\":\"short\"}",
        ] {
            assert!(parse_lan_acknowledgment(invalid).is_err(), "{invalid}");
        }
        assert_eq!(
            parse_lan_acknowledgment("{\"ok\":true,\"accepted\":1,\"duplicates\":0}").unwrap(),
            None,
        );
        let token = "a".repeat(48);
        let ack = format!("{{\"ok\":true,\"next_token\":\"{token}\"}}");
        assert_eq!(parse_lan_acknowledgment(&ack).unwrap(), Some(token));
    }
}
