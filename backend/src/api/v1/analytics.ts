import { Router, Request, Response } from 'express';
import { AnalyticsService } from '../../domain/analytics/AnalyticsService';
import { EpisodeReconstructor } from '../../domain/reconstruction/EpisodeReconstructor';
import { ListeningTimeline } from '../../domain/reconstruction/ListeningTimeline';

const router = Router();
const analyticsService = new AnalyticsService();
const episodeReconstructor = new EpisodeReconstructor();
const listeningTimeline = new ListeningTimeline(episodeReconstructor);

/**
 * GET /api/v1/analytics/summary
 *
 * Returns a read-only summary of stored raw events for a given userId.
 *
 * Query parameters:
 *   userId — optional. Defaults to "tunemind-dev" for local development.
 *            Pass "all" to include every stored event regardless of userId.
 *
 * Guard:
 *   Available only when NODE_ENV === 'development'. In any other environment
 *   the endpoint returns 403 without executing analytics queries.
 *
 * Development limitation:
 *   The endpoint does NOT authenticate. userId is a query filter, not a
 *   security boundary. In production, this endpoint must require authentication
 *   scoped to the caller's own userId. The NODE_ENV guard is a defence-in-depth
 *   measure, not a replacement for authentication.
 */
router.get('/summary', (req: Request, res: Response) => {
  if (process.env['NODE_ENV'] !== 'development') {
    res.status(403).json({ error: 'Analytics endpoints are available only in development mode.' });
    return;
  }

  const userId = (req.query['userId'] as string | undefined) ?? 'tunemind-dev';
  const effectiveUserId = userId === 'all' ? null : userId;

  try {
    const summary = analyticsService.getSummary(effectiveUserId);
    res.json(summary);
  } catch (err) {
    console.error('[GET /api/v1/analytics/summary] Error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

/**
 * GET /api/v1/analytics/sessions
 *
 * Returns a per-observer-session breakdown of stored events.
 *
 * Query parameters:
 *   userId — optional. Defaults to "tunemind-dev" for local development.
 *            Pass "all" to include every stored event regardless of userId.
 *
 * Guard:
 *   Available only when NODE_ENV === 'development'.
 *
 * Development limitation:
 *   The endpoint does NOT authenticate. userId is a query filter, not a
 *   security boundary. The NODE_ENV guard is a defence-in-depth measure.
 */
router.get('/sessions', (req: Request, res: Response) => {
  if (process.env['NODE_ENV'] !== 'development') {
    res.status(403).json({ error: 'Analytics endpoints are available only in development mode.' });
    return;
  }

  const userId = (req.query['userId'] as string | undefined) ?? 'tunemind-dev';
  const effectiveUserId = userId === 'all' ? null : userId;

  try {
    const sessions = analyticsService.getSessions(effectiveUserId);
    res.json(sessions);
  } catch (err) {
    console.error('[GET /api/v1/analytics/sessions] Error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

/**
 * GET /api/v1/analytics/episodes
 *
 * Returns reconstructed listening episodes from stored events.
 *
 * Query parameters:
 *   userId — optional. Defaults to "tunemind-dev" for local development.
 *            Pass "all" to include every stored event regardless of userId.
 *
 * Guard:
 *   Available only when NODE_ENV === 'development'.
 *
 * Limitations:
 *   This is a development-only reconstruction. It uses wall-clock timestamps
 *   (occurredAt) for ordering, not the monotonic elapsed-realtime clock.
 *   Active playback duration is NOT computed — only wall-clock span is reported.
 *   Episode boundaries are inferred; they do not confirm user attention.
 */
router.get('/episodes', (req: Request, res: Response) => {
  if (process.env['NODE_ENV'] !== 'development') {
    res.status(403).json({ error: 'Analytics endpoints are available only in development mode.' });
    return;
  }

  const userId = (req.query['userId'] as string | undefined) ?? 'tunemind-dev';
  const effectiveUserId = userId === 'all' ? null : userId;

  try {
    const reconstruction = episodeReconstructor.reconstruct(effectiveUserId);
    res.json(reconstruction);
  } catch (err) {
    console.error('[GET /api/v1/analytics/episodes] Error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

/**
 * GET /api/v1/analytics/timeline
 *
 * Returns a chronological listening timeline with internal raw event IDs stripped.
 *
 * Query parameters:
 *   userId — optional. Defaults to "tunemind-dev" for local development.
 *            Pass "all" to include every stored event regardless of userId.
 *
 * Guard:
 *   Available only when NODE_ENV === 'development'.
 *
 * This endpoint presents a cleaned-up view of the same reconstruction data
 * returned by /episodes. Internal raw event identifiers are removed.
 * All other semantics, limitations, and uncertainty disclosures are identical.
 */
router.get('/timeline', (req: Request, res: Response) => {
  if (process.env['NODE_ENV'] !== 'development') {
    res.status(403).json({ error: 'Analytics endpoints are available only in development mode.' });
    return;
  }

  const userId = (req.query['userId'] as string | undefined) ?? 'tunemind-dev';
  const effectiveUserId = userId === 'all' ? null : userId;

  try {
    const timeline = listeningTimeline.build(effectiveUserId);
    res.json(timeline);
  } catch (err) {
    console.error('[GET /api/v1/analytics/timeline] Error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

export default router;
