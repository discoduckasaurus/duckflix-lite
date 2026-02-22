const jwt = require('jsonwebtoken');
const logger = require('../utils/logger');

// CRITICAL: JWT_SECRET must be set in environment variables
const JWT_SECRET = process.env.JWT_SECRET;

if (!JWT_SECRET) {
  logger.error('CRITICAL: JWT_SECRET environment variable is not set!');
  logger.error('Generate a secure secret: openssl rand -base64 32');
  process.exit(1);
}

/**
 * Middleware to verify JWT token
 */
function authenticateToken(req, res, next) {
  const authHeader = req.headers['authorization'];
  // Support query-param token for Chromecast/AirPlay (devices can't set headers)
  const token = (authHeader && authHeader.split(' ')[1]) || req.query.token;

  if (!token) {
    logger.warn('No token provided. Auth header:', authHeader);
    return res.status(401).json({ error: 'Authentication required' });
  }

  try {
    // Specify algorithm to prevent "none" algorithm attack
    const decoded = jwt.verify(token, JWT_SECRET, { algorithms: ['HS256'] });
    req.user = decoded; // { sub: userId, username, isAdmin, iat, exp }
    next();
  } catch (error) {
    logger.warn(`Invalid token. Error: ${error.message} | Token length: ${token?.length} | First 20 chars: ${token?.substring(0, 20)}`);
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}

/**
 * Middleware to require admin privileges
 */
function requireAdmin(req, res, next) {
  if (!req.user || !req.user.isAdmin) {
    return res.status(403).json({ error: 'Admin privileges required' });
  }
  next();
}

/**
 * Generate JWT token
 */
function generateToken(user) {
  const payload = {
    sub: user.id,
    username: user.username,
    isAdmin: !!user.is_admin
  };

  const expiresIn = process.env.JWT_EXPIRES_IN || '365d';

  return jwt.sign(payload, JWT_SECRET, { expiresIn, algorithm: 'HS256' });
}

module.exports = {
  authenticateToken,
  requireAdmin,
  generateToken
};
