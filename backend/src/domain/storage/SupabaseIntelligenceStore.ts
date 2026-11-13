import { getLiveSupabaseClient, isSupabaseLive } from '../../config/supabase';
import { EventStore } from '../events/EventStore';

export interface SupabaseConnectionOverride {
  supabaseUrl?: string;
  supabaseKey?: string;
}

export interface LearnedTrackEntry {
  title: string;
  artist: string;
  spotifyUri?: string;
  playCount: number;
  source?: string;
  lastPlayedAt?: string;
}

export interface AgentInterventionRecord {
  user_id: string;
  trigger_transcript: string;
  computed_satiety: number;
  agent_tts_response: string;
  os_intent: string;
  target_search_query: string;
  action?: string;
  track_title?: string;
  track_artist?: string;
  spotify_uri?: string;
  circadian_daypart?: string;
  created_at?: string;
}

export interface LongTermIntelligenceSummary {
  userId: string;
  isLiveSupabaseConnected: boolean;
  intelligenceMaturityLevel: 'SEEDING' | 'DEVELOPING' | 'CALIBRATED' | 'DEEP_NEURAL_SYNC';
  totalHistoricalInterventions: number;
  totalTracksLearned: number;
  approvedTracks: Array<{ title: string; artist: string; spotifyUri?: string; approvedAt: string }>;
  rejectedTracks: Array<{ title: string; artist: string; rejectedAt: string }>;
  topArtistsOverTime: Array<{ artist: string; totalPlays: number }>;
  topTracksOverTime: LearnedTrackEntry[];
  recentInterventions: Array<{
    userSaid: string;
    agentReplied: string;
    action: string;
    targetTrack: string;
    satiety: number;
    createdAt: string;
  }>;
  circadianPatternNotes: string[];
}

export class SupabaseIntelligenceStore {
  private static inMemoryInterventions: AgentInterventionRecord[] = [];
  private static inMemoryApprovedTracks = new Map<string, { title: string; artist: string; spotifyUri?: string; approvedAt: string }>();
  private static inMemoryRejectedTracks = new Map<string, { title: string; artist: string; rejectedAt: string }>();
  private static inMemoryLearnedTracks = new Map<string, LearnedTrackEntry>();
  private static lastSupabaseHydrationMs = 0;

  public static normalizeUserId(rawUserId?: string, rawEmail?: string): string {
    const email = (rawEmail || '').trim().toLowerCase();
    if (email && email.includes('@')) return email;
    const uid = (rawUserId || '').trim();
    if (uid && uid !== 'anonymous' && uid !== 'null' && uid !== 'undefined') return uid;
    return 'tunemind-default-user';
  }

