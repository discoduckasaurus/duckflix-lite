const axios = require('axios');
const logger = require('../utils/logger');

const NTV_COUNTRY_FILTER = (process.env.NTV_COUNTRY_FILTER || 'US').toLowerCase();
const NTV_MIRRORS = ['https://ntvstream.cx', 'https://ntv.cx'];
const NTV_DOMAINS = ['edge.cdn-live.ru', 'cdn-live.ru', 'cdn-live.tv', 'edge.cdn-google.ru', 'cdn-google.ru'];

/**
 * Fetch NTV channels from the API, with mirror fallback.
 * Filters by NTV_COUNTRY_FILTER (default: US).
 * @returns {Array} Array of { channel_id, channel_name, channel_code, channel_url, channel_image, server }
 */
async function fetchNTVChannels() {
  let lastError;

  for (const mirror of NTV_MIRRORS) {
    try {
      const url = `${mirror}/api/get-channels`;
      logger.info(`[NTV] Fetching channels from ${url}...`);

      const response = await axios.get(url, {
        timeout: 15000,
        headers: { 'User-Agent': 'DuckFlix/1.0' }
      });

      if (!response.data?.channels) {
        throw new Error('Invalid response: no channels array');
      }

      const allChannels = response.data.channels;
      logger.info(`[NTV] Got ${allChannels.length} total channels from ${mirror}`);

      // Filter by country code + include empty-code channels (many US channels have no code set)
      const filterCodes = NTV_COUNTRY_FILTER.split(',').map(c => c.trim().toLowerCase());
      const filtered = filterCodes[0] === 'all'
        ? allChannels
        : allChannels.filter(ch => {
            const code = (ch.channel_code || '').toLowerCase();
            return filterCodes.includes(code) || code === '';
          });

      logger.info(`[NTV] Country filter [${NTV_COUNTRY_FILTER}]+empty: ${filtered.length}/${allChannels.length} channels`);
      return filtered;
    } catch (err) {
      lastError = err;
      logger.warn(`[NTV] Mirror ${mirror} failed: ${err.message}`);
    }
  }

  throw lastError || new Error('All NTV mirrors failed');
}

/**
 * Decode base64url string (handles padding and url-safe chars).
 */
function b64Decode(str) {
  str = str.replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) str += '=';
  return Buffer.from(str, 'base64').toString('utf-8');
}

/**
 * Get a live HLS stream URL for an NTV channel by fetching the player page
 * and extracting the token-bearing m3u8 URL from the obfuscated JS.
 *
 * The player page contains an obfuscated eval() block that decodes to JS with
 * base64-encoded URL segments. We decode the eval, then decode the base64 parts
 * to reconstruct the full edge.cdn-live.ru playlist URL with ephemeral token.
 *
 * @param {object} ntvChannel - { channel_id, channel_name, channel_code, channel_url }
 * @returns {string} Full HLS URL with token
 */
async function getNTVStreamUrl(ntvChannel) {
  const playerUrl = ntvChannel.channel_url;
  if (!playerUrl) {
    throw new Error(`No player URL for NTV channel ${ntvChannel.channel_name}`);
  }

  logger.info(`[NTV] Resolving stream for ${ntvChannel.channel_name}: ${playerUrl.substring(0, 80)}`);

  const response = await axios.get(playerUrl, {
    timeout: 15000,
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
      'Referer': 'https://ntvstream.cx/'
    }
  });

  const html = typeof response.data === 'string' ? response.data : String(response.data);

  // Extract and decode the obfuscated script
  const decodedJs = deobfuscatePlayerScript(html);
  if (!decodedJs) {
    throw new Error('Failed to decode player script');
  }

  // Extract the base64-encoded URL segments and reconstruct the stream URL
  // The decoded JS defines variables with b64 fragments that concatenate to the full URL.
  // We look for the second URL construction (uses "token=" param) which is the actual stream URL.
  const streamUrl = extractStreamUrl(decodedJs);
  if (!streamUrl) {
    throw new Error('No stream URL found in decoded player script');
  }

  logger.info(`[NTV] Resolved ${ntvChannel.channel_name}: ${streamUrl.substring(0, 80)}...`);
  return streamUrl;
}

/**
 * Deobfuscate the cdn-live.tv player page script.
 * The page uses a custom packer: eval(function(h,u,n,t,e,r){...}("encoded",45,"charset",offset,base,outputBase))
 */
function deobfuscatePlayerScript(html) {
  // Extract the script content containing the obfuscation
  const scriptMatch = html.match(/<script>(var _0x[a-f0-9]+.*?)<\/script>/s);
  if (!scriptMatch) return null;

  const scriptContent = scriptMatch[1];

  // Replace eval() with a capture function
  const captureScript = scriptContent.replace('eval(', 'global.__ntvDecoded = (');

  try {
    eval(captureScript);
    return global.__ntvDecoded;
  } catch (e) {
    logger.error(`[NTV] Deobfuscation failed: ${e.message}`);
    return null;
  }
}

/**
 * Extract the stream URL from decoded player JS.
 * The JS has base64-encoded fragments assigned to const variables.
 * It constructs two URLs — we want the one with "token=" (the second construction).
 */
function extractStreamUrl(decodedJs) {
  // Strategy 1: Find all base64 const assignments and reconstruct
  // The pattern is: const VarName = 'base64string';
  const b64Consts = {};
  const constRegex = /const\s+(\w+)\s*=\s*'([A-Za-z0-9+/=_-]+)'/g;
  let match;
  while ((match = constRegex.exec(decodedJs)) !== null) {
    b64Consts[match[1]] = match[2];
  }

  // Find the URL concatenation lines (xPWptwlxuGVq is the b64 decode function name)
  // Pattern: const URL_VAR = decodeFn(a) + decodeFn(b) + ...
  const decodeFnMatch = decodedJs.match(/function\s+(\w+)\s*\(\s*str\s*\)/);
  if (!decodeFnMatch) return null;
  const decodeFnName = decodeFnMatch[1];

  // Find URL construction lines
  const urlConcatRegex = new RegExp(
    `const\\s+(\\w+)\\s*=\\s*${decodeFnName}\\((\\w+)\\)(?:\\s*\\+\\s*${decodeFnName}\\((\\w+)\\))+`,
    'g'
  );

  const urls = [];
  while ((match = urlConcatRegex.exec(decodedJs)) !== null) {
    // Extract all variable references from this line
    const fullLine = decodedJs.substring(match.index, decodedJs.indexOf(';', match.index));
    const refRegex = new RegExp(`${decodeFnName}\\((\\w+)\\)`, 'g');
    let refMatch;
    let url = '';
    while ((refMatch = refRegex.exec(fullLine)) !== null) {
      const varName = refMatch[1];
      const b64Value = b64Consts[varName];
      if (b64Value) {
        url += b64Decode(b64Value);
      }
    }
    if (url) urls.push(url);
  }

  // Prefer the URL with "token=" parameter (the actual stream URL)
  // Over the one with a randomized param name
  const tokenUrl = urls.find(u => u.includes('token='));
  return tokenUrl || urls.find(u => u.includes('edge.cdn-live')) || urls[0] || null;
}

module.exports = {
  fetchNTVChannels,
  getNTVStreamUrl,
  NTV_DOMAINS
};
