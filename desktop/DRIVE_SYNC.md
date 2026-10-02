# Google Drive history sync (Desktop)

Tempo Desktop remains local-first. The existing LAN/hotspot transport is still available and does not require a Google account. Google Drive history sync is an optional transport for users whose desktop and Android phone are not usually reachable on the same local network.

## Transport model

Tempo uses the same history protocol across Android, the browser extension, and Desktop:

- **Same local network:** the existing direct LAN transport can send history to the phone.
- **Different networks:** when the user explicitly enables Google Drive sync, clients exchange immutable history batches through the Google Drive `appDataFolder` application-data space.
- The Android database remains the primary Tempo history store. Drive is a synchronization transport, not a public music-history folder.

Drive history files use the `tempo_history_v1_` namespace and schema version 1. Current clients name batches as `tempo_history_v1_g<generation>_<device_id>_<batch_id>.json.gz` and store the same generation in the Drive app property `tempo_generation`. Clients generate stable SHA-256 event IDs and deterministic batch IDs so retries are idempotent. Imported events are not re-uploaded as new events, and temporal deduplication remains a fallback for overlapping capture sources.

Files produced before generation metadata existed are treated as generation `0`. Every readable batch must still carry valid identity metadata, a declared byte size and a matching `tempo_sha256` checksum. Earlier Desktop prototype uploads without this checksum are re-queued once after upgrading; stable event IDs prevent duplicate history.

Downloads are limited to 10 MiB before and after decompression. A batch is validated as a whole, including its event IDs, deterministic batch ID, filename, device/platform metadata and numeric/text limits. Malformed batches are skipped. Database imports run in one transaction per batch, so an insertion failure cannot leave a partial batch behind.

## Privacy and permissions

Drive sync is opt-in.

Tempo requests only the Google Drive application-data scope:

`https://www.googleapis.com/auth/drive.appdata`

The application-data area is hidden from the normal Drive file UI and is intended for app-specific data. Tempo does not need full access to the user's regular Drive files for history sync.

Cross-device history batches identify their producer with a random Tempo device UUID and a generic device label. The Desktop sync transport must not upload the operating-system hostname because hostnames can contain personal or company names.

The existing LAN pairing hostname may still be used locally for device discovery/pairing; it is not part of the Google Drive history payload.

## Cloud deletion coordination

All clients share `tempo_history_control_v1.json` as a server-timestamped disable/deletion marker. Its Google Drive `modifiedTime` is also the accepted history generation.

When a user deletes shared cloud history, a client must:

1. update/create the disable marker first and obtain the new server generation `N`;
2. atomically store `N`, disable Drive history sync locally and reset Drive-only cursors/upload flags;
3. delete only history batches whose `tempo_generation` is less than `N`.

Cloud cleanup failure must not resume local synchronization. Duplicate same-name control markers are paged through and validated; the newest Google-server timestamp is authoritative, independent of list order. An upload retry succeeds only when filename, size, checksum and producer/schema/generation metadata all match.

Other linked clients check the marker before uploading. If they observe a newer marker than the one they explicitly accepted, they stop Drive sync, clear their Drive-upload cursors/flags, clean only older generations, and require explicit re-enablement.

If another client deliberately re-enables after the marker update, it publishes generation `N`. A stale Desktop/browser/Android client that wakes later may still clean generations older than `N`, but it must never erase the freshly seeded generation `N`. This keeps deletion effective without creating a second race where a late stale client destroys newly re-enabled cloud history.

Readers also ignore and may best-effort remove batches older than their accepted generation. This prevents an upload that was already in flight during deletion from resurrecting old history after it eventually reaches Drive.

## Google account boundaries

Drive cursors and deletion-marker/generation acceptance are account-scoped. If the signed-in Google account changes, Tempo resets Drive-only upload/download state before accepting the new account.

