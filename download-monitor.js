#!/usr/bin/env node
const blessed = require("blessed");
const contrib = require("blessed-contrib");
const https = require("https");

const API_URL = "https://localhost:3001/api/monitor/stats";

const screen = blessed.screen({
  smartCSR: true,
  title: "DuckFlix Download Monitor"
});

const grid = new contrib.grid({ rows: 12, cols: 12, screen: screen });

const activeTable = grid.set(0, 0, 7, 12, contrib.table, {
  keys: true,
  fg: "white",
  selectedFg: "white",
  selectedBg: "blue",
  interactive: false,
  label: " Active Downloads ",
  width: "100%",
  height: "100%",
  border: { type: "line", fg: "cyan" },
  columnSpacing: 2,
  columnWidth: [10, 15, 6, 35, 12, 8, 10]
});

const completedTable = grid.set(7, 0, 5, 12, contrib.table, {
  keys: true,
  fg: "white",
  selectedFg: "white",
  selectedBg: "green",
  interactive: false,
  label: " Recent Playback Requests (Last 20) ",
  width: "100%",
  height: "100%",
  border: { type: "line", fg: "green" },
  columnSpacing: 2,
  columnWidth: [10, 15, 6, 30, 15, 8, 8]
});

let errorCount = 0;
let successCount = 0;

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return "N/A";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

function formatElapsed(timestamp, endTimestamp = null) {
  const endTime = endTimestamp || Date.now();
  const elapsed = Math.floor((endTime - timestamp) / 1000);
  if (elapsed < 60) return `${elapsed}s ago`;
  if (elapsed < 3600) {
    const minutes = Math.floor(elapsed / 60);
    return `${minutes}m ago`;
  }
  const hours = Math.floor(elapsed / 3600);
  const minutes = Math.floor((elapsed % 3600) / 60);
  return `${hours}h ${minutes}m ago`;
}

function truncate(text, maxLen) {
  if (!text) return "";
  return text.length > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
}

function formatContent(job) {
  const { title, type, season, episode } = job.contentInfo;
  if (type === "tv" || type === "episode") {
    return `${truncate(title, 20)} S${String(season).padStart(2, "0")}E${String(episode).padStart(2, "0")}`;
  }
  return truncate(title, 30);
}

function formatSource(job) {
  if (!job.source) return "Searching...";
  const quality = job.source.match(/\b(2160p|4K|1080p|720p|480p)\b/i)?.[0] || "";
  return truncate(`${quality}`, 12);
}

function getStatusColor(status) {
  switch (status) {
    case "searching": return "{yellow-fg}";
    case "downloading": return "{cyan-fg}";
    case "completed": return "{green-fg}";
    case "error": return "{red-fg}";
    default: return "{white-fg}";
  }
}

function fetchMonitorData() {
  return new Promise((resolve, reject) => {
    const options = {
      rejectUnauthorized: false
    };
    
    https.get(API_URL, options, (res) => {
      let data = "";
      res.on("data", chunk => data += chunk);
      res.on("end", () => {
        try {
          resolve(JSON.parse(data));
        } catch (err) {
          reject(new Error(`Parse error: ${err.message}`));
        }
      });
    }).on("error", reject);
  });
}

async function updateDisplay() {
  try {
    const data = await fetchMonitorData();
    const { activeJobs, recentPlaybacks } = data;
    
    successCount++;
    errorCount = 0; // Reset error count on success

    const activeHeaders = ["User", "IP", "RD Key", "Content", "Source", "Progress", "Time"];
    const activeData = activeJobs
      .filter(job => job.status !== "completed" && job.status !== "error")
      .map(job => {
        const statusColor = getStatusColor(job.status);
        return [
          truncate(job.userInfo.username, 10),
          truncate(job.userInfo.ip, 15),
          job.userInfo.rdApiKeyLast4,
          formatContent(job),
          formatSource(job),
          `${statusColor}${job.progress}%{/}`,
          formatElapsed(job.createdAt)
        ];
      });

    if (activeData.length === 0) {
      activeData.push(["", "", "", "No active downloads", "", "", ""]);
    }

    activeTable.setData({
      headers: activeHeaders,
      data: activeData
    });

    const playbackHeaders = ["User", "IP", "RD Key", "Content", "Source", "Status", "Time"];
    const playbackData = recentPlaybacks.map(pb => {
      let sourceColor = "{white-fg}";
      let sourceLabel = pb.source;

      if (pb.source === "zurg") {
        sourceColor = "{cyan-fg}";
        sourceLabel = "Zurg";
      } else if (pb.source === "rd-cached") {
        sourceColor = "{green-fg}";
        sourceLabel = "RD Cache";
      } else if (pb.source === "rd-download") {
        sourceColor = "{yellow-fg}";
        sourceLabel = "RD Download";
      }

      return [
        truncate(pb.userInfo.username, 10),
        truncate(pb.userInfo.ip, 15),
        pb.userInfo.rdApiKeyLast4 || "N/A",
        formatContent(pb),
        `${sourceColor}${sourceLabel}{/}`,
        "{green-fg}Playing{/}",
        formatElapsed(pb.timestamp)
      ];
    });

    if (playbackData.length === 0) {
      playbackData.push(["", "", "", "No recent playback requests", "", "", ""]);
    }

    completedTable.setData({
      headers: playbackHeaders,
      data: playbackData
    });

    updateHelpText(`Connected (${successCount} updates) | Q/ESC=quit | Refresh: 1s | Zurg(cyan) RD-Cache(green) RD-DL(yellow)`);
    screen.render();
  } catch (error) {
    errorCount++;
    updateHelpText(`{red-fg}ERROR (${errorCount}): ${error.message}{/} | Press Q to quit`);
    screen.render();
  }
}

function updateHelpText(text) {
  helpText.setContent(` ${text}`);
}

screen.key(["escape", "q", "C-c"], function() {
  return process.exit(0);
});

const helpText = blessed.box({
  bottom: 0,
  left: 0,
  width: "100%",
  height: 1,
  content: " Connecting to server...",
  style: {
    fg: "white",
    bg: "black"
  },
  tags: true
});
screen.append(helpText);

updateDisplay();
setInterval(updateDisplay, 1000);
screen.render();
