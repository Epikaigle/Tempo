# Tempo Premium — Cloud, Sync, AI & Public Profiles

**Status:** Final plan (v7)
**Date:** 2026-09-16
**Locked decisions:** v1 = cloud sync foundation. Core app free forever, **no login required for free users**. Premium optional. `wh0.im` owned, built later as an independent identity. **Cloud DB is the authoritative master.** **Own VPS route.** ~2.5K MAU. Implementation by AI coding agents.

> This document is self-contained. It states the context, defines the terms, records every decision together with what replaces the rejected option, and cites sources — so it can be read cold by anyone.

---

## 0. Revision history

| # | Comment | What changed |
|---|---|---|
| 1 | "It's expensive — smart stacks that skip complex engineering **on our own VPS route**." | Supabase removed. §5.3/§5.8 specify a **single VPS + Docker Compose + Caddy + Postgres + Go**, ~**€8–15/mo**. |
| 2 | "What if two or more devices play the same song at once? How do updates reach every device without conflict?" | §4.7 added — and it surfaced a **real bug** in the existing dedup logic. Fix specified. |
| 3 | "Just tell me the final Play commission, country by country." | §5.9 leads with: for a **subscription** it is **15% in every country**, before and after every rollover. |
| 4 | "I didn't understand the fee arithmetic." | §5.9 rewritten as plain arithmetic on one €2 customer and one €15 customer. |
| 5 | "Multi-device is about listening on many devices, not new-phone migration." | §5.10 reframed around one complete history across every device, including the desktop app and browser extension. |
| 6 | "Why not Go or something made for this?" | §5.3: backend language changed to **Go**, with the condition under which Kotlin would win. |
| 7 | "What for backup if the server fails, is corrupted, or the provider disappears? Keep it in easy storable form." | §5.7 rewritten as **two layers** — WAL for speed, a **portable single-file dump** you can restore on your own laptop for survival. |
| 8 | "Security?" | New **Security posture** subsection in §5.8, with a ranked realistic-threat list. |
| 9 | "3 free requests can be exploited by uninstalling." | §5.11 — you can't prevent it client-side and shouldn't try; three no-login signals, and the decision priced in explicitly. |
| 10 | "Keep only DeepSeek V4 Flash 0731." | §5.11 narrowed to a single model. |
| 11 | "DeepSeek V4 Flash exists on OpenRouter with ZDR hosts." | **Correct — verified and adopted.** Trial cost drops to ~$6 for all 2,500 users. |

---

## 1. Decisions, with replacements

| Decision | Verdict | **Replaces it with** | Revisit when |
|---|---|---|---|
| PowerSync / Electric / Zero / Couchbase / ObjectBox / Turso | Reject | **Nothing** — Room stays the schema owner; sync built from `MergeEngine` (§4). | You add iOS/web clients *and* want a shared sync layer. |
| A global catalog of **user-linked** rows | Reject | **A global catalog of world-facts only** (§5.2) — no `user_id` in any shared table. | Never — the split is the point. |
| MCP | Reject | **Native tool-calling + typed local functions** (§5.11). | Third-party AI hosts need to query Tempo. |
| A vector DB over listening events | Reject | **On-device `sqlite-vec` over library entities** (§5.11). | Never for events. |
| On-device LLM inference | Reject | **Server-side cheap open models** (§5.11). | Offline AI becomes required. |
| Kotlin/Ktor backend | Reject | **Go** (§5.3). | You want to personally audit the auth code — then Kotlin. |
| Coolify as a production control plane | Reject | **Docker Compose + Caddy.** | You run Coolify on a separate host. |
| pgBackRest/WAL-G → **Cloudflare R2** | Reject | **Backblaze B2**, plus a second independent location (§5.7). | Never for backups; R2 stays fine for serving public assets. |
| WAL archives as the *only* backup | Reject | **A nightly portable `pg_dump` file** alongside WAL archiving (§5.7). | Never — one is for speed, the other for survival. |
| Custom password/email auth, MFA, reset | Reject | **Google-only sign-in + QR device pairing** (§5.1). | You want email/Apple sign-in. |
| A second VPS / WireGuard mesh / hosted bastion | Reject | **Nothing.** Offsite tested backups + alerting are the real protections. | A >4-hour outage would materially hurt paying users. |

---

## 2. What the codebase already gives you

| Asset | Where | Why it matters |
|---|---|---|
| **Content-fingerprint dedup** — SHA-256 of `source\|track_id\|timestamp\|playDuration\|endTimestamp`, plus cross-source temporal reconciliation (5 s / 60 s) with `SourceAuthority` ranking | `data/local/dao/ListeningEventDao.kt`, `utils/EventFingerprint` | "The same play logged twice" — the hardest problem in multi-device music sync — is already implemented and idempotent. Needs one fix (§4.7). |
| **Natural keys + unique indexes** — `artists.normalized_name`, `albums` (title+artist), `tracks.spotify_id`/`youtube_id`/`musicbrainz_id`, `scrobbles_archive.track_hash` | `app/schemas/.../52.json` | Sync keys on content identity. **No PK migration to UUIDs.** |
| **ID remapping on merge** — `artistIdMap`/`albumIdMap`/`trackIdMap` | `data/importexport/ImportExportManager.kt` | The pattern for mapping device-local ↔ server ids. |
| **Streaming JSON + ZIP** — `data.json` v9, keyset-paged via `getMaxEventId()` | `data/importexport/TempoExportJsonCodec.kt` | Proven, versioned, memory-safe; the cold path stays forever. |
| **A working remote transport** — Drive upload/download, MD5-verified, retry + interrupted-run recovery | `data/drive/GoogleDriveService.kt`, `worker/DriveBackupWorker.kt` | Cloud backup is a transport swap. |
| **A tiered hot/cold store, already compressed** — `scrobbles_archive` keeps one row per track with delta-encoded GZIP'd timestamps (`ArchiveTimestampCodec`: 8 B base + 4 B count + **4 B per delta**) | `data/local/entities/ScrobbleArchive.kt` | The mechanism that makes a bounded device window safe (§5.5). |
| **Existing non-phone clients** — desktop app + browser extension posting to `POST /api/plays` on port 8765, HMAC-signed, **LAN-only** | `desktop/DesktopSatelliteServer.kt`, `browser-extension/` | They become first-class premium beneficiaries (§5.10). |
| **QR pairing with ECDH P-256** — `DesktopPairingManager`, `EcdhKeyExchange`, ZXing QR + CameraX | `desktop/`, `ui/desktop/` | **Reused almost as-is** to enrol cloud devices (§5.1). |
| **User-authored canonicalisation** — `track_aliases`, `artist_aliases`, `manual_content_marks` | `data/repository/TrackAliasRepository.kt`, `ArtistSplitRepository.kt` | The only rows where two devices can genuinely conflict. |

