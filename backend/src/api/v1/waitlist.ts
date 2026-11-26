import { Router, Request, Response } from 'express';
import { getSupabaseAdminClient, getLiveSupabaseClient } from '../../config/supabase';

const router = Router();

const ALLOWED_PLAYERS = new Set([
  'Spotify',
  'YouTube Music',
  'Boomplay',
  'Audiomack',
  'Phone Audio / Local',
  'Multiple'
]);

const CLOSED_BETA_LINKS = {
  google_group: 'https://groups.google.com/g/tunemind-closed-beta',
  opt_in: 'https://play.google.com/apps/testing/com.tunemind.app',
  play_store: 'https://play.google.com/store/apps/details?id=com.tunemind.app'
};

interface WaitlistRecord {
  email: string;
  preferred_player: string;
  wants_closed_beta: boolean;
  referral_source: string;
  created_at: string;
}

// Local fallback store when Supabase env vars are not configured (e.g. unit tests / local dev)
const memoryWaitlist = new Map<string, WaitlistRecord>();

function normalizeEmail(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  return raw.trim().toLowerCase();
}

function isValidEmail(email: string): boolean {
  if (!email || email.length > 254) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email);
}

function normalizePlayer(raw: unknown): string {
  if (typeof raw !== 'string') return 'Spotify';
  const trimmed = raw.trim();
  return ALLOWED_PLAYERS.has(trimmed) ? trimmed : 'Spotify';
}

function normalizeReferral(raw: unknown): string {
  if (typeof raw !== 'string') return 'direct';
  const cleaned = raw.trim().toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 40);
  return cleaned || 'direct';
}

async function getTotalCount(): Promise<number> {
  const client = getSupabaseAdminClient() || getLiveSupabaseClient();
  if (!client) {
    return memoryWaitlist.size;
  }
  try {
    const { count, error } = await client
      .from('waitlist_signups')
      .select('id', { count: 'exact', head: true });
    if (!error && typeof count === 'number') {
      return count;
    }
  } catch {
    // fall through
  }
  return memoryWaitlist.size;
}

/**
 * GET /api/v1/waitlist/stats
 * Public signup count only — never exposes emails or PII.
 */
router.get('/stats', async (_req: Request, res: Response) => {
  const count = await getTotalCount();
  res.json({ ok: true, count });
});

/**
 * POST /api/v1/waitlist
 * Saves an email + preferred music app + closed beta opt-in to Supabase `waitlist_signups`.
 */
router.post('/', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const email = normalizeEmail(body.email);
    if (!isValidEmail(email)) {
      res.status(400).json({ ok: false, error: 'Please enter a valid email address.' });
      return;
    }

    const preferredPlayer = normalizePlayer(body.preferred_player);
    const wantsClosedBeta = Boolean(body.wants_closed_beta);
    const referralSource = normalizeReferral(body.referral_source);
    const userAgent = String(req.headers['user-agent'] || '').slice(0, 240);

    const client = getSupabaseAdminClient() || getLiveSupabaseClient();
    let alreadyRegistered = false;

    if (client) {
      const { data: existing, error: selectErr } = await client
        .from('waitlist_signups')
        .select('id, wants_closed_beta')
        .eq('email', email)
        .maybeSingle();

      if (selectErr) {
        console.error('[Waitlist] Supabase lookup error:', selectErr.message);
        res.status(500).json({ ok: false, error: 'Could not save your spot right now. Please try again in a moment.' });
        return;
      }

      if (existing) {
        alreadyRegistered = true;
        // Update preference/beta flag if they came back to opt into beta
        await client
          .from('waitlist_signups')
          .update({
            preferred_player: preferredPlayer,
            wants_closed_beta: wantsClosedBeta || Boolean(existing.wants_closed_beta)
          })
          .eq('id', existing.id);
      } else {
        const { error: insertErr } = await client.from('waitlist_signups').insert({
          email,
          preferred_player: preferredPlayer,
          wants_closed_beta: wantsClosedBeta,
          referral_source: referralSource,
          user_agent: userAgent
        });
        if (insertErr) {
          // Handle race-condition unique violation cleanly
          if (insertErr.code === '23505') {
            alreadyRegistered = true;
          } else {
            console.error('[Waitlist] Supabase insert error:', insertErr.message);
            res.status(500).json({ ok: false, error: 'Could not save your spot right now. Please try again in a moment.' });
            return;
          }
        }
      }
    } else {
      if (memoryWaitlist.has(email)) {
        alreadyRegistered = true;
        const prev = memoryWaitlist.get(email)!;
        memoryWaitlist.set(email, {
          ...prev,
          preferred_player: preferredPlayer,
          wants_closed_beta: wantsClosedBeta || prev.wants_closed_beta
        });
      } else {
        memoryWaitlist.set(email, {
          email,
          preferred_player: preferredPlayer,
          wants_closed_beta: wantsClosedBeta,
          referral_source: referralSource,
          created_at: new Date().toISOString()
        });
      }
    }

    const totalCount = await getTotalCount();

    res.status(alreadyRegistered ? 200 : 201).json({
      ok: true,
      already_registered: alreadyRegistered,
      wants_closed_beta: wantsClosedBeta,
      total_count: totalCount,
      message: alreadyRegistered
        ? "You're already on the TuneMind waitlist — we'll email you first when we launch on Google Play!"
        : "You're on the TuneMind waitlist! We'll notify you first when we launch on Google Play.",
      closed_beta_links: wantsClosedBeta ? CLOSED_BETA_LINKS : undefined
    });
  } catch (err: any) {
    console.error('[Waitlist] Unexpected error:', err);
    res.status(500).json({ ok: false, error: 'Something went wrong. Please try again.' });
  }
});

