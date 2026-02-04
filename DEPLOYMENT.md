# DuckFlix Lite Server - Deployment Guide

**⚠️ DO NOT DELETE THIS FILE - CONTAINS CRITICAL CREDENTIALS ⚠️**

## 🚨 CRITICAL: Real-Debrid API Key Policy

**EACH USER MUST HAVE THEIR OWN UNIQUE RD API KEY**

- **NEVER** share RD API keys between users
- **NEVER** use the same RD key on multiple accounts
- **NEVER** set RD_API_KEY in .env (ENV must be empty)
- Sharing keys = INSTANT BAN from Real-Debrid
- Each user sets their key via admin panel
- Tawnia's test key: ONLY for remote testing, NEVER on local network

## Production Server Details

- **Host**: duckflix.tv (Ubuntu LTS)
- **User**: ducky
- **SSH Password**: `claude`
- **Sudo Password**: `duckadmin`
- **SSH Command**: `ssh ducky@192.168.4.66`
- **Install Path**: `~/duckflix-lite-server-v2/` (actual: /home/ducky/duckflix-lite-server-v2)
- **Public URL**: `https://duckflix.tv/lite_service`
- **Process Manager**: PM2 (process name: duckflix-lite-server-v2)
- **Reverse Proxy**: Nginx

**CREDENTIALS SUMMARY:**
```bash
ssh ducky@192.168.4.66
# SSH Password: claude
# Sudo Password: duckadmin
```

## Prerequisites

Server should have:
- Node.js 20+
- PM2 installed globally
- Nginx configured
- Git (for deployment)

## Initial Deployment

### 1. Prepare Local Repository

```bash
cd /Users/aaron/projects/duckflix_lite
git init
git add .
git commit -m "Initial DuckFlix Lite commit"
```

### 2. SSH to Production Server

```bash
ssh ducky@192.168.4.66
# Password: claude
```

### 3. Create Directory Structure

```bash
sudo mkdir -p /var/lib/duckflix_lite/{server,db,logs,static/apk}
sudo chown -R ducky:ducky /var/lib/duckflix_lite
cd /var/lib/duckflix_lite
```

### 4. Transfer Files

From local machine:
```bash
# Option A: Using rsync (recommended)
rsync -avz --exclude 'node_modules' --exclude 'db/*.db' \
  /Users/aaron/projects/duckflix_lite/ \
  ducky@192.168.4.66:/var/lib/duckflix_lite/

# Option B: Using Git
# Push to a Git remote, then pull on server
```

### 5. Install Dependencies on Server

```bash
ssh ducky@192.168.4.66
cd /var/lib/duckflix_lite/server
npm install --production
```

### 6. Configure Environment

```bash
cd /var/lib/duckflix_lite/server
cp .env.example .env
nano .env
```

**Required changes in .env**:
```bash
NODE_ENV=production
JWT_SECRET=<generate-random-string-32-chars>
ADMIN_PASSWORD=<secure-password>
TMDB_API_KEY=<your-tmdb-key>
ZURG_MOUNT_PATH=/mnt/zurg
EPG_SOURCE_URL=<your-epg-url>
M3U_SOURCE_URL=<your-m3u-url>
```

Generate JWT secret:
```bash
openssl rand -base64 32
```

### 7. Set Up PM2

```bash
cd /var/lib/duckflix_lite/server
pm2 start ecosystem.config.js
pm2 save
pm2 startup
# Run the command PM2 outputs
```

### 8. Configure Nginx

```bash
sudo nano /etc/nginx/sites-available/duckflix
```

Add location block:
```nginx
# DuckFlix Lite API
location /lite_service/ {
    proxy_pass http://localhost:3001/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_set_header Host $host;
    proxy_cache_bypass $http_upgrade;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Test and reload nginx:
```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 9. Verify Deployment

```bash
# Check server is running
pm2 status

# Check logs
pm2 logs duckflix-lite

# Test health endpoint
curl http://localhost:3001/health

# Test via nginx
curl https://duckflix.tv/lite_service/health
```

### 10. Test API

```bash
# Login
curl -X POST https://duckflix.tv/lite_service/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<your-password>"}'
```

## Updates / Redeployment

### Quick Update

```bash
# From local machine
rsync -avz --exclude 'node_modules' --exclude 'db/*.db' \
  /Users/aaron/projects/duckflix_lite/server/ \
  ducky@192.168.4.66:/var/lib/duckflix_lite/server/

# On server
ssh ducky@192.168.4.66
cd /var/lib/duckflix_lite/server
npm install --production
pm2 restart duckflix-lite
```

### Zero-Downtime Update

```bash
# On server
cd /var/lib/duckflix_lite/server
git pull  # or rsync
npm install --production
pm2 reload duckflix-lite  # reload instead of restart
```

## Monitoring

### PM2 Commands

```bash
# Status
pm2 status

# Logs (live tail)
pm2 logs duckflix-lite

# Last 100 lines
pm2 logs duckflix-lite --lines 100

# Error logs only
pm2 logs duckflix-lite --err

# Stop
pm2 stop duckflix-lite

# Restart
pm2 restart duckflix-lite

# Delete from PM2
pm2 delete duckflix-lite
```

### Application Logs

```bash
# Server logs (Winston)
tail -f /var/lib/duckflix_lite/server/logs/combined.log
tail -f /var/lib/duckflix_lite/server/logs/error.log

# PM2 logs
tail -f /var/lib/duckflix_lite/logs/pm2-combined.log
```

### Database

