#!/bin/bash
# Sync production server changes back to dev server
# Preserves environment-specific files and databases

echo "Syncing duckflix-lite-server-v2 → duckflix-lite-server-dev..."

ssh ducky@192.168.4.66 "rsync -av --delete \
  --exclude='node_modules/' \
  --exclude='db/' \
  --exclude='.env' \
  --exclude='ecosystem.*.config.js' \
  --exclude='*.log' \
  /home/ducky/duckflix-lite-server-v2/ \
  /home/ducky/duckflix-lite-server-dev/"

echo "Restarting dev server..."
ssh ducky@192.168.4.66 "cd /home/ducky/duckflix-lite-server-dev && pm2 restart duckflix-lite-dev"

echo "✅ Sync complete!"
