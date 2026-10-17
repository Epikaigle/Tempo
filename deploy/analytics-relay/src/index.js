/**
 * Tempo analytics relay.
 *
 * Sits between the app and PostHog and does five jobs the client cannot do for itself:
 *
 *  1. **Holds the real PostHog project token.** The app only ever knows this Worker's own
 *     app token, so the project token never ships inside the APK where anyone can extract it.
 *  2. **Enforces the event whitelist at the edge.** An event removed from the schema stops
 *     being stored the moment this list changes — no app release, no waiting months for
 *     Android update adoption. That is the lesson from shipping a per-provider enrichment
 *     event that multiplied itself eightfold: you cannot un-ship an event from a client, but
 *     you can stop accepting it.
 *  3. **Forces anonymity.** `$process_person_profile` is set to false here, unconditionally,
 *     rather than trusted from the client. A buggy or malicious build cannot create person
 *     profiles.
 *  4. **Drops reserved and oversized properties**, bounds the batch, and rate-limits bursts
 *     per install.
 *
 * Deliberately no KV: the free tier allows 1,000 writes a day, which a per-batch counter would
 * exhaust immediately. Burst protection uses the Rate Limiting binding instead, which is
 * available on the Workers Free plan, and degrades to "no limiting" if the binding is absent so
 * a misconfiguration cannot take ingestion down.
 */

/** Must track `AnalyticsEvent` — an event missing here is silently dropped. */
export const ALLOWED_EVENTS = new Set([
  "app_started",
  "screen_viewed",
  "feature_used",
  "onboarding_step",
  "onboarding_completed",
  "notif_access",
  "battery_exemption",
  "daily_activity",
  "tracking_health",
  "listening_activity",
  "db_migration",
  "import_run",
  "backup_run",
  "crash",
]);

/**
 * PostHog's own namespace. Only these pass through, so a client cannot smuggle `$current_url`,
 * `$ email` or any other reserved key that would change how PostHog interprets the event.
 */
const ALLOWED_RESERVED = new Set([
  "$lib",
  "$lib_version",
  "$os",
  "$os_version",
  "$app_version",
  "$locale",
]);

const DEVICE_MODEL_PROP = "$device_model";
const DEVICE_MODEL_EVENT = "crash";
const ANONYMOUS_PROP = "$process_person_profile";

/**
 * Tells PostHog to skip its own GeoIP enrichment.
 *
 * The relay is the one that sees the real client address, and PostHog would otherwise geolocate
 * whatever address reaches it — which, for anything forwarded from here, is a Cloudflare
 * datacenter rather than the user. That is not just less private, it is wrong: every event would
 * be filed under whichever region the Worker happened to run in.
 */
const GEOIP_DISABLE_PROP = "$geoip_disable";

/** ISO-3166 alpha-2, derived from Cloudflare's own view of the request. */
const COUNTRY_PROP = "country";
const MAX_COUNTRY_LENGTH = 2;

const MAX_BATCH_EVENTS = 100;
const MAX_CUSTOM_PROPS = 30;
const MAX_KEY_LENGTH = 40;
const MAX_STRING_LENGTH = 64;
const MAX_DISTINCT_ID_LENGTH = 200;
const MAX_TIMESTAMP_LENGTH = 40;

const DEFAULT_POSTHOG_HOST = "https://eu.i.posthog.com";