  /**
   * Syncs a batch of Spotify / OS listening history tracks from the Android phone into:
   * 1. Live Supabase (`listening_events`, `user_taste_genome`, and `agent_interventions` snapshot)
   * 2. Local persistent `EventStore`
   */
  public static async syncTracksAndTelemetryToSupabase(
    userId: string,
    tracks: Array<{ title: string; artist: string; trackId?: string; playCount?: number; source?: string }>,
    options?: {
      hearingTelemetry?: {
        estimated_db?: number;
        safe_headroom_db?: number;
        daily_exposure_minutes?: number;
        output_device?: string;
      };
      vaultTracks?: Array<{ title: string; artist: string; cooldown_days_remaining?: number; recovery_percent?: number }>;
      override?: SupabaseConnectionOverride;
    }
  ): Promise<{ syncedTracksCount: number; liveSupabaseStored: boolean }> {
    const cleanUserId = this.normalizeUserId(userId);
    const validTracks = (tracks || []).filter(
      t =>
        t &&
        t.title &&
        t.title.trim().length > 0 &&
        t.title !== 'Standing By' &&
        t.title !== 'Active Session' &&
        t.title !== 'Deep Flow State'
    );

    const nowIso = new Date().toISOString();

    for (const t of validTracks) {
      const key = `${t.title.toLowerCase().trim()}::${(t.artist || '').toLowerCase().trim()}`;
      const existing = this.inMemoryLearnedTracks.get(key);
      const count = Math.max(1, Number(t.playCount || 1));
      if (!existing) {
        this.inMemoryLearnedTracks.set(key, {
          title: t.title.trim(),
          artist: (t.artist || 'Unknown Artist').trim(),
          spotifyUri: t.trackId && t.trackId.startsWith('spotify:track:') ? t.trackId : undefined,
          playCount: count,
          source: t.source || 'com.spotify.music',
          lastPlayedAt: nowIso
        });
      } else {
        existing.playCount = Math.max(existing.playCount, count);
        if (!existing.spotifyUri && t.trackId && t.trackId.startsWith('spotify:track:')) {
          existing.spotifyUri = t.trackId;
        }
        existing.lastPlayedAt = nowIso;
      }
    }

    // Mirror into persistent EventStore
    try {
      const eventStore = new EventStore();
      eventStore.syncClientTracks(
        validTracks.map(t => ({
          title: t.title,
          artist: t.artist,
          source: t.source || 'com.spotify.music',
          playCount: t.playCount || 1,
          trackId: t.trackId
        }))
      );
    } catch (e) {
      console.warn('[SupabaseIntelligenceStore] Local EventStore mirror notice:', e);
    }

    const client = getLiveSupabaseClient(options?.override?.supabaseUrl, options?.override?.supabaseKey);
    if (!client) {
      return { syncedTracksCount: validTracks.length, liveSupabaseStored: false };
    }

    let storedToSupabase = false;

    // 1. Try upserting into `listening_events` table if present in Supabase
    if (validTracks.length > 0) {
      try {
        const rows = validTracks.slice(0, 40).map((t, idx) => ({
          event_id: `sync_${cleanUserId}_${t.title.toLowerCase().replace(/[^a-z0-9]/g, '_').slice(0, 32)}_${(t.artist || '').toLowerCase().replace(/[^a-z0-9]/g, '_').slice(0, 20)}`,
          user_id: cleanUserId,
          track_title: t.title.trim(),
          track_artist: (t.artist || 'Unknown Artist').trim(),
          spotify_uri: t.trackId || null,
          play_count: Math.max(1, Number(t.playCount || 1)),
          media_source: t.source || 'com.spotify.music',
          observed_at: new Date(Date.now() - idx * 1000).toISOString()
        }));
        const { error } = await client.from('listening_events').upsert(rows, { onConflict: 'event_id' });
        if (!error) {
          storedToSupabase = true;
        }
      } catch {
        // Table may not exist yet; handled below via universal agent_interventions + user_taste_genome
      }
    }

    // 2. Try upserting aggregated Taste Genome into `user_taste_genome` table if present
    try {
      const allLearned = Array.from(this.inMemoryLearnedTracks.values()).sort((a, b) => b.playCount - a.playCount);
      const topArtistsMap = new Map<string, number>();
      for (const trk of allLearned) {
        if (trk.artist && trk.artist !== 'Unknown Artist') {
          topArtistsMap.set(trk.artist, (topArtistsMap.get(trk.artist) || 0) + trk.playCount);
        }
      }
      const topArtists = Array.from(topArtistsMap.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 15)
        .map(([artist, plays]) => ({ artist, plays }));

      const { error } = await client.from('user_taste_genome').upsert(
        {
          user_id: cleanUserId,
          top_tracks_json: JSON.stringify(allLearned.slice(0, 40)),
          top_artists_json: JSON.stringify(topArtists),
          approved_tracks_json: JSON.stringify(Array.from(this.inMemoryApprovedTracks.values()).slice(-30)),
          rejected_tracks_json: JSON.stringify(Array.from(this.inMemoryRejectedTracks.values()).slice(-30)),
          hearing_telemetry_json: options?.hearingTelemetry ? JSON.stringify(options.hearingTelemetry) : null,
          vault_tracks_json: options?.vaultTracks ? JSON.stringify(options.vaultTracks) : null,
          updated_at: nowIso
        },
        { onConflict: 'user_id' }
      );
      if (!error) {
        storedToSupabase = true;
      }
    } catch {
      // Optional table
    }

    // 3. Universal guarantee: also store a compact telemetry snapshot in `agent_interventions`
    // (only when new tracks arrive and at most once every 3 minutes for background syncs so we don't spam rows)
    const nowMs = Date.now();
    if (validTracks.length > 0 && nowMs - this.lastSupabaseHydrationMs > 180_000) {
      this.lastSupabaseHydrationMs = nowMs;
      try {
        const topSummary = validTracks
          .slice(0, 10)
          .map(t => `${t.title} by ${t.artist} (${t.playCount || 1}x)${t.trackId ? ` [${t.trackId}]` : ''}`)
          .join(' | ');

        const { error } = await client.from('agent_interventions').insert({
          user_id: cleanUserId,
          trigger_transcript: 'SPOTIFY_HISTORY_TELEMETRY_SYNC',
          computed_satiety: 22,
          agent_tts_response: `Synced ${validTracks.length} Spotify tracks for long-term intelligence growth.`,
          os_intent: 'TELEMETRY_SYNC',
          target_search_query: topSummary.slice(0, 950)
        });
        if (!error) {
          storedToSupabase = true;
          console.log(`[SupabaseIntelligenceStore] ☁️ Persisted ${validTracks.length} Spotify tracks to live Supabase for ${cleanUserId}.`);
        }
      } catch (e) {
        console.warn('[SupabaseIntelligenceStore] Snapshot insert notice:', e);
      }
    }

    return { syncedTracksCount: validTracks.length, liveSupabaseStored: storedToSupabase };
  }

