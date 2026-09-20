# What Tempo collects

Tempo sends anonymous app-health statistics: crashes, errors, and which features get used. It never sends what you listened to. This page lists every event it can send.

Source of truth is `app/src/main/java/me/avinas/tempo/data/analytics/AnalyticsEvent.kt`. If that file and this page disagree, the file is right and this page is a bug.

To turn reporting off, open Settings → Your Data → Anonymous app-health stats. Turning it off stops all reporting at once and deletes anything buffered but not yet sent. To see the same list in the app, open Settings → Your Data → What we collect.

## How anonymity works

Tempo runs no server of its own and has no accounts or login.

Tempo never sets a user ID. The only session value is random, kept in memory, regenerated on every start and rotated after an hour of inactivity. It is never written to disk, so it cannot become a device ID. Tempo collects no advertising ID, no `ANDROID_ID`, and does no fingerprinting.

Reporting works over any connection. Each event is a few hundred bytes, about 16 KB on a busy day. Waiting for Wi-Fi would silence everyone on mobile data only, so Tempo does not wait for it.

Full policy: [PRIVACY.md](../PRIVACY.md).

## What is sent

Numbers go out as ranges, not exact figures. Counts use `0`, `1-5`, `6-25`, `26-100`, `100+`. Durations use `<1s`, `1-5s`, `5-30s`, `30s+`. Sizes use `0`, `<1MB`, `1-10MB`, `10-100MB`, `100MB+`. Ranges avoid fingerprinting and keep dashboards stable as libraries grow.

Everything else is a fixed name from a closed list. There is no free-text field a track title could pass through. The exceptions are small `0`/`1` flags, the app version string, and the two database version numbers in `db_migration`.

| Event | What it tells us |
|---|---|
| `app_started` | Startup health: cold or warm start, startup time range, whether music detection was ready |
| `screen_viewed` | Which screens are reached, such as Home, Stats, History, Settings |
| `feature_used` | Which features get used, such as share cards, imports, backups |
| `onboarding_step` | Where setup stalls: step name, next/skip/back, time range on step |
| `onboarding_completed` | Setup completion: skipped-step range, total time range |
| `notif_access` | Notification access result and whether it was granted during onboarding or in Settings |
| `battery_exemption` | Battery-optimisation exemption result |
| `tracking_source` | Active detection path: notification, Spotify API, desktop, import |
| `tracking_gap` | When music detection stopped, why, and for how long (range) |
| `service_revived` | Whether Tempo restarted itself after the system stopped it, and how |
| `listening_activity` | Daily totals only, never per track: listen-count range, app-count range |
| `db_migration` | On-device database schema moved from one version to another |
| `import_run` | Import result for Last.fm, Spotify, YouTube Music, or Tempo backup: phase, record range, failure category, time range |
| `enrichment_run` | Metadata lookup health per provider: success and failure counts in ranges |
| `backup_run` | Backup result for local or Drive: success flag, size and time ranges |
| `crash` | Fatal crash signature: obfuscated class name, one obfuscated line, app version |

Failure reasons use a closed list: `IO`, `PARSE`, `NETWORK`, `DATABASE`, `PERMISSION`, `OUT_OF_MEMORY`, `CANCELLED`, `UNKNOWN`. Never an error message or stack trace.

## Crash reports

Error messages often echo the notification text Tempo just parsed. That text can contain listening data, so Tempo leaves messages out entirely. A crash sends three fields and nothing else:

```
crash_class   obfuscated class name, e.g. "a.b.c"
top_frame     obfuscated method + line, e.g. "d(SourceFile:412)"
app_version   e.g. "4.8.8"
```

The format is checked before the event can exist. Text that does not match the pattern is rejected, so a song title cannot fit through this path.

## What is never sent

- Track, artist, album, or playlist names, not even hashed.
- Search queries, notification content, file paths, listening timestamps.
- Account details, device identifiers, advertising ID.
- No advertising or attribution SDK, no Google Analytics, no Firebase.
- No session replay, no screen recording, no tap maps.

## Diagnostics report

Separate from automatic reporting, Tempo can build a diagnostics report at Settings → Your Data → Data & diagnostics.

It covers build and database versions, library counts, metadata-enrichment status, music detection health, and background work states. It appears on screen so you can read it first. It leaves your device only if you share it, for example by attaching it to a bug report.

Because you start it and review it, it can carry more detail than the automatic events. It still has no track, artist, or album names, no file paths, and no account or device identifiers.

## Where it goes

[Aptabase](https://aptabase.com), in the EU region. Aptabase is open source and its SDKs send no identifiers. On receipt the server derives a temporary hash from IP plus user agent against a salt that rotates every 24 hours. Old salts are purged on a schedule, so events cannot be linked across days. Events older than 24 hours are rejected.

Caveat: that same IP produces a coarse country/region stored with the event. It is not precise and is not linked to anything else about you. Stating it here matters more than claiming no location data at all.

## Builds from source

Builds compiled from source send nothing. The reporting key is not committed to the repository, so a self-built copy has reporting inert. Debug builds never report either.

## Questions

- Full policy: [PRIVACY.md](../PRIVACY.md)
- Contact: hi@avinash.im
- In the app: Settings → Your Data for the toggle, the event list, and the diagnostics report

> Contributing code that touches analytics? See [CONTRIBUTION.md](../CONTRIBUTION.md). Events use a closed schema. Put anything you need for debugging in the user-initiated diagnostics report, not in the automatic events.
