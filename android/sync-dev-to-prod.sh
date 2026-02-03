#!/bin/bash
# Sync dev server changes to production server
# Preserves environment-specific files and databases

echo "Syncing duckflix-lite-server-dev → duckflix-lite-server-v2..."

ssh ducky@192.168.4.66 "rsync -av --delete \
  --exclude='node_modules/' \
  --exclude='db/' \
  --exclude='.env' \
  --exclude='ecosystem.*.config.js' \
  --exclude='*.log' \
  /home/ducky/duckflix-lite-server-dev/ \
  /home/ducky/duckflix-lite-server-v2/"

echo "Restarting production server..."
ssh ducky@192.168.4.66 "pm2 restart duckflix-lite-server-v2"

echo "✅ Sync complete!"