---

## 3. Blockers — two of which turned out to be non-issues

### 3.1 Licensing: **no change required**
A licence binds licensees, not the copyright holder. The "NO COMMERCIAL USE" addendum restricts forks. **Nothing is removed, closed, or made premium** — every file Épikaigle touched (`GoogleAuthManager.kt`, `GoogleDriveService.kt`, `DriveBackupWorker.kt`, `LocalBackupWorker.kt`, `ImportExportManager.kt`, `BackupRestoreViewModel.kt`) stays free and stays AGPL. Consent is only needed to *change the licence terms* governing their code or to ship a closed-source build. **Do not touch `LICENSE`; do not chase a CLA.**

**Still required:** rewrite `PRIVACY.md` + `browser-extension/PRIVACY.md`; update Play **Data safety**; add in-app **and** web-accessible account deletion.

### 3.2 Spotify — amber, one precise action
`SpotifyApi.kt` — *"Audio features endpoints removed as they are deprecated for third-party apps since Nov 2024."* `SpotifyEnrichmentService.kt:61` — *"Spotify enrichment is disabled; album art/genres fall back to iTunes, MusicBrainz, Deezer."* The only Spotify entry in Settings is the user's **own JSON export**, which §III.9 permits transferring as personal data.

**One residual:** `spotifyApiOnlyMode` → `SpotifyPollingWorker` → `SpotifyHistoryReconstructionService` still pulls data from `api.spotify.com`. **Decide its fate before shipping AI or public pages.**

### 3.3 Album art on public pages — informational only
Storing a **URL** isn't copying; the image stays on the origin server. The two *practical* reasons to default to the typographic card are (1) **link rot** — store `last_verified_at` and auto-fallback on 404; and (2) **your own share cards look better** than a Spotify screenshot.

### 3.4 Play obligations the moment accounts exist
In-app **and** web-accessible account deletion is mandatory. Data safety must declare **Email address**, **User IDs**, and **App activity**.

---

## 4. Sync architecture: server-authoritative master + local replica

### 4.1 Authority

| Actor | Authority |
|---|---|
| **Cloud (Postgres)** | **Master.** Owns canonical IDs, ordering, the world-fact catalog, tombstones. Single arbiter. |
| **Device (Room)** | A **replica with a bounded working window**. Fully functional offline. |
| **`.tempo` ZIP** | Independent cold path. **Sync must never be the only copy.** |

### 4.2 Identity — device-local integer PK meets server
Do **not** migrate primary keys. Add `server_id UUID` (nullable, unique) and `natural_key TEXT` (indexed). Events need no identity — the fingerprint makes them idempotent.

### 4.3 Two-phase entity creation (offline-safe, duplicate-proof)
1. Device creates a track offline: local row, `server_id = NULL`, `natural_key` computed, plus a `client_uuid`.
2. On push the server upserts **by `natural_key`**; if it already exists it **returns the existing `server_id` instead of inserting a duplicate**.
3. The server records `client_uuid`, so a retried create can never produce a second row.
4. The device stores the returned `server_id` and merges via `MergeEngine` if needed.

**Natural key is the dedup axis; the server is the arbiter; `client_uuid` makes retries safe.**

### 4.4 Ordering — a per-user change sequence, and yes, it's like git

The git analogy is right, with two differences that matter:

| Like git | Different from git |
|---|---|
| Each device has a full local copy; a central remote holds canonical history | **Linear, not a DAG** — the server serialises everything into one ordered log per user, so there are no branches. |
| You push your changes and pull others' | **No manual conflict resolution** — every conflict has a rule (§4.8). |
| Each side tracks a cursor, like a ref | **The server assigns canonical IDs** — devices don't mint identity. |
| Pushes are idempotent and replay-safe | **Tombstones are explicit** — absence is never interpreted as deletion. |

Every server write appends to `changes(user_id, change_seq bigserial, table_name, row_identity, op, changed_at)`. A device's cursor is *"the highest `change_seq` I have seen"* — monotonic, gap-free, immune to clock skew. **Retention:** prune only below `min(cursor)` across a user's active devices; a device returning after pruning gets **`resync_required`** and bootstraps fresh. Event `timestamp` is preserved verbatim but **never** orders a merge.

### 4.5 The three flows
**Bootstrap** — catalog slice, alias/preference state, the **recent raw-event window**, the **full compact archive**, aggregates.
**Push** — `POST /v1/sync/push`, batches of `(device_id, batch_id, seq, table, rows[])`, one transaction; returns `applied_seq` + canonical overrides. **Cursor advances only on acknowledgement.**
**Pull** — `GET /v1/sync/pull?cursor=<change_seq>`; rows changed since the cursor, **including tombstones**.

**Batching:** 500–2,000 rows/request, ~1 MB cap, gzipped. `SyncWorker` under WorkManager with network + battery constraints, exponential backoff with jitter, and the existing `wifi_only` preference.

### 4.6 The device window, and why pruning must be careful
The device keeps a **configurable window** of raw events plus the **complete** archive and aggregates; older periods are fetched on demand.

**A raw event may be pruned only if ALL hold:** (a) the server acknowledged it, (b) it's older than the window, (c) no pending outbox operation references it, (d) its archive row already accounts for it.

**Statistics caveat:** the archive preserves *counts and timestamps*, not `playDuration`, completion %, skip or replay detail. After pruning, "listening time" and LQS for old periods must come from **server-side aggregates**. Compute and verify those **before** enabling pruning.

**Backup consequence:** once pruning is on, **the server may hold the only copy of pruned raw-event detail.** Before pruning, every device was a full replica and the server could be rebuilt from clients. After pruning, it cannot be, for pruned ranges. **Portable, verified backups (§5.7) are therefore a hard prerequisite for shipping pruning.** Pruning ships off by default.

### 4.7 Concurrent and simultaneous scenarios

**There is no device-to-device conflict, by construction.** Devices never talk to each other; each reports to the master and pulls from the master, so the per-user `change_seq` guarantees every device observes **every change exactly once, in the same order**.

**Propagation:** each device pulls on app open, on a timer, and after its own successful push. For near-instant updates the API exposes **Server-Sent Events** (polling stays the fallback).

**A real bug in the existing dedup logic.** The fingerprint input is `source|track_id|timestamp|playDuration|endTimestamp`, and `source` is the **media app package** (e.g. `com.spotify.music`), **not a device identifier**. Same-source reconciliation uses a **5-second tolerance**. Therefore:

