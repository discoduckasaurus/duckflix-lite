const Database = require('better-sqlite3');
const db = new Database(process.env.DB_PATH || './db/duckflix_lite.db');

const row = db.prepare('SELECT * FROM special_channels WHERE id = ?').get('dftv-mixed');
console.log('special_channels:', row);

const meta = db.prepare('SELECT * FROM channel_metadata WHERE channel_id = ?').get('dftv-mixed');
console.log('channel_metadata:', meta || 'NOT FOUND');

// If no metadata entry, insert one as enabled
if (!meta) {
  db.prepare(`INSERT INTO channel_metadata (channel_id, custom_display_name, is_enabled, sort_order) VALUES ('dftv-mixed', 'DFTV', 1, 1)`).run();
  console.log('INSERTED channel_metadata for dftv-mixed');
}

db.close();
