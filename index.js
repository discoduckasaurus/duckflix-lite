// Increase libuv thread pool BEFORE any I/O modules load.
// Default is 4 — Zurg FUSE mount operations (readdir, stat, open, read)
// can consume all threads and starve other async I/O during autoplay transitions.
process.env.UV_THREADPOOL_SIZE = process.env.UV_THREADPOOL_SIZE || '64';

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
const adminChannelRoutes = require('./routes/admin-channels');
const adminProviderRoutes = require('./routes/admin-providers');
const epgRoutes = require('./routes/epg');
const searchRoutes = require('./routes/search');
const publicRoutes = require('./routes/public-routes');
const rdRoutes = require('./routes/rd');
const vodRoutes = require('./routes/vod');
const apkRoutes = require('./routes/apk');
const userRoutes = require('./routes/user');
const contentRoutes = require('./routes/content');
const liveTVRoutes = require('./routes/livetv');
const bandwidthRoutes = require('./routes/bandwidth');
const settingsRoutes = require('./routes/settings');

const app = express();
const PORT = process.env.PORT || 3001;
let server = null;

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
app.use('/api/admin', adminChannelRoutes);
app.use('/api/admin', adminProviderRoutes);
app.use('/api/epg', epgRoutes);
app.use('/api/m3u', epgRoutes); // M3U uses same routes as EPG
app.use('/api/search', searchRoutes);
app.use('/api/rd', rdRoutes);
app.use('/api/vod', vodRoutes);
app.use('/api/apk', apkRoutes);
app.use('/api/user', userRoutes);
app.use('/api/content', contentRoutes);
app.use('/api/bandwidth', bandwidthRoutes);
app.use('/api/settings', settingsRoutes);
app.use('/api/livetv', liveTVRoutes);
app.use('/api/dvr', liveTVRoutes); // DVR routes in same file
app.use('/api', liveTVRoutes); // Logo proxy at /api/logo-proxy

// Static files (APK hosting)
app.use('/static', express.static(path.join(__dirname, 'static')));

// Admin dashboard (SPA - serve index.html for all /admin routes)
app.get('/admin*', (req, res) => {
  res.sendFile(path.join(__dirname, 'static', 'admin', 'index.html'));
});

