import express, { Request, Response, NextFunction } from 'express';
import { requestLogger } from './middleware/requestLogger';
import { securityHeaders } from './middleware/securityHeaders';
import { createRateLimiter } from './middleware/rateLimiter';
import { optionalAuth, resolveEffectiveUserIdentity } from './middleware/authMiddleware';
import eventsRouter from './api/v1/events';
import analyticsRouter from './api/v1/analytics';
import reasoningRouter from './api/v1/reasoning';
import spotifyAuthRouter from './api/v1/spotifyAuth';
import discoveryRouter from './api/v1/discovery';
import musicProfilesRouter from './api/v1/musicProfiles';
import supportRouter from './api/v1/support';
import accountRouter from './api/v1/account';
import legalRouter from './api/v1/legalRoutes';
import { SupabaseIntelligenceStore } from './domain/storage/SupabaseIntelligenceStore';

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

  app.use('/api/v1', apiGlobalLimiter, optionalAuth);

  // ─── Public Legal & Mobile App Version Routes ──────────────────────────────
  app.use('/', legalRouter);

  // ─── API v1 Routes ─────────────────────────────────────────────────────────
  app.use('/api/v1/events', eventsRouter);
  app.use('/api/v1/analytics', analyticsRouter);
  app.use('/api/v1/reasoning', aiComputeLimiter, reasoningRouter);
  app.use('/api/v1/spotify', spotifyAuthRouter);
  app.use('/api/v1/discovery', aiComputeLimiter, discoveryRouter);
  app.use('/api/v1/music', musicProfilesRouter);
  app.use('/api/v1/support', sensitiveOpsLimiter, supportRouter);
  app.use('/api/v1/account', sensitiveOpsLimiter, accountRouter);

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
        intelligence_summary: summary
      });
    } catch (err: any) {
      console.error('[IntelligenceSync] Error:', err);
      res.status(500).json({ error: err?.message || 'Intelligence sync failed' });
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

  app.get('/', (_req: Request, res: Response) => {
    res.json({
      status: 'online',
      service: 'TuneMind Cloud Brain (24/7 Online)',
      websocket_endpoint: '/ws/stt',
      health_endpoint: '/health',
      privacy_endpoint: '/privacy',
      terms_endpoint: '/terms',
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
