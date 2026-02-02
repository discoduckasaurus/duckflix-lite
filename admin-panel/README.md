# DuckFlix Lite - Admin Panel

React SPA for managing DuckFlix Lite users and monitoring Real-Debrid subscriptions.

## Features

- **Dashboard**: Overview of system stats
- **User Management**: Create, view, and delete users
- **RD Expiry Alerts**: Monitor expiring Real-Debrid subscriptions
- **Responsive Design**: TailwindCSS styling

## Development

```bash
npm install
npm run dev
```

Runs on http://localhost:3000 with API proxy to http://localhost:3001

## Building

```bash
npm run build
```

Builds to `../server/static/admin` for production deployment.

## Production

Admin panel is served by the Express server at:
```
https://duckflix.tv/lite_service/admin
```

## Tech Stack

- React 18
- React Router 6
- Axios
- TailwindCSS
- Vite

## API Integration

Uses the DuckFlix Lite server API:
- `POST /api/auth/login` - Admin login
- `GET /api/admin/users` - List users
- `POST /api/admin/users` - Create user
- `DELETE /api/admin/users/:id` - Delete user
- `GET /api/admin/rd-expiry-alerts` - Get expiry alerts

## Security

- JWT token stored in localStorage
- Auto-logout on 401 responses
- Admin-only access required
