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
| Desktop + Chrome same computer | Run both detectors for one physical play | One logical play after receiving both producers' batches |
| Two quick replays | Play same 25-second song twice while both detectors are on | Exactly **two** plays, never zero, one or four |
| Background/offline | Disable Wi-Fi, queue events, restart, reconnect | No loss; pending counts resolve; repeat sync adds zero events |
| Post-create crash | Server accepts upload, then simulate client timeout before local acknowledgement | Retry preserves one logical event and a verified cloud copy |
| Historical recovery | Import ten years of fixture batches, reset local receiver, choose Restore full history | All recoverable years and event IDs restored; no locally owned event deleted |
| Own-device recovery | Keep the Tempo device identity, delete only its local play rows, then restore from the same device's Drive batches | Previously locally owned tracks are recovered; surviving local plays are not duplicated |
| Local retention | Leave synced local plays older than 1 year/30 days/7 days; run routine maintenance | No listening-event rows automatically deleted |
| Cloud delete with stale client | Disconnect one client, delete cloud history on another, re-enable a third, reconnect stale client | Stale client does not resurrect history or delete the new generation |
| Account switching | Connect account A, switch to B on one client | No A history uploaded to B; explicit authorization needed |
| Auth expiration | Expire/revoke access tokens; sync again | Safe refresh/reconnect, no silent account switch |
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
