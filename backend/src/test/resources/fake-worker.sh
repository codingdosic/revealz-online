#!/bin/sh
trap 'exit 0' TERM INT
echo "[MP-SERVER] listening"
sleep "${FAKE_WORKER_LIFETIME_SECONDS:-300}"
