# Server Testing Guide

## Quick Start

```bash
cd /Users/aaron/projects/duckflix_lite/server
npm install
cp .env.example .env
# Edit .env if needed
npm run dev
```

Server runs on `http://localhost:3001` (or PORT from .env)

## API Testing Examples

### 1. Health Check
```bash
curl http://localhost:3001/health
```

Expected: `{"status":"ok","timestamp":"..."}`

### 2. Login as Admin
```bash
curl -X POST http://localhost:3001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme123"}'
```

Expected: Returns JWT token and user object

**Save the token for next requests:**
```bash
export TOKEN="<token_from_login_response>"
```

### 3. Get Current User
```bash
curl http://localhost:3001/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

### 4. List All Users (Admin Only)
```bash
curl http://localhost:3001/api/admin/users \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Create New User
```bash
curl -X POST http://localhost:3001/api/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john",
    "password": "secret123",
    "isAdmin": false
  }'
```

### 6. Create Sub-Account
```bash
# First get parent user ID (e.g., 2)
curl -X POST http://localhost:3001/api/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john_mobile",
    "password": "secret123",
    "parentUserId": 2
  }'
```

### 7. Set RD API Key
```bash
curl -X POST http://localhost:3001/api/admin/users/2/rd-key \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "rdApiKey": "ABC123XYZ",
    "rdExpiryDate": "2026-12-31"
  }'
```

### 8. Check RD Expiry Alerts
```bash
curl http://localhost:3001/api/admin/rd-expiry-alerts \
  -H "Authorization: Bearer $TOKEN"
```

### 9. Check VOD Session
```bash
curl "http://localhost:3001/api/auth/check-vod-session?ip=192.168.1.100" \
  -H "Authorization: Bearer $TOKEN"
```

Expected: `{"allowed":true,"currentIp":"192.168.1.100"}`

### 10. Send VOD Heartbeat
```bash
curl -X POST http://localhost:3001/api/auth/vod-heartbeat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ip":"192.168.1.100"}'
```

### 11. End VOD Session
```bash
curl -X POST http://localhost:3001/api/auth/vod-session-end \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ip":"192.168.1.100"}'
```

### 12. Update User
```bash
curl -X PUT http://localhost:3001/api/admin/users/2 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "password": "newsecret456"
  }'
```

### 13. Delete User
```bash
curl -X DELETE http://localhost:3001/api/admin/users/2 \
  -H "Authorization: Bearer $TOKEN"
```

### 14. Get User Recommendations
```bash
curl "http://localhost:3001/api/user/recommendations?page=1&limit=20" \
  -H "Authorization: Bearer $TOKEN"
```

### 15. Get Trending Content
```bash
curl "http://localhost:3001/api/user/trending?mediaType=movie&timeWindow=week" \
  -H "Authorization: Bearer $TOKEN"
```

### 16. Add to Watchlist
```bash
curl -X POST http://localhost:3001/api/user/watchlist \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 603,
    "type": "movie",
    "title": "The Matrix",
    "posterPath": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
    "releaseDate": "1999-03-30",
    "voteAverage": 8.2
  }'
```

### 17. Get Watchlist
```bash
curl http://localhost:3001/api/user/watchlist \
  -H "Authorization: Bearer $TOKEN"
```

### 18. Update Watch Progress
```bash
curl -X POST http://localhost:3001/api/user/watch-progress \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 27205,
    "type": "movie",
    "title": "Inception",
    "posterPath": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg",
    "releaseDate": "2010-07-15",
    "position": 1800000,
    "duration": 8880000,
    "voteAverage": 8.4
  }'
```

### 19. Get Watch Progress
```bash
curl http://localhost:3001/api/user/watch-progress \
  -H "Authorization: Bearer $TOKEN"
```

## Testing Scenarios

### Scenario 1: IP Blocking Test
1. User A starts VOD playback from IP 1.2.3.4
2. User A tries to play from IP 5.6.7.8 (should be blocked)
3. Wait 2 minutes (session timeout)
4. User A can now play from IP 5.6.7.8

```bash
# Start session from IP 1.2.3.4
curl "http://localhost:3001/api/auth/check-vod-session?ip=1.2.3.4" \
  -H "Authorization: Bearer $TOKEN"

# Try from different IP (should fail)
curl "http://localhost:3001/api/auth/check-vod-session?ip=5.6.7.8" \
  -H "Authorization: Bearer $TOKEN"

# End first session
curl -X POST http://localhost:3001/api/auth/vod-session-end \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"ip":"1.2.3.4"}'

# Now second IP works
curl "http://localhost:3001/api/auth/check-vod-session?ip=5.6.7.8" \
  -H "Authorization: Bearer $TOKEN"
```

### Scenario 2: Sub-Account Inherits Parent RD Key
1. Create parent user with RD key
2. Create sub-account under parent
3. Sub-account can access VOD (inherits parent's RD key)

### Scenario 3: RD Expiry Alerts
1. Set user RD expiry to tomorrow
2. Check alerts endpoint (should show 1_day alert)
3. Set expiry to past date
4. Check alerts (should show expired)

## Database Inspection

```bash
# Access database
sqlite3 db/duckflix_lite.db

# List tables
.tables

# Show users
SELECT * FROM users;

# Show sessions
SELECT * FROM user_sessions;

# Exit
.quit
```

## Logs

Development logs go to console. Production logs:
- `logs/combined.log` - All logs
- `logs/error.log` - Errors only

## Common Issues

### Port Already in Use
```bash
# Find process on port 3001
lsof -i :3001

# Kill it
kill <PID>
```

### Database Locked
```bash
# Remove WAL files
rm db/*.db-shm db/*.db-wal
```

### Reset Database
```bash
# WARNING: Deletes all data
rm db/duckflix_lite.db
# Restart server to recreate
```

## Production Deployment Notes

When deploying to duckflix.tv:
1. Change `JWT_SECRET` in .env
2. Change `ADMIN_PASSWORD` in .env
3. Set `NODE_ENV=production`
4. Use PM2 for process management
5. Configure nginx reverse proxy
6. Set up log rotation

See Task 1.12 in MASTER_PLAN.md for deployment steps.
