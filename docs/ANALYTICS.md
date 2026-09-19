# What Tempo collects

This is the complete list of what Tempo's anonymous app-health reporting sends, how it works,
and how to operate it. It is generated from
`app/src/main/java/me/avinas/tempo/data/analytics/AnalyticsEvent.kt` — if this document and
that file ever disagree, the file is correct and this document is a bug.

## The four promises

1. **No account, no login.** Tempo has no servers of its own and no user accounts.
2. **No identifiers.** We never set a user ID. The only client-side value is a `sessionId`
   of `epochSeconds + 8 random digits`, held **in memory only**, regenerated on every
   process start and rotated after an hour of inactivity. It is never written to disk, so it
   cannot become a device ID. No advertising ID, no `ANDROID_ID`, no fingerprinting.
3. **Never your listening data.** No track, artist, album or playlist names, no search
   queries, no notification content, no file paths, no play timestamps.
4. **Off in one tap.** Settings → Your Data → *Anonymous app-health stats*. Turning it off
   immediately deletes anything buffered but not yet sent.

Reporting also waits for **unmetered (Wi-Fi) networks**, so it never spends your mobile data.

## What we do send

Every property is either an enum name or a bucketed range. Numbers are bucketed on purpose,
both to avoid fingerprinting and to keep the data stable as a library grows. Counts are
bucketed as `0`, `1-5`, `6-25`, `26-100`, `100+`; durations as `<1s`, `1-5s`, `5-30s`, `30s+`;
sizes as `0`, `<1MB`, `1-10MB`, `10-100MB`, `100MB+`.

| Event | What it tells us | Properties |
|---|---|---|
| `app_started` | Startup health | `start_type` (cold/warm), `startup` (bucket), `listener_ready` |
| `screen_viewed` | Which screens are reached | `screen` (enum) |
| `feature_used` | Which features are actually used — and which are not | `feature` (enum of 22) |
| `onboarding_step` | Where people drop out of setup | `step`, `action` (next/skip/back), `dur` |
| `onboarding_completed` | Setup funnel completion | `skipped` (bucket), `total_dur` (bucket) |
| `notif_access` | Whether notification access was granted | `result`, `via` |
| `battery_exemption` | Whether battery optimisation was exempted | `result` |
| `tracking_source` | Which detection path is active | `source` (notification/spotify_api/desktop/import) |
| `tracking_gap` | **The most important one.** When and why tracking stopped | `reason` (enum), `gap` (bucket) |
| `service_revived` | Whether the tracker self-heals after being killed | `by`, `recovery` (enum) |
| `listening_activity` | Daily aggregate only — never per-track | `listens` (bucket), `apps` (bucket) |
| `db_migration` | That the on-disk schema version advanced, and from where | `from_v`, `to_v` |
| `import_run` | Whether Last.fm / Spotify / YouTube Music imports worked | `provider`, `phase`, `records`, `dur`, `error_class` |
| `enrichment_run` | Whether metadata lookups are failing | `provider`, `ok`, `failed` |
| `backup_run` | Whether backups succeed | `target`, `ok`, `size`, `dur` |
| `crash` | Fatal crashes Play Console misses | `crash_class`, `top_frame`, `app_version` |

`error_class` is a closed enum (`IO`, `PARSE`, `NETWORK`, `DATABASE`, …). Never a message or a stack trace.

## The `crash` event in detail

Exception messages routinely embed the notification text Tempo parsed, which is exactly the
listening data we promise not to collect. So a crash is reduced to:

```
crash_class   obfuscated class name, e.g. "a.b.c"        (max 64 chars)
top_frame     obfuscated method + line, e.g. "d(SourceFile:412)"
app_version   e.g. "4.8.8"
```

Both fields are validated against a strict pattern at construction, which makes it
structurally impossible to smuggle free text into a crash report — a title like
`Never Gonna Give You Up` is rejected before the event can exist.

Release builds are obfuscated by R8, so these frames are read back locally against the
`mapping.txt` archived for that exact version via `./gradlew archiveReleaseMapping`.
That task is why the crash event is useful at all; without the mapping file you would have
to fall back to Play Console Android vitals.

