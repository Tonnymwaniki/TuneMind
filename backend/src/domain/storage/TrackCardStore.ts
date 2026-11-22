import { getSupabaseAdminClient } from '../../config/supabase';

export interface IncomingTrackCard {
  canonical_track_key: string;
  track_id?: string;
  title: string;
  artist?: string;
  album?: string;
  genre?: string;
  duration_ms?: number;
  spotify_uri?: string | null;
  ytm_video_id?: string | null;
  album_art_url?: string | null;
  preview_url?: string | null;
  primary_source_package?: string;
  source_packages?: string[];
  is_liked?: boolean;
  is_in_vault?: boolean;
  first_played_at_ms?: number;
  last_played_at_ms?: number;
}

export interface IncomingPlayEvent {
  play_event_id: string;
  canonical_track_key: string;
  track_id?: string;
  title?: string;
  artist?: string;
  album?: string;
  source_package?: string;
  source_app_name?: string;
  played_at_ms: number;
  listened_ms?: number;
  track_duration_ms?: number;
  completion_ratio?: number;
  was_skipped?: boolean;
}

export interface IncomingPlaylistItem {
  entry_id: string;
  canonical_track_key: string;
  title: string;
  artist?: string;
  external_uri?: string;
  track_id?: string;
  source_player?: string;
  position_index?: number;
  added_by?: string;
  added_at_ms?: number;
}

export interface IncomingPlaylist {
  id: string;
  playlist_origin?: string;
  external_playlist_id?: string | null;
  name: string;
  description?: string;
  mood_or_audience?: string;
  cover_image_url?: string | null;
  created_at_ms?: number;
  updated_at_ms?: number;
  items?: IncomingPlaylistItem[];
}

export interface TrackCardSyncPayload {
  track_cards?: IncomingTrackCard[];
  play_events?: IncomingPlayEvent[];
  playlists?: IncomingPlaylist[];
}

export interface StoredTrackCardRecord {
  id: string;
  user_id: string;
  canonical_track_key: string;
  track_id: string;
  title: string;
  artist: string;
  album: string;
  genre: string;
  duration_ms: number;
  spotify_uri: string | null;
  ytm_video_id: string | null;
  album_art_url: string | null;
  preview_url: string | null;
  primary_source_package: string;
  source_packages: string[];
  verified_play_count: number;
  total_listened_ms: number;
  first_played_at: string | null;
  last_played_at: string | null;
  is_liked: boolean;
  is_in_vault: boolean;
}

export interface TrackCardSyncResult {
  persistedToSupabase: boolean;
  upsertedTrackCards: number;
  insertedPlayEvents: number;
  upsertedPlaylists: number;
  upsertedPlaylistItems: number;
  verifiedPlayCountsByKey: Record<string, number>;
}

interface MemoryUserState {
  cardsByKey: Map<string, StoredTrackCardRecord>;
  playEventsById: Map<string, { canonicalKey: string; listenedMs: number; playedAtIso: string; wasSkipped: boolean }>;
  playlistsById: Map<string, IncomingPlaylist>;
}

const memoryStore = new Map<string, MemoryUserState>();

function getMemoryUser(userId: string): MemoryUserState {
  let state = memoryStore.get(userId);
  if (!state) {
    state = {
      cardsByKey: new Map(),
      playEventsById: new Map(),
      playlistsById: new Map()
    };
    memoryStore.set(userId, state);
  }
  return state;
}

function toIsoOrNull(ms?: number): string | null {
  if (!ms || !Number.isFinite(ms) || ms <= 0) return null;
  try {
    return new Date(ms).toISOString();
  } catch {
    return null;
  }
}

function sanitizeKey(raw?: string): string {
  return String(raw || '').trim().toLowerCase();
}

function mergePackages(existing: string[], incoming?: string[], primary?: string): string[] {
  const set = new Set<string>();
  for (const p of existing) {
    const clean = String(p || '').trim();
    if (clean) set.add(clean);
  }
  if (Array.isArray(incoming)) {
    for (const p of incoming) {
      const clean = String(p || '').trim();
      if (clean) set.add(clean);
    }
  }
  if (primary && primary.trim()) {
    set.add(primary.trim());
  }
  return Array.from(set).sort();
}