> Phone starts a song at 10:00:01, tablet starts the same song at 10:00:03 → same source, same track, within 5 s → **reconciliation deletes one.** Two real plays silently become one.

**The fix:**
1. **Add `device_id` to the fingerprint input** → `fp_version = 2`.
2. **Same-source temporal reconciliation becomes same-device only** — it exists to catch one device double-reporting, never to merge across devices.
3. **Cross-source reconciliation stays**, but only for reporters on the *same* device.
4. **Migration:** do **not** recompute stored fingerprints. Add `fp_version`; treat v1 rows as legacy and dedup conservatively (exact match only).
5. **The rule: under-dedup rather than over-dedup.** A duplicate play is cosmetic; a lost play is data loss.
6. **Test:** two devices, same track, 2 s apart → assert **two** events survive.

| Scenario | Behaviour |
|---|---|
| Same song on 2 devices, same moment | **Two events** (after the fix) — the user played it twice. |
| One device, two reporters observing one session | **One event** — same-device cross-source reconciliation. |
| Both devices rename the same artist simultaneously | Server LWW by **receipt order**; the loser gets a canonical override on next pull. |
| Both devices merge different tracks into one target | Alias sets **converge by union**. |
| Device offline 3 weeks | Pushes its backlog; pulls since its cursor; `resync_required` if the log was pruned. |
| 200 plays in a minute | Batched, applied in `seq` order; cursor advances at the end. |

### 4.8 Conflict resolution

| Data | Rule |
|---|---|
| `listening_events`, `scrobbles_archive` | Append-only, dedup by fingerprint / `track_hash`. **Cannot conflict.** |
| Catalog entities | Server canonical; client adopts `server_id` and merges via `MergeEngine`. |
| `track_aliases`, `artist_aliases`, `manual_content_marks` | Converge by **union**; server arbitrates. |
| `user_preferences`, `app_preferences` | LWW by **server receipt order**, per key. |
| Tombstones | Authoritative; a delete beats a concurrent create unless the create is later in server order. |

### 4.9 Error taxonomy

| Class | Examples | Client behaviour |
|---|---|---|
| Retryable | network, 5xx, 429 | Backoff + jitter; cursor unchanged |
| Permanent | 400 `schema_version_unsupported`, `payload_too_large`, `quota_exceeded`, `sync_disabled` | Stop; surface in the sync log; never retry-loop |
| Re-bootstrap | `resync_required` | Discard cursors; run bootstrap |
| Fatal | auth invalid | Re-auth; pause sync; local app unaffected |

### 4.10 Is `MergeEngine` actually capable of this? *(honest answer)*

**What it already does well** — per-table natural-key matching; ID remapping through `artistIdMap`/`albumIdMap`/`trackIdMap`; chunked replay (`EVENT_IMPORT_CHUNK = 5000`); all inside one `database.withTransaction`; conflict strategies (`SKIP`/`REPLACE`); single-flight guard (`ImportExportOperationGate`). The genuinely hard part — **deciding whether two plays are the same play** — isn't in `MergeEngine` at all; it lives in `ListeningEventDao.insertAllBatchedWithDedup`, **battle-tested against real Last.fm exports**.

**What it has never done:**
| Gap | Why it's new |
|---|---|
| Partial application | Today it applies a whole snapshot and owns the DB for the duration. Sync applies many small batches, repeatedly, interleaved with live writes. |
| Tombstones | No deletion concept exists — absence simply means absence. |
| Server round-trips | It never reconciled against an authority that can *override* it. |
| Adversarial ordering | Imports are one-shot; sync can deliver rows twice, out of order, or after a delete. |

**`MergeEngine` is the correct foundation, not a finished solution.** The merge *semantics* are correct and tested; the sync-*specific* machinery is new and must be proven.

**How confidence is earned — must not be skipped:**
1. **Extraction with equivalence:** pull the merge logic out behind `MergeEngine`, existing restore tests passing unchanged. If behaviour shifts, the extraction is wrong.
2. **Property-based tests** over generated data, asserting: **idempotence** (same batch N times = once); **no loss** (every input event present or deduped against a *fingerprint-equal* sibling — this test would have caught §4.7); **order independence** for append-only tables; **tombstone dominance**.
3. **Crash-injection:** kill mid-batch, mid-pull, and between push and cursor-advance; assert no duplication and no gap.
4. **The scale test:** 10-year/182k-event synthetic DB with pruning, asserting old-period stats are byte-identical.

**Risk bounds:** if `MergeEngine` is wrong in a way tests miss, failure is contained by the ZIP cold path, the server kill switch, cursors not advancing on error, and under-dedup. Worst case is "sync stops", never "data is gone."

---

## 5. Target architecture

### 5.1 Auth — Google-only + QR device pairing
Google ID token → verify against Google's JWKS (cached, rotation handled), check `aud`/`iss`/`exp` → mint a **short-lived access JWT + rotating refresh token** (hashed at rest, revocable per device). Because only Google is accepted, there are no passwords, no reset, no email verification, no MFA flows, no credential stuffing — **that's why this is safe to hand-write.**

**Desktop app + browser extension:** re-use the existing **QR pairing + ECDH P-256** machinery (`DesktopPairingManager`, `EcdhKeyExchange`, ZXing QR + CameraX). They scan a code, exchange keys, and receive a **cloud device token** bound to the user. Almost no new cryptography.

**Free users never see a login prompt.**

### 5.2 Global vs private data
**World-facts are global; person-facts are private. The join between them is private.**

| **Global catalog** (shared, deduped, **no `user_id` ever**) | **Private** (scoped to one user) |
|---|---|
| `tracks`, `artists`, `albums` — metadata only | `listening_events` — every play |
| `artwork` — **hotlink URLs**, sizes, source, `last_verified_at` | `scrobbles_archive` — that user's compressed timeline |
| `external_ids` — spotify / youtube / musicbrainz / isrc | `user_preferences`, `app_preferences` |
| `genres`, `audio_attributes`, `enriched_metadata` | `track_aliases`, `artist_aliases`, `manual_content_marks` |
| `catalog_provenance` — source, trust level, `quarantined` | aggregates, `backups`, `devices`, `entitlements` |

**What it buys:** metadata, art URLs, genres and attributes stored **once** instead of per user; a **shared enrichment cache** so user B doesn't re-hit iTunes/MusicBrainz/Deezer; far less load on those free public services; cheap public pages later.

**Guardrails:** ① no `user_id` in any shared table, ever; ② `catalog_provenance` + `quarantined` — client-pushed metadata lands quarantined, promoted by trust level; ③ **merge/split must be server-aware** — `TrackAliasRepository.mergeTracks()` must emit a catalog alias record or tracks fork; ④ never block the user on the catalog.

