#!/usr/bin/env bash
set -euo pipefail

slot="${1:-manual}"
port="${PORT:-3080}"
base_url="${REFRESH_BASE_URL:-http://127.0.0.1:${port}}"
health_url="${base_url%/}/api/health"
refresh_url="${base_url%/}/api/refresh"

echo "[${slot}] checking ${health_url}"
curl --fail --silent --show-error "${health_url}" >/dev/null

echo "[${slot}] posting ${refresh_url}"
curl --fail --silent --show-error \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "{\"slot\":\"${slot}\"}" \
  "${refresh_url}" >/dev/null

echo "[${slot}] refresh complete"
