# Admin Dashboard Setup Guide

The admin dashboard is a web-based interface for managing DuckFlix Lite users, viewing playback failures, and monitoring service health.

## Features

- **Dashboard Overview**: User count, RD expiry alerts, recent failures, service health
- **User Management**: Create, edit, delete users; manage RD API keys
- **Playback Failures**: View and monitor streaming issues
- **Service Health**: Check status of TMDB, Prowlarr, Zurg, and RD API
- **Loading Phrases**: Customize loading screen messages

## Setup Steps

### 1. Configure CORS

Edit your `.env` file and add your subdomain to `ALLOWED_ORIGINS`:

```bash
ALLOWED_ORIGINS=https://lite.duckflix.tv,http://localhost:3000
```

### 2. Restart the Server

```bash
# If using PM2
pm2 restart duckflix-lite

# Or if running directly
node index.js
```

### 3. Set Up nginx

#### Option A: New nginx Configuration

Copy the example config:
```bash
sudo cp nginx-admin.conf.example /etc/nginx/sites-available/duckflix-lite
sudo ln -s /etc/nginx/sites-available/duckflix-lite /etc/nginx/sites-enabled/
```

Edit the config and update:
- SSL certificate paths
- Server name (if different)
- Backend port (if not 3001)

#### Option B: Add to Existing nginx Config

Add these location blocks to your existing `lite.duckflix.tv` server block:

```nginx
location /admin {
    proxy_pass https://localhost:3001;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /static {
    proxy_pass https://localhost:3001;
    proxy_set_header Host $host;
}

location /api {
    proxy_pass https://localhost:3001;
    proxy_set_header Host $host;
    proxy_read_timeout 300s;
}
```

### 4. Test and Reload nginx

```bash
# Test configuration
sudo nginx -t

# Reload nginx
sudo systemctl reload nginx
```

### 5. Access the Dashboard

Navigate to: `https://lite.duckflix.tv/admin`

Login with your admin credentials (from `.env`):
- Username: `ADMIN_USERNAME` (default: admin)
- Password: `ADMIN_PASSWORD`

## Local Testing

You can test the admin dashboard locally before setting up nginx:

1. Make sure your server is running: `node index.js`
2. Navigate to: `https://localhost:3001/admin`
3. Accept the self-signed certificate warning
4. Login with your admin credentials

## Security Notes

- The admin dashboard requires a valid JWT token with `isAdmin: true`
- Non-admin users cannot access admin endpoints even if they know the URLs
- JWT tokens expire after 7 days (configurable via `JWT_EXPIRES_IN`)
- All API requests are logged with IP and user agent

## Troubleshooting

### 404 Error on /admin
- Check that the server restarted after adding the route
- Verify the route was added to `index.js` before the 404 handler

### CORS Errors
- Add your domain to `ALLOWED_ORIGINS` in `.env`
- Restart the server after changing `.env`

### 401 Unauthorized
- Clear browser localStorage: `localStorage.clear()`
- Login again with admin credentials

### "Access denied: Admin privileges required"
- Ensure the user has `is_admin = 1` in the database
- Check the database: `sqlite3 db/duckflix_lite.db "SELECT * FROM users WHERE is_admin = 1;"`

### nginx 502 Bad Gateway
- Verify the Express server is running: `curl -k https://localhost:3001/health`
- Check nginx error logs: `sudo tail -f /var/log/nginx/duckflix-lite-error.log`

## File Locations

- Admin HTML: `/static/admin/index.html`
- Admin JS: `/static/admin/app.js`
- Admin CSS: `/static/admin/styles.css`
- nginx Config: `nginx-admin.conf.example`

## API Endpoints Used

The dashboard consumes these admin API endpoints:
- `POST /api/auth/login` - Authentication
- `GET /api/admin/dashboard` - Dashboard overview
- `GET /api/admin/users` - List users
- `POST /api/admin/users` - Create user
- `PUT /api/admin/users/:id` - Update user
- `DELETE /api/admin/users/:id` - Delete user
- `GET /api/admin/failures` - Playback failures
- `GET /api/admin/health` - Service health
- `GET /api/admin/loading-phrases` - Get loading phrases
- `PUT /api/admin/loading-phrases` - Update loading phrases
