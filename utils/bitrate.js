/**
 * Bitrate calculation and validation utilities
 */

/**
 * Estimate bitrate in Mbps from file size and runtime
 * @param {number} fileSizeBytes - File size in bytes
 * @param {number} runtimeMinutes - Content runtime in minutes
 * @returns {number} Estimated bitrate in Mbps
 */
function estimateBitrateMbps(fileSizeBytes, runtimeMinutes) {
  if (!fileSizeBytes || !runtimeMinutes || runtimeMinutes <= 0) {
    return 0;
  }
  const sizeMB = fileSizeBytes / (1024 * 1024);
  const mbPerMinute = sizeMB / runtimeMinutes;
  // Convert MB/min to Mbps: (MB/min * 8 bits/byte) / 60 seconds
  return (mbPerMinute * 8) / 60;
}

/**
 * Convert MB/min to Mbps
 * @param {number} mbPerMinute - MB per minute
 * @returns {number} Mbps
 */
function mbPerMinToMbps(mbPerMinute) {
  return (mbPerMinute * 8) / 60;
}

/**
 * Minimum MB/min thresholds by resolution (rejects garbage/fake files)
 */
const MIN_MB_PER_MIN_BY_RESOLUTION = {
  2160: 15,  // 4K must be at least 15 MB/min (~2 Mbps)
  1080: 5,   // 1080p at least 5 MB/min
  720: 2,    // 720p at least 2 MB/min
  480: 1,    // 480p at least 1 MB/min
  360: 0.5
};

/**
 * Check if a source passes sanity checks (not garbage/mislabeled)
 * @param {number} fileSizeBytes - File size in bytes
 * @param {number} resolution - Video resolution (2160, 1080, 720, etc)
 * @param {number} runtimeMinutes - Content runtime
 * @returns {boolean} True if source appears valid
 */
function isValidSource(fileSizeBytes, resolution, runtimeMinutes) {
  if (!fileSizeBytes || !runtimeMinutes || runtimeMinutes <= 0) {
    return true; // Can't validate without data, allow through
  }

  const sizeMB = fileSizeBytes / (1024 * 1024);
  const mbPerMin = sizeMB / runtimeMinutes;

  const minRequired = MIN_MB_PER_MIN_BY_RESOLUTION[resolution] || 0.5;
  return mbPerMin >= minRequired;
}

/**
 * Parse resolution from filename/title
 * @param {string} title - Torrent title or filename
 * @returns {number} Resolution (2160, 1080, 720, 480, or 0 if unknown)
 */
function parseResolution(title) {
  if (!title) return 0;
  const lower = title.toLowerCase();

  if (/2160p|4k|uhd/i.test(lower)) return 2160;
  if (/1080p|1080i|fullhd|fhd/i.test(lower)) return 1080;
  if (/720p/i.test(lower)) return 720;
  if (/480p|sd/i.test(lower)) return 480;
  if (/360p/i.test(lower)) return 360;

  return 0;
}

/**
 * Get user's max playable bitrate
 * @param {number} measuredBandwidthMbps - User's measured bandwidth
 * @param {number} safetyMargin - Safety margin divisor (default 1.3)
 * @returns {number} Max bitrate in Mbps
 */
function getMaxBitrate(measuredBandwidthMbps, safetyMargin = 1.3) {
  if (!measuredBandwidthMbps || measuredBandwidthMbps <= 0) {
    // Default to conservative 10 Mbps if no measurement
    return 10 / safetyMargin;
  }
  return measuredBandwidthMbps / safetyMargin;
}

module.exports = {
  estimateBitrateMbps,
  mbPerMinToMbps,
  isValidSource,
  parseResolution,
  getMaxBitrate,
  MIN_MB_PER_MIN_BY_RESOLUTION
};
