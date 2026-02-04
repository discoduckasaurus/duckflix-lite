# Admin Dashboard - Quick Start

## What Was Built

A web-based admin dashboard at `/admin` that:
- ✅ Uses your existing admin API endpoints
- ✅ Provides user management (CRUD operations)
- ✅ Displays dashboard stats and playback failures
- ✅ Shows service health monitoring
- ✅ Manages loading phrases
- ✅ Works on desktop browsers (no TV remote navigation needed)

## Files Created

```
/static/admin/
├── index.html    # Main HTML structure
├── app.js        # JavaScript logic and API calls
└── styles.css    # Modern, clean styling

/nginx-admin.conf.example  # nginx configuration
/ADMIN_SETUP.md           # Detailed setup guide
```

## Quick Test (Local)

1. **Start the server:**
   ```bash
   cd /home/ducky/duckflix-lite-server-v2
   node index.js
   ```

2. **Open browser:**
   ```
   https://localhost:3001/admin
   ```

3. **Login with admin credentials from .env:**
   - Username: `admin` (or your ADMIN_USERNAME)
   - Password: Your ADMIN_PASSWORD

## Deploy to Production (nginx)

### Step 1: Test Server

Make sure your server is running and admin route works:
```bash
curl -k https://localhost:3001/admin
# Should return HTML content
```

### Step 2: Set Up nginx

```bash
# Copy example config
sudo cp nginx-admin.conf.example /etc/nginx/sites-available/duckflix-lite

# Edit the config (adjust SSL paths if needed)
sudo nano /etc/nginx/sites-available/duckflix-lite

# Enable the site
sudo ln -s /etc/nginx/sites-available/duckflix-lite /etc/nginx/sites-enabled/

# Test nginx config
sudo nginx -t

# Reload nginx
sudo systemctl reload nginx
```

### Step 3: Access Dashboard

Navigate to: **https://lite.duckflix.tv/admin**

## What's Already Configured

- ✅ Express route added to serve `/admin`
- ✅ CORS updated to allow `https://lite.duckflix.tv`
- ✅ Admin API endpoints are ready
- ✅ JWT authentication with admin flag

## Next Steps

1. **Test locally first** - Make sure the dashboard loads at localhost
2. **Set up nginx** - Configure the reverse proxy
3. **Get SSL certificate** - If not already set up:
   ```bash
   sudo certbot --nginx -d lite.duckflix.tv
   ```
4. **Restart services** - Restart Express server and reload nginx

## Troubleshooting

### Dashboard shows blank page
- Check browser console for errors
- Verify server is running: `curl -k https://localhost:3001/health`

### Login fails
- Check admin password in `.env` is set
- Check JWT_SECRET is set in `.env`

### CORS errors in browser console
- Verify ALLOWED_ORIGINS includes your domain
- Restart the server after changing `.env`

### nginx 502 error
- Ensure Express server is running on port 3001
- Check Express is using HTTPS (not HTTP)

## API Credentials

The dashboard authenticates using:
- Endpoint: `POST /api/auth/login`
- Body: `{"username": "...", "password": "..."}`
- Returns: JWT token with `isAdmin: true`

All subsequent API requests include: `Authorization: Bearer <token>`

## Security

- Admin-only endpoints protected by `requireAdmin()` middleware
- JWT tokens stored in browser localStorage
- Tokens expire after 7 days (configurable)
- Non-admin users get "Access denied" even if they have a valid token

---

**Ready to test?** Run `node index.js` and visit `https://localhost:3001/admin`
