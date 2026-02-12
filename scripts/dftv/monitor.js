#!/usr/bin/env node

/**
 * DFTV Download Monitor — Visual progress dashboard
 *
 * Usage:
 *   node scripts/dftv/monitor.js            # Live dashboard (refreshes every 3s)
 *   node scripts/dftv/monitor.js --once     # Print once and exit
 */

require('dotenv').config();

const fs = require('fs');
const path = require('path');

const DFTV_ROOT = process.env.DFTV_ROOT || '/mnt/nas/DFTV';
const STATE_FILE = path.join(DFTV_ROOT, '.dftv-state.json');
const LOG_FILE = '/tmp/dftv-setup.log';

// ANSI color codes
const c = {
  reset: '\x1b[0m',
  bold: '\x1b[1m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  white: '\x1b[37m',
  bgGreen: '\x1b[42m',
  bgYellow: '\x1b[43m',
  bgRed: '\x1b[41m',
  bgBlue: '\x1b[44m',
  bgMagenta: '\x1b[45m',
  bgWhite: '\x1b[47m',
  bgGray: '\x1b[100m',
};

const SHOW_COLORS = {
  'American Dad': c.red,
  'The Office': c.blue,
  'Parks and Recreation': c.green,
  'Brooklyn Nine-Nine': c.magenta,
};

const SHOW_BG_COLORS = {
  'American Dad': c.bgRed,
  'The Office': c.bgBlue,
  'Parks and Recreation': c.bgGreen,
  'Brooklyn Nine-Nine': c.bgMagenta,
};

const STATUS_ICONS = {
  downloaded: `${c.green}\u2588${c.reset}`,   // Full block (green)
  downloading: `${c.yellow}\u2593${c.reset}`,  // Medium shade (yellow)
  searching: `${c.cyan}\u2592${c.reset}`,      // Light shade (cyan)
  pending: `${c.dim}\u2591${c.reset}`,          // Light shade (dim)
  error: `${c.red}\u2573${c.reset}`,            // Cross (red)
  not_found: `${c.red}\u00D7${c.reset}`,        // Multiplication sign (red)
};

function loadState() {
  try {
    if (!fs.existsSync(STATE_FILE)) return null;
    return JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
  } catch {
    return null;
  }
}

function getLogTail(lines = 5) {
  try {
    if (!fs.existsSync(LOG_FILE)) return [];
    const content = fs.readFileSync(LOG_FILE, 'utf-8');
    const allLines = content.trim().split('\n');
    return allLines.slice(-lines).map(l => {
      // Strip winston timestamp/level prefix, keep just the message
      return l.replace(/^\d{4}-\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\s\[.*?\]:\s*/, '');
    });
  } catch {
    return [];
  }
}

function isSetupRunning() {
  try {
    const { execSync } = require('child_process');
    const out = execSync('pgrep -f "node scripts/dftv/setup.js" 2>/dev/null', { encoding: 'utf-8' });
    return out.trim().length > 0;
  } catch {
    return false;
  }
}

function progressBar(done, total, width = 30) {
  if (total === 0) return `${c.dim}${'░'.repeat(width)}${c.reset}`;
  const pct = Math.min(done / total, 1);
  const filled = Math.round(pct * width);
  const empty = width - filled;

  let bar = '';
  if (pct >= 1) {
    bar = `${c.green}${'█'.repeat(filled)}${c.reset}`;
  } else if (pct > 0.5) {
    bar = `${c.green}${'█'.repeat(filled)}${c.dim}${'░'.repeat(empty)}${c.reset}`;
  } else if (pct > 0) {
    bar = `${c.yellow}${'█'.repeat(filled)}${c.dim}${'░'.repeat(empty)}${c.reset}`;
  } else {
    bar = `${c.dim}${'░'.repeat(width)}${c.reset}`;
  }
  return bar;
}

function render() {
  const state = loadState();
  const running = isSetupRunning();
  const now = new Date();

  // Clear screen
  process.stdout.write('\x1b[2J\x1b[H');

  // Header
  console.log(`${c.bold}${c.cyan}╔════════════════════════════════════════════════════════════════╗${c.reset}`);
  console.log(`${c.bold}${c.cyan}║${c.reset}  ${c.bold}DFTV Download Monitor${c.reset}                      ${running ? `${c.green}● RUNNING${c.reset}` : `${c.dim}○ IDLE${c.reset}`}    ${c.bold}${c.cyan}║${c.reset}`);
  console.log(`${c.bold}${c.cyan}╚════════════════════════════════════════════════════════════════╝${c.reset}`);
  console.log(`  ${c.dim}${now.toLocaleTimeString()}${c.reset}`);
  console.log();

  if (!state) {
    console.log(`  ${c.yellow}Waiting for state file...${c.reset}`);
    console.log(`  ${c.dim}${STATE_FILE}${c.reset}`);
    console.log();
    renderLogTail();
    return;
  }

  // Legend
  console.log(`  ${STATUS_ICONS.downloaded} Downloaded  ${STATUS_ICONS.downloading} Downloading  ${STATUS_ICONS.searching} Searching  ${STATUS_ICONS.pending} Pending  ${STATUS_ICONS.error} Error  ${STATUS_ICONS.not_found} Not Found`);
  console.log();

  // Overall totals
  let grandTotal = 0;
  let grandDownloaded = 0;
  let grandErrors = 0;
  let grandNotFound = 0;
  let grandActive = 0;

  const showOrder = ['American Dad', 'The Office', 'Parks and Recreation', 'Brooklyn Nine-Nine'];

  for (const showName of showOrder) {
    // Find this show in state
    let showState = null;
    let tmdbId = null;
    for (const [id, data] of Object.entries(state.shows || {})) {
      if (data.title === showName) {
        showState = data;
        tmdbId = id;
        break;
      }
    }

    const color = SHOW_COLORS[showName] || c.white;
    const bgColor = SHOW_BG_COLORS[showName] || c.bgGray;

    if (!showState) {
      console.log(`  ${bgColor}${c.bold}${c.white} ${showName.padEnd(25)} ${c.reset}  ${c.dim}Not started${c.reset}`);
      console.log();
      continue;
    }

    const episodes = Object.entries(showState.episodes || {});
    const total = showState.totalEpisodes || episodes.length;
    const downloaded = episodes.filter(([, e]) => e.status === 'downloaded').length;
    const downloading = episodes.filter(([, e]) => e.status === 'downloading').length;
    const searching = episodes.filter(([, e]) => e.status === 'searching').length;
    const errors = episodes.filter(([, e]) => e.status === 'error').length;
    const notFound = episodes.filter(([, e]) => e.status === 'not_found').length;
    const pct = total > 0 ? ((downloaded / total) * 100).toFixed(1) : '0.0';

    grandTotal += total;
    grandDownloaded += downloaded;
    grandErrors += errors;
    grandNotFound += notFound;
    grandActive += downloading + searching;

    // Show header
    console.log(`  ${bgColor}${c.bold}${c.white} ${showName.padEnd(25)} ${c.reset}  ${downloaded}/${total} ${c.dim}(${pct}%)${c.reset}`);
    console.log(`  ${progressBar(downloaded, total, 40)}  ${downloading > 0 ? `${c.yellow}↓${downloading}${c.reset} ` : ''}${searching > 0 ? `${c.cyan}?${searching}${c.reset} ` : ''}${errors > 0 ? `${c.red}✗${errors}${c.reset} ` : ''}${notFound > 0 ? `${c.red}×${notFound}${c.reset}` : ''}`);

    // Season breakdown with episode grid
    const seasonMap = {};
    for (const [key, ep] of episodes) {
      const match = key.match(/S(\d{2})E(\d{2})/);
      if (!match) continue;
      const s = parseInt(match[1]);
      if (!seasonMap[s]) seasonMap[s] = [];
      seasonMap[s].push({ episode: parseInt(match[2]), status: ep.status, title: ep.title });
    }

    const seasonNums = Object.keys(seasonMap).map(Number).sort((a, b) => a - b);

    for (const sNum of seasonNums) {
      const eps = seasonMap[sNum].sort((a, b) => a.episode - b.episode);
      const sDone = eps.filter(e => e.status === 'downloaded').length;
      const sTotal = eps.length;

      // Build episode grid (visual blocks for each episode)
      let grid = '';
      for (const ep of eps) {
        grid += STATUS_ICONS[ep.status] || STATUS_ICONS.pending;
      }

      const seasonLabel = `S${String(sNum).padStart(2, '0')}`;
      const countLabel = `${sDone}/${sTotal}`;

      if (sDone === sTotal) {
        console.log(`    ${c.dim}${seasonLabel}${c.reset} ${grid} ${c.green}${countLabel} ✓${c.reset}`);
      } else {
        console.log(`    ${color}${seasonLabel}${c.reset} ${grid} ${c.white}${countLabel}${c.reset}`);

        // Show currently active episode names
        const activeEps = eps.filter(e => e.status === 'downloading' || e.status === 'searching');
        for (const ep of activeEps) {
          const icon = ep.status === 'downloading' ? `${c.yellow}↓${c.reset}` : `${c.cyan}?${c.reset}`;
          console.log(`         ${icon} E${String(ep.episode).padStart(2, '0')} ${c.dim}${(ep.title || '').substring(0, 40)}${c.reset}`);
        }
      }
    }

    console.log();
  }

  // Grand total
  const grandPct = grandTotal > 0 ? ((grandDownloaded / grandTotal) * 100).toFixed(1) : '0.0';
  console.log(`${c.bold}${c.cyan}──────────────────────────────────────────────────────────────────${c.reset}`);
  console.log(`  ${c.bold}Total: ${grandDownloaded}/${grandTotal} episodes (${grandPct}%)${c.reset}  ${grandActive > 0 ? `${c.yellow}${grandActive} active${c.reset}  ` : ''}${grandErrors > 0 ? `${c.red}${grandErrors} errors${c.reset}  ` : ''}${grandNotFound > 0 ? `${c.red}${grandNotFound} not found${c.reset}` : ''}`);
  console.log(`  ${progressBar(grandDownloaded, grandTotal, 50)}`);
  console.log();

  // Disk usage
  try {
    const { execSync } = require('child_process');
    const du = execSync(`du -sh ${DFTV_ROOT} 2>/dev/null`, { encoding: 'utf-8' }).trim();
    const size = du.split('\t')[0];
    console.log(`  ${c.dim}Disk usage: ${size}${c.reset}`);
  } catch {}

  renderLogTail();
}

function renderLogTail() {
  const tail = getLogTail(6);
  if (tail.length > 0) {
    console.log();
    console.log(`  ${c.dim}─── Recent Log ───${c.reset}`);
    for (const line of tail) {
      // Colorize based on content
      let coloredLine = line.substring(0, 80);
      if (line.includes('error') || line.includes('Error') || line.includes('failed')) {
        coloredLine = `${c.red}${coloredLine}${c.reset}`;
      } else if (line.includes('Downloaded') || line.includes('Copied from Zurg')) {
        coloredLine = `${c.green}${coloredLine}${c.reset}`;
      } else if (line.includes('Searching') || line.includes('search')) {
        coloredLine = `${c.cyan}${coloredLine}${c.reset}`;
      } else {
        coloredLine = `${c.dim}${coloredLine}${c.reset}`;
      }
      console.log(`  ${coloredLine}`);
    }
  }

  console.log();
  console.log(`  ${c.dim}Press Ctrl+C to exit monitor${c.reset}`);
}

// Main
const args = process.argv.slice(2);
const once = args.includes('--once');

if (once) {
  render();
  process.exit(0);
}

// Live mode: refresh every 3 seconds
render();
const interval = setInterval(render, 3000);

process.on('SIGINT', () => {
  clearInterval(interval);
  process.stdout.write('\x1b[?25h'); // Show cursor
  console.log(`\n  ${c.dim}Monitor stopped${c.reset}\n`);
  process.exit(0);
});

// Hide cursor for cleaner display
process.stdout.write('\x1b[?25l');
