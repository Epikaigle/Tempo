use crate::commands::drive_sync;
use crate::db::models::Settings;
use crate::AppState;
use serde::{Deserialize, Serialize};
use tauri::State;

#[derive(Debug, Serialize)]
pub struct SettingsResponse {
    #[serde(flatten)]
    pub settings: Settings,
    pub drive_sync_enabled: bool,
    pub drive_sync_configured: bool,
    pub drive_sync_connected: bool,
    pub drive_sync_account_email: Option<String>,
    pub drive_sync_last_sync_time: Option<i64>,
    pub drive_sync_last_error: Option<String>,
    pub drive_sync_last_uploaded: i64,
    pub drive_sync_last_imported: i64,
}

#[derive(Debug, Deserialize)]
pub struct SettingsInput {
    pub sync_interval_minutes: i32,
    pub auto_detect_enabled: bool,
    pub polling_interval_seconds: i32,
    pub minimize_to_tray: bool,
    pub start_on_boot: bool,
    pub theme: String,
    #[serde(default = "default_battery_threshold")]
    pub low_battery_threshold: i32,
    /// Transient UI action. It is never persisted in the normal settings table.
    #[serde(default)]
    pub drive_sync_action: Option<String>,
}

fn default_battery_threshold() -> i32 {
    15
}

impl SettingsInput {
    fn core_settings(&self) -> Settings {
        Settings {
            sync_interval_minutes: self.sync_interval_minutes,
            auto_detect_enabled: self.auto_detect_enabled,
            polling_interval_seconds: self.polling_interval_seconds,
            minimize_to_tray: self.minimize_to_tray,
            start_on_boot: self.start_on_boot,
            theme: self.theme.clone(),
            low_battery_threshold: self.low_battery_threshold,
        }
    }
}

#[tauri::command]
pub async fn get_settings(state: State<'_, AppState>) -> Result<SettingsResponse, String> {
    let settings = {
        let db = state.db.lock().await;
        db.get_settings().map_err(|e| e.to_string())?
    };
    let drive = drive_sync::drive_get_status(state).await?;
    Ok(SettingsResponse {
        settings,
        drive_sync_enabled: drive.enabled,
        drive_sync_configured: drive.configured,
        drive_sync_connected: drive.connected,
        drive_sync_account_email: drive.account_email,
        drive_sync_last_sync_time: drive.last_sync_time,
        drive_sync_last_error: drive.last_error,
        drive_sync_last_uploaded: drive.last_uploaded,
        drive_sync_last_imported: drive.last_imported,
    })
}

#[tauri::command]
pub async fn update_settings(
    app_handle: tauri::AppHandle,
    state: State<'_, AppState>,
    settings: SettingsInput,
) -> Result<(), String> {
    // Drive buttons are transient actions, not a hidden "Save settings".
    // Persist ordinary settings only when this call is a normal settings save.
    if matches!(settings.drive_sync_action.as_deref(), None | Some("")) {
        let db = state.db.lock().await;
        db.update_settings(&settings.core_settings())
            .map_err(|e| e.to_string())?;
    }

    match settings.drive_sync_action.as_deref() {
        None | Some("") => {}
        Some("connect") => {
            drive_sync::drive_connect(app_handle, state).await?;
        }
        Some("sync") => {
            drive_sync::drive_sync_now(state).await?;
        }
        Some("disconnect") => {
            drive_sync::drive_disconnect(state).await?;
        }
        Some("delete") => {
            drive_sync::drive_delete_cloud_history(state).await?;
        }
        Some(other) => return Err(format!("Unknown Google Drive sync action: {other}")),
    }

    // The background media + LAN queue loops re-read normal settings on each
    // iteration. Drive sync uses the same interval while enabled.
    Ok(())
}