## The diagnostics report

The counterpart to everything above. Rather than transmitting more detail to be useful, Tempo
shows the detail to you and lets you decide: **Settings → Your Data → Diagnostics report**.

It summarises build and database versions, library counts, metadata-enrichment status,
tracking health (notification access, listener connection, heartbeat age, battery exemption)
and the state of every scheduled background worker. It is rendered on screen, selectable, and
shareable as plain text.

Nothing is uploaded to produce it. It leaves the device only if you explicitly share it. That
is why it can carry far more detail than the anonymous events — and why it is the most useful
thing to attach to a bug report.

It still contains no track, artist or album names, no file paths and no account or device
identifiers. That is not a rule anyone has to remember: `DiagnosticsInput` has no field that
could carry them, so the guarantee is structural. `DiagnosticsReportTest` asserts the report's
own text states this.

## Where it goes

Aptabase, in the **EU region** (`A-EU-*` app keys). Aptabase is open source, and its SDKs
send no identifiers — on ingest the server derives a temporary hash from IP + user agent
against a salt that **rotates every 24 hours**, with old salts purged by a scheduled job, so
sessions cannot be correlated across days or apps. Events are rejected if older than 24h.

**One honest caveat:** that same IP is used to derive a **coarse country/region**, which is
stored on the event. It is the only location-ish datum collected, it is not precise, and it
is not linked to anything else about you — but claiming "we collect nothing about where you
are" would be false.

The host is a `BuildConfig` field (`APTABASE_HOST`), so Tempo can move to a self-hosted
instance on its own infrastructure without any client rewrite.

## What you will never see in the code

- No advertising or attribution SDK, no advertising ID, no Google Analytics, no Firebase.
- No session replay, no screen recording, no tap heatmaps.
- No per-track or per-artist events, ever — not even "anonymously hashed".
- No tracking in debug builds, and none in builds compiled from source.

## How it works

| Piece | Responsibility |
|---|---|
| `data/analytics/AnalyticsEvent.kt` | The closed schema. Sealed hierarchy; properties are enum names or bucket calls. |
| `AnalyticsTracker` | The only interface call sites touch. `track`, `flush`, `purge`. |
| `NoOpAnalyticsTracker` | Bound when the build cannot report. Discards everything. |
| `AptabaseClient` | The transport. Plain `POST {host}/api/v0/events`, its own `OkHttpClient` with no interceptors. |
| `AnalyticsGate` | Pure consent logic — the decisions, separated from Android so they are testable. |
| `AnalyticsConsent` | The two DataStore flags: enabled, and disclosure seen. |
| `AnalyticsQueueStore` | File-backed queue: 24h TTL, 25 per batch, 200 stored. `purge()` deletes it. |
| `AnalyticsSession` | Mints the in-memory session id and rotates it. |
| `AptabasePayload` | Serialises the request body with an explicit `JsonWriter`. |
| `AnalyticsFlushWorker` | Periodic upload, constrained to unmetered networks. |
| `CrashSignature` / `CrashSignatureRecorder` | Reduce an uncaught exception to a signature and report it next launch. |
| `FailureClassifier` | Maps a throwable onto the closed `FailureClass` taxonomy. |
| `AnalyticsEntryPoint` | Reaches the tracker from the handful of places with no injection point. |

**Two independent gates**, both checked at enqueue time *and* again at flush time:

- **Build gate** — an `APTABASE_APP_KEY` must be present and the build must not be debug.
  Fails closed: no key, no reporting, and no analytics UI is even shown.
- **Consent gate** — the user must be opted in *and* the Home notice must have been rendered.
  Also fails closed: if the disclosure never renders, nothing is ever collected.

`AnalyticsModule` binds `NoOp` when the build gate fails, and `AptabaseClient` re-checks both
gates before every send, so even a mis-wired dependency graph cannot transmit from a build
that should stay silent.

## Building from source

The Aptabase key lives in the uncommitted `local.properties`:

```properties
APTABASE_APP_KEY=A-EU-0000000000
```

