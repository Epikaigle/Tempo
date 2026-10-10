# Google Drive history sync (Desktop)

Tempo Desktop remains local-first. The existing LAN/hotspot transport is still available and does not require a Google account. Google Drive history sync is an optional transport for users whose desktop and Android phone are not usually reachable on the same local network.

## Transport model

Tempo uses the same history protocol across Android, the browser extension, and Desktop:

- **Same local network:** the existing direct LAN transport can send history to the phone.
- **Different networks:** when the user explicitly enables Google Drive sync, clients exchange immutable history batches through the Google Drive `appDataFolder` application-data space.
- Every client retains its own local history and may operate without Android. Drive is a synchronization transport, not a public music-history folder.

Drive history files use the `tempo_history_v1_` namespace and schema version 1. Current clients name batches as `tempo_history_v1_g<generation>_<device_id>_<batch_id>.json.gz` and store the same generation in the Drive app property `tempo_generation`. Clients generate stable SHA-256 event IDs and deterministic batch IDs so retries are idempotent. Imported events are not re-uploaded as new events, and temporal deduplication remains a fallback for overlapping capture sources.

Desktop retains every reconciled producer/event identity in a local alias table instead of overwriting the original event. Its conservative cross-producer temporal fallback is limited to **2 seconds**, the same Drive-import window used by Android and browser clients. Session IDs are source-local and are not treated as proof of independent playback across capture apps. A seven-second independent replay remains separate. Exact producer/origin IDs are authoritative, but without a shared playback identity no heuristic can distinguish all near-simultaneous independent plays. Once an event is uploaded, its original ID is reused by both LAN and Drive even if title or artist metadata is later corrected. Exact retries remain idempotent across restarts, and a second distinct event from any already represented device remains a legitimate replay. Candidate title/artist comparisons use trim and Unicode lowercase so accented names behave consistently with the other clients. Existing origins are migrated automatically; Desktop rescans retained Drive batches once to recover identities and replays lost by the earlier reconciliation, while keeping local history and the accepted deletion generation.

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
2. atomically store `N`, disable Drive history sync locally, reset the download cursor and suppress pre-existing local history from automatic republication;
3. delete history batches whose valid `tempo_generation` is less than `N`; an explicit user-initiated deletion also removes batches in the Tempo history namespace with malformed generation metadata.

Reconnecting to the same Google account does **not** automatically republish deleted cloud history. Local plays that already existed when the deletion marker was accepted, including unsent plays, are marked `cloud_suppressed` for that Google account. They remain available locally. **Upload older local history** is the explicit opt-in to re-upload those records. New plays recorded after re-enabling Drive can sync normally. A cloud deletion on account A must not suppress account B's locally owned records.

Cloud cleanup failure must not resume local synchronization. Duplicate same-name control markers are paged through and validated; the newest Google-server timestamp is authoritative, independent of list order. An upload retry succeeds only when filename, size, checksum and producer/schema/generation metadata all match.

Clients retry idempotent Drive reads, deletes and control-marker overwrites after transient HTTP 429/5xx failures with bounded exponential backoff. An uncertain batch-creation POST is deliberately **not** blindly retried; the next sync first verifies an existing file by exact name, size, checksum and producer metadata.

Other linked clients check the marker before uploading. If they observe a newer marker than the one they explicitly accepted, they stop Drive sync, reset the download cursor, suppress existing local captures for that account, clean only older generations, and require explicit re-enablement.

If another client deliberately re-enables after the marker update, it publishes generation `N`. A stale Desktop/browser/Android client that wakes later may still clean generations older than `N`, but it must never erase the freshly seeded generation `N`. This keeps deletion effective without creating a second race where a late stale client destroys newly re-enabled cloud history.

Stale clients never delete batches with unknown/malformed generation metadata, because they cannot prove such a batch is older than the accepted marker. Only the client handling the user's explicit cloud-delete action removes those files, scoped to Tempo's own history filename prefix/suffix. Clients never delete a known current or newer generation.

Readers also ignore and may best-effort remove batches older than their accepted generation. This prevents an upload that was already in flight during deletion from resurrecting old history after it eventually reaches Drive.

## Google account boundaries

Drive cursors and deletion-marker/generation acceptance are account-scoped. Desktop binds the session to Google's immutable OpenID Connect `sub` identity, not an email address that may change or be reassigned. If the signed-in Google account changes, Tempo resets Drive-only upload/download state before accepting the new account. Older Desktop sessions saved with an email but without `sub` are disabled during migration and require explicit reconnection. Their existing locally owned events are quarantined from automatic uploads until the user explicitly chooses the account to receive them.

