#!/usr/bin/env node

const https = require('https');
const fs = require('fs');
const path = require('path');

// You'll need to set your TMDB API key here or via environment
const TMDB_API_KEY = process.env.TMDB_API_KEY || 'YOUR_API_KEY_HERE';

// Test content IDs
const tests = [
  { id: 1433, type: 'tv', name: 'American Dad!' },
  { id: 269149, type: 'movie', name: 'Zootopia' }
];

async function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
    }).on('error', reject);
  });
}

async function downloadImage(url, filepath) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      const file = fs.createWriteStream(filepath);
      res.pipe(file);
      file.on('finish', () => {
        file.close();
        resolve(filepath);
      });
    }).on('error', reject);
  });
}

async function testLogoFetch() {
  console.log('Testing TMDB Logo Fetching\n' + '='.repeat(50));

  for (const test of tests) {
    console.log(`\n${test.name} (${test.type} ID: ${test.id})`);
    console.log('-'.repeat(50));

    // 1. Fetch images
    const imagesUrl = `https://api.themoviedb.org/3/${test.type}/${test.id}/images?api_key=${TMDB_API_KEY}`;
    console.log(`Fetching: ${imagesUrl.replace(TMDB_API_KEY, 'API_KEY')}`);

    try {
      const images = await fetchJson(imagesUrl);

      console.log(`\nFound ${images.logos?.length || 0} logos`);

      if (images.logos && images.logos.length > 0) {
        // Find English logo
        const englishLogo = images.logos.find(logo => logo.iso_639_1 === 'en');
        const logo = englishLogo || images.logos[0];

        console.log(`\nSelected logo:`);
        console.log(`  Language: ${logo.iso_639_1 || 'null'}`);
        console.log(`  Path: ${logo.file_path}`);
        console.log(`  Size: ${logo.width}x${logo.height}`);
        console.log(`  Aspect ratio: ${logo.aspect_ratio}`);

        const logoUrl = `https://image.tmdb.org/t/p/w500${logo.file_path}`;
        console.log(`  Full URL: ${logoUrl}`);

        // Download logo
        const filename = `${test.name.replace(/[^a-z0-9]/gi, '_')}_logo.png`;
        const filepath = path.join(__dirname, filename);
        console.log(`\nDownloading to: ${filepath}`);

        await downloadImage(logoUrl, filepath);
        console.log(`✓ Downloaded successfully`);

        // Also save URL to text file
        const urlFile = filepath.replace('.png', '_url.txt');
        fs.writeFileSync(urlFile, logoUrl);
        console.log(`✓ URL saved to: ${urlFile}`);
      } else {
        console.log('✗ No logos available for this content');
      }
    } catch (error) {
      console.error(`✗ Error: ${error.message}`);
    }
  }

  console.log('\n' + '='.repeat(50));
  console.log('Test complete! Check the debug_logos directory for results.');
}

testLogoFetch();