A refresh token from a previous Google account must never be reused for a newly selected account. If Google does not issue a fresh refresh token during an account switch, the connection is rejected and the user must connect again.
Before replacing the OS credential, Desktop commits a disabled state with no usable token or accepted account identity. Identity is saved again only after credential replacement succeeds; sync is enabled after the shared marker is checked. A keyring or SQLite failure therefore requires reconnecting instead of allowing an account/token mismatch. Same-account reconnects preserve Drive cursors; a different or unverified previous account resets them.

## OAuth build configuration

Tempo is multi-platform, so each platform should use its own OAuth client ID under the same Google Cloud project/logical Tempo application.

Desktop reads its OAuth client ID at compile time from:

`TEMPO_GOOGLE_OAUTH_CLIENT_ID_DESKTOP`

The GitHub Desktop release workflow reads that value from the repository variable with the same name. Release builds fail early if the variable is missing, preventing an installer from being published with a non-functional Google Drive sign-in flow.

Enable the Google Drive API and register a Desktop public client with a loopback callback. Android, Chrome, Firefox and Desktop must use clients from the same Google Cloud project to access the same hidden application-data namespace.
The Desktop loopback listener reads complete bounded HTTP headers, checks the callback route and OAuth state, and ignores unrelated requests within one fixed sign-in deadline. The browser acknowledgment confirms receipt of authorization; the Desktop UI reports connection only after token exchange and account verification finish.

The OAuth client ID is a public application identifier. Do not add an OAuth client secret to the Desktop app or repository: Tempo Desktop is a public/native client and uses Authorization Code + PKCE through the user's system browser.

## Local token storage

Long-lived Google refresh tokens should be stored in the operating system credential store rather than plaintext application SQLite:

- macOS: Keychain
- Windows: Credential Manager
- Linux: Secret Service-compatible keyring

Short-lived access tokens may be cached, but the refresh token is the credential that must receive durable OS-backed protection. Existing plaintext refresh tokens should be migrated to the credential store and removed from SQLite.

## Sync cadence

Drive sync is deliberately batch-oriented rather than real-time polling. Desktop can upload queued history at the configured sync interval, while Android can pull on app start, manual sync, and periodic background work. This keeps Drive API traffic low while still allowing histories to converge when the devices never share a LAN.

## Protocol compatibility checklist

Before changing schema version 1, verify all three producers/consumers agree on:

- file prefix: `tempo_history_v1_`
- filename generation form: `tempo_history_v1_g<generation>_<device_id>_<batch_id>.json.gz`
- Drive generation app property: `tempo_generation`
- compressed batch SHA-256 app property: `tempo_sha256`
- control marker: `tempo_history_control_v1.json`
- `schema_version: 1`
- snake_case JSON field names
- batch size: 50 events
- timestamps/durations in milliseconds
- volume represented on the 0–100 protocol scale (`null` when unknown)
- Desktop fractional volume converted to percent, preserving 1% as audible and capping amplification above 100%
- stable SHA-256 event IDs
- deterministic SHA-256 batch IDs
- marker-before-delete semantics
- generation-aware deletion (`batch_generation < marker_generation`)
- account-scoped Drive cursors/generation state

Any incompatible wire-format change should introduce a new schema version rather than silently changing v1.

## Remaining real-account validation

CI uses a dummy public client ID for compilation and unit tests. Keep this PR as a draft until a real OAuth client is configured and these checks pass:

1. Connect Desktop, Android and the browser extension to the same test account on different networks. Send history in both directions, retry and restart; each event must appear once.
2. Switch Google accounts and verify no credential, cursor or deletion generation crosses the account boundary.
3. Delete cloud history, deliberately re-enable one client, then wake a stale client. It must stop without deleting the newly accepted generation.
4. Expire the access token and verify OS credential-store refresh. Disconnect with the store unavailable and verify local sync is disabled and the cleanup error is shown.
5. Upgrade an earlier Desktop prototype with uploaded history and confirm its local events are re-sent with checksum metadata once.
