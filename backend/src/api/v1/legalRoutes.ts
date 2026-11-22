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
      <a href="/">Home</a>
      <a href="/privacy">Privacy Policy</a>
      <a href="/terms">Terms of Service</a>
      <a href="/refund-policy">Refund Policy</a>
      <a href="/cookies">Cookie Policy</a>
      <a href="/delete-account">Account Deletion</a>
      <a href="mailto:support@tunemind.co">support@tunemind.co</a>
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
          heading: '1. Data We Collect',
          body: 'TuneMind collects only the data needed to operate your listening companion: (a) Account Identity — email address, display name, and authentication provider; (b) Music Library & OAuth Data — encrypted OAuth tokens, playlists, liked/saved songs, and playback/listening history; (c) Device & Telemetry Identifiers — device model/OS version, audio route state, and anonymized app session identifiers.'
        },
        {
          heading: '2. AI Processing & Personalization',
          body: 'Your listening history, track metadata, satiety/repetition patterns, and voice or text prompts are processed using AI to generate musical insights, daily track recommendations, and conversational playback assistance. TuneMind does not sell your personal data or listening history.'
        },
        {
          heading: '3. Third-Party Processors & Integrations',
          body: 'TuneMind relies on the following named processors and integrations: <strong>Anthropic (Claude API)</strong> for AI reasoning and music insights; <strong>Supabase</strong> for authentication, encrypted database storage, and private user file storage; <strong>Spotify</strong>, <strong>Google / YouTube Data API</strong>, and <strong>Audiomack</strong> for cross-app music metadata and playback integration; <strong>Google Play Billing</strong> for subscription management and verification; and <strong>Namecheap Private Email</strong> for transactional account and trial-expiration notifications.'
        },
        {
          heading: '4. Device & Music Permissions',
          body: 'Microphone access is activated only when wake-word mode is enabled or when you tap the TuneMind orb. Notification Listener permission reads active media session metadata locally on-device across your installed music players (such as Spotify, YouTube Music, Audiomack, Boomplay, and local audio) so TuneMind can organize per-app listening stats and control active playback. You can pause tracking anytime via Private Session in Profile or Settings.'
        },
        {
          heading: '5. Your Data Controls & Hard Deletion',
          body: 'From TuneMind Profile or Settings you can export your complete listening data in JSON format, clear listening history, or permanently erase your account, OAuth tokens, database records, and private cloud storage files via <strong>Delete Account &amp; Wipe Data</strong> (or at <a href="/delete-account">tunemind.co/delete-account</a>).'
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
          body: 'TuneMind helps you track repetition fatigue, protect song freshness, monitor safe volume headroom, and control playback across supported Android music apps and local audio for personal, non-commercial use.'
        },
        {
          heading: '2. Third-Party Services & Content Rights',
          body: 'TuneMind interoperates with installed media players on your device using Android media session standards and queries public music metadata catalogs. All audio streaming rights remain with your selected music provider; TuneMind does not bypass DRM or redistribute copyrighted audio.'
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
 * GET /refund-policy
 * Public HTML Refund & Subscription Policy for Google Play Billing & web links.
 */
router.get('/refund-policy', (_req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(
    renderLegalHtml(
      'TuneMind Refund & Subscription Policy',
      'TuneMind Labs · support@tunemind.co',
      [
        {
          heading: '1. 7-Day Free Trial & Billing Cycle',
          body: 'TuneMind Pro offers a 7-day free trial on eligible Google Play plans ($4.99/month or $29.99/year, localized by region). You will not be charged if you cancel before the trial ends.'
        },
        {
          heading: '2. One-Click Cancellation',
          body: 'You can cancel your subscription anytime directly from TuneMind (Settings or Paywall -> Manage or Cancel Subscription in Google Play) or via Google Play Subscriptions. Cancellation stops future renewals while preserving access through the end of the current billing period.'
        },
        {
          heading: '3. Google Play Refund Rules',
          body: 'All Android purchases are processed by Google Play Billing. Within 48 hours of an accidental charge or renewal, you can request a refund directly through Google Play Order History. After 48 hours, email support@tunemind.co with your Google Play order ID (GPA.*) for review.'
        },
        {
          heading: '4. Official Contact',
          body: 'Developer / Business Entity: TuneMind Labs · Official Support: support@tunemind.co · Legal Inquiries: legal@tunemind.co.'
        }
      ]
    )
  );
});

/**
 * GET /cookies
 * Public HTML Cookie Policy for tunemind.co and OAuth web surfaces.
 */
router.get('/cookies', (_req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(
    renderLegalHtml(
      'TuneMind Cookie Policy',
      'TuneMind Labs · tunemind.co',
      [
        {
          heading: '1. Essential Storage Only by Default',
          body: 'tunemind.co and TuneMind OAuth sign-in surfaces use strictly necessary session storage and security tokens (such as OAuth state and CSRF protection) required for login and core functionality.'
        },
        {
          heading: '2. Optional Analytics Cookies (Opt-In)',
          body: 'Non-essential analytics cookies are blocked by default until you explicitly click "Accept Analytics" on the cookie consent banner. You may withdraw consent at any time by clearing site storage or updating your cookie preference.'
        },
        {
          heading: '3. Contact',
          body: 'Questions about cookies or data privacy may be sent to support@tunemind.co.'
        }
      ]
    )
  );
});

/**
 * GET /delete-account
 * Public web deletion instructions & contact URL required by Google Play Data Safety.
 */
router.get('/delete-account', (_req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(
    renderLegalHtml(
      'Delete Your TuneMind Account & Data',
      'TuneMind Account & Cloud Data Deletion',
      [
        {
          heading: '1. Instant In-App Deletion (Recommended)',
          body: 'Open TuneMind on Android, go to Profile -> Delete Account & Wipe Data, and confirm. TuneMind immediately removes your profile, synced listening events, agent memory, taste genome, support feedback, and Supabase Auth login.'
        },
        {
          heading: '2. Request Deletion Without the App',
          body: 'If you uninstalled TuneMind and want your cloud account and associated listening intelligence deleted, email support@tunemind.co from your registered account email with subject "Delete TuneMind Account". Verified requests are permanently erased within 7 days.'
        },
        {
          heading: '3. Data Removed vs. On-Device Data',
          body: 'Account deletion permanently erases your Supabase Auth user, profile row, synced listening telemetry, taste genome, discovery outcomes, and support history. Local TuneMind playlists stored on your phone are erased automatically when you clear app data or uninstall.'
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