The last verified Google `sub` is also stored separately from the currently active OAuth session. If a sign-in is interrupted after credentials were temporarily disabled, the verified subject remains durable and is used to attribute older unowned local captures before any different Google account can sync. This protects the interrupted-sign-in → different-account path without silently claiming old events for the new account.

The OS keyring credential now contains both the **verified immutable Google subject** and refresh token. On every read the subject must match the active account, and successful OAuth token refreshes are independently checked against Google's `userinfo.sub` before Tempo uses the token for Drive operations. If an interrupted account switch leaves another account's credential in the keyring, access fails closed. Legacy *unbound* SQLite/keyring refresh credentials require fresh Google sign-in rather than being automatically trusted or silently relabeled.

Imported events are likewise tagged with their Google account. Temporal reconciliation never matches an imported event owned by a different account; old imported records whose owner was not stored are quarantined under an unverified owner rather than guessed. On first upgrade, legacy origin-alias backfill is a one-time SQLite migration, avoiding a full-table scan on every sync/status request.

A refresh token from a previous Google account must never be reused for a newly selected account. If Google does not issue a fresh refresh token during an account switch, the connection is rejected and the user must connect again.
Before replacing the OS credential, Desktop commits a disabled state with no usable token or accepted account identity. Identity is saved again only after credential replacement succeeds; sync is enabled after the shared marker is checked. A keyring or SQLite failure therefore requires reconnecting instead of allowing an account/token mismatch. Same-account reconnects preserve Drive cursors; a different or unverified previous account resets them. Disconnect deletes only Desktop's OS credential and disables its cloud sync. It intentionally does **not** call Google's project-wide token revocation endpoint: doing so would also revoke grants used by Android and browser clients. The last verified `sub` is retained for attribution, while new plays captured with Desktop Drive disabled are tagged `legacy-unverified` (local-only). Later account changes cannot silently claim these plays for another Google account. Desktop tags local producer events with their owning account; changing accounts cannot silently upload that account's earlier captures to the newly selected Drive account.

### Explicitly sharing older local history

The optional **Upload older local history** button requires a confirmation that includes the risk of uploading plays previously associated with another Google account. It reassigns locally owned Desktop plays to the current verified Google subject, clears any cloud-suppression flags and starts a normal bounded upload; later sync cycles drain the remaining backlog. It never intentionally re-exports Drive-imported events. Use this action when migrating an old email-only Desktop installation or deliberately transferring a history archive between accounts. The ordinary **Sync now** action never performs this reassignment.

### Invalid local records and independent inbound sync

Desktop validates individual locally recorded plays before forming 50-event batches. If a play violates the shared wire-format requirements (for example an empty title or overlong text), Desktop **retains it in SQLite** and skips just that play for the current upload, rather than poisoning the whole batch. A warning in Drive sync status reports the number of skipped local plays and the first local row ID; users can correct the local metadata and retry without losing the original recording. Upload scanning uses bounded keyset pages so even many invalid earlier records cannot hide later valid records. An upload error also does not prevent an attempted inbound download; once the inbound transfer finishes, the upload error is still reported.

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

Short-lived access tokens may be cached, but the refresh token is the credential that must receive durable OS-backed protection. Earlier plaintext refresh tokens cannot prove which Google account owns them, so this security upgrade **requires reconnecting Google** instead of automatically migrating unbound credentials. New credentials carry the immutable account subject inside the OS keyring entry.

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

## LAN acknowledgment and token continuity

The paired LAN transport requires explicit `{"ok":true}` JSON in the Android HTTP response; HTTP 200 alone is not a durable batch acknowledgment. Malformed, truncated or unconfirmed responses retain queued plays for retry. When the phone rotates its pairing token and returns `next_token`, Desktop persists the new token before marking plays as synced, on all five discovery routes. An error saving the token does not mark the batch as delivered. Cloud Google Drive sync remains independently optional.

## Long offline periods and LAN delivery limits

The existing Android LAN endpoint accepts at most **100 plays in one HTTP request**.
Desktop now selects at most **50 queued plays** per request, in deterministic
timestamp/row-ID order. Both **Sync now** and the periodic LAN sync can drain up
to **20 successive batches (1,000 plays)** per invocation, stopping on errors
or when the queue is empty. Remaining local plays stay queued for another
attempt. This prevents an old offline backlog from being rejected as an
oversized LAN upload; Drive's independent upload bookkeeping is unaffected.

