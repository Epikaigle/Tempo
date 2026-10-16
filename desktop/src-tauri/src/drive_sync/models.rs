use chrono::Utc;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::Duration;

pub const AUTH_ENDPOINT: &str = "https://accounts.google.com/o/oauth2/v2/auth";
pub const TOKEN_ENDPOINT: &str = "https://oauth2.googleapis.com/token";
pub const USERINFO_ENDPOINT: &str = "https://openidconnect.googleapis.com/v1/userinfo";
pub const DRIVE_API: &str = "https://www.googleapis.com/drive/v3";
pub const DRIVE_UPLOAD_API: &str = "https://www.googleapis.com/upload/drive/v3";
pub const DRIVE_SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
pub const KEYRING_SERVICE: &str = "me.avinas.tempo.desktop.google-drive";

pub const FILE_PREFIX: &str = "tempo_history_v1_";
pub const DISABLE_MARKER_NAME: &str = "tempo_history_control_v1.json";
pub const APP_PROPERTY_GENERATION: &str = "tempo_generation";
pub const APP_PROPERTY_SHA256: &str = "tempo_sha256";
pub const MAX_WIRE_INTEGER: i64 = 9_007_199_254_740_991;
pub const SCHEMA_VERSION: i32 = 1;
pub const BATCH_SIZE: usize = 50;
pub const MAX_LOCAL_SCAN: usize = 5000;
pub const MAX_BATCH_BYTES: usize = 10 * 1024 * 1024;
pub const DOWNLOAD_OVERLAP_MS: i64 = 24 * 60 * 60 * 1000;
pub const DRIVE_REQUEST_RETRIES: usize = 3;
pub const LEGACY_UNVERIFIED_ACCOUNT: &str = "legacy-unverified";
// Cross-producer temporal reconciliation is a fallback, not a proof of origin.
// A narrow two-second window reduces false merges of independently replayed
// short tracks; exact origin IDs remain the primary deduplication mechanism.
pub const TEMPORAL_DEDUP_MS: i64 = 2_000;

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
pub(crate) struct StoredDriveState {
    pub enabled: bool,
    pub device_id: String,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub token_expires_at: i64,
    pub account_email: Option<String>,
    pub account_subject: Option<String>,
    pub last_verified_account_subject: Option<String>,
    pub download_cursor: i64,
    pub accepted_disable_version: i64,
    pub last_sync_time: Option<i64>,
    pub last_error: Option<String>,
    pub last_uploaded: i64,
    pub last_imported: i64,
}

#[derive(Debug, Deserialize)]
pub(crate) struct OAuthTokenResponse {
    pub access_token: String,
    #[serde(default)]
    pub refresh_token: Option<String>,
    #[serde(default = "default_expires_in")]
    pub expires_in: i64,
}

pub(crate) fn default_expires_in() -> i64 {
    3600
}

#[derive(Debug, Deserialize)]
pub(crate) struct GoogleUserInfo {
    pub email: Option<String>,
    pub sub: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct WireEvent {
    pub event_id: String,
    pub title: String,
    pub artist: String,
    #[serde(deserialize_with = "deserialize_required_option")]
    pub album: Option<String>,
    pub timestamp_utc: i64,
    pub duration_ms: i64,
    pub listened_ms: i64,
    pub source_app: String,
    pub source: String,
    pub skipped: bool,
    pub replay_count: i64,
    pub completion_percentage: i64,
    pub pause_count: i64,
    pub seek_count: i64,
    #[serde(deserialize_with = "deserialize_required_option")]
    pub session_id: Option<String>,
    #[serde(deserialize_with = "deserialize_required_option")]
    pub site: Option<String>,
    pub content_type: String,
    #[serde(deserialize_with = "deserialize_required_option")]
    pub volume_level: Option<i64>,
    pub total_pause_duration_ms: i64,
    pub position_updates_count: i64,
}

pub(crate) fn deserialize_required_option<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    // Protocol-v1 nullable fields must be present, even when their value is null.
    Option::<T>::deserialize(deserializer)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct WireBatch {
    pub schema_version: i32,
    pub batch_id: String,
    pub source_device_id: String,
    pub source_device_name: String,
    pub source_platform: String,
    pub created_at_utc: i64,
    pub events: Vec<WireEvent>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct DriveFileRecord {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub size: Option<String>,
    #[serde(rename = "createdTime", default)]
    pub created_time: Option<String>,
    #[serde(rename = "modifiedTime", default)]
    pub modified_time: Option<String>,
    #[serde(rename = "appProperties", default)]
    pub app_properties: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct DriveListResponse {
    #[serde(rename = "nextPageToken")]
    pub next_page_token: Option<String>,
    #[serde(default)]
    pub files: Vec<DriveFileRecord>,
}

#[derive(Debug)]
pub(crate) struct LocalPlay {
    pub id: i64,
    /// Preserve the first published canonical identity even if title/artist
    /// metadata is corrected before a cloud-delete retry or account change.
    pub origin_event_id: Option<String>,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: i64,
    pub timestamp_utc: i64,
    pub source_app: String,
    pub listened_ms: i64,
    pub skipped: bool,
    pub replay_count: i64,
    pub is_muted: bool,
    pub completion_percentage: f64,
    pub pause_count: i64,
    pub seek_count: i64,
    pub session_id: String,
    pub site: String,
    pub content_type: String,
    pub volume_level: f64,
}

pub(crate) fn oauth_client_id() -> Option<String> {
    option_env!("TEMPO_GOOGLE_OAUTH_CLIENT_ID_DESKTOP")
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .or_else(|| {
            std::env::var("TEMPO_GOOGLE_OAUTH_CLIENT_ID_DESKTOP")
                .ok()
                .map(|v| v.trim().to_string())
                .filter(|v| !v.is_empty())
        })
}

pub(crate) fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

pub(crate) fn http_client() -> Result<Client, String> {
    Client::builder()
        .user_agent("Tempo-Desktop-DriveSync/1.0")
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())
}

pub(crate) fn sha256_hex(value: &str) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(value.as_bytes()))
}

pub(crate) fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}
