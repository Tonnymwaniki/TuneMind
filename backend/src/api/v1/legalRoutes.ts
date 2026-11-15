import { Router, Request, Response } from 'express';

const router = Router();

function renderLegalHtml(title: string, subtitle: string, sections: Array<{ heading: string; body: string }>): string {
  const sectionHtml = sections
    .map(
      (s) => `
      <section class="card">
        <h2>${s.heading}</h2>
        <p>${s.body}</p>
      </section>`
    )
    .join('\n');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${title} — TuneMind</title>
  <style>
    :root {
      --bg: #07090e;
      --card: #101521;
      --border: rgba(255, 255, 255, 0.1);
      --text: #f8fafc;
      --muted: #94a3b8;
      --accent: #00f0ff;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      padding: 32px 18px 64px;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
      background: radial-gradient(circle at top, #111b2e 0%, var(--bg) 60%);
      color: var(--text);
      line-height: 1.65;
    }
    main {
      max-width: 780px;
      margin: 0 auto;
    }
    header {
      margin-bottom: 24px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--border);
    }
    .brand {
      display: inline-block;
      font-size: 12px;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--accent);
      font-weight: 700;
      margin-bottom: 8px;
    }
    h1 {
      margin: 0 0 6px;
      font-size: 28px;
      letter-spacing: -0.02em;
    }
    .subtitle {
      margin: 0;
      color: var(--muted);
      font-size: 14px;
    }
    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 18px 20px;
      margin-bottom: 14px;
    }
    h2 {
      margin: 0 0 8px;
      font-size: 16px;
      color: #e2e8f0;
    }
    p {
      margin: 0;
      color: #cbd5e1;
      font-size: 14px;
    }
    footer {
      margin-top: 28px;
      color: var(--muted);
      font-size: 13px;
      display: flex;
      gap: 18px;
      flex-wrap: wrap;
    }
    a {
      color: var(--accent);
      text-decoration: none;
    }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <main>
    <header>
      <div class="brand">TuneMind</div>
      <h1>${title}</h1>
      <p class="subtitle">${subtitle}</p>
    </header>
    ${sectionHtml}
    <footer>
      <a href="/privacy">Privacy Policy</a>
      <a href="/terms">Terms of Service</a>
      <a href="/health">Service Status</a>
    </footer>
  </main>
</body>
</html>`;
}

/**
 * GET /privacy
 * Public HTML Privacy Policy for Google Play Console & in-app legal links.
 */
router.get('/privacy', (_req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(
    renderLegalHtml(
      'TuneMind Privacy Policy',
      'Effective November 2026 · Local-First Listening Companion Architecture',
      [
        {
          heading: '1. Account Identity & Credentials',
          body: 'When you sign in to TuneMind with Google or Email & Password, TuneMind stores your profile display name, email address, and taste preferences in our encrypted database so you can switch devices seamlessly. Spotify is never used to create a TuneMind account — it is connected strictly as a music data source.'
        },
        {
          heading: '2. Listening Telemetry & On-Device Storage',
          body: 'Playback history, repetition fatigue (satiety) calculations, Cooldown Vault tracks, and Musical Moments are primarily processed and stored locally on your device inside an encrypted Room SQLite database. You can pause all tracking immediately using Private Session in Profile or Settings.'
        },
        {
          heading: '3. Device & Music Permissions',
          body: 'Microphone access is activated only when wake-word mode is enabled or when you tap the TuneMind orb. Notification Listener permission reads active media session metadata locally on-device. Spotify OAuth scopes are used solely to sync your liked tracks, playlists, podcasts, and recently played catalog.'
        },
        {
          heading: '4. Your Data Controls & Account Deletion',
          body: 'From the TuneMind Profile screen you can export your complete listening data in JSON format, clear listening history, disconnect Spotify data without deleting your account, or permanently wipe your account and cloud records via Delete Account & Wipe Data.'
        }
      ]
    )
  );
});

/**
 * GET /terms
 * Public HTML Terms of Service for Google Play Console & in-app legal links.
 */
router.get('/terms', (_req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(
    renderLegalHtml(
      'TuneMind Terms of Service',
      'TuneMind Personal Listening Companion',
      [
        {
          heading: '1. Personal Listening Companion',
          body: 'TuneMind helps you track repetition fatigue, organize musical moments, monitor safe volume headroom, and control playback across supported music services for personal, non-commercial use.'
        },
        {
          heading: '2. Third-Party Services & Content Rights',
          body: 'TuneMind integrates with third-party platforms such as Spotify and Google Play Services under their respective terms. All audio streaming rights remain with your selected music provider; TuneMind does not bypass DRM or redistribute copyrighted audio.'
        },
        {
          heading: '3. Safe Listening Disclaimer',
          body: 'Decibel headroom and daily exposure estimates are informational heuristics derived from device volume steps and audio route characteristics. They are not medical advice — always adjust playback volume responsibly.'
        },
        {
          heading: '4. Account Termination & Support',
          body: 'You may stop using TuneMind or delete your account and associated cloud data at any time from Profile -> Delete Account & Wipe Data, or reach out through Help & Feedback inside the app.'
        }
      ]
    )
  );
});

/**
 * GET /api/v1/app/version
 * Returns current mobile app release metadata for optional / forced update prompts.
 */
router.get('/api/v1/app/version', (_req: Request, res: Response) => {
  const latestVersion = (process.env.TUNEMIND_LATEST_VERSION || '0.1.0').trim();
  const minSupportedVersion = (process.env.TUNEMIND_MIN_VERSION || '0.1.0').trim();
  const latestVersionCode = parseInt(process.env.TUNEMIND_LATEST_VERSION_CODE || '1', 10) || 1;
  const minSupportedVersionCode = parseInt(process.env.TUNEMIND_MIN_VERSION_CODE || '1', 10) || 1;
  const forceUpdate = (process.env.TUNEMIND_FORCE_UPDATE || 'false').trim().toLowerCase() === 'true';
  const playStoreUrl = (
    process.env.TUNEMIND_PLAY_STORE_URL ||
    'https://play.google.com/store/apps/details?id=com.tunemind.app'
  ).trim();

  res.json({
    status: 'ok',
    platform: 'android',
    latest_version: latestVersion,
    min_supported_version: minSupportedVersion,
    latest_version_code: latestVersionCode,
    min_supported_version_code: minSupportedVersionCode,
    force_update: forceUpdate,
    play_store_url: playStoreUrl,
    privacy_url: '/privacy',
    terms_url: '/terms'
  });
});

export default router;
