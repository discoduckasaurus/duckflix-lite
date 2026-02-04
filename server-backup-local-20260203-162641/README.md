# DuckFlix Lite Server

Minimal Express server for DuckFlix Lite Android TV app.

## Features

- JWT authentication
- User management (admin only)
- Sub-account support
- RD API key management
- IP-based VOD session tracking (1 IP per account)
- RD expiry alerts
- EPG/M3U sync and caching
- Content search (TMDB, Zurg, Prowlarr)
- APK hosting

## Installation

```bash
npm install
cp .env.example .env
# Edit .env with your configuration
npm start
```

## Development

```bash
npm run dev  # Uses nodemon for auto-reload
```

## Environment Variables

See `.env.example` for all configuration options.

**Critical variables**:
- `JWT_SECRET` - Change in production!
- `ADMIN_PASSWORD` - Change in production!
- `TMDB_API_KEY` - Required for search
- `DB_PATH` - Database file location

## API Endpoints

### Authentication
- `POST /api/auth/login` - Login with username/password
- `GET /api/auth/me` - Get current user info
- `POST /api/auth/logout` - Logout
- `GET /api/auth/check-vod-session` - Check if VOD playback allowed
- `POST /api/auth/vod-heartbeat` - Update VOD session heartbeat
- `POST /api/auth/vod-session-end` - End VOD session

### Admin (requires admin token)
- `GET /api/admin/users` - List all users
- `POST /api/admin/users` - Create user
- `PUT /api/admin/users/:id` - Update user
- `DELETE /api/admin/users/:id` - Delete user
- `POST /api/admin/users/:id/rd-key` - Set RD API key
- `GET /api/admin/rd-expiry-alerts` - Get expiry alerts

### EPG/M3U
- `GET /api/epg` - Get EPG data (JSON)
- `GET /api/m3u` - Get M3U playlist (JSON)

### Search
- `GET /api/search/tmdb?query=<title>&type=movie|tv` - Search TMDB
- `GET /api/search/zurg?title=<title>&year=<year>` - Search Zurg
- `GET /api/search/prowlarr?query=<query>` - Search Prowlarr

### APK
- `GET /api/apk/latest` - Download latest APK
- `GET /api/apk/version` - Get APK version info

## Database Schema

See `/db/init.js` for full schema.

**Tables**:
- `users` - User accounts
- `user_sessions` - IP tracking for VOD
- `epg_cache` - Cached EPG data
- `m3u_sources` - M3U source URLs
- `rd_alerts` - RD expiry alert log

## Deployment

Production deployment to `duckflix.tv/lite_service`:

```bash
# On server (ducky@192.168.4.66)
cd /var/lib/duckflix_lite
git pull
npm install --production
pm2 restart duckflix-lite
```

See `MASTER_PLAN.md` Phase 1, Task 1.12 for full deployment instructions.

## Testing

```bash
# Health check
curl http://localhost:3001/health

# Login
curl -X POST http://localhost:3001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme123"}'

# Get users (admin only)
curl http://localhost:3001/api/admin/users \
  -H "Authorization: Bearer <token>"
```

## Architecture

- **Express** - HTTP server
- **better-sqlite3** - Fast, synchronous SQLite
- **JWT** - Stateless authentication
- **Winston** - Structured logging
- **bcryptjs** - Password hashing

See `ARCHITECTURE.md` for full system design.