### 5.3 Backend language: **Go**

| | Go | Kotlin/JVM | Node/TS | Rust | Python |
|---|---|---|---|---|---|
| Memory on the box | **~20–50 MB** | ~200–400 MB | ~80–150 MB | ~10–30 MB | ~80–200 MB |
| Deploy artefact | **one static binary** | JAR + JVM | node_modules | one binary | venv |
| Restart/redeploy | **instant** | seconds (JIT warmup) | fast | instant | fast |
| Correctness for agent-written code | **explicit, few footguns** | good | weakest typing | safest, hardest to write | weak for this |
| Personal auditability (Kotlin dev) | low | **high** | medium | low | medium |

On a 4 GB box also running Postgres, a JVM idles at 5–10% of RAM and stalls every deploy. A Go static binary is the simplest thing to run under Compose.

**The one cost — paid properly:** define the wire contract **once** as an **OpenAPI document in `cloud-core/`**, and generate the Go structs and the Kotlin/Moshi DTOs from it. That preserves the anti-drift benefit without a JVM, and gives the agents a machine-checkable contract — if schema and code disagree, generation fails.

**Everything else:** VPS **2 vCPU / 4 GB / 80 GB NVMe** (~€6–12/mo); **Docker Compose** with pinned tags; **Caddy** for automatic TLS; **Postgres 18** in a container (`wal_level=replica` is enough — no logical replication); **Backblaze B2**; **Uptime Kuma** + external check; key-only SSH, non-standard port, `PermitRootLogin no`, fail2ban. **Total ~€8–15/mo.**

### 5.4 Robustness invariants

1. **Room is the source of truth for the device.** Sync merges; it never destructively rewrites local rows.
2. **Every sync operation is idempotent and resumable** — `batch_id`, `client_uuid`, fingerprints.
3. **The cloud is never the only copy** — the ZIP path and portable backups (§5.7) stay.
4. **No sync operation can corrupt local data.** All sync writes go through `database.withTransaction` + `MergeEngine`, guarded by `ImportExportOperationGate` — sync and restore can never run concurrently.
5. **A play is never lost to a sync failure.** Events are written locally first.
6. **Nothing is pruned until the server has acknowledged it** (§4.6).
7. **Under-dedup rather than over-dedup** (§4.7).

**Observability:** a local `sync_log` table surfaced in Settings so users can self-diagnose — **this replaces telemetry**. Server-side: a **kill switch** (`sync_disabled` per user/device/global) so a data-eating bug is fixed by config, not an emergency release.

### 5.5 The 10-year question
**"Should we give the user a device that loads 10 years?" — No. That's where the cloud earns its keep.**

| Question | Answer |
|---|---|
| Does SQLite cope with 10 years? | Yes — ~50 plays/day × 10 y ≈ **182,500 events ≈ ~36 MB**. Trivial. |
| 10 years of *timestamps*? | `ArchiveTimestampCodec` = **4 bytes each** → ≈ **730 KB inside one row**. |
| Aggregations | Fine — composite indexes cover the hot paths, plus the 64-entry / 5-min stats cache. |
| **The real weak point: text search** | Every search is `LIKE '%query%' COLLATE NOCASE` (`StatsDao.getHistory`, `TrackDao.searchByTitle`, `ArtistDao.search`, `ScrobbleArchiveDao.search*`). **Leading-wildcard `LIKE` cannot use an index — full scan, per keystroke.** At 182k rows that's visible jank. |
| Phone storage | Coil bounded to **50 MB / 100 MB** (`di/CoilModule.kt`); OkHttp has its own 50 MB cache. |

**Three fixes, in order:** ① **add FTS** (`@Fts4` on Room 2.x; `@Fts5` if Room 3.0 is adopted); ② **wire up the dead workers** — `StatsPrecomputeWorker` and `StatsCacheInvalidationWorker` exist in `worker/StatsWorker.kt` but **nothing in `src/main` ever calls `schedule()`/`runNow()`/`trigger()`**; ③ **`PRAGMA optimize` on close + periodic `ANALYZE`**.

**The architecture that makes history unbounded:** device keeps a bounded window, cloud holds the master, old periods fetched on demand. **Premium benefit:** free users' apps slow down as history grows; premium's doesn't.

### 5.6 Encryption boundary
Full E2EE is off the table permanently: **AI reports must read plaintext to narrate, and public pages must serve data to anonymous visitors.** The second wall is **multi-device key sharing** — with no passphrase and no server-held key, adding a device needs pairing or a passphrase.

**Design now so it stays possible where it *is* possible:** sync user-authored rows as an opaque `payload` blob with only non-PII metadata in plaintext; per-user DEK + `key_version` column from day one; server stays dumb; AI is a separate, consented, deletable step.

### 5.7 Backups — the easy-storable form

**Two layers, because they solve different problems.** WAL archiving is for *speed*; a portable dump is for *survival*.

#### Layer 1 — Operational (fast recovery)
`pgBackRest` or WAL-G with continuous archiving → B2. Enables **point-in-time recovery** for "I deleted a table 40 minutes ago". Incremental, compact, but **requires the tooling to restore**.

#### Layer 2 — Portable (the easy storable form)
A single self-contained file, restorable anywhere with no tooling beyond Postgres itself:

```bash
# Nightly: one portable file
pg_dump --format=custom --compress=9 \
        --file=/backup/tempo-$(date +%F).dump "$DATABASE_URL"

# Encrypt with a key YOU hold
age -r "$YOUR_PUBLIC_KEY" tempo-2026-09-16.dump > tempo-2026-09-16.dump.age

# Ship to TWO independent places
rclone copy tempo-2026-09-16.dump.age b2:tempo-backups/
rclone copy tempo-2026-09-16.dump.age remote2:tempo-backups/   # different vendor
```

**Restore on your own laptop, with no VPS and no original provider involved:**
```bash
docker run --rm -e POSTGRES_PASSWORD=x -p 5433:5432 -d postgres:18
pg_restore -h localhost -p 5433 -U postgres -d tempo tempo-2026-09-16.dump
```
Two commands. That is what makes it "easy storable": the file is worthless only if Postgres ceases to exist.

**Retention:** 7 daily, 4 weekly, 12 monthly, and **one copy physically off-site**.

**Automated verification — an untested backup is a file, not a backup:** a weekly job restores the newest dump into a scratch Postgres and runs a row-count assertion, alerting on failure.

#### What happens when things go wrong

