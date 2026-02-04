# DuckFlix Lite Server - Quick Start

## Immediate Testing (Local Development)

```bash
cd /Users/aaron/projects/duckflix_lite/server

# Dependencies already installed
npm run dev  # Starts on port 3001 with auto-reload
```

Server runs at: `http://localhost:3001`

## Test the API

### 1. Health Check
```bash
curl http://localhost:3001/health
# {"status":"ok","timestamp":"..."}
```

### 2. Login as Admin
```bash
TOKEN=$(curl -s -X POST http://localhost:3001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme123"}' | jq -r '.token')

echo $TOKEN
```

### 3. Create a User
```bash
curl -X POST http://localhost:3001/api/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john",
    "password": "secret123",
    "isAdmin": false,
    "rdApiKey": "YOUR_RD_KEY",
    "rdExpiryDate": "2026-12-31"
  }' | jq .
```

### 4. Test VOD Session Tracking
```bash
# Check if user can start playback
curl "http://localhost:3001/api/auth/check-vod-session?ip=192.168.1.100" \
  -H "Authorization: Bearer $TOKEN" | jq .
# {"allowed":true,"currentIp":"192.168.1.100"}

# Send heartbeat (do this every 30s while playing)
curl -X POST http://localhost:3001/api/auth/vod-heartbeat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ip":"192.168.1.100"}' | jq .

# End session when done
curl -X POST http://localhost:3001/api/auth/vod-session-end \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ip":"192.168.1.100"}' | jq .
```

### 5. Search Zurg (requires /mnt/zurg mount)
```bash
curl "http://localhost:3001/api/search/zurg?title=Inception&year=2010&type=movie" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### 6. Get EPG Data
```bash
curl http://localhost:3001/api/epg \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### 7. Download APK
```bash
curl http://localhost:3001/api/apk/version | jq .
curl -O http://localhost:3001/api/apk/latest
```

## Configuration

Edit `.env` for customization:

```bash
# Change admin password
ADMIN_PASSWORD=your-secure-password

# Add TMDB API key (for search)
TMDB_API_KEY=your-tmdb-api-key

# Configure EPG/M3U sources
EPG_SOURCE_URL=https://example.com/epg.xml
M3U_SOURCE_URL=https://example.com/playlist.m3u

# Set Zurg mount path (if different)
ZURG_MOUNT_PATH=/mnt/zurg
```

## Production Deployment

When ready to deploy to duckflix.tv:

```bash
# See DEPLOYMENT.md for full instructions
# Quick summary:

# 1. SSH to server
ssh ducky@192.168.4.66

# 2. Transfer files
rsync -avz --exclude 'node_modules' --exclude 'db/*.db' \
  /Users/aaron/projects/duckflix_lite/ \
  ducky@192.168.4.66:/var/lib/duckflix_lite/

# 3. Install & start
cd /var/lib/duckflix_lite/server
npm install --production
pm2 start ecosystem.config.js
pm2 save

# 4. Configure nginx (see DEPLOYMENT.md)
# 5. Access at https://duckflix.tv/lite_service/health
```

## Logs

Development logs go to console. View with:
```bash
npm run dev
```

Check database:
```bash
sqlite3 db/duckflix_lite.db "SELECT * FROM users;"
sqlite3 db/duckflix_lite.db "SELECT * FROM user_sessions;"
```

## Troubleshooting

### Port in use
```bash
lsof -i :3001
kill <PID>
```

### Reset database
```bash
rm db/duckflix_lite.db*
npm run dev  # Recreates with default admin
```

### Check cron jobs
EPG/M3U sync runs automatically:
- EPG: Every 30 minutes
- M3U: Every hour

Manual trigger:
```bash
curl -X POST http://localhost:3001/api/epg/sync \
  -H "Authorization: Bearer $TOKEN"
```

## Documentation

- `README.md` - Server overview
- `TESTING.md` - Comprehensive test examples
- `DEPLOYMENT.md` - Production deployment guide
- `CHANGELOG.md` - What was built
- `PHASE1_COMPLETE.md` - Phase 1 summary

## Next Steps

With the server running, you can:
1. Test all API endpoints with the examples in `TESTING.md`
2. Deploy to production following `DEPLOYMENT.md`
3. Start Phase 2: Android project setup
4. Build the React admin panel

The server is production-ready! 🚀
