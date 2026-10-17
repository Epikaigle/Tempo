# Tempo analytics relay

A small Cloudflare Worker that sits between the Tempo app and PostHog. It is optional — the app
can post to PostHog directly — but there are four reasons to run it.

**It hides the PostHog project token.** The app only ever knows this Worker's own app token, so
the project token never ships inside the APK where anyone can pull it out.

**It can stop an event immediately.** Once an event name is removed from `ALLOWED_EVENTS`, it
stops being stored, with no app release and no waiting for Android update adoption. This is not
theoretical: Tempo once shipped an enrichment event that fired once per provider per run, so a
single track could emit eight of them, and it was 30% of all traffic. Deleting it from the app
did nothing for months, because devices on older builds kept sending it. The relay is what makes
that class of mistake fixable in a minute.

**It enforces anonymity rather than trusting it.** `$process_person_profile` is set to `false`
in the Worker, unconditionally. A buggy build cannot create person profiles.

**It drops what should never leave.** Reserved PostHog properties outside a small allow list,
device model on anything but a crash, oversized values, and oversized batches. It also
rate-limits bursts per install.

## Deploy

```sh
cd deploy/analytics-relay
npm install -g wrangler          # if you do not have it
wrangler login

# Generate two strong, different values
openssl rand -hex 32             # APP_TOKEN        — the app authenticates with this
openssl rand -hex 32             # POSTHOG_API_KEY  — your PostHog *project* token

wrangler secret put APP_TOKEN
wrangler secret put POSTHOG_API_KEY

wrangler deploy
```

`wrangler deploy` prints the URL, e.g. `https://tempo-analytics-relay.<you>.workers.dev`.

Check it is alive:

```sh
curl https://tempo-analytics-relay.<you>.workers.dev/health
# {"ok":true}
```

## Point the app at it

In `local.properties` (uncommitted):

```properties
ANALYTICS_ENDPOINT=https://tempo-analytics-relay.<you>.workers.dev/ingest
ANALYTICS_TOKEN=<the APP_TOKEN you generated>
```

`ANALYTICS_ENDPOINT` must be the **full URL**, including `/ingest`. To talk to PostHog directly
instead, use `https://eu.i.posthog.com/batch/` and set `ANALYTICS_TOKEN` to the project token —
at the cost of shipping that token in the APK.

Releasing with the endpoint blank keeps the app completely inert, which is what a build from
source should do.

## Tests

The filtering rules are the security-relevant part, so they are tested directly with no
deployment and no network:

```sh
npm test
```

## What it deliberately does not do

**No KV storage.** The free tier allows 1,000 writes a day, which a per-batch counter would
exhaust immediately. Burst protection uses the Rate Limiting binding — available on the Workers
Free plan — and the Worker checks for it before use, so a missing or misconfigured binding
degrades to "no rate limiting" rather than failing every request.

**No allow list of custom property names.** The Worker bounds their count, key length and value
length, and lets the app's own `AnalyticsSchemaTest` police which names exist. Duplicating the
whole schema here would guarantee the two drift apart.

**It is not a general-purpose proxy.** It accepts one route, one method, one shape.

## Keeping the event list honest

`ALLOWED_EVENTS` in `src/index.js` must contain every event in
`app/src/main/java/me/avinas/tempo/data/analytics/AnalyticsEvent.kt`. A name missing from the
list is silently dropped, so add new events in both places — the test suite asserts a few
known-good names to catch the list falling behind.