export function renderWaitlistClientScript(): string {
  return `(function () {
  var form = document.getElementById('waitlist-form');
  var emailInput = document.getElementById('wl-email');
  var playerSelect = document.getElementById('wl-player');
  var betaCheckbox = document.getElementById('wl-beta');
  var submitBtn = document.getElementById('wl-submit');
  var errorBox = document.getElementById('wl-error');
  var successBox = document.getElementById('wl-success');
  var successMsg = document.getElementById('wl-success-msg');
  var betaSteps = document.getElementById('wl-beta-steps');
  var countBadge = document.getElementById('wl-count');
  var copyBtn = document.getElementById('wl-copy-share');

  function getRefSource() {
    try {
      var params = new URLSearchParams(window.location.search);
      return params.get('ref') || params.get('utm_source') || 'direct';
    } catch (e) {
      return 'direct';
    }
  }

  function updateCount(n) {
    if (!countBadge || typeof n !== 'number' || n <= 0) return;
    countBadge.textContent = n + (n === 1 ? ' music lover on the waitlist' : ' music lovers on the waitlist');
    countBadge.style.display = 'inline-flex';
  }

  fetch('/api/v1/waitlist/stats')
    .then(function (r) { return r.json(); })
    .then(function (d) { if (d && d.ok && typeof d.count === 'number') updateCount(d.count); })
    .catch(function () {});

  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var shareUrl = 'https://tunemind.co/?ref=share';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(shareUrl).then(function () {
          copyBtn.textContent = 'Link copied!';
          setTimeout(function () { copyBtn.textContent = 'Share tunemind.co'; }, 2200);
        }).catch(function () {});
      }
    });
  }

  if (!form) return;

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    if (errorBox) {
      errorBox.style.display = 'none';
      errorBox.textContent = '';
    }
    var email = emailInput ? emailInput.value.trim() : '';
    if (!email) {
      if (errorBox) {
        errorBox.textContent = 'Please enter your email address.';
        errorBox.style.display = 'block';
      }
      return;
    }

    var originalBtnText = submitBtn ? submitBtn.textContent : 'Join Waitlist';
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Saving your spot...';
    }

    fetch('/api/v1/waitlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email,
        preferred_player: playerSelect ? playerSelect.value : 'Spotify',
        wants_closed_beta: betaCheckbox ? Boolean(betaCheckbox.checked) : false,
        referral_source: getRefSource()
      })
    })
      .then(function (resp) {
        return resp.json().then(function (data) { return { ok: resp.ok, data: data }; });
      })
      .then(function (res) {
        if (!res.ok || !res.data || !res.data.ok) {
          throw new Error((res.data && res.data.error) || 'Could not join right now. Please try again.');
        }
        if (typeof res.data.total_count === 'number') updateCount(res.data.total_count);
        form.style.display = 'none';
        if (successMsg) successMsg.textContent = res.data.message || "You're on the TuneMind waitlist!";
        if (betaSteps) {
          betaSteps.style.display = res.data.wants_closed_beta ? 'block' : 'none';
        }
        if (successBox) successBox.style.display = 'block';
      })
      .catch(function (err) {
        if (errorBox) {
          errorBox.textContent = err && err.message ? err.message : 'Something went wrong. Please try again.';
          errorBox.style.display = 'block';
        }
      })
      .finally(function () {
        if (submitBtn) {
          submitBtn.disabled = false;
          submitBtn.textContent = originalBtnText;
        }
      });
  });
})();
`;
}

