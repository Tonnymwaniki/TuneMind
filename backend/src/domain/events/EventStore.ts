/**
 * EventStore — Persistent In-memory + File Storage for accepted ListeningEvents.
 *
 * Implements the full EventStore contract without requiring native C++ SQLite binaries.
 * Writes durably to disk so that real music played on the phone is saved across server restarts.
 * Preserves idempotency, event ordering, and development analytics queries.
 */

import fs from 'fs';
import path from 'path';
import { coerceNonNegativePlayCount, resolveCanonicalTrackIdentity } from '../identity/trackIdentity';

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

interface StoreBucket {
  events: StoredEvent[];
  eventIdSet: Set<string>;
  initialized: boolean;
}

const DATA_DIR = path.resolve(process.cwd(), 'data');
const DATA_FILE = path.join(DATA_DIR, 'tunemind_events.json');

export class EventStore {
  private static stores = new Map<string, StoreBucket>();
  private readonly dbPath: string;
  private readonly bucket: StoreBucket;

  constructor(dbPath?: string) {
    this.dbPath = path.resolve(dbPath ?? DATA_FILE);
    let existing = EventStore.stores.get(this.dbPath);
    if (!existing) {
      existing = {
        events: [],
        eventIdSet: new Set<string>(),
        initialized: false
      };
      EventStore.stores.set(this.dbPath, existing);
    }
    this.bucket = existing;
    if (!this.bucket.initialized) {
      this.bucket.initialized = true;
      this.initStorage();
    }
  }

  private static isLegacySeedEvent(eventId: string): boolean {
    return (
      eventId.startsWith('evt-play-') ||
      eventId.startsWith('evt-meta-') ||
      eventId.startsWith('evt-sc-') ||
      eventId.startsWith('evt-stop-')
    );
  }

