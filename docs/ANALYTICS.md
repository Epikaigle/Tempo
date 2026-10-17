# What Tempo collects

Tempo sends anonymous app-health statistics: crashes, errors, and which features get used. It never sends what you listened to. This page lists every event it can send.

Source of truth is `app/src/main/java/me/avinas/tempo/data/analytics/AnalyticsEvent.kt`. If that file and this page disagree, the file is right and this page is a bug.

To turn reporting off, open Settings → Your Data → Anonymous app-health stats. Turning it off stops all reporting at once and deletes anything buffered but not yet sent. To see the same list in the app, open Settings → Your Data → What we collect.

## How anonymity works

Tempo has no accounts and no login, and your listening history never leaves the device.

The only identifier involved is a **random install id**: a value the app generates once, keeps
on your device, and sends only as the routing key for its own statistics. It is not your
advertising ID, not `ANDROID_ID`, and not derived from anything about your device or you, so it
cannot be matched against another app — and there is nothing to match it to, because Tempo has
no accounts. Its one job is making "how many people used Tempo today, and did they come back"
answerable at all. Without a value that survives across days, one person opening the app thirty
times and thirty people opening it once are the same number.

You can replace it at any time in Settings → Your Data → **Reset analytics ID**. Turning
reporting off replaces it too, so everything recorded before becomes unlinkable to anything
after.

Tempo collects no advertising ID, no `ANDROID_ID`, and does no fingerprinting. Events are sent
as anonymous: the analytics service is told not to create a user profile, so no user record is
stored for you, and Tempo's analytics project is configured to discard the client IP address, so
no country or region is derived or kept.

Reporting works over any connection. Each event is a few hundred bytes, about 16 KB on a busy
day. Waiting for Wi-Fi would silence everyone on mobile data only, so Tempo does not wait for it.

Full policy: [PRIVACY.md](../PRIVACY.md).

## What is sent

Numbers go out as ranges, not exact figures. Counts use `0`, `1-5`, `6-25`, `26-100`, `100+`. Durations use `<1s`, `1-5s`, `5-30s`, `30s+`. Sizes use `0`, `<1MB`, `1-10MB`, `10-100MB`, `100MB+`. Ranges avoid fingerprinting and keep dashboards stable as libraries grow.

Everything else is a fixed name from a closed list. There is no free-text field a track title could pass through. The exceptions are small `0`/`1` flags, the app version string, and the two database version numbers in `db_migration`.

| Event | What it tells us |
|---|---|
| `app_started` | Startup health: cold or warm start, startup time range, whether music detection was ready |
| `screen_viewed` | Detail-screen visits only (song, artist, album) — all other screens cost zero events |
| `feature_used` | Which features get used, such as share cards, imports, backups |
| `onboarding_step` | Where setup stalls: step name, next/skip/back, time range on step |
| `onboarding_completed` | Setup completion: skipped-step range, total time range |
| `notif_access` | Notification access result and whether it was granted during onboarding or in Settings |
| `battery_exemption` | Battery-optimisation exemption result |
| `daily_activity` | That Tempo was used at all today, and whether music detection was already down when it was. Once a day, and the only thing "daily active users" is counted from |
| `tracking_health` | That music detection stopped and Tempo brought it back: how long it had been down, what triggered the check, and how forceful the recovery was. Once a day at most, however many times it happened |
| `listening_activity` | Daily totals only, never per track: listen-count range, app-count range, and whether the app's own screens were opened that day |
| `db_migration` | On-device database schema moved from one version to another |
| `import_run` | Import result for Last.fm, Spotify, YouTube Music, the browser-extension export, or Tempo backup: phase, record range, failure category, time range |
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
- Account details, advertising ID, `ANDROID_ID`, or any hardware identifier.
- Your IP address, and therefore your country or region. The analytics project is configured to discard it rather than derive location from it.
- No advertising or attribution SDK, no Google Analytics, no Firebase.
- No session replay, no screen recording, no tap maps.

## Diagnostics report

Separate from automatic reporting, Tempo can build a diagnostics report at Settings → Your Data → Data & diagnostics.

It covers build and database versions, library counts, metadata-enrichment status, music detection health, and background work states. It appears on screen so you can read it first. It leaves your device only if you share it, for example by attaching it to a bug report.

Because you start it and review it, it can carry more detail than the automatic events. It still has no track, artist, or album names, no file paths, and no account or device identifiers.

## Where it goes

[PostHog](https://posthog.com), on EU infrastructure in Frankfurt. PostHog is open source and does not sell data. Two project settings matter here and both are deliberate:

- **Person profiles are off.** Every event is sent with `$process_person_profile = false`, so no user record is created and there is nothing to look a person up by.
- **Client IP addresses are discarded**, so no country or region is derived or stored.

Events are sent either straight to PostHog or through a small relay Tempo operates, depending on how the build is configured. The relay holds the PostHog token so it never ships inside the app, forces the anonymous flag so a bug cannot create a profile, drops reserved properties, and refuses any event not in the published list above. Its source is in [`deploy/analytics-relay`](../deploy/analytics-relay), so the filtering is auditable.

There is no longer a location caveat to disclose: the previous backend derived a coarse country and region from the request IP and stored it, and that is exactly what this setup removes.

## Builds from source

Builds compiled from source send nothing. The analytics endpoint is not committed to the repository, so a self-built copy has reporting inert. Debug builds never report either.

To point a build at your own analytics, set `ANALYTICS_ENDPOINT` and `ANALYTICS_TOKEN` in
`local.properties`. See [`deploy/analytics-relay`](../deploy/analytics-relay) for the relay.

## Questions

- Full policy: [PRIVACY.md](../PRIVACY.md)
- Contact: hi@avinash.im
- In the app: Settings → Your Data for the toggle, the event list, and the diagnostics report

> Contributing code that touches analytics? See [CONTRIBUTION.md](../CONTRIBUTION.md). Events use a closed schema. Put anything you need for debugging in the user-initiated diagnostics report, not in the automatic events.
