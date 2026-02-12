const path = require('path');
const fs = require('fs');
const cron = require('node-cron');
const axios = require('axios');
const { parseEPGData } = require('@duckflix/epg-parser');
const { parseM3U } = require('@duckflix/m3u-parser');
const { db } = require('../db/init');
const logger = require('../utils/logger');

let epgSyncInProgress = false;
let m3uSyncInProgress = false;

const M3U_FILE = path.join(__dirname, '..', 'db', 'channels.m3u');

// NTV configuration (cdn-live.tv streams via ntvstream.cx)
const NTV_ENABLED = process.env.NTV_ENABLED === 'true';
const { fetchNTVChannels } = require('./ntv-service');

/**
 * Fetch and cache M3U playlist from local file
 */
async function syncM3U() {
  try {
    logger.info('Parsing M3U playlist from local file...');
    const channels = await parseM3U(M3U_FILE);

    logger.info(`Parsed ${channels.length} channels from M3U`);

    // Store channels in database
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    const channelData = channels.map(ch => ({
      id: ch.id,
      name: ch.name,
      displayName: ch.displayName,
      url: ch.url,
      group: ch.group
    }));

    stmt.run('m3u_channels', JSON.stringify(channelData));

    logger.info('M3U sync complete');
  } catch (error) {
    logger.error('M3U sync failed:', error);
  }
}

/**
 * Normalize a channel name for fuzzy matching.
 * Strips parenthetical content, common suffixes/qualifiers, lowercases, removes non-alphanumeric.
 */
function normalizeName(name) {
  return name
    .toLowerCase()
    .replace(/\([^)]*\)/g, '')  // Strip parenthetical content: (BBCA), (OWN), (CBSSN), (SNY)
    .replace(/\b(the|us|usa|eastern|western|feed|east|west|hd|sd|fhd|uhd|new york|los angeles|ny|ca|channel|entertainment|television|live|tv|movies|movie|mystery|mysteries|mysterie|true|crime)\b/g, '')
    .replace(/[^a-z0-9]/g, '')
    .trim();
}

/**
 * Raw name aliases for NTV channels (lowercased) — checked before normalization.
 */
const NTV_RAW_ALIASES = {
  'abc': 'abc-kabc-los-angeles-ca',
  'abc new york': 'abc-wabc-new-york-ny',
  'cbs': 'cbs-kcbs-los-angeles-ca',
  'cbs new york': 'cbs-wcbs-new-york-ny',
  'nbc': 'nbc-knbc-los-angeles-ca',
  'nbc new york': 'nbc-wnbc-new-york-ny',
  'fox': 'fox-kttv-los-angeles-ca',
  'fox new york': 'fox-wnyw-new-york-ny',
  'the hallmark channel': 'hallmark-eastern-feed',
  'hallmark movies & mysteries': 'hallmark-mystery-eastern-hd',
  'sportsnet new york (sny)': 'sny-sportsnet-new-york-comcast',
};

/**
 * Normalized name aliases for NTV channels with structurally different names.
 */
const NTV_ALIASES = {
  'oprahwinfreynetwork': 'oprah-winfrey-network-usa-eastern',
  'ahc': 'american-heroes-channel',
  'nick': 'nickelodeon-usa-east-feed',
  'cwpix11': 'wpix-new-york-superstation',
  'investigationdiscovery': 'investigation-discovery-usa-eastern',
};

/**
 * Direct NTV channel_id -> TVPass channel_id mappings.
 * Highest priority — checked before name matching.
 * Covers numeric-ID channels that are hard to match by name.
 */
const NTV_ID_ALIASES = {
  // Network affiliates (NY)
  '51': 'abc-wabc-new-york-ny',
  '52': 'cbs-wcbs-new-york-ny',
  '53': 'nbc-wnbc-new-york-ny',
  '54': 'fox-wnyw-new-york-ny',
  '30': 'cw-wdcw-district-of-columbia',
  '766': 'abc-wabc-new-york-ny',
  // Entertainment channels (empty country code)
  '298': 'fxx-usa-eastern',
  '301': 'freeform-east-feed',
  '302': 'ae-us-eastern-feed',
  '303': 'amc-eastern-feed',
  '307': 'bravo-usa-eastern-feed',
  '310': 'comedy-central-us-eastern-feed',
  '315': 'e-entertainment-usa-eastern-feed',
  '317': 'fx-networks-east-coast',
  '332': 'oxygen-eastern-feed',
  '373': 'syfy-eastern-feed',
  // Collision fixes (name normalization causes wrong matches)
  '381': 'fx-movie-channel',           // "FX Movie Channel" → "fx" collides with FX
  '389': 'lifetime-movies-east',       // "Lifetime Movies Network" → collides with Lifetime
  '407': 'sportsnet-west',             // "Sportsnet West" → "sportsnet" collides
  '408': 'sportsnet-east',             // "Sportsnet East" → "sportsnet" collides
  '759': 'sny-sportsnet-new-york-comcast', // "SportsNet New York (SNY)"
  '765': 'msg-madison-square-gardens', // "MSG USA" → "msg" collides with MSG+
};

