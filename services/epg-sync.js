const path = require('path');
const fs = require('fs');
const cron = require('node-cron');
const axios = require('axios');
const { parseEPGData } = require('@duckflix/epg-parser');
const { parseM3U } = require('@duckflix/m3u-parser');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const M3U_FILE = path.join(__dirname, '..', 'db', 'channels.m3u');

// DaddyLive configuration (via StepDaddyLiveHD)
const DADDYLIVE_ENABLED = process.env.DADDYLIVE_ENABLED === 'true';
const DADDYLIVE_URL = process.env.DADDYLIVE_URL || 'http://localhost:9191';
const DADDYLIVE_COUNTRY_FILTER = (process.env.DADDYLIVE_COUNTRY_FILTER || 'US').toUpperCase();

// Load StepDaddyLiveHD meta.json for country tag filtering
let daddyliveMeta = {};
try {
  const metaPath = path.join(__dirname, '..', '..', 'StepDaddyLiveHD', 'StepDaddyLiveHD', 'meta.json');
  daddyliveMeta = JSON.parse(fs.readFileSync(metaPath, 'utf-8'));
  logger.info(`Loaded DaddyLive meta.json: ${Object.keys(daddyliveMeta).length} channels`);
} catch (err) {
  logger.warn('Failed to load StepDaddyLiveHD meta.json:', err.message);
}

/**
 * Convert a 2-letter country code to its flag emoji for matching against meta.json tags.
 * e.g., "US" -> "🇺🇸", "UK" -> "🇬🇧"
 */
function countryCodeToFlag(code) {
  return String.fromCodePoint(...[...code.toUpperCase()].map(c => 0x1F1E6 + c.charCodeAt(0) - 65));
}

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
 * Parse StepDaddyLiveHD M3U playlist into channel objects.
 * Format: #EXTINF:-1 tvg-logo="...",Channel Name\nhttp://url/stream/ID.m3u8
 * Extracts numeric channel ID from the stream URL.
 */
function parseDaddyLiveM3U(m3uContent) {
  const channels = [];
  const lines = m3uContent.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;

    // Extract logo and name from EXTINF line
    const logoMatch = line.match(/tvg-logo="([^"]*)"/);
    const nameMatch = line.match(/,(.+)$/);
    const logo = logoMatch ? logoMatch[1] : null;
    const name = nameMatch ? nameMatch[1].trim() : 'Unknown';

    // Next non-empty, non-comment line is the URL
    let url = '';
    for (let j = i + 1; j < lines.length; j++) {
      const nextLine = lines[j].trim();
      if (nextLine && !nextLine.startsWith('#')) {
        url = nextLine;
        break;
      }
    }

    // Extract channel ID from URL: /stream/123.m3u8 -> 123
    const idMatch = url.match(/\/stream\/(\d+)\.m3u8/);
    const id = idMatch ? idMatch[1] : null;

    if (id && url) {
      channels.push({ id, name, url, logo });
    }
  }

  return channels;
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
 * Raw name aliases (lowercased) for cases where normalization loses distinguishing info.
 * Checked BEFORE normalization. Handles collisions (e.g., two TVPass channels both
 * normalizing to "hallmark") and NY/LA affiliate disambiguation.
 */
const DADDYLIVE_RAW_ALIASES = {
  // Network affiliates: "XX USA" → LA, "XXNY USA" / "XX NY USA" → NY
  'abc usa':                       'abc-kabc-los-angeles-ca',
  'abc ny usa':                    'abc-wabc-new-york-ny',
  'cbs usa':                       'cbs-kcbs-los-angeles-ca',
  'nbc usa':                       'nbc-knbc-los-angeles-ca',
  'fox usa':                       'fox-kttv-los-angeles-ca',
  // Hallmark disambiguation (all three normalize to "hallmark")
  'the hallmark channel':          'hallmark-eastern-feed',
  'hallmark movies & mysterie':    'hallmark-mystery-eastern-hd',
};

