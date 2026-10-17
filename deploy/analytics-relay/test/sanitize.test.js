import { test } from "node:test";
import assert from "node:assert/strict";

import { ALLOWED_EVENTS, sanitizeEvent } from "../src/index.js";

const INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e";

function event(overrides = {}) {
  return {
    event: "daily_activity",
    distinct_id: INSTALL,
    timestamp: "2026-10-16T12:00:00.000+00:00",
    properties: { listener_down: 0 },
    ...overrides,
  };
}

test("an event no longer in the schema is dropped", () => {
  // The whole point of the relay: an event deleted from the app must stop being stored
  // immediately, not once every install has updated.
  assert.equal(sanitizeEvent(event({ event: "enrichment_run" })), null);
  assert.equal(sanitizeEvent(event({ event: "tracking_source" })), null);
});

test("an event missing a usable distinct id is dropped", () => {
  assert.equal(sanitizeEvent(event({ distinct_id: undefined })), null);
  assert.equal(sanitizeEvent(event({ distinct_id: "" })), null);
  assert.equal(sanitizeEvent(event({ distinct_id: "x".repeat(201) })), null);
});

test("an allowed event keeps its name and custom properties", () => {
  const clean = sanitizeEvent(event({ properties: { listener_down: 1, ui_opened: 0 } }));

  assert.equal(clean.event, "daily_activity");
  assert.equal(clean.distinct_id, INSTALL);
  assert.equal(clean.properties.listener_down, 1);
  assert.equal(clean.properties.ui_opened, 0);
});

test("anonymity is forced rather than trusted", () => {
  // A client claiming to be identified must still come out anonymous.
  const clean = sanitizeEvent(
    event({ properties: { $process_person_profile: true, listener_down: 0 } }),
  );

  assert.equal(clean.properties.$process_person_profile, false);
});

test("the anonymous flag cannot be removed by omitting it", () => {
  const clean = sanitizeEvent(event({ properties: { listener_down: 0 } }));

  assert.equal(clean.properties.$process_person_profile, false);
});

test("device model travels only with a crash", () => {
  const withModel = { $device_model: "Pixel 8" };

  const normal = sanitizeEvent(event({ properties: { ...withModel } }));
  assert.equal(normal.properties.$device_model, undefined);

  const crash = sanitizeEvent(
    event({ event: "crash", properties: { ...withModel, crash_class: "a.b.c" } }),
  );
  assert.equal(crash.properties.$device_model, "Pixel 8");
});

test("reserved properties outside the allow list are dropped", () => {
  const clean = sanitizeEvent(
    event({
      properties: {
        $current_url: "https://example.com/secret",
        $email: "someone@example.com",
        $os: "Android",
        listener_down: 0,
      },
    }),
  );

  assert.equal(clean.properties.$current_url, undefined);
  assert.equal(clean.properties.$email, undefined);
  assert.equal(clean.properties.$os, "Android");
});

test("an allowed event keeps its timestamp", () => {
  const clean = sanitizeEvent(event());

  assert.equal(clean.timestamp, "2026-10-16T12:00:00.000+00:00");
});

test("a non-string timestamp is dropped rather than filed at ingestion time", () => {
  // PostHog reads timestamp as ISO 8601 and silently ignores a numeric epoch, which would
  // smear the event across the wrong day.
  assert.equal(sanitizeEvent(event({ timestamp: 1700000000000 })).timestamp, undefined);
  assert.equal(sanitizeEvent(event({ timestamp: undefined })).timestamp, undefined);
});

test("long strings are truncated and long keys are dropped", () => {
  const clean = sanitizeEvent(
    event({
      properties: {
        listener_down: "y".repeat(500),
        ["k".repeat(41)]: "dropped",
      },
    }),
  );

  assert.equal(clean.properties.listener_down.length, 64);
  assert.equal(Object.keys(clean.properties).includes("k".repeat(41)), false);
});

test("the number of custom properties is bounded", () => {
  const properties = {};
  for (let i = 0; i < 100; i++) properties[`prop_${i}`] = i;

  const clean = sanitizeEvent(event({ properties }));
  const customCount = Object.keys(clean.properties).filter((k) => !k.startsWith("$")).length;

  assert.equal(customCount, 30);
});

test("structured property values are dropped", () => {
  const clean = sanitizeEvent(
    event({ properties: { nested: { a: 1 }, list: [1, 2], ok: 1 } }),
  );

  assert.equal(clean.properties.nested, undefined);
  assert.equal(clean.properties.list, undefined);
  assert.equal(clean.properties.ok, 1);
});

test("the whitelist covers the events the app can currently send", () => {
  // A guard against the whitelist falling behind the schema, which would silently drop
  // real events instead of unknown ones.
  for (const name of [
    "daily_activity",
    "tracking_health",
    "listening_activity",
    "crash",
    "import_run",
    "backup_run",
  ]) {
    assert.ok(ALLOWED_EVENTS.has(name), `${name} should be allowed`);
  }
});

test("country is attached from the connection, not from the client", () => {
  const clean = sanitizeEvent(event(), "DE");

  assert.equal(clean.properties.country, "DE");
});

test("country is omitted rather than guessed when the edge does not know it", () => {
  assert.equal(sanitizeEvent(event(), null).properties.country, undefined);
  assert.equal(sanitizeEvent(event()).properties.country, undefined);
  // A non-ISO value is not passed through on trust.
  assert.equal(sanitizeEvent(event(), "Germany").properties.country, undefined);
});

test("a client cannot claim its own country", () => {
  // The relay derives this from the connection; a client asserting one is claiming something
  // it has no way to know.
  const clean = sanitizeEvent(event({ properties: { country: "US", listener_down: 0 } }), "DE");

  assert.equal(clean.properties.country, "DE");
});

test("GeoIP is disabled so the backend geolocates nothing", () => {
  // Without this, PostHog would enrich using the address that reached it — which on this path
  // is a Cloudflare datacenter, not the user.
  const clean = sanitizeEvent(event(), "DE");
  const withoutCountry = sanitizeEvent(event());

  assert.equal(clean.properties.$geoip_disable, true);
  assert.equal(withoutCountry.properties.$geoip_disable, true);
});

test("a client cannot re-enable GeoIP", () => {
  const clean = sanitizeEvent(event({ properties: { $geoip_disable: false } }), "DE");

  assert.equal(clean.properties.$geoip_disable, true);
});
