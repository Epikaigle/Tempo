#!/usr/bin/env python3
"""Watches the Google Play Android vitals crash rate and reports whether it regressed.

This is the free half of Tempo's crash reporting: Play already collects crashes and ANRs for
every install, and because releases ship as an AAB (AGP 4.1+) Play deobfuscates the stack
traces automatically. Nothing here requires an SDK in the app or any new consent.

The in-app `crash` event covers what Play cannot see: non-fatal errors, sideloaded builds, and
crashes that fall between Play's sampled, lagged reports.

Run with a `CRASH_RESPONSE_FIXTURE` pointing at a canned API response to exercise the parsing
and threshold logic without network access or credentials.
"""

from __future__ import annotations

import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

API_ROOT = "https://playdeveloperreporting.googleapis.com/v1beta1/apps"

# Daily aggregation is only available in this timezone for DAILY queries.
API_TIMEZONE = "America/Los_Angeles"


def build_query_body(today: datetime.date, window_days: int) -> dict:
    """Builds a query for the last `window_days` complete days of daily metrics."""

    def ymd(day: datetime.date) -> dict:
        return {
            "year": day.year,
            "month": day.month,
            "day": day.day,
            "timeZone": API_TIMEZONE,
        }

    return {
        "timelineSpec": {
            "aggregationPeriod": "DAILY",
            "startTime": ymd(today - datetime.timedelta(days=window_days)),
            "endTime": ymd(today - datetime.timedelta(days=1)),
        },
        "metrics": ["crashRate", "userPerceivedCrashRate", "distinctUsers"],
    }


def query(body: dict, package: str, token: str) -> dict:
    url = f"{API_ROOT}/{package}/crashRateMetricSet:query"
    if urllib.parse.urlparse(url).scheme not in ("https",):
        raise ValueError(f"refusing to query non-HTTPS URL: {url!r}")
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        if urllib.parse.urlparse(request.full_url).scheme not in ("https",):
            raise ValueError(f"refusing to open non-HTTPS URL: {request.full_url!r}")
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except (OSError, ValueError) as e:
        raise RuntimeError(f"Play crash-rate query failed: {e}") from e


def summarise(payload: dict) -> tuple[float, float, str]:
    """Returns (peak active users in a day, worst user-perceived crash rate, latest date).

    Takes the maximum rather than a sum for distinct users: the API rounds the value and
    explicitly documents that it must not be aggregated, since a user active on two days
    would otherwise be counted twice.
    """
    users: list[float] = []
    rates: list[float] = []
    latest = "n/a"

    for row in payload.get("rows", []):
        for metric in row.get("metrics", []):
            try:
                value = float(metric.get("decimalValue", 0))
            except (TypeError, ValueError):
                continue
            if metric.get("metric") == "distinctUsers":
                users.append(value)
            elif metric.get("metric") == "userPerceivedCrashRate":
                rates.append(value)

        start = row.get("startTime")
        if start:
            latest = f"{start.get('year')}-{start.get('month')}-{start.get('day')}"

    return (max(users) if users else 0.0, max(rates) if rates else 0.0, latest)


def main() -> int:
    package = os.environ.get("PACKAGE_NAME", "me.avinas.tempo")
    token = os.environ.get("ACCESS_TOKEN", "")
    try:
        max_rate = float(os.environ.get("MAX_ACCEPTABLE_RATE", "1.5"))
    except (TypeError, ValueError):
        print("WARN: invalid MAX_ACCEPTABLE_RATE; using 1.5", file=sys.stderr)
        max_rate = 1.5
    try:
        window_days = int(os.environ.get("WINDOW_DAYS", "7"))
    except (TypeError, ValueError):
        print("WARN: invalid WINDOW_DAYS; using 7", file=sys.stderr)
        window_days = 7
    fixture = os.environ.get("CRASH_RESPONSE_FIXTURE")

    # Local time is close enough for choosing a date window; the API's own timezone governs
    # how the rows are bucketed, and an off-by-one day at the edge is harmless for a trend.
    today = datetime.datetime.now(datetime.timezone.utc).date()
    body = build_query_body(today, window_days)

    if os.environ.get("PRINT_BODY_ONLY"):
        print(json.dumps(body, indent=2))
        return 0

    if fixture:
        try:
            with open(fixture, encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError) as e:
            print(f"Invalid fixture {fixture}: {e}", file=sys.stderr)
            return 2
    elif token:
        payload = query(body, package, token)
    else:
        print("No ACCESS_TOKEN and no fixture; skipping.")
        return 0

    users, rate, latest = summarise(payload)
    regressed = rate > max_rate

    print(f"window: last {window_days} complete days, latest row {latest}")
    print(f"peak active users in a day: {users}")
    print(f"worst user-perceived crash rate: {rate}% (threshold {max_rate}%)")
    print("VERDICT:", "REGRESSED" if regressed else "OK")

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        try:
            with open(output_path, "a", encoding="utf-8") as handle:
                handle.write(f"worst={rate}\n")
                handle.write(f"users={users}\n")
                handle.write(f"latest={latest}\n")
                handle.write(f"regressed={'true' if regressed else 'false'}\n")
        except OSError as e:
            print(f"WARN: cannot write GITHUB_OUTPUT: {e}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
