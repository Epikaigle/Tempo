# Tempo Google Drive v1 — release acceptance matrix

This checklist is a **real-account** validation complement to CI. A green Kotlin/Rust/TypeScript
build using dummy OAuth client IDs is not proof that cross-platform Google sign-in works.

## Preconditions
- Register Android, Desktop, Chrome and Firefox OAuth clients in **one Google Cloud project**;
  enable the Google Drive API and configure the consent screen/test users.
- Build/sign the clients with their actual public OAuth client IDs (never commit a client secret).
- Use a dedicated Google test account without valuable existing appDataFolder Tempo history.
- Back up local databases and export any existing test data before cloud-deletion experiments.
- Record each client's app version, account email, random Tempo device ID and UTC clock.
- Verify all four clients can operate without Android and with Drive disabled.

## Cross-client matrix
| Scenario | Action | Pass criterion |
| --- | --- | --- |
| Android -> Desktop | Track on Android; synchronize Drive; restore on Desktop | One event; track, timestamp and listen duration preserved |
| Desktop -> Android | Track desktop-native music; sync via Drive | One event in Android, including after retry |
| Chrome -> Firefox | Track Spotify Web in Chrome; sync on different network | One imported event in Firefox |
| Firefox -> Chrome | Track YouTube Music in Firefox; sync | One imported event in Chrome |
| Browser -> Desktop | Capture browser music on another machine; sync | Exactly one Desktop event |
| LAN incomplete persistence | Fail one Android database write in the middle of a paired LAN batch | Android returns HTTP 503; sender retries without treating the incomplete batch as delivered |
| LAN invalid acknowledgment | Return HTTP 200 with empty, malformed, or `ok:false` JSON from a test phone | Desktop and browser both retain every queued play; neither counts the response as success |
| LAN token rotation | Send two successive batches from Desktop via each available discovery method | Desktop persists `next_token` before acknowledging each batch, and the second batch authenticates without re-pairing |
| LAN + Drive same origin | Send a Desktop/browser play to Android over LAN, then publish the sender's Drive batch | Same exact event ID is retained; Android counts once and never republishes it under an Android-owned ID |
| LAN with Drive disabled | Send a Desktop/browser play to Android with Google Drive disabled on sender | No Google OAuth or connection is required merely to attach LAN provenance; local sync still works |
| LAN-only origin relayed to cloud | Disable Drive on Desktop/extension, send LAN plays to an Android device with Drive enabled, sync Android, and restore on a separate client | Android relays original event IDs; all plays are recoverable without the original sender accessing Drive |
| Original sender later enables Drive | After Android has relayed LAN plays, enable Google Drive on their original producer and sync all devices again | The two cloud copies represent one event per original event ID, not two listens |
| Mixed-version LAN | Use a previous Desktop/browser sender without origin fields | Legacy payloads still import through the existing bounded temporal fallback |
| Desktop + Chrome same computer | Run both detectors for one physical play | One logical play after receiving both producers' batches |
| Two quick replays | Play same 25-second song twice while both detectors are on | Exactly **two** plays, never zero, one or four |
| Android cross-batch origin claims | Device A captures a play. Device B sends its same-play capture within 1 second in batch 1, then sends a different short-track replay seven seconds later in batch 2 | The first matches the existing physical play; Android persists B's origin alias and inserts the second distinct B event (two total), including after app restart |
| Android v55→v56 upgrade | Update a populated v55 Room database containing LAN/Drive imports, then restart and delete one listening row | All previous plays survive; existing producer IDs are backfilled; unrelated events remain; deleting a play cascades to its aliases |
| Background/offline | Disable Wi-Fi, queue events, restart, reconnect | No loss; pending counts resolve; repeat sync adds zero events |
| Post-create crash | Server accepts upload, then simulate client timeout before local acknowledgement | Retry preserves one logical event and a verified cloud copy |
| Historical recovery | Import ten years of fixture batches, reset local receiver, choose Restore full history | All recoverable years and event IDs restored; no locally owned event deleted |
| Own-device recovery | Keep the Tempo device identity, delete only its local play rows, then restore from the same device's Drive batches | Previously locally owned tracks are recovered; surviving local plays are not duplicated |
| Local retention | Leave synced local plays older than 1 year/30 days/7 days; run routine maintenance | No listening-event rows automatically deleted |
| Cloud delete with stale client | Disconnect one client, delete cloud history on another, re-enable a third, reconnect stale client | Stale client does not resurrect history or delete the new generation |
| Account switching | Connect account A, switch to B on one client | No A history uploaded to B; explicit authorization needed |
| Auth expiration | Expire/revoke access tokens; sync again | Safe refresh/reconnect, no silent account switch |
| Firefox OAuth PKCE | Register signed Firefox redirect, connect with real Google account, let token expire, reconnect and simulate forged callback | Auth-code + S256 PKCE works without a client secret; state and callback URI are validated and implicit tokens are never accepted |
| Own-browser restore without duplicates | Upload browser plays, keep some local records, then restore all same-device batches | Exact original event IDs prevent creating second copies of surviving plays |
| Consent rejected | Decline Google sign-in and Firefox optional permission | Local/LAN tracking continues; no Drive requests |
| Corrupt batch | Insert truncated gzip, invalid SHA, malformed Unicode, enormous payload in test account | Valid batches continue safely; corrupt data never committed |
| Same-name legacy batch | Upload old same-name payload and retry with corrected deterministic payload | Last valid backup never removed before replacement is verified |
| Big history | At least 100,000 events across multiple producers | No OOM/quota surprises, bounded download buffers, resumable restores |

