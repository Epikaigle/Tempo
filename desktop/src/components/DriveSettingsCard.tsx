import { Cloud, RefreshCw, LogOut, Trash2 } from "lucide-react";
import type { DriveSyncAction, Settings } from "../lib/types";

function formatDriveSyncTime(timestamp: number | null): string {
  if (!timestamp) return "Never";
  return new Date(timestamp).toLocaleString();
}

interface DriveSettingsCardProps {
  settings: Settings;
  busy: boolean;
  action: DriveSyncAction | null;
  notice: { kind: "success" | "error"; message: string } | null;
  onAction: (action: DriveSyncAction) => void;
}

/** Google Drive history has its own lifecycle, independent from LAN settings. */
export default function DriveSettingsCard({
  settings, busy: driveBusy, action: driveAction, notice: driveNotice,
  onAction: handleDriveAction,
}: DriveSettingsCardProps) {
  return (
    <div className="card" style={{ marginBottom: 20 }} aria-busy={driveBusy}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 14 }}>
        <Cloud size={18} style={{ color: "var(--accent)" }} />
        <div>
          <h3 className="section-title" style={{ margin: 0 }}>Google Drive Cross-Device History</h3>
          <div style={{ fontSize: 12, color: "var(--text-secondary)", marginTop: 4 }}>
            Optional. Links Desktop, Android and browser history even when they are on different networks.
          </div>
        </div>
      </div>

      {!settings.drive_sync_configured ? (
        <div style={{ fontSize: 13, color: "var(--text-secondary)" }}>
          Google Drive sync is unavailable in this build because its Desktop OAuth client is not configured.
          Local tracking and direct LAN sync continue to work normally.
        </div>
      ) : settings.drive_sync_enabled && !settings.drive_sync_connected ? (
        <div>
          <p role="alert" style={{ fontSize: 13, color: "var(--danger)", marginTop: 0 }}>
            Google Drive sync is enabled but the Google session is unavailable.
            Reconnect Google to resume cloud transfers; local history and LAN sync are unaffected.
          </p>
          {settings.drive_sync_last_error && (
            <p style={{ fontSize: 13, color: "var(--danger)" }}>{settings.drive_sync_last_error}</p>
          )}
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn btn-primary" disabled={driveBusy}
              onClick={() => handleDriveAction("connect")}>
              <Cloud size={16} />{driveAction === "connect" ? "Connecting…" : "Reconnect Google"}
            </button>
            <button className="btn btn-secondary" disabled={driveBusy}
              onClick={() => handleDriveAction("disconnect")}>
              <LogOut size={16} />Disconnect locally
            </button>
          </div>
        </div>
      ) : !settings.drive_sync_enabled ? (
        <div>
          <p style={{ fontSize: 13, color: "var(--text-secondary)", marginTop: 0 }}>
            Drive sync is off. Tempo will only use your private Google Drive application-data space after you connect explicitly.
          </p>
          <button
            className="btn btn-primary"
            disabled={driveBusy}
            onClick={() => handleDriveAction("connect")}
          >
            <Cloud size={16} />
            {driveAction === "connect" ? "Connecting…" : "Connect Google"}
          </button>
        </div>
      ) : (
        <div>
          <div style={{ display: "grid", gap: 6, fontSize: 13, marginBottom: 14 }}>
            <div>
              <strong>Account:</strong>{" "}
              <span style={{ color: "var(--text-secondary)" }}>
                {settings.drive_sync_account_email ?? "Connected Google account"}
              </span>
            </div>
            <div>
              <strong>Last sync:</strong>{" "}
              <span style={{ color: "var(--text-secondary)" }}>
                {formatDriveSyncTime(settings.drive_sync_last_sync_time)}
              </span>
            </div>
            <div style={{ color: "var(--text-secondary)" }}>
              Last run: {settings.drive_sync_last_uploaded} sent · {settings.drive_sync_last_imported} received
            </div>
            {settings.drive_sync_last_error && (
              <div style={{ color: "var(--danger)" }}>{settings.drive_sync_last_error}</div>
            )}
          </div>

          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button
              className="btn btn-primary"
              disabled={driveBusy}
              onClick={() => handleDriveAction("sync")}
            >
              <RefreshCw size={16} />
              {driveAction === "sync" ? "Syncing…" : "Sync now"}
            </button>
            <button className="btn btn-secondary" disabled={driveBusy}
              onClick={() => handleDriveAction("restore")}
              title="Recover older Drive history without deleting local listening data">
              <RefreshCw size={16} />
              {driveAction === "restore" ? "Restoring…" : "Restore full history"}
            </button>
          </div>
          <p style={{ fontSize: 12, color: "var(--text-secondary)", margin: "8px 0 0" }}>
            Restore checks all historical Google Drive batches and keeps your existing local plays.
          </p>
          <details style={{ marginTop: 16, borderTop: "1px solid var(--border)", paddingTop: 12 }}>
            <summary style={{ cursor: "pointer", fontWeight: 600, fontSize: 13 }}>
              Older-history sharing and account controls
            </summary>
            <p style={{ fontSize: 12, color: "var(--text-secondary)", margin: "8px 0 12px" }}>
              Upload older local history explicitly shares your past recordings with this Google account,
              including previously deleted plays and potentially records from another Google account.
            </p>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button className="btn btn-secondary" disabled={driveBusy}
                onClick={() => handleDriveAction("share-history")}>
                <Cloud size={16} />
                {driveAction === "share-history" ? "Uploading…" : "Upload older local history"}
              </button>
            </div>
            <div style={{ borderTop: "1px solid var(--border)", marginTop: 16, paddingTop: 12 }}>
              <p style={{ fontSize: 12, color: "var(--text-secondary)", margin: "0 0 10px" }}>
                Disconnect affects this computer only. Delete cloud history stops
                shared Drive sync on linked devices as they reconnect.
              </p>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button className="btn btn-secondary" disabled={driveBusy}
                  onClick={() => handleDriveAction("disconnect")}>
                  <LogOut size={16} />
                  {driveAction === "disconnect" ? "Disconnecting…" : "Disconnect this computer"}
                </button>
                <button className="btn btn-secondary" disabled={driveBusy}
                  onClick={() => handleDriveAction("delete")}
                  style={{ color: "var(--danger)" }}>
                  <Trash2 size={16} />
                  {driveAction === "delete" ? "Deleting…" : "Delete cloud history"}
                </button>
              </div>
            </div>
          </details>
        </div>
      )}

      {driveNotice && (
        <p role={driveNotice.kind === "error" ? "alert" : "status"} aria-live={driveNotice.kind === "error" ? "assertive" : "polite"}
          style={{ fontSize: 13, marginTop: 12,
            color: driveNotice.kind === "error" ? "var(--danger)" : "var(--text-primary)" }}>
          {driveNotice.message}
        </p>
      )}
      {driveBusy && (
        <p role="status" aria-live="polite" style={{ fontSize: 12, marginTop: 10, color: "var(--text-secondary)" }}>
          {driveAction === "connect" ? "Connecting to Google…" :
           driveAction === "restore" ? "Restoring historical batches…" :
           driveAction === "delete" ? "Deleting shared Drive batches…" :
           driveAction === "share-history" ? "Authorizing older recordings…" :
           driveAction === "disconnect" ? "Disconnecting this computer…" :
           "Synchronizing with Google Drive…"}
        </p>
      )}
      <div style={{ fontSize: 12, color: "var(--text-secondary)", marginTop: 14 }}>
        Google Drive is independent from LAN. Disconnect affects this computer only.
        After Delete cloud history, reconnection does not re-upload your old recordings.
        Use “Upload older local history” to authorize restoring that archive.
      </div>
    </div>
  );
}