export function renderWaitlistLandingHtml(): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>TuneMind — Your Personal Music Brain for Android</title>
  <meta name="description" content="Track every music app on Android (Spotify, YouTube Music, Boomplay, Audiomack & Phone Audio), stop song burnout with Freshness % and Resting Vault, and explore your deep music journey." />
  <meta property="og:type" content="website" />
  <meta property="og:url" content="https://tunemind.co" />
  <meta property="og:title" content="TuneMind — Your Personal Music Brain for Android" />
  <meta property="og:description" content="Cross-app music tracking, Song Freshness %, Resting Vault, Song Cards, and AI voice companion. Join the Google Play launch waitlist or Android Closed Beta." />
  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:title" content="TuneMind — Your Personal Music Brain for Android" />
  <meta name="twitter:description" content="Stop overplaying your favorite songs. Cross-app music stats across Spotify, YouTube Music, Boomplay, Audiomack & local audio." />
  <style>
    :root {
      --bg: #050811;
      --surface: #0d1322;
      --surface-2: #121a2d;
      --border: rgba(255, 255, 255, 0.1);
      --text: #f8fafc;
      --muted: #94a3b8;
      --accent: #00e5ff;
      --accent-2: #10b981;
      --warm: #ffb000;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
      background:
        radial-gradient(circle at 18% 0%, rgba(0, 229, 255, 0.16), transparent 42%),
        radial-gradient(circle at 85% 15%, rgba(16, 185, 129, 0.14), transparent 38%),
        var(--bg);
      color: var(--text);
      line-height: 1.6;
      -webkit-font-smoothing: antialiased;
    }
    .wrap {
      max-width: 960px;
      margin: 0 auto;
      padding: 28px 20px 72px;
    }
    nav {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 42px;
    }
    .logo {
      font-weight: 800;
      font-size: 19px;
      letter-spacing: -0.02em;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .logo-dot {
      width: 11px;
      height: 11px;
      border-radius: 50%;
      background: linear-gradient(135deg, var(--accent), var(--accent-2));
      box-shadow: 0 0 14px rgba(0, 229, 255, 0.7);
    }
    .nav-pill {
      font-size: 12px;
      font-weight: 700;
      color: var(--accent);
      background: rgba(0, 229, 255, 0.1);
      border: 1px solid rgba(0, 229, 255, 0.25);
      padding: 6px 12px;
      border-radius: 999px;
    }
    .hero {
      display: grid;
      grid-template-columns: 1.15fr 0.85fr;
      gap: 28px;
      align-items: start;
    }
    @media (max-width: 780px) {
      .hero { grid-template-columns: 1fr; }
    }
    .eyebrow {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.12em;
      color: var(--accent-2);
      margin-bottom: 14px;
    }
    h1 {
      margin: 0 0 16px;
      font-size: clamp(34px, 5vw, 50px);
      line-height: 1.08;
      letter-spacing: -0.03em;
    }
    .grad {
      background: linear-gradient(90deg, #ffffff 0%, #7ef5ff 55%, #6ee7b7 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .lead {
      font-size: 17px;
      color: #cbd5e1;
      margin: 0 0 22px;
      max-width: 540px;
    }
    .apps-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 22px;
    }
    .app-chip {
      font-size: 12px;
      font-weight: 600;
      color: #e2e8f0;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--border);
      padding: 5px 11px;
      border-radius: 999px;
    }
    .count-pill {
      display: none;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      font-weight: 600;
      color: var(--accent);
      background: rgba(0, 229, 255, 0.08);
      border: 1px solid rgba(0, 229, 255, 0.22);
      padding: 7px 13px;
      border-radius: 999px;
    }
    .waitlist-card {
      background: linear-gradient(180deg, var(--surface-2) 0%, var(--surface) 100%);
      border: 1px solid rgba(0, 229, 255, 0.22);
      border-radius: 20px;
      padding: 24px;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
    }
    .waitlist-card h2 {
      margin: 0 0 6px;
      font-size: 20px;
      letter-spacing: -0.01em;
    }
    .waitlist-sub {
      margin: 0 0 18px;
      font-size: 13.5px;
      color: var(--muted);
    }
    label.field-label {
      display: block;
      font-size: 12.5px;
      font-weight: 600;
      color: #cbd5e1;
      margin-bottom: 6px;
    }
    input[type="email"], select {
      width: 100%;
      padding: 12px 14px;
      border-radius: 12px;
      border: 1px solid rgba(255, 255, 255, 0.14);
      background: #080d18;
      color: var(--text);
      font-size: 15px;
      margin-bottom: 14px;
      outline: none;
    }
    input[type="email"]:focus, select:focus {
      border-color: var(--accent);
      box-shadow: 0 0 0 3px rgba(0, 229, 255, 0.15);
    }
    .beta-check {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      font-size: 13px;
      color: #cbd5e1;
      background: rgba(16, 185, 129, 0.08);
      border: 1px solid rgba(16, 185, 129, 0.25);
      border-radius: 12px;
      padding: 10px 12px;
      margin-bottom: 16px;
      cursor: pointer;
    }
    .beta-check input {
      margin-top: 3px;
      accent-color: var(--accent-2);
      transform: scale(1.1);
    }
    button.cta {
      width: 100%;
      padding: 13px 16px;
      border: none;
      border-radius: 12px;
      background: linear-gradient(90deg, var(--accent) 0%, var(--accent-2) 100%);
      color: #041019;
      font-weight: 800;
      font-size: 15px;
      cursor: pointer;
      transition: transform 0.12s ease, opacity 0.15s ease;
    }
    button.cta:hover { opacity: 0.94; transform: translateY(-1px); }
    button.cta:disabled { opacity: 0.65; cursor: wait; }
    .fine-print {
      margin: 10px 0 0;
      font-size: 12px;
      color: var(--muted);
      text-align: center;
    }
    .error-box {
      display: none;
      background: rgba(239, 68, 68, 0.14);
      border: 1px solid rgba(239, 68, 68, 0.35);
      color: #fecaca;
      padding: 10px 12px;
      border-radius: 10px;
      font-size: 13px;
      margin-bottom: 12px;
    }
    .success-box {
      display: none;
    }
    .success-title {
      font-size: 18px;
      font-weight: 800;
      color: var(--accent-2);
      margin: 0 0 8px;
    }
    .beta-steps {
      display: none;
      margin-top: 14px;
      padding: 14px;
      border-radius: 12px;
      background: rgba(0, 229, 255, 0.06);
      border: 1px solid rgba(0, 229, 255, 0.22);
      font-size: 13.5px;
    }
    .beta-steps ol {
      margin: 8px 0 0;
      padding-left: 18px;
    }
    .beta-steps li { margin-bottom: 8px; }
    .share-btn {
      margin-top: 14px;
      width: 100%;
      padding: 10px 14px;
      border-radius: 10px;
      border: 1px solid var(--border);
      background: rgba(255, 255, 255, 0.06);
      color: var(--text);
      font-weight: 600;
      font-size: 13.5px;
      cursor: pointer;
    }
    .section-title {
      margin: 56px 0 18px;
      font-size: 22px;
      letter-spacing: -0.02em;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }
    @media (max-width: 680px) {
      .grid { grid-template-columns: 1fr; }
    }
    .feature {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 18px 20px;
    }
    .feature h3 {
      margin: 0 0 6px;
      font-size: 16px;
      color: #f1f5f9;
    }
    .feature p {
      margin: 0;
      font-size: 14px;
      color: var(--muted);
    }
    .compare {
      margin-top: 22px;
      background: linear-gradient(90deg, rgba(0, 229, 255, 0.08), rgba(16, 185, 129, 0.08));
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 16px;
      padding: 18px 20px;
      font-size: 14.5px;
      color: #e2e8f0;
    }
    footer {
      margin-top: 52px;
      padding-top: 20px;
      border-top: 1px solid var(--border);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 14px;
      font-size: 13px;
      color: var(--muted);
    }
    .links {
      display: flex;
      gap: 18px;
      flex-wrap: wrap;
    }
    a {
      color: var(--accent);
      text-decoration: none;
      font-weight: 600;
    }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <div class="wrap">
    <nav>
      <div class="logo"><span class="logo-dot"></span> TuneMind</div>
      <div class="nav-pill">Android · Launching Soon</div>
    </nav>

    <section class="hero">
      <div>
        <div class="eyebrow">Personal Music Intelligence</div>
        <h1><span class="grad">Stop ruining your favorite songs by overplaying them.</span></h1>
        <p class="lead">
          TuneMind tracks your listening across every music app on Android in real time — monitoring <strong>Song Freshness %</strong>, resting burned-out tracks in your <strong>Resting Vault</strong>, and turning your taste into deep Song Cards &amp; smart recommendations.
        </p>
        <div class="apps-row">
          <span class="app-chip">Spotify (Free &amp; Premium)</span>
          <span class="app-chip">YouTube Music</span>
          <span class="app-chip">Boomplay</span>
          <span class="app-chip">Audiomack</span>
          <span class="app-chip">Downloaded Phone Audio</span>
        </div>
        <div id="wl-count" class="count-pill"></div>
      </div>

      <div class="waitlist-card">
        <h2>Get Early Play Store Access</h2>
        <p class="waitlist-sub">Join the launch waitlist for Founding Listener perks (7-day TuneMind Pro trial on day one).</p>

        <div id="wl-error" class="error-box"></div>

        <form id="waitlist-form">
          <label class="field-label" for="wl-email">Email address</label>
          <input id="wl-email" name="email" type="email" required placeholder="you@gmail.com" autocomplete="email" />

          <label class="field-label" for="wl-player">Where do you listen to music most?</label>
          <select id="wl-player" name="preferred_player">
            <option value="Spotify">Spotify</option>
            <option value="YouTube Music">YouTube Music</option>
            <option value="Boomplay">Boomplay</option>
            <option value="Audiomack">Audiomack</option>
            <option value="Phone Audio / Local">Downloaded Phone Audio</option>
            <option value="Multiple">Multiple apps</option>
          </select>

          <label class="beta-check">
            <input id="wl-beta" type="checkbox" />
            <span><strong>Want to try TuneMind today?</strong> Get instant Android Closed Beta access links right after signup.</span>
          </label>

          <button id="wl-submit" class="cta" type="submit">Join the Waitlist</button>
          <p class="fine-print">No spam. Only a launch alert when TuneMind goes live on Google Play.</p>
        </form>

        <div id="wl-success" class="success-box">
          <p class="success-title">You're in!</p>
          <p id="wl-success-msg" style="margin:0;color:#cbd5e1;font-size:14px;"></p>

          <div id="wl-beta-steps" class="beta-steps">
            <strong>Join Android Closed Beta right now:</strong>
            <ol>
              <li>Join the tester Google Group: <a href="${CLOSED_BETA_LINKS.google_group}" target="_blank" rel="noopener">TuneMind Closed Beta Group</a></li>
              <li>Tap <strong>Become a tester</strong>: <a href="${CLOSED_BETA_LINKS.opt_in}" target="_blank" rel="noopener">Google Play Opt-In</a></li>
              <li>Download on Google Play: <a href="${CLOSED_BETA_LINKS.play_store}" target="_blank" rel="noopener">Install TuneMind</a></li>
            </ol>
          </div>

          <button id="wl-copy-share" class="share-btn" type="button">Share tunemind.co</button>
        </div>
      </div>
    </section>

    <h2 class="section-title">What makes TuneMind different</h2>
    <div class="grid">
      <div class="feature">
        <h3>True Cross-App Tracking</h3>
        <p>Play music anywhere — Spotify, YouTube Music, Boomplay, Audiomack, or downloaded MP3s — and watch your unified stats update live.</p>
      </div>
      <div class="feature">
        <h3>Song Freshness % &amp; Resting Vault</h3>
        <p>Detects when you're about to burn out a favorite track and lets you rest it in the Vault until it hits like the first time again.</p>
      </div>
      <div class="feature">
        <h3>Deep Song Cards &amp; Shareable Journey</h3>
        <p>See exact play counts, skips, circadian listening peaks, and export sleek journey cards to share with friends.</p>
      </div>
      <div class="feature">
        <h3>Phone Audio + AI Music Companion</h3>
        <p>Play downloaded songs directly inside TuneMind, plus get smart discovery crates and hands-free voice control.</p>
      </div>
    </div>

    <div class="compare">
      <strong>Beyond passive social widgets:</strong> While apps like Airbuds focus on showing what friends played, TuneMind is your <em>personal music brain</em> — built to track every player on Android, protect your favorite songs from fatigue, and level up your listening journey.
    </div>

    <footer>
      <span>&copy; ${new Date().getFullYear()} TuneMind Labs</span>
      <div class="links">
        <a href="/privacy">Privacy Policy</a>
        <a href="/terms">Terms of Service</a>
        <a href="/cookies">Cookies</a>
        <a href="/delete-account">Delete Account</a>
        <a href="/health">Status</a>
      </div>
    </footer>
  </div>
  <script src="/waitlist.js" defer></script>
</body>
</html>`;
}

export default router;