## Historical retention and full recovery

Tempo Desktop no longer deletes local synced scrobbles after 30 days during routine maintenance. Its **Restore full history** action resets only the Drive receive cursor, then re-enumerates all current appDataFolder batches with idempotent event/origin reconciliation. This is distinct from normal incremental sync, which keeps a 24-hour listing overlap.

Drive is not a guaranteed ten-year backup: removal of application data, account loss, or revoked authorization can make those cloud files unavailable. Export separate backups for disaster recovery, and verify that a restored device reconstructs the expected year-by-year history. Batch uploads now serialize a stable event-derived creation timestamp; conflicting same-name files are never interpreted as proof of a successful upload unless the size, checksum and producer metadata match.

## Remaining real-account validation

CI uses a dummy public client ID for compilation and unit tests. Keep this PR as a draft until a real OAuth client is configured and these checks pass:

1. Connect Desktop, Android and the browser extension to the same test account on different networks. Send history in both directions, retry and restart; each event must appear once.
2. Switch Google accounts using Disconnect → Connect **and** cancel a reconnect between database credential preparation and Google token exchange, then connect a different account. Verify the last verified `sub` remains authoritative and no credentials, cursors or locally owned plays leak across the boundary. Test changed email for the same `sub` and distinct `sub` identities.
3. Delete cloud history, including a malformed-generation history object, deliberately re-enable one client, then wake a stale client. It must stop without deleting the newly accepted generation. Verify that old local history does not reappear until the user explicitly chooses to share it.
4. Simulate HTTP 429/503 during Drive list/download/delete and verify bounded retries. Simulate an upload timeout and confirm name/checksum verification prevents a duplicate logical event on retry.
5. Expire the access token and verify OS credential-store refresh. Disconnect with the store unavailable and verify local sync is disabled and the cleanup error is shown.
6. Upgrade an earlier Desktop prototype with uploaded history; confirm its old events are retained locally and not uploaded automatically while the account is unverified. Reconnect, approve **Upload older local history**, then confirm that the events are re-sent with checksum metadata once.
7. Add one invalid local play before valid plays, including more than 5,000 skipped candidates, and verify valid ones still upload, the invalid record remains locally available, a warning appears and inbound Drive batches continue downloading.
8. Play the same short track independently on two sources seven seconds apart, and compare with a real parallel capture within two seconds. Confirm all three Drive-import implementations follow the same 2-second fallback, including different source-local session IDs. Exact event IDs must remain authoritative; ambiguity for truly simultaneous independent plays cannot be eliminated without shared playback IDs.
9. Simulate a crash after writing a Google B refresh token into the OS keyring but before storing B's identity in SQLite, then sign in to A. The keyring subject and subsequent token refresh must be rejected if mismatched. Verify legacy unbound refresh credentials prompt for reconnect.
10. Import a same-title/time event from A, switch to B, and import a different B event at nearly the same timestamp. Both records must survive, and A's quarantined/legacy imports must never become B temporal aliases.
11. Reopen the Desktop database repeatedly with a large legacy archive and confirm origin-alias migration runs only once, while incremental origin writes remain durable.


## Per-account event identities and safe LAN provenance

The account-scoped alias migration replaces global uniqueness of producer
event IDs with `(account_subject, origin_event_id)`. The primary event-state
table retains one row per local scrobble but permits the same cloud origin ID
for separate Google archives. Old aliases are copied under their verified
event owner or `legacy-unverified`; the existing deletion trigger is rebuilt,
and migration is transactional and one-time. Exact-id and temporal matching
both scope their results to the authorized account.

A paired phone receives an `origin_account_subject` for Desktop plays only
when Desktop Drive is enabled and the stored play owner matches the active
verified subject. LAN synchronization remains available with Drive disabled,
but those unidentified plays are local-only on Android, not silently copied
into another Google account. This intentionally favors explicit cloud consent
over forwarding unmatched or unverified histories.

Before merging, confirm Desktop-only Disconnect does not invalidate Android/browser grants, test manual/background LAN calls starting simultaneously, repeat same-origin A/B/A restore on real Google accounts,
upgrade a populated Desktop database and verify the legacy aliases survived,
and try paired-LAN sending with both matching and mismatched Google subjects.
