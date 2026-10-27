import { Router, Request, Response } from 'express';
import { EventIngestionService } from '../../domain/events/EventIngestionService';

const router = Router();
const ingestionService = new EventIngestionService();

/**
 * POST /api/v1/events
 *
 * Accepts a batch of raw ListeningEvents from the Android client.
 *
 * Request body:
 *   { "events": [ ListeningEvent, ... ] }
 *
 * Response:
 *   200 — { accepted: string[], rejected: { eventId, errors }[] }
 *   400 — { error: string } — malformed request body
 *
 * Phase 2: Validates each event and persists accepted events to SQLite.
 * Duplicate eventIds are silently ignored (idempotent).
 */
router.post('/', async (req: Request, res: Response) => {
  const body = req.body as { events?: unknown };

  if (!body || !Array.isArray(body.events)) {
    res.status(400).json({ error: 'Request body must be { "events": [...] }' });
    return;
  }

  if (body.events.length === 0) {
    res.status(200).json({ accepted: [], rejected: [] });
    return;
  }

  if (body.events.length > 500) {
    res.status(400).json({ error: 'Batch size exceeds maximum of 500 events.' });
    return;
  }

  try {
    const result = await ingestionService.ingestBatch(body.events as Record<string, unknown>[]);
    res.status(200).json(result);
  } catch (err) {
    console.error('[POST /api/v1/events] Unhandled error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

export default router;

// Exported for testing
export { ingestionService };