function derivePlaylistOrigin(playlist: IncomingPlaylist): string {
  const explicit = String(playlist.playlist_origin || '').trim().toUpperCase();
  if (explicit) return explicit;
  if (playlist.id.startsWith('spotify_pl_')) return 'SPOTIFY';
  if (playlist.id.startsWith('ytm_pl_')) return 'YOUTUBE_MUSIC';
  return 'TUNEMIND';
}

export class TrackCardStore {
  static async syncSnapshot(userId: string, payload: TrackCardSyncPayload): Promise<TrackCardSyncResult> {
    const cleanUserId = String(userId || '').trim();
    const rawCards = Array.isArray(payload.track_cards) ? payload.track_cards : [];
    const rawEvents = Array.isArray(payload.play_events) ? payload.play_events : [];
    const rawPlaylists = Array.isArray(payload.playlists) ? payload.playlists : [];

    // Ensure every play_event and playlist_item also guarantees a Track Card row exists
    const cardMap = new Map<string, IncomingTrackCard>();
    for (const c of rawCards) {
      const key = sanitizeKey(c.canonical_track_key);
      if (!key || !String(c.title || '').trim()) continue;
      cardMap.set(key, { ...c, canonical_track_key: key });
    }
    for (const ev of rawEvents) {
      const key = sanitizeKey(ev.canonical_track_key);
      if (!key) continue;
      if (!cardMap.has(key) && String(ev.title || '').trim()) {
        cardMap.set(key, {
          canonical_track_key: key,
          track_id: ev.track_id || '',
          title: String(ev.title || '').trim(),
          artist: String(ev.artist || '').trim(),
          album: String(ev.album || '').trim(),
          duration_ms: Number(ev.track_duration_ms || 0),
          primary_source_package: ev.source_package || 'unknown_player',
          source_packages: ev.source_package ? [ev.source_package] : [],
          first_played_at_ms: ev.played_at_ms,
          last_played_at_ms: ev.played_at_ms
        });
      }
    }
    for (const pl of rawPlaylists) {
      for (const item of Array.isArray(pl.items) ? pl.items : []) {
        const key = sanitizeKey(item.canonical_track_key);
        if (!key) continue;
        if (!cardMap.has(key) && String(item.title || '').trim()) {
          cardMap.set(key, {
            canonical_track_key: key,
            track_id: item.track_id || item.external_uri || '',
            title: String(item.title || '').trim(),
            artist: String(item.artist || '').trim(),
            spotify_uri: item.external_uri?.startsWith('spotify:track:') ? item.external_uri : null,
            primary_source_package: item.source_player || 'unknown_player',
            source_packages: item.source_player ? [item.source_player] : []
          });
        }
      }
    }

    const adminClient = getSupabaseAdminClient();
    if (!adminClient || !cleanUserId) {
      return this.syncToMemoryFallback(cleanUserId || 'anonymous', Array.from(cardMap.values()), rawEvents, rawPlaylists);
    }

    try {
      const nowIso = new Date().toISOString();

      // 1. Fetch existing track cards for this user so we merge source_packages & flags cleanly without losing history
      const existingByKey = new Map<string, any>();
      const { data: existingRows, error: fetchErr } = await adminClient
        .from('track_cards')
        .select('id, canonical_track_key, track_id, album, genre, duration_ms, spotify_uri, ytm_video_id, album_art_url, preview_url, primary_source_package, source_packages, verified_play_count, total_listened_ms, first_played_at, last_played_at, is_liked, is_in_vault')
        .eq('user_id', cleanUserId);

      if (fetchErr) {
        console.warn('[TrackCardStore] Supabase track_cards read notice (falling back to memory):', fetchErr.message);
        return this.syncToMemoryFallback(cleanUserId, Array.from(cardMap.values()), rawEvents, rawPlaylists);
      }

      for (const row of existingRows || []) {
        existingByKey.set(sanitizeKey(row.canonical_track_key), row);
      }

      // 2. Upsert permanent Track Cards (never clobbering verified_play_count; Postgres trigger owns verified_play_count!)
      const cardUpsertRows: any[] = [];
      for (const card of cardMap.values()) {
        const ex = existingByKey.get(card.canonical_track_key);
        const mergedPkgs = mergePackages(
          Array.isArray(ex?.source_packages) ? ex.source_packages : [],
          card.source_packages,
          card.primary_source_package
        );
        const resolvedSpotifyUri =
          (card.spotify_uri && card.spotify_uri.startsWith('spotify:track:') ? card.spotify_uri : null) ||
          (card.track_id && card.track_id.startsWith('spotify:track:') ? card.track_id : null) ||
          ex?.spotify_uri ||
          null;

        const row: any = {
          user_id: cleanUserId,
          canonical_track_key: card.canonical_track_key,
          track_id: String(card.track_id || ex?.track_id || resolvedSpotifyUri || card.canonical_track_key).trim(),
          title: String(card.title || '').trim(),
          artist: String(card.artist || '').trim(),
          album: String(card.album || ex?.album || '').trim(),
          genre: String(card.genre && card.genre !== 'Music' ? card.genre : ex?.genre || card.genre || 'Music').trim(),
          duration_ms: Math.max(Number(card.duration_ms || 0), Number(ex?.duration_ms || 0)),
          spotify_uri: resolvedSpotifyUri,
          ytm_video_id: card.ytm_video_id || ex?.ytm_video_id || null,
          album_art_url: card.album_art_url || ex?.album_art_url || null,
          preview_url: card.preview_url || ex?.preview_url || null,
          primary_source_package:
            (card.primary_source_package && card.primary_source_package !== 'unknown_player'
              ? card.primary_source_package
              : ex?.primary_source_package) || 'unknown_player',
          source_packages: mergedPkgs,
          is_liked: typeof card.is_liked === 'boolean' ? card.is_liked : Boolean(ex?.is_liked),
          is_in_vault: typeof card.is_in_vault === 'boolean' ? card.is_in_vault : Boolean(ex?.is_in_vault),
          updated_at: nowIso
        };

        // Preserve existing verified_play_count on upsert; new cards start strictly at 0 until track_play_events fire trigger!
        if (ex) {
          row.verified_play_count = Number(ex.verified_play_count || 0);
          row.total_listened_ms = Number(ex.total_listened_ms || 0);
          row.first_played_at = ex.first_played_at || toIsoOrNull(card.first_played_at_ms);
          row.last_played_at = ex.last_played_at || toIsoOrNull(card.last_played_at_ms);
        } else {
          row.verified_play_count = 0;
          row.total_listened_ms = 0;
          row.first_played_at = toIsoOrNull(card.first_played_at_ms);
          row.last_played_at = toIsoOrNull(card.last_played_at_ms);
        }

        cardUpsertRows.push(row);
      }

      const cardIdByKey = new Map<string, string>();
      for (const [k, row] of existingByKey.entries()) {
        if (row.id) cardIdByKey.set(k, String(row.id));
      }

      if (cardUpsertRows.length > 0) {
        // Chunk upserts by 200 for PostgREST payload safety
        for (let i = 0; i < cardUpsertRows.length; i += 200) {
          const chunk = cardUpsertRows.slice(i, i + 200);
          const { data: upserted, error: upsertErr } = await adminClient
            .from('track_cards')
            .upsert(chunk, { onConflict: 'user_id,canonical_track_key' })
            .select('id, canonical_track_key');

          if (upsertErr) {
            console.error('[TrackCardStore] track_cards upsert error:', upsertErr.message);
            return this.syncToMemoryFallback(cleanUserId, Array.from(cardMap.values()), rawEvents, rawPlaylists);
          }

          for (const r of upserted || []) {
            cardIdByKey.set(sanitizeKey(r.canonical_track_key), String(r.id));
          }
        }
      }

      // 3. Insert append-only verified Play Events (idempotent by play_event_id).
      // The Postgres trigger `trg_sync_track_card_play_stats` atomically updates `track_cards.verified_play_count`!
      const eventRows: any[] = [];
      for (const ev of rawEvents) {
        const eventId = String(ev.play_event_id || '').trim();
        const key = sanitizeKey(ev.canonical_track_key);
        const cardId = cardIdByKey.get(key);
        if (!eventId || !cardId) continue;

        const playedAtIso = toIsoOrNull(ev.played_at_ms) || nowIso;
        eventRows.push({
          play_event_id: eventId,
          user_id: cleanUserId,
          track_card_id: cardId,
          source_package: String(ev.source_package || 'unknown_player').trim(),
          source_app_name: String(ev.source_app_name || 'Music Player').trim(),
          played_at: playedAtIso,
          listened_ms: Math.max(0, Math.round(Number(ev.listened_ms || 0))),
          track_duration_ms: Math.max(0, Math.round(Number(ev.track_duration_ms || 0))),
          completion_ratio: Math.max(0, Math.min(1, Number(ev.completion_ratio || 0))),
          was_skipped: Boolean(ev.was_skipped)
        });
      }

      let insertedPlayEvents = 0;
      if (eventRows.length > 0) {
        for (let i = 0; i < eventRows.length; i += 200) {
          const chunk = eventRows.slice(i, i + 200);
          const { error: evErr } = await adminClient
            .from('track_play_events')
            .upsert(chunk, { onConflict: 'play_event_id', ignoreDuplicates: true });

          if (evErr) {
            console.warn('[TrackCardStore] track_play_events insert notice:', evErr.message);
          } else {
            insertedPlayEvents += chunk.length;
          }
        }
      }

      // 4. Upsert relational Playlists & Playlist Items linked via track_card_id FK
      let upsertedPlaylists = 0;
      let upsertedPlaylistItems = 0;

      if (rawPlaylists.length > 0) {
        const playlistRows = rawPlaylists
          .filter((pl) => String(pl.id || '').trim() && String(pl.name || '').trim())
          .map((pl) => ({
            id: String(pl.id).trim(),
            user_id: cleanUserId,
            playlist_origin: derivePlaylistOrigin(pl),
            external_playlist_id:
              pl.external_playlist_id ||
              (pl.id.startsWith('spotify_pl_') ? pl.id.replace('spotify_pl_', '') : null),
            name: String(pl.name).trim(),
            description: String(pl.description || '').trim(),
            mood_or_audience: String(pl.mood_or_audience || '').trim(),
            cover_image_url: pl.cover_image_url || null,
            created_at: toIsoOrNull(pl.created_at_ms) || nowIso,
            updated_at: toIsoOrNull(pl.updated_at_ms) || nowIso
          }));

        if (playlistRows.length > 0) {
          const { error: plErr } = await adminClient
            .from('user_playlists')
            .upsert(playlistRows, { onConflict: 'id' });
          if (plErr) {
            console.warn('[TrackCardStore] user_playlists upsert notice:', plErr.message);
          } else {
            upsertedPlaylists = playlistRows.length;
          }
        }

        // Batched replace of playlist items (eliminates N+1 per-playlist DB round-trips)
        const playlistIdsToReplace: string[] = [];
        const allItemRows: any[] = [];
        for (const pl of rawPlaylists) {
          const pid = String(pl.id || '').trim();
          if (!pid || !Array.isArray(pl.items)) continue;
          playlistIdsToReplace.push(pid);

          for (let idx = 0; idx < pl.items.length; idx++) {
            const item = pl.items[idx];
            const cardId = cardIdByKey.get(sanitizeKey(item.canonical_track_key));
            const entryId = String(item.entry_id || `${pid}::${idx}`).trim();
            if (!cardId || !entryId) continue;
            allItemRows.push({
              entry_id: entryId,
              playlist_id: pid,
              track_card_id: cardId,
              position_index: Number.isFinite(item.position_index) ? Number(item.position_index) : idx,
              source_player: String(item.source_player || 'unknown_player').trim(),
              added_by: String(item.added_by || 'USER').trim().toUpperCase(),
              added_at: toIsoOrNull(item.added_at_ms) || nowIso
            });
          }
        }

        if (playlistIdsToReplace.length > 0) {
          await adminClient.from('user_playlist_items').delete().in('playlist_id', playlistIdsToReplace);
        }

        if (allItemRows.length > 0) {
          for (let i = 0; i < allItemRows.length; i += 200) {
            const chunk = allItemRows.slice(i, i + 200);
            const { error: itemErr } = await adminClient
              .from('user_playlist_items')
              .upsert(chunk, { onConflict: 'entry_id' });
            if (itemErr) {
              console.warn('[TrackCardStore] user_playlist_items upsert notice:', itemErr.message);
            } else {
              upsertedPlaylistItems += chunk.length;
            }
          }
        }
      }

      // 5. Read back authoritative verified_play_count for every Track Card of this user
      const verifiedPlayCountsByKey: Record<string, number> = {};
      const { data: authoritativeCards } = await adminClient
        .from('track_cards')
        .select('canonical_track_key, verified_play_count')
        .eq('user_id', cleanUserId);

      for (const row of authoritativeCards || []) {
        verifiedPlayCountsByKey[sanitizeKey(row.canonical_track_key)] = Number(row.verified_play_count || 0);
      }

      return {
        persistedToSupabase: true,
        upsertedTrackCards: cardUpsertRows.length,
        insertedPlayEvents,
        upsertedPlaylists,
        upsertedPlaylistItems,
        verifiedPlayCountsByKey
      };
    } catch (err: any) {
      console.error('[TrackCardStore] syncSnapshot error:', err?.message || err);
      return this.syncToMemoryFallback(cleanUserId, Array.from(cardMap.values()), rawEvents, rawPlaylists);
    }
  }

