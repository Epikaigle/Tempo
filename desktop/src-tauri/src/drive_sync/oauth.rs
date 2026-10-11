use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use rand::RngCore;
use reqwest::header;
use rusqlite::params;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::Path;
use std::time::Duration;
use tauri::AppHandle;
use tauri_plugin_shell::ShellExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use url::Url;
use uuid::Uuid;

use super::db::{load_state, open_sync_db, prepare_oauth_credentials, restore_account_progress};
use super::models::{
    http_client, now_ms, oauth_client_id, GoogleUserInfo, OAuthTokenResponse, StoredDriveState,
    AUTH_ENDPOINT, DRIVE_SCOPE, KEYRING_SERVICE, TOKEN_ENDPOINT, USERINFO_ENDPOINT,
};

#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct SubjectBoundRefreshToken {
    pub(crate) sub: String,
    pub(crate) refresh_token: String,
}

pub(crate) fn decode_subject_bound_token(
    value: &str,
    expected_subject: &str,
) -> Result<String, String> {
    let stored: SubjectBoundRefreshToken = serde_json::from_str(value).map_err(|_| {
        "Google credential format is outdated. Reconnect Google securely.".to_string()
    })?;
    if stored.sub != expected_subject || stored.refresh_token.is_empty() {
        return Err(
            "Google credential belongs to a different account. Reconnect securely.".to_string(),
        );
    }
    Ok(stored.refresh_token)
}

pub(crate) async fn secure_refresh_token_get(
    device_id: &str,
    expected_subject: &str,
) -> Result<Option<String>, String> {
    let username = device_id.to_string();
    let expected_subject = expected_subject.to_string();
    tokio::task::spawn_blocking(move || {
        let entry = keyring::Entry::new(KEYRING_SERVICE, &username)
            .map_err(|e| format!("Could not open the OS credential store: {e}"))?;
        match entry.get_password() {
            Ok(value) if !value.is_empty() => {
                Ok(Some(decode_subject_bound_token(&value, &expected_subject)?))
            }
            Ok(_) | Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(format!(
                "Could not read the Google Drive credential from the OS credential store: {e}"
            )),
        }
    })
    .await
    .map_err(|e| format!("OS credential store task failed: {e}"))?
}

pub(crate) async fn secure_refresh_token_set(
    device_id: &str,
    subject: &str,
    refresh_token: &str,
) -> Result<(), String> {
    if refresh_token.is_empty() || subject.trim().is_empty() {
        return Err(
            "Refusing to store a Google credential without a verified subject and token"
                .to_string(),
        );
    }
    let username = device_id.to_string();
    let secret = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: subject.to_string(),
        refresh_token: refresh_token.to_string(),
    })
    .map_err(|e| e.to_string())?;
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

pub(crate) async fn secure_refresh_token_delete(device_id: &str) -> Result<(), String> {
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

pub(crate) async fn refresh_token_for_state(
    _app_data_dir: &Path,
    state: &StoredDriveState,
) -> Result<String, String> {
    let subject = state
        .account_subject
        .as_deref()
        .filter(|subject| !subject.trim().is_empty())
        .ok_or("Google account identity is unavailable. Connect Google again.")?;
    // Old plaintext SQLite/keyring credentials carry no cryptographic account
    // binding. Never adopt one into a new identity without re-authenticating.
    if state
        .refresh_token
        .as_deref()
        .is_some_and(|value| !value.is_empty())
    {
        return Err("Legacy Google credential is unverified. Reconnect Google securely.".into());
    }
    secure_refresh_token_get(&state.device_id, subject)
        .await?
        .ok_or_else(|| "Google Drive needs you to reconnect".to_string())
}

pub(crate) async fn access_token(app_data_dir: &Path) -> Result<String, String> {
    let conn = open_sync_db(app_data_dir)?;
    let state = load_state(&conn)?;
    if state
        .account_subject
        .as_deref()
        .map(str::trim)
        .filter(|subject| !subject.is_empty())
        .is_none()
    {
        return Err("Google account identity is unavailable. Connect Google again.".into());
    }
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
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        let invalid_grant = ["invalid_grant", "invalid_client", "unauthorized_client"]
            .iter()
            .any(|reason| body.contains(reason));
        return Err(if invalid_grant {
            format!("Google authorization failed (invalid_grant or revoked credentials; HTTP {status}). Reconnect Google.")
        } else {
            format!("Google token refresh failed (HTTP {status})")
        });
    }
    let token: OAuthTokenResponse = response.json().await.map_err(|e| e.to_string())?;
    // A successfully refreshed OAuth token must still belong to the expected
    // Google subject. A mismatched/corrupt native credential cannot silently
    // move history into a different Drive account.
    let user_info = http_client()?
        .get(USERINFO_ENDPOINT)
        .bearer_auth(&token.access_token)
        .send()
        .await
        .map_err(|e| format!("Could not verify refreshed Google account: {e}"))?;
    if !user_info.status().is_success() {
        return Err(format!(
            "Could not verify refreshed Google account (HTTP {})",
            user_info.status()
        ));
    }
    let info: GoogleUserInfo = user_info.json().await.map_err(|e| e.to_string())?;
    if info.sub.as_deref() != state.account_subject.as_deref() {
        return Err(
            "Google refreshed a token for a different account. Reconnect Google securely.".into(),
        );
    }
    if let Some(rotated) = token
        .refresh_token
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        secure_refresh_token_set(
            &state.device_id,
            state.account_subject.as_deref().unwrap_or_default(),
            rotated,
        )
        .await?;
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

pub(crate) fn random_pkce_verifier() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub(crate) fn pkce_challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

pub(crate) fn parse_oauth_callback(
    line: &str,
    expected_state: &str,
) -> Option<Result<String, String>> {
    let mut parts = line.split_whitespace();
    if parts.next()? != "GET" {
        return None;
    }
    let target = parts.next()?;
    if !matches!(parts.next()?, "HTTP/1.0" | "HTTP/1.1")
        || parts.next().is_some()
        || !target.starts_with('/')
        || target.starts_with("//")
    {
        return None;
    }
    let callback = Url::parse(&format!("http://127.0.0.1{target}")).ok()?;
    if callback.path() != "/oauth2/callback" || callback.fragment().is_some() {
        return None;
    }
    let mut values = HashMap::new();
    for (key, value) in callback.query_pairs() {
        if matches!(key.as_ref(), "state" | "code" | "error")
            && values
                .insert(key.into_owned(), value.into_owned())
                .is_some()
        {
            return None;
        }
    }
    if values.get("state").map(String::as_str) != Some(expected_state) {
        return None;
    }
    if let Some(error) = values.get("error") {
        return Some(Err(format!("Google sign-in failed: {error}")));
    }
    Some(
        values
            .remove("code")
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| "Google sign-in did not return an authorization code".to_string()),
    )
}