| Failure | Effect on users | Recovery |
|---|---|---|
| **VPS goes down** | Almost nothing — the app is local-first. Sync pauses. | Rebuild from Compose + restore. Target **<1 hour** of sync outage. |
| **Server data corrupted / wiped by provider / provider silently disappears** | Sync stops. **No user data loss on devices** — subject to the pruning caveat below. | **Layer 2 dump into a fresh Postgres anywhere** — different host, different provider, even your laptop. |
| **Disk fills / Postgres OOM-killed** | Sync pauses; app unaffected. | Alerted *before* users notice (disk >70%, connections >80%, process down). |
| **Accidental deletion** | Sync stops; local data intact. | **PITR** from Layer 1, or last nightly dump. Soft-deletes (`deleted_at`) make most deletions reversible within a retention window. |
| **B2 unavailable** | Nothing — sync only needs Postgres. | Wait, or use the second location. |
| **Credential compromise / ransomware** | Server could be corrupted. | Immutable/versioned offsite backups limit blast radius. Devices are untouched; rebuild the master by re-pushing from clients **for unpruned ranges**. |
| **Total loss, everywhere** | Users keep local data, plus their own `.tempo` exports and Drive backups. | **Local-first means total server loss is never total user loss.** |

**The pruning interaction:** while every device is a full replica, the server is *rebuildable from clients*. Once device pruning (§4.6) is enabled, it isn't. **Ship pruning only after Layer 2 is running and verified.**

#### Three independent representations of a user's data
1. The device's Room DB (always, offline).
2. Their `.tempo` ZIP — portable, plus the Google Drive copy.
3. The server's copy — protected by Layer 1 + Layer 2.

### 5.8 The stack — and its security posture

| Layer | Choice | Monthly |
|---|---|---|
| VPS (2 vCPU / 4 GB / 80 GB) | Your provider | €6–12 |
| Deploy | Docker Compose + Caddy | €0 |
| Database | Postgres 18 (container) | €0 |
| API | **Go** (single static binary) | €0 |
| Backups + ZIP storage | Backblaze B2 (+ a second location) | ~€1–3 |
| Edge / DNS / TLS | Cloudflare Free (SSL Full *strict*) | €0 |
| Monitoring | Uptime Kuma + external check | €0 |
| AI | DeepSeek V4 Flash 0731 via OpenRouter | ~€0–3 |
| **Total** | | **≈ €8–15/mo** |

#### Security posture — what protects what

**Realistic threats, ranked.** Everything below addresses one of these, and nothing below exists for hypothetical ones:

| Threat | Likelihood here | Primary defence |
|---|---|---|
| **AI endpoint cost abuse** | **Highest** — your key, your bill | Rate limits + server-side trial counter + fail-closed spend cap (§5.11) |
| Bot scanning the VPS / brute-forcing SSH | High volume, low success | Key-only SSH, no root login, fail2ban, only 443 exposed |
| Leaked backup | Medium — the classic | Encrypted `age` files, keys you hold, two vendors |
| Unpatched dependency | Medium | Pinned digests, minimal Go stdlib surface, update cadence |
| Credential compromise | Low | Hashed rotating refresh tokens, per-device revocation |
| Subpoena / lawful request | Low but real | Minimise what is held: no PII in logs, minimal columns |
| Server-side adversary | Not in the threat model | (Why E2EE is deferred — §5.6.) |

**Network.** Only **443** reachable. Postgres is **never published to the host** — Compose-internal network only. SSH on a non-standard port, keys only, no root login, fail2ban. Caddy handles TLS with HSTS; Cloudflare in front with **SSL Full (strict)** — never Flexible. Tailscale optional later.

**Host.** Unattended security updates. Containers run **non-root**, read-only root filesystems where practical, no `docker.sock` mount. Minimal base images (`scratch`/`distroless` for Go). Docker daemon not exposed.

**Application.** Every private endpoint requires a valid token. Constant-time secret comparison. Request size caps and timeouts. Per-IP and **per-user rate limits on auth and AI** — the AI endpoint gets the tightest limits because it is the only one that costs money per call. Strict input validation against the generated DTOs. **No PII, tokens, or event payloads in logs**; auth events are logged for audit.

**Data.** Provider disk encryption or LUKS at rest. B2 buckets private, versioned, with **object lock/immutability** where available — that is what defeats ransomware. Per-service DB credentials; the API uses a least-privilege role that cannot alter the schema. Backups **encrypted with a key you hold**. Secrets in a root-owned `.env` (`chmod 600`) — never in git, never in the APK.

**Supply chain.** Pinned image digests, no `latest` tags. Go's small stdlib-based surface keeps dependency count low, which is itself a security control.

**Abuse and cost.** The AI endpoint is the one place an attacker costs real money per request. Defences: per-user quota, per-IP limit, a **global daily spend ceiling that fails closed** (friendly error rather than a bill), and alerting on unusual volume.

**Incident response.** A written runbook: rotate DB credentials and the AI key; revoke refresh tokens; use the **kill switch** to disable sync globally; restore from the newest verified dump.

**Verification.** Every control above has a test in §5.12 — especially the negative-path isolation test, which catches the most likely AI-agent mistake.

**Deliberately NOT at this scale:** a WAF product, an IDS, a SIEM, a second VPS for "security", or a bastion host.

### 5.9 Monetization — and why it's more than 2–5%

#### You're paying for three different things

| What | Typical cost | What you're buying |
|---|---|---|
| **Payment processing** | **2–4%** (Stripe 2.9% + 30¢; Razorpay ~2% + 18% GST) | Just moving the money. This is the 2–5% — and it's real. |
| **Merchant of record** | **4–7% + ~40–50¢** (Paddle, Creem, Dodo) | Someone else becomes the **legal seller**, registering and filing **VAT/GST in 100+ jurisdictions**, handling refunds and chargebacks. A tax and legal service, not a payment fee. |
| **Platform/distribution** | **15%** (Play, subscriptions) | Discovery, installs, updates, billing UI, refund handling. Not a payment fee at all. |

**These rates are actively being challenged.** The Epic v. Google injunction forced Google to allow alternative billing and external links in the US with fees around **10%** instead of 15% (reporting/fees from 1 Oct 2026), and the EEA's DMA created the External Offers program (0% acquisition, 10% ongoing). So ~10% is achievable — at the cost of more integration work and, in the EEA, giving up Play Billing entirely.

#### The Play commission, country by country — simplified

**A subscription is 15% in every country, before and after every regional change.** The country timeline only affects *one-time* purchases, which are not being sold.

| What is sold | **Today** | **After the regional rollover** |
|---|---|---|
| **Subscription (the case here)** | **15%** | **15% — unchanged** |
| One-time purchase | 30% (15% under the first-$1M tier) | 25% new / 30% existing, +5% billing fee |