## Publication blockers

1. Fix every event-loss, bad deduplication or cross-account leak found above.
2. Verify Chrome, Firefox, Android and Desktop with **real** OAuth credentials and signatures.
3. Verify the exact fork PR head SHAs in GitHub Actions, on all listed operating systems.
4. Test historic migration from old LAN-only/Desktop and legacy browser history.
5. Review project licenses, permissions, privacy language and changes against current upstream.
6. Do not publish Google credentials or real listening-history archives in test logs/PR artifacts.

## Limits

Drive `appDataFolder` is not a guaranteed permanent archival backup. A user can remove the
application's hidden data or lose access to the account. Keep an independent user-controlled
backup/export and test restoration from it. The extension's long-lived IndexedDB store can
also be evicted or cleared by the browser; never interpret a cloud-upload flag alone as a
guarantee of recoverability.

### Shared Drive deduplication — acceptance update

- Import the same song from independent devices seven seconds apart: Desktop, Android and both browser extensions must retain **two** plays, regardless of the order the batches arrive.
- Import parallel captures of one playback one second apart: all importers may reconcile to one play while preserving the two producer/origin aliases, including when the apps use different local session IDs.
- Switch accounts and verify previously imported records (or imports whose previous owner is unknown) are not eligible for temporal matching against the new account. Older local events must not upload across account boundaries without explicit consent.
- Exact origin IDs are mandatory for idempotency. Two truly independent plays within two seconds can still be indistinguishable using only title, artist and timestamp; avoid treating the temporal fallback as proof of exact playback identity.


### Google-account ownership and invalid-row recovery (v57)

- Android: create old-account history, then switch to another Google `sub`. Verify the existing rows are tagged with the old owner, the next account starts at the current maximum Room row ID, and only new captures upload. Changing an email for the same Google `sub` must not be treated as a new account
- Android: migrate Room from schema 56 to 57. Imported Drive rows without provable ownership become `legacy-unverified` and stay visible locally; local rows remain intact. A restored older account must not supply temporal dedup matches for a different account
- Android: delete a local track while retaining its listening row or create malformed metadata; verify the record is retained, separately recorded for retry, valid later plays still upload, and incoming Drive batches download despite an outgoing error
- Browser: switch Google accounts with a mix of uploaded, never-uploaded, imported and reconciled plays. Existing rows must retain their original account identity and upload status. Reconnect the original account and verify no cross-account publishing
- Browser: upgrade IndexedDB v2 to v3 with an archive of reconciled producers. Every origin ID must be present in the new multi-entry index. Normal Drive sync must use indexed lookups instead of scanning the entire ten-year play collection; full own-device restore may explicitly scan missing legacy origins
- Browser: one malformed local play must not prevent uploading later valid plays or receiving inbound history. The invalid original record must remain available for repair
- Both: verify current user identity from Google's immutable subject rather than mutable email, including sign-in restart, token refresh and Firefox consent


### Multi-account source identities and schema migrations (v58/v4)

- Upgrade a non-empty real Android Room v57 database to v58; assert the original
  aliases survive and `(subject A, event X)` and `(subject B, event X)` can both
  exist while original `listening_events` rows and foreign-key cascade remain valid
- Import exactly the same verified remote producer ID first from Google A, then
  from Google B: Android and Desktop must retain separate records; replay within
  each account must remain idempotent. Legacy unverified aliases must not count as
  proof of a duplicate in a newly authorized account
- Pair Desktop on A with an Android phone on B over LAN; the phone retains the
  play locally but never uploads it to B. Repeat with matching A accounts; when
  the sending client explicitly disables Drive, the phone must not silently
  circumvent that choice. Test legacy LAN payloads with no account provenance
- Upgrade Chrome/Firefox IndexedDB v3 to v4 with 100,000+ uploaded plays and a
  few pending owner/unowned plays. Verify the indexed pending query returns only
  relevant unsent records, sorted by timestamp/keyset, and that a cloud deletion
  affects only its own owner. Confirm all older play IDs and origin aliases survive
- Keep independent offline backups. Passing CI cannot substitute for actual
  multi-client OAuth, years-old archive restore or mobile database migration tests


### Restore and LAN/Drive race regressions (2026-10-10)

- Restore an offline ZIP containing the same event ID for two Google accounts A and B; both listening rows and their distinct producer aliases must survive, including altered metadata and repeated restore.
- Pair an extension/Desktop while its cloud sync is disabled. Ingest a play by LAN as `lan-unverified`, then later enable the sender's cloud sync and download exactly that producer ID to Android. The phone must retain **one** listening record, adopt the verified account, and preserve its alias; a merely similar title/time must not cause ownership promotion. Repeat with distinct A/B owners.
- Restore an Android database whose auto-incremented IDs change while older A and B upload cursors and failed-row retry sets exist. Switch A→B→A and ensure no eligible restored plays are lost. Do not reset the server deletion-generation marker when resetting local row-ID state.