Leave it blank and the build reports nothing at all — no key, no reporting, no analytics UI.
That is the default for anyone compiling Tempo themselves.

## Operating it

**Going live.** Set `APTABASE_APP_KEY` in `local.properties` to the `A-EU-*` key from the
Aptabase dashboard. Debug builds still stay silent, so verify in a release build.

**Changing the consent model.** `AnalyticsDefaults.ENABLED` in `AnalyticsConsent.kt` is the
single switch. Flipping it to `false` requires an explicit opt-in; nothing else needs to
change, because `AnalyticsGate` and the UI already handle both modes.

**Archiving release mappings.** Run `./gradlew assembleRelease archiveReleaseMapping` before
shipping and keep the output. `mappings/` is gitignored and the file is large (roughly 300 MB),
so copy it somewhere durable — without it, release crash signatures cannot be read back and
you would have to rely on Play Console.

**Crash-rate alerting.** `.github/workflows/play-crash-rate.yml` queries the Play Developer
Reporting API daily and opens (or comments on) an issue when the user-perceived crash rate
exceeds `MAX_ACCEPTABLE_RATE`. Needs a Google Cloud service account with the *Play Developer
Reporting API* enabled, invited in Play Console with *View app information (read-only)*, and
its JSON key stored as the `PLAY_SERVICE_ACCOUNT_JSON` repository secret. Without the secret
the run exits quietly rather than failing. To exercise the logic without credentials:

```bash
CRASH_RESPONSE_FIXTURE=response.json python3 .github/scripts/play_crash_rate.py
```

**Translations.** Consent and diagnostics copy lives in `values-*/strings_transparency.xml`.
If you change a string that makes a privacy claim, update every locale or remove the stale
translation — falling back to English is better than telling users something untrue.

**Play Console.** The Data Safety form must declare app-activity collection: not linked to
identity, users can opt out. It is not optional paperwork — it is the counterpart to the
in-app disclosure.

## For maintainers

Two files keep this honest, and both are enforced by tests rather than by review:

- `data/analytics/AnalyticsEvent.kt` — the only place events can be defined. New events are
  written as data classes whose properties are enums or bucket calls.
- `AnalyticsSchemaTest` — asserts that no property key names listening content or an
  identifier, that keys stay under the 40-character server limit, and that values are only
  `String` or `Int`. Add any new event to `AnalyticsEventSamples` or it ships unchecked.

Each new event also needs a matching `AnalyticsCatalog` entry; `AnalyticsCatalogTest` fails if
the published list and the real events drift apart in either direction.

### Rules the schema has already been broken by

Every one of these was shipped and then caught. They are worth stating because each looked
fine at the time:

- **No property may be a constant.** `service_revived` originally carried an `attemptCount`
  of 1, 2 or 3 — but counts are bucketed to `1-5`, so every value collapsed together and the
  property carried no information. It became a `RecoveryAction` enum.
- **No property may be unpopulatable.** `OnboardingCompleted.restored` and
  `DbMigration.success` could not be supplied truthfully by any caller, so they would have
  shipped as constants. Both were removed rather than faked.
- **No enum value without a real user action.** `STATS_EXPORT` had no distinct implementation
  (no save-to-gallery path exists), and `DAILY_CHALLENGE` had no user action at all —
  challenges generate and complete automatically. Both were removed. Verify an action exists
  before adding a value, or the published list promises data that can never arrive.
- **Never report per keystroke.** `HistoryViewModel.onSearchQueryChanged` fires on every
  character; the debounce is on the database reload, not the call. `HISTORY_FILTER` hooks the
  filter sheet and view-mode toggle instead.
- **Gate on the thing you actually mean.** `MOOD_ANALYSIS` fires only when a mood was really
  derived and not on the quiet reload, and `DESKTOP_LINK` only on a completed pairing — not
  on opening a screen or starting a scan.
- **Match strategy→provider by type, not by display name.** `EnrichmentWorker` maps its
  sources with `is` checks so renaming a source breaks the build instead of silently
  mis-attributing every outcome.

`AnalyticsGateTest` covers the consent gate, including the fail-safe that matters most: if
the disclosure notice never renders, nothing is collected.
