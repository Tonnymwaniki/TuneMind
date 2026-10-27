import { EventStore } from './EventStore';
import { validateEvent, ListeningEvent } from './EventValidator';

/**
 * EventIngestionService — receives validated events and persists them.
 *
 * Phase 2: Validates each event via EventValidator, stores accepted events
 * in the local SQLite EventStore, and rejects malformed events.
 *
 * Responsibility boundaries:
 * - Validates each event via EventValidator
 * - Sets receivedAt server-side timestamp per event
 * - Writes raw events to storage
 * - Does NOT compute analytics — that is AnalyticsService's role
 * - Does NOT call the AI reasoning layer
 */
export class EventIngestionService {
  private store: EventStore;

  constructor(store?: EventStore) {
    this.store = store ?? new EventStore();
  }

  /**
   * Ingest a batch of raw ListeningEvents from the Android client.
   *
   * @param events Array of events from a single batch POST request.
   * @returns Summary of accepted and rejected events.
   */
  async ingestBatch(events: Record<string, unknown>[]): Promise<IngestResult> {
    const accepted: string[] = [];
    const rejected: RejectedEvent[] = [];

    for (const event of events) {
      const validation = validateEvent(event);

      if (!validation.valid) {
        const eventId = typeof event.eventId === 'string' ? event.eventId : '(unknown)';
        rejected.push({ eventId, errors: validation.errors });
        continue;
      }

      const typed = event as unknown as ListeningEvent;
      const receivedAt = new Date().toISOString();
      const payloadJson = JSON.stringify(event);

      try {
        const inserted = this.store.insert(
          typed.eventId,
          typed.userId,
          typed.sessionId,
          typed.deviceId,
          typed.schemaVersion,
          typed.eventType,
          typed.occurredAt,
          receivedAt,
          typed.mediaSource,
          payloadJson,
        );

        // Every valid event is acknowledged by eventId, whether newly inserted
        // or already stored (idempotent duplicate). This lets the Android client
        // mark the event as synced regardless of which server instance saw it first.
        accepted.push(typed.eventId);
      } catch (err) {
        console.error(`[EventIngestionService] Persistence failure for event ${typed.eventId}:`, err);
        rejected.push({ eventId: typed.eventId, errors: ['Internal persistence error'] });
      }
    }

    return { accepted, rejected };
  }

  /** Expose the store for testing / debug inspection. */
  getStore(): EventStore {
    return this.store;
  }
}

export interface RejectedEvent {
  eventId: string;
  errors: string[];
}

export interface IngestResult {
  accepted: string[];
  rejected: RejectedEvent[];
}