  private initStorage(): void {
    try {
      const dir = path.dirname(this.dbPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      if (fs.existsSync(this.dbPath)) {
        const raw = fs.readFileSync(this.dbPath, 'utf-8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          const cleansed = parsed.filter((e: StoredEvent) => {
            if (!e || EventStore.isLegacySeedEvent(e.event_id || '')) return false;
            const p = e.payload_json || '';
            return (
              !p.includes('The Neon Hearts') &&
              !p.includes('Solar Pulse') &&
              !p.includes('Vektor Drift') &&
              !p.includes('Luna Rivers') &&
              !p.includes('Komorebi')
            );
          });

          if (cleansed.length !== parsed.length) {
            console.log(
              `[EventStore] Purged ${parsed.length - cleansed.length} legacy seeded events from ${this.dbPath}.`
            );
          }

          this.bucket.events = cleansed;
          this.bucket.eventIdSet = new Set(cleansed.map(e => e.event_id));
          if (cleansed.length !== parsed.length) {
            this.persistToDisk();
          }
          if (cleansed.length > 0) {
            console.log(`[EventStore] Restored ${cleansed.length} persistent events from ${this.dbPath}`);
          }
          return;
        }
      }
    } catch (err) {
      console.warn('[EventStore] Could not load persisted data:', err);
    }
  }

  private persistToDisk(): void {
    try {
      const dir = path.dirname(this.dbPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(this.dbPath, JSON.stringify(this.bucket.events, null, 2), 'utf-8');
    } catch (err) {
      console.error('[EventStore] Error persisting events to disk:', err);
    }
  }

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
    payloadJson: string
  ): boolean {
    if (this.bucket.eventIdSet.has(eventId)) {
      return false; // Idempotent duplicate
    }

    const event: StoredEvent = {
      event_id: eventId,
      user_id: userId,
      session_id: sessionId,
      device_id: deviceId,
      schema_version: schemaVersion,
      event_type: eventType,
      occurred_at: occurredAt,
      received_at: receivedAt,
      media_source: mediaSource,
      payload_json: payloadJson,
      ingested_at: new Date().toISOString()
    };

    this.bucket.eventIdSet.add(eventId);
    this.bucket.events.push(event);
    this.persistToDisk();
    return true;
  }

  listAll(): StoredEvent[] {
    return [...this.bucket.events].sort((a, b) => a.ingested_at.localeCompare(b.ingested_at));
  }

  private static recentAgentTracks: string[] = [];

  recordAgentTrackAction(title: string): void {
    const clean = (title || '').trim().toLowerCase();
    if (!clean) return;
    EventStore.recentAgentTracks = [
      clean,
      ...EventStore.recentAgentTracks.filter(t => t !== clean)
    ].slice(0, 25);
  }

  getRecentAgentTracks(): string[] {
    return [...EventStore.recentAgentTracks];
  }

  syncClientTracks(
    tracks: Array<{
      title: string;
      artist: string;
      source?: string;
      trackId?: string;
      spotifyUri?: string;
      playCount?: number;
    }>,
    userId: string = 'tunemind-default-user'
  ): void {
    const cleanUserId = (userId || '').trim() || 'tunemind-default-user';
    const userSlug = cleanUserId.toLowerCase().replace(/[^a-z0-9]/g, '_').slice(0, 32) || 'default';
    let changed = 0;
    for (const t of tracks) {
      if (!t.title || t.title === 'Standing By' || t.title === 'Active Session' || t.title === 'Deep Flow State') continue;
      const cleanTitle = t.title.trim();
      const cleanArtist = (t.artist || 'Unknown Artist').trim();
      const identity = resolveCanonicalTrackIdentity({
        trackId: t.trackId,
        spotifyUri: t.spotifyUri,
        title: cleanTitle,
        artist: cleanArtist
      });
      const playCount = coerceNonNegativePlayCount(t.playCount);
      const safeKey = `${cleanTitle.toLowerCase()}-${cleanArtist.toLowerCase()}`.replace(/[^a-z0-9-]/g, '_');
      const eventId = `client-sync-${userSlug}-${safeKey}`;
      const now = new Date().toISOString();
      const payloadJson = JSON.stringify({
        track: {
          trackId: identity.trackId,
          spotifyUri: identity.spotifyUri,
          title: cleanTitle,
          artist: cleanArtist,
          playCount
        }
      });

      if (!this.bucket.eventIdSet.has(eventId)) {
        this.bucket.eventIdSet.add(eventId);
        this.bucket.events.push({
          event_id: eventId,
          user_id: cleanUserId,
          session_id: 'live-phone-sync',
          device_id: 'android-client',
          schema_version: '1.0',
          event_type: 'METADATA_CHANGED',
          occurred_at: now,
          received_at: now,
          media_source: t.source || 'com.spotify.music',
          payload_json: payloadJson,
          ingested_at: now
        });
        changed++;
      } else {
        const existingEvt = this.bucket.events.find(e => e.event_id === eventId);
        if (existingEvt) {
          try {
            const parsed = JSON.parse(existingEvt.payload_json);
            const prevCount = coerceNonNegativePlayCount(parsed?.track?.playCount);
            const prevTrackId = parsed?.track?.trackId;
            const prevUri = parsed?.track?.spotifyUri;
            if (playCount > prevCount || (!prevTrackId && identity.trackId) || (!prevUri && identity.spotifyUri)) {
              existingEvt.payload_json = JSON.stringify({
                track: {
                  trackId: identity.trackId || prevTrackId,
                  spotifyUri: identity.spotifyUri || prevUri,
                  title: cleanTitle,
                  artist: cleanArtist,
                  playCount: Math.max(prevCount, playCount)
                }
              });
              existingEvt.received_at = now;
              changed++;
            }
          } catch {
            // Keep existing payload if unparseable
          }
        }
      }
    }
    if (changed > 0) {
      this.persistToDisk();
    }
  }

  getRecentObservedTracks(
    limit: number = 50,
    userId?: string
  ): Array<{
    title: string;
    artist: string;
    album?: string;
    trackId: string;
    spotifyUri?: string;
    source: string;
    playCount: number;
  }> {
    const targetUser = userId?.trim();
    const tracksMap = new Map<
      string,
      {
        title: string;
        artist: string;
        album?: string;
        trackId: string;
        spotifyUri?: string;
        source: string;
        playCount: number;
      }
    >();

    const sorted = [...this.bucket.events].reverse();
    for (const evt of sorted) {
      if (EventStore.isLegacySeedEvent(evt.event_id)) continue;
      if (targetUser && evt.user_id && evt.user_id !== targetUser) continue;
      try {
        const payload = JSON.parse(evt.payload_json);
        const trk = payload.track || payload.current_track || payload.nowPlaying;
        if (trk?.title && trk.title !== 'Active Session' && trk.title !== 'Standing By' && trk.title !== 'Deep Flow State') {
          const cleanTitle = String(trk.title).trim();
          const cleanArtist = String(trk.artist || 'Unknown Artist').trim();
          const art = cleanArtist.toLowerCase();
          if (
            art.includes('neon hearts') ||
            art.includes('solar pulse') ||
            art.includes('vektor drift') ||
            art.includes('luna rivers') ||
            art.includes('komorebi')
          ) {
            continue;
          }
          const identity = resolveCanonicalTrackIdentity({
            trackId: trk.trackId,
            spotifyUri: trk.spotifyUri || trk.spotify_uri,
            title: cleanTitle,
            artist: cleanArtist
          });
          const isClientSync = evt.event_id.startsWith('client-sync-');
          const hasExplicitPlayCount = trk.playCount !== undefined && trk.playCount !== null;
          const eventPlayCount = hasExplicitPlayCount
            ? coerceNonNegativePlayCount(trk.playCount)
            : isClientSync
            ? 0
            : evt.event_type === 'PLAYBACK_STATE_CHANGED' || evt.event_type === 'METADATA_CHANGED'
            ? 1
            : 0;

          const key = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
          const existing = tracksMap.get(key);
          if (existing) {
            if (isClientSync || hasExplicitPlayCount) {
              existing.playCount = Math.max(existing.playCount, eventPlayCount);
            } else {
              existing.playCount += eventPlayCount;
            }
            if (!existing.spotifyUri && identity.spotifyUri) {
              existing.spotifyUri = identity.spotifyUri;
            }
            if ((!existing.trackId || existing.trackId.startsWith('trk:')) && identity.trackId && !identity.trackId.startsWith('trk:')) {
              existing.trackId = identity.trackId;
            }
          } else {
            tracksMap.set(key, {
              title: cleanTitle,
              artist: cleanArtist,
              album: trk.album,
              trackId: identity.trackId,
              spotifyUri: identity.spotifyUri,
              source: evt.media_source || 'com.spotify.music',
              playCount: eventPlayCount
            });
          }
          if (tracksMap.size >= limit) break;
        }
      } catch (_e) {}
    }
    return Array.from(tracksMap.values());
  }

  count(): number {
    return this.bucket.events.length;
  }

  private filterEvents(params: unknown[]): StoredEvent[] {
    let list = [...this.bucket.events];
    if (params.length > 0 && typeof params[0] === 'string') {
      list = list.filter(e => e.user_id === params[0]);
    }
    return list;
  }

  queryRow<T>(sql: string, params: unknown[] = []): T | undefined {
    const list = this.filterEvents(params);

    if (sql.includes('COUNT(DISTINCT session_id)')) {
      const filtered = sql.includes("session_id != ''") ? list.filter(e => e.session_id !== '') : list;
      const cnt = new Set(filtered.map(e => e.session_id)).size;
      return { cnt } as unknown as T;
    }

    if (sql.includes('COUNT(*)')) {
      if (sql.includes("event_type = 'SESSION_CREATED'")) {
        const cnt = list.filter(e => e.event_type === 'SESSION_CREATED').length;
        return { cnt } as unknown as T;
      }
      if (sql.includes("session_id != ''")) {
        const cnt = list.filter(e => e.session_id !== '').length;
        return { cnt } as unknown as T;
      }
      return { cnt: list.length } as unknown as T;
    }

    if (sql.includes('MIN(occurred_at)') && sql.includes('MAX(occurred_at)')) {
      if (list.length === 0) {
        return { earliest: null, latest: null } as unknown as T;
      }
      const sorted = [...list].sort((a, b) => a.occurred_at.localeCompare(b.occurred_at));
      return {
        earliest: sorted[0]?.occurred_at ?? null,
        latest: sorted[sorted.length - 1]?.occurred_at ?? null
      } as unknown as T;
    }

    return undefined;
  }

  queryAll<T>(sql: string, params: unknown[] = []): T[] {
    let list = this.filterEvents(params);

    if (sql.includes("session_id != ''")) {
      list = list.filter(e => e.session_id !== '');
    }

    if (sql.includes('GROUP BY media_source')) {
      const counts = new Map<string, number>();
      list.forEach(e => {
        counts.set(e.media_source, (counts.get(e.media_source) || 0) + 1);
      });
      return Array.from(counts.entries()).map(([media_source, cnt]) => ({
        media_source,
        cnt
      })) as unknown as T[];
    }

    if (sql.includes('GROUP BY event_type')) {
      const counts = new Map<string, number>();
      list.forEach(e => {
        counts.set(e.event_type, (counts.get(e.event_type) || 0) + 1);
      });
      return Array.from(counts.entries()).map(([event_type, cnt]) => ({
        event_type,
        cnt
      })) as unknown as T[];
    }

    if (sql.includes('DISTINCT session_id')) {
      const distinct = Array.from(new Set(list.map(e => e.session_id)));
      return distinct.map(session_id => ({ session_id })) as unknown as T[];
    }

    if (sql.includes('DISTINCT media_source')) {
      const distinct = Array.from(new Set(list.map(e => e.media_source))).sort();
      return distinct.map(media_source => ({ media_source })) as unknown as T[];
    }

    if (sql.includes('ORDER BY occurred_at ASC')) {
      const sorted = [...list].sort((a, b) => {
        const t = a.occurred_at.localeCompare(b.occurred_at);
        return t !== 0 ? t : a.event_id.localeCompare(b.event_id);
      });
      return sorted as unknown as T[];
    }

    return list as unknown as T[];
  }

  clear(): void {
    this.bucket.events = [];
    this.bucket.eventIdSet.clear();
    this.persistToDisk();
  }

  deleteByUserIds(userIds: string[]): number {
    const targets = new Set(userIds.map(id => id.trim().toLowerCase()).filter(Boolean));
    if (targets.size === 0) return 0;

    const before = this.bucket.events.length;
    this.bucket.events = this.bucket.events.filter(
      e => !targets.has((e.user_id || '').trim().toLowerCase())
    );
    const removed = before - this.bucket.events.length;
    if (removed > 0) {
      this.bucket.eventIdSet = new Set(this.bucket.events.map(e => e.event_id));
      this.persistToDisk();
    }
    return removed;
  }

  getDbPath(): string {
    return this.dbPath;
  }

  close(): void {
    this.persistToDisk();
  }
}