  static async getSnapshot(
    userId: string,
    options?: { limit?: number; offset?: number }
  ): Promise<{
    source: 'supabase' | 'memory';
    track_cards: StoredTrackCardRecord[];
    playlists: any[];
    has_more: boolean;
    next_offset: number;
  }> {
    const cleanUserId = String(userId || '').trim();
    const limit = Math.min(500, Math.max(1, Number(options?.limit ?? 200) || 200));
    const offset = Math.max(0, Number(options?.offset ?? 0) || 0);
    const adminClient = getSupabaseAdminClient();

    if (adminClient && cleanUserId) {
      try {
        const { data: cards, error: cardsErr } = await adminClient
          .from('track_cards')
          .select('*')
          .eq('user_id', cleanUserId)
          .order('last_played_at', { ascending: false, nullsFirst: false })
          .range(offset, offset + limit);

        if (!cardsErr && Array.isArray(cards)) {
          const hasMore = cards.length > limit;
          const pageCards = hasMore ? cards.slice(0, limit) : cards;
          const { data: playlists } = await adminClient
            .from('user_playlists')
            .select(
              `
              id,
              playlist_origin,
              external_playlist_id,
              name,
              description,
              mood_or_audience,
              cover_image_url,
              created_at,
              updated_at,
              user_playlist_items (
                entry_id,
                position_index,
                source_player,
                added_by,
                added_at,
                track_card:track_cards (
                  id,
                  canonical_track_key,
                  track_id,
                  title,
                  artist,
                  album,
                  spotify_uri,
                  album_art_url,
                  verified_play_count,
                  is_liked,
                  primary_source_package
                )
              )
            `
            )
            .eq('user_id', cleanUserId)
            .order('updated_at', { ascending: false })
            .limit(100);

          return {
            source: 'supabase',
            track_cards: pageCards as StoredTrackCardRecord[],
            playlists: playlists || [],
            has_more: hasMore,
            next_offset: offset + pageCards.length
          };
        }
      } catch (err: any) {
        console.warn('[TrackCardStore] getSnapshot Supabase fallback:', err?.message || err);
      }
    }

    const mem = getMemoryUser(cleanUserId || 'anonymous');
    const allCards = Array.from(mem.cardsByKey.values());
    const pageCards = allCards.slice(offset, offset + limit);
    return {
      source: 'memory',
      track_cards: pageCards,
      playlists: Array.from(mem.playlistsById.values()).slice(0, 100),
      has_more: offset + pageCards.length < allCards.length,
      next_offset: offset + pageCards.length
    };
  }

