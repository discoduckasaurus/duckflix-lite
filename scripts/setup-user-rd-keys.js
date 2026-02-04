#!/usr/bin/env node

/**
 * Migration: Add RD API keys to users
 * - Admin gets ENV RD_API_KEY
 * - Create test user "Tawnia" with test RD_API_KEY
 */

const Database = require('better-sqlite3');
const path = require('path');
const bcrypt = require('bcryptjs');
require('dotenv').config();

const DB_PATH = process.env.DB_PATH || path.join(__dirname, '../db/duckflix_lite.db');
const db = new Database(DB_PATH);

async function migrate() {
  console.log('Setting up user RD API keys...');

  // 1. Update admin user to have ENV RD_API_KEY
  const adminUsername = process.env.ADMIN_USERNAME || 'admin';
  const envRdApiKey = process.env.RD_API_KEY;

  if (envRdApiKey) {
    db.prepare(`
      UPDATE users
      SET rd_api_key = ?
      WHERE username = ? AND is_admin = 1
    `).run(envRdApiKey, adminUsername);
    console.log(`✅ Updated admin user "${adminUsername}" with ENV RD_API_KEY`);
  } else {
    console.log('⚠️  No RD_API_KEY in environment, skipping admin update');
  }

  // 2. Create test user "Tawnia" if doesn't exist
  const testUsername = 'Tawnia';
  const testPassword = 'jjjjjj';
  const testRdApiKey = 'IOGOUVDH4JSBH57UJAFDP3O375DCPSKP7ERWPURNCP3CCNUSFPKQ';

  const existingUser = db.prepare('SELECT id FROM users WHERE username = ?').get(testUsername);

  if (existingUser) {
    // Update existing user
    db.prepare(`
      UPDATE users
      SET rd_api_key = ?
      WHERE username = ?
    `).run(testRdApiKey, testUsername);
    console.log(`✅ Updated existing user "${testUsername}" with test RD_API_KEY`);
  } else {
    // Create new user
    const passwordHash = await bcrypt.hash(testPassword, 10);
    db.prepare(`
      INSERT INTO users (username, password_hash, is_admin, rd_api_key)
      VALUES (?, ?, 0, ?)
    `).run(testUsername, passwordHash, testRdApiKey);
    console.log(`✅ Created test user "${testUsername}" with password "${testPassword}"`);
  }

  console.log('\n✅ Migration complete!');
  console.log(`\nTest user credentials:`);
  console.log(`  Username: ${testUsername}`);
  console.log(`  Password: ${testPassword}`);
  console.log(`  RD API Key: ${testRdApiKey.substring(0, 10)}...`);

  db.close();
}

migrate().catch(err => {
  console.error('Migration failed:', err);
  process.exit(1);
});