/**
 * Fetch and sync NTV channels into the channel list.
 * Matched NTV channels store their channel object (not URL — URLs are ephemeral) in ntv_stream_map.
 * Unmatched NTV channels are added as ntv-* entries.
 */
async function syncNTVChannels() {
  if (!NTV_ENABLED) {
    logger.debug('NTV integration disabled, skipping');
    return;
  }

  try {
    const filteredChannels = await fetchNTVChannels();

    if (filteredChannels.length === 0) {
      logger.warn('NTV returned 0 channels after filtering, skipping merge');
      return;
    }

    // Get existing M3U channels from cache (TVPass, no old ntv-/cab- entries)
    const existingRow = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    let existingChannels = [];
    if (existingRow) {
      existingChannels = JSON.parse(existingRow.epg_data);
    }

    // Remove old NTV-only and legacy cab- channels (ntv-/cab- prefix)
    const baseChannels = existingChannels.filter(ch => !ch.id.startsWith('ntv-') && !ch.id.startsWith('cab-'));

    // Load channel-config display names for better matching
    let channelConfigNames = {};
    try {
      channelConfigNames = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'channel-config.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    // Build normalized name -> channel map for deduplication
    const channelNameMap = new Map();
    for (const ch of baseChannels) {
      const normalized = normalizeName(ch.displayName || ch.name);
      if (normalized) channelNameMap.set(normalized, ch);
      const configName = channelConfigNames[ch.id]?.displayName;
      if (configName) {
        const configNormalized = normalizeName(configName);
        if (configNormalized && configNormalized !== normalized) {
          channelNameMap.set(configNormalized, ch);
        }
      }
    }

    // Build ID lookup for manual aliases
    const channelById = new Map();
    for (const ch of baseChannels) {
      channelById.set(ch.id, ch);
    }

    // Deduplicate: match NTV channels to existing channels by normalized name
    const ntvStreamMap = {}; // tvpassId/cabId -> { channel_id, channel_name, channel_code, channel_url }
    const unmatchedChannels = [];

    for (const ch of filteredChannels) {
      const ntvId = String(ch.channel_id);
      const rawLower = ch.channel_name.toLowerCase().trim();
      const normalized = normalizeName(ch.channel_name);

      // Check ID aliases first (most specific), then raw name, then normalized, then auto-match
      const aliasId = NTV_ID_ALIASES[ntvId] || NTV_RAW_ALIASES[rawLower] || NTV_ALIASES[normalized];
      const existingMatch = aliasId ? channelById.get(aliasId) : channelNameMap.get(normalized);

      if (existingMatch) {
        // Only add to NTV stream map if channel has a player URL we can resolve.
        // dlhd channels (numeric IDs, empty code) have no player URL and can't be resolved.
        // Prefer cdnlive sources over scorpion (our deobfuscator handles cdn-live.tv).
        if (ch.channel_url) {
          const existing = ntvStreamMap[existingMatch.id];
          const isCdnlive = ntvId.startsWith('cdnlive-');
          if (!existing || isCdnlive) {
            ntvStreamMap[existingMatch.id] = {
              channel_id: ch.channel_id,
              channel_name: ch.channel_name,
              channel_code: ch.channel_code,
              channel_url: ch.channel_url
            };
          }
        }
      } else if (ch.channel_code) {
        // Only add as ntv-* entry if from explicit country filter (not empty-code)
        // Empty-code unmatched channels are silently dropped to avoid foreign channel clutter
        unmatchedChannels.push({
          id: `ntv-${ch.channel_id}`,
          name: ch.channel_name,
          displayName: ch.channel_name,
          url: '', // NTV URLs are ephemeral, resolved on-demand
          group: 'NTV',
          source: 'ntv'
        });
      }
    }

    // Store NTV stream mapping in epg_cache
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('ntv_stream_map', JSON.stringify(ntvStreamMap));

    // Merge: base channels + unmatched NTV-only channels
    const merged = [...baseChannels, ...unmatchedChannels];

    // Store merged list
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('m3u_channels', JSON.stringify(merged));

    const matchedCount = Object.keys(ntvStreamMap).length;
    logger.info(`NTV sync: ${matchedCount} matched to existing, ${unmatchedChannels.length} NTV-only, ${baseChannels.length} existing = ${merged.length} total channels`);
  } catch (error) {
    if (error.code === 'ECONNREFUSED' || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      logger.warn(`NTV API offline (${error.code}), keeping existing channels`);
    } else {
      logger.error('NTV sync failed:', error.message);
    }
  }
}

/**
 * Auto-disable channels that have no sources (no NTV, no backup-streams, no valid M3U URL).
 * Only disables — never re-enables (respects admin overrides).
 */
function autoDisableSourcelessChannels() {
  try {
    let ntvStreamMap = {};
    let backupStreams = {};

    try {
      const ntvRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'ntv_stream_map'`).get();
      if (ntvRow) ntvStreamMap = JSON.parse(ntvRow.epg_data);
    } catch (e) { /* ignore */ }

    try {
      backupStreams = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'backup-streams.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    const m3uRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'`).get();
    if (!m3uRow) return;

    const channels = JSON.parse(m3uRow.epg_data);
    const disabledIds = [];

    const upsert = db.prepare(`
      INSERT INTO channel_metadata (channel_id, is_enabled, updated_at)
      VALUES (?, 0, datetime('now'))
      ON CONFLICT(channel_id) DO UPDATE SET
        is_enabled = 0,
        updated_at = datetime('now')
      WHERE channel_metadata.is_enabled = 1
    `);

    const transaction = db.transaction(() => {
      for (const ch of channels) {
        if (ch.id.startsWith('ntv-')) continue;

        const hasNTV = !!ntvStreamMap[ch.id];
        const hasBackup = !!backupStreams[ch.id];
        const hasM3UUrl = !!(ch.url && ch.url.startsWith('http'));

        if (!hasNTV && !hasBackup && !hasM3UUrl) {
          const meta = db.prepare(`SELECT is_enabled FROM channel_metadata WHERE channel_id = ?`).get(ch.id);
          if (!meta || meta.is_enabled === 1) {
            upsert.run(ch.id);
            disabledIds.push(ch.id);
          }
        }
      }
    });

    transaction();

    if (disabledIds.length > 0) {
      logger.info(`Auto-disabled ${disabledIds.length} channels with no sources: ${disabledIds.slice(0, 10).join(', ')}${disabledIds.length > 10 ? '...' : ''}`);
    }
  } catch (error) {
    logger.error('Auto-disable sourceless channels failed:', error.message);
  }
}

/**
 * Fetch and cache EPG data from all sources
 */
async function syncEPG() {
  try {
    logger.info('Fetching EPG data from all sources...');

    const { channels, programs } = await parseEPGData({
      includeTvPass: true,
      includePluto: true,
      includeEpgshare01: true
    });

    logger.info(`Fetched EPG for ${Object.keys(programs).length} channels`);

    // Store EPG data per channel
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    for (const [channelId, channelPrograms] of Object.entries(programs)) {
      stmt.run(channelId, JSON.stringify(channelPrograms));
    }

    logger.info('EPG sync complete');
  } catch (error) {
    logger.error('EPG sync failed:', error);
  }
}

/**
 * Sync EPG, M3U, and NTV data
 */
async function syncAll() {
  logger.info('Starting EPG/M3U sync...');
  await Promise.all([syncEPG(), syncM3U()]);
  await syncNTVChannels();
  autoDisableSourcelessChannels();
  logger.info('EPG/M3U sync finished');
}

/**
 * Start cron jobs for periodic syncing
 */
function startSyncJobs() {
  // Sync EPG every 30 minutes
  cron.schedule('*/30 * * * *', () => {
    if (epgSyncInProgress) {
      logger.warn('Cron: EPG sync skipped - previous sync still running');
      return;
    }
    logger.info('Cron: EPG sync triggered');
    epgSyncInProgress = true;
    syncEPG()
      .catch(err => logger.error('Cron EPG sync error:', err))
      .finally(() => { epgSyncInProgress = false; });
  });

  // Sync M3U every hour (then merge NTV on top)
  cron.schedule('0 * * * *', () => {
    if (m3uSyncInProgress) {
      logger.warn('Cron: M3U sync skipped - previous sync still running');
      return;
    }
    logger.info('Cron: M3U sync triggered');
    m3uSyncInProgress = true;
    syncM3U()
      .then(() => syncNTVChannels())
      .then(() => autoDisableSourcelessChannels())
      .catch(err => logger.error('Cron M3U sync error:', err))
      .finally(() => { m3uSyncInProgress = false; });
  });

  logger.info('EPG/M3U cron jobs started (EPG: 30min, M3U+NTV: 1hr)');

  // Run initial sync
  syncAll().catch(err => logger.error('Initial sync error:', err));
}

module.exports = {
  syncEPG,
  syncM3U,
  syncNTVChannels,
  syncAll,
  startSyncJobs
};
