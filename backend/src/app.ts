import express, { Request, Response, NextFunction } from 'express';
import { requestLogger } from './middleware/requestLogger';
import { securityHeaders } from './middleware/securityHeaders';
import { createRateLimiter } from './middleware/rateLimiter';
import { optionalAuth, resolveEffectiveUserIdentity } from './middleware/authMiddleware';
import eventsRouter from './api/v1/events';
import analyticsRouter from './api/v1/analytics';
import reasoningRouter from './api/v1/reasoning';
import discoveryRouter from './api/v1/discovery';
import musicProfilesRouter from './api/v1/musicProfiles';
import supportRouter from './api/v1/support';
import accountRouter from './api/v1/account';
import spotifyRouter from './api/v1/spotify';
import trackCardsRouter from './api/v1/trackCards';
import billingRouter from './api/v1/billing';
import legalRouter from './api/v1/legalRoutes';
import { SupabaseIntelligenceStore } from './domain/storage/SupabaseIntelligenceStore';
import { RestingVaultEngine } from './domain/vault/RestingVaultEngine';

export function createApp(): express.Express {
  const app = express();

  app.disable('x-powered-by');
  app.set('trust proxy', 1);

  // ─── Core Security & Parsing Middleware ────────────────────────────────────
  app.use(securityHeaders);
  app.use(express.json({ limit: '5mb' }));
  app.use(requestLogger);

  // ─── Tiered Rate Limiters ──────────────────────────────────────────────────
  const apiGlobalLimiter = createRateLimiter({
    windowMs: 60_000,
    max: 120,
    keyPrefix: 'api-global',
    message: 'API rate limit exceeded. Please wait a moment and try again.'
  });

  const aiComputeLimiter = createRateLimiter({
    windowMs: 60_000,
    max: 35,
    keyPrefix: 'api-ai',
    message: 'AI reasoning rate limit reached. Please try again in a minute.'
  });

  const sensitiveOpsLimiter = createRateLimiter({
    windowMs: 15 * 60_000,
    max: 20,
    keyPrefix: 'api-sensitive',
    message: 'Too many support or account requests. Please wait a few minutes.'
  });

  app.use('/api/v1', optionalAuth, apiGlobalLimiter);

  // ─── Public Legal & Mobile App Version Routes ──────────────────────────────
  app.use('/', legalRouter);

  // ─── API v1 Routes ─────────────────────────────────────────────────────────
  app.use('/api/v1/events', eventsRouter);
  app.use('/api/v1/analytics', analyticsRouter);
  app.use('/api/v1/reasoning', aiComputeLimiter, reasoningRouter);
  app.use('/api/v1/discovery', aiComputeLimiter, discoveryRouter);
  app.use('/api/v1/music', musicProfilesRouter);
  app.use('/api/v1/support', sensitiveOpsLimiter, supportRouter);
  app.use('/api/v1/account', sensitiveOpsLimiter, accountRouter);
  app.use('/api/v1/spotify', spotifyRouter);
  app.use('/api/v1/track-cards', trackCardsRouter);
  app.use('/api/v1/billing', billingRouter);

  // ─── Live Supabase Intelligence Sync & Long-Term Growth Endpoints ──────────
  app.post('/api/v1/intelligence/sync', async (req: Request, res: Response) => {
    try {
      const body = req.body || {};
      const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
      const userId = identity.userId;
      const override = {
        supabaseUrl: body.supabase_url,
        supabaseKey: body.supabase_anon_key
      };

      const tracks = Array.isArray(body.tracks) ? body.tracks : [];
      const syncRes = await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(userId, tracks, {
        hearingTelemetry: body.hearing_telemetry,
        vaultTracks: body.vault_tracks,
        restoredTrackTimestamps: body.restored_track_timestamps,
        override
      });

      if (body.intervention && typeof body.intervention === 'object') {
        await SupabaseIntelligenceStore.recordAgentIntervention(
          {
            user_id: userId,
            trigger_transcript: body.intervention.trigger_transcript || 'app_event',
            computed_satiety: Number(body.intervention.computed_satiety ?? 24),
            agent_tts_response: body.intervention.agent_tts_response || '',
            os_intent: body.intervention.os_intent || body.intervention.action || 'NONE',
            target_search_query: body.intervention.target_search_query || '',
            action: body.intervention.action,
            track_id: body.intervention.track_id || body.intervention.trackId,
            track_title: body.intervention.track_title,
            track_artist: body.intervention.track_artist,
            spotify_uri: body.intervention.spotify_uri || body.intervention.spotifyUri,
            circadian_daypart: body.intervention.circadian_daypart
          },
          override
        );
      }

      const summary = await SupabaseIntelligenceStore.fetchUserLongTermMemory(userId, override);
      res.json({
        status: 'synced',
        user_id: userId,
        synced_tracks_count: syncRes.syncedTracksCount,
        live_supabase_stored: syncRes.liveSupabaseStored,
        reconciled_vault_count: syncRes.reconciledVaultCount,
        graduated_count: syncRes.graduatedCount,
        vault_tracks: summary.vaultTracks || [],
        graduated_vault_tracks: summary.graduatedVaultTracks || [],
        intelligence_summary: summary
      });
    } catch (err: any) {
      console.error('[IntelligenceSync] Error:', err);
      res.status(500).json({ error: err?.message || 'Intelligence sync failed' });
    }
  });

  app.post('/api/v1/intelligence/vault/reconcile', async (req: Request, res: Response) => {
    try {
      const body = req.body || {};
      const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
      const userId = identity.userId;
      const sensitivity = Number(body.repetition_sensitivity ?? 0.75);
      const rawVault = Array.isArray(body.vault_tracks) ? body.vault_tracks : [];
      const restoredTimestamps =
        body.restored_track_timestamps && typeof body.restored_track_timestamps === 'object'
          ? body.restored_track_timestamps
          : undefined;

      const reconciled = RestingVaultEngine.reconcileVaultTracks(rawVault, Date.now(), restoredTimestamps);
      const syncRes = await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(
        userId,
        Array.isArray(body.tracks) ? body.tracks : [],
        {
          vaultTracks: reconciled.activeVault,
          restoredTrackTimestamps: restoredTimestamps,
          override: {
            supabaseUrl: body.supabase_url,
            supabaseKey: body.supabase_anon_key
          }
        }
      );

      res.json({
        status: 'reconciled',
        user_id: userId,
        entry_threshold_satiety: RestingVaultEngine.computeEntryThreshold(sensitivity),
        active_vault: reconciled.activeVault,
        graduated_tracks: reconciled.graduatedTracks,
        live_supabase_stored: syncRes.liveSupabaseStored
      });
    } catch (err: any) {
      console.error('[VaultReconcile] Error:', err);
      res.status(500).json({ error: err?.message || 'Vault reconcile failed' });
    }
  });

  app.post('/api/v1/intelligence/fresh/rank', async (req: Request, res: Response) => {
    try {
      const body = req.body || {};
      const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
      const sensitivity = Number(body.repetition_sensitivity ?? 0.75);
      const rawCandidates = Array.isArray(body.candidates) ? body.candidates : [];
      const activeVault = Array.isArray(body.vault_tracks) ? body.vault_tracks : [];
      const vaultKeys = new Set(
        activeVault
          .filter((v: any) => v && v.title)
          .map((v: any) => `${String(v.title).trim().toLowerCase()}::${String(v.artist || '').trim().toLowerCase()}`)
      );

      const nowMs = Date.now();
      const ranked = rawCandidates
        .filter((c: any) => c && c.title)
        .filter((c: any) => {
          const k = `${String(c.title).trim().toLowerCase()}::${String(c.artist || '').trim().toLowerCase()}`;
          return !vaultKeys.has(k);
        })
        .map((c: any) => {
          const playTimestamps = Array.isArray(c.playTimestampsMs)
            ? c.playTimestampsMs.map(Number).filter(Number.isFinite)
            : [];
          const fatigue = RestingVaultEngine.evaluateTrackFatigue({
            playTimestampsMs: playTimestamps,
            sensitivity,
            lastRestoredAtMs: Number(c.lastRestoredAtMs || 0),
            nowMs
          });
          const readiness = RestingVaultEngine.computeFreshReadiness({
            baseFreshnessPercent: fatigue.freshnessPercent,
            playCount: Number(c.playCount ?? playTimestamps.length ?? 0),
            lastPlayedAtMs: Number(c.lastPlayedAtMs || playTimestamps[0] || 0),
            isLikedOrSaved: Boolean(c.isLikedOrSaved),
            isGraduatedComeback: Boolean(c.isGraduatedComeback),
            restedDays: Number(c.restedDays || 0),
            sourceLabel: c.sourceLabel,
            nowMs
          });
          return {
            title: String(c.title).trim(),
            artist: String(c.artist || 'Unknown Artist').trim(),
            trackId: c.trackId,
            spotifyUri: c.spotifyUri,
            playCount: Number(c.playCount ?? playTimestamps.length ?? 0),
            shouldAutoQuarantine: fatigue.shouldAutoQuarantine,
            ...readiness
          };
        })
        .filter((r: any) => !r.shouldAutoQuarantine)
        .sort((a: any, b: any) => b.readinessScore - a.readinessScore);

      res.json({
        status: 'ok',
        user_id: identity.userId,
        fresh_count: ranked.length,
        fresh_tracks: ranked
      });
    } catch (err: any) {
      console.error('[FreshRank] Error:', err);
      res.status(500).json({ error: err?.message || 'Fresh ranking failed' });
    }
  });

  app.get('/api/v1/intelligence/growth', async (req: Request, res: Response) => {
    try {
      const identity = resolveEffectiveUserIdentity(
        req,
        String(req.query.user_id || ''),
        String(req.query.user_email || '')
      );
      const summary = await SupabaseIntelligenceStore.fetchUserLongTermMemory(identity.userId);
      res.json(summary);
    } catch (err: any) {
      res.status(500).json({ error: err?.message || 'Failed to load intelligence growth summary' });
    }
  });

  app.get('/', (req: Request, res: Response) => {
    const accept = String(req.headers.accept || '').toLowerCase();
    if (accept.includes('text/html')) {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.status(200).send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>TuneMind — AI Music Companion & Listening Intelligence</title>
  <style>
    body { margin: 0; padding: 48px 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif; background: #07090e; color: #f8fafc; line-height: 1.6; }
    main { max-width: 720px; margin: 0 auto; }
    .badge { color: #00f0ff; font-size: 12px; font-weight: 700; letter-spacing: 0.14em; text-transform: uppercase; }
    h1 { font-size: 36px; margin: 8px 0 12px; }
    p { color: #cbd5e1; font-size: 16px; }
    .card { background: #101521; border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; padding: 20px; margin: 22px 0; }
    .links { display: flex; gap: 18px; flex-wrap: wrap; margin-top: 24px; }
    a { color: #00f0ff; text-decoration: none; font-weight: 600; }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <main>
    <div class="badge">TuneMind</div>
    <h1>TuneMind</h1>
    <p>TuneMind is your intelligent Android voice music companion — tracking cross-app listening satiety, guarding song freshness with the Resting Vault, monitoring safe ear headroom, and curating TuneMind playlists across your favorite music apps.</p>
    <div class="card">
      <strong>Core Capabilities</strong>
      <p>Real-time conversational music AI, local TuneMind playlists played inside TuneMind or across your favorite Android music apps (YouTube Music, Boomplay, Spotify, Audiomack, local audio), Kids Safe filtering, acoustic headroom insights, and Taste Genome analytics.</p>
    </div>
    <div class="links">
      <a href="/privacy">Privacy Policy</a>
      <a href="/terms">Terms of Service</a>
      <a href="/delete-account">Account & Data Deletion</a>
      <a href="/health">Service Status</a>
    </div>
  </main>
</body>
</html>`);
      return;
    }
    res.json({
      status: 'online',
      service: 'TuneMind Cloud Brain (24/7 Online)',
      websocket_endpoint: '/ws/stt',
      health_endpoint: '/health',
      privacy_endpoint: '/privacy',
      terms_endpoint: '/terms',
      delete_account_endpoint: '/delete-account',
      version_endpoint: '/api/v1/app/version',
      timestamp: new Date().toISOString()
    });
  });

  app.get('/health', (_req: Request, res: Response) => {
    res.json({
      status: 'ok',
      phase: 2,
      service: 'TuneMind Fullstack Intelligence Platform',
      timestamp: new Date().toISOString()
    });
  });

  // Malformed JSON / Payload Too Large error handler
  app.use((err: any, _req: Request, res: Response, next: NextFunction) => {
    if (err && err.type === 'entity.too.large') {
      res.status(413).json({ error: 'Payload too large' });
      return;
    }
    if (err instanceof SyntaxError && 'body' in err) {
      res.status(400).json({ error: 'Invalid JSON request body' });
      return;
    }
    next(err);
  });

  // ─── 404 handler for HTTP routes ───────────────────────────────────────────
  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: 'Not found' });
  });

  return app;
}

const app = createApp();
export default app;
