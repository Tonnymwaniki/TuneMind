import { Router, Request, Response } from 'express';
import { resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';
import { TrackCardStore } from '../../domain/storage/TrackCardStore';

const router = Router();

/**
 * POST /api/v1/track-cards/sync
 * Permanently upserts canonical Track Cards, appends idempotent verified Play Events
 * (Postgres trigger atomically recomputes `verified_play_count`), and syncs relational
 * user playlists (`TUNEMIND`, `SPOTIFY`, etc.) linked by `track_card_id` FK.
 */
router.post('/sync', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
    const result = await TrackCardStore.syncSnapshot(identity.userId, {
      track_cards: Array.isArray(body.track_cards) ? body.track_cards : [],
      play_events: Array.isArray(body.play_events) ? body.play_events : [],
      playlists: Array.isArray(body.playlists) ? body.playlists : []
    });

    res.json({
      status: 'synced',
      user_id: identity.userId,
      persisted_to_supabase: result.persistedToSupabase,
      upserted_track_cards: result.upsertedTrackCards,
      inserted_play_events: result.insertedPlayEvents,
      upserted_playlists: result.upsertedPlaylists,
      upserted_playlist_items: result.upsertedPlaylistItems,
      verified_play_counts: result.verifiedPlayCountsByKey
    });
  } catch (err: any) {
    console.error('[TrackCardsAPI] POST /sync error:', err);
    res.status(500).json({ error: err?.message || 'Failed to sync relational track cards' });
  }
});

/**
 * GET /api/v1/track-cards/snapshot
 * Returns all canonical Track Cards (with exact Postgres-verified play counts)
 * and relational Playlists with nested Track Card joins.
 */
router.get('/snapshot', async (req: Request, res: Response) => {
  try {
    const identity = resolveEffectiveUserIdentity(
      req,
      typeof req.query.user_id === 'string' ? req.query.user_id : undefined,
      typeof req.query.user_email === 'string' ? req.query.user_email : undefined
    );
    const snapshot = await TrackCardStore.getSnapshot(identity.userId);
    res.json({
      status: 'ok',
      user_id: identity.userId,
      source: snapshot.source,
      track_cards: snapshot.track_cards,
      playlists: snapshot.playlists
    });
  } catch (err: any) {
    console.error('[TrackCardsAPI] GET /snapshot error:', err);
    res.status(500).json({ error: err?.message || 'Failed to fetch track cards snapshot' });
  }
});

export default router;
