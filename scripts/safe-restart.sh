#!/usr/bin/env bash
# safe-restart.sh — Reload duckflix-lite-v2 only when no active streams are running.
# Usage: ./scripts/safe-restart.sh [--wait N] [--force]

set -euo pipefail

APP_DIR="/home/ducky/duckflix-lite-server-v2"
DB_PATH="$APP_DIR/db/duckflix.db"
NODE="/home/ducky/.nvm/versions/node/v20.20.0/bin/node"
PM2="$(command -v pm2)"

MAX_WAIT=120
FORCE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --wait)  MAX_WAIT="$2"; shift 2 ;;
    --force) FORCE=true; shift ;;
    *)       echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Count active RD sessions (heartbeat within last 30s)
count_active_streams() {
  "$NODE" -e "
    const Database = require('better-sqlite3');
    const db = new Database('$DB_PATH', { readonly: true });
    const threshold = new Date(Date.now() - 30000).toISOString();
    const row = db.prepare('SELECT COUNT(*) as cnt FROM rd_sessions WHERE last_heartbeat_at > ?').get(threshold);
    db.close();
    process.stdout.write(String(row.cnt));
  " 2>/dev/null || echo "0"
}

if [ "$FORCE" = true ]; then
  echo "[safe-restart] --force specified, skipping session check"
else
  WAITED=0
  while true; do
    ACTIVE=$(count_active_streams)
    if [ "$ACTIVE" -eq 0 ]; then
      echo "[safe-restart] No active streams, proceeding with reload"
      break
    fi

    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
      echo "[safe-restart] Timeout after ${MAX_WAIT}s with $ACTIVE active stream(s) — aborting"
      echo "[safe-restart] Use --force to skip session check, or --wait N to increase timeout"
      exit 1
    fi

    echo "[safe-restart] $ACTIVE active stream(s), waiting... (${WAITED}s/${MAX_WAIT}s)"
    sleep 5
    WAITED=$((WAITED + 5))
  done
fi

echo "[safe-restart] Reloading PM2 with --update-env..."
cd "$APP_DIR"
"$PM2" reload ecosystem.config.js --update-env

echo ""
echo "[safe-restart] Reload complete. Current status:"
"$PM2" show duckflix-lite-v2
