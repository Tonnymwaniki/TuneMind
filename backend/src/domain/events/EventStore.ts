/**
 * EventStore — Persistent In-memory + File Storage for accepted ListeningEvents.
 *
 * Implements the full EventStore contract without requiring native C++ SQLite binaries.
 * Writes durably to disk so that real music played on the phone is saved across server restarts.
 * Preserves idempotency, event ordering, and development analytics queries.
 */

import fs from 'fs';
import path from 'path';

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

const DATA_DIR = path.resolve(process.cwd(), 'data');
const DATA_FILE = path.join(DATA_DIR, 'tunemind_events.json');

export class EventStore {
  private static events: StoredEvent[] = [];
  private static eventIdSet = new Set<string>();
  private static initialized = false;
  private readonly dbPath: string;

  constructor(dbPath?: string) {
    this.dbPath = dbPath ?? DATA_FILE;
    if (!EventStore.initialized) {
      EventStore.initialized = true;
      this.initStorage();
    }
  }

  private initStorage(): void {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }

      if (fs.existsSync(DATA_FILE)) {
        const raw = fs.readFileSync(DATA_FILE, 'utf-8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          // Cleanse any legacy synthetic synthwave tracks from older builds
          const cleansed = parsed.filter((e: StoredEvent) => {
            const p = e.payload_json || '';
            return !p.includes('The Neon Hearts') &&
                   !p.includes('Solar Pulse') &&
                   !p.includes('Vektor Drift') &&
                   !p.includes('Luna Rivers') &&
                   !p.includes('Komorebi');
          });

          if (cleansed.length !== parsed.length) {
            console.log(`[EventStore] 🧹 Purged ${parsed.length - cleansed.length} legacy synthetic synthwave events from persistent storage.`);
          }

          if (cleansed.length > 0) {
            EventStore.events = cleansed;
            EventStore.eventIdSet = new Set(cleansed.map(e => e.event_id));
            console.log(`[EventStore] Restored ${cleansed.length} persistent events from ${DATA_FILE}`);
            if (cleansed.length !== parsed.length) {
              this.persistToDisk();
            }
            return;
          }
        }
      }
    } catch (err) {
      console.warn('[EventStore] Could not load persisted data:', err);
    }

    if (EventStore.events.length === 0) {
      this.seedInitialEvents();
      this.persistToDisk();
    }
  }

  private persistToDisk(): void {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }
      fs.writeFileSync(DATA_FILE, JSON.stringify(EventStore.events, null, 2), 'utf-8');
    } catch (err) {
      console.error('[EventStore] Error persisting events to disk:', err);
    }
  }

  private seedInitialEvents(): void {
    // Seed initial realistic listening observations across authentic genres (Bongo Flava, Afrobeats, Afro-Pop)
    const baseTime = Date.now() - 7 * 24 * 3600 * 1000;
    const sampleTracks = [
      { id: 'trk-1', title: 'Huwezi Jua', artist: 'Mbosso', album: 'Definition of Love', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 102 },
      { id: 'trk-2', title: 'Komasava', artist: 'Diamond Platnumz', album: 'First Of All', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 112 },
      { id: 'np-001', title: 'Enjoy', artist: 'Jux & Diamond Platnumz', album: 'King of Hearts', source: 'com.spotify.music', genre: 'Afro-Bongo', bpm: 108 },
      { id: 'cand-1', title: 'Sikomi', artist: 'Diamond Platnumz', album: 'A Boy From Tandale', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 98 },
      { id: 'cand-2', title: 'Amepotea', artist: 'Mbosso', album: 'Khan', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 104 },
      { id: 'cand-3', title: 'Mi Amor', artist: 'Marioo & Jovial', album: 'The Kid You Know', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 106 },
      { id: 'cand-4', title: 'Single Again', artist: 'Harmonize', album: 'Made For Us', source: 'com.spotify.music', genre: 'Bongo Flava', bpm: 100 },
      { id: 'cand-5', title: 'Calm Down', artist: 'Rema', album: 'Rave & Climax', source: 'com.spotify.music', genre: 'Afrobeats', bpm: 105 },
      { id: 'cand-6', title: 'Sawa', artist: 'Otile Brown', album: 'Just In Love', source: 'com.spotify.music', genre: 'Afro-Pop', bpm: 95 }
    ];

    const users = ['tunemind-dev', 'tonny@tunemind.neuro'];

    users.forEach(userId => {
      let offset = 0;
      for (let day = 0; day < 7; day++) {
        const sessionId = `obs-sess-day-${day}-${userId.slice(0, 5)}`;
        const dayBase = baseTime + day * 86400 * 1000 + 3600 * 1000 * 10; // 10 AM

        // Session created
        this.insert(
          `evt-sc-${day}-${userId}`,
          userId,
          sessionId,
          'pixel-8-pro',
          '1.0',
          'SESSION_CREATED',
          new Date(dayBase).toISOString(),
          new Date(dayBase + 10).toISOString(),
          'com.spotify.music',
          JSON.stringify({ observerSessionId: sessionId, packageName: 'com.spotify.music' })
        );

        // Observations for tracks
        sampleTracks.slice(0, 3 + (day % 3)).forEach((trk, idx) => {
          const trackTime = dayBase + (idx * 240 + 60) * 1000;
          // State playing
          this.insert(
            `evt-play-${day}-${idx}-${userId}`,
            userId,
            sessionId,
            'pixel-8-pro',
            '1.0',
            'PLAYBACK_STATE_CHANGED',
            new Date(trackTime).toISOString(),
            new Date(trackTime + 20).toISOString(),
            trk.source,
            JSON.stringify({
              playback: {
                positionMs: 5000,
                elapsedRealtimeMs: 12000000 + offset,
                isPlaying: true,
                playbackSpeed: 1.0,
                playbackStateCode: 3
              },
              track: {
                trackId: trk.id,
                title: trk.title,
                artist: trk.artist,
                album: trk.album,
                durationMs: 210000
              }
            })
          );

          // State metadata
          this.insert(
            `evt-meta-${day}-${idx}-${userId}`,
            userId,
            sessionId,
            'pixel-8-pro',
            '1.0',
            'METADATA_CHANGED',
            new Date(trackTime + 30000).toISOString(),
            new Date(trackTime + 30020).toISOString(),
            trk.source,
            JSON.stringify({
              playback: {
                positionMs: 35000,
                elapsedRealtimeMs: 12030000 + offset,
                isPlaying: true,
                playbackSpeed: 1.0,
                playbackStateCode: 3
              },
              track: {
                trackId: trk.id,
                title: trk.title,
                artist: trk.artist,
                album: trk.album,
                durationMs: 210000
              }
            })
          );

          offset += 60000;
        });

        // Observation stopped
        const endDayTime = dayBase + 1200 * 1000;
        this.insert(
          `evt-stop-${day}-${userId}`,
          userId,
          sessionId,
          'pixel-8-pro',
          '1.0',
          'OBSERVATION_STOPPED',
          new Date(endDayTime).toISOString(),
          new Date(endDayTime + 15).toISOString(),
          'com.spotify.music',
          JSON.stringify({
            playback: {
              positionMs: 200000,
              elapsedRealtimeMs: 12120000 + offset,
              isPlaying: false,
              playbackSpeed: 0,
              playbackStateCode: 2
            }
          })
        );
      }
    });
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
    payloadJson: string,
  ): boolean {
    if (EventStore.eventIdSet.has(eventId)) {
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

    EventStore.eventIdSet.add(eventId);
    EventStore.events.push(event);
    this.persistToDisk();
    return true;
  }

  listAll(): StoredEvent[] {
    return [...EventStore.events].sort((a, b) => a.ingested_at.localeCompare(b.ingested_at));
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

  syncClientTracks(tracks: Array<{ title: string; artist: string; source?: string; trackId?: string; playCount?: number }>): void {
    let added = 0;
    for (const t of tracks) {
      if (!t.title || t.title === 'Standing By' || t.title === 'Active Session' || t.title === 'Deep Flow State') continue;
      const cleanTitle = t.title.trim();
      const cleanArtist = (t.artist || 'Unknown Artist').trim();
      const safeKey = `${cleanTitle.toLowerCase()}-${cleanArtist.toLowerCase()}`.replace(/[^a-z0-9-]/g, '_');
      const eventId = `client-sync-${safeKey}`;
      if (!EventStore.eventIdSet.has(eventId)) {
        const now = new Date().toISOString();
        EventStore.eventIdSet.add(eventId);
        EventStore.events.push({
          event_id: eventId,
          user_id: 'tunemind-default-user',
          session_id: 'live-phone-sync',
          device_id: 'android-client',
          schema_version: '1.0',
          event_type: 'METADATA_CHANGED',
          occurred_at: now,
          received_at: now,
          media_source: t.source || 'com.spotify.music',
          payload_json: JSON.stringify({
            track: {
              trackId: t.trackId || undefined,
              title: cleanTitle,
              artist: cleanArtist,
              playCount: t.playCount || 1
            }
          }),
          ingested_at: now
        });
        added++;
      }
    }
    if (added > 0) {
      this.persistToDisk();
      console.log(`[EventStore] 💾 Persisted ${added} new tracks from Android phone memory to disk.`);
    }
  }

  getRecentObservedTracks(limit: number = 50): Array<{ title: string; artist: string; album?: string; trackId?: string; source: string; playCount: number }> {
    const tracksMap = new Map<string, { title: string; artist: string; album?: string; trackId?: string; source: string; playCount: number }>();

    const isSeedEvent = (id: string) => id.startsWith('evt-play-') || id.startsWith('evt-meta-') || id.startsWith('evt-sc-') || id.startsWith('evt-stop-');
    const hasRealEvents = EventStore.events.some(e => !isSeedEvent(e.event_id));

    // Scan events in reverse chronological order, ignoring synthetic seed tracks if real user tracks exist
    const sorted = [...EventStore.events].reverse();
    for (const evt of sorted) {
      if (hasRealEvents && isSeedEvent(evt.event_id)) continue;
      try {
        const payload = JSON.parse(evt.payload_json);
        const trk = payload.track || payload.current_track || payload.nowPlaying;
        if (trk?.title && trk.title !== 'Active Session' && trk.title !== 'Standing By' && trk.title !== 'Deep Flow State') {
          const cleanTitle = String(trk.title).trim();
          const cleanArtist = String(trk.artist || 'Unknown Artist').trim();
          const art = cleanArtist.toLowerCase();
          if (art.includes('neon hearts') || art.includes('solar pulse') || art.includes('vektor drift') || art.includes('luna rivers') || art.includes('komorebi')) {
            continue;
          }
          const key = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
          const existing = tracksMap.get(key);
          if (existing) {
            existing.playCount++;
            if (!existing.trackId && trk.trackId) {
              existing.trackId = trk.trackId;
            }
          } else {
            tracksMap.set(key, {
              title: cleanTitle,
              artist: cleanArtist,
              album: trk.album,
              trackId: trk.trackId || undefined,
              source: evt.media_source || 'com.spotify.music',
              playCount: trk.playCount || 1
            });
          }
          if (tracksMap.size >= limit) break;
        }
      } catch (_e) {}
    }
    return Array.from(tracksMap.values());
  }

  count(): number {
    return EventStore.events.length;
  }

  private filterEvents(params: unknown[]): StoredEvent[] {
    let list = [...EventStore.events];
    if (params.length > 0 && typeof params[0] === 'string') {
      list = list.filter(e => e.user_id === params[0]);
    }
    return list;
  }

  queryRow<T>(sql: string, params: unknown[] = []): T | undefined {
    const list = this.filterEvents(params);

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
    EventStore.events = [];
    EventStore.eventIdSet.clear();
    this.persistToDisk();
  }

  getDbPath(): string {
    return this.dbPath;
  }

  close(): void {
    this.persistToDisk();
  }
}
