const sgMail = require('@sendgrid/mail');
const logger = require('../utils/logger');

// Initialize SendGrid
const apiKey = process.env.SENDGRID_API_KEY;
if (apiKey) {
  sgMail.setApiKey(apiKey);
  logger.info('SendGrid email service initialized');
} else {
  logger.warn('SENDGRID_API_KEY not configured, email features disabled');
}

/**
 * Send user report email to admin
 * @param {Object} report - Report details
 * @param {string} report.username - User who submitted report
 * @param {string} report.page - Page where report was submitted (home, title, etc)
 * @param {string} report.title - Title/content being viewed (if applicable)
 * @param {string} report.tmdbId - TMDB ID (if applicable)
 * @param {string} report.message - Optional user message
 * @param {string} report.timestamp - Timestamp of report
 * @param {string} report.userAgent - User's browser/app info
 */
async function sendUserReport(report) {
  if (!apiKey) {
    logger.warn('Cannot send report email: SendGrid not configured');
    return false;
  }

  const adminEmail = process.env.ADMIN_EMAIL || 'admin@duckflix.tv';

  const msg = {
    to: adminEmail,
    from: adminEmail, // SendGrid requires verified sender
    subject: `DuckFlix Report: ${report.page} - ${report.username}`,
    html: `
      <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
        <h2 style="color: #2563eb;">User Report from DuckFlix</h2>

        <table style="width: 100%; border-collapse: collapse; margin: 20px 0;">
          <tr style="background-color: #f3f4f6;">
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">User</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${report.username}</td>
          </tr>
          <tr>
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">Page</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${report.page}</td>
          </tr>
          ${report.title ? `
          <tr style="background-color: #f3f4f6;">
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">Title</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${report.title}</td>
          </tr>
          ` : ''}
          ${report.tmdbId ? `
          <tr>
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">TMDB ID</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${report.tmdbId}</td>
          </tr>
          ` : ''}
          <tr style="background-color: #f3f4f6;">
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">Timestamp</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${new Date(report.timestamp).toLocaleString()}</td>
          </tr>
          ${report.message ? `
          <tr>
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">Message</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb;">${report.message}</td>
          </tr>
          ` : ''}
          <tr style="background-color: #f3f4f6;">
            <td style="padding: 12px; font-weight: bold; border: 1px solid #e5e7eb;">User Agent</td>
            <td style="padding: 12px; border: 1px solid #e5e7eb; font-size: 11px;">${report.userAgent}</td>
          </tr>
        </table>

        <p style="color: #6b7280; font-size: 12px; margin-top: 30px;">
          This report was automatically generated from DuckFlix Lite
        </p>
      </div>
    `,
    text: `
User Report from DuckFlix

User: ${report.username}
Page: ${report.page}
${report.title ? `Title: ${report.title}` : ''}
${report.tmdbId ? `TMDB ID: ${report.tmdbId}` : ''}
Timestamp: ${new Date(report.timestamp).toLocaleString()}
${report.message ? `Message: ${report.message}` : ''}
User Agent: ${report.userAgent}
    `
  };

  try {
    await sgMail.send(msg);
    logger.info(`Report email sent for ${report.username} on ${report.page}`);
    return true;
  } catch (error) {
    logger.error('Failed to send report email:', {
      error: error.message,
      response: error.response?.body
    });
    return false;
  }
}

module.exports = {
  sendUserReport
};
