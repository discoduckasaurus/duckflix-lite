const express = require('express');
const https = require('https');
const fs = require('fs');
const cors = require('cors');
const path = require('path');
require('dotenv').config();

const logger = require('./utils/logger');
const { initDatabase, createAdminUser } = require('./db/init');
const { startSyncJobs } = require('./services/epg-sync');
const authRoutes = require('./routes/auth');
const adminRoutes = require('./routes/admin');
const adminDashboardRoutes = require('./routes/admin-dashboard');
const epgRoutes = require('./routes/epg');
const searchRoutes = require('./routes/search');
const publicRoutes = require('./routes/public-routes');
const rdRoutes = require('./routes/rd');
const vodRoutes = require('./routes/vod');
const apkRoutes = require('./routes/apk');
const userRoutes = require('./routes/user');
const contentRoutes = require('./routes/content');

const app = express();
const PORT = process.env.PORT || 3001;

// Middleware
// SECURITY: Configure CORS with allowed origins
const allowedOrigins = process.env.ALLOWED_ORIGINS
  ? process.env.ALLOWED_ORIGINS.split(',')
  : ['http://localhost:3000', 'http://localhost:3001'];

app.use(cors({
  origin: allowedOrigins,
  credentials: true,
  maxAge: 600 // 10 minutes
}));

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Request logging
app.use((req, res, next) => {
  logger.info(`${req.method} ${req.path}`, {
    ip: req.ip,
    userAgent: req.get('user-agent')
  });
  next();
});

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// API Routes
app.use('/api/auth', authRoutes);
app.use('/api/public', publicRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/admin', adminDashboardRoutes);
app.use('/api/epg', epgRoutes);
app.use('/api/m3u', epgRoutes); // M3U uses same routes as EPG
app.use('/api/search', searchRoutes);
app.use('/api/rd', rdRoutes);
app.use('/api/vod', vodRoutes);
app.use('/api/apk', apkRoutes);
app.use('/api/user', userRoutes);
app.use('/api/content', contentRoutes);

// Static files (APK hosting)
app.use('/static', express.static(path.join(__dirname, 'static')));

// Admin dashboard (SPA - serve index.html for all /admin routes)
app.get('/admin*', (req, res) => {
  res.sendFile(path.join(__dirname, 'static', 'admin', 'index.html'));
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Error handler
app.use((err, req, res, next) => {
  logger.error('Server error:', err);
  res.status(err.status || 500).json({
    error: err.message || 'Internal server error',
    ...(process.env.NODE_ENV === 'development' && { stack: err.stack })
  });
});

// Initialize database and start server
async function start() {
  try {
    logger.info('Initializing database...');
    await initDatabase();

    logger.info('Creating admin user if not exists...');
    await createAdminUser();

    logger.info('Starting EPG/M3U sync jobs...');
    startSyncJobs();

    // Start RD cache cleanup job (runs every hour)
    logger.info('Starting RD cache cleanup job...');
    const rdCacheService = require('./services/rd-cache-service');
    setInterval(async () => {
      try {
        await rdCacheService.cleanupExpired();
      } catch (error) {
        logger.error('[RD Cache] Cleanup failed:', error);
      }
    }, 60 * 60 * 1000); // 1 hour

    // Start RD expiry checker job (runs every 6 hours)
    logger.info('Starting RD expiry checker job...');
    const { checkAllUsersRDExpiry } = require('./services/rd-expiry-checker');

    // Run once on startup
    setTimeout(async () => {
      try {
        await checkAllUsersRDExpiry();
      } catch (error) {
        logger.error('[RD Expiry] Initial check failed:', error);
      }
    }, 30000); // Wait 30s after startup

    // Then run every 6 hours
    setInterval(async () => {
      try {
        await checkAllUsersRDExpiry();
      } catch (error) {
        logger.error('[RD Expiry] Periodic check failed:', error);
      }
    }, 6 * 60 * 60 * 1000); // 6 hours

    // Start RD session cleanup job (runs every 30 seconds)
    logger.info('Starting RD session cleanup job...');
    const { cleanupExpiredRdSessions } = require('./services/rd-session-tracker');

    setInterval(() => {
      try {
        cleanupExpiredRdSessions();
      } catch (error) {
        logger.error('[RD Session] Cleanup failed:', error);
      }
    }, 30000); // 30 seconds

    // HTTPS server configuration
    const httpsOptions = {
      key: fs.readFileSync(path.join(__dirname, 'certs', 'key.pem')),
      cert: fs.readFileSync(path.join(__dirname, 'certs', 'cert.pem'))
    };

    https.createServer(httpsOptions, app).listen(PORT, () => {
      logger.info(`DuckFlix Lite Server running on port ${PORT} (HTTPS)`);
      logger.info(`Environment: ${process.env.NODE_ENV || 'development'}`);
      logger.info(`Health check: https://localhost:${PORT}/health`);
    });
  } catch (error) {
    logger.error('Failed to start server:', error);
    process.exit(1);
  }
}

// Graceful shutdown
process.on('SIGTERM', () => {
  logger.info('SIGTERM received, shutting down gracefully');
  const { db } = require('./db/init');
  db.close();
  process.exit(0);
});

process.on('SIGINT', () => {
  logger.info('SIGINT received, shutting down gracefully');
  const { db } = require('./db/init');
  db.close();
  process.exit(0);
});

start();