  private static syncToMemoryFallback(
    userId: string,
    cards: IncomingTrackCard[],
    events: IncomingPlayEvent[],
    playlists: IncomingPlaylist[]
  ): TrackCardSyncResult {
    const state = getMemoryUser(userId);
    for (const c of cards) {
      const existing = state.cardsByKey.get(c.canonical_track_key);
      state.cardsByKey.set(c.canonical_track_key, {
        id: existing?.id || `mem_${c.canonical_track_key}`,
        user_id: userId,
        canonical_track_key: c.canonical_track_key,
        track_id: c.track_id || existing?.track_id || c.canonical_track_key,
        title: c.title,
        artist: c.artist || existing?.artist || '',
        album: c.album || existing?.album || '',
        genre: c.genre || existing?.genre || 'Music',
        duration_ms: Math.max(Number(c.duration_ms || 0), Number(existing?.duration_ms || 0)),
        spotify_uri: c.spotify_uri || existing?.spotify_uri || null,
        ytm_video_id: c.ytm_video_id || existing?.ytm_video_id || null,
        album_art_url: c.album_art_url || existing?.album_art_url || null,
        preview_url: c.preview_url || existing?.preview_url || null,
        primary_source_package: c.primary_source_package || existing?.primary_source_package || 'unknown_player',
        source_packages: mergePackages(existing?.source_packages || [], c.source_packages, c.primary_source_package),
        verified_play_count: existing?.verified_play_count || 0,
        total_listened_ms: existing?.total_listened_ms || 0,
        first_played_at: existing?.first_played_at || toIsoOrNull(c.first_played_at_ms),
        last_played_at: existing?.last_played_at || toIsoOrNull(c.last_played_at_ms),
        is_liked: typeof c.is_liked === 'boolean' ? c.is_liked : Boolean(existing?.is_liked),
        is_in_vault: typeof c.is_in_vault === 'boolean' ? c.is_in_vault : Boolean(existing?.is_in_vault)
      });
    }

    let inserted = 0;
    for (const ev of events) {
      const id = String(ev.play_event_id || '').trim();
      const key = sanitizeKey(ev.canonical_track_key);
      if (!id || !key || state.playEventsById.has(id)) continue;
      state.playEventsById.set(id, {
        canonicalKey: key,
        listenedMs: Math.max(0, Number(ev.listened_ms || 0)),
        playedAtIso: toIsoOrNull(ev.played_at_ms) || new Date().toISOString(),
        wasSkipped: Boolean(ev.was_skipped)
      });
      inserted++;
    }

    // Recompute exact verified_play_count from playEventsById
    for (const card of state.cardsByKey.values()) {
      card.verified_play_count = 0;
      card.total_listened_ms = 0;
    }
    for (const ev of state.playEventsById.values()) {
      if (ev.wasSkipped) continue;
      const card = state.cardsByKey.get(ev.canonicalKey);
      if (card) {
        card.verified_play_count += 1;
        card.total_listened_ms += ev.listenedMs;
      }
    }

    let itemCount = 0;
    for (const pl of playlists) {
      if (pl.id) {
        state.playlistsById.set(pl.id, pl);
        itemCount += Array.isArray(pl.items) ? pl.items.length : 0;
      }
    }

    const verifiedPlayCountsByKey: Record<string, number> = {};
    for (const [k, c] of state.cardsByKey.entries()) {
      verifiedPlayCountsByKey[k] = c.verified_play_count;
    }

    return {
      persistedToSupabase: false,
      upsertedTrackCards: cards.length,
      insertedPlayEvents: inserted,
      upsertedPlaylists: playlists.length,
      upsertedPlaylistItems: itemCount,
      verifiedPlayCountsByKey
    };
  }
}
