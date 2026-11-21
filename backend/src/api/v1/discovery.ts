import { Router, Request, Response } from 'express';
import {
  DiscoveryFeedbackEvent,
  SelfLearningDiscoveryEngine
} from '../../domain/recommendation/SelfLearningDiscoveryEngine';
import { resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';

const router = Router();

/**
 * POST /api/v1/discovery/daily-drop
 * Runs online learning on yesterday's recommendation outcomes + cross-app listening context,
 * then builds a verified 2-per-genre + 2 global wildcard unplayed daily slate.
 */
router.post('/daily-drop', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(
      req,
      body.userId || body.user_id,
      body.userEmail || body.user_email
    );
    const engine = new SelfLearningDiscoveryEngine();
    const toStringArray = (val: unknown, maxLen: number): string[] =>
      Array.isArray(val)
        ? val.map((x) => (typeof x === 'string' ? x.trim() : '')).filter(Boolean).slice(0, maxLen)
        : [];
    const headerSpotifyToken =
      typeof req.headers['x-spotify-access-token'] === 'string'
        ? req.headers['x-spotify-access-token'].trim()
        : undefined;
    const rawSpotifyToken =
      headerSpotifyToken ||
      (typeof body.spotifyAccessToken === 'string' ? body.spotifyAccessToken.trim() : '') ||
      (typeof body.spotify_access_token === 'string' ? body.spotify_access_token.trim() : '');

    const result = await engine.generateDailyForYouDrop({
      userId: req.authUser ? identity.userId : (body.userId || body.user_id),
      userEmail: req.authUser ? identity.userEmail : (body.userEmail || body.user_email),
      dropDate: body.dropDate || body.drop_date,
      forceRefresh: Boolean(body.forceRefresh || body.force_refresh),
      timezone: body.timezone,
      localHour: typeof body.localHour === 'number' ? body.localHour : undefined,
      timeSlot: body.timeSlot || body.time_slot,
      satietyLevel: Number(body.satietyLevel ?? body.satiety_level ?? 38),
      preferredProvider: body.preferredProvider || body.preferred_provider,
      avgVolumeRatio: typeof body.avgVolumeRatio === 'number' ? body.avgVolumeRatio : undefined,
      topArtists: toStringArray(body.topArtists, 40),
      userTasteAnchors: toStringArray(body.userTasteAnchors, 40),
      genreDistribution: body.genreDistribution && typeof body.genreDistribution === 'object'
        ? body.genreDistribution
        : undefined,
      artistGenres: body.artistGenres && typeof body.artistGenres === 'object'
        ? body.artistGenres
        : undefined,
      recentTracks: Array.isArray(body.recentTracks) ? body.recentTracks : [],
      vaultTracks: Array.isArray(body.vaultTracks) ? body.vaultTracks : [],
      excludeTitles: toStringArray(body.excludeTitles, 2000),
      excludeKeys: toStringArray(body.excludeKeys || body.exclude_keys, 2500),
      excludedSpotifyUris: toStringArray(body.excludedSpotifyUris || body.excluded_spotify_uris, 1500),
      excludedTrackIds: toStringArray(body.excludedTrackIds || body.excluded_track_ids, 1500),
      spotifyAccessToken: rawSpotifyToken.length >= 20 ? rawSpotifyToken : undefined,
      yesterdayOutcomes: Array.isArray(body.yesterdayOutcomes) ? body.yesterdayOutcomes : [],
      playedFeedback: toStringArray(body.playedFeedback, 50),
      dismissedFeedback: toStringArray(body.dismissedFeedback, 50),
      supabaseUrl: body.supabase_url,
      supabaseKey: body.supabase_anon_key
    });
    res.json(result);
  } catch (error) {
    console.error('[discovery.ts] Error generating daily drop:', error);
    res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

/**
 * POST /api/v1/discovery/feedback
 * Records real-time recommendation feedback (PLAYED, COMPLETED, SAVED, SKIPPED, DISMISSED, IGNORED)
 * along with listen duration and volume ratio so the model trains immediately.
 */
router.post('/feedback', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const event: DiscoveryFeedbackEvent = {
      userId: req.authUser?.id || body.userId || body.user_id,
      trackId: body.trackId || body.track_id,
      spotifyUri: body.spotifyUri || body.spotify_uri,
      title: String(body.title || ''),
      artist: String(body.artist || ''),
      genre: body.genre,
      subTier: body.subTier,
      outcome: (body.outcome || 'PLAYED') as DiscoveryFeedbackEvent['outcome'],
      listenedMs: Number(body.listenedMs || 0),
      durationMs: Number(body.durationMs || 0),
      volumeRatio: typeof body.volumeRatio === 'number' ? body.volumeRatio : undefined,
      timeSlot: body.timeSlot,
      dropDate: body.dropDate
    };

    if (!event.title.trim()) {
      res.status(400).json({ status: 'error', message: 'title is required' });
      return;
    }

    const engine = new SelfLearningDiscoveryEngine();
    const summary = await engine.recordFeedback(event, {
      supabaseUrl: body.supabase_url,
      supabaseKey: body.supabase_anon_key
    });

    res.json({
      status: 'ok',
      learningSummary: summary
    });
  } catch (error) {
    console.error('[discovery.ts] Error recording discovery feedback:', error);
    res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

/**
 * GET /api/v1/discovery/insights
 * Returns the user's learned genre weights, time-of-day volume profile, and daily match score.
 */
router.get('/insights', async (req: Request, res: Response) => {
  try {
    const userId = req.authUser?.id || String(req.query.userId || req.query.user_id || '');
    const engine = new SelfLearningDiscoveryEngine();
    const insights = await engine.getInsights(userId);
    res.json({
      status: 'ok',
      ...insights
    });
  } catch (error) {
    console.error('[discovery.ts] Error fetching discovery insights:', error);
    res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

export default router;
