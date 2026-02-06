// PM2 Ecosystem Configuration for DuckFlix Lite Server

module.exports = {
  apps: [{
    name: 'duckflix-lite-v2',
    script: 'index.js',
    cwd: '/home/ducky/duckflix-lite-server-v2',
    interpreter: '/home/ducky/.nvm/versions/node/v20.20.0/bin/node',
    instances: 1,
    exec_mode: 'fork',
    autorestart: true,
    watch: false,
    max_memory_restart: '8G',
    env: {
      NODE_ENV: 'production',
      PORT: 3001
    },
    error_file: './logs/pm2-error.log',
    out_file: './logs/pm2-out.log',
    log_file: './logs/pm2-combined.log',
    time: true,
    merge_logs: true
  }]
};
