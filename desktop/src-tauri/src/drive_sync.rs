use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use chrono::{DateTime, SecondsFormat, Utc};
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use once_cell::sync::Lazy;
use rand::RngCore;
use reqwest::{header, Client};
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
const REVOKE_ENDPOINT: &str = "https://oauth2.googleapis.com/revoke";
const USERINFO_ENDPOINT: &str = "https://openidconnect.googleapis.com/v1/userinfo";
const DRIVE_API: &str = "https://www.googleapis.com/drive/v3";
const DRIVE_UPLOAD_API: &str = "https://www.googleapis.com/upload/drive/v3";
const DRIVE_SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const KEYRING_SERVICE: &str = "me.avinas.tempo.desktop.google-drive";

const FILE_PREFIX: &str = "tempo_history_v1_";
const DISABLE_MARKER_NAME: &str = "tempo_history_control_v1.json";
const APP_PROPERTY_GENERATION: &str = "tempo_generation";
const SCHEMA_VERSION: i32 = 1;
const BATCH_SIZE: usize = 50;
const MAX_LOCAL_SCAN: usize = 5000;
const MAX_BATCH_BYTES: usize = 10 * 1024 * 1024;
const DOWNLOAD_OVERLAP_MS: i64 = 24 * 60 * 60 * 1000;
const TEMPORAL_DEDUP_MS: i64 = 60_000;

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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct WireEvent {
    event_id: String,
    title: String,
    artist: String,
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
    session_id: Option<String>,
    site: Option<String>,
    content_type: String,
    volume_level: Option<i64>,
    total_pause_duration_ms: i64,
    position_updates_count: i64,
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
            drive_uploaded_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_drive_origin_event ON drive_event_state(origin_event_id);
        INSERT OR IGNORE INTO drive_sync_state (id) VALUES (1);
        ",
    )
    .map_err(|e| e.to_string())?;

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

    conn.execute(
        "DELETE FROM drive_event_state WHERE scrobble_id NOT IN (SELECT id FROM scrobbles)",
        [],
    )
    .map_err(|e| e.to_string())?;
    Ok(conn)
}