/**
 * Normalized name aliases for DaddyLive channels with structurally different names.
 * Keys are normalized DaddyLive names, values are TVPass channel IDs.
 */
const DADDYLIVE_ALIASES = {
  // Network NY affiliates (NY glued to name, \bny\b won't match)
  'cbsny':              'cbs-wcbs-new-york-ny',
  'nbcny':              'nbc-wnbc-new-york-ny',
  'foxny':              'fox-wnyw-new-york-ny',
  'abcny':              'abc-wabc-new-york-ny',
  // Abbreviation vs full name
  'oprahwinfreynetwork': 'oprah-winfrey-network-usa-eastern',
  // SportsNet New York (SNY) — parens stripped, "new york" stripped → "sportsnet"
  'sportsnet':          'sny-sportsnet-new-york-comcast',
  // CW PIX 11 → PIX11
  'cwpix11':            'wpix-new-york-superstation',
  // Abbreviation → full name
  'ahc':                'american-heroes-channel',
  'nick':               'nickelodeon-usa-east-feed',
};

/**
 * Fetch and merge DaddyLive channels (via StepDaddyLiveHD) into the cached channel list.
 * Matched DaddyLive channels are NOT added as separate entries — instead their stream URL
 * is stored in a `daddylive_stream_map` mapping (tvpassId -> daddyliveUrl) so it can be
 * used as the primary stream source. Only unmatched DaddyLive channels are added as `cab-*`.
 * Country filtering uses meta.json flag emoji tags.
 * Gracefully handles StepDaddyLiveHD being offline — keeps existing channels untouched.
 */
