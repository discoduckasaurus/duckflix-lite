// PM2 Ecosystem Configuration for DuckFlix Lite Server

module.exports = {
  apps: [{
    name: 'duckflix-lite',
    script: 'index.js',
    cwd: '/var/lib/duckflix_lite/server',
    instances: 1,
    autorestart: true,
    watch: false,
    max_memory_restart: '500M',
    env: {
      NODE_ENV: 'production',
      PORT: 3001
    },
    error_file: '/var/lib/duckflix_lite/logs/pm2-error.log',
    out_file: '/var/lib/duckflix_lite/logs/pm2-out.log',
    log_file: '/var/lib/duckflix_lite/logs/pm2-combined.log',
    time: true,
    merge_logs: true
  }]
};