  /**
   * Logs every voice conversation turn, track proposal, track approval, or track rejection
   * to the live Supabase database (`agent_interventions` + `user_taste_genome`) so the AI grows smarter over time.
   */
  public static async recordAgentIntervention(
    record: AgentInterventionRecord,
    override?: SupabaseConnectionOverride
  ): Promise<boolean> {
    const cleanUserId = this.normalizeUserId(record.user_id);
    const nowIso = new Date().toISOString();

    const enrichedRecord: AgentInterventionRecord = {
      ...record,
      user_id: cleanUserId,
      created_at: nowIso
    };

    this.inMemoryInterventions.unshift(enrichedRecord);
    if (this.inMemoryInterventions.length > 100) {
      this.inMemoryInterventions = this.inMemoryInterventions.slice(0, 100);
    }

    // Track approvals vs rejections for long-term reinforcement learning
    const cleanTitle = (record.track_title || '').trim();
    const cleanArtist = (record.track_artist || '').trim();
    if (cleanTitle && cleanTitle !== 'Standing By') {
      const trackKey = `${cleanTitle.toLowerCase()}::${cleanArtist.toLowerCase()}`;
      if (record.action === 'PLAY_TRACK_NOW' || record.os_intent === 'OS_MEDIA_NEXT_AND_DEEP_LINK' || record.action === 'APPROVED_TRACK') {
        this.inMemoryApprovedTracks.set(trackKey, {
          title: cleanTitle,
          artist: cleanArtist,
          spotifyUri: record.spotify_uri,
          approvedAt: nowIso
        });
        this.inMemoryRejectedTracks.delete(trackKey);
      } else if (record.action === 'REJECTED_TRACK') {
        this.inMemoryRejectedTracks.set(trackKey, {
          title: cleanTitle,
          artist: cleanArtist,
          rejectedAt: nowIso
        });
      }
    }

    const client = getLiveSupabaseClient(override?.supabaseUrl, override?.supabaseKey);
    if (!client) {
      return false;
    }

    // Encode structured action & URI metadata inside target_search_query so even basic 6-column `agent_interventions` schemas preserve 100% of intelligence!
    const structuredTargetQuery = [
      record.target_search_query || `${cleanTitle} ${cleanArtist}`.trim(),
      record.action ? `action=${record.action}` : '',
      record.spotify_uri ? `uri=${record.spotify_uri}` : '',
      record.circadian_daypart ? `daypart=${record.circadian_daypart}` : ''
    ]
      .filter(Boolean)
      .join(' | ')
      .slice(0, 500);

    try {
      const { error } = await client.from('agent_interventions').insert({
        user_id: cleanUserId,
        trigger_transcript: (record.trigger_transcript || 'voice_interaction').slice(0, 500),
        computed_satiety: Math.round(Number(record.computed_satiety || 25)),
        agent_tts_response: (record.agent_tts_response || '').slice(0, 1000),
        os_intent: record.os_intent || record.action || 'NONE',
        target_search_query: structuredTargetQuery
      });

      if (error) {
        console.warn('[SupabaseIntelligenceStore] Supabase agent_interventions insert warning:', error.message);
        return false;
      }

      console.log(
        `[SupabaseIntelligenceStore] ✅ Logged intervention to live Supabase Taste Genome (user=${cleanUserId}, action=${record.action || record.os_intent})`
      );
      return true;
    } catch (err) {
      console.warn('[SupabaseIntelligenceStore] Failed to write intervention to Supabase:', err);
      return false;
    }
  }