```bash
cd /var/lib/duckflix_lite/server
sqlite3 db/duckflix_lite.db

# Useful queries
SELECT COUNT(*) FROM users;
SELECT * FROM user_sessions;
SELECT channel_id, updated_at FROM epg_cache;
.exit
```

## Backup

### Automated Backup Script

```bash
#!/bin/bash
# /var/lib/duckflix_lite/backup.sh

DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_DIR=/var/lib/duckflix_lite/backups
mkdir -p $BACKUP_DIR

# Backup database
cp /var/lib/duckflix_lite/server/db/duckflix_lite.db \
   $BACKUP_DIR/duckflix_lite-$DATE.db

# Backup .env
cp /var/lib/duckflix_lite/server/.env \
   $BACKUP_DIR/env-$DATE.bak

# Keep only last 30 days
find $BACKUP_DIR -name "*.db" -mtime +30 -delete

echo "Backup complete: $DATE"
```

Make executable and add to cron:
```bash
chmod +x /var/lib/duckflix_lite/backup.sh
crontab -e
# Add: 0 2 * * * /var/lib/duckflix_lite/backup.sh
```

### Manual Backup

```bash
# Backup everything
tar -czf duckflix-lite-backup-$(date +%Y%m%d).tar.gz \
  /var/lib/duckflix_lite/server/db \
  /var/lib/duckflix_lite/server/.env
```

## Troubleshooting

### Server Won't Start

```bash
# Check PM2 logs
pm2 logs duckflix-lite --err

# Check port availability
netstat -tlnp | grep 3001

# Check file permissions
ls -la /var/lib/duckflix_lite/server

# Try running directly
cd /var/lib/duckflix_lite/server
NODE_ENV=production node index.js
```

### Database Issues

```bash
# Check database file exists
ls -lh /var/lib/duckflix_lite/server/db/

# Check permissions
chmod 644 /var/lib/duckflix_lite/server/db/duckflix_lite.db

# Rebuild database (WARNING: deletes data)
rm /var/lib/duckflix_lite/server/db/duckflix_lite.db*
pm2 restart duckflix-lite
```

### Nginx 502 Bad Gateway

```bash
# Check server is running
pm2 status

# Check server port
curl http://localhost:3001/health

# Check nginx error logs
sudo tail -f /var/log/nginx/error.log

# Restart nginx
sudo systemctl restart nginx
```

### High Memory Usage

```bash
# Check memory
pm2 monit

# Restart if needed
pm2 restart duckflix-lite

# Check for memory leaks in logs
pm2 logs duckflix-lite | grep -i memory
```

## Security Checklist

- [ ] Change default admin password
- [ ] Generate strong JWT_SECRET
- [ ] Ensure .env is not publicly readable
- [ ] Set up firewall (ufw) - allow 22, 80, 443
- [ ] Enable automatic security updates
- [ ] Set up log rotation
- [ ] Regular database backups
- [ ] Monitor disk space
- [ ] Review user access logs

## Performance Tuning

### Database

```sql
-- Add indexes if needed
CREATE INDEX IF NOT EXISTS idx_sessions_user_ip ON user_sessions(user_id, ip_address);
CREATE INDEX IF NOT EXISTS idx_sessions_heartbeat ON user_sessions(last_heartbeat_at);
```

### PM2 Memory Limit

Edit `ecosystem.config.js`:
```javascript
max_memory_restart: '500M'  // Restart if exceeds 500MB
```

### Nginx Caching

Add to nginx config for static assets:
```nginx
location /lite_service/static/ {
    proxy_pass http://localhost:3001/static/;
    proxy_cache_valid 200 1d;
    expires 1d;
    add_header Cache-Control "public, immutable";
}
```

## Rollback

If something goes wrong:

```bash
# Stop current version
pm2 stop duckflix-lite

# Restore backup
cd /var/lib/duckflix_lite/server
cp /var/lib/duckflix_lite/backups/duckflix_lite-YYYYMMDD.db \
   db/duckflix_lite.db
cp /var/lib/duckflix_lite/backups/env-YYYYMMDD.bak .env

# Restart
pm2 restart duckflix-lite
```

## Health Checks

Set up automated health checks:

```bash
# Add to crontab
*/5 * * * * curl -f http://localhost:3001/health || pm2 restart duckflix-lite
```

## APK Deployment

When Android APK is ready:

```bash
# Copy APK to server
scp android/app/build/outputs/apk/release/app-release.apk \
  ducky@192.168.4.66:/var/lib/duckflix_lite/server/static/apk/latest.apk

# Verify
curl -I https://duckflix.tv/lite_service/api/apk/latest
```

## Download Monitor (TUI)

Real-time monitoring tool for active and completed downloads.

### Start Monitor

```bash
cd ~/duckflix-lite-server-v2
./monitor.sh
```

### Features

- Shows active downloads with real-time progress
- User, IP, RD API key (last 4 chars)
- Content title, source quality, file size
- Keeps last 20 completed downloads
- Auto-refreshes every second
- Press `Q` or `ESC` to quit

### Monitor Display

**Active Downloads:**
- User, IP, RD Key, Content, Source, Progress, Elapsed Time

**Recently Completed:**
- User, IP, RD Key, Content, Size, Status (OK/FAIL), Duration

## Support

If issues persist:
1. Check server logs: `pm2 logs duckflix-lite`
2. Check nginx logs: `sudo tail -f /var/log/nginx/error.log`
3. Check system resources: `htop`
4. Verify environment variables: `pm2 env duckflix-lite`
5. Monitor downloads in real-time: `./monitor.sh`

---

**Last Updated**: 2026-02-01
**Server Version**: 1.0.0
**Deployment Path**: `/var/lib/duckflix_lite/`
