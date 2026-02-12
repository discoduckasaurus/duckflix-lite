#!/usr/bin/env bash
# mem-monitor.sh — Track duckflix-lite-v2 memory usage and auto-capture heap snapshots.
#
# Usage:
#   crontab -e
#   */5 * * * * /home/ducky/duckflix-lite-server-v2/scripts/mem-monitor.sh
#
# Output:
#   logs/mem-trend.csv  — timestamp, rss_mb, uptime_sec
#   logs/mem-alerts.log — growth warnings + snapshot triggers

set -euo pipefail

APP_DIR="/home/ducky/duckflix-lite-server-v2"
CSV="$APP_DIR/logs/mem-trend.csv"
SNAPSHOT_DIR="$APP_DIR/logs"
PM2="$(command -v pm2)"

# Thresholds in MB — heap snapshot captured once per threshold crossing
SNAPSHOT_THRESHOLDS=(2048 4096 6144)

# Alert if RSS grew more than this (MB) over the lookback window
ALERT_GROWTH_MB=500
LOOKBACK_LINES=24  # 24 * 5min = 2 hours

# ── Get PID from PM2 ─────────────────────────────────────────────────

PID=$("$PM2" pid duckflix-lite-v2 2>/dev/null || echo "0")

if [ -z "$PID" ] || [ "$PID" = "0" ] || [ ! -d "/proc/$PID" ]; then
  exit 0  # process not running
fi

# ── Collect RSS from /proc ────────────────────────────────────────────

RSS_KB=$(awk '/^VmRSS:/ { print $2 }' "/proc/$PID/status" 2>/dev/null || echo "0")
RSS_MB=$(( RSS_KB / 1024 ))

if [ "$RSS_MB" -eq 0 ]; then
  exit 0
fi

# Uptime: process start time from /proc/stat field 22 (starttime in clock ticks).
# Field 2 is the process name in parens — e.g., "(node /home/duck)" — which can
# contain spaces, breaking naive awk field splitting. Strip it first via sed.
UPTIME_SEC=0
if [ -f "/proc/$PID/stat" ]; then
  # Remove everything up to and including the closing paren of field 2,
  # then field 22 (starttime) becomes field 20 of the remaining string.
  START_TICKS=$(sed 's/^.*) //' "/proc/$PID/stat" 2>/dev/null | awk '{ print $20 }')
  START_TICKS="${START_TICKS:-0}"
  CLK_TCK=$(getconf CLK_TCK 2>/dev/null || echo "100")
  SYS_UPTIME=$(awk '{ printf "%d", $1 }' /proc/uptime 2>/dev/null || echo "0")
  if [ "$START_TICKS" -gt 0 ] && [ "$CLK_TCK" -gt 0 ]; then
    PROC_START_SEC=$(( START_TICKS / CLK_TCK ))
    UPTIME_SEC=$(( SYS_UPTIME - PROC_START_SEC ))
  fi
fi

# ── Write CSV row ─────────────────────────────────────────────────────

if [ ! -f "$CSV" ]; then
  echo "timestamp,rss_mb,uptime_sec" > "$CSV"
fi

TIMESTAMP=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
echo "$TIMESTAMP,$RSS_MB,$UPTIME_SEC" >> "$CSV"

# ── Growth alert ──────────────────────────────────────────────────────

LINE_COUNT=$(wc -l < "$CSV")
if [ "$LINE_COUNT" -gt "$((LOOKBACK_LINES + 1))" ]; then
  OLD_RSS=$(tail -n "$((LOOKBACK_LINES + 1))" "$CSV" | head -n 1 | cut -d',' -f2)
  GROWTH=$((RSS_MB - OLD_RSS))

  if [ "$GROWTH" -gt "$ALERT_GROWTH_MB" ]; then
    logger -t duckflix-mem "WARNING: RSS grew ${GROWTH}MB in last $((LOOKBACK_LINES * 5))min (${OLD_RSS}MB -> ${RSS_MB}MB)"
    echo "$TIMESTAMP [ALERT] RSS grew ${GROWTH}MB in last $((LOOKBACK_LINES * 5))min (${OLD_RSS}MB -> ${RSS_MB}MB)" >> "$APP_DIR/logs/mem-alerts.log"
  fi
fi

# ── Auto heap snapshot at thresholds ──────────────────────────────────

for THRESHOLD in "${SNAPSHOT_THRESHOLDS[@]}"; do
  MARKER="$SNAPSHOT_DIR/.heap-snapshot-${THRESHOLD}mb"

  if [ "$RSS_MB" -ge "$THRESHOLD" ] && [ ! -f "$MARKER" ]; then
    echo "$TIMESTAMP [SNAPSHOT] RSS crossed ${THRESHOLD}MB — triggering heap dump" >> "$APP_DIR/logs/mem-alerts.log"
    logger -t duckflix-mem "RSS crossed ${THRESHOLD}MB — triggering heap dump"

    # pm2 trigger captures a .heapsnapshot in the app cwd
    "$PM2" trigger duckflix-lite-v2 km:heapdump 2>/dev/null || true

    # Mark this threshold as captured (reset on restart via uptime check)
    echo "$TIMESTAMP" > "$MARKER"
  fi

  # Reset markers after restart (uptime < 10 min = fresh start)
  if [ "$UPTIME_SEC" -lt 600 ] && [ -f "$MARKER" ]; then
    rm -f "$MARKER"
  fi
done

# ── CSV rotation (keep ~7 days = 2016 rows at 5min intervals) ─────────

MAX_ROWS=2100
CURRENT_ROWS=$(wc -l < "$CSV")
if [ "$CURRENT_ROWS" -gt "$MAX_ROWS" ]; then
  TRIM=$((CURRENT_ROWS - MAX_ROWS))
  # Keep header + last MAX_ROWS data rows
  { head -n 1 "$CSV"; tail -n "+$((TRIM + 2))" "$CSV"; } > "$CSV.tmp"
  mv "$CSV.tmp" "$CSV"
fi