  /**
   * Fetches the user's complete long-term memory & evolving taste intelligence from the live Supabase database,
   * combining `agent_interventions`, `listening_events`, and `user_taste_genome` with local memory.
   */
  public static async fetchUserLongTermMemory(
    userId?: string,
    override?: SupabaseConnectionOverride
  ): Promise<LongTermIntelligenceSummary> {
    const cleanUserId = this.normalizeUserId(userId);
    const client = getLiveSupabaseClient(override?.supabaseUrl, override?.supabaseKey);
    const liveConnected = Boolean(client && isSupabaseLive(override?.supabaseUrl, override?.supabaseKey));

    let supabaseInterventions: any[] = [];

    if (client) {
      try {
        // Fetch up to 35 most recent rows from live Supabase `agent_interventions`
        // Query both the specific user_id and 'tunemind-default-user' so historical rows are always included
        const userCandidates = Array.from(new Set([cleanUserId, 'tunemind-default-user']));
        const { data, error } = await client
          .from('agent_interventions')
          .select('user_id, trigger_transcript, computed_satiety, agent_tts_response, os_intent, target_search_query, created_at')
          .in('user_id', userCandidates)
          .order('created_at', { ascending: false })
          .limit(35);

        if (!error && Array.isArray(data)) {
          supabaseInterventions = data;
        }
      } catch (e) {
        console.warn('[SupabaseIntelligenceStore] Live Supabase query notice:', e);
      }

      // Also check `listening_events` table in Supabase if it exists
      try {
        const { data: eventRows, error: eventErr } = await client
          .from('listening_events')
          .select('track_title, track_artist, spotify_uri, play_count, media_source, observed_at')
          .eq('user_id', cleanUserId)
          .order('play_count', { ascending: false })
          .limit(50);

        if (!eventErr && Array.isArray(eventRows)) {
          for (const row of eventRows) {
            if (row.track_title) {
              const key = `${String(row.track_title).toLowerCase().trim()}::${String(row.track_artist || '').toLowerCase().trim()}`;
              const existing = this.inMemoryLearnedTracks.get(key);
              const count = Math.max(1, Number(row.play_count || 1));
              if (!existing) {
                this.inMemoryLearnedTracks.set(key, {
                  title: String(row.track_title).trim(),
                  artist: String(row.track_artist || 'Unknown Artist').trim(),
                  spotifyUri: row.spotify_uri || undefined,
                  playCount: count,
                  source: row.media_source || 'com.spotify.music',
                  lastPlayedAt: row.observed_at
                });
              } else {
                existing.playCount = Math.max(existing.playCount, count);
                if (!existing.spotifyUri && row.spotify_uri) existing.spotifyUri = row.spotify_uri;
              }
            }
          }
        }
      } catch {
        // Optional table
      }
    }

    // Parse historical interventions from Supabase to reconstruct approved tracks, rejected tracks, and synced tracks
    const recentInterventionsFormatted: LongTermIntelligenceSummary['recentInterventions'] = [];
    const circadianCounts: Record<string, Map<string, number>> = {};

    const combinedInterventions = [
      ...supabaseInterventions,
      ...this.inMemoryInterventions.slice(0, 15)
    ];

    const seenTimestamps = new Set<string>();
    for (const row of combinedInterventions) {
      const sig = `${row.trigger_transcript}::${row.agent_tts_response}::${String(row.created_at || '').slice(0, 16)}`;
      if (seenTimestamps.has(sig)) continue;
      seenTimestamps.add(sig);

      const trigger = String(row.trigger_transcript || '');
      const targetRaw = String(row.target_search_query || '');
      const intentRaw = String(row.os_intent || 'NONE');

      // If this row was a SPOTIFY_HISTORY_TELEMETRY_SYNC snapshot, parse any tracks embedded in target_search_query
      if (trigger === 'SPOTIFY_HISTORY_TELEMETRY_SYNC' || intentRaw === 'TELEMETRY_SYNC') {
        const parts = targetRaw.split('|').map(s => s.trim()).filter(Boolean);
        for (const p of parts) {
          const match = p.match(/^(.+?)\s+by\s+(.+?)\s+\((\d+)x\)(?:\s+\[(spotify:track:[^\]]+)\])?$/i);
          if (match) {
            const title = match[1].trim();
            const artist = match[2].trim();
            const plays = parseInt(match[3], 10) || 1;
            const uri = match[4];
            const key = `${title.toLowerCase()}::${artist.toLowerCase()}`;
            if (!this.inMemoryLearnedTracks.has(key)) {
              this.inMemoryLearnedTracks.set(key, {
                title,
                artist,
                spotifyUri: uri,
                playCount: plays,
                source: 'com.spotify.music'
              });
            }
          }
        }
        continue;
      }

      const cleanTarget = targetRaw.split('|')[0]?.trim() || '';
      const actionMatch = targetRaw.match(/action=([A-Z_]+)/);
      const uriMatch = targetRaw.match(/uri=(spotify:track:[A-Za-z0-9]+)/);
      const daypartMatch = targetRaw.match(/daypart=([A-Za-z\s]+)/);
      const effectiveAction = actionMatch?.[1] || intentRaw;

      if (cleanTarget && (effectiveAction === 'PLAY_TRACK_NOW' || intentRaw === 'OS_MEDIA_NEXT_AND_DEEP_LINK' || effectiveAction === 'APPROVED_TRACK')) {
        const key = cleanTarget.toLowerCase();
        if (!this.inMemoryApprovedTracks.has(key)) {
          this.inMemoryApprovedTracks.set(key, {
            title: cleanTarget,
            artist: '',
            spotifyUri: uriMatch?.[1],
            approvedAt: row.created_at || new Date().toISOString()
          });
        }
      } else if (cleanTarget && effectiveAction === 'REJECTED_TRACK') {
        const key = cleanTarget.toLowerCase();
        if (!this.inMemoryRejectedTracks.has(key)) {
          this.inMemoryRejectedTracks.set(key, {
            title: cleanTarget,
            artist: '',
            rejectedAt: row.created_at || new Date().toISOString()
          });
        }
      }

      if (daypartMatch && cleanTarget) {
        const dp = daypartMatch[1].trim();
        if (!circadianCounts[dp]) circadianCounts[dp] = new Map<string, number>();
        circadianCounts[dp].set(cleanTarget, (circadianCounts[dp].get(cleanTarget) || 0) + 1);
      }

      if (recentInterventionsFormatted.length < 12) {
        recentInterventionsFormatted.push({
          userSaid: trigger,
          agentReplied: String(row.agent_tts_response || ''),
          action: effectiveAction,
          targetTrack: cleanTarget,
          satiety: Number(row.computed_satiety || 25),
          createdAt: String(row.created_at || '')
        });
      }
    }