pub(crate) async fn read_oauth_request(
    stream: &mut tokio::net::TcpStream,
) -> Result<String, String> {
    let mut request = Vec::new();
    let mut buffer = [0u8; 1024];
    loop {
        let count = stream.read(&mut buffer).await.map_err(|e| e.to_string())?;
        if count == 0 {
            return Err("Incomplete OAuth callback".into());
        }
        request.extend_from_slice(&buffer[..count]);
        if request.len() > 16 * 1024 {
            return Err("OAuth callback is too large".into());
        }
        if request.windows(4).any(|bytes| bytes == b"\r\n\r\n") {
            return String::from_utf8(request)
                .map_err(|_| "Invalid OAuth callback encoding".into());
        }
    }
}

pub(crate) async fn wait_for_oauth_callback(
    listener: &tokio::net::TcpListener,
    expected_state: &str,
    deadline: Duration,
) -> Result<String, String> {
    tokio::time::timeout(deadline, async {
        loop {
            let (mut stream, _) = listener.accept().await
                .map_err(|e| format!("OAuth callback failed: {e}"))?;
            let callback = match tokio::time::timeout(Duration::from_secs(10), read_oauth_request(&mut stream)).await {
                Ok(Ok(request)) => parse_oauth_callback(request.lines().next().unwrap_or_default(), expected_state),
                _ => None,
            };
            let (status, body) = match &callback {
                Some(Ok(_)) => ("200 OK", "Google authorization received. Return to Tempo Desktop to finish connecting."),
                Some(Err(_)) => ("200 OK", "Google sign-in was not completed. Return to Tempo Desktop and try again."),
                None => ("400 Bad Request", "This is not a valid Tempo sign-in callback. Continue in the Google sign-in tab."),
            };
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
            if let Some(result) = callback { return result; }
            // An unrelated request, probe or wrong nonce must not consume the
            // valid browser callback. The shared deadline never restarts.
        }
    }).await.map_err(|_| "Google sign-in timed out".to_string())?
}

pub(crate) async fn interactive_oauth(app: &AppHandle, app_data_dir: &Path) -> Result<(), String> {
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

    #[allow(deprecated)]
    app.shell()
        .open(auth_url.to_string(), None)
        .map_err(|e| format!("Could not open Google sign-in in your browser: {e}"))?;

    let code = wait_for_oauth_callback(&listener, &state_nonce, Duration::from_secs(180)).await?;

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
    let info = user_info
        .json::<GoogleUserInfo>()
        .await
        .map_err(|e| format!("Could not parse Google account info: {e}"))?;
    let email = info
        .email
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .ok_or("Google account email is unavailable; refusing Drive sync state reuse")?;
    // OIDC subject, not the editable email, identifies a Google account.
    let subject = info
        .sub
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .ok_or("Google account subject is unavailable; reconnect securely")?;

    let conn = open_sync_db(app_data_dir)?;
    let existing = load_state(&conn)?;
    let previous_account_matches =
        existing.last_verified_account_subject.as_deref() == Some(subject.as_str());
    prepare_oauth_credentials(&conn, !previous_account_matches)?;
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
            secure_refresh_token_get(&device_id, &subject)
                .await?
                .ok_or_else(|| {
                    "Google did not issue a refresh token. Disconnect and connect again."
                        .to_string()
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

    secure_refresh_token_set(&device_id, &subject, &refresh_token).await?;
    let expires_at = now_ms() + token.expires_in.max(60) * 1000;
    let conn = open_sync_db(app_data_dir)?;
    let transaction = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    transaction.execute(
        "UPDATE drive_sync_state SET access_token = ?1, refresh_token = NULL, token_expires_at = ?2, account_email = ?3, account_subject = ?4,
          last_verified_account_subject = ?4, last_error = NULL WHERE id = 1",
        params![token.access_token, expires_at, email, subject],
    )
    .map_err(|e| e.to_string())?;
    restore_account_progress(&transaction, &subject)?;
    transaction.commit().map_err(|e| e.to_string())?;
    Ok(())
}
