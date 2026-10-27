/**
 * EventStore — SQLite-backed persistence for accepted ListeningEvents.
 *
 * SQLite is a local-development choice. It provides:
 *  - Durability: synchronous writes with WAL mode; fsync on commit.
 *  - Idempotency: UNIQUE constraint on eventId prevents duplicate insertion.
 *  - Zero-config: no external process, no Docker, no cloud dependency.
 *
 * Limitations (must be documented):
 *  - Single-process: not suitable for horizontal scaling.
 *  - File-based: data lives on the local filesystem.
 *  - Production should replace this with Firestore (as designed in the architecture).
 */

import Database from 'better-sqlite3';
import path from 'path';

const DB_DIR = process.env['TUNEMIND_DB_DIR'] ?? '.';
const DB_FILE = process.env['TUNEMIND_DB_FILE'] ?? 'tunemind.db';

export interface StoredEvent {
  event_id: string;
  user_id: string;
  session_id: string;
  device_id: string;
  schema_version: string;
  event_type: string;
  occurred_at: string;
  received_at: string;
  media_source: string;
  payload_json: string;
  ingested_at: string;
}

export class EventStore {
  private db: Database.Database;
  private readonly dbPath: string;

  constructor(dbPath?: string) {
    this.dbPath = dbPath ?? path.join(DB_DIR, DB_FILE);
    this.db = new Database(this.dbPath);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('synchronous = FULL');
    this.migrate();
  }

  private migrate(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS raw_events (
        event_id TEXT PRIMARY KEY NOT NULL,
        user_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        device_id TEXT NOT NULL,
        schema_version TEXT NOT NULL,
        event_type TEXT NOT NULL,
        occurred_at TEXT NOT NULL,
        received_at TEXT NOT NULL,
        media_source TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        ingested_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      )
    `);
  }

  /**
   * Insert an event. Returns true if the event was inserted, false if a duplicate eventId
   * already exists (idempotent — the stored record is not overwritten).
   * Throws on disk I/O or constraint errors other than the eventId uniqueness violation.
   */
  insert(
    eventId: string,
    userId: string,
    sessionId: string,
    deviceId: string,
    schemaVersion: string,
    eventType: string,
    occurredAt: string,
    receivedAt: string,
    mediaSource: string,
    payloadJson: string,
  ): boolean {
    try {
      const stmt = this.db.prepare(`
        INSERT INTO raw_events
          (event_id, user_id, session_id, device_id, schema_version, event_type, occurred_at, received_at, media_source, payload_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);
      const result = stmt.run(eventId, userId, sessionId, deviceId, schemaVersion, eventType, occurredAt, receivedAt, mediaSource, payloadJson);
      return result.changes === 1;
    } catch (err: unknown) {
      // SQLITE_CONSTRAINT_PRIMARYKEY — duplicate eventId; treat as idempotent
      if (err instanceof Error && 'code' in err && (err as { code: string }).code === 'SQLITE_CONSTRAINT_PRIMARYKEY') {
        return false;
      }
      throw err;
    }
  }

  /** Return all stored events (for testing / debug). */
  listAll(): StoredEvent[] {
    return this.db.prepare('SELECT * FROM raw_events ORDER BY ingested_at ASC').all() as StoredEvent[];
  }

  /** Return the count of stored events. */
  count(): number {
    const row = this.db.prepare('SELECT COUNT(*) AS cnt FROM raw_events').get() as { cnt: number };
    return row.cnt;
  }

  /**
   * Execute an arbitrary SELECT query with parameters and return a single row.
   * Exposed for analytics queries that need aggregation.
   */
  queryRow<T>(sql: string, params: unknown[] = []): T | undefined {
    const stmt = this.db.prepare(sql);
    return stmt.get(...params) as T | undefined;
  }

  /**
   * Execute an arbitrary SELECT query with parameters and return all matching rows.
   * Exposed for analytics queries that need aggregation.
   */
  queryAll<T>(sql: string, params: unknown[] = []): T[] {
    const stmt = this.db.prepare(sql);
    return stmt.all(...params) as T[];
  }

  /** Delete all stored events (for testing cleanup). */
  clear(): void {
    this.db.exec('DELETE FROM raw_events');
  }

  getDbPath(): string {
    return this.dbPath;
  }

  close(): void {
    this.db.close();
  }
}