Rollover dates, only if one-time items are ever sold: EEA/UK/US since 30 Jun 2026 · AU/JP 30 Sep 2026 · KR 31 Dec 2026 · rest of world 30 Sep 2027. **Budget 15%.**

#### Why Play beats the web at this price — plain numbers

One customer paying **€2/month**:

| Rail | What you keep |
|---|---|
| **Play** | €2.00 − 15% = **€1.70** |
| **Web via a merchant of record** (~5% + 50¢) | €2.00 − €0.10 − €0.50 = **€1.40** |

The web takes **30%** here, not 5%, because **50¢ is a *fixed* fee** — a quarter of a €2 sale before any percentage.

One customer paying **€15/year**: Play keeps **€12.75**; the web keeps **€13.75**. The web wins by **€1/year** on annual, and loses by **€0.30/month** on monthly. **Conclusion: sell annual, bill through Play.**

| Alternative rail | Fee | Merchant of record? |
|---|---|---|
| **Google Play** | **15%** subscriptions | Yes — 195 markets |
| Creem | 3.9% + 40¢ | Yes |
| Paddle | 5% + 50¢ | Yes |
| Polar | 5%+50¢ / 3.8%+40¢ ($20/mo plan) | Yes |
| **Razorpay / Cashfree (India)** | ~2% + 18% GST domestic; **~2.9% + GST for card recurring**; ~3% premium methods | **No — you handle the tax** |
| **Dodo Payments (Indian-founded MoR)** | ~5%+ per transaction *(verify)* | Yes |

**Do this regardless:** build entitlements source-agnostic — `entitlements(user_id, feature, source, status, expires_at)` with `source = play | razorpay | dodo`.

**Urgent if billing is added:** the **Billing Library v8 deadline has already passed** — since **31 Aug 2026** all new apps and updates must use v8+ (extension to 1 Nov 2026).

**Ask a chartered accountant (do not guess):** GST threshold for zero-rated exports of services; whether LUT/Bond applies; whether foreign customers count toward the threshold; FIRC/FIRA on inward remittances; TDS/TCS on foreign receipts; how Play payouts are treated.

### 5.10 What premium actually is

**Premium is not storage, and not "new phone migration".** Every item must pass one test: *could the phone do this alone?*

| Premium capability | Why the phone can't do it alone |
|---|---|
| **AI reports** | Real marginal cost per report. |
| **Unlimited history, fast phone** | Cloud holds the master; device keeps a bounded window (§4.6). |
| **One history across every device** | Phone on the commute, desktop at work, browser extension on a work network, tablet at home → **one complete history, current everywhere.** |
| **Desktop app + browser extension, untethered** | **Locked decision:** LAN sync stays free forever; premium routes the same payload schema to the cloud instead, so the extension works from any network. Means the **extension and desktop app need a cloud mode with a device token** — a Phase 2 scope item. |
| **Public profile at `you.wh0.im`** | Impossible locally. |
| **Metadata work offloaded** | Shared enrichment cache (§5.2). |
| **Web access** *(later)* | Needs a web surface. |

**The framing:** *"One listening history across everything you listen on — plus insights your phone can't compute."*

**What stays free, undegraded:** local tracking, all analytics, Spotlight cards, all six share themes, local backups, Google Drive backup, every importer, merge/split tools, widgets, **LAN desktop sync**, the browser extension. Premium is strictly **additive**.

### 5.11 AI — trial, model, and data flow

**No on-device inference. No login required to be a free user.**

#### The trial, and the honest answer on abuse

**Design:** **3 requests with no account**, then sign in to unlock the remaining 12 of the 15 lifetime, counted server-side per Google `sub`.

**A local counter is defeated by uninstalling.** You cannot prevent that client-side, and — this is the important part — **you shouldn't try.**

At **$0.00016 per report**, three requests cost **$0.0005**. Even 10,000 abusive reinstall cycles cost about **$5**. A login wall to stop this would cost far more in conversion than it saves in inference.

**Three no-login signals that raise the cost without inconveniencing anyone:**
1. **Android App Set ID** (Play Services) — stable across reinstalls on the same device/account, resettable by the user, designed for exactly this, no login required.
2. **Play Integrity API** — confirms the app is genuine and untampered; blocks scripted/emulator abuse without identifying anyone.
3. **A server-side per-IP daily cap on *no-account* requests** — the decisive control, because **the key is on your server, so abuse costs you directly**. Honest users never hit it; a script hits it in seconds.

**The decision, stated explicitly so it is deliberate rather than accidental:** a determined user may extract a handful of extra reports. Accept it, price it in, and monitor aggregate no-account volume — if it spikes, tighten the IP cap, which is a **config change, not a release**. Also set a **global daily spend ceiling that fails closed** (§5.8), so the worst case is a friendly error, not a surprise bill.

#### The model — one

**Primary and only: `deepseek-v4-flash-0731` via OpenRouter, with `zdr: true`.** $0.04/$0.10 per 1M tokens — the cheapest capable option verified.

| | Value |
|---|---|
| Cost per report (2,000 in / 800 out) | **~$0.00016** |
| **Full trial, all 2,500 users × 15 requests (37,500)** | **~$6.00** |
| 3,000 reports/month (plausible premium steady state) | **~$0.48** |

Keep the model ID **in config, not in code** — a provider outage, re-pricing, or retirement is then a config change.

**Note on the name:** DeepSeek's **first-party** API lists `deepseek-flash` (V4.1-Flash); on **OpenRouter** the model appears as `deepseek-v4-flash` in two GA versions (0423 and 0731), and it is cheaper than V4.1 Flash.

**Zero data retention.** OpenRouter supports ZDR enforcement per request (`provider: { zdr: true }`), and: *"Providers that do not retain your data are also unable to train on your data."* ZDR is per-**endpoint**, not per-model — OpenRouter tracks each provider's specific policy and conservatively assumes retention where it cannot establish one. The live list is queryable at `https://openrouter.ai/api/v1/endpoints/zdr`, so the server can pick a ZDR endpoint at runtime.

**One technical caveat to verify:** DeepSeek V4.1 Flash *"does not support `response_format`, so JSON output is not enforced."* **Confirm whether V4 Flash 0731 supports enforced structured output before relying on it.** If not, obtain the shape via tool-calling and rely on the numeral-grounding validator — which is non-optional regardless.

#### How AI reaches the data — the database never leaves the phone