function isSimple(value) {
  return (
    typeof value === "string" ||
    typeof value === "number" ||
    typeof value === "boolean"
  );
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Returns the event as it will be forwarded, or null when it must be dropped.
 *
 * @param country ISO-3166 alpha-2 for the requesting client, as seen by Cloudflare's edge, or
 *   null when it is not known. Only this coarse value is attached; the address it came from is
 *   never forwarded to the analytics backend and never stored anywhere.
 *
 * Exported so the rules can be tested without deploying anything.
 */
export function sanitizeEvent(event, country = null) {
  if (typeof event !== "object" || event === null) return null;

  const name = event.event;
  if (typeof name !== "string" || !ALLOWED_EVENTS.has(name)) return null;

  const distinctId = event.distinct_id;
  if (
    typeof distinctId !== "string" ||
    distinctId.length === 0 ||
    distinctId.length > MAX_DISTINCT_ID_LENGTH
  ) {
    return null;
  }

  // Forced, not trusted. This is the guarantee that no person profile is ever created.
  const properties = { [ANONYMOUS_PROP]: false, [GEOIP_DISABLE_PROP]: true };

  // Attached here rather than left to the backend, which never sees the client address at all
  // on this path and would otherwise geolocate the relay.
  if (typeof country === "string" && country.length === MAX_COUNTRY_LENGTH) {
    properties[COUNTRY_PROP] = country.toUpperCase();
  }

  const source = event.properties;
  if (source && typeof source === "object") {
    let customCount = 0;
    for (const [key, value] of Object.entries(source)) {
      if (key === ANONYMOUS_PROP) continue;

      // The relay is authoritative for country: it is derived from the connection itself, so a
      // client claiming one would be asserting something it has no way to know.
      if (key === COUNTRY_PROP) continue;

      if (key.startsWith("$")) {
        if (key === DEVICE_MODEL_PROP) {
          // Kept only where it earns its keep. Paired with a stable install id, device model
          // is the field most likely to make a small user base re-identifiable.
          if (name === DEVICE_MODEL_EVENT && typeof value === "string") {
            properties[key] = value.slice(0, MAX_STRING_LENGTH);
          }
          continue;
        }
        if (ALLOWED_RESERVED.has(key) && isSimple(value)) properties[key] = value;
        continue;
      }

      if (customCount >= MAX_CUSTOM_PROPS) break;
      if (key.length === 0 || key.length > MAX_KEY_LENGTH) continue;
      if (!isSimple(value)) continue;

      properties[key] =
        typeof value === "string" ? value.slice(0, MAX_STRING_LENGTH) : value;
      customCount++;
    }
  }

  const clean = { event: name, distinct_id: distinctId, properties };

  // PostHog reads `timestamp` as ISO 8601 and silently ignores a numeric epoch, so a
  // non-string is dropped rather than forwarded to be filed at ingestion time.
  if (
    typeof event.timestamp === "string" &&
    event.timestamp.length <= MAX_TIMESTAMP_LENGTH
  ) {
    clean.timestamp = event.timestamp;
  }

  return clean;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true });
    }

    if (request.method !== "POST" || url.pathname !== "/ingest") {
      return json({ error: "not_found" }, 404);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    if (!env.APP_TOKEN || body?.api_key !== env.APP_TOKEN) {
      return json({ error: "unauthorized" }, 401);
    }

    const batch = body?.batch;
    if (!Array.isArray(batch)) return json({ error: "batch_must_be_array" }, 400);
    if (batch.length === 0) return json({ accepted: 0, dropped: 0 });
    if (batch.length > MAX_BATCH_EVENTS) return json({ error: "batch_too_large" }, 413);

    const installId =
      typeof batch[0]?.distinct_id === "string" ? batch[0].distinct_id : "unknown";

    if (env.RATE_LIMITER) {
      const { success } = await env.RATE_LIMITER.limit({ key: installId });
      if (!success) {
        // Answered 200 on purpose: the client treats a non-2xx as retryable and would hold
        // the batch until it expired. A throttled batch is dropped, not retried.
        return json({ accepted: 0, dropped: batch.length, reason: "rate_limited" });
      }
    }

    // Cloudflare resolves this at the edge from the connection, so it costs no external lookup
    // and no address is stored to produce it.
    const clientCountry = request.cf?.country ?? null;

    const kept = [];
    let dropped = 0;
    for (const event of batch) {
      const clean = sanitizeEvent(event, clientCountry);
      if (clean) kept.push(clean);
      else dropped++;
    }

    if (kept.length === 0) return json({ accepted: 0, dropped });

    const host = env.POSTHOG_HOST || DEFAULT_POSTHOG_HOST;
    const upstream = await fetch(`${host.replace(/\/+$/, "")}/batch/`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        // The real project token, which exists only here and in the Worker's secrets.
        api_key: env.POSTHOG_API_KEY,
        historical_migration: false,
        batch: kept,
      }),
    });

    if (!upstream.ok) {
      // 502 so the client keeps the batch and retries; it expires after 24h regardless.
      return json({ error: "upstream_failed", status: upstream.status }, 502);
    }

    return json({ accepted: kept.length, dropped });
  },
};
