# DuckFlix Lite Server - Changelog

## 2026-02-01 - Phase 1, Tasks 1.1-1.8 Complete

### Server Scaffold Created (Task 1.1)
- Express server with production-ready structure
- Winston logging with console and file transports
- Environment-based configuration (.env)
- Health check endpoint: `GET /health`
- Error handling middleware
- Request logging middleware
- CORS enabled

### Database Setup (Task 1.2)
- SQLite database using better-sqlite3
- WAL mode for better concurrency
- Complete schema implementation:
  - `users` - User accounts with admin flag, parent_user_id for sub-accounts
  - `user_sessions` - IP tracking for VOD with UNIQUE(user_id, ip_address)
  - `epg_cache` - EPG data caching
  - `m3u_sources` - M3U source management
  - `rd_alerts` - RD expiry alert logging
- Automatic admin user creation on first startup
- Database cleanup cron (expired sessions every 5 minutes)

### Authentication (Task 1.3)
- JWT token generation with 7-day expiry
- JWT verification middleware
- Admin-only middleware
- Endpoints:
  - `POST /api/auth/login` - Username/password login
  - `GET /api/auth/me` - Get current user
  - `POST /api/auth/logout` - Logout (client-side token deletion)

### User Management (Task 1.4)
- Full CRUD API for users (admin only)
- Endpoints:
  - `GET /api/admin/users` - List all users
  - `POST /api/admin/users` - Create user
  - `PUT /api/admin/users/:id` - Update user
  - `DELETE /api/admin/users/:id` - Delete user (cascades to sub-accounts)
- Prevent self-deletion
- Bcrypt password hashing (10 rounds)
- Unique username constraint

### Sub-Account Logic (Task 1.5)
- `parent_user_id` foreign key in users table
- Sub-accounts inherit parent's RD API key
- Cascading delete when parent deleted
- Validation: parent must exist when creating sub-account

### RD API Key Management (Task 1.6)
- RD API key storage per user
- RD expiry date tracking
- Dedicated endpoint: `POST /api/admin/users/:id/rd-key`
- Update username, password, RD key independently

### VOD IP Tracking (Task 1.7)
- Session tracking with IP address
- 1 IP per account enforcement
- Endpoints:
  - `GET /api/auth/check-vod-session?ip=<ip>` - Check if playback allowed
  - `POST /api/auth/vod-heartbeat` - Update heartbeat (30s interval)
  - `POST /api/auth/vod-session-end` - End session
- Configurable timeout (default: 2 minutes)
- Automatic cleanup of expired sessions

### RD Expiry Alerts (Task 1.8)
- Alert calculation endpoint: `GET /api/admin/rd-expiry-alerts`
- Alert types: expired, 1_day, 5_days
- Returns all users with expiring RD keys
- Includes days until expiry

### Testing
All endpoints tested and working:
- Health check ✅
- Login ✅
- Get current user ✅
- List users (admin) ✅
- Create user ✅
- VOD session check ✅

### File Structure
```
server/
├── index.js              # Main entry point
├── package.json          # Dependencies
├── .env.example          # Config template
├── .env                  # Local config
├── .gitignore            # Git ignore rules
├── README.md             # Server documentation
├── db/
│   └── init.js           # Database initialization
├── middleware/
│   └── auth.js           # JWT middleware
├── routes/
│   ├── auth.js           # Auth endpoints
│   ├── admin.js          # Admin endpoints
│   ├── epg.js            # EPG/M3U endpoints (stubs)
│   ├── search.js         # Search endpoints (stubs)
│   └── apk.js            # APK hosting endpoints (stubs)
└── utils/
    └── logger.js         # Winston logger
```

### Next Steps (Tasks 1.9-1.12)
1. Implement EPG/M3U sync with cron jobs
2. Implement Zurg search criteria API using @duckflix/zurg-client
3. Test APK hosting endpoint
4. Deploy to duckflix.tv/lite_service with PM2 and nginx