```
User question (on device)
      ├─ computable? ──► SQL / Kotlin on Room ──► deterministic FACTS
      └─ not computable ──► model requests a TYPED CAPABILITY
                             (top_artists(range), compare_periods(a,b),
                              search_history(query, range, limit), genre_trend(...),
                              find_similar_track(id))
                                    └─► executed LOCALLY on Room ──► small result set
      ▼
Facts + question + "use only these numbers" ──► LLM (via your API; key never in the APK)
      ▼
Output (structured if supported, else tool-shaped) {sections:[{text, cited_metrics[]}]}
      ▼
Deterministic numeral-grounding validator on device — every digit must exist in FACTS
      ├─ pass ──► show          └─ fail ──► retry once ──► template narrative fallback
```

**On "the user can ask anything":** vectors over *play events* are still wrong — "what did I overplay when I was anxious in 2022?" is a *join* of completion/skip/replay patterns against genre and energy, then reasoning. SQL computes it exactly; embeddings approximate it. Vectors over **library entities** are right, and run **on-device** via `sqlite-vec` (prebuilt Android binaries from v0.1.2). The **existing FK graph** answers most "more like this" questions better — try it first.

**Why not MCP:** MCP lets an AI *host* call *external* tool servers. Here the tool server is your own app. Use native tool-calling.

### 5.12 For the implementing agents

**Put this in `AGENTS.md` at the repo root.**

**Follow existing conventions:** Hilt modules in `di/`; Retrofit + Moshi; Compose with the existing design system; strings in **all six** locale files; `./gradlew test` must pass (`CONTRIBUTION.md`).

**Hard prohibitions:**
- Never modify `ImportExportManager` semantics. **Extract `MergeEngine` first, existing tests passing unchanged.**
- Never edit an existing migration object or committed schema JSON. Migrations are **additive only**.
- Never run sync and restore concurrently — always via `ImportExportOperationGate`.
- Never prune local data the server has not acknowledged.
- Never weaken or delete a failing test to make a build pass.
- Never log PII, tokens, or event payloads.
- Never commit secrets. The AI key must never appear in the APK.

**Security-critical areas where agent-written code most often fails — each with a required test:**
| Area | Required test |
|---|---|
| **Auth token validation** | JWKS cached with rotation handled; expired / wrong-`aud` / wrong-`iss` / tampered rejected. |
| **Session handling** | Refresh tokens hashed at rest, rotating, revocable per device; replay of a used token rejected. |
| **Device pairing** | Pairing codes single-use and short-lived; device token bound to one user and revocable. |
| **User isolation** | **Negative path:** user A's token returns zero rows for user B's data, on every private table and endpoint. CI. |
| **Rate limiting** | Per-user and per-IP limits enforced on auth and AI; the global spend ceiling **fails closed**. |
| **Dedup correctness** | Two devices, same track, 2 s apart → **two events survive** (§4.7). |
| **Merge properties** | Idempotence, no-loss, order-independence, tombstone dominance (§4.10). |
| **Cursor arithmetic** | `change_seq` is 64-bit; no overflow, no gaps; `resync_required` on stale cursors. |
| **Time handling** | Never use device clocks for ordering; UTC everywhere. |
| **Pruning** | Synthetic 10-year DB proves old-period stats unchanged after pruning. |
| **Backup restorability** | Weekly automated restore of the newest dump into a scratch Postgres, with a row-count assertion. |
| **Secret exposure** | Release-build check that the AI key and DB credentials are absent from the APK. |

**Definition of done per change:** one vertical slice; tests written with the code; full suite green; migration + committed schema JSON; a `sync_log` entry; a stated rollback path.

---

## 6. Phased implementation

### Phase 0 — Unblock (~a day)
1. ~~LICENSE / CLA~~ — **not required** (§3.1).
2. Rewrite `PRIVACY.md` + `browser-extension/PRIVACY.md`; draft Play Data safety; plan account deletion. **Name the AI subprocessors here** (§5.11).
3. Decide the fate of the Spotify API/polling path (§3.2).
4. Write `AGENTS.md` from §5.12.

### Phase 1 — Accounts + Cloud Backup *(ships "Cloud Sync")*
**Server:** VPS; Compose stack (Postgres, Go API, Caddy, Uptime Kuma); migrations; Google ID-token verification + session issuing; B2 bucket for `.tempo` ZIPs; `DELETE /v1/account`; **both backup layers (§5.7) with the weekly restore-verification job**.
**Contract:** OpenAPI spec in `cloud-core/` → generated Go structs + Kotlin/Moshi DTOs.
**Android — add:** `data/cloud/` (`TempoCloudApi`, `CloudAuthManager`, `CloudBackupRepository`, `CloudBackupWorker` mirroring `DriveBackupWorker` + `BackupRunState`), `data/premium/EntitlementRepository`, `ui/settings/CloudAccountScreen.kt`.
**Android — modify:** `BackupRestoreScreen.kt` / `BackupRestoreViewModel.kt` (add a "Tempo Cloud" section beside Google Drive), `di/NetworkModule.kt`, `app/build.gradle.kts`.
**`ImportExportManager` is not touched.**

### Phase 2 — Multi-device sync + the global catalog
**First:** extract **`MergeEngine`** with behavioural-equivalence tests, then the **property-based tests** (§4.10).
**Then the fingerprint fix (§4.7)** — a correctness bug, not a feature.
**Schema v53 (additive):** `server_id`, `natural_key`, `fp_version`; `sync_cursors`, `sync_tombstones`, `sync_log`.
**Server:** global catalog with `catalog_provenance`/`quarantined`; the `changes` log; canonical overrides.
**Cloud ingestion for the desktop app + browser extension** (§5.10) — same payload schema, device tokens instead of LAN HMAC.
**Cheap wins:** FTS for search; wire up the dead `StatsPrecomputeWorker`/`StatsCacheInvalidationWorker`.

### Phase 3 — AI reports
Start with the no-account trial. Build the §5.11 pipeline. Verify structured-output support for the chosen model first. Add the on-device `sqlite-vec` index for fuzzy recall. Exclude Spotify-Platform-derived data (§3.2).

### Phase 4 — `wh0.im` public profiles
- **Wildcard DNS + Universal SSL covers `*.wh0.im` free.** Cloudflare for SaaS is *not* needed. Do not use Cloudflare Pages (no wildcard custom domains).
- Serving: Worker route `*.wh0.im/*` + D1 for the projection + R2 for assets (zero egress is ideal *here*). Free tier covers ~3M page views/month.
- App Links: `android:host="*.wh0.im"` with `assetlinks.json` at **the apex**. Ship a canonical `https://wh0.im/{handle}` fallback and a `tempo://` fallback. Re-verification can take up to 7 days.
- Art: hotlinked, attributed, opt-in, automatic typographic fallback on 404.
- Controls: opt-in, off by default, `noindex`, recent plays hidden, handle reservation, impersonation report path, and a **purge path including the CDN cache**.