/// Change the log level at runtime (TRACE, DEBUG, INFO, WARN, ERROR).
#[tauri::command]
pub async fn set_log_level(level: String) -> Result<(), String> {
    let filter = match level.to_uppercase().as_str() {
        "TRACE" => log::LevelFilter::Trace,
        "DEBUG" => log::LevelFilter::Debug,
        "INFO" => log::LevelFilter::Info,
        "WARN" => log::LevelFilter::Warn,
        "ERROR" => log::LevelFilter::Error,
        "OFF" => log::LevelFilter::Off,
        _ => {
            return Err(format!(
                "Invalid log level: {}. Use TRACE/DEBUG/INFO/WARN/ERROR/OFF",
                level
            ))
        }
    };
    log::set_max_level(filter);
    log::info!("Log level changed to {}", level);
    Ok(())
}

/// Check if autostart is enabled via the OS.
#[tauri::command]
pub async fn get_autostart_enabled(app_handle: tauri::AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    app_handle
        .autolaunch()
        .is_enabled()
        .map_err(|e| e.to_string())
}

/// Enable or disable autostart via the OS.
#[tauri::command]
pub async fn set_autostart_enabled(
    app_handle: tauri::AppHandle,
    enabled: bool,
) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let autostart = app_handle.autolaunch();
    if enabled {
        autostart.enable().map_err(|e| e.to_string())?;
    } else {
        autostart.disable().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Get current battery status of the desktop/laptop.
#[tauri::command]
pub async fn get_battery_status() -> Result<crate::battery::BatteryStatus, String> {
    Ok(crate::battery::get_battery_status())
}

/// Check whether battery saver is currently pausing tracking.
#[tauri::command]
pub async fn get_battery_saver_active(state: State<'_, AppState>) -> Result<bool, String> {
    let db = state.db.lock().await;
    let settings = db.get_settings().map_err(|e| e.to_string())?;
    if settings.low_battery_threshold == 0 {
        return Ok(false);
    }
    Ok(crate::battery::should_pause_for_battery(
        settings.low_battery_threshold,
    ))
}

/// Enable "Allow JavaScript from Apple Events" for a Chromium-based browser on macOS.
///
/// Writes `AllowJavascriptFromAppleEvents = true` to the browser's NSUserDefaults
/// domain via `defaults write`. The browser must be restarted for the change to
/// take effect. Safe to call: the browser name is mapped to a hard-coded bundle
/// identifier — no shell interpolation occurs.
#[tauri::command]
pub async fn enable_browser_apple_events(browser_name: String) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let bundle_id = browser_name_to_bundle_id(&browser_name).ok_or_else(|| {
            format!(
                "Unrecognized browser '{}'. Enable it manually: open the browser and go to \
                 View > Developer > Allow JavaScript from Apple Events.",
                browser_name
            )
        })?;

        let status = std::process::Command::new("defaults")
            .args([
                "write",
                bundle_id,
                "AllowJavascriptFromAppleEvents",
                "-bool",
                "true",
            ])
            .status()
            .map_err(|e| format!("Failed to run `defaults write`: {}", e))?;

        if status.success() {
            Ok(())
        } else {
            Err(format!(
                "Could not update {} preferences. Try enabling it manually: \
                 View > Developer > Allow JavaScript from Apple Events.",
                browser_name
            ))
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = browser_name;
        Err("Apple Events permissions are macOS-only.".to_string())
    }
}

#[cfg(target_os = "macos")]
fn browser_name_to_bundle_id(name: &str) -> Option<&'static str> {
    let lower = name.to_lowercase();
    if lower.contains("brave") {
        Some("com.brave.Browser")
    } else if lower.contains("chrome") {
        Some("com.google.Chrome")
    } else if lower.contains("chromium") {
        Some("org.chromium.Chromium")
    } else if lower.contains("edge") {
        Some("com.microsoft.edgemac")
    } else if lower.contains("arc") {
        Some("company.day.arc")
    } else if lower.contains("opera") {
        Some("com.operasoftware.Opera")
    } else if lower.contains("vivaldi") {
        Some("com.vivaldi.Vivaldi")
    } else {
        None
    }
}
