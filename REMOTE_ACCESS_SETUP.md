# DuckFlix Lite - Remote Access Configuration

**⚠️ DO NOT DELETE - Complete Remote Access Setup Guide ⚠️**

## ✅ Configuration Complete

All systems configured for remote access from any network.

---

## Server Credentials

**SSH Access:**
```bash
ssh ducky@192.168.4.66
# SSH Password: claude
# Sudo Password: duckadmin
```

**PM2 Process:** `duckflix-lite-server-v2`

---

## Public URL Configuration

### API Endpoint
- **Public URL**: `https://duckflix.tv/lite_service/api/`
- **Health Check**: `https://duckflix.tv/lite_service/health`
- **Server**: Running on host at `https://192.168.4.66:3001`
- **Nginx Proxy**: Docker container `duckflix-nginx`
- **Config Location**: `/home/ducky/duckflix/nginx/conf.d/duckflix.conf`

### Testing Endpoints
```bash
# Health check
curl https://duckflix.tv/lite_service/health

# Login test
curl -X POST https://duckflix.tv/lite_service/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"Tawnia","password":"jjjjjj"}'

# Search test (with token)
curl https://duckflix.tv/lite_service/api/search/movie?query=inception \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

---

## Test User Account

**Remote Tester Credentials:**
- **Username**: `Tawnia`
- **Password**: `jjjjjj`
- **RD API Key**: `IOGOUVDH4JSBH57UJAFDP3O375DCPSKP7ERWPURNCP3CCNUSFPKQ`
- **⚠️ WARNING**: This RD API key must ONLY be used from remote networks, NOT the local 192.168.4.x network

**Features:**
- Separate RD API key (won't affect local network usage)
- Token persists across app restarts (no auto-login needed)
- Sub-account capable (can create sub-accounts that inherit RD key)

---

## Android APK Configuration

### Current Build
- **Base URL**: `https://duckflix.tv/lite_service/api/`
- **SSL**: Uses system certificate validation (no self-signed cert bypass)
- **Auto-login**: Disabled (token caching enabled)

### APK Download Links
```
https://duckflix.tv/lite_service/static/apk/duckflix-lite-debug.apk
https://duckflix.tv/lite_service/static/apk/duckflix-lite-latest.apk
```

---

## Remote Installation Methods

### Method 1: Direct Download on Device
1. On Android TV, open a browser
2. Navigate to: `https://duckflix.tv/lite_service/static/apk/duckflix-lite-latest.apk`
3. Download and install

### Method 2: Tailscale + ADB (Recommended for Testing)

**Prerequisites:**
- Tailscale installed on both ends
- ADB enabled on Android TV
- Both devices connected to Tailscale network

**Setup Tailscale on Android TV:**
1. Install Tailscale from Play Store
2. Login and connect to network
3. Enable "Settings → Device Preferences → Developer Options → USB Debugging"
4. Note the Tailscale IP address (e.g., `100.x.x.x`)

**Install via ADB over Tailscale:**
```bash
# From your Mac (connected to Tailscale)
adb connect 100.x.x.x:5555  # Use Android TV's Tailscale IP

# Verify connection
adb devices

# Install APK
adb install -r /Users/aaron/projects/duckflix_lite/android/app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.duckflix.lite.debug/com.duckflix.lite.MainActivity
```

**ADB Commands:**
```bash
# View logs
adb logcat | grep DuckFlix

# Clear app data
adb shell pm clear com.duckflix.lite.debug

# Uninstall
adb uninstall com.duckflix.lite.debug
```

### Method 3: Cloud Share Link
1. Upload APK to Google Drive/Dropbox
2. Share link with tester
3. Download and install on Android TV

---

## Network Configuration

### What's Configured

**✅ Public DNS**: duckflix.tv points to your public IP
**✅ Nginx Proxy**: Routes `/lite_service/` → `https://192.168.4.66:3001`
**✅ SSL Certificate**: Let's Encrypt cert for duckflix.tv (valid for public access)
**✅ CORS**: Configured to allow Android app requests
**✅ Firewall**: Ports 80/443 open to public