---

## 7. Verification

**Every phase:** `./gradlew test` passes.

- **Phase 1 round trip:** export → upload → download → import; counts and fingerprint sets match; a second import is a **no-op**.
- **Phase 1 auth + isolation:** token vectors (valid / expired / wrong `aud` / wrong `iss` / tampered); **user A cannot read user B's data on any endpoint**.
- **Phase 1 backup:** the **weekly restore-verification job** restores the newest dump into a scratch Postgres and asserts row counts; a deliberately corrupted dump makes the job fail loudly.
- **Phase 2 simultaneous play:** two devices, same track, 2 s apart → **two events survive**.
- **Phase 2 merge properties:** idempotence, no-loss, order-independence, tombstone dominance.
- **Phase 2 two-device merge:** airplane mode both, overlapping plays, reconnect → **zero duplicates**; same delta twice is idempotent.
- **Phase 2 offline entity creation:** both devices create the same artist offline → **exactly one** catalog artist.
- **Phase 2 propagation:** a play on A appears on B **exactly once**, same relative order.
- **Phase 2 deletes:** gone on B, driven by tombstones.
- **Phase 2 interruption:** kill mid-batch → cursor unchanged; re-send produces no duplicates.
- **Phase 2 stale cursor:** force pruning past a cursor → `resync_required` → clean bootstrap.
- **Phase 2 catalog integrity:** no shared table carries a `user_id`; quarantined rows cannot reach another user.
- **Phase 2 cloud ingestion:** the browser extension posts from a foreign network → the play appears on phone and desktop, exactly once.
- **Scale test:** 10-year / 182k-event DB — search and top-N stats within budget, **and pruning leaves old-period stats unchanged**.
- **Rate limits / spend ceiling:** the AI endpoint refuses beyond quota and the global ceiling fails closed with a friendly error.
- **Phase 3:** golden-set eval over ~50 fixture profiles — **zero unsupported numerals**; adversarial prompts yield a template fallback, not a fabricated number.

---

## 8. Risks, ranked

1. **Silent data loss in sync.** Mitigated by §4's protocol, §4.10's property tests, the seven invariants (§5.4), tombstones, `resync_required`, under-dedup, the kill switch, and the ZIP cold path.
2. **The dedup bug in §4.7** — it exists *today* and becomes data loss with a second device. Fix first.
3. **The pruning/statistics trap** (§4.6) plus its backup consequence. **Ship pruning only after the portable backup layer is running and verified.**
4. **AI endpoint cost abuse** — the most likely real-world security incident. Bounded by rate limits, the server-side counter, and a **fail-closed spend ceiling**.
5. **Hand-written auth** — the price of the own-VPS route, bounded by Google-only sign-in and §5.12's tests. **Review this code personally.**
6. **Trust reversal** — the app's identity is "no servers". Premium must be opt-in, reversible, additive, and must never degrade the free app. **No login for free users, ever.**
7. **Catalog quality/abuse** — provenance + quarantine from day one.
8. **Merge/split vs catalog divergence** — client merges must emit catalog aliases or tracks fork.

---

## 9. Sources

- [OpenRouter DeepSeek V4.1 Flash (provider list)](https://openrouter.ai/deepseek/deepseek-v4.1-flash) · [OpenRouter ZDR guide](https://openrouter.ai/docs/guides/features/zdr) · [OpenRouter ZDR endpoint list (API)](https://openrouter.ai/api/v1/endpoints/zdr) · [OpenRouter pricing](https://openrouter.ai/pricing) · [DeepSeek first-party API pricing](https://api-docs.deepseek.com/quick_start/pricing) · [Z.AI (GLM) pricing](https://docs.z.ai/guides/overview/pricing) · [Alibaba Model Studio pricing](https://www.alibabacloud.com/help/en/model-studio/model-pricing)
- [Play service fees](https://support.google.com/googleplay/android-developer/answer/112622) · [Lower service fees](https://support.google.com/googleplay/android-developer/answer/16954621) · [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738) · [Billing Library deprecation FAQ](https://developer.android.com/google/play/billing/deprecation-faq) · [US external content links](https://support.google.com/googleplay/android-developer/answer/16470497) · [EEA external offers](https://support.google.com/googleplay/android-developer/answer/14372887) · [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) · [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Razorpay pricing](https://razorpay.com/blog/razorpay-payment-gateway-pricing-explained/) · [Razorpay recurring fees](https://www.rebounce.dev/blog/razorpay-saas-guide) · [GST on export of services](https://razorpay.com/blog/gst-export-services-india-guide/) · [Creem pricing](https://www.creem.io/pricing) · [Paddle pricing](https://www.paddle.com/pricing) · [Merchant-of-record comparison](https://freemius.com/blog/best-merchant-of-record-software-developers/)
- [PowerSync licensing](https://powersync.com/legal/licensing-terms) · [PowerSync Room integration (beta, requires Room 3.0)](https://docs.powersync.com/client-sdks/orms/kotlin/room) · [Room 3.0 release notes](https://developer.android.com/jetpack/androidx/releases/room3) · [Electric writes guide](https://electric.ax/docs/guides/writes) · [Zero self-hosting](https://zero.rocicorp.dev/docs/self-host)
- [sqlite-vec on Android/iOS](https://alexgarcia.xyz/sqlite-vec/android-ios.html) · [Cloudflare R2 limits](https://developers.cloudflare.com/r2/platform/limits/) · [Backblaze B2 pricing](https://www.backblaze.com/cloud-storage/pricing) · [Cloudflare wildcard DNS](https://developers.cloudflare.com/dns/manage-dns-records/reference/wildcard-dns-records/) · [Cloudflare for SaaS plans](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/plans/) · [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/) · [Universal SSL](https://developers.cloudflare.com/ssl/edge-certificates/universal-ssl/enable-universal-ssl/) · [Coolify CVE analysis](https://wz-it.com/en/blog/coolify-cve-security-vulnerabilities-update-2025-2026/)
- [Android App Links (wildcard hosts)](https://developer.android.com/training/app-links/add-applinks) · [Verify App Links](https://developer.android.com/training/app-links/verify-applinks) · [Play Integrity API](https://developer.android.com/google/play/integrity) · [App Set ID](https://developer.android.com/identity/app-set-id) · [Spotify Developer Policy](https://developer.spotify.com/policy) · [AGPLv3 §13](https://www.gnu.org/licenses/agpl-3.0.html) · [MCP architecture](https://modelcontextprotocol.io/docs/learn/architecture)
