const {
  addMagnet: rdAddMagnet,
  getTorrentInfo: rdGetTorrentInfo,
  selectFiles: rdSelectFiles,
  unrestrictLink,
  downloadFromRD
} = require('@duckflix/rd-client');
const logger = require('../utils/logger');

/**
 * Add magnet to Real-Debrid
 */
async function addMagnet(apiKey, magnetUrl) {
  try {
    logger.info('Adding magnet to RD');
    const torrentId = await rdAddMagnet(magnetUrl, apiKey);
    return { torrentId, success: true };
  } catch (error) {
    logger.error('RD add magnet error:', error);
    throw error;
  }
}

/**
 * Get torrent info from Real-Debrid
 */
async function getTorrentInfo(apiKey, torrentId) {
  try {
    const info = await rdGetTorrentInfo(torrentId, apiKey);
    return info;
  } catch (error) {
    logger.error('RD get torrent info error:', error);
    throw error;
  }
}

/**
 * Select files in torrent
 */
async function selectFiles(apiKey, torrentId, fileIds) {
  try {
    await rdSelectFiles(torrentId, fileIds, apiKey);
    return { success: true };
  } catch (error) {
    logger.error('RD select files error:', error);
    throw error;
  }
}

/**
 * Get unrestricted download link
 */
async function getUnrestrictedLink(apiKey, link) {
  try {
    const result = await unrestrictLink(link, apiKey);
    return result;
  } catch (error) {
    logger.error('RD unrestrict link error:', error);
    throw error;
  }
}

/**
 * Complete download flow (add -> select -> wait -> unrestrict)
 */
async function completeDownloadFlow(apiKey, magnetUrl, season = null, episode = null) {
  try {
    logger.info('Starting RD download flow');
    logger.info(`Magnet URL type: ${typeof magnetUrl}, starts with magnet: ${magnetUrl?.startsWith?.('magnet:')}`);
    logger.info(`Magnet URL preview: ${magnetUrl?.substring?.(0, 100)}...`);

    const result = await downloadFromRD(magnetUrl, apiKey, season, episode);

    logger.info('RD download flow complete:', {
      download: result.download?.substring?.(0, 100),
      filename: result.filename
    });

    return {
      streamUrl: result.download,
      fileName: result.filename
    };
  } catch (error) {
    logger.error('RD download flow error:', error.message);
    logger.error('Error code:', error.code);
    if (error.stack) {
      logger.error('Stack trace:', error.stack.split('\n').slice(0, 3).join('\n'));
    }
    throw error;
  }
}

module.exports = {
  addMagnet,
  getTorrentInfo,
  selectFiles,
  getUnrestrictedLink,
  completeDownloadFlow
};