### Firewall Rules
```bash
# On server, check current rules
sudo ufw status

# Should show:
# 22/tcp   ALLOW   Anywhere (SSH)
# 80/tcp   ALLOW   Anywhere (HTTP)
# 443/tcp  ALLOW   Anywhere (HTTPS)
```

---

## Testing Checklist

### From Remote Network (Tester's Home)

- [ ] Can access `https://duckflix.tv/lite_service/health`
- [ ] Can login with Tawnia credentials
- [ ] Can search for content
- [ ] Can play videos (verify RD download works)
- [ ] Token persists after app restart
- [ ] No connection to local 192.168.4.66 network

### Verify Separate RD Keys Working
```bash
# On server, check user's RD API key
ssh ducky@192.168.4.66
cd ~/duckflix-lite-server-v2
sqlite3 db/duckflix_lite.db "SELECT username, substr(rd_api_key, 1, 10) || '...' as key_preview FROM users WHERE username IN ('admin', 'Tawnia');"
```

Should show:
```
admin|IOGOUVDH4J...  (ENV key)
Tawnia|IOGOUVDH4J... (test key)
```

---

## Troubleshooting

### App Can't Connect
```bash
# Check if server is running
pm2 status duckflix-lite-server-v2

# Check server logs
pm2 logs duckflix-lite-server-v2 --lines 50

# Restart server
pm2 restart duckflix-lite-server-v2
```

### Nginx Issues
```bash
# Check nginx status
docker ps | grep nginx

# View nginx logs
docker logs duckflix-nginx --tail 50

# Restart nginx
docker restart duckflix-nginx

# Test nginx config
docker exec duckflix-nginx nginx -t
```

### DNS Not Resolving
```bash
# From remote network
nslookup duckflix.tv
ping duckflix.tv

# Should return your public IP
```

### SSL Certificate Issues
```bash
# Check cert validity
openssl s_client -connect duckflix.tv:443 -servername duckflix.tv </dev/null 2>/dev/null | openssl x509 -noout -dates

# Renew cert if needed (on server)
docker compose -f ~/duckflix/docker-compose.yml run --rm certbot renew
docker restart duckflix-nginx
```

---

## Email Report Feature

**Status**: Backend ready, needs SendGrid API key

**To Enable:**
```bash
# Add to .env file on server
echo "SENDGRID_API_KEY=your_key_here" >> ~/duckflix-lite-server-v2/.env
echo "ADMIN_EMAIL=admin@duckflix.tv" >> ~/duckflix-lite-server-v2/.env

# Restart server
pm2 restart duckflix-lite-server-v2
```

**Usage from App:**
```
POST https://duckflix.tv/lite_service/api/report
Authorization: Bearer <token>

{
  "page": "home",
  "title": "The Office",
  "tmdbId": "2316",
  "message": "Playback failed"
}
```

---

## Security Notes

- **RD API Key Separation**: Each user has their own RD key, preventing network conflicts
- **SSL/TLS**: All traffic encrypted via Let's Encrypt certificate
- **Token Authentication**: JWT tokens with 7-day expiration
- **IP Session Tracking**: Prevents concurrent streams from different IPs
- **Sub-Account Support**: Family members can share parent's RD key via sub-accounts

---

## Maintenance

### Update Server Code
```bash
ssh ducky@192.168.4.66
cd ~/duckflix-lite-server-v2
git pull  # or upload via SFTP
npm install
pm2 restart duckflix-lite-server-v2
```

### Deploy New APK
```bash
# From Mac
scp -i ~/.ssh/duckserver \
  android/app/build/outputs/apk/release/app-release.apk \
  ducky@192.168.4.66:~/duckflix-lite-server-v2/static/apk/duckflix-lite-latest.apk
```

### Check User Activity
```bash
# On server
cd ~/duckflix-lite-server-v2
sqlite3 db/duckflix_lite.db "SELECT username, ip_address, last_vod_playback_at FROM user_sessions ORDER BY last_vod_playback_at DESC LIMIT 10;"
```

---

**Last Updated**: 2026-02-02
**Remote Access Status**: ✅ FULLY OPERATIONAL
