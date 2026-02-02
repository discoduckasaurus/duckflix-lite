/**
 * In-memory store for active RD download jobs
 */
class DownloadJobManager {
  constructor() {
    this.jobs = new Map();

    // Cleanup old jobs every 5 minutes
    setInterval(() => {
      this.cleanupOldJobs();
    }, 5 * 60 * 1000);
  }

  createJob(jobId, contentInfo) {
    this.jobs.set(jobId, {
      jobId,
      contentInfo,
      progress: 0,
      status: 'searching', // 'searching' | 'downloading' | 'completed' | 'error'
      message: 'Searching for content...',
      streamUrl: null,
      fileName: null,
      error: null,
      createdAt: Date.now()
    });

    return jobId;
  }

  updateJob(jobId, updates) {
    const job = this.jobs.get(jobId);
    if (job) {
      Object.assign(job, updates);
    }
  }

  getJob(jobId) {
    return this.jobs.get(jobId);
  }

  deleteJob(jobId) {
    this.jobs.delete(jobId);
  }

  // Cleanup jobs older than 5 minutes
  cleanupOldJobs() {
    const fiveMinutesAgo = Date.now() - (5 * 60 * 1000);
    let cleaned = 0;

    for (const [jobId, job] of this.jobs.entries()) {
      if (job.createdAt < fiveMinutesAgo) {
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
