const {
  addMagnet: rdAddMagnet,
  getTorrentInfo: rdGetTorrentInfo,
  selectFiles: rdSelectFiles,
  unrestrictLink,
  downloadFromRD,
  rdAxios
} = require('@duckflix/rd-client');
const logger = require('../utils/logger');

const RD_API_BASE = 'https://api.real-debrid.com/rest/1.0';

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

/**
 * Delete a torrent from Real-Debrid
 * Used to clean up failed/timed-out downloads
 */
async function deleteTorrent(apiKey, torrentId) {
  try {
    await rdAxios.delete(`${RD_API_BASE}/torrents/delete/${torrentId}`, {
      headers: { 'Authorization': `Bearer ${apiKey}` }
    });
    logger.info(`🗑️  Deleted torrent ${torrentId} from RD`);
    return { success: true };
  } catch (error) {
    // Don't throw - deletion is best-effort cleanup
    logger.debug(`Failed to delete torrent ${torrentId}: ${error.message}`);
    return { success: false, error: error.message };
  }
}

/**
 * List all torrents on Real-Debrid account
 */
async function listTorrents(apiKey) {
  try {
    const response = await rdAxios.get(`${RD_API_BASE}/torrents`, {
      headers: { 'Authorization': `Bearer ${apiKey}` }
    });
    return response.data || [];
  } catch (error) {
    logger.error(`Failed to list RD torrents: ${error.message}`);
    return [];
  }
}

/**
 * Clean up stuck/downloading torrents from RD
 * Deletes torrents that have been downloading for too long with low progress
 */
async function cleanupStuckTorrents(apiKey, maxAgeMinutes = 5, maxProgress = 10) {
  try {
    const torrents = await listTorrents(apiKey);
    const now = Date.now();
    let deleted = 0;

    for (const torrent of torrents) {
      // Only clean up torrents that are downloading/waiting
      if (torrent.status === 'downloading' || torrent.status === 'waiting_files_selection') {
        const addedTime = new Date(torrent.added).getTime();
        const ageMinutes = (now - addedTime) / 1000 / 60;
        const progress = torrent.progress || 0;

        // Delete if old enough and low progress
        if (ageMinutes > maxAgeMinutes && progress < maxProgress) {
          await deleteTorrent(apiKey, torrent.id);
          deleted++;
        }
      }
    }

    if (deleted > 0) {
      logger.info(`🧹 Cleaned up ${deleted} stuck torrents from RD`);
    }
    return { deleted };
  } catch (error) {
    logger.error(`RD cleanup error: ${error.message}`);
    return { deleted: 0, error: error.message };
  }
}

module.exports = {
  addMagnet,
  getTorrentInfo,
  selectFiles,
  getUnrestrictedLink,
  completeDownloadFlow,
  deleteTorrent,
  listTorrents,
  cleanupStuckTorrents
};