    const allLearnedTracks = Array.from(this.inMemoryLearnedTracks.values()).sort((a, b) => b.playCount - a.playCount);
    const artistTotals = new Map<string, number>();
    for (const t of allLearnedTracks) {
      if (t.artist && t.artist !== 'Unknown Artist') {
        artistTotals.set(t.artist, (artistTotals.get(t.artist) || 0) + t.playCount);
      }
    }

    const topArtistsOverTime = Array.from(artistTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 12)
      .map(([artist, totalPlays]) => ({ artist, totalPlays }));

    const circadianPatternNotes: string[] = [];
    for (const [dp, map] of Object.entries(circadianCounts)) {
      const topForDp = Array.from(map.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 3)
        .map(([trk]) => trk);
      if (topForDp.length > 0) {
        circadianPatternNotes.push(`${dp}: user frequently gravitates toward ${topForDp.join(', ')}`);
      }
    }

    const totalInterventions = Math.max(supabaseInterventions.length, recentInterventionsFormatted.length);
    const totalTracks = allLearnedTracks.length;
    const maturity: LongTermIntelligenceSummary['intelligenceMaturityLevel'] =
      totalInterventions >= 20 || totalTracks >= 35
        ? 'DEEP_NEURAL_SYNC'
        : totalInterventions >= 8 || totalTracks >= 15
        ? 'CALIBRATED'
        : totalInterventions >= 2 || totalTracks >= 5
        ? 'DEVELOPING'
        : 'SEEDING';

    return {
      userId: cleanUserId,
      isLiveSupabaseConnected: liveConnected,
      intelligenceMaturityLevel: maturity,
      totalHistoricalInterventions: totalInterventions,
      totalTracksLearned: totalTracks,
      approvedTracks: Array.from(this.inMemoryApprovedTracks.values()).slice(-15),
      rejectedTracks: Array.from(this.inMemoryRejectedTracks.values()).slice(-15),
      topArtistsOverTime,
      topTracksOverTime: allLearnedTracks.slice(0, 20),
      recentInterventions: recentInterventionsFormatted,
      circadianPatternNotes
    };
  }
}