async function syncDaddyLiveM3U() {
  if (!DADDYLIVE_ENABLED) {
    logger.debug('DaddyLive integration disabled, skipping');
    return;
  }

  try {
    const m3uUrl = `${DADDYLIVE_URL}/playlist.m3u8`;
    logger.info(`Fetching DaddyLive M3U from ${m3uUrl}...`);

    const response = await axios.get(m3uUrl, {
      timeout: 15000,
      headers: { 'User-Agent': 'DuckFlix/1.0' }
    });

    const allChannels = parseDaddyLiveM3U(response.data);
    logger.info(`Parsed ${allChannels.length} channels from DaddyLive`);

    // Filter by country using meta.json flag emoji tags
    let filteredChannels = allChannels;
    if (DADDYLIVE_COUNTRY_FILTER && DADDYLIVE_COUNTRY_FILTER !== 'ALL' && Object.keys(daddyliveMeta).length > 0) {
      const filterCodes = DADDYLIVE_COUNTRY_FILTER.split(',').map(f => f.trim());
      const filterFlags = filterCodes.map(code => countryCodeToFlag(code));

      filteredChannels = allChannels.filter(ch => {
        const meta = daddyliveMeta[ch.name];
        if (!meta || !meta.tags) return false;
        return meta.tags.some(tag => filterFlags.includes(tag));
      });
      logger.info(`Country filter [${DADDYLIVE_COUNTRY_FILTER}]: ${filteredChannels.length}/${allChannels.length} channels passed`);
    }

    if (filteredChannels.length === 0) {
      logger.warn('DaddyLive returned 0 channels after filtering, skipping merge');
      return;
    }

    // Get existing M3U channels from cache (TVPass + special, no old cab- entries)
    const existingRow = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    let existingChannels = [];
    if (existingRow) {
      existingChannels = JSON.parse(existingRow.epg_data);
    }

    // Remove old DaddyLive channels (cab- prefix)
    const tvpassChannels = existingChannels.filter(ch => !ch.id.startsWith('cab-'));

    // Load channel-config display names for better matching
    let channelConfigNames = {};
    try {
      channelConfigNames = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'channel-config.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    // Build normalized name -> TVPass channel map for deduplication
    // Index BOTH the M3U name and the channel-config display name (e.g. "IFC" vs "Independent Film Channel US")
    const tvpassNameMap = new Map();
    for (const ch of tvpassChannels) {
      const normalized = normalizeName(ch.displayName || ch.name);
      if (normalized) {
        tvpassNameMap.set(normalized, ch);
      }
      // Also index by channel-config display name if different
      const configName = channelConfigNames[ch.id]?.displayName;
      if (configName) {
        const configNormalized = normalizeName(configName);
        if (configNormalized && configNormalized !== normalized) {
          tvpassNameMap.set(configNormalized, ch);
        }
      }
    }

    // Build ID lookup for manual aliases
    const tvpassById = new Map();
    for (const ch of tvpassChannels) {
      tvpassById.set(ch.id, ch);
    }

    // Deduplicate: match DaddyLive channels to TVPass by normalized name
    const daddyliveStreamMap = {}; // tvpassId -> daddylive stream URL
    const unmatchedChannels = []; // DaddyLive-only channels (new)

    for (const ch of filteredChannels) {
      const rawLower = ch.name.toLowerCase().trim();
      const normalized = normalizeName(ch.name);
      // Check raw aliases first (most specific), then normalized aliases, then auto-match
      const aliasId = DADDYLIVE_RAW_ALIASES[rawLower] || DADDYLIVE_ALIASES[normalized];
      const tvpassMatch = aliasId ? tvpassById.get(aliasId) : tvpassNameMap.get(normalized);

      if (tvpassMatch) {
        // Matched — store DaddyLive URL mapped to the TVPass channel ID
        daddyliveStreamMap[tvpassMatch.id] = ch.url;
      } else {
        // Unmatched — add as new cab-* channel
        unmatchedChannels.push({
          id: `cab-${ch.id}`,
          name: ch.name,
          displayName: ch.name,
          url: ch.url,
          group: 'DaddyLive',
          source: 'cabernet'
        });
      }
    }

    // Store the DaddyLive stream mapping in epg_cache
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('daddylive_stream_map', JSON.stringify(daddyliveStreamMap));

    // Merge: TVPass channels + unmatched DaddyLive-only channels
    const merged = [...tvpassChannels, ...unmatchedChannels];

    // Store merged list
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('m3u_channels', JSON.stringify(merged));

    const matchedCount = Object.keys(daddyliveStreamMap).length;
    logger.info(`DaddyLive sync: ${matchedCount} matched to TVPass, ${unmatchedChannels.length} new DaddyLive-only, ${tvpassChannels.length} TVPass = ${merged.length} total channels`);
  } catch (error) {
    if (error.code === 'ECONNREFUSED' || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      logger.warn(`StepDaddyLiveHD offline (${error.code}), keeping existing channels`);
    } else {
      logger.error('DaddyLive M3U sync failed:', error.message);
    }
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
 * Sync EPG, M3U, and DaddyLive data
 */
async function syncAll() {
  logger.info('Starting EPG/M3U sync...');
  // Sync tvpass M3U first, then DaddyLive merges on top
  await Promise.all([syncEPG(), syncM3U()]);
  await syncDaddyLiveM3U();
  logger.info('EPG/M3U sync finished');
}

/**
 * Start cron jobs for periodic syncing
 */
function startSyncJobs() {
  // Sync EPG every 30 minutes
  cron.schedule('*/30 * * * *', () => {
    logger.info('Cron: EPG sync triggered');
    syncEPG().catch(err => logger.error('Cron EPG sync error:', err));
  });

  // Sync M3U every hour (then merge DaddyLive on top)
  cron.schedule('0 * * * *', () => {
    logger.info('Cron: M3U sync triggered');
    syncM3U()
      .then(() => syncDaddyLiveM3U())
      .catch(err => logger.error('Cron M3U sync error:', err));
  });

  logger.info('EPG/M3U cron jobs started (EPG: 30min, M3U+DaddyLive: 1hr)');

  // Run initial sync
  syncAll().catch(err => logger.error('Initial sync error:', err));
}

module.exports = {
  syncEPG,
  syncM3U,
  syncDaddyLiveM3U,
  syncAll,
  startSyncJobs
};
