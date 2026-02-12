const fs = require('fs');

/**
 * In-memory store for active RD download jobs and all playback activity
 */
class DownloadJobManager {
  constructor() {
    this.jobs = new Map();
    this.completedJobs = []; // Keep last 20 completed jobs
    this.recentPlaybacks = []; // Keep last 20 playback requests (all sources)
    this.maxCompletedJobs = 20;
    this.maxRecentPlaybacks = 20;

    // Cleanup old jobs every 5 minutes
    setInterval(() => {
      this.cleanupOldJobs();
    }, 5 * 60 * 1000);
  }

  createJob(jobId, contentInfo, userInfo = {}, options = {}) {
    this.jobs.set(jobId, {
      jobId,
      contentInfo,
      userInfo: {
        username: userInfo.username || 'unknown',
        userId: userInfo.userId,
        ip: userInfo.ip || 'unknown',
        rdApiKeyLast4: userInfo.rdApiKey ? userInfo.rdApiKey.slice(-4) : 'N/A'
      },
      isPrefetch: !!options.isPrefetch,
      progress: 0,
      status: 'searching', // 'searching' | 'downloading' | 'completed' | 'error'
      message: 'Searching for content...',
      streamUrl: null,
      fileName: null,
      fileSize: null,
      quality: null, // Quality extracted from resolved stream (e.g., "2160p", "1080p")
      source: null,
      error: null,
      attemptedSources: [], // Track all sources tried
      createdAt: Date.now(),
      completedAt: null
    });

    return jobId;
  }

  /**
   * Track that a source was attempted for this job
   */
  addAttemptedSource(jobId, sourceInfo) {
    const job = this.jobs.get(jobId);
    if (job && job.attemptedSources) {
      job.attemptedSources.push({
        ...sourceInfo,
        attemptedAt: Date.now()
      });
    }
  }

  /**
   * Get list of attempted sources for a job
   */
  getAttemptedSources(jobId) {
    const job = this.jobs.get(jobId);
    return job?.attemptedSources || [];
  }

  updateJob(jobId, updates) {
    const job = this.jobs.get(jobId);
    if (job) {
      Object.assign(job, updates);

      // If job is now completed or errored, move to completed list
      if ((updates.status === 'completed' || updates.status === 'error') && !job.completedAt) {
        job.completedAt = Date.now();
        this.completedJobs.unshift({ ...job }); // Add to front

        // Keep only last 20 completed jobs
        if (this.completedJobs.length > this.maxCompletedJobs) {
          this.completedJobs = this.completedJobs.slice(0, this.maxCompletedJobs);
        }
      }
    }
  }

  getJob(jobId) {
    return this.jobs.get(jobId);
  }

  getAllJobs() {
    return Array.from(this.jobs.values());
  }

  getCompletedJobs() {
    return this.completedJobs;
  }

  /**
   * Track immediate playback (Zurg, RD cache, etc.)
   */
  trackPlayback(contentInfo, userInfo, source, streamUrl, fileName) {
    console.log(`[DownloadJobManager] trackPlayback called: ${contentInfo.title} via ${source} by ${userInfo.username}`);

    // Normalize userInfo to match job format
    const normalizedUserInfo = {
      username: userInfo.username || 'unknown',
      userId: userInfo.userId,
      ip: userInfo.ip || 'unknown',
      rdApiKeyLast4: userInfo.rdApiKey ? userInfo.rdApiKey.slice(-4) : 'N/A'
    };

    const playback = {
      contentInfo,
      userInfo: normalizedUserInfo,
      source, // 'zurg', 'rd-cached', 'rd-download'
      streamUrl,
      fileName,
      status: 'playing',
      timestamp: Date.now()
    };

    this.recentPlaybacks.unshift(playback);
    console.log(`[DownloadJobManager] Total playbacks tracked: ${this.recentPlaybacks.length}`);

    // Keep only last 20
    if (this.recentPlaybacks.length > this.maxRecentPlaybacks) {
      this.recentPlaybacks = this.recentPlaybacks.slice(0, this.maxRecentPlaybacks);
    }
  }

  getRecentPlaybacks() {
    return this.recentPlaybacks;
  }

  deleteJob(jobId) {
    this.jobs.delete(jobId);
  }

  // Cleanup old jobs (keep active downloads indefinitely, errors for 12 hours)
  cleanupOldJobs() {
    const fiveMinutesAgo = Date.now() - (5 * 60 * 1000);
    const twelveHoursAgo = Date.now() - (12 * 60 * 60 * 1000);
    let cleaned = 0;

    for (const [jobId, job] of this.jobs.entries()) {
      // Keep searching/downloading jobs alive indefinitely (user decides when to cancel)
      // Cleanup completed jobs after 5 minutes
      // Keep error jobs for 12 hours (so user can see what failed in Continue Watching)
      const isCompleted = job.status === 'completed';
      const isError = job.status === 'error';

      if (isCompleted && job.completedAt && job.completedAt < fiveMinutesAgo) {
        if (job.processedFilePath) {
          fs.unlink(job.processedFilePath, () => {});
        }
        this.jobs.delete(jobId);
        cleaned++;
      } else if (isError && job.completedAt && job.completedAt < twelveHoursAgo) {
        if (job.processedFilePath) {
          fs.unlink(job.processedFilePath, () => {});
        }
        this.jobs.delete(jobId);
        cleaned++;
      }
    }

    if (cleaned > 0) {
      console.log(`[Download Job Manager] Cleaned up ${cleaned} old jobs`);
    }
  }
}

module.exports = new DownloadJobManager();