// Stream proxy for web app (CORS bypass for RD download URLs)
const { pipeline } = require('stream');
app.get('/stream-proxy', async (req, res) => {
  const url = req.query.url;
  if (!url) return res.status(400).json({ error: 'Missing url parameter' });

  try {
    const parsed = new URL(url);

    // Same-origin: internally route instead of external fetch or redirect
    if (parsed.pathname.startsWith('/api/')) {
      req.url = parsed.pathname + parsed.search;
      return app.handle(req, res);
    }

    // External URLs (RD download links): proxy with range support
    const headers = {};
    if (req.headers.range) headers.Range = req.headers.range;

    const upstream = await fetch(url, { headers });
    if (!upstream.ok && upstream.status !== 206) {
      return res.status(upstream.status).json({ error: `Upstream ${upstream.status}` });
    }

    // Forward headers needed for video playback
    const fwd = ['content-type', 'content-length', 'content-range', 'accept-ranges'];
    for (const h of fwd) {
      const v = upstream.headers.get(h);
      if (v) res.setHeader(h, v);
    }

    res.status(upstream.status);
    pipeline(upstream.body, res, (err) => {
      if (err && err.code !== 'ERR_STREAM_PREMATURE_CLOSE') {
        logger.error('[Stream Proxy] Pipeline error:', err.message);
      }
    });
  } catch (err) {
    logger.error('[Stream Proxy] Fetch error:', err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Proxy fetch failed' });
  }
});

// Dev build (test deployment at /devbuild)
app.use('/devbuild', express.static(path.join(__dirname, 'devbuild')));
app.get('/devbuild/*', (req, res) => {
  res.sendFile(path.join(__dirname, 'devbuild', 'index.html'));
});

// Web app (SPA - but never intercept API routes)
app.use(express.static(path.join(__dirname, 'web')));
app.get('*', (req, res, next) => {
  // Don't serve SPA HTML for API routes — let them fall through to 404 JSON handler
  if (req.path.startsWith('/api/')) {
    return next();
  }
  res.sendFile(path.join(__dirname, 'web', 'index.html'));
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

    // Create transcoded/ directory for audio processing output
    const transcodedDir = path.join(__dirname, 'transcoded');
    fs.mkdirSync(transcodedDir, { recursive: true });
    logger.info('Transcoded directory ready');

    // Clean up old transcoded files on startup + daily at 3am
    const { cleanupOldFiles } = require('./services/audio-processor');
    cleanupOldFiles(transcodedDir, 1); // Delete anything older than 1 day

    setInterval(() => {
      try {
        cleanupOldFiles(transcodedDir, 1);
      } catch (error) {
        logger.error('[Cleanup] Old files cleanup failed:', error);
      }
    }, 24 * 60 * 60 * 1000); // Daily

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

    // DFTV pseudo-live channel: refresh EPG every 6 hours
    logger.info('Starting DFTV EPG refresh job...');
    const dftvService = require('./services/dftv-service');

    // Initial EPG refresh after 10s (let DB initialize)
    setTimeout(() => {
      try {
        dftvService.refreshEPG();
      } catch (error) {
        logger.error('[DFTV] Initial EPG refresh failed:', error);
      }
    }, 10000);

    // Then every 6 hours
    setInterval(() => {
      try {
        dftvService.refreshEPG();
      } catch (error) {
        logger.error('[DFTV] EPG refresh failed:', error);
      }
    }, 6 * 60 * 60 * 1000);

    // WAL checkpoint every 6 hours to prevent WAL file growth
    setInterval(() => {
      try {
        const { db } = require('./db/init');
        db.pragma('wal_checkpoint(TRUNCATE)');
        logger.info('[DB] WAL checkpoint completed');
      } catch (error) {
        logger.error('[DB] WAL checkpoint failed:', error);
      }
    }, 6 * 60 * 60 * 1000); // 6 hours

    // HTTPS server configuration
    const httpsOptions = {
      key: fs.readFileSync(path.join(__dirname, 'certs', 'key.pem')),
      cert: fs.readFileSync(path.join(__dirname, 'certs', 'cert.pem'))
    };

    server = https.createServer(httpsOptions, app);
    server.listen(PORT, () => {
      logger.info(`DuckFlix Lite Server running on port ${PORT} (HTTPS)`);
      logger.info(`Environment: ${process.env.NODE_ENV || 'development'}`);
      logger.info(`Health check: https://localhost:${PORT}/health`);
      if (typeof process.send === 'function') process.send('ready');
    });
  } catch (error) {
    logger.error('Failed to start server:', error);
    process.exit(1);
  }
}

// Graceful shutdown
let isShuttingDown = false;

async function gracefulShutdown(signal) {
  if (isShuttingDown) return;
  isShuttingDown = true;
  logger.info(`${signal} received, starting graceful shutdown...`);

  // Stop accepting new connections
  if (server) {
    server.close(() => {
      logger.info('Server closed to new connections');
    });
  }

  // Wait up to 30s for active streams to drain
  const { getActiveRdSessions } = require('./services/rd-session-tracker');
  const maxWait = 30000;
  const pollInterval = 2000;
  let waited = 0;

  while (waited < maxWait) {
    const sessions = getActiveRdSessions();
    if (sessions.length === 0) break;
    logger.info(`Waiting for ${sessions.length} active stream(s) to drain... (${waited / 1000}s/${maxWait / 1000}s)`);
    await new Promise(resolve => setTimeout(resolve, pollInterval));
    waited += pollInterval;
  }

  const remaining = getActiveRdSessions();
  if (remaining.length > 0) {
    logger.warn(`Shutdown proceeding with ${remaining.length} active stream(s) still connected`);
  }

  // Stop DFTV ffmpeg process
  try {
    const dftvService = require('./services/dftv-service');
    dftvService.stopFfmpeg();
    logger.info('DFTV ffmpeg stopped');
  } catch (error) {
    logger.error('DFTV shutdown error:', error);
  }

  // Checkpoint and close database
  try {
    const { db } = require('./db/init');
    db.pragma('wal_checkpoint(TRUNCATE)');
    logger.info('WAL checkpoint completed');
    db.close();
    logger.info('Database closed');
  } catch (error) {
    logger.error('Database shutdown error:', error);
  }

  process.exit(0);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

// Process error handlers
process.on('uncaughtException', (error) => {
  logger.error('Uncaught exception:', error);
  // Process state is unknown — flush logs then exit
  setTimeout(() => process.exit(1), 1000);
});

process.on('unhandledRejection', (reason) => {
  logger.error('Unhandled rejection:', reason);
  // Do NOT exit — prevents a single failed promise from crashing the server
});

start();
