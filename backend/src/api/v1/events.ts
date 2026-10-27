import { Router, Request, Response } from 'express';
import { EventIngestionService } from '../../domain/events/EventIngestionService';

const router = Router();
const ingestionService = new EventIngestionService();

/**
 * POST /api/v1/events
 *
 * Accepts batches or single raw ListeningEvents from the phone/companion client.
 *
 * Accepted payloads:
 *   - { "events": [ ListeningEvent, ... ] }
 *   - [ ListeningEvent, ... ]
 *   - { eventId: "...", mediaSource: "...", ... } (single event)
 */
router.post('/', async (req: Request, res: Response) => {
  let eventBatch: Record<string, unknown>[] = [];

  const body = req.body as any;

  if (!body) {
    res.status(400).json({ error: 'Request body cannot be empty' });
    return;
  }

  if (Array.isArray(body)) {
    eventBatch = body;
  } else if (Array.isArray(body.events)) {
    eventBatch = body.events;
  } else if (typeof body === 'object' && (body.mediaSource || body.eventType || body.track)) {
    // Single event payload
    eventBatch = [body];
  } else {
    res.status(400).json({ error: 'Request body must be { "events": [...] } or a valid event object' });
    return;
  }

  if (eventBatch.length === 0) {
    res.status(200).json({ accepted: [], rejected: [] });
    return;
  }

  if (eventBatch.length > 500) {
    res.status(400).json({ error: 'Batch size exceeds maximum of 500 events.' });
    return;
  }

  try {
    const result = await ingestionService.ingestBatch(eventBatch);
    console.log(`[POST /api/v1/events] Ingested ${result.accepted.length} event(s), rejected: ${result.rejected.length}`);
    res.status(200).json(result);
  } catch (err) {
    console.error('[POST /api/v1/events] Unhandled error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

/**
 * GET /api/v1/events
 *
 * Returns stored raw events (most recent first) for live stream visualization and debugging.
 */
router.get('/', (req: Request, res: Response) => {
  const store = ingestionService.getStore();
  const allEvents = store.listAll();
  const limit = parseInt(req.query['limit'] as string, 10) || 100;
  const userId = req.query['userId'] as string | undefined;

  let filtered = allEvents;
  if (userId && userId !== 'all') {
    filtered = filtered.filter(e => e.user_id === userId);
  }

  // Reverse so newest events appear first
  const reversed = [...filtered].reverse().slice(0, limit);

  res.json({
    total: filtered.length,
    returned: reversed.length,
    events: reversed
  });
});

/**
 * DELETE /api/v1/events
 *
 * Clears stored events.
 */
router.delete('/', (_req: Request, res: Response) => {
  const store = ingestionService.getStore();
  store.clear();
  res.json({ status: 'ok', message: 'All stored telemetry events have been purged.' });
});

export default router;
export { ingestionService };