fn load_state(conn: &Connection) -> Result<StoredDriveState, String> {
    conn.query_row(
        "SELECT enabled, device_id, access_token, refresh_token, token_expires_at,
                account_email, download_cursor, accepted_disable_version,
                last_sync_time, last_error, last_uploaded, last_imported
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

async fn secure_refresh_token_get(device_id: &str) -> Result<Option<String>, String> {
    let username = device_id.to_string();
    tokio::task::spawn_blocking(move || {
        let entry = keyring::Entry::new(KEYRING_SERVICE, &username)
            .map_err(|e| format!("Could not open the OS credential store: {e}"))?;
        match entry.get_password() {
            Ok(value) if !value.is_empty() => Ok(Some(value)),
            Ok(_) | Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(format!(
                "Could not read the Google Drive credential from the OS credential store: {e}"
            )),
        }
    })
    .await
    .map_err(|e| format!("OS credential store task failed: {e}"))?
}

async fn secure_refresh_token_set(device_id: &str, refresh_token: &str) -> Result<(), String> {
    if refresh_token.is_empty() {
        return Err("Refusing to store an empty Google refresh token".to_string());
    }
    let username = device_id.to_string();
    let secret = refresh_token.to_string();
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
    app_data_dir: &Path,
    state: &StoredDriveState,
) -> Result<String, String> {
    // One-time migration for builds that previously stored the long-lived token
    // in SQLite. Do not erase the legacy value until the native store accepted it.
    if let Some(legacy) = state
        .refresh_token
        .clone()
        .filter(|value| !value.is_empty())
    {
        secure_refresh_token_set(&state.device_id, &legacy).await?;
        let conn = open_sync_db(app_data_dir)?;
        conn.execute(
            "UPDATE drive_sync_state SET refresh_token = NULL WHERE id = 1",
            [],
        )
        .map_err(|e| e.to_string())?;
        return Ok(legacy);
    }

    secure_refresh_token_get(&state.device_id)
        .await?
        .ok_or_else(|| "Google Drive needs you to reconnect".to_string())
}

async fn access_token(app_data_dir: &Path) -> Result<String, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
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
    if let Some(rotated) = token
        .refresh_token
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        secure_refresh_token_set(&state.device_id, rotated).await?;
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

    let (mut stream, _) = tokio::time::timeout(Duration::from_secs(180), listener.accept())
        .await
        .map_err(|_| "Google sign-in timed out".to_string())?
        .map_err(|e| format!("OAuth callback failed: {e}"))?;

    let mut buffer = vec![0u8; 16 * 1024];
    let count = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut buffer))
        .await
        .map_err(|_| "OAuth callback timed out".to_string())?
        .map_err(|e| e.to_string())?;
    let request = String::from_utf8_lossy(&buffer[..count]);
    let first_line = request.lines().next().unwrap_or_default();
    let target = first_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| "Invalid OAuth callback".to_string())?;
    let callback =
        Url::parse(&format!("http://127.0.0.1:{port}{target}")).map_err(|e| e.to_string())?;

    let mut code: Option<String> = None;
    let mut returned_state: Option<String> = None;
    let mut oauth_error: Option<String> = None;
    for (key, value) in callback.query_pairs() {
        match key.as_ref() {
            "code" => code = Some(value.into_owned()),
            "state" => returned_state = Some(value.into_owned()),
            "error" => oauth_error = Some(value.into_owned()),
            _ => {}
        }
    }

    let ok = oauth_error.is_none()
        && returned_state.as_deref() == Some(state_nonce.as_str())
        && code.is_some();
    let body = if ok {
        "Tempo is connected to Google Drive. You can close this tab and return to Tempo Desktop."
    } else {
        "Tempo could not complete Google Drive sign-in. Return to Tempo Desktop and try again."
    };
    let response = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.as_bytes().len(),
        body
    );
    let _ = stream.write_all(response.as_bytes()).await;

    if let Some(err) = oauth_error {
        return Err(format!("Google sign-in failed: {err}"));
    }
    if returned_state.as_deref() != Some(state_nonce.as_str()) {
        return Err("Google sign-in returned an invalid OAuth state".to_string());
    }
    let code =
        code.ok_or_else(|| "Google sign-in did not return an authorization code".to_string())?;

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
    let email = user_info
        .json::<GoogleUserInfo>()
        .await
        .map_err(|e| format!("Could not parse Google account info: {e}"))?
        .email
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            "Google account email is unavailable; refusing to reuse Drive sync state".to_string()
        })?;

    let conn = open_sync_db(app_data_dir)?;
    let existing = load_state(&conn)?;
    let previous_account_matches = existing
        .account_email
        .as_deref()
        .is_some_and(|old| old.eq_ignore_ascii_case(&email));
    let account_changed = existing
        .account_email
        .as_deref()
        .is_some_and(|old| !old.eq_ignore_ascii_case(&email));
    if account_changed {
        conn.execute(
            "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
            [],
        )
        .map_err(|e| e.to_string())?;
        conn.execute(
            "UPDATE drive_sync_state SET download_cursor = 0, accepted_disable_version = 0,
             last_sync_time = NULL, last_uploaded = 0, last_imported = 0 WHERE id = 1",
            [],
        )
        .map_err(|e| e.to_string())?;
    }
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
            secure_refresh_token_get(&device_id).await?.ok_or_else(|| {
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

    secure_refresh_token_set(&device_id, &refresh_token).await?;
    let expires_at = now_ms() + token.expires_in.max(60) * 1000;
    let conn = open_sync_db(app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET access_token = ?1, refresh_token = NULL, token_expires_at = ?2, account_email = ?3, last_error = NULL WHERE id = 1",
        params![token.access_token, expires_at, email],
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

async fn drive_json<T: for<'de> Deserialize<'de>>(
    access_token: &str,
    url: Url,
) -> Result<T, String> {
    let response = http_client()?
        .get(url)
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|e| format!("Google Drive request failed: {e}"))?;
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
    list_files(access_token, &query, Some("createdTime asc")).await
}

async fn find_exact_file(
    access_token: &str,
    name: &str,
) -> Result<Option<DriveFileRecord>, String> {
    let escaped = name.replace('\\', "\\\\").replace('\'', "\\'");
    let query = format!("name = '{escaped}' and trashed = false");
    Ok(list_files(access_token, &query, None)
        .await?
        .into_iter()
        .next())
}

async fn get_disable_marker_version(access_token: &str) -> Result<i64, String> {
    let marker = find_exact_file(access_token, DISABLE_MARKER_NAME).await?;
    match marker {
        None => Ok(0),
        Some(file) => require_server_time(file.modified_time.as_deref(), "deletion marker"),
    }
}

fn batch_generation(file: &DriveFileRecord) -> i64 {
    file.app_properties
        .get(APP_PROPERTY_GENERATION)
        .and_then(|value| value.parse::<i64>().ok())
        .filter(|value| *value >= 0)
        .unwrap_or(0)
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
    let response = http_client()?
        .get(url)
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|e| e.to_string())?;
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
    Ok(bytes)
}

fn encode_batch(batch: &WireBatch) -> Result<Vec<u8>, String> {
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
    if batch.schema_version != SCHEMA_VERSION
        || batch.batch_id.is_empty()
        || batch.source_device_id.is_empty()
    {
        return Err("Unsupported or malformed Tempo Drive history batch".to_string());
    }
    if batch.events.len() > 1000 {
        return Err("Tempo Drive history batch contains too many events".to_string());
    }
    Ok(batch)
}

fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn event_id(device_id: &str, play: &LocalPlay) -> String {
    sha256_hex(&format!(
        "tempo-history-v1|{}|{}|{}|{}|{}",
        device_id,
        play.id,
        play.timestamp_utc,
        play.title.trim().to_lowercase(),
        play.artist.trim().to_lowercase()
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
    if play.volume_level <= 1.0 {
        if play.volume_level <= 0.01 {
            Some(0)
        } else {
            Some(((play.volume_level * 100.0).round() as i64).max(1))
        }
    } else {
        Some((play.volume_level.round() as i64).max(1))
    }
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
    let mut stmt = conn
        .prepare(
            "SELECT s.id, s.title, s.artist, s.album, s.duration_ms, s.timestamp_utc,
                    s.source_app, s.listened_ms, s.skipped, s.replay_count, s.is_muted,
                    s.completion_percentage, s.pause_count, s.seek_count, s.session_id,
                    s.site, s.content_type, s.volume_level
             FROM scrobbles s
             LEFT JOIN drive_event_state d ON d.scrobble_id = s.id
             WHERE COALESCE(d.drive_imported, 0) = 0 AND d.drive_uploaded_at IS NULL
             ORDER BY s.timestamp_utc ASC, s.id ASC LIMIT ?1",
        )
        .map_err(|e| e.to_string())?;
    let rows = stmt
        .query_map([MAX_LOCAL_SCAN as i64], |row| {
            Ok(LocalPlay {
                id: row.get(0)?,
                title: row.get(1)?,
                artist: row.get(2)?,
                album: row.get(3)?,
                duration_ms: row.get(4)?,
                timestamp_utc: row.get(5)?,
                source_app: row.get(6)?,
                listened_ms: row.get(7)?,
                skipped: row.get::<_, i64>(8)? != 0,
                replay_count: row.get(9)?,
                is_muted: row.get::<_, i64>(10)? != 0,
                completion_percentage: row.get(11)?,
                pause_count: row.get(12)?,
                seek_count: row.get(13)?,
                session_id: row.get(14)?,
                site: row.get(15)?,
                content_type: row.get(16)?,
                volume_level: row.get(17)?,
            })
        })
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

async fn upload_batch(
    access_token: &str,
    file_name: &str,
    device_id: &str,
    generation: i64,
    compressed: &[u8],
) -> Result<(), String> {
    if find_exact_file(access_token, file_name).await?.is_some() {
        return Ok(());
    }

    let boundary = format!("tempo_{}", Uuid::new_v4().simple());
    let metadata = serde_json::json!({
        "name": file_name,
        "parents": ["appDataFolder"],
        "appProperties": {
            "tempo_kind": "history_batch",
            "tempo_schema": SCHEMA_VERSION.to_string(),
            "source_device_id": device_id,
            "source_platform": "desktop",
            APP_PROPERTY_GENERATION: generation.to_string()
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
        .post(format!("{DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,createdTime,appProperties"))
        .bearer_auth(access_token)
        .header(header::CONTENT_TYPE, format!("multipart/related; boundary={boundary}"))
        .body(body)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("Drive upload failed (HTTP {})", response.status()));
    }
    Ok(())
}

async fn upload_local_history(
    app_data_dir: &Path,
    access_token: &str,
    device_id: &str,
) -> Result<usize, String> {
    let conn = open_sync_db(app_data_dir)?;
    let generation = load_state(&conn)?.accepted_disable_version.max(0);
    let pending = pending_local_plays(&conn)?;
    let mut uploaded = 0usize;
    for chunk in pending.chunks(BATCH_SIZE) {
        let events: Vec<WireEvent> = chunk
            .iter()
            .map(|play| local_to_wire(device_id, play))
            .collect();
        if events.is_empty() {
            continue;
        }
        let id = batch_id(&events);
        let batch = WireBatch {
            schema_version: SCHEMA_VERSION,
            batch_id: id.clone(),
            source_device_id: device_id.to_string(),
            // The random Tempo device UUID already identifies the producer. Do not upload
            // the operating-system hostname, which may contain a person's or company's name.
            source_device_name: "Tempo Desktop".to_string(),
            source_platform: "desktop".to_string(),
            created_at_utc: now_ms(),
            events,
        };
        let compressed = encode_batch(&batch)?;
        let file_name = batch_file_name(generation, device_id, &id);
        upload_batch(
            access_token,
            &file_name,
            device_id,
            generation,
            &compressed,
        )
        .await?;

        let uploaded_at = now_ms();
        let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
        for play in chunk {
            let origin = event_id(device_id, play);
            tx.execute(
                "INSERT INTO drive_event_state
                 (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
                 VALUES (?1, ?2, ?3, 0, ?4)
                 ON CONFLICT(scrobble_id) DO UPDATE SET origin_event_id = excluded.origin_event_id,
                    origin_device_id = excluded.origin_device_id, drive_imported = 0,
                    drive_uploaded_at = excluded.drive_uploaded_at",
                params![play.id, origin, device_id, uploaded_at],
            )
            .map_err(|e| e.to_string())?;
        }
        tx.commit().map_err(|e| e.to_string())?;
        uploaded += chunk.len();
    }
    Ok(uploaded)
}

fn valid_event(event: &WireEvent) -> bool {
    !event.event_id.is_empty()
        && !event.title.trim().is_empty()
        && event.title.len() <= 1000
        && !event.artist.trim().is_empty()
        && event.artist.len() <= 1000
        && event.timestamp_utc > 0
}

fn insert_remote_event(
    conn: &Connection,
    source_device_id: &str,
    event: &WireEvent,
) -> Result<bool, String> {
    let existing_origin: Option<i64> = conn
        .query_row(
            "SELECT scrobble_id FROM drive_event_state WHERE origin_event_id = ?1 LIMIT 1",
            [&event.event_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?;
    if existing_origin.is_some() {
        return Ok(false);
    }

    // The ±60s fallback exists to reconcile two different capture origins that
    // observed the same physical playback. Distinct event IDs from the same
    // originating device are legitimate rapid replays and must not be merged.
    let existing_temporal: Option<i64> = conn
        .query_row(
            "SELECT s.id FROM scrobbles s
             LEFT JOIN drive_event_state d ON d.scrobble_id = s.id
             WHERE lower(s.title) = lower(?1) AND lower(s.artist) = lower(?2)
               AND s.timestamp_utc BETWEEN ?3 AND ?4
               AND (d.origin_device_id IS NULL OR d.origin_device_id <> ?6)
             ORDER BY abs(s.timestamp_utc - ?5) ASC LIMIT 1",
            params![
                event.title,
                event.artist,
                event.timestamp_utc - TEMPORAL_DEDUP_MS,
                event.timestamp_utc + TEMPORAL_DEDUP_MS,
                event.timestamp_utc,
                source_device_id
            ],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| e.to_string())?;

    if let Some(id) = existing_temporal {
        conn.execute(
            "INSERT INTO drive_event_state
             (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
             VALUES (?1, ?2, ?3, 1, ?4)
             ON CONFLICT(scrobble_id) DO UPDATE SET origin_event_id = excluded.origin_event_id,
                origin_device_id = excluded.origin_device_id, drive_imported = 1,
                drive_uploaded_at = excluded.drive_uploaded_at",
            params![id, event.event_id, source_device_id, now_ms()],
        )
        .map_err(|e| e.to_string())?;
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
         (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
         VALUES (?1, ?2, ?3, 1, ?4)",
        params![scrobble_id, event.event_id, source_device_id, now_ms()],
    )
    .map_err(|e| e.to_string())?;
    Ok(true)
}

async fn download_remote_history(
    app_data_dir: &Path,
    access_token: &str,
    device_id: &str,
) -> Result<(usize, usize), String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    let accepted_generation = state.accepted_disable_version.max(0);
    let after =
        (state.download_cursor > 0).then_some((state.download_cursor - DOWNLOAD_OVERLAP_MS).max(0));
    let files = list_batches(access_token, after).await?;
    let mut imported = 0usize;
    let mut duplicates = 0usize;
    let mut max_created = state.download_cursor;

    for file in files {
        let created = parse_time_ms(file.created_time.as_deref());
        if batch_generation(&file) < accepted_generation {
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
            Err(err) if err.contains("too large") => {
                // Permanently malformed/hostile payload. Consume this one file so
                // it cannot block every later batch forever.
                log::warn!(
                    "Skipping oversized Drive history batch {}: {}",
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
        if batch.source_device_id == device_id {
            max_created = max_created.max(created);
            continue;
        }
        for event in &batch.events {
            if !valid_event(event) {
                continue;
            }
            if insert_remote_event(&conn, &batch.source_device_id, event)? {
                imported += 1;
            } else {
                duplicates += 1;
            }
        }
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
    let response = http_client()?
        .delete(format!("{DRIVE_API}/files/{id}"))
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if response.status().is_success() || response.status().as_u16() == 404 {
        Ok(())
    } else {
        Err(format!("Drive delete failed (HTTP {})", response.status()))
    }
}

async fn delete_batches_before_generation(
    access_token: &str,
    generation: i64,
) -> Result<usize, String> {
    let files = list_batches(access_token, None).await?;
    let mut deleted = 0usize;
    for file in files {
        if batch_generation(&file) >= generation {
            continue;
        }
        delete_file(access_token, &file.id).await?;
        deleted += 1;
    }
    Ok(deleted)
}

async fn bump_disable_marker(access_token: &str) -> Result<i64, String> {
    let marker_body = serde_json::json!({
        "schema_version": 1,
        "disabled": true,
        "updated_at_utc": now_ms()
    })
    .to_string();

    if let Some(existing) = find_exact_file(access_token, DISABLE_MARKER_NAME).await? {
        let response = http_client()?
            .patch(format!(
                "{DRIVE_UPLOAD_API}/files/{}?uploadType=media&fields=id,name,modifiedTime",
                existing.id
            ))
            .bearer_auth(access_token)
            .header(header::CONTENT_TYPE, "application/json")
            .body(marker_body)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!(
                "Could not update Drive disable marker (HTTP {})",
                response.status()
            ));
        }
        let updated: DriveFileRecord = response.json().await.map_err(|e| e.to_string())?;
        return require_server_time(updated.modified_time.as_deref(), "deletion marker");
    }

    let boundary = format!("tempo_{}", Uuid::new_v4().simple());
    let metadata = serde_json::json!({
        "name": DISABLE_MARKER_NAME,
        "parents": ["appDataFolder"],
        "appProperties": {
            "tempo_kind": "history_control",
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
    let created: DriveFileRecord = response.json().await.map_err(|e| e.to_string())?;
    require_server_time(created.modified_time.as_deref(), "deletion marker")
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

    // Delete only history from generations older than the newly observed marker.
    // Another client may already have explicitly re-enabled and seeded generation
    // N; this stale Desktop instance must never erase that fresh generation.
    delete_batches_before_generation(access_token, marker_version).await?;
    conn.execute(
        "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
        [],
    )
    .map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0, accepted_disable_version = ?1,
         download_cursor = 0, last_uploaded = 0, last_imported = 0,
         last_error = ?2 WHERE id = 1",
        params![
            marker_version,
            "Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history."
        ],
    )
    .map_err(|e| e.to_string())?;
    Ok(true)
}

async fn run_sync(app_data_dir: &Path) -> Result<DriveSyncResult, String> {
    let _guard = SYNC_LOCK.lock().await;
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

    let uploaded = upload_local_history(app_data_dir, &token, &device_id).await?;
    let (imported, duplicates) = download_remote_history(app_data_dir, &token, &device_id).await?;

    let conn = open_sync_db(app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET last_sync_time = ?1, last_error = NULL,
         last_uploaded = ?2, last_imported = ?3 WHERE id = 1",
        params![now_ms(), uploaded as i64, imported as i64],
    )
    .map_err(|e| e.to_string())?;

    Ok(DriveSyncResult {
        uploaded,
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
        secure_refresh_token_get(&state.device_id)
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
    interactive_oauth(&app, &state.app_data_dir).await?;
    let token = access_token(&state.app_data_dir).await?;
    let marker_version = get_disable_marker_version(&token).await?;

    let conn = open_sync_db(&state.app_data_dir)?;
    let previous = load_state(&conn)?;
    if marker_version > previous.accepted_disable_version {
        conn.execute(
            "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
            [],
        )
        .map_err(|e| e.to_string())?;
    }
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, accepted_disable_version = ?1,
         download_cursor = CASE WHEN ?1 > accepted_disable_version THEN 0 ELSE download_cursor END,
         last_error = NULL WHERE id = 1",
        [marker_version],
    )
    .map_err(|e| e.to_string())?;
    drop(conn);

    if let Err(err) = run_sync(&state.app_data_dir).await {
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
    let state_before = load_state(&conn)?;
    drop(conn);

    // The user explicitly asked to disconnect. A broken/unavailable native
    // credential store must not leave background Drive sync enabled locally.
    // Read/revoke is best-effort; deletion errors are reported only after the
    // local database has been made safe and inert.
    let secure_refresh = secure_refresh_token_get(&state_before.device_id)
        .await
        .ok()
        .flatten();
    let credential_delete_error = secure_refresh_token_delete(&state_before.device_id)
        .await
        .err();

    let conn = open_sync_db(&state.app_data_dir)?;
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0, access_token = NULL, refresh_token = NULL,
         token_expires_at = 0, last_error = NULL WHERE id = 1",
        [],
    )
    .map_err(|e| e.to_string())?;
    drop(conn);

    let token_to_revoke = secure_refresh
        .or(state_before.refresh_token)
        .or(state_before.access_token);
    if let Some(token) = token_to_revoke {
        let revoke_body = {
            let mut serializer = url::form_urlencoded::Serializer::new(String::new());
            serializer.append_pair("token", &token);
            serializer.finish()
        };
        let _ = http_client()?
            .post(REVOKE_ENDPOINT)
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(revoke_body)
            .send()
            .await;
    }

    if let Some(err) = credential_delete_error {
        return Err(format!(
            "Google Drive was disabled locally, but Tempo could not remove the OS credential: {err}"
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

#[tauri::command]
pub async fn drive_delete_cloud_history(state: State<'_, AppState>) -> Result<usize, String> {
    let _guard = SYNC_LOCK.lock().await;
    let token = access_token(&state.app_data_dir).await?;
    // Publish the shared generation marker first, then remove only older batches.
    // A client explicitly re-enabled after the marker update may safely publish
    // generation N while stale clients are still waking up and honoring deletion.
    let marker_version = bump_disable_marker(&token).await?;
    let deleted = delete_batches_before_generation(&token, marker_version).await?;

    let conn = open_sync_db(&state.app_data_dir)?;
    conn.execute(
        "UPDATE drive_event_state SET drive_uploaded_at = NULL WHERE drive_imported = 0",
        [],
    )
    .map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0, accepted_disable_version = ?1,
         download_cursor = 0, last_uploaded = 0, last_imported = 0,
         last_error = NULL WHERE id = 1",
        [marker_version],
    )
    .map_err(|e| e.to_string())?;
    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_event_and_batch_ids_match_protocol_shape() {
        let play = LocalPlay {
            id: 42,
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
        assert_eq!(batch_generation(&legacy), 0);
    }

    #[test]
    fn protocol_volume_uses_android_percent_scale() {
        let mut play = LocalPlay {
            id: 1,
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
        play.is_muted = true;
        assert_eq!(protocol_volume(&play), Some(0));
    }
}