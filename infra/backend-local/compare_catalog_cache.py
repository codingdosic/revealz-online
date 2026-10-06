#!/usr/bin/env python3
"""Measure one sequential catalog series and append its result to CSV."""

import argparse
import csv
import http.client
import json
import math
import statistics
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen


CATALOG_PATH = "/v1/shop/catalog"
CACHE_KEY = "revealz:catalog:v1"
EDGE = ("127.0.0.1", 18080)
DIRECT_URLS = ("http://127.0.0.1:18082", "http://127.0.0.1:18083")
METRIC_PATH = "/actuator/metrics/catalog.db.select"
FIELDS = (
    "recorded_at_utc", "round", "label", "kind", "requests", "errors", "mismatches",
    "mean_ms", "p95_ms", "sql_counter_delta", "instances",
)


def get_json(url):
    with urlopen(url, timeout=5) as response:
        return response.status, response.headers, json.loads(response.read())


def warm_up(requests_per_instance):
    reference = None
    for base_url in DIRECT_URLS:
        for _ in range(requests_per_instance):
            status, _, body = get_json(base_url + CATALOG_PATH)
            if status != 200:
                raise RuntimeError(f"warm-up failed with HTTP {status}")
            if reference is None:
                reference = body
            elif body != reference:
                raise RuntimeError("catalog JSON changed during warm-up")
    return reference


def metric_value(base_url):
    try:
        status, _, body = get_json(base_url + METRIC_PATH)
    except HTTPError as error:
        if error.code == 404:
            return 0.0
        raise
    if status != 200:
        raise RuntimeError(f"metric endpoint failed with HTTP {status}")
    for measurement in body.get("measurements", []):
        if measurement.get("statistic") == "COUNT":
            return float(measurement["value"])
    return 0.0


def total_selects():
    return sum(metric_value(base_url) for base_url in DIRECT_URLS)


def delete_cache_key():
    compose = Path(__file__).with_name("compose.yaml")
    subprocess.run(
        [
            "docker", "compose", "-f", str(compose), "exec", "-T",
            "redis", "redis-cli", "DEL", CACHE_KEY,
        ],
        check=True,
        capture_output=True,
        text=True,
    )


def measure(round_number, label, kind, request_count, reference):
    connection = http.client.HTTPConnection(*EDGE, timeout=5)
    latencies = []
    errors = 0
    mismatches = 0
    instances = set()
    selects_before = total_selects()

    try:
        for _ in range(request_count):
            started = time.perf_counter()
            connection.request("GET", CATALOG_PATH)
            response = connection.getresponse()
            raw = response.read()
            latencies.append((time.perf_counter() - started) * 1000)
            instances.add(response.getheader("X-Instance-Id") or "missing")
            if response.status != 200:
                errors += 1
                continue
            try:
                body = json.loads(raw)
            except json.JSONDecodeError:
                mismatches += 1
                continue
            if body != reference:
                mismatches += 1
    finally:
        connection.close()

    selects_after = total_selects()
    ordered = sorted(latencies)
    p95_index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return {
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "round": round_number,
        "label": label,
        "kind": kind,
        "requests": request_count,
        "errors": errors,
        "mismatches": mismatches,
        "mean_ms": f"{statistics.fmean(latencies):.3f}",
        "p95_ms": f"{ordered[p95_index]:.3f}",
        "sql_counter_delta": f"{selects_after - selects_before:.0f}",
        "instances": ";".join(sorted(instances)),
    }


def append_rows(output, rows):
    output.parent.mkdir(parents=True, exist_ok=True)
    write_header = not output.exists()
    with output.open("a", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS)
        if write_header:
            writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--round", type=int, required=True)
    parser.add_argument("--label", choices=("off", "on"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=20)
    args = parser.parse_args()

    reference = warm_up(args.warmup)
    rows = []
    if args.label == "on":
        delete_cache_key()
        rows.append(measure(args.round, args.label, "cold", 1, reference))
    rows.append(measure(args.round, args.label, "series", args.requests, reference))
    append_rows(args.output, rows)

    for row in rows:
        print(row)
    if any(int(row["errors"]) or int(row["mismatches"]) for row in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
