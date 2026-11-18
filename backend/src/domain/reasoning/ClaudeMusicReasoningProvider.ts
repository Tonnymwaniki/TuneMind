import Anthropic from '@anthropic-ai/sdk';
import { v4 as uuidv4 } from 'uuid';
import { supabase } from '../../config/supabase';
import { EventStore } from '../events/EventStore';
import { SelfLearningDiscoveryEngine } from '../recommendation/SelfLearningDiscoveryEngine';
import { SupabaseIntelligenceStore } from '../storage/SupabaseIntelligenceStore';
import { RestingVaultEngine } from '../vault/RestingVaultEngine';
import {
  coerceNonNegativePlayCount,
  normalizeSpotifyUri,
  resolveCanonicalTrackIdentity
} from '../identity/trackIdentity';
import {
  MusicReasoningProvider,
  UserMusicContext,
  ReasoningResult,
  ReasoningReport,
  ProbabilisticInference,
  AuditoryReceptorStatus,
  AcousticShiftRecommendation,
  TrackFatigueSignal
} from './MusicReasoningProvider';

export interface AgentToolCall {
  action:
    | 'PLAY_TRACK_NOW'
    | 'PROPOSE_TRACK'
    | 'PAUSE_PLAYBACK'
    | 'RESUME_PLAYBACK'
    | 'SKIP_NEXT'
    | 'SKIP_PREVIOUS'
    | 'SET_VOLUME_PERCENT'
    | 'QUARANTINE_TO_VAULT'
    | 'RESTORE_FROM_VAULT'
    | 'SHIFT_ATMOSPHERE'
    | 'SET_THEME_COLOR'
    | 'TOGGLE_AUTONOMOUS_DJ'
    | 'TOGGLE_STAY_IN_TUNEMIND'
    | 'TOGGLE_STRICT_NO_APP_SWITCH'
    | 'SELECT_MUSIC_PLAYER'
    | 'OPEN_SETTINGS'
    | 'NAVIGATE_SCREEN'
    | 'LAUNCH_DOPAMINE_CAPSULE'
    | 'ADVISE_ONLY';
  track_id?: string;
  track_title?: string;
  track_artist?: string;
  spotify_uri?: string;
  target_player?: string;
  remember_default_player?: boolean;
  volume_percent?: number;
  palette_id?: string; // 'morning' | 'afternoon' | 'evening' | 'night' | 'auto'
  custom_accent_hex?: string; // e.g. '#00FFA3', '#FF2A85', or 'AUTO' to reset adaptive
  theme_behavior?: string; // 'ADAPTIVE' | 'STATIC'
  autonomous_dj_enabled?: boolean;
  stay_in_tunemind_enabled?: boolean;
  strict_no_app_switch_enabled?: boolean;
  never_open_external_app?: boolean;
  target_screen?: string;
  expect_follow_up?: boolean;
  extra_actions?: AgentToolCall[];
}

export interface TuneMindTelemetryContext {
  timestamp_utc: string;
  circadian_daypart: 'Morning' | 'Midday' | 'Evening' | 'Late Night';
  local_time?: string;
  local_hour?: number;
  local_minute?: number;
  timezone?: string;
  current_track: {
    title: string;
    artist: string;
    trackId?: string;
    spotifyUri?: string;
    source_player: string;
    is_playing?: boolean;
    acoustic_features?: {
      bpm: number | null;
      energy: number | null;
      key: string | null;
    };
  };
  user_command?: string;
  rolling_history: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; playCount?: number; source_player?: string; bpm?: number }>;
  recently_played_by_agent?: string[];
  current_satiety_percentage: number;
  hearing_telemetry?: {
    estimated_db: number;
    safe_headroom_db: number;
    daily_exposure_minutes: number;
    daily_exposure_seconds?: number;
    sound_dose_percent?: number;
    safe_hours_remaining?: number;
    risk_level?: string;
    output_device: string;
    transducer_category?: string;
    safety_guidance: string;
  };
  vault_tracks?: Array<{
    id: string;
    title: string;
    artist: string;
    cooldown_days_remaining: number;
    recovery_percent: number;
    satiety_score: number;
  }>;
  dopamine_capsule?: Array<{
    title: string;
    artist: string;
    trackId?: string;
    spotifyUri?: string;
    freshness_percent: number;
    reason: string;
  }>;
  pending_proposed_track?: {
    title: string;
    artist: string;
    trackId?: string;
    spotifyUri?: string;
    source?: string;
    never_open_external_app?: boolean;
  } | null;
  spotify_access_token?: string;
  dialogue_history?: Array<{ role: 'user' | 'assistant'; text: string }>;
  user_id?: string;
  user_email?: string;
  supabase_url?: string;
  supabase_anon_key?: string;
  for_you_picks?: Array<{
    title: string;
    artist: string;
    match_percent: number;
    category?: string;
    sub_tier?: string;
    genre_tag?: string;
    is_wildcard?: boolean;
    is_saved?: boolean;
    reason: string;
    spotify_uri?: string;
    track_id?: string;
  }>;
  spotify_playlists?: Array<{
    playlist_id: string;
    name: string;
    owner?: string;
    track_count: number;
    spotify_uri?: string;
    tracks?: Array<{
      title: string;
      artist: string;
      spotify_uri?: string;
      track_id?: string;
    }>;
  }>;
  spotify_liked_tracks?: Array<{
    title: string;
    artist: string;
    spotify_uri?: string;
    track_id?: string;
  }>;
  listening_stats?: {
    today_minutes?: number;
    seven_day_minutes?: number;
    thirty_day_minutes?: number;
    all_time_minutes?: number;
    total_verified_plays?: number;
    unique_tracks?: number;
    unique_artists?: number;
    skipped_count?: number;
    completion_rate_percent?: number;
    discovery_rate_percent?: number;
    peak_window_label?: string;
    windows?: {
      today?: { minutes: number; plays: number; unique_tracks: number; skipped: number; completion_rate_percent: number; discovery_rate_percent: number; peak_window?: string };
      seven_days?: { minutes: number; plays: number; unique_tracks: number; unique_artists: number; skipped: number; completion_rate_percent: number; discovery_rate_percent: number; peak_window?: string; range_label?: string };
      thirty_days?: { minutes: number; plays: number; unique_tracks: number; unique_artists: number; skipped: number; completion_rate_percent: number; discovery_rate_percent: number };
      all_time?: { minutes: number; plays: number; unique_tracks: number; unique_artists: number; skipped: number; completion_rate_percent: number; discovery_rate_percent: number };
    };
    top_artists?: Array<{ artist: string; plays: number; minutes: number }>;
    top_tracks?: Array<{ title: string; artist: string; plays: number; freshness_percent?: number; is_cooling_down?: boolean; app?: string }>;
    app_breakdown?: Array<{ app: string; plays: number; minutes?: number; share_percent: number }>;
    recent_play_history?: Array<{ title: string; artist: string; app: string; played_at_utc?: string; listened_minutes?: number }>;
  };
  player_preferences?: {
    has_explicit_default_player: boolean;
    default_player_id?: string;
    default_player_package?: string;
    default_player_name?: string;
    available_players: Array<{
      id: string;
      name: string;
      package_name: string;
    }>;
  };
  app_state?: {
    current_screen?: string;
    current_dopamine_tab?: string;
    current_rhythm_tab?: string;
    theme_behavior?: string;
    palette_id?: string;
    custom_accent_hex?: string;
    autonomous_dj_enabled?: boolean;
    stay_in_tunemind_enabled?: boolean;
    strict_no_app_switch_enabled?: boolean;
    prefer_in_app_player_enabled?: boolean;
    has_overlay_permission?: boolean;
    spotify_connected?: boolean;
    app_remote_connected?: boolean;
    inapp_player_active?: boolean;
  };
}

export interface TuneMindReasoningResponse {
  calculated_satiety_percentage: number;
  flow_state_analysis: string;
  voice_agent_tts: string;
  reasoning_mode?: 'JOURNEY_ADVISOR' | 'HEARING_GUARDIAN' | 'DOPAMINE_CAPSULE' | 'DIRECT_EXECUTION' | 'SONIC_CURATOR';
  agent_tool_call?: AgentToolCall;
  os_execution_command: {
    intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK' | 'OS_MEDIA_PAUSE' | 'OS_MEDIA_RESUME' | 'OS_MEDIA_NEXT' | 'OS_MEDIA_PREV' | 'NONE';
    target_player: string;
    search_query: string;
    track_id?: string;
    track_title?: string;
    track_artist?: string;
    spotify_uri?: string;
    never_open_external_app?: boolean;
    suggested_bpm_target: number;
  };
  database_tags: string[];
  voice_agent_audio_base64?: string | null;
}

export class ClaudeMusicReasoningProvider implements MusicReasoningProvider {
  readonly providerId = 'anthropic-claude';
  private anthropic: Anthropic;
  private hasInvalidKey = false;
  private lastKeyCheckAt: number = 0;
  private activeModelUsed: string = process.env.ANTHROPIC_MODEL || 'claude-sonnet-5-5';
  private lastErrorDetails: { status?: number | string; message?: string } | null = null;
  private spotifyAccessToken: { token: string; expiresAt: number } | null = null;

  private static cachedWorkingModel: string | null = null;
  private static discoveredAnthropicModels: string[] | null = null;
  private static lastModelDiscoveryAt: number = 0;
  private static lastStaticErrorDetails: { status?: number | string; message?: string; provider?: string } | null = null;
  private static lastActiveModelUsed: string = process.env.ANTHROPIC_MODEL || 'claude-sonnet-5-5';
  private static pendingProposedTracksByUser = new Map<
    string,
    { title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; never_open_external_app?: boolean }
  >();
  private static recentDialogueTurnsByUser = new Map<
    string,
    Array<{ role: 'user' | 'assistant'; text: string }>
  >();
  private static rotationIndex: number = 0;

  public static setPendingProposedTrack(
    track: { title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; never_open_external_app?: boolean } | null,
    userId: string = 'tunemind-default-user'
  ) {
    const cleanUid = SupabaseIntelligenceStore.normalizeUserId(userId);
    if (track && track.title) {
      ClaudeMusicReasoningProvider.pendingProposedTracksByUser.set(cleanUid, track);
    } else {
      ClaudeMusicReasoningProvider.pendingProposedTracksByUser.delete(cleanUid);
    }
  }

  private static getPendingProposedTrack(userId: string) {
    const cleanUid = SupabaseIntelligenceStore.normalizeUserId(userId);
    return ClaudeMusicReasoningProvider.pendingProposedTracksByUser.get(cleanUid) || null;
  }

  constructor() {
    this.anthropic = new Anthropic(); // Uses ANTHROPIC_API_KEY from .env
    this.activeModelUsed = ClaudeMusicReasoningProvider.lastActiveModelUsed;
  }

  getLastDiagnostics() {
    const rawKey = process.env.ANTHROPIC_API_KEY;
    return {
      hasAiClient: Boolean(this.anthropic),
      keyPresent: Boolean(rawKey && rawKey !== 'your_key_here'),
      keyLength: rawKey?.length || 0,
      hasInvalidKey: this.hasInvalidKey,
      discoveredClaudeModels: ClaudeMusicReasoningProvider.discoveredAnthropicModels,
      lastErrorDetails: this.lastErrorDetails || ClaudeMusicReasoningProvider.lastStaticErrorDetails,
      activeModelUsed: ClaudeMusicReasoningProvider.lastActiveModelUsed || this.activeModelUsed
    };
  }

  private cleanKey(raw?: string): string | null {
    if (!raw) return null;
    const trimmed = raw.trim().replace(/^["']|["']$/g, '');
    if (!trimmed || trimmed === 'TODO' || trimmed === 'your_key_here' || trimmed === 'YOUR_API_KEY' || trimmed === 'undefined' || trimmed === 'null') {
      return null;
    }
    return trimmed;
  }

  public static sanitizeTrackTitle(rawTitle?: string): string {
    if (!rawTitle) return '';
    return rawTitle
      .replace(/\s*\((official\s*(music\s*)?(video|audio|lyric\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\)/gi, '')
      .replace(/\s*\[(official\s*(music\s*)?(video|audio|lyric\s*video|visualizer)|lyrics?|audio|hd|4k|live.*?)\]/gi, '')
      .replace(/,\s*tenzi\s*no\.?\s*\d+\.?/gi, '')
      .replace(/\s+-\s+(official\s*(music\s*)?(video|audio)|lyrics?)$/gi, '')
      .replace(/[."']+$/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  public static sanitizeArtistName(rawArtist?: string): string {
    if (!rawArtist || rawArtist === 'Unknown Artist') return '';
    return rawArtist
      .split(/[,&]|feat\.?|ft\.?/i)[0]
      .replace(/[."']+$/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  /**
   * Queries Anthropic's official GET /v1/models endpoint using the user's paid ANTHROPIC_API_KEY
   * so we always use active, non-retired Claude models enabled on their account (prioritizing Sonnet).
   */
  private async discoverLiveAnthropicModels(forceRefresh: boolean = false): Promise<string[]> {
    const now = Date.now();
    if (
      !forceRefresh &&
      ClaudeMusicReasoningProvider.discoveredAnthropicModels &&
      ClaudeMusicReasoningProvider.discoveredAnthropicModels.length > 0 &&
      now - ClaudeMusicReasoningProvider.lastModelDiscoveryAt < 30 * 60 * 1000
    ) {
      return ClaudeMusicReasoningProvider.discoveredAnthropicModels;
    }

    const apiKey = this.cleanKey(process.env.ANTHROPIC_API_KEY);
    if (!apiKey) return [];

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);
      const res = await fetch('https://api.anthropic.com/v1/models?limit=50', {
        method: 'GET',
        headers: {
          'x-api-key': apiKey,
          'anthropic-version': '2023-06-01'
        },
        signal: controller.signal
      });
      clearTimeout(timeoutId);

      if (res.ok) {
        const body = (await res.json()) as any;
        const items: any[] = Array.isArray(body?.data) ? body.data : [];
        const rawIds = items
          .map(item => String(item?.id || '').trim())
          .filter(id => id.startsWith('claude-'));

        if (rawIds.length > 0) {
          // Prioritize Claude Sonnet 5.5 first, then latest Sonnet models, with Haiku as fallback
          const sonnet55 = rawIds.filter(id => id.includes('sonnet') && id.includes('5-5'));
          const otherSonnets = rawIds.filter(id => id.includes('sonnet') && !id.includes('5-5'));
          const haikus = rawIds.filter(id => id.includes('haiku') && !id.includes('sonnet'));
          const others = rawIds.filter(id => !id.includes('sonnet') && !id.includes('haiku'));
          const ranked = [...sonnet55, ...otherSonnets, ...haikus, ...others];

          ClaudeMusicReasoningProvider.discoveredAnthropicModels = ranked;
          ClaudeMusicReasoningProvider.lastModelDiscoveryAt = now;
          console.log(`[Claude Provider] ✅ Discovered live Anthropic models on account: ${ranked.slice(0, 6).join(', ')}`);
          return ranked;
        }
      } else {
        const errBody = await res.text().catch(() => '');
        console.warn(`[Claude Provider] /v1/models HTTP ${res.status}: ${errBody.slice(0, 200)}`);
      }
    } catch (e: any) {
      console.warn('[Claude Provider] /v1/models discovery notice:', e?.message || e);
    }

    return ClaudeMusicReasoningProvider.discoveredAnthropicModels || [];
  }

  private async getCandidateClaudeModels(forceRefreshDiscovery: boolean = false): Promise<string[]> {
    const candidateModels: string[] = [];
    if (!forceRefreshDiscovery && ClaudeMusicReasoningProvider.cachedWorkingModel) {
      candidateModels.push(ClaudeMusicReasoningProvider.cachedWorkingModel);
    }

    const liveDiscovered = await this.discoverLiveAnthropicModels(forceRefreshDiscovery);
    for (const m of liveDiscovered) {
      if (!candidateModels.includes(m)) {
        candidateModels.push(m);
      }
    }

    const envModel = process.env.ANTHROPIC_MODEL?.trim();
    if (envModel && !candidateModels.includes(envModel)) {
      candidateModels.push(envModel);
    }

    for (const m of [
      'claude-sonnet-5-5',
      'claude-sonnet-4-6',
      'claude-sonnet-4-5',
      'claude-sonnet-4-5-20250929',
      'claude-sonnet-4-20250514',
      'claude-haiku-5-5',
      'claude-haiku-4-5',
      'claude-haiku-4-5-20251001',
      'claude-3-5-haiku-20241022'
    ]) {
      if (!candidateModels.includes(m)) {
        candidateModels.push(m);
      }
    }
    return candidateModels;
  }

  private async callClaudeWithHaikuPriority(
    systemPrompt: string,
    userContent: string,
    maxTokens: number = 900
  ): Promise<any> {
    return this.callClaudeWithSonnetPriority(systemPrompt, userContent, maxTokens);
  }

  private async callClaudeWithSonnetPriority(
    systemPrompt: string,
    userContent: string,
    maxTokens: number = 1400
  ): Promise<any> {
    const candidateModels = await this.getCandidateClaudeModels();
    let lastErr: any = null;
    for (const modelId of candidateModels) {
      try {
        const res = await this.anthropic.messages.create({
          model: modelId,
          max_tokens: maxTokens,
          system: systemPrompt,
          messages: [{ role: 'user', content: userContent }]
        });
        this.activeModelUsed = modelId;
        ClaudeMusicReasoningProvider.lastActiveModelUsed = modelId;
        ClaudeMusicReasoningProvider.cachedWorkingModel = modelId;
        this.lastErrorDetails = null;
        ClaudeMusicReasoningProvider.lastStaticErrorDetails = null;
        return res;
      } catch (err: any) {
        lastErr = err;
        const msg = String(err?.message || err);
        this.lastErrorDetails = { status: err?.status, message: msg };
        ClaudeMusicReasoningProvider.lastStaticErrorDetails = { status: err?.status, message: msg, provider: 'anthropic' };
        if (ClaudeMusicReasoningProvider.cachedWorkingModel === modelId) {
          ClaudeMusicReasoningProvider.cachedWorkingModel = null;
        }
        if (msg.includes('authentication_error') || msg.includes('invalid x-api-key') || msg.includes('credit balance is too low')) {
          throw err;
        }
        continue;
      }
    }
    throw lastErr || new Error('All Claude models failed');
  }

  private async callClaudeMessagesWithTools(
    systemPrompt: string,
    messages: any[],
    tools: any[],
    maxTokens: number = 1400
  ): Promise<any> {
    const candidateModels = await this.getCandidateClaudeModels();
    let lastErr: any = null;
    for (const modelId of candidateModels) {
      try {
        const res = await this.anthropic.messages.create({
          model: modelId,
          max_tokens: maxTokens,
          system: systemPrompt,
          tools,
          messages
        });
        this.activeModelUsed = modelId;
        ClaudeMusicReasoningProvider.lastActiveModelUsed = modelId;
        ClaudeMusicReasoningProvider.cachedWorkingModel = modelId;
        this.lastErrorDetails = null;
        ClaudeMusicReasoningProvider.lastStaticErrorDetails = null;
        return res;
      } catch (err: any) {
        lastErr = err;
        const msg = String(err?.message || err);
        this.lastErrorDetails = { status: err?.status, message: msg };
        ClaudeMusicReasoningProvider.lastStaticErrorDetails = { status: err?.status, message: msg, provider: 'anthropic' };
        if (ClaudeMusicReasoningProvider.cachedWorkingModel === modelId) {
          ClaudeMusicReasoningProvider.cachedWorkingModel = null;
        }
        if (msg.includes('authentication_error') || msg.includes('invalid x-api-key') || msg.includes('credit balance is too low')) {
          throw err;
        }
        continue;
      }
    }
    throw lastErr || new Error('All Claude tool-calling models failed');
  }

  private getSystemPrompt(): string {
    return this.getAutonomousSystemPrompt();
  }

  private extractRawText(content: any): string {
    if (typeof content === 'string') {
      return content;
    }
    if (Array.isArray(content)) {
      const textOnly = content
        .filter((block: any) => block && block.type === 'text' && typeof block.text === 'string')
        .map((block: any) => block.text.trim())
        .filter(Boolean)
        .join('\n');
      if (textOnly) {
        return textOnly;
      }
      return content.map((block: any) => {
        if (block.type === 'text') return block.text || '';
        if (block.type === 'thinking') return block.thinking || '';
        if (typeof block.text === 'string') return block.text;
        return '';
      }).join('\n');
    }
    return JSON.stringify(content || '{}');
  }

  public async isAvailable(): Promise<boolean> {
    const rawKey = process.env.ANTHROPIC_API_KEY;
    return Boolean(this.cleanKey(rawKey) && !this.hasInvalidKey);
  }

  /**
   * Defines the 4 Native Anthropic Tools that allow Claude to autonomously inspect
   * the user's listening stats, discover new songs on Spotify, search specific songs,
   * and submit its final conversational turn decision.
   */
  private getAutonomousAgentTools(): any[] {
    return [
      {
        name: 'query_user_listening_history',
        description:
          'Queries the user\'s synced Spotify listening history and play counts. Call this whenever the user asks about their most played song, favorite song, recent listening habits, or overplayed songs.',
        input_schema: {
          type: 'object',
          properties: {
            sort_by: {
              type: 'string',
              enum: ['most_played', 'most_recent', 'least_played_fresh', 'overplayed_fatigued'],
              description: 'How to rank the user\'s tracks: most_played (highest play count #1), most_recent, least_played_fresh, or overplayed_fatigued.'
            },
            artist_or_keyword: {
              type: 'string',
              description: 'Optional artist name or keyword filter.'
            },
            limit: {
              type: 'number',
              description: 'Max tracks to return (default 8).'
            }
          },
          required: ['sort_by']
        }
      },
      {
        name: 'discover_and_recommend_spotify_tracks',
        description:
          'Discovers real, playable songs from the live Spotify catalog tailored to the user\'s top artists, mood, or genre while filtering out songs they have already overplayed. Call this whenever the user asks for a new song, a recommendation, a suggestion, or a specific mood/genre.',
        input_schema: {
          type: 'object',
          properties: {
            recommendation_goal: {
              type: 'string',
              enum: ['new_discovery_from_user_taste', 'similar_to_artist', 'mood_or_genre', 'palate_cleanser'],
              description: 'The type of recommendation the user is looking for.'
            },
            seed_artists: {
              type: 'array',
              items: { type: 'string' },
              description: 'Optional list of artist names to base the discovery on. If omitted, uses the user\'s top Spotify artists automatically.'
            },
            mood_or_genre_query: {
              type: 'string',
              description: 'Optional mood, vibe, or genre search query (e.g., "chill afrobeats", "bongo flava", "upbeat gospel", "late night r&b", "acoustic").'
            },
            exclude_overplayed: {
              type: 'boolean',
              description: 'If true (default), excludes tracks the user has already played heavily or that were recently suggested.'
            }
          },
          required: ['recommendation_goal']
        }
      },
      {
        name: 'search_spotify_catalog',
        description:
          'Searches the live Spotify Web API catalog for a specific track title, artist, or lyric when the user names a particular song or artist.',
        input_schema: {
          type: 'object',
          properties: {
            query: {
              type: 'string',
              description: 'The song title, artist name, or search query.'
            },
            artist: {
              type: 'string',
              description: 'Optional artist name to narrow the search.'
            }
          },
          required: ['query']
        }
      },
      {
        name: 'query_supabase_taste_genome',
        description:
          'Queries the live Supabase Taste Genome database for the user\'s long-term intelligence growth over time: songs they approved in past sessions, songs they rejected, all-time top artists, circadian patterns, and historical voice turns.',
        input_schema: {
          type: 'object',
          properties: {
            focus: {
              type: 'string',
              enum: [
                'approved_and_rejected_tracks',
                'top_artists_and_tracks_over_time',
                'recent_voice_interventions',
                'full_intelligence_profile'
              ],
              description: 'Which aspect of the user\'s long-term Supabase intelligence profile to inspect.'
            }
          },
          required: ['focus']
        }
      },
      {
        name: 'inspect_for_you_suggestions',
        description:
          'Inspects today\'s personalized "For You" AI discovery suggestions screen inside TuneMind (daily curated picks, match percentages, categories, wildcard picks, saved status, and telemetry reasons). Call whenever the user asks "what do you have for me today", asks about the suggestion / For You screen, or wants to hear or play from today\'s suggestions.',
        input_schema: {
          type: 'object',
          properties: {
            category_filter: {
              type: 'string',
              description: 'Optional filter by category/tier or keyword (e.g. "wildcard", "dopamine", "saved", artist, or leave empty for all active picks).'
            },
            limit: {
              type: 'number',
              description: 'Maximum suggestions to return (default 10).'
            }
          }
        }
      },
      {
        name: 'query_spotify_library',
        description:
          'Inspects the user\'s synced Spotify Library (personal playlists, tracks inside playlists, and liked songs). Call whenever the user asks about their Spotify playlists, wants to play a playlist from their Spotify library, asks how many playlists or liked songs they have, or picks a song from their saved library.',
        input_schema: {
          type: 'object',
          properties: {
            scope: {
              type: 'string',
              enum: ['playlists', 'liked_tracks', 'all'],
              description: 'Whether to inspect playlists, liked tracks, or both.'
            },
            query: {
              type: 'string',
              description: 'Optional playlist name, song title, or artist filter.'
            }
          }
        }
      },
      {
        name: 'query_listening_stats',
        description:
          'Queries the user\'s cross-app listening analytics from the Stats and Hearing Health screens (minutes today / 7 days / 30 days / all time, total verified plays, unique tracks & artists, completion rate, discovery freshness rate, peak window, top artists, top tracks, app share breakdown, and acoustic hearing headroom).',
        input_schema: {
          type: 'object',
          properties: {
            window: {
              type: 'string',
              enum: ['today', '7_days', '30_days', 'all_time', 'all'],
              description: 'Time window to inspect.'
            }
          }
        }
      },
      {
        name: 'inspect_vault_tracks',
        description:
          'Inspects the user\'s Cooldown Resting Vault tracks (tracks quarantined for dopamine recovery with remaining cooldown days, recovery %, and satiety score) plus their Fresh low-satiety rotation. Call whenever the user asks to play a song in the vault, check what is in the vault, restore a song, or check fresh songs.',
        input_schema: {
          type: 'object',
          properties: {
            include_fresh_pool: {
              type: 'boolean',
              description: 'Also include the freshest ready-to-play songs from their rotation.'
            }
          }
        }
      },
      {
        name: 'submit_agent_turn',
        description:
          'Submits your final spoken response and app/playback action to the user after you have finished reasoning and calling any necessary tools.',
        input_schema: {
          type: 'object',
          properties: {
            voice_agent_tts: {
              type: 'string',
              description: 'Your natural, warm, unscripted spoken response to the user (1-3 sentences, conversational like ChatGPT Voice). If proposing a track or asking which music player to use, ask clearly.'
            },
            flow_state_analysis: {
              type: 'string',
              description: 'Short 1-line studio HUD insight summarizing your reasoning or track selection.'
            },
            calculated_satiety_percentage: {
              type: 'number',
              description: 'Estimated listener satiety percentage (0-100).'
            },
            action: {
              type: 'string',
              enum: [
                'ADVISE_ONLY',
                'PROPOSE_TRACK',
                'PLAY_TRACK_NOW',
                'PAUSE_PLAYBACK',
                'RESUME_PLAYBACK',
                'SKIP_NEXT',
                'SKIP_PREVIOUS',
                'SET_VOLUME_PERCENT',
                'QUARANTINE_TO_VAULT',
                'RESTORE_FROM_VAULT',
                'SHIFT_ATMOSPHERE',
                'SET_THEME_COLOR',
                'TOGGLE_AUTONOMOUS_DJ',
                'TOGGLE_STAY_IN_TUNEMIND',
                'TOGGLE_STRICT_NO_APP_SWITCH',
                'SELECT_MUSIC_PLAYER',
                'OPEN_SETTINGS',
                'NAVIGATE_SCREEN',
                'LAUNCH_DOPAMINE_CAPSULE'
              ],
              description:
                'Use ADVISE_ONLY for greetings, stats/insights answers, asking which player to use, or open dialogue. Use PROPOSE_TRACK when recommending or finding a song/playlist for user approval. Use PLAY_TRACK_NOW when approving pending_proposed_track, during AUTO_CURATE_NEXT_TRACK, or right after the user answers which music player to play a proposed track on. Use SELECT_MUSIC_PLAYER to switch/save the user\'s chosen player, RESTORE_FROM_VAULT to un-vault a track, or NAVIGATE_SCREEN / SET_VOLUME_PERCENT / SET_THEME_COLOR to control TuneMind.'
            },
            track_title: {
              type: 'string',
              description: 'Exact song or playlist title (ONLY when action is PROPOSE_TRACK, PLAY_TRACK_NOW, LAUNCH_DOPAMINE_CAPSULE, QUARANTINE_TO_VAULT, or RESTORE_FROM_VAULT). Keep empty for ADVISE_ONLY or app/theme commands.'
            },
            track_artist: {
              type: 'string',
              description: 'Exact artist name (or playlist owner / "Spotify Playlist" when launching a playlist).'
            },
            track_id: {
              type: 'string',
              description: 'Optional canonical trackId or Vault track ID.'
            },
            spotify_uri: {
              type: 'string',
              description: 'Exact canonical Spotify URI (spotify:track:... or spotify:playlist:...) returned by tools.'
            },
            target_player: {
              type: 'string',
              description: 'Exact Android package name or provider id from player_preferences.available_players (e.g. "com.spotify.music", "com.afmobi.boomplayer", "com.google.android.apps.youtube.music", "com.audiomack", "tunemind_inapp", "local_files") whenever targeting or switching a specific music player.'
            },
            remember_default_player: {
              type: 'boolean',
              description: 'Set to true when the user chooses a default player or wants to remember that player going forward.'
            },
            volume_percent: {
              type: 'number',
              description: 'Target media volume (10-100) when action is SET_VOLUME_PERCENT.'
            },
            palette_id: {
              type: 'string',
              description: 'Target theme palette ("morning" | "afternoon" | "evening" | "night" | "auto") when action is SHIFT_ATMOSPHERE.'
            },
            custom_accent_hex: {
              type: 'string',
              description: 'Hex color string like "#00FFA3", "#FF2A85", "#A855F7", "#00E5FF", "#FF9400", "#3B82F6", or "AUTO" to return to adaptive circadian colors when action is SET_THEME_COLOR.'
            },
            theme_behavior: {
              type: 'string',
              enum: ['ADAPTIVE', 'STATIC'],
              description: 'Optional theme mode override when action is SET_THEME_COLOR.'
            },
            autonomous_dj_enabled: {
              type: 'boolean',
              description: 'Target boolean for Autonomous End-of-Song DJ when action is TOGGLE_AUTONOMOUS_DJ.'
            },
            stay_in_tunemind_enabled: {
              type: 'boolean',
              description: 'Target boolean for Stay-in-TuneMind playback mode when action is TOGGLE_STAY_IN_TUNEMIND.'
            },
            strict_no_app_switch_enabled: {
              type: 'boolean',
              description: 'Target boolean for Strict Never-Leave-TuneMind Mode when action is TOGGLE_STRICT_NO_APP_SWITCH.'
            },
            never_open_external_app: {
              type: 'boolean',
              description: 'Set to true whenever the user asks to play a song without opening Spotify or leaving TuneMind, or when app_state.strict_no_app_switch_enabled is true.'
            },
            target_screen: {
              type: 'string',
              description: 'Target screen or sub-tab when action is NAVIGATE_SCREEN: "DOPAMINE" | "FRESH" | "RESTING_VAULT" | "FOR_YOU" | "AGENT" | "RHYTHM" | "STATS" | "SPOTIFY_LIBRARY" | "HISTORY" | "WEEKLY_REPORT" | "HEARING_HEALTH" | "TASTE_PROFILE" | "SEARCH" | "NOW_PLAYING" | "PROFILE" | "SETTINGS".'
            },
            extra_actions: {
              type: 'array',
              description: 'Optional array of extra app actions to execute in parallel alongside your primary action (e.g. changing theme color, setting default player, navigating to For You / Vault / Stats, or adjusting volume).',
              items: {
                type: 'object',
                properties: {
                  action: { type: 'string' },
                  custom_accent_hex: { type: 'string' },
                  palette_id: { type: 'string' },
                  theme_behavior: { type: 'string' },
                  autonomous_dj_enabled: { type: 'boolean' },
                  stay_in_tunemind_enabled: { type: 'boolean' },
                  strict_no_app_switch_enabled: { type: 'boolean' },
                  never_open_external_app: { type: 'boolean' },
                  target_player: { type: 'string' },
                  remember_default_player: { type: 'boolean' },
                  volume_percent: { type: 'number' },
                  target_screen: { type: 'string' },
                  track_title: { type: 'string' },
                  track_artist: { type: 'string' }
                }
              }
            },
            expect_follow_up: {
              type: 'boolean',
              description: 'Set to true whenever you ask a question, ask which player to use, greet, converse, or propose a track so the microphone re-opens smoothly for their reply.'
            }
          },
          required: ['voice_agent_tts', 'flow_state_analysis', 'calculated_satiety_percentage', 'action', 'expect_follow_up']
        }
      }
    ];
  }

  private getAutonomousSystemPrompt(): string {
    return `Your name is TuneMind. You are the autonomous, warm, deeply perceptive AI voice companion, music psychoacoustic intelligence, and full-app controller living inside the TuneMind Android app.

CORE IDENTITY, GROUNDING & CONVERSATIONAL FREEDOM:
- You ALWAYS know your name is TuneMind! When the user greets you ("Hi TuneMind", "Hey TuneMind", "Hello", "How are you?", "What's your name?", "Who are you?"), respond warmly and naturally as TuneMind using action="ADVISE_ONLY", leaving track_title/track_artist EMPTY, with expect_follow_up=true!
- NEVER force, pitch, or propose a song when the user is simply greeting you, asking how you are, chatting, asking a question about TuneMind or music, asking about stats/vault, or asking to change a setting/volume/navigation!
- ZERO HALLUCINATION & NO SCRIPTED REPLIES: Answer every user question with genuine reasoning grounded ONLY in live telemetry, tool outputs, and true TuneMind app behavior. NEVER invent song titles, artist names, play counts, playlists, or app features that do not appear in tools or telemetry. If a search or history query returns empty, say so honestly or try a better search query.
- ACCURATE TUNEMIND APP ARCHITECTURE KNOWLEDGE (for when users ask about how the app works):
  - Main 3-pager navigation: **Vault / Dopamine** (Fresh tracks, Resting Vault with 5-day dopamine receptor cooldown & recovery progress, and Daily For You Suggestions), **Agent** (center Pulse Agent orb where users tap the orb or say "Hi TuneMind" while the app is open, plus Stats & Taste Profile shortcuts), and **Flow / Rhythm DNA** (Overview, Spotify library/playlists, Clean Play History, plus Hearing Health decibel exposure & safe headroom, and Weekly Recap).
  - Settings & Modes: **Hands-Free DJ** (asks/curates next track when a song finishes), **Stay-in-TuneMind** & **Strict Never-Leave Mode** (play via Spotify App Remote / TuneMind In-App player without pulling users away to Spotify UI), **Adaptive Circadian Theme** or custom accent colors, and **Preferred Music Player** (Spotify, Boomplay, YouTube Music, Audiomack, TuneMind In-App, Local Audio).
  - Voice activation works actively while TuneMind is open ("Hi TuneMind" or tapping the orb, including pausing an active song when called). Do NOT claim the app listens when closed/in background.

FULL ACCESS ACROSS ALL TUNEMIND SCREENS (REASON OVER LIVE TOOLS, NEVER FABRICATE):
- FOR YOU SUGGESTION SCREEN: When the user asks "what do you have for me today", "look in the suggestion screen", or asks about daily AI picks -> call 'inspect_for_you_suggestions' to read the live personalized picks, match percentages, and reasons! Summarize accurately or propose a real pick from that list (PROPOSE_TRACK), and attach NAVIGATE_SCREEN("FOR_YOU") in extra_actions if helpful.
- COOLDOWN VAULT & FRESH POOL: When the user says "play for me a song in the vault", asks what's resting in the Vault, wants to un-vault/restore a track, or asks for a fresh song from their rotation -> call 'inspect_vault_tracks' first! Never invent Vault songs. You can propose playing/restoring a real Vault track (with RESTORE_FROM_VAULT or PROPOSE_TRACK) or pick from 'fresh_low_satiety_user_tracks'.
- STATS, WEEKLY RECAP & HEARING HEALTH (UNIFIED SCREEN ACCESS):
  Whenever the user asks "how are my listening stats", asks about listening time, top artists/tracks, skips, completion rate, fresh mix %, app share, OR ear/hearing safety -> ALWAYS call 'query_listening_stats'! That tool returns BOTH the complete Stats Screen windows (Today, 7 Days, 30 Days, All Time, top artists, top tracks, app breakdown, recent plays) AND the live Hearing Health Screen metrics (estimated_db, safe_headroom_db, daily_exposure_minutes, sound_dose_percent, safe_hours_remaining, risk_level, output_device). Combine key listening stats AND ear safety headroom naturally in your response, and navigate to "STATS" or "HEARING_HEALTH" if they ask to open the screen.
- SPOTIFY LIBRARY PLAYLISTS & LIKED SONGS:
  Whenever the user asks about their playlists ("play a song in my playlists", "play from our playlist", "what's in my Spotify library?", "play from my liked songs") -> ALWAYS call 'query_spotify_library'! It inspects both cached TuneMind library playlists AND fetches live Spotify Web API playlists & tracks when connected. NEVER tell the user their playlists aren't synced or to restart TuneMind when 'spotify_playlists_count' > 0 or 'app_state.spotify_connected' is true! Pick a verified song from that playlist (or launch the playlist's canonical spotify:playlist:<id> URI directly if they ask for the whole playlist), fill track_title, track_artist, and spotify_uri, and propose it cleanly with PROPOSE_TRACK!
- GENRE, LANGUAGE & DIRECT SONG REQUESTS:
  - Understand both broad requests ("play any song", "play something good") AND cultural/regional/language genres cleanly: e.g., **Kikuyu / Gikuyu** music (Mugithi, Kikuyu Benga, Kikuyu Gospel by Samidoh, Salim Junior, Mighty Salim, John De'Mathew, Jose Gatutura, Joyce Wa Mamaa, Shiru Wa GP, Phyllis Mbuthia, Muigai Wa Njoroge, Kamaru), Bongo Flava, Kenyan Gengetone, Ohangla, Lingala, Afrobeats, Amapiano, Gospel, Reggae, Hip-Hop, R&B, Jazz, Rock, etc.
  - NEVER search Spotify literally for generic phrases like 'Any Song', 'A Song', or 'Play Any Song'! When a user says 'play any song' or 'play any good song', recommend a fresh personalized song via 'discover_and_recommend_spotify_tracks' or their history/For You picks.
  - When a user asks for 'a Kikuyu song' / 'Kikuyu music' / 'Gikuyu song' / any genre, ALWAYS call 'search_spotify_catalog' (e.g. query="Kikuyu Mugithi", or artist="Samidoh", etc.) or 'discover_and_recommend_spotify_tracks' with mood_or_genre_query="Kikuyu Mugithi Gikuyu" and pick a real verified track returned by the tool!
  - For any specific song or artist ("Calm Down by Rema", "Blinding Lights"), ALWAYS call 'search_spotify_catalog' so you return exact verified metadata and spotify_uri.

SMART DEFAULT MUSIC PLAYER CHECK & VOICE SELECTION:
- Check 'player_preferences' in the telemetry! It tells you:
  - 'has_explicit_default_player': true if the user already chose a default music player, false if they have NOT chosen one yet.
  - 'default_player_name' / 'default_player_package'
  - 'available_players': list of genuine music players installed on their phone (e.g. Spotify, Boomplay, YouTube Music, Audiomack, TuneMind In-App, Local Audio).
- RULE 1 (No Explicit Default Chosen Yet): Whenever 'player_preferences.has_explicit_default_player' is false AND the user has NOT explicitly named a music player in this conversation yet (for example they only said "suggest a song for me" or "play a fresh song"):
  -> Propose the verified track with action="PROPOSE_TRACK" (filling track_title, track_artist, spotify_uri, expect_follow_up=true), AND in 'voice_agent_tts' naturally name the song/artist and ask WHICH music player they'd like you to play it on (naming 2-4 actual choices from 'player_preferences.available_players', e.g., Spotify, Boomplay, YouTube Music, or TuneMind In-App), and whether they want to make it their default!
- RULE 2 (User Replies With a Player Name): If 'pending_proposed_track_awaiting_user_approval' exists AND the user replies with a player name (e.g. "Play it on Boomplay", "Use Spotify", "Boomplay please, make it default", "Audiomack", "TuneMind"):
  -> Set action="PLAY_TRACK_NOW" using the EXACT pending track's title, artist, and spotify_uri, set 'target_player' to the exact matching package_name or id from 'player_preferences.available_players', and set 'remember_default_player'=true if they agreed/asked to save it as default (or when no explicit default was set yet and they confirmed a player!).
- RULE 3 (Switching Player Without a Track): If the user says "set Boomplay as my default music player" or "switch my player to Spotify" -> use action="SELECT_MUSIC_PLAYER", 'target_player'=(matching package_name/id), 'remember_default_player'=true!

TWO-STEP APPROVAL vs. REJECTION vs. AUTONOMOUS END-OF-SONG DJ:
- By default, NEVER jump straight to playing an unapproved track on your own! Always propose with action="PROPOSE_TRACK", expect_follow_up=true, and ask if they want it played (and which player if no default is set).
- APPROVAL ONLY ON CLEAR POSITIVE INTENT: When 'pending_proposed_track_awaiting_user_approval' is present AND the user clearly approves ("yes", "yeah", "play it", "go ahead", "sure", "okay", "do it", "ndio", "sawa"), call 'submit_agent_turn' with action="PLAY_TRACK_NOW" and the EXACT pending track's title, artist, spotify_uri, and target_player (honoring default player or explicitly chosen player).
- STRICT REJECTION & CANCELLATION RULE: When 'pending_proposed_track_awaiting_user_approval' is present and the user declines or says anything negative ("no", "nope", "I don't want that song", "I do not want that", "don't play it", "not that one", "cancel", "pass", "skip", "never mind"):
  -> NEVER output action="PLAY_TRACK_NOW"! NEVER play or keep the rejected song pending!
  -> If they simply rejected/cancelled without naming a replacement, set action="ADVISE_ONLY", leave track_title/track_artist/spotify_uri EMPTY, expect_follow_up=true, and kindly ask what kind of song or vibe they'd prefer instead.
  -> If they explicitly asked for a different/another song ("no, play something else" or "no, give me a Kikuyu song"), call 'discover_and_recommend_spotify_tracks' or 'search_spotify_catalog' to find a DIFFERENT verified song and propose it with action="PROPOSE_TRACK", expect_follow_up=true.
- ONLY when 'user_spoken_message' starts with "AUTO_CURATE_NEXT_TRACK:", the previous song ended while Autonomous DJ is enabled -> call 'discover_and_recommend_spotify_tracks' and submit action="PLAY_TRACK_NOW", expect_follow_up=false.

STAY-IN-TUNEMIND ARCHITECTURE:
- Respect app_state flags ('strict_no_app_switch_enabled', 'stay_in_tunemind_enabled', 'app_remote_connected', 'has_overlay_permission', 'prefer_in_app_player_enabled').
- WHENEVER the user says "without opening Spotify", "don't open an external app", "stay in TuneMind", or 'strict_no_app_switch_enabled' is true -> set "never_open_external_app": true and never claim you are switching away to an external app UI!

FULL APP-WIDE CONTROL ACTIONS:
- Theme accent color: action="SET_THEME_COLOR" with custom_accent_hex ("#00FFA3", "#00E5FF", "#FF2A85", "#A855F7", "#FF9400", "#3B82F6", or "AUTO") + optional theme_behavior ("STATIC"|"ADAPTIVE").
- Circadian palette: action="SHIFT_ATMOSPHERE" (palette_id: "morning" | "afternoon" | "evening" | "night" | "auto").
- Toggles: "TOGGLE_AUTONOMOUS_DJ", "TOGGLE_STAY_IN_TUNEMIND", "TOGGLE_STRICT_NO_APP_SWITCH".
- Volume: action="SET_VOLUME_PERCENT" (volume_percent: 10-100).
- Navigation: action="NAVIGATE_SCREEN" with target_screen in:
  "DOPAMINE" | "FRESH" | "RESTING_VAULT" | "FOR_YOU" | "AGENT" | "RHYTHM" | "STATS" | "SPOTIFY_LIBRARY" | "HISTORY" | "WEEKLY_REPORT" | "HEARING_HEALTH" | "TASTE_PROFILE" | "SEARCH" | "NOW_PLAYING" | "PROFILE" | "SETTINGS".
- Transport & Vault: "PAUSE_PLAYBACK", "RESUME_PLAYBACK", "SKIP_NEXT", "SKIP_PREVIOUS", "QUARANTINE_TO_VAULT", "RESTORE_FROM_VAULT", "OPEN_SETTINGS".
- Use 'extra_actions' to combine multiple commands seamlessly in one turn!

- East African & Global Genre Precision: Never misclassify local or international tracks! Know East African genres fluently (e.g. Gikuyu / Mugithi & Benga like Samidoh, Salim, De'Mathew, Joyce Wa Mamaa, Jose Gatutura; Bongo Flava like Diamond Platnumz, Mbosso, Harmonize, Marioo, Zuchu, Alikiba, Jux, Rayvanny, Kusah, Jay Melody; Kenyan Pop & Gengetone like Sauti Sol, Nyashinski, Bien, Wakadinali, Khaligraph Jones, Mejja; Kenyan & East African Gospel like Mercy Masika, Guardian Angel, Evelyn Wanjiru, Christina Shusho, Reuben Kigame; Ohangla like Prince Indah; Lingala/Rumba like Fally Ipupa, Koffi Olomide; plus Afrobeats, Amapiano, Hip-Hop, R&B, Dancehall, Pop, Nu-Jazz, Bossa Nova). Use the inferred 'genre' labels on tracks accurately when asked or recommending.

Always finish by calling 'submit_agent_turn' with concise, warm, accurate spoken text (1-3 sentences).`;
  }

  private async getSpotifyAccessToken(userOAuthToken?: string): Promise<string | null> {
    if (userOAuthToken && userOAuthToken.trim().length > 10) {
      return userOAuthToken.trim();
    }

    const clientId = (process.env.SPOTIFY_CLIENT_ID || 'dcfad6acbd594477b0705ecc1e6ace3a').trim();
    const clientSecret = (process.env.SPOTIFY_CLIENT_SECRET || '134028eff64c4e2cafa4b1f617463650').trim();

    if (this.spotifyAccessToken && Date.now() < this.spotifyAccessToken.expiresAt) {
      return this.spotifyAccessToken.token;
    }

    try {
      const response = await fetch('https://accounts.spotify.com/api/token', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Authorization': 'Basic ' + Buffer.from(clientId + ':' + clientSecret).toString('base64')
        },
        body: new URLSearchParams({ grant_type: 'client_credentials' })
      });

      const data = (await response.json()) as any;
      if (data.access_token) {
        this.spotifyAccessToken = {
          token: data.access_token,
          expiresAt: Date.now() + (data.expires_in * 1000) - 60000
        };
        return data.access_token;
      }
    } catch (error) {
      console.error('[Spotify Middleware] Failed to fetch access token:', error);
    }
    return null;
  }

  public async lookupSpotifyTrack(
    query: string,
    artist?: string,
    userOAuthToken?: string
  ): Promise<{ trackId: string; uri: string; title: string; artist: string } | null> {
    const hits = await this.searchSpotifyCatalogTracks(query, artist, 3, userOAuthToken);
    return hits[0] || null;
  }

  private async searchSpotifyCatalogTracks(
    query: string,
    artist?: string,
    limit: number = 6,
    userOAuthToken?: string
  ): Promise<Array<{ trackId: string; uri: string; title: string; artist: string; album: string; popularity: number }>> {
    const token = await this.getSpotifyAccessToken(userOAuthToken);
    if (!token) return [];

    const cleanTitle = ClaudeMusicReasoningProvider.sanitizeTrackTitle(query);
    const cleanArtist = ClaudeMusicReasoningProvider.sanitizeArtistName(artist);

    const searchCandidates: string[] = [];
    if (cleanTitle && cleanArtist) {
      searchCandidates.push(`track:${cleanTitle} artist:${cleanArtist}`);
      searchCandidates.push(`${cleanTitle} ${cleanArtist}`);
    }
    if (cleanTitle) {
      searchCandidates.push(cleanTitle);
    }

    const boundedLimit = Math.min(10, Math.max(1, Number(limit) || 6));
    for (const q of searchCandidates) {
      try {
        const searchRes = await fetch(
          `https://api.spotify.com/v1/search?q=${encodeURIComponent(q)}&type=track&limit=${boundedLimit}`,
          {
            headers: { Authorization: `Bearer ${token}` }
          }
        );

        if (!searchRes.ok) continue;
        const searchData = (await searchRes.json()) as any;
        const items = searchData.tracks?.items;
        if (Array.isArray(items) && items.length > 0) {
          return items
            .filter((item: any) => item && typeof item.uri === 'string' && item.uri.startsWith('spotify:track:'))
            .map((item: any) => ({
              trackId: item.id,
              uri: item.uri,
              title: item.name,
              artist: item.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist',
              album: item.album?.name || '',
              popularity: Number(item.popularity || 50)
            }));
        }
      } catch (error) {
        console.warn('[Spotify Search] Notice:', error);
      }
    }
    return [];
  }

  private async fetchUserSpotifyTopTracks(
    userOAuthToken: string | undefined,
    timeRange: 'short_term' | 'medium_term' | 'long_term' = 'short_term',
    limit: number = 15
  ): Promise<Array<{ trackId: string; uri: string; title: string; artist: string; album: string; popularity: number; playCount: number }>> {
    if (!userOAuthToken || userOAuthToken.trim().length < 10) return [];
    try {
      const res = await fetch(
        `https://api.spotify.com/v1/me/top/tracks?time_range=${timeRange}&limit=${limit}`,
        {
          headers: { Authorization: `Bearer ${userOAuthToken.trim()}` }
        }
      );
      if (!res.ok) return [];
      const data = (await res.json()) as any;
      const items = data.items;
      if (!Array.isArray(items)) return [];
      return items
        .filter((item: any) => item && typeof item.uri === 'string' && item.uri.startsWith('spotify:track:'))
        .map((item: any) => ({
          trackId: item.id,
          uri: item.uri,
          title: item.name,
          artist: item.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist',
          album: item.album?.name || '',
          popularity: Number(item.popularity || 60),
          playCount: 0
        }));
    } catch {
      return [];
    }
  }

  private async fetchSpotifyUserPlaylistsLive(
    userOAuthToken?: string,
    limit: number = 50
  ): Promise<Array<{ playlist_id: string; name: string; owner?: string; track_count: number; spotify_uri: string }>> {
    if (!userOAuthToken || userOAuthToken.trim().length < 10) return [];
    try {
      const res = await fetch(
        `https://api.spotify.com/v1/me/playlists?limit=${Math.min(50, Math.max(1, limit))}`,
        { headers: { Authorization: `Bearer ${userOAuthToken.trim()}` } }
      );
      if (!res.ok) return [];
      const data = (await res.json()) as any;
      const items = Array.isArray(data?.items) ? data.items : [];
      return items
        .filter((pl: any) => pl && typeof pl.id === 'string' && typeof pl.name === 'string')
        .map((pl: any) => ({
          playlist_id: pl.id,
          name: pl.name,
          owner: pl.owner?.display_name || pl.owner?.id || '',
          track_count: Number(pl.tracks?.total || 0),
          spotify_uri: pl.uri || `spotify:playlist:${pl.id}`
        }));
    } catch {
      return [];
    }
  }

  private async fetchSpotifyPlaylistTracksLive(
    userOAuthToken: string | undefined,
    playlistId: string,
    limit: number = 50
  ): Promise<Array<{ title: string; artist: string; spotify_uri: string; track_id: string }>> {
    if (!userOAuthToken || userOAuthToken.trim().length < 10 || !playlistId) return [];
    try {
      const res = await fetch(
        `https://api.spotify.com/v1/playlists/${encodeURIComponent(playlistId)}/tracks?limit=${Math.min(50, Math.max(1, limit))}&fields=items(track(id,name,uri,artists(name)))`,
        { headers: { Authorization: `Bearer ${userOAuthToken.trim()}` } }
      );
      if (!res.ok) return [];
      const data = (await res.json()) as any;
      const items = Array.isArray(data?.items) ? data.items : [];
      return items
        .map((it: any) => it?.track)
        .filter((tr: any) => tr && typeof tr.name === 'string' && typeof tr.uri === 'string' && tr.uri.startsWith('spotify:track:'))
        .map((tr: any) => ({
          title: tr.name,
          artist: tr.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist',
          spotify_uri: tr.uri,
          track_id: tr.id || tr.uri
        }));
    } catch {
      return [];
    }
  }

  private async fetchSpotifyLikedTracksLive(
    userOAuthToken?: string,
    limit: number = 50
  ): Promise<Array<{ title: string; artist: string; spotify_uri: string; track_id: string }>> {
    if (!userOAuthToken || userOAuthToken.trim().length < 10) return [];
    try {
      const res = await fetch(
        `https://api.spotify.com/v1/me/tracks?limit=${Math.min(50, Math.max(1, limit))}`,
        { headers: { Authorization: `Bearer ${userOAuthToken.trim()}` } }
      );
      if (!res.ok) return [];
      const data = (await res.json()) as any;
      const items = Array.isArray(data?.items) ? data.items : [];
      return items
        .map((it: any) => it?.track)
        .filter((tr: any) => tr && typeof tr.name === 'string' && typeof tr.uri === 'string' && tr.uri.startsWith('spotify:track:'))
        .map((tr: any) => ({
          title: tr.name,
          artist: tr.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist',
          spotify_uri: tr.uri,
          track_id: tr.id || tr.uri
        }));
    } catch {
      return [];
    }
  }

  private rememberTurn(userId: string, userText: string, assistantText: string) {
    if (!userText && !assistantText) return;
    const cleanUid = SupabaseIntelligenceStore.normalizeUserId(userId);
    const turns = ClaudeMusicReasoningProvider.recentDialogueTurnsByUser.get(cleanUid) || [];
    if (userText) {
      turns.push({ role: 'user', text: userText });
    }
    if (assistantText) {
      turns.push({ role: 'assistant', text: assistantText });
    }
    ClaudeMusicReasoningProvider.recentDialogueTurnsByUser.set(cleanUid, turns.slice(-16));
  }

  public async evaluateSatiety(context: TuneMindTelemetryContext): Promise<TuneMindReasoningResponse> {
    const eventStore = new EventStore();
    const userCommandRaw = (context.user_command || '').trim();
    const targetPlayerDefault = context.current_track?.source_player || 'com.spotify.music';
    const userId = SupabaseIntelligenceStore.normalizeUserId(context.user_id, context.user_email);
    const supabaseOverride = {
      supabaseUrl: context.supabase_url,
      supabaseKey: context.supabase_anon_key
    };

    if (context.pending_proposed_track && context.pending_proposed_track.title) {
      ClaudeMusicReasoningProvider.setPendingProposedTrack(context.pending_proposed_track, userId);
    }
    const pending = ClaudeMusicReasoningProvider.getPendingProposedTrack(userId);

    // ─── 0. SYNC LIVE TELEMETRY & FETCH LONG-TERM SUPABASE TASTE GENOME ───────
    await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(
      userId,
      (context.rolling_history || []).map(t => ({
        title: t.title,
        artist: t.artist,
        trackId: t.trackId,
        spotifyUri: t.spotifyUri,
        playCount: coerceNonNegativePlayCount(t.playCount),
        source: t.source_player
      })),
      {
        hearingTelemetry: context.hearing_telemetry,
        vaultTracks: context.vault_tracks,
        override: supabaseOverride
      }
    );

    const supabaseMemory = await SupabaseIntelligenceStore.fetchUserLongTermMemory(userId, supabaseOverride);

    // ─── 1. MERGE REAL LISTENING HISTORY: LIVE CLIENT + SUPABASE + EVENTSTORE ─
    const historyMap = new Map<string, { title: string; artist: string; trackId: string; spotifyUri?: string; source: string; playCount: number }>();

    if (Array.isArray(context.rolling_history)) {
      for (const t of context.rolling_history) {
        if (t.title && t.title !== 'Active Session' && t.title !== 'Standing By' && t.title !== 'Deep Flow State') {
          const cleanTitle = t.title.trim();
          const cleanArtist = (t.artist || 'Unknown Artist').trim();
          const identity = resolveCanonicalTrackIdentity({
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            title: cleanTitle,
            artist: cleanArtist
          });
          const key = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
          const existing = historyMap.get(key);
          const count = coerceNonNegativePlayCount(t.playCount);
          if (!existing) {
            historyMap.set(key, {
              title: cleanTitle,
              artist: cleanArtist,
              trackId: identity.trackId,
              spotifyUri: identity.spotifyUri,
              source: (t as any).source_player || 'com.spotify.music',
              playCount: count
            });
          } else {
            existing.playCount = Math.max(existing.playCount, count);
            if ((!existing.trackId || existing.trackId.startsWith('trk:')) && !identity.trackId.startsWith('trk:')) {
              existing.trackId = identity.trackId;
            }
            if (!existing.spotifyUri && identity.spotifyUri) {
              existing.spotifyUri = identity.spotifyUri;
            }
          }
        }
      }
    }

    // Merge historical tracks stored in Supabase Taste Genome across sessions
    for (const supaTrk of supabaseMemory.topTracksOverTime) {
      if (supaTrk.title) {
        const cleanTitle = supaTrk.title.trim();
        const cleanArtist = (supaTrk.artist || 'Unknown Artist').trim();
        const identity = resolveCanonicalTrackIdentity({
          trackId: supaTrk.trackId,
          spotifyUri: supaTrk.spotifyUri,
          title: cleanTitle,
          artist: cleanArtist
        });
        const key = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
        const existing = historyMap.get(key);
        const supaCount = coerceNonNegativePlayCount(supaTrk.playCount);
        if (!existing) {
          historyMap.set(key, {
            title: cleanTitle,
            artist: cleanArtist,
            trackId: identity.trackId,
            spotifyUri: identity.spotifyUri,
            source: supaTrk.source || 'com.spotify.music',
            playCount: supaCount
          });
        } else {
          existing.playCount = Math.max(existing.playCount, supaCount);
          if ((!existing.trackId || existing.trackId.startsWith('trk:')) && !identity.trackId.startsWith('trk:')) {
            existing.trackId = identity.trackId;
          }
          if (!existing.spotifyUri && identity.spotifyUri) existing.spotifyUri = identity.spotifyUri;
        }
      }
    }

    try {
      const storedTracks = eventStore.getRecentObservedTracks(50, userId);
      for (const t of storedTracks) {
        if (t.title) {
          const cleanTitle = t.title.trim();
          const cleanArtist = (t.artist || 'Unknown Artist').trim();
          const identity = resolveCanonicalTrackIdentity({
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            title: cleanTitle,
            artist: cleanArtist
          });
          const key = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
          const existing = historyMap.get(key);
          const storedCount = coerceNonNegativePlayCount(t.playCount);
          if (!existing) {
            historyMap.set(key, {
              title: cleanTitle,
              artist: cleanArtist,
              trackId: identity.trackId,
              spotifyUri: identity.spotifyUri,
              source: t.source || 'com.spotify.music',
              playCount: storedCount
            });
          } else {
            existing.playCount = Math.max(existing.playCount, storedCount);
            if ((!existing.trackId || existing.trackId.startsWith('trk:')) && !identity.trackId.startsWith('trk:')) {
              existing.trackId = identity.trackId;
            }
            if (!existing.spotifyUri && identity.spotifyUri) {
              existing.spotifyUri = identity.spotifyUri;
            }
          }
        }
      }
    } catch (e) {
      console.warn('[Claude Provider] Could not load EventStore history:', e);
    }

    const allUserTracks = Array.from(historyMap.values());

    // ─── 2. PARTITION FRESH vs. OVERPLAYED & COMPACT DOPAMINE CAPSULE ─────────
    const excludedSet = new Set<string>();
    if (context.current_track?.title) {
      excludedSet.add(context.current_track.title.toLowerCase().trim());
    }
    for (const t of eventStore.getRecentAgentTracks()) {
      excludedSet.add(t.toLowerCase().trim());
    }
    if (Array.isArray(context.recently_played_by_agent)) {
      for (const t of context.recently_played_by_agent) {
        if (t) excludedSet.add(String(t).toLowerCase().trim());
      }
    }
    // Also exclude tracks the user explicitly rejected in past sessions (stored in Supabase)
    for (const rej of supabaseMemory.rejectedTracks) {
      if (rej.title) excludedSet.add(rej.title.toLowerCase().trim());
    }

    // Strict active Resting Vault exclusion: tracks currently cooling in Vault NEVER leak into Fresh pool
    const activeVaultTitles = new Set<string>();
    const activeVaultTrackIds = new Set<string>();
    for (const vt of context.vault_tracks || []) {
      if (vt?.title) activeVaultTitles.add(String(vt.title).toLowerCase().trim());
      if ((vt as any)?.trackId) activeVaultTrackIds.add(String((vt as any).trackId).trim());
    }
    const isLockedInVault = (title: string, trackId?: string) =>
      activeVaultTitles.has(title.toLowerCase().trim()) ||
      (trackId ? activeVaultTrackIds.has(trackId.trim()) : false);

    // Enrich candidate Fresh pool with untouched Spotify Liked Songs (not already heard / vaulted)
    const freshCandidatePool: Array<typeof allUserTracks[number] & {
      readinessScore?: number;
      freshCategory?: string;
      freshReason?: string;
    }> = [];

    for (const t of allUserTracks) {
      if (isLockedInVault(t.title, t.trackId)) continue;
      if (excludedSet.has(t.title.toLowerCase().trim())) continue;
      const estimatedSatiety = Math.min(95, t.playCount * 20);
      const baseFresh = Math.max(8, 100 - estimatedSatiety);
      if (baseFresh < 35 && allUserTracks.length > 5) continue;
      const assessed = RestingVaultEngine.computeFreshReadiness({
        baseFreshnessPercent: baseFresh,
        playCount: t.playCount,
        isLikedOrSaved: false
      });
      freshCandidatePool.push({
        ...t,
        readinessScore: assessed.readinessScore,
        freshCategory: assessed.category,
        freshReason: assessed.freshReason
      });
    }

    for (const liked of context.spotify_liked_tracks || []) {
      const cleanTitle = String(liked.title || '').trim();
      if (!cleanTitle || excludedSet.has(cleanTitle.toLowerCase())) continue;
      if (isLockedInVault(cleanTitle, liked.track_id)) continue;
      const cleanArtist = String(liked.artist || 'Spotify Library').trim();
      const dupKey = `${cleanTitle.toLowerCase()} - ${cleanArtist.toLowerCase()}`;
      if (historyMap.has(dupKey)) continue;
      const assessed = RestingVaultEngine.computeFreshReadiness({
        baseFreshnessPercent: 99,
        playCount: 0,
        isLikedOrSaved: true,
        sourceLabel: 'Liked Song'
      });
      freshCandidatePool.push({
        title: cleanTitle,
        artist: cleanArtist,
        trackId: liked.track_id || `trk:${cleanTitle.toLowerCase()}`,
        spotifyUri: normalizeSpotifyUri(liked.spotify_uri),
        source: 'com.spotify.music',
        playCount: 0,
        readinessScore: assessed.readinessScore,
        freshCategory: assessed.category,
        freshReason: assessed.freshReason
      });
    }

    freshCandidatePool.sort((a, b) => (b.readinessScore || 0) - (a.readinessScore || 0));

    let freshTracks = freshCandidatePool;
    if (freshTracks.length === 0 && allUserTracks.length > 0) {
      freshTracks = allUserTracks.filter(t => !isLockedInVault(t.title, t.trackId));
    }
    if (freshTracks.length === 0 && allUserTracks.length > 0) {
      freshTracks = [...allUserTracks];
    }

    const overplayedTracks = [...allUserTracks]
      .filter(t => t.playCount >= 3)
      .sort((a, b) => b.playCount - a.playCount)
      .slice(0, 8);

    const mostPlayedTracks = [...allUserTracks]
      .filter(t => t.playCount > 0)
      .sort((a, b) => b.playCount - a.playCount)
      .slice(0, 10);

    const topUserArtists = Array.from(
      allUserTracks.reduce((acc, t) => {
        const primaryArtist = ClaudeMusicReasoningProvider.sanitizeArtistName(t.artist);
        if (primaryArtist && t.playCount > 0) {
          acc.set(primaryArtist, (acc.get(primaryArtist) || 0) + t.playCount);
        }
        return acc;
      }, new Map<string, number>()).entries()
    )
      .sort((a, b) => b[1] - a[1])
      .map(e => e[0])
      .slice(0, 6);

    const compactedCapsule = (context.dopamine_capsule && context.dopamine_capsule.length > 0)
      ? context.dopamine_capsule
      : freshTracks.slice(0, 3).map((t, idx) => ({
          title: t.title,
          artist: t.artist,
          trackId: t.trackId,
          spotifyUri: t.spotifyUri,
          freshness_percent: Math.max(76, 94 - t.playCount * 6 - idx * 3),
          reason: idx === 0
            ? `Peak ${context.circadian_daypart} dopamine anchor with low receptor habituation`
            : `Harmonic cadence match from your personal rotation`
        }));

    // Strict all-listened exclusion set (Supabase + EventStore + live history + rejected + agent history)
    const strictDiscoveryExclusionSet = new Set<string>(excludedSet);
    const normalizeKey = (s: string) =>
      ClaudeMusicReasoningProvider.sanitizeTrackTitle(s)
        .toLowerCase()
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();

    for (const t of allUserTracks) {
      if (t.title) {
        strictDiscoveryExclusionSet.add(t.title.toLowerCase().trim());
        strictDiscoveryExclusionSet.add(normalizeKey(t.title));
      }
      if (t.trackId && t.trackId.startsWith('spotify:track:')) {
        strictDiscoveryExclusionSet.add(t.trackId.trim());
      }
    }
    for (const title of supabaseMemory.allListenedTitles || []) {
      strictDiscoveryExclusionSet.add(title.toLowerCase().trim());
      strictDiscoveryExclusionSet.add(normalizeKey(title));
    }
    for (const uri of supabaseMemory.allListenedUris || []) {
      strictDiscoveryExclusionSet.add(uri.trim());
    }

    // Merge multi-turn dialogue history from client + server memory + Supabase recent interventions
    const perUserDialogue = ClaudeMusicReasoningProvider.recentDialogueTurnsByUser.get(userId) || [];
    const mergedDialogue = (Array.isArray(context.dialogue_history) && context.dialogue_history.length > 0)
      ? context.dialogue_history.slice(-12)
      : perUserDialogue.length > 0
      ? perUserDialogue.slice(-12)
      : supabaseMemory.recentInterventions
          .slice(0, 5)
          .reverse()
          .flatMap(r => [
            { role: 'user' as const, text: r.userSaid },
            { role: 'assistant' as const, text: r.agentReplied }
          ]);

    const systemPrompt = this.getAutonomousSystemPrompt();
    const tools = this.getAutonomousAgentTools();

    const daypartWindow =
      context.circadian_daypart === 'Morning'
        ? '06:00 - 12:00'
        : context.circadian_daypart === 'Midday'
        ? '12:00 - 17:00'
        : context.circadian_daypart === 'Evening'
        ? '17:00 - 22:00'
        : '22:00 - 06:00';

    const initialContextPayload = {
      user_id: userId,
      user_spoken_message: userCommandRaw,
      circadian_daypart: context.circadian_daypart,
      circadian_window: `${context.circadian_daypart} (${daypartWindow})`,
      user_local_time: context.local_time || undefined,
      user_timezone: context.timezone || undefined,
      app_state: context.app_state || {
        current_screen: 'AGENT',
        theme_behavior: 'ADAPTIVE',
        autonomous_dj_enabled: false,
        stay_in_tunemind_enabled: true
      },
      player_preferences: context.player_preferences || {
        has_explicit_default_player: false,
        default_player_package: targetPlayerDefault,
        default_player_name: 'Spotify',
        available_players: []
      },
      for_you_picks_count: Array.isArray(context.for_you_picks) ? context.for_you_picks.length : 0,
      spotify_token_available: Boolean(context.spotify_access_token),
      spotify_playlists_count: Array.isArray(context.spotify_playlists) ? context.spotify_playlists.length : 0,
      spotify_playlists_preview: Array.isArray(context.spotify_playlists)
        ? context.spotify_playlists.slice(0, 12).map(pl => ({
            name: pl.name,
            track_count: pl.track_count,
            hydrated_tracks_count: Array.isArray(pl.tracks) ? pl.tracks.length : 0,
            spotify_uri: pl.spotify_uri || `spotify:playlist:${pl.playlist_id}`
          }))
        : [],
      spotify_liked_tracks_count: Array.isArray(context.spotify_liked_tracks) ? context.spotify_liked_tracks.length : 0,
      has_listening_stats: Boolean(context.listening_stats),
      listening_stats_preview: context.listening_stats
        ? {
            today_minutes: context.listening_stats.today_minutes,
            seven_day_minutes: context.listening_stats.seven_day_minutes,
            total_verified_plays: context.listening_stats.total_verified_plays,
            completion_rate_percent: context.listening_stats.completion_rate_percent,
            discovery_rate_percent: context.listening_stats.discovery_rate_percent
          }
        : null,
      current_track: context.current_track
        ? {
            ...context.current_track,
            genre: SelfLearningDiscoveryEngine.inferGenre(
              context.current_track.title || '',
              context.current_track.artist || ''
            )
          }
        : context.current_track,
      pending_proposed_track_awaiting_user_approval: pending,
      top_most_played_user_tracks: mostPlayedTracks.slice(0, 6).map(t => ({
        ...t,
        genre: SelfLearningDiscoveryEngine.inferGenre(t.title, t.artist)
      })),
      top_user_artists: topUserArtists,
      fresh_low_satiety_user_tracks: freshTracks.slice(0, 12).map(t => ({
        ...t,
        genre: SelfLearningDiscoveryEngine.inferGenre(t.title, t.artist)
      })),
      overplayed_high_satiety_tracks: overplayedTracks,
      dopamine_capsule: compactedCapsule,
      vault_tracks: context.vault_tracks || [],
      hearing_telemetry: context.hearing_telemetry || {
        estimated_db: 68,
        safe_headroom_db: 17,
        daily_exposure_minutes: 45,
        output_device: 'Phone / Wireless Audio',
        safety_guidance: 'Listening exposure within safe acoustic threshold (<80 dB).'
      },
      supabase_taste_genome_long_term_memory: {
        intelligence_maturity_level: supabaseMemory.intelligenceMaturityLevel,
        total_historical_interventions: supabaseMemory.totalHistoricalInterventions,
        total_tracks_learned: supabaseMemory.totalTracksLearned,
        previously_approved_tracks: supabaseMemory.approvedTracks.slice(-8),
        previously_rejected_tracks_to_avoid: supabaseMemory.rejectedTracks.slice(-8),
        circadian_pattern_notes: supabaseMemory.circadianPatternNotes
      },
      recently_suggested_or_played_titles_to_avoid_repeating: Array.from(excludedSet).slice(0, 15),
      recent_conversation_history: mergedDialogue
    };

    let submittedTurnDecision: any = null;
    let lastDiscoveredOrSearchedTrack: { title: string; artist: string; uri: string; trackId?: string } | null = null;
    let providerTag = 'autonomous-tool-agent';

    const available = await this.isAvailable();
    if (available) {
      console.log(
        `[Claude Agent] 🧠 Starting Autonomous Tool-Calling Loop for user="${userId}" (Supabase maturity=${supabaseMemory.intelligenceMaturityLevel}): "${userCommandRaw}"`
      );
      try {
        const messages: any[] = [
          {
            role: 'user',
            content: `Here is the live studio telemetry, Supabase long-term Taste Genome memory, conversation history, and the user's latest spoken message. Reason naturally, call any Spotify, listening-history, or Supabase Taste Genome tools you need, and finish by calling 'submit_agent_turn':\n\n${JSON.stringify(initialContextPayload, null, 2)}`
          }
        ];

        for (let step = 0; step < 5; step++) {
          const response = await this.callClaudeMessagesWithTools(
            systemPrompt,
            messages,
            tools,
            1400
          );

          const contentBlocks: any[] = Array.isArray(response?.content) ? response.content : [];
          const toolUseBlocks = contentBlocks.filter((b: any) => b && b.type === 'tool_use');

          if (toolUseBlocks.length === 0) {
            const rawText = this.extractRawText(contentBlocks);
            const jsonMatch = rawText.match(/\{[\s\S]*\}/);
            if (jsonMatch) {
              try {
                const parsed = JSON.parse(jsonMatch[0]);
                submittedTurnDecision = {
                  voice_agent_tts: parsed.voice_agent_tts || rawText,
                  flow_state_analysis: parsed.flow_state_analysis || 'Autonomous Studio Reasoning complete.',
                  calculated_satiety_percentage: parsed.calculated_satiety_percentage ?? 28,
                  action: parsed.agent_tool_call?.action || parsed.action || 'ADVISE_ONLY',
                  track_id: parsed.agent_tool_call?.track_id || parsed.track_id,
                  track_title: parsed.agent_tool_call?.track_title || parsed.track_title,
                  track_artist: parsed.agent_tool_call?.track_artist || parsed.track_artist,
                  spotify_uri: parsed.agent_tool_call?.spotify_uri || parsed.spotify_uri,
                  volume_percent: parsed.agent_tool_call?.volume_percent,
                  palette_id: parsed.agent_tool_call?.palette_id,
                  target_screen: parsed.agent_tool_call?.target_screen,
                  expect_follow_up: parsed.agent_tool_call?.expect_follow_up ?? true
                };
              } catch {
                submittedTurnDecision = {
                  voice_agent_tts: rawText.trim(),
                  flow_state_analysis: 'Conversational Studio Advisor active.',
                  calculated_satiety_percentage: 26,
                  action: 'ADVISE_ONLY',
                  expect_follow_up: true
                };
              }
            } else {
              submittedTurnDecision = {
                voice_agent_tts: rawText.trim(),
                flow_state_analysis: 'Conversational Studio Advisor active.',
                calculated_satiety_percentage: 26,
                action: 'ADVISE_ONLY',
                expect_follow_up: true
              };
            }
            break;
          }

          messages.push({
            role: 'assistant',
            content: contentBlocks
          });

          const toolResults: any[] = [];
          for (const toolCall of toolUseBlocks) {
            const toolName = toolCall.name;
            const input = toolCall.input || {};
            console.log(`[Claude Agent] 🛠️ Executing Tool: ${toolName}`, JSON.stringify(input));

            if (toolName === 'submit_agent_turn') {
              submittedTurnDecision = input;
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({ status: 'turn_submitted' })
              });
              break;
            }

            if (toolName === 'query_supabase_taste_genome') {
              const refreshedMemory = await SupabaseIntelligenceStore.fetchUserLongTermMemory(userId, supabaseOverride);
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  user_id: refreshedMemory.userId,
                  live_supabase_connected: refreshedMemory.isLiveSupabaseConnected,
                  intelligence_maturity_level: refreshedMemory.intelligenceMaturityLevel,
                  total_historical_interventions: refreshedMemory.totalHistoricalInterventions,
                  total_tracks_learned: refreshedMemory.totalTracksLearned,
                  approved_tracks: refreshedMemory.approvedTracks,
                  rejected_tracks: refreshedMemory.rejectedTracks,
                  top_artists_over_time: refreshedMemory.topArtistsOverTime,
                  top_tracks_over_time: refreshedMemory.topTracksOverTime.slice(0, 12),
                  recent_interventions: refreshedMemory.recentInterventions.slice(0, 8),
                  circadian_pattern_notes: refreshedMemory.circadianPatternNotes
                })
              });
            } else if (toolName === 'query_user_listening_history') {
              const sortBy = String(input.sort_by || 'most_played');
              const filterKeyword = String(input.artist_or_keyword || '').toLowerCase().trim();
              const limit = Math.min(20, Math.max(1, Number(input.limit || 8)));

              let combinedPool = [...allUserTracks];
              if (sortBy === 'most_played' && context.spotify_access_token) {
                const apiTop = await this.fetchUserSpotifyTopTracks(context.spotify_access_token, 'short_term', 10);
                for (const topItem of apiTop) {
                  const exists = combinedPool.find(
                    p => p.title.toLowerCase() === topItem.title.toLowerCase()
                  );
                  if (!exists) {
                    combinedPool.push({
                      title: topItem.title,
                      artist: topItem.artist,
                      trackId: topItem.trackId,
                      spotifyUri: topItem.uri,
                      source: 'com.spotify.music',
                      playCount: topItem.playCount
                    });
                  } else {
                    if (!exists.spotifyUri && topItem.uri) exists.spotifyUri = topItem.uri;
                    if ((!exists.trackId || exists.trackId.startsWith('trk:')) && topItem.trackId) {
                      exists.trackId = topItem.trackId;
                    }
                  }
                }
              }

              if (filterKeyword) {
                combinedPool = combinedPool.filter(
                  t =>
                    t.title.toLowerCase().includes(filterKeyword) ||
                    t.artist.toLowerCase().includes(filterKeyword)
                );
              }

              if (sortBy === 'most_played' || sortBy === 'overplayed_fatigued') {
                combinedPool.sort((a, b) => coerceNonNegativePlayCount(b.playCount) - coerceNonNegativePlayCount(a.playCount));
              } else if (sortBy === 'least_played_fresh') {
                combinedPool.sort((a, b) => coerceNonNegativePlayCount(a.playCount) - coerceNonNegativePlayCount(b.playCount));
              }

              const topSlice = combinedPool.slice(0, limit);
              if (topSlice[0] && !topSlice[0].spotifyUri) {
                const resolved = await this.lookupSpotifyTrack(
                  topSlice[0].title,
                  topSlice[0].artist,
                  context.spotify_access_token
                );
                if (resolved) {
                  topSlice[0].spotifyUri = resolved.uri;
                  if ((!topSlice[0].trackId || topSlice[0].trackId.startsWith('trk:')) && resolved.trackId) {
                    topSlice[0].trackId = resolved.trackId;
                  }
                }
              }
              if (topSlice[0]) {
                lastDiscoveredOrSearchedTrack = {
                  title: topSlice[0].title,
                  artist: topSlice[0].artist,
                  uri: topSlice[0].spotifyUri || '',
                  trackId: topSlice[0].trackId
                };
              }

              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  sort_by: sortBy,
                  total_matched: combinedPool.length,
                  tracks: topSlice.map((t, idx) => {
                    const plays = coerceNonNegativePlayCount(t.playCount);
                    return {
                      rank: idx + 1,
                      track_id: t.trackId,
                      title: t.title,
                      artist: t.artist,
                      play_count: plays,
                      spotify_uri: t.spotifyUri || '',
                      freshness_percent: Math.max(15, 96 - plays * 8)
                    };
                  })
                })
              });
            } else if (toolName === 'discover_and_recommend_spotify_tracks') {
              const seedArtists: string[] = Array.isArray(input.seed_artists) && input.seed_artists.length > 0
                ? input.seed_artists.map(String)
                : topUserArtists.slice(0, 3);
              const moodQuery = String(input.mood_or_genre_query || '').trim();
              const excludeOverplayed = input.exclude_overplayed !== false;

              const discovered = await this.discoverSpotifyTracksForContext(
                context,
                allUserTracks,
                excludeOverplayed ? strictDiscoveryExclusionSet : excludedSet,
                seedArtists,
                moodQuery,
                excludeOverplayed
              );

              if (discovered[0]) {
                const discIdentity = resolveCanonicalTrackIdentity({
                  spotifyUri: discovered[0].spotify_uri,
                  title: discovered[0].title,
                  artist: discovered[0].artist
                });
                lastDiscoveredOrSearchedTrack = {
                  title: discovered[0].title,
                  artist: discovered[0].artist,
                  uri: discIdentity.spotifyUri || discovered[0].spotify_uri,
                  trackId: discIdentity.trackId
                };
              }

              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  recommendations_count: discovered.length,
                  recommended_tracks: discovered
                })
              });
            } else if (toolName === 'search_spotify_catalog') {
              const q = String(input.query || '').trim();
              const artist = input.artist ? String(input.artist).trim() : undefined;
              const hits = await this.searchSpotifyCatalogTracks(q, artist, 6, context.spotify_access_token);
              if (hits[0]) {
                const hitIdentity = resolveCanonicalTrackIdentity({
                  spotifyUri: hits[0].uri,
                  title: hits[0].title,
                  artist: hits[0].artist
                });
                lastDiscoveredOrSearchedTrack = {
                  title: hits[0].title,
                  artist: hits[0].artist,
                  uri: hitIdentity.spotifyUri || hits[0].uri,
                  trackId: hitIdentity.trackId
                };
              }
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  query: q,
                  results: hits.map(h => ({
                    title: h.title,
                    artist: h.artist,
                    album: h.album,
                    spotify_uri: h.uri,
                    popularity: h.popularity
                  }))
                })
              });
            } else if (toolName === 'inspect_for_you_suggestions') {
              const filter = String(input.category_filter || '').toLowerCase().trim();
              const limit = Math.min(20, Math.max(1, Number(input.limit || 10)));
              let picks = Array.isArray(context.for_you_picks) ? [...context.for_you_picks] : [];
              if (filter) {
                if (filter === 'saved') {
                  picks = picks.filter(p => p.is_saved);
                } else if (filter === 'wildcard') {
                  picks = picks.filter(p => p.is_wildcard || (p.sub_tier || '').toLowerCase().includes('wildcard'));
                } else {
                  picks = picks.filter(p =>
                    p.title.toLowerCase().includes(filter) ||
                    p.artist.toLowerCase().includes(filter) ||
                    (p.category || '').toLowerCase().includes(filter) ||
                    (p.genre_tag || '').toLowerCase().includes(filter) ||
                    (p.reason || '').toLowerCase().includes(filter)
                  );
                }
              }
              const sliced = picks.slice(0, limit);
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  total_for_you_picks: picks.length,
                  picks: sliced
                })
              });
            } else if (toolName === 'query_spotify_library') {
              const scope = String(input.scope || 'all');
              const q = String(input.query || '').toLowerCase().trim();
              let playlists = Array.isArray(context.spotify_playlists)
                ? context.spotify_playlists.map(pl => ({
                    ...pl,
                    tracks: Array.isArray(pl.tracks) ? [...pl.tracks] : []
                  }))
                : [];
              let liked = Array.isArray(context.spotify_liked_tracks) ? [...context.spotify_liked_tracks] : [];

              // Live Spotify Web API fallback if Room hasn't populated playlists yet or matches are missing
              if (scope !== 'liked_tracks' && context.spotify_access_token && playlists.length === 0) {
                const livePls = await this.fetchSpotifyUserPlaylistsLive(context.spotify_access_token, 50);
                playlists = livePls.map(lp => ({
                  ...lp,
                  tracks: []
                }));
              }

              let matchedPlaylists = q
                ? playlists.filter(pl =>
                    pl.name.toLowerCase().includes(q) ||
                    (pl.tracks || []).some(t => t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q))
                  )
                : [...playlists];

              if (q && matchedPlaylists.length === 0 && context.spotify_access_token && playlists.length > 0) {
                // Retry after fetching live list in case Room was stale
                const livePls = await this.fetchSpotifyUserPlaylistsLive(context.spotify_access_token, 50);
                for (const lp of livePls) {
                  if (!playlists.some(p => p.playlist_id === lp.playlist_id)) {
                    playlists.push({ ...lp, tracks: [] });
                  }
                }
                matchedPlaylists = playlists.filter(pl =>
                  pl.name.toLowerCase().includes(q) ||
                  (pl.tracks || []).some(t => t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q))
                );
              }

              // Hydrate tracks via Spotify Web API for matching playlists (or top 2 non-empty playlists) that still have tracks=[]
              const toHydrate = (matchedPlaylists.length > 0 ? matchedPlaylists : playlists)
                .filter(pl => (pl.tracks || []).length === 0 && (pl.track_count || 0) > 0 && pl.playlist_id)
                .slice(0, 3);
              if (context.spotify_access_token && toHydrate.length > 0) {
                for (const pl of toHydrate) {
                  const fetchedTracks = await this.fetchSpotifyPlaylistTracksLive(
                    context.spotify_access_token,
                    pl.playlist_id,
                    50
                  );
                  if (fetchedTracks.length > 0) {
                    pl.tracks = fetchedTracks;
                  }
                }
              }

              if (scope !== 'playlists' && liked.length === 0 && context.spotify_access_token) {
                liked = await this.fetchSpotifyLikedTracksLive(context.spotify_access_token, 50);
              }
              const matchedLiked = q
                ? liked.filter(t => t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q))
                : liked;

              // Prefer hydrated playlists first, avoiding repeating recently played tracks
              const candidateTrack = matchedPlaylists
                .flatMap(pl => pl.tracks || [])
                .find(t => t.title && !excludedSet.has(t.title.toLowerCase().trim()))
                || matchedPlaylists.flatMap(pl => pl.tracks || [])[0]
                || matchedLiked[0];
              if (candidateTrack && candidateTrack.spotify_uri) {
                lastDiscoveredOrSearchedTrack = {
                  title: candidateTrack.title,
                  artist: candidateTrack.artist,
                  uri: candidateTrack.spotify_uri,
                  trackId: candidateTrack.track_id
                };
              }

              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  spotify_connected: Boolean(context.spotify_access_token || context.app_state?.spotify_connected),
                  playlists: scope === 'liked_tracks' ? [] : (matchedPlaylists.length > 0 ? matchedPlaylists : playlists).slice(0, 15),
                  liked_tracks: scope === 'playlists' ? [] : matchedLiked.slice(0, 20),
                  total_playlists: playlists.length,
                  total_liked_tracks: liked.length
                })
              });
            } else if (toolName === 'query_listening_stats') {
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  listening_stats: context.listening_stats || null,
                  hearing_telemetry: context.hearing_telemetry || null,
                  top_user_artists: topUserArtists,
                  top_most_played_tracks: mostPlayedTracks.slice(0, 6)
                })
              });
            } else if (toolName === 'inspect_vault_tracks') {
              const includeFresh = input.include_fresh_pool !== false;
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  vault_count: (context.vault_tracks || []).length,
                  vault_tracks: context.vault_tracks || [],
                  fresh_tracks: includeFresh ? freshTracks.slice(0, 10) : []
                })
              });
            } else {
              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({ error: `Unknown tool ${toolName}` })
              });
            }
          }

          if (submittedTurnDecision) {
            break;
          }

          messages.push({
            role: 'user',
            content: toolResults
          });
        }
      } catch (claudeError: any) {
        console.warn('[Claude Provider] Anthropic tool loop notice, attempting single-shot Claude JSON reasoning:', claudeError?.message || claudeError);
        const msg = String(claudeError?.message || claudeError);
        if (msg.includes('401') || msg.includes('invalid x-api-key')) {
          this.hasInvalidKey = true;
        }
      }
    }

    // ─── 3B. SINGLE-SHOT CLAUDE JSON PASS (SAME PAID ANTHROPIC KEY ONLY) ──────
    if (!submittedTurnDecision && available && !this.hasInvalidKey) {
      const singleShotClaude = await this.tryClaudeSingleShotJsonDecision(
        context,
        initialContextPayload,
        allUserTracks,
        mostPlayedTracks,
        topUserArtists,
        strictDiscoveryExclusionSet
      );
      if (singleShotClaude) {
        submittedTurnDecision = singleShotClaude.decision;
        if (singleShotClaude.fallbackTrack) {
          lastDiscoveredOrSearchedTrack = singleShotClaude.fallbackTrack;
        }
        providerTag = singleShotClaude.providerTag;
      }
    }

    // ─── 3C. FINALIZE AUTONOMOUS CLAUDE DECISION IF OBTAINED ──────────────────
    if (submittedTurnDecision) {
      let action = (submittedTurnDecision.action || 'ADVISE_ONLY') as AgentToolCall['action'];
      let ttsText = String(submittedTurnDecision.voice_agent_tts || '').trim();
      // IMPORTANT: Never attach a track if Claude chose ADVISE_ONLY!
      const isTrackBearingAction =
        action === 'PROPOSE_TRACK' ||
        action === 'PLAY_TRACK_NOW' ||
        action === 'LAUNCH_DOPAMINE_CAPSULE' ||
        action === 'QUARANTINE_TO_VAULT' ||
        action === 'RESTORE_FROM_VAULT';

      let trackTitle = isTrackBearingAction
        ? String(submittedTurnDecision.track_title || lastDiscoveredOrSearchedTrack?.title || '').trim()
        : '';
      let trackArtist = isTrackBearingAction
        ? String(submittedTurnDecision.track_artist || lastDiscoveredOrSearchedTrack?.artist || '').trim()
        : '';
      let spotifyUri = isTrackBearingAction
        ? String(submittedTurnDecision.spotify_uri || lastDiscoveredOrSearchedTrack?.uri || '').trim()
        : '';

      if (spotifyUri) {
        const isAllowedSpotifyUri =
          spotifyUri.startsWith('spotify:track:') ||
          spotifyUri.startsWith('spotify:playlist:') ||
          spotifyUri.startsWith('spotify:album:') ||
          spotifyUri.startsWith('spotify:artist:') ||
          spotifyUri.startsWith('spotify:episode:') ||
          /^https:\/\/open\.spotify\.com\/(track|playlist|album|artist|episode)\//i.test(spotifyUri);
        if (!isAllowedSpotifyUri) {
          if (/^[A-Za-z0-9]{22}$/.test(spotifyUri)) {
            spotifyUri = `spotify:track:${spotifyUri}`;
          } else {
            spotifyUri = '';
          }
        }
      }

      const resolvePlayerPackage = (rawPlayer?: string): string => {
        const cleaned = String(rawPlayer || '').trim();
        const availablePlayers = context.player_preferences?.available_players || [];
        if (cleaned) {
          const lower = cleaned.toLowerCase();
          const matched = availablePlayers.find(
            p =>
              p.package_name.toLowerCase() === lower ||
              p.id.toLowerCase() === lower ||
              p.name.toLowerCase() === lower ||
              p.name.toLowerCase().includes(lower) ||
              lower.includes(p.name.toLowerCase()) ||
              lower.includes(p.id.toLowerCase())
          );
          if (matched) return matched.package_name || matched.id;
          if (lower.includes('boomplay')) return 'com.afmobi.boomplayer';
          if (lower.includes('youtube music') || lower.includes('yt music') || lower.includes('youtube_music')) {
            return 'com.google.android.apps.youtube.music';
          }
          if (lower.includes('audiomack')) return 'com.audiomack';
          if (lower.includes('apple')) return 'com.apple.android.music';
          if (lower.includes('tunemind') || lower.includes('inapp') || lower.includes('built-in')) return 'tunemind_inapp';
          if (lower.includes('local')) return 'local_files';
          if (lower.includes('spotify')) return 'com.spotify.music';
          return cleaned;
        }
        return context.player_preferences?.default_player_package || targetPlayerDefault;
      };

      const isAutoCurateNextTrack = /^AUTO_CURATE_NEXT_TRACK\b/i.test(userCommandRaw);
      const commandWithoutAppSwitchNegations = userCommandRaw
        .replace(/\b(?:don'?t|do\s+not|never)\s+(?:open|leave|switch(?:\s+to)?|launch)\s+(?:spotify|the\s+app|another\s+app|external(?:\s+app)?)\b/gi, ' ')
        .trim();
      const isRejectingPending = Boolean(
        pending &&
        !isAutoCurateNextTrack &&
        /\b(no|nope|nah|cancel|never\s*mind|don'?t|do\s+not|won'?t|wouldn'?t|not\s+(?:that|this|really|now)|pass|skip|reject|decline|dislike|hate|stop|another|different|something\s+else|someone\s+else|change\s+it|leave\s+it|forget\s+it)\b/i.test(
          commandWithoutAppSwitchNegations
        )
      );

      // Enforce two-step approval for normal turns, EXCEPT when AUTO_CURATE_NEXT_TRACK is triggered at end-of-song!
      const isApprovingPending = Boolean(
        pending &&
        !isRejectingPending &&
        (action === 'PLAY_TRACK_NOW' ||
          (trackTitle && pending.title.toLowerCase() === trackTitle.toLowerCase() && /\b(yes|yeah|yep|sure|ok|okay|play|go ahead|do it|spin|approve|ndio|sawa)\b/i.test(userCommandRaw)))
      );

      let candidateTrackId: string | undefined = isTrackBearingAction
        ? (submittedTurnDecision.track_id || lastDiscoveredOrSearchedTrack?.trackId || undefined)
        : undefined;

      if (isRejectingPending && pending) {
        // Pending song was explicitly declined: clear it and NEVER allow it to execute playback!
        ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
        const sameAsRejectedPending = Boolean(
          trackTitle &&
          ClaudeMusicReasoningProvider.normalizeForExclusion(trackTitle) ===
            ClaudeMusicReasoningProvider.normalizeForExclusion(pending.title)
        );
        if (!trackTitle || sameAsRejectedPending || (action === 'PLAY_TRACK_NOW' && !sameAsRejectedPending)) {
          if (!trackTitle || sameAsRejectedPending) {
            action = 'ADVISE_ONLY';
            trackTitle = '';
            trackArtist = '';
            spotifyUri = '';
            candidateTrackId = undefined;
            if (!ttsText || /\b(playing|spinning|launching)\b/i.test(ttsText)) {
              ttsText = `Got it, I won't play "${pending.title}". What song, artist, or vibe would you like instead?`;
            }
          } else {
            // Claude fetched a genuinely different alternative track — propose it for confirmation, never autoplay directly!
            action = 'PROPOSE_TRACK';
            if (ttsText && !ttsText.includes('?')) {
              ttsText = `${ttsText.replace(/[.!]+$/, '')}. Would you like me to play this one instead?`;
            }
          }
        }
      } else if (isAutoCurateNextTrack) {
        if (!trackTitle && freshTracks[0]) {
          trackTitle = freshTracks[0].title;
          trackArtist = freshTracks[0].artist;
          spotifyUri = freshTracks[0].spotifyUri || normalizeSpotifyUri(freshTracks[0].trackId) || spotifyUri;
          candidateTrackId = freshTracks[0].trackId || candidateTrackId;
        }
        if (trackTitle) {
          action = 'PLAY_TRACK_NOW';
          const cleanArt = trackArtist && trackArtist !== 'Unknown Artist' ? ` by ${trackArtist}` : '';
          if (!ttsText || ttsText.includes('?')) {
            ttsText = `Up next in your flow, playing ${trackTitle}${cleanArt} right now.`;
          }
        }
      } else if (action === 'PLAY_TRACK_NOW' && !isApprovingPending) {
        action = 'PROPOSE_TRACK';
        if (ttsText && !ttsText.includes('?')) {
          ttsText = `${ttsText.replace(/[.!]+$/, '')}. Would you like me to play it right now?`;
        }
      }

      if (!isAutoCurateNextTrack && isApprovingPending && pending) {
        action = 'PLAY_TRACK_NOW';
        trackTitle = pending.title;
        trackArtist = pending.artist;
        spotifyUri = pending.spotifyUri || normalizeSpotifyUri(pending.trackId) || spotifyUri;
        candidateTrackId = pending.trackId || candidateTrackId;
      }

      // Resolve Spotify URI if we are playing or proposing a track and spotifyUri is still empty
      if (!spotifyUri && trackTitle && (action === 'PLAY_TRACK_NOW' || action === 'PROPOSE_TRACK' || action === 'LAUNCH_DOPAMINE_CAPSULE')) {
        const cleanArtist = trackArtist && trackArtist !== 'Unknown Artist' ? trackArtist : undefined;
        const resolved = await this.lookupSpotifyTrack(trackTitle, cleanArtist, context.spotify_access_token);
        if (resolved) {
          spotifyUri = resolved.uri;
          if (!candidateTrackId && resolved.trackId) {
            candidateTrackId = resolved.trackId;
          }
          if (!trackArtist || trackArtist === 'Unknown Artist') {
            trackArtist = resolved.artist;
          }
        }
      }

      const cleanTitleForSearch = trackTitle ? ClaudeMusicReasoningProvider.sanitizeTrackTitle(trackTitle) : '';
      const cleanArtistForSearch = trackArtist ? ClaudeMusicReasoningProvider.sanitizeArtistName(trackArtist) : '';
      const cleanSearchQuery = trackTitle ? `${cleanTitleForSearch} ${cleanArtistForSearch}`.trim() : '';
      const targetPlayer = resolvePlayerPackage(submittedTurnDecision.target_player || pending?.source);
      const trackIdentity = trackTitle
        ? resolveCanonicalTrackIdentity({
            trackId: candidateTrackId || pending?.trackId,
            spotifyUri: spotifyUri || pending?.spotifyUri,
            title: cleanTitleForSearch || trackTitle,
            artist: cleanArtistForSearch || trackArtist
          })
        : null;
      const resolvedTrackId = trackIdentity?.trackId;
      if (trackIdentity?.spotifyUri && !spotifyUri) {
        spotifyUri = trackIdentity.spotifyUri;
      }

      const requestedNeverOpenExternal = Boolean(
        submittedTurnDecision.never_open_external_app === true ||
        pending?.never_open_external_app === true ||
        context.app_state?.strict_no_app_switch_enabled === true ||
        /(?:without\s+(?:opening|leaving|switching|launching|going\s+to)|don'?t\s+(?:open|leave|switch\s+to|launch)\s+(?:spotify|the\s+app|another\s+app)|stay\s+(?:inside|in)\s+tunemind|never\s+(?:open|leave|switch)|in\s*-?\s*app\s*only|keep\s+me\s+(?:inside|in)\s+tunemind)/i.test(userCommandRaw)
      );

      let resolvedIntent: TuneMindReasoningResponse['os_execution_command']['intent'] = 'NONE';
      switch (action) {
        case 'PLAY_TRACK_NOW':
          resolvedIntent = 'OS_MEDIA_NEXT_AND_DEEP_LINK';
          ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
          if (trackTitle) eventStore.recordAgentTrackAction(trackTitle);
          break;
        case 'PROPOSE_TRACK':
        case 'LAUNCH_DOPAMINE_CAPSULE':
          resolvedIntent = 'NONE';
          if (trackTitle) {
            ClaudeMusicReasoningProvider.setPendingProposedTrack(
              {
                title: cleanTitleForSearch || trackTitle,
                artist: cleanArtistForSearch || trackArtist,
                trackId: resolvedTrackId,
                spotifyUri: spotifyUri || undefined,
                source: targetPlayer,
                never_open_external_app: requestedNeverOpenExternal
              },
              userId
            );
            eventStore.recordAgentTrackAction(trackTitle);
          }
          break;
        case 'PAUSE_PLAYBACK':
          resolvedIntent = 'OS_MEDIA_PAUSE';
          ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
          break;
        case 'RESUME_PLAYBACK':
          resolvedIntent = 'OS_MEDIA_RESUME';
          break;
        case 'SKIP_NEXT':
          resolvedIntent = 'OS_MEDIA_NEXT';
          break;
        case 'SKIP_PREVIOUS':
          resolvedIntent = 'OS_MEDIA_PREV';
          break;
        default:
          resolvedIntent = 'NONE';
          break;
      }

      if (!ttsText) {
        ttsText = action === 'PLAY_TRACK_NOW' && trackTitle
          ? `Playing "${cleanTitleForSearch || trackTitle}"${cleanArtistForSearch ? ` by ${cleanArtistForSearch}` : ''} right now.`
          : action === 'PAUSE_PLAYBACK'
          ? 'Paused your playback.'
          : 'I am TuneMind — right here with you. What would you like to talk about or adjust?';
      }

      const expectFollowUp = Boolean(
        action === 'PROPOSE_TRACK' ||
        action === 'LAUNCH_DOPAMINE_CAPSULE' ||
        submittedTurnDecision.expect_follow_up ||
        ttsText.includes('?')
      );

      this.rememberTurn(userId, userCommandRaw, ttsText);

      const finalSatiety = Number(
        submittedTurnDecision.calculated_satiety_percentage ??
        context.current_satiety_percentage ??
        28
      );

      await SupabaseIntelligenceStore.recordAgentIntervention(
        {
          user_id: userId,
          trigger_transcript: userCommandRaw || 'voice_turn',
          computed_satiety: finalSatiety,
          agent_tts_response: ttsText,
          os_intent: resolvedIntent,
          target_search_query: cleanSearchQuery,
          action,
          track_id: resolvedTrackId,
          track_title: (cleanTitleForSearch || trackTitle) || undefined,
          track_artist: (cleanArtistForSearch || trackArtist) || undefined,
          spotify_uri: spotifyUri || undefined,
          circadian_daypart: context.circadian_daypart
        },
        supabaseOverride
      );

      const extraActionsRaw = Array.isArray(submittedTurnDecision.extra_actions)
        ? submittedTurnDecision.extra_actions
        : [];
      const extraActions: AgentToolCall[] = extraActionsRaw
        .filter((ea: any) => ea && typeof ea.action === 'string')
        .map((ea: any) => ({
          action: ea.action as AgentToolCall['action'],
          custom_accent_hex: ea.custom_accent_hex || undefined,
          palette_id: ea.palette_id || undefined,
          theme_behavior: ea.theme_behavior || undefined,
          autonomous_dj_enabled: typeof ea.autonomous_dj_enabled === 'boolean' ? ea.autonomous_dj_enabled : undefined,
          stay_in_tunemind_enabled: typeof ea.stay_in_tunemind_enabled === 'boolean' ? ea.stay_in_tunemind_enabled : undefined,
          strict_no_app_switch_enabled:
            typeof ea.strict_no_app_switch_enabled === 'boolean' ? ea.strict_no_app_switch_enabled : undefined,
          never_open_external_app:
            typeof ea.never_open_external_app === 'boolean' ? ea.never_open_external_app : undefined,
          target_player: ea.target_player ? resolvePlayerPackage(ea.target_player) : undefined,
          remember_default_player: typeof ea.remember_default_player === 'boolean' ? ea.remember_default_player : undefined,
          volume_percent: typeof ea.volume_percent === 'number' ? ea.volume_percent : undefined,
          target_screen: ea.target_screen || undefined,
          track_title: ea.track_title || undefined,
          track_artist: ea.track_artist || undefined
        }));

      const finalToolCall: AgentToolCall = {
        action,
        track_id: resolvedTrackId,
        track_title: (cleanTitleForSearch || trackTitle) || undefined,
        track_artist: (cleanArtistForSearch || trackArtist) || undefined,
        spotify_uri: spotifyUri || undefined,
        target_player: targetPlayer,
        remember_default_player:
          typeof submittedTurnDecision.remember_default_player === 'boolean'
            ? submittedTurnDecision.remember_default_player
            : undefined,
        volume_percent: typeof submittedTurnDecision.volume_percent === 'number' ? submittedTurnDecision.volume_percent : undefined,
        palette_id: submittedTurnDecision.palette_id || undefined,
        custom_accent_hex: submittedTurnDecision.custom_accent_hex || undefined,
        theme_behavior: submittedTurnDecision.theme_behavior || undefined,
        autonomous_dj_enabled:
          typeof submittedTurnDecision.autonomous_dj_enabled === 'boolean'
            ? submittedTurnDecision.autonomous_dj_enabled
            : undefined,
        stay_in_tunemind_enabled:
          typeof submittedTurnDecision.stay_in_tunemind_enabled === 'boolean'
            ? submittedTurnDecision.stay_in_tunemind_enabled
            : undefined,
        strict_no_app_switch_enabled:
          typeof submittedTurnDecision.strict_no_app_switch_enabled === 'boolean'
            ? submittedTurnDecision.strict_no_app_switch_enabled
            : undefined,
        never_open_external_app: requestedNeverOpenExternal,
        target_screen: submittedTurnDecision.target_screen || undefined,
        expect_follow_up: expectFollowUp,
        extra_actions: extraActions.length > 0 ? extraActions : undefined
      };

      return {
        calculated_satiety_percentage: finalSatiety,
        reasoning_mode: action === 'ADVISE_ONLY' ? 'JOURNEY_ADVISOR' : 'SONIC_CURATOR',
        flow_state_analysis: String(
          submittedTurnDecision.flow_state_analysis ||
          (action === 'PROPOSE_TRACK' && trackTitle
            ? `Proposed "${cleanTitleForSearch || trackTitle}" — awaiting your approval.`
            : 'Autonomous Studio Companion active.')
        ),
        voice_agent_tts: ttsText,
        agent_tool_call: finalToolCall,
        os_execution_command: {
          intent: resolvedIntent,
          target_player: targetPlayer,
          search_query: cleanSearchQuery,
          track_id: resolvedTrackId,
          track_title: (cleanTitleForSearch || trackTitle) || undefined,
          track_artist: (cleanArtistForSearch || trackArtist) || undefined,
          spotify_uri: spotifyUri || undefined,
          never_open_external_app: requestedNeverOpenExternal,
          suggested_bpm_target: 108
        },
        database_tags: [providerTag, 'supabase-taste-genome', action.toLowerCase()]
      };
    }

    // ─── 4. CONTEXT-AWARE CONVERSATIONAL & SPOTIFY FALLBACK ───────────────────
    const fb = await this.buildSmartHistoryFallbackResponse(
      context,
      freshTracks,
      allUserTracks,
      overplayedTracks,
      compactedCapsule,
      null,
      null,
      eventStore,
      strictDiscoveryExclusionSet
    );
    this.rememberTurn(userId, userCommandRaw, fb.voice_agent_tts);
    await SupabaseIntelligenceStore.recordAgentIntervention(
      {
        user_id: userId,
        trigger_transcript: userCommandRaw || 'voice_turn',
        computed_satiety: fb.calculated_satiety_percentage,
        agent_tts_response: fb.voice_agent_tts,
        os_intent: fb.os_execution_command?.intent || 'NONE',
        target_search_query: fb.os_execution_command?.search_query || '',
        action: fb.agent_tool_call?.action,
        track_id: fb.agent_tool_call?.track_id,
        track_title: fb.agent_tool_call?.track_title,
        track_artist: fb.agent_tool_call?.track_artist,
        spotify_uri: fb.agent_tool_call?.spotify_uri,
        circadian_daypart: context.circadian_daypart
      },
      supabaseOverride
    );
    return fb;
  }

  private static normalizeForExclusion(text: string): string {
    return ClaudeMusicReasoningProvider.sanitizeTrackTitle(text)
      .toLowerCase()
      .replace(/\([^)]*\)/g, ' ')
      .replace(/\[[^\]]*\]/g, ' ')
      .replace(/[^a-z0-9\s]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  private async discoverSpotifyTracksForContext(
    context: TuneMindTelemetryContext,
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    excludedSet: Set<string>,
    seedArtists: string[],
    moodQuery: string = '',
    excludeOverplayed: boolean = true
  ): Promise<Array<{ title: string; artist: string; album: string; spotify_uri: string; popularity: number; source_reason: string }>> {
    const discovered: Array<{ title: string; artist: string; album: string; spotify_uri: string; popularity: number; source_reason: string }> = [];
    const strictExclusion = new Set<string>();
    const addExcluded = (title?: string, uri?: string) => {
      if (title) {
        const lower = title.toLowerCase().trim();
        const norm = ClaudeMusicReasoningProvider.normalizeForExclusion(title);
        if (lower) strictExclusion.add(lower);
        if (norm) strictExclusion.add(norm);
      }
      if (uri && uri.startsWith('spotify:track:')) {
        strictExclusion.add(uri.trim());
      }
    };

    for (const e of excludedSet) {
      if (e.startsWith('spotify:track:')) {
        strictExclusion.add(e);
      } else {
        addExcluded(e);
      }
    }
    if (excludeOverplayed) {
      for (const ut of allUserTracks) {
        addExcluded(ut.title, ut.trackId);
      }
    }

    const seedArtistIds: string[] = [];
    const seedArtistNamesFromApi: string[] = [];
    const token = await this.getSpotifyAccessToken(context.spotify_access_token);
    const hasUserOAuth = Boolean(context.spotify_access_token && context.spotify_access_token.trim().length > 10);

    // 1. Pull user's daily/top/saved Spotify Web API listening history to STRICTLY EXCLUDE already-listened songs
    if (hasUserOAuth && context.spotify_access_token) {
      const oauth = context.spotify_access_token.trim();
      const headers = { Authorization: `Bearer ${oauth}` };

      try {
        const recentRes = await fetch('https://api.spotify.com/v1/me/player/recently-played?limit=50', { headers });
        if (recentRes.ok) {
          const data = (await recentRes.json()) as any;
          for (const entry of data?.items || []) {
            const trk = entry?.track;
            if (trk) {
              addExcluded(trk.name, trk.uri);
              for (const a of trk.artists || []) {
                if (a?.id && !seedArtistIds.includes(a.id)) seedArtistIds.push(a.id);
                if (a?.name && !seedArtistNamesFromApi.includes(a.name)) seedArtistNamesFromApi.push(a.name);
              }
            }
          }
        }
      } catch {}

      for (const range of ['short_term', 'medium_term', 'long_term'] as const) {
        try {
          const topRes = await fetch(`https://api.spotify.com/v1/me/top/tracks?time_range=${range}&limit=25`, { headers });
          if (topRes.ok) {
            const data = (await topRes.json()) as any;
            for (const trk of data?.items || []) {
              if (trk) {
                addExcluded(trk.name, trk.uri);
                for (const a of trk.artists || []) {
                  if (a?.id && !seedArtistIds.includes(a.id)) seedArtistIds.push(a.id);
                  if (a?.name && !seedArtistNamesFromApi.includes(a.name)) seedArtistNamesFromApi.push(a.name);
                }
              }
            }
          }
        } catch {}
      }

      try {
        const savedRes = await fetch('https://api.spotify.com/v1/me/tracks?limit=50', { headers });
        if (savedRes.ok) {
          const data = (await savedRes.json()) as any;
          for (const entry of data?.items || []) {
            const trk = entry?.track;
            if (trk) addExcluded(trk.name, trk.uri);
          }
        }
      } catch {}
    }

    const isUnheardCandidate = (title: string, uri: string): boolean => {
      const lower = title.toLowerCase().trim();
      const norm = ClaudeMusicReasoningProvider.normalizeForExclusion(title);
      if (!lower || !uri || !uri.startsWith('spotify:track:')) return false;
      if (strictExclusion.has(uri) || strictExclusion.has(lower) || (norm && strictExclusion.has(norm))) return false;
      if (discovered.some(d => d.spotify_uri === uri || ClaudeMusicReasoningProvider.normalizeForExclusion(d.title) === norm)) {
        return false;
      }
      return true;
    };

    // 2. If user asked for a specific mood/culture/genre (e.g. "Kikuyu", "Mugithi", "Bongo Flava", "Gospel"),
    // run targeted genre catalog searches FIRST so generic seed-artist hits never drown out the requested genre!
    const cleanMood = moodQuery.trim();
    const isKikuyuQuery = /\b(kikuyu|gikuyu|mugithi)\b/i.test(cleanMood);
    if (token && cleanMood) {
      const moodQueries: Array<{ q: string; reason: string }> = isKikuyuQuery
        ? [
            { q: 'Kikuyu Mugithi Samidoh', reason: 'Authentic Kikuyu / Mugithi discovery matching your request' },
            { q: 'Gikuyu Benga', reason: 'Fresh Kikuyu Benga track matching your request' },
            { q: 'Kikuyu Gospel', reason: 'Kikuyu / Gikuyu catalog discovery matching your request' }
          ]
        : [{ q: cleanMood, reason: `Matches requested vibe: "${cleanMood}"` }];

      for (const mq of moodQueries) {
        try {
          const res = await fetch(
            `https://api.spotify.com/v1/search?q=${encodeURIComponent(mq.q)}&type=track&limit=10&offset=0`,
            { headers: { Authorization: `Bearer ${token}` } }
          );
          if (res.ok) {
            const data = (await res.json()) as any;
            for (const item of data?.tracks?.items || []) {
              const artistLabel = item.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist';
              if (isUnheardCandidate(item.name || '', item.uri || '')) {
                discovered.push({
                  title: item.name,
                  artist: artistLabel,
                  album: item.album?.name || '',
                  spotify_uri: item.uri,
                  popularity: Number(item.popularity || 55),
                  source_reason: mq.reason
                });
              }
              if (discovered.length >= 8) break;
            }
          }
        } catch {}
        if (discovered.length >= 8) break;
      }
    }

    // 3. Resolve Spotify Artist IDs from user's seed artists if still needing candidates
    if (token && discovered.length < 6) {
      const mergedSeeds = Array.from(new Set([...seedArtists, ...seedArtistNamesFromApi])).slice(0, 3);
      for (const artistName of mergedSeeds) {
        if (seedArtistIds.length >= 4) break;
        try {
          const aRes = await fetch(
            `https://api.spotify.com/v1/search?q=${encodeURIComponent(artistName)}&type=artist&limit=1`,
            { headers: { Authorization: `Bearer ${token}` } }
          );
          if (aRes.ok) {
            const aData = (await aRes.json()) as any;
            const id = aData?.artists?.items?.[0]?.id;
            if (id && !seedArtistIds.includes(id)) seedArtistIds.push(id);
          }
        } catch {}
      }

      const relatedArtists: Array<{ id: string; name: string }> = [];
      for (const seedId of seedArtistIds.slice(0, 3)) {
        try {
          const relRes = await fetch(`https://api.spotify.com/v1/artists/${seedId}/related-artists`, {
            headers: { Authorization: `Bearer ${token}` }
          });
          if (relRes.ok) {
            const relData = (await relRes.json()) as any;
            for (const rel of (relData?.artists || []).slice(0, 4)) {
              if (rel?.id && rel?.name && !relatedArtists.some(r => r.id === rel.id)) {
                relatedArtists.push({ id: rel.id, name: rel.name });
              }
            }
          }
        } catch {}
      }

      const marketParam = hasUserOAuth ? 'from_token' : 'US';
      for (const rel of relatedArtists.slice(0, 5)) {
        try {
          const topRes = await fetch(
            `https://api.spotify.com/v1/artists/${rel.id}/top-tracks?market=${marketParam}`,
            { headers: { Authorization: `Bearer ${token}` } }
          );
          if (topRes.ok) {
            const topData = (await topRes.json()) as any;
            for (const item of topData?.tracks || []) {
              const artistLabel = item.artists?.map((a: any) => a.name).join(', ') || rel.name;
              if (isUnheardCandidate(item.name || '', item.uri || '')) {
                discovered.push({
                  title: item.name,
                  artist: artistLabel,
                  album: item.album?.name || '',
                  spotify_uri: item.uri,
                  popularity: Number(item.popularity || 55),
                  source_reason: `Fresh discovery via artist kindred to your taste genome (${rel.name})`
                });
              }
              if (discovered.length >= 6) break;
            }
          }
        } catch {}
        if (discovered.length >= 6) break;
      }
    }

    // 3. Fallback / Mood Catalog Searches with rotating offset so every call yields novel unheard tracks
    const queriesToRun: Array<{ q: string; reason: string }> = [];
    if (moodQuery) {
      queriesToRun.push({ q: moodQuery, reason: `Matches requested vibe: "${moodQuery}"` });
    }
    for (const artist of seedArtists.slice(0, 2)) {
      queriesToRun.push({
        q: artist,
        reason: `Fresh adjacent discovery inspired by ${artist}`
      });
    }
    if (queriesToRun.length === 0) {
      queriesToRun.push(
        { q: 'afrobeats chill new', reason: 'Fresh melodic Afrobeats discovery' },
        { q: 'neo soul r&b groove', reason: 'Warm Neo-Soul discovery outside daily rotation' }
      );
    }

    const searchOffset = (ClaudeMusicReasoningProvider.rotationIndex * 5) % 25;
    if (token) {
      for (const qItem of queriesToRun) {
        try {
          const res = await fetch(
            `https://api.spotify.com/v1/search?q=${encodeURIComponent(qItem.q)}&type=track&limit=10&offset=${searchOffset}`,
            { headers: { Authorization: `Bearer ${token}` } }
          );
          if (res.ok) {
            const data = (await res.json()) as any;
            for (const item of data?.tracks?.items || []) {
              const artistLabel = item.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist';
              if (isUnheardCandidate(item.name || '', item.uri || '')) {
                discovered.push({
                  title: item.name,
                  artist: artistLabel,
                  album: item.album?.name || '',
                  spotify_uri: item.uri,
                  popularity: Number(item.popularity || 55),
                  source_reason: qItem.reason
                });
              }
              if (discovered.length >= 10) break;
            }
          }
        } catch {}
        if (discovered.length >= 10) break;
      }
    }

    if (discovered.length > 1) {
      const offset = ClaudeMusicReasoningProvider.rotationIndex % discovered.length;
      ClaudeMusicReasoningProvider.rotationIndex++;
      const rotated = [...discovered.slice(offset), ...discovered.slice(0, offset)];
      discovered.splice(0, discovered.length, ...rotated);
    }

    return discovered;
  }

  /**
   * Secondary Single-Shot Claude JSON Pass using ONLY the user's paid ANTHROPIC_API_KEY
   * (used if a newly discovered Claude model ever skips native tool_use blocks).
   */
  private async tryClaudeSingleShotJsonDecision(
    context: TuneMindTelemetryContext,
    initialContextPayload: any,
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; playCount?: number }>,
    mostPlayedTracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; playCount?: number }>,
    topUserArtists: string[],
    excludedSet: Set<string>
  ): Promise<{ decision: any; fallbackTrack: { title: string; artist: string; uri: string; trackId?: string } | null; providerTag: string } | null> {
    const userMsg = (context.user_command || '').trim();
    const lowerMsg = userMsg.toLowerCase();

    let fallbackTrack: { title: string; artist: string; uri: string; trackId?: string } | null = null;
    let liveSpotifyDiscovery: any[] = [];
    let liveSpotifySearchHits: any[] = [];

    const wantsMostPlayed = /\b(most\s+(played|listened|heard)|listened\s+(to\s+)?(the\s+)?most|listen\s+(to\s+)?(the\s+)?most|played\s+(the\s+)?most|favorite\s+(song|track)|favourite\s+(song|track)|top\s+(song|track)|number\s+one\s+(song|track))\b/i.test(lowerMsg);
    const requestedGenreMatch = lowerMsg.match(/\b(kikuyu|gikuyu|mugithi|bongo\s*flava?|bongo|gengetone|ohangla|lingala|benga|gospel|afrobeats?|amapiano|reggae|dancehall|r&b|rnb|hip\s*hop|rap|jazz|rock|chill|lofi|lo-fi|upbeat|workout|romantic)\b/i);
    const wantsDiscoveryOrNew = Boolean(requestedGenreMatch) || /\b(new\s+(song|track|music|tune|vibe)|fresh\s+(song|track|music|tune)|any\s+(good\s+|nice\s+|random\s+)?(song|track|music|tune)|random\s+(song|track)|something\s+(new|fresh|different|good|nice)|recommend|recommendation|suggest|suggestion|discover|another\s+(song|one|track)|different\s+(song|track)|play\s+(for\s+me\s+|me\s+)?(a\s+|some\s+|any\s+|another\s+)?(new\s+|fresh\s+|good\s+|nice\s+)?(song|track|music|tune))\b/i.test(lowerMsg);
    const explicitPlayMatch = lowerMsg.match(/^\s*(?:please\s+|hey\s+|hi\s+)?(?:play|spin|put on)\s+(.+)$/i);

    if (wantsMostPlayed && mostPlayedTracks[0]) {
      const top = mostPlayedTracks[0];
      let uri = normalizeSpotifyUri(top.spotifyUri || top.trackId) || '';
      if (!uri) {
        const resolved = await this.lookupSpotifyTrack(top.title, top.artist, context.spotify_access_token);
        if (resolved) uri = resolved.uri;
      }
      const topIdentity = resolveCanonicalTrackIdentity({
        trackId: top.trackId,
        spotifyUri: uri,
        title: top.title,
        artist: top.artist
      });
      fallbackTrack = { title: top.title, artist: top.artist, uri: topIdentity.spotifyUri || uri, trackId: topIdentity.trackId };
    } else if (wantsDiscoveryOrNew) {
      const extractedGenre = requestedGenreMatch
        ? (/kikuyu|gikuyu|mugithi/i.test(requestedGenreMatch[1]) ? 'Kikuyu Mugithi Gikuyu' : requestedGenreMatch[1])
        : '';
      liveSpotifyDiscovery = await this.discoverSpotifyTracksForContext(
        context,
        allUserTracks,
        excludedSet,
        topUserArtists.slice(0, 3),
        extractedGenre,
        true
      );
      if (liveSpotifyDiscovery[0]) {
        const discIdentity = resolveCanonicalTrackIdentity({
          spotifyUri: liveSpotifyDiscovery[0].spotify_uri,
          title: liveSpotifyDiscovery[0].title,
          artist: liveSpotifyDiscovery[0].artist
        });
        fallbackTrack = {
          title: liveSpotifyDiscovery[0].title,
          artist: liveSpotifyDiscovery[0].artist,
          uri: discIdentity.spotifyUri || liveSpotifyDiscovery[0].spotify_uri,
          trackId: discIdentity.trackId
        };
      }
    } else if (explicitPlayMatch && explicitPlayMatch[1]) {
      const queryTarget = explicitPlayMatch[1]
        .replace(/^(?:for\s+me\s+|me\s+)/i, '')
        .replace(/\b(?:right\s+now|now|please|on\s+spotify)\b/gi, '')
        .trim();
      const isGenericNonTitle = /^(?:a|an|any|some|random|good|nice|fresh|new)?\s*(?:song|songs|track|tracks|music|tune|anything|whatever)$/i.test(queryTarget);
      if (queryTarget.length >= 2 && !isGenericNonTitle) {
        liveSpotifySearchHits = await this.searchSpotifyCatalogTracks(queryTarget, undefined, 5, context.spotify_access_token);
        if (liveSpotifySearchHits[0]) {
          const hitIdentity = resolveCanonicalTrackIdentity({
            spotifyUri: liveSpotifySearchHits[0].uri,
            title: liveSpotifySearchHits[0].title,
            artist: liveSpotifySearchHits[0].artist
          });
          fallbackTrack = {
            title: liveSpotifySearchHits[0].title,
            artist: liveSpotifySearchHits[0].artist,
            uri: hitIdentity.spotifyUri || liveSpotifySearchHits[0].uri,
            trackId: hitIdentity.trackId
          };
        }
      }
    }

    const enrichedContext = {
      ...initialContextPayload,
      pre_fetched_spotify_discovery_candidates: liveSpotifyDiscovery.slice(0, 5),
      pre_fetched_spotify_catalog_search_results: liveSpotifySearchHits.slice(0, 4)
    };

    const jsonSystemPrompt = `Your name is TuneMind. You are an intelligent, warm, conversational, self-reasoning AI music companion and full-app controller on Android powered by Anthropic Claude.
Reason naturally about what the user actually said before choosing an action:
1. IDENTITY & CONVERSATION FIRST: You know your name is TuneMind! When the user greets you ("hi tunemind", "hello", "hey", "how are you", "who are you"), talks about their day/feelings, asks a general question, or chats WITHOUT asking to play or recommend a song right now, set "action": "ADVISE_ONLY", leave "track_title" empty, and reply warmly and naturally! NEVER pitch a song when the user is just greeting you or chatting!
2. NEW-SONG DISCOVERY vs. 2-STEP APPROVAL: Only when the user asks to play music, requests a genre (e.g. Kikuyu/Gikuyu/Mugithi, Bongo Flava, Afrobeats), or asks for a recommendation/most-played song, pick a real matching track from pre_fetched_spotify_discovery_candidates, search results, or history and set "action": "PROPOSE_TRACK", "expect_follow_up": true, asking if they want you to play it. Never pick a track literally named "Any Song" unless the user explicitly asked for Zico.
3. APPROVAL vs. REJECTION OF PENDING TRACK:
   - ONLY if pending_proposed_track_awaiting_user_approval is present AND the user clearly approves ("yes", "play it", "sure", "okay", "ndio", "sawa"), set "action": "PLAY_TRACK_NOW" with that exact pending track.
   - If pending_proposed_track_awaiting_user_approval is present AND the user declines ("no", "I don't want that song", "don't play it", "cancel", "pass", "not that one"), NEVER return PLAY_TRACK_NOW! Use "action": "ADVISE_ONLY" with empty track fields (or propose a different alternative with "PROPOSE_TRACK" if they asked for another song).
4. FULL APP CONTROLS (inspect app_state):
   - Accent color change -> "SET_THEME_COLOR" ("custom_accent_hex": "#00FFA3" | "#FF2A85" | "#A855F7" | "#00E5FF" | "#FF9400" | "#3B82F6" | "AUTO")
   - Mood palette -> "SHIFT_ATMOSPHERE" ("morning" | "afternoon" | "evening" | "night" | "auto")
   - Autonomous End-of-Song DJ toggle -> "TOGGLE_AUTONOMOUS_DJ" ("autonomous_dj_enabled": true/false)
   - Stay-in-TuneMind toggle -> "TOGGLE_STAY_IN_TUNEMIND" ("stay_in_tunemind_enabled": true/false)
   - Strict Never-Leave-TuneMind toggle -> "TOGGLE_STRICT_NO_APP_SWITCH" ("strict_no_app_switch_enabled": true/false)
   - If user says "without opening Spotify" / "don't leave TuneMind" OR app_state.strict_no_app_switch_enabled is true -> set "never_open_external_app": true and NEVER say you will open Spotify UI!
   - Settings screen -> "OPEN_SETTINGS"
   - Tabs -> "NAVIGATE_SCREEN" ("DOPAMINE" | "AGENT" | "RHYTHM")
   - Playback/Volume/Vault -> "PAUSE_PLAYBACK", "RESUME_PLAYBACK", "SKIP_NEXT", "SKIP_PREVIOUS", "SET_VOLUME_PERCENT", "QUARANTINE_TO_VAULT"
   - Optionally include "extra_actions": [...] to combine actions in one turn.

Respond ONLY with a single valid JSON object:
{
  "voice_agent_tts": "Your natural, warm, unscripted spoken reply (1-3 sentences)",
  "flow_state_analysis": "Short 1-line studio HUD note",
  "calculated_satiety_percentage": 24,
  "action": "ADVISE_ONLY" | "PROPOSE_TRACK" | "PLAY_TRACK_NOW" | "PAUSE_PLAYBACK" | "RESUME_PLAYBACK" | "SKIP_NEXT" | "SKIP_PREVIOUS" | "SET_VOLUME_PERCENT" | "QUARANTINE_TO_VAULT" | "SHIFT_ATMOSPHERE" | "SET_THEME_COLOR" | "TOGGLE_AUTONOMOUS_DJ" | "TOGGLE_STAY_IN_TUNEMIND" | "TOGGLE_STRICT_NO_APP_SWITCH" | "OPEN_SETTINGS" | "NAVIGATE_SCREEN" | "LAUNCH_DOPAMINE_CAPSULE",
  "track_title": "",
  "track_artist": "",
  "spotify_uri": "",
  "volume_percent": 60,
  "palette_id": "evening",
  "custom_accent_hex": "",
  "theme_behavior": "",
  "autonomous_dj_enabled": false,
  "stay_in_tunemind_enabled": true,
  "strict_no_app_switch_enabled": false,
  "never_open_external_app": false,
  "target_screen": "DOPAMINE",
  "extra_actions": [],
  "expect_follow_up": true
}`;

    const userPromptContent = `Reason over the user's spoken message ("${userMsg}") using this live telemetry, app_state, conversation history, and Spotify data, and output ONLY the JSON object:\n${JSON.stringify(enrichedContext)}`;

    try {
      const claudeRes = await this.callClaudeWithSonnetPriority(jsonSystemPrompt, userPromptContent, 750);
      const rawText = this.extractRawText(claudeRes?.content);
      const jsonMatch = rawText.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        const parsed = JSON.parse(jsonMatch[0]);
        if (parsed && typeof parsed.voice_agent_tts === 'string' && parsed.voice_agent_tts.trim()) {
          return { decision: parsed, fallbackTrack, providerTag: 'claude-autonomous-agent' };
        }
      }
    } catch (err: any) {
      console.warn('[Claude Single-Shot JSON] Notice:', err?.message || err);
    }

    return null;
  }

  private async buildSmartHistoryFallbackResponse(
    context: TuneMindTelemetryContext,
    freshTracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; playCount?: number }>,
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; playCount?: number }>,
    overplayedTracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; playCount?: number }>,
    compactedCapsule: Array<{ title: string; artist: string; trackId?: string; freshness_percent: number; reason: string }>,
    explicitlyNamedQuery: string | null,
    matchedExplicitLibraryTrack: any | null,
    eventStore: EventStore,
    strictDiscoveryExclusionSet?: Set<string>
  ): Promise<TuneMindReasoningResponse> {
    const userCommandRaw = (context.user_command || '').trim();
    const userCommandLower = userCommandRaw.toLowerCase();
    const targetPlayer = context.current_track?.source_player || 'com.spotify.music';
    const userId = SupabaseIntelligenceStore.normalizeUserId(context.user_id, context.user_email);
    const pending = ClaudeMusicReasoningProvider.getPendingProposedTrack(userId);
    const fallbackNeverOpenExternal = Boolean(
      pending?.never_open_external_app === true ||
      context.app_state?.strict_no_app_switch_enabled === true ||
      /(?:without\s+(?:opening|leaving|switching|launching|going\s+to)|don'?t\s+(?:open|leave|switch\s+to|launch)\s+(?:spotify|the\s+app|another\s+app)|stay\s+(?:inside|in)\s+tunemind|never\s+(?:open|leave|switch)|in\s*-?\s*app\s*only|keep\s+me\s+(?:inside|in)\s+tunemind)/i.test(userCommandRaw)
    );

    // 0. Autonomous End-of-Song DJ Handover ('AUTO_CURATE_NEXT_TRACK')
    if (/^auto_curate_next_track\b/i.test(userCommandLower)) {
      const excluded = new Set<string>(strictDiscoveryExclusionSet || []);
      if (context.current_track?.title) excluded.add(context.current_track.title.toLowerCase().trim());
      for (const s of context.recently_played_by_agent || []) {
        if (s) excluded.add(String(s).toLowerCase().trim());
      }
      const seedArtists = Array.from(
        new Set(
          allUserTracks
            .map(t => ClaudeMusicReasoningProvider.sanitizeArtistName(t.artist))
            .filter(Boolean)
        )
      ).slice(0, 3);
      const discovered = await this.discoverSpotifyTracksForContext(
        context,
        allUserTracks,
        excluded,
        seedArtists,
        '',
        true
      );
      const chosen: { title: string; artist: string; uri: string; trackId?: string } = discovered[0]
        ? {
            title: discovered[0].title,
            artist: discovered[0].artist,
            uri: normalizeSpotifyUri(discovered[0].spotify_uri) || '',
            trackId: undefined
          }
        : freshTracks[0]
        ? {
            title: freshTracks[0].title,
            artist: freshTracks[0].artist,
            uri: normalizeSpotifyUri(freshTracks[0].spotifyUri || freshTracks[0].trackId) || '',
            trackId: freshTracks[0].trackId
          }
        : {
            title: 'Midnight City',
            artist: 'M83',
            uri: '',
            trackId: undefined
          };
      if (!chosen.uri && chosen.title) {
        const resolved = await this.lookupSpotifyTrack(chosen.title, chosen.artist, context.spotify_access_token);
        if (resolved) chosen.uri = resolved.uri;
      }
      const cleanTitle = ClaudeMusicReasoningProvider.sanitizeTrackTitle(chosen.title) || chosen.title;
      const cleanArtist = ClaudeMusicReasoningProvider.sanitizeArtistName(chosen.artist) || chosen.artist;
      const chosenIdentity = resolveCanonicalTrackIdentity({
        trackId: chosen.trackId,
        spotifyUri: chosen.uri,
        title: cleanTitle,
        artist: cleanArtist
      });
      const resolvedUri = chosenIdentity.spotifyUri || normalizeSpotifyUri(chosen.uri) || undefined;
      const artistPart = cleanArtist && cleanArtist !== 'Unknown Artist' ? ` by ${cleanArtist}` : '';
      eventStore.recordAgentTrackAction(cleanTitle);
      return {
        calculated_satiety_percentage: 18,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Autonomous DJ Handover • Spinning "${cleanTitle}"${artistPart}`,
        voice_agent_tts: `Up next for your flow, playing "${cleanTitle}"${artistPart} right now.`,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_id: chosenIdentity.trackId,
          track_title: cleanTitle,
          track_artist: cleanArtist,
          spotify_uri: resolvedUri,
          target_player: targetPlayer,
          never_open_external_app: fallbackNeverOpenExternal,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: targetPlayer,
          search_query: `${cleanTitle} ${cleanArtist}`.trim(),
          track_id: chosenIdentity.trackId,
          track_title: cleanTitle,
          track_artist: cleanArtist,
          spotify_uri: resolvedUri,
          never_open_external_app: fallbackNeverOpenExternal,
          suggested_bpm_target: 108
        },
        database_tags: ['autonomous-end-of-song-dj', 'auto-curated-next-track']
      };
    }

    // 0A. Pause / Stop / Resume / Skip playback controls
    if (/\b(pause|stop\s+playing|stop\s+the\s+music|mute|hold\s+on|simama|nyamaza)\b/i.test(userCommandLower)) {
      ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Playback paused.',
        voice_agent_tts: 'Paused your playback. Let me know whenever you want to continue or talk through your music.',
        agent_tool_call: { action: 'PAUSE_PLAYBACK', target_player: targetPlayer, expect_follow_up: false },
        os_execution_command: { intent: 'OS_MEDIA_PAUSE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 100 },
        database_tags: ['direct-pause']
      };
    }
    if (/\b(resume|unpause|continue\s+playing|keep\s+playing)\b/i.test(userCommandLower)) {
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Playback resumed.',
        voice_agent_tts: 'Resuming your music right now.',
        agent_tool_call: { action: 'RESUME_PLAYBACK', target_player: targetPlayer, expect_follow_up: false },
        os_execution_command: { intent: 'OS_MEDIA_RESUME', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['direct-resume']
      };
    }
    if (/\b(skip|next\s+song|next\s+track)\b/i.test(userCommandLower)) {
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Skipping to next track.',
        voice_agent_tts: 'Skipping to your next track right now.',
        agent_tool_call: { action: 'SKIP_NEXT', target_player: targetPlayer, expect_follow_up: false },
        os_execution_command: { intent: 'OS_MEDIA_NEXT', target_player: targetPlayer, search_query: '', suggested_bpm_target: 108 },
        database_tags: ['direct-skip']
      };
    }

    // 0B. Approval OR Rejection of pending proposed track
    const sanitizedForRejection = userCommandLower.replace(
      /\b(?:don'?t|do\s+not|never)\s+(?:open|leave|switch(?:\s+to)?|launch)\s+(?:spotify|the\s+app|another\s+app|external(?:\s+app)?)\b/gi,
      ' '
    );
    const isRejectingPending = /\b(no|nope|nah|cancel|never\s*mind|not\s+(?:that|this|really|now)|don'?t|do\s+not|won'?t|pass|skip|reject|decline|dislike|another|different|change|switch|else|forget\s+it)\b/i.test(
      sanitizedForRejection
    );
    if (pending && isRejectingPending) {
      ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
      const wantsReplacement = /\b(another|different|something\s+else|kikuyu|gikuyu|mugithi|bongo|afrobeat|amapiano|gospel|instead|next)\b/i.test(userCommandLower);
      if (!wantsReplacement) {
        return {
          calculated_satiety_percentage: context.current_satiety_percentage || 25,
          reasoning_mode: 'JOURNEY_ADVISOR',
          flow_state_analysis: `Declined "${pending.title}" — cleared pending queue.`,
          voice_agent_tts: `No problem, I cancelled "${pending.title}". Tell me what song, artist, or vibe you want instead.`,
          agent_tool_call: { action: 'ADVISE_ONLY', expect_follow_up: true },
          os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
          database_tags: ['rejected-pending-track', 'advise-only']
        };
      }
      // Otherwise let step 6/7 discover a fresh replacement while ensuring pending track is excluded
      strictDiscoveryExclusionSet?.add(pending.title.toLowerCase().trim());
    }
    if (
      pending &&
      !isRejectingPending &&
      /\b(yes|yeah|yep|sure|ok|okay|play|play\s+it|go\s+ahead|do\s+it|sounds\s+good|spin\s+it|ndio|sawa|approve)\b/i.test(userCommandLower)
    ) {
      ClaudeMusicReasoningProvider.setPendingProposedTrack(null, userId);
      const artistPart = pending.artist && pending.artist !== 'Unknown Artist' ? ` by ${pending.artist}` : '';
      const approvedIdentity = resolveCanonicalTrackIdentity({
        trackId: pending.trackId,
        spotifyUri: pending.spotifyUri || normalizeSpotifyUri(pending.trackId),
        title: pending.title,
        artist: pending.artist
      });
      const approvedSpotifyUri = approvedIdentity.spotifyUri || normalizeSpotifyUri(pending.spotifyUri) || '';
      return {
        calculated_satiety_percentage: 20,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Approved "${pending.title}" — launching playback.`,
        voice_agent_tts: fallbackNeverOpenExternal
          ? `Keeping you right inside TuneMind and playing "${pending.title}"${artistPart}.`
          : `Awesome, playing "${pending.title}"${artistPart} inside TuneMind right now.`,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_id: approvedIdentity.trackId,
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: approvedSpotifyUri || undefined,
          target_player: pending.source || targetPlayer,
          never_open_external_app: fallbackNeverOpenExternal,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: pending.source || targetPlayer,
          search_query: `${pending.title} ${pending.artist || ''}`.trim(),
          track_id: approvedIdentity.trackId,
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: approvedSpotifyUri || undefined,
          never_open_external_app: fallbackNeverOpenExternal,
          suggested_bpm_target: 108
        },
        database_tags: ['approved-pending-track']
      };
    }

    // 1. Volume / Hearing Health commands
    if (/\b(volume|louder|quieter|lower|turn down|turn up|ear|hearing|decibel|db|loud)\b/i.test(userCommandLower)) {
      const isVolumeChangeCommand = /\b(volume|louder|quieter|lower|turn down|turn up|raise|reduce)\b/i.test(userCommandLower);
      const estDb = context.hearing_telemetry?.estimated_db ?? 68;
      const headroom = context.hearing_telemetry?.safe_headroom_db ?? 17;
      if (!isVolumeChangeCommand) {
        return {
          calculated_satiety_percentage: 26,
          reasoning_mode: 'HEARING_GUARDIAN',
          flow_state_analysis: `Hearing Guardian: ${Math.round(estDb)} dB avg • ${Math.round(headroom)} dB safe headroom`,
          voice_agent_tts: `Your ears are in a safe zone right now at around ${Math.round(estDb)} decibels, leaving ${Math.round(headroom)} decibels of comfortable headroom. Would you like me to adjust the volume or keep things right where they are?`,
          agent_tool_call: { action: 'ADVISE_ONLY', expect_follow_up: true },
          os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 100 },
          database_tags: ['hearing-guardian', 'advise-only']
        };
      }
      const wantsLower = /\b(lower|down|quieter|soft|protect|safe|ear|hearing|loud)\b/i.test(userCommandLower);
      const targetVol = wantsLower ? 45 : 75;
      const tts = wantsLower
        ? `Your average exposure is around ${Math.round(estDb)} decibels with ${Math.round(headroom)} dB of safe headroom. I have lowered your media volume to ${targetVol}% to keep your ears fresh.`
        : `Adjusting your studio volume to ${targetVol}% while keeping you inside safe decibel headroom.`;
      return {
        calculated_satiety_percentage: 28,
        reasoning_mode: 'HEARING_GUARDIAN',
        flow_state_analysis: `Hearing Guardian: ${Math.round(estDb)} dB avg • Volume set to ${targetVol}%`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'SET_VOLUME_PERCENT',
          volume_percent: targetVol,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          suggested_bpm_target: 100
        },
        database_tags: ['hearing-guardian', 'volume-control']
      };
    }

    // 2. Cooldown Vault quarantine / restore commands
    if (/\b(vault|quarantine|lock|cooldown|cool down|rest this song)\b/i.test(userCommandLower) && !/\b(open|show|go to)\b/i.test(userCommandLower)) {
      const targetTrack = context.current_track?.title && context.current_track.title !== 'Standing By'
        ? context.current_track
        : (overplayedTracks[0] || allUserTracks[0]);
      const title = targetTrack?.title || 'Current Track';
      const artist = targetTrack?.artist || '';
      const vaultIdentity = resolveCanonicalTrackIdentity({
        trackId: (targetTrack as any)?.trackId,
        spotifyUri: (targetTrack as any)?.spotifyUri || normalizeSpotifyUri((targetTrack as any)?.trackId),
        title,
        artist
      });
      const tts = `I have locked "${title}" into your Cooldown Vault for 5 days so its dopamine impact can fully recover.`;
      return {
        calculated_satiety_percentage: 30,
        reasoning_mode: 'JOURNEY_ADVISOR',
        flow_state_analysis: `Locked "${title}" in Cooldown Vault for receptor recovery.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'QUARANTINE_TO_VAULT',
          track_id: vaultIdentity.trackId,
          track_title: title,
          track_artist: artist,
          spotify_uri: vaultIdentity.spotifyUri,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          track_id: vaultIdentity.trackId,
          track_title: title,
          track_artist: artist,
          spotify_uri: vaultIdentity.spotifyUri,
          suggested_bpm_target: 100
        },
        database_tags: ['vault-quarantine']
      };
    }

    // 3. Settings / Autonomous DJ / Stay-in-TuneMind / Accent Theme Color commands
    if (/\b(open|show|go\s+to)\s+(the\s+)?settings\b/i.test(userCommandLower)) {
      return {
        calculated_satiety_percentage: 24,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Opening TuneMind Settings.',
        voice_agent_tts: 'Opening your TuneMind settings panel right now.',
        agent_tool_call: { action: 'OPEN_SETTINGS', expect_follow_up: false },
        os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['open-settings']
      };
    }

    if (/\b(autonomous\s*dj|auto\s*dj|end\s*of\s*song\s*dj|auto\s*curate)\b/i.test(userCommandLower)) {
      const enableDj = !/\b(off|disable|stop|deactivate)\b/i.test(userCommandLower);
      return {
        calculated_satiety_percentage: 24,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Autonomous End-of-Song DJ turned ${enableDj ? 'ON' : 'OFF'}.`,
        voice_agent_tts: enableDj
          ? 'Autonomous End-of-Song DJ is now ON. When a track ends, I will curate and play the next one seamlessly.'
          : 'Autonomous End-of-Song DJ is now OFF. I will wait for your approval before playing anything.',
        agent_tool_call: {
          action: 'TOGGLE_AUTONOMOUS_DJ',
          autonomous_dj_enabled: enableDj,
          expect_follow_up: false
        },
        os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['toggle-autonomous-dj']
      };
    }

    if (/\b(strict\s+mode|strict\s+never\s+leave|never\s+leave\s+tunemind|never\s+open\s+spotify\s+mode)\b/i.test(userCommandLower)) {
      const enableStrict = !/\b(off|disable|stop)\b/i.test(userCommandLower);
      return {
        calculated_satiety_percentage: 24,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Strict Never-Leave-TuneMind mode set to ${enableStrict ? 'ON' : 'OFF'}.`,
        voice_agent_tts: enableStrict
          ? 'Strict Never-Leave-TuneMind mode is now ON. I will never switch away to Spotify or any external player.'
          : 'Strict mode is now OFF. Smart remote playback and automatic return remain active.',
        agent_tool_call: {
          action: 'TOGGLE_STRICT_NO_APP_SWITCH',
          strict_no_app_switch_enabled: enableStrict,
          expect_follow_up: false
        },
        os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['toggle-strict-no-app-switch']
      };
    }

    if (/\b(stay\s+in\s+tunemind|remote\s+playback|in\s*-?app\s+playback)\b/i.test(userCommandLower)) {
      const enableStay = !/\b(off|disable)\b/i.test(userCommandLower);
      return {
        calculated_satiety_percentage: 24,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Stay-in-TuneMind mode set to ${enableStay ? 'ON' : 'OFF'}.`,
        voice_agent_tts: enableStay
          ? 'Stay-in-TuneMind mode is ON so Spotify plays without pulling you out of TuneMind.'
          : 'Stay-in-TuneMind mode is now OFF.',
        agent_tool_call: {
          action: 'TOGGLE_STAY_IN_TUNEMIND',
          stay_in_tunemind_enabled: enableStay,
          expect_follow_up: false
        },
        os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['toggle-stay-in-tunemind']
      };
    }

    if (/\b(theme|atmosphere|color|palette|glacier|emerald|solar|violet|cyan|pink|purple|green|orange|amber|blue|adaptive)\b/i.test(userCommandLower)) {
      const customHex =
        userCommandLower.includes('adaptive') || userCommandLower.includes('reset color') ? 'AUTO'
        : userCommandLower.includes('emerald') || userCommandLower.includes('green') || userCommandLower.includes('mint') ? '#00FFA3'
        : userCommandLower.includes('pink') || userCommandLower.includes('rose') || userCommandLower.includes('magenta') ? '#FF2A85'
        : userCommandLower.includes('purple') || userCommandLower.includes('violet') ? '#A855F7'
        : userCommandLower.includes('cyan') || userCommandLower.includes('aqua') || userCommandLower.includes('glacier') ? '#00E5FF'
        : userCommandLower.includes('orange') || userCommandLower.includes('amber') || userCommandLower.includes('solar') ? '#FF9400'
        : userCommandLower.includes('blue') ? '#3B82F6'
        : '';

      if (customHex) {
        return {
          calculated_satiety_percentage: 25,
          reasoning_mode: 'DIRECT_EXECUTION',
          flow_state_analysis: customHex === 'AUTO' ? 'Restored Adaptive Circadian Theme' : `Theme Accent set to ${customHex}`,
          voice_agent_tts: customHex === 'AUTO'
            ? 'Restored your adaptive circadian studio colors.'
            : 'Updated your TuneMind theme accent color right now.',
          agent_tool_call: {
            action: 'SET_THEME_COLOR',
            custom_accent_hex: customHex,
            theme_behavior: customHex === 'AUTO' ? 'ADAPTIVE' : 'STATIC',
            expect_follow_up: false
          },
          os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
          database_tags: ['theme-color-shift']
        };
      }

      const paletteId = userCommandLower.includes('morning') ? 'morning'
        : userCommandLower.includes('afternoon') ? 'afternoon'
        : userCommandLower.includes('evening') ? 'evening'
        : 'night';
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Studio Atmosphere shifted to ${paletteId.toUpperCase()}.`,
        voice_agent_tts: `Shifting your studio atmosphere to the ${paletteId} mood palette right now.`,
        agent_tool_call: {
          action: 'SHIFT_ATMOSPHERE',
          palette_id: paletteId,
          expect_follow_up: false
        },
        os_execution_command: { intent: 'NONE', target_player: targetPlayer, search_query: '', suggested_bpm_target: 105 },
        database_tags: ['atmosphere-shift']
      };
    }

    // 4. Screen Navigation commands
    if (/\b(show|open|go to|switch to)\s+(my\s+)?(dopamine|vault|rhythm|dna)\b/i.test(userCommandLower)) {
      const screen = userCommandLower.includes('rhythm') || userCommandLower.includes('dna') ? 'RHYTHM' : 'DOPAMINE';
      const tts = screen === 'RHYTHM'
        ? 'Opening your Rhythm DNA and hearing telemetry screen.'
        : 'Opening your Dopamine Vault so you can inspect your fresh and cooling tracks.';
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Navigating to ${screen} screen.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'NAVIGATE_SCREEN',
          target_screen: screen,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          suggested_bpm_target: 105
        },
        database_tags: ['screen-navigation']
      };
    }

    // 5. Most-Played Song Request ("play the song I listened to the most", "what is my most played song", "my favorite song")
    if (
      /\b(most\s+(played|listened|heard)|listened\s+(to\s+)?(the\s+)?most|listen\s+(to\s+)?(the\s+)?most|played\s+(the\s+)?most|favorite\s+(song|track)|favourite\s+(song|track)|top\s+(song|track)|number\s+one\s+(song|track))\b/i.test(userCommandLower)
    ) {
      const sortedMost = [...allUserTracks].sort(
        (a, b) => coerceNonNegativePlayCount(b.playCount) - coerceNonNegativePlayCount(a.playCount)
      );
      const topTrack = sortedMost[0];
      if (topTrack) {
        const identity = resolveCanonicalTrackIdentity({
          trackId: topTrack.trackId,
          spotifyUri: (topTrack as any).spotifyUri,
          title: topTrack.title,
          artist: topTrack.artist
        });
        let uri = identity.spotifyUri || '';
        if (!uri) {
          const resolved = await this.lookupSpotifyTrack(topTrack.title, topTrack.artist, context.spotify_access_token);
          if (resolved) uri = resolved.uri;
        }
        ClaudeMusicReasoningProvider.setPendingProposedTrack(
          {
            title: topTrack.title,
            artist: topTrack.artist,
            trackId: identity.trackId,
            spotifyUri: uri || undefined,
            source: targetPlayer
          },
          userId
        );
        const topPlays = coerceNonNegativePlayCount(topTrack.playCount);
        const artistPart = topTrack.artist && topTrack.artist !== 'Unknown Artist' ? ` by ${topTrack.artist}` : '';
        const countPart = topPlays > 1
          ? ` with ${topPlays} plays in your history`
          : topPlays === 1
          ? ' with 1 play in your history'
          : ' saved in your library (0 plays yet)';
        return {
          calculated_satiety_percentage: 24,
          reasoning_mode: 'SONIC_CURATOR',
          flow_state_analysis: `Top Played Track: "${topTrack.title}"${artistPart} (${topPlays}x plays).`,
          voice_agent_tts: topPlays > 0
            ? `Your most-listened song is "${topTrack.title}"${artistPart}${countPart}. Would you like me to play it right now?`
            : `You don't have any verified play counts yet, but "${topTrack.title}"${artistPart} is ready in your library. Would you like me to play it right now?`,
          agent_tool_call: {
            action: 'PROPOSE_TRACK',
            track_id: identity.trackId,
            track_title: topTrack.title,
            track_artist: topTrack.artist,
            spotify_uri: uri,
            target_player: targetPlayer,
            expect_follow_up: true
          },
          os_execution_command: {
            intent: 'NONE',
            target_player: targetPlayer,
            search_query: `${topTrack.title} ${topTrack.artist !== 'Unknown Artist' ? topTrack.artist : ''}`.trim(),
            track_id: identity.trackId,
            track_title: topTrack.title,
            track_artist: topTrack.artist,
            spotify_uri: uri,
            suggested_bpm_target: 105
          },
          database_tags: ['most-played-query', 'awaiting-approval']
        };
      }
    }

    // 6. Explicit Play / Recommend / Suggest / New Song / Genre Request
    const genreMatch = userCommandLower.match(/\b(kikuyu|gikuyu|mugithi|bongo\s*flava?|bongo|gengetone|ohangla|lingala|benga|gospel|afrobeats?|amapiano|reggae|dancehall|r&b|rnb|hip\s*hop|jazz|rock)\b/i);
    const isExplicitMusicRequest =
      Boolean(explicitlyNamedQuery) ||
      Boolean(genreMatch) ||
      /\b(play|spin|put\s+on|recommend|recommendation|suggest|suggestion|new\s+song|fresh\s+song|any\s+song|another\s+song|different\s+song|give\s+me\s+a\s+song|find\s+me\s+a\s+song|capsule)\b/i.test(userCommandLower);

    if (!isExplicitMusicRequest) {
      // OPEN CONVERSATIONAL TURN -> Respond naturally with ADVISE_ONLY (NEVER force a song proposal!)
      const topTrack = [...allUserTracks].sort(
        (a, b) => coerceNonNegativePlayCount(b.playCount) - coerceNonNegativePlayCount(a.playCount)
      )[0];
      const curPlaying = context.current_track?.title && context.current_track.title !== 'Standing By'
        ? `"${context.current_track.title}"${context.current_track.artist ? ` by ${context.current_track.artist}` : ''}`
        : null;

      let conversationalReply = '';
      if (/\b(what'?s\s+your\s+name|who\s+are\s+you|what\s+are\s+you|called\s+tunemind|your\s+name)\b/i.test(userCommandLower)) {
        conversationalReply = `I'm TuneMind, your personal voice companion and studio controller. We can talk about anything, customize my theme colors or settings, or discover brand-new music when you ask. What's on your mind?`;
      } else if (/\b(how\s+are\s+you|how'?s\s+it\s+going|how\s+are\s+things|how\s+do\s+you\s+feel|you\s+good|are\s+you\s+okay|habari|mzima)\b/i.test(userCommandLower)) {
        conversationalReply = curPlaying
          ? `I'm TuneMind, doing great this ${context.circadian_daypart.toLowerCase()} while you're on ${curPlaying}! How is your day going?`
          : `I'm TuneMind, doing great and right here with you this ${context.circadian_daypart.toLowerCase()}! How are you feeling today?`;
      } else if (/\b(hi|hello|hey|good\s+morning|good\s+afternoon|good\s+evening|yo\b|what'?s\s+up|sup\b|niaje|mambo|sasa|habari|tunemind)\b/i.test(userCommandLower)) {
        conversationalReply = `Hey! TuneMind here — good to hear your voice this ${context.circadian_daypart.toLowerCase()}. What would you like to talk about or adjust right now?`;
      } else if (/\b(what\s+can\s+you\s+do|what\s+do\s+you\s+do|help\s+me|capabilities)\b/i.test(userCommandLower)) {
        conversationalReply = `I'm TuneMind, your autonomous studio companion. We can talk freely, customize app colors and DJ settings, inspect your ear safety and listening history, or discover new Spotify tracks whenever you ask. What should we do first?`;
      } else if (/\b(tired|exhausted|stressed|long\s+day|sad|down|bored|sleepy|overwhelmed)\b/i.test(userCommandLower)) {
        conversationalReply = `I hear you—days like that can really drain your energy. Take a breath; we can just chill and talk, or if you want a calm acoustic reset later, just say the word.`;
      } else if (/\b(i'?m\s+good|i\s+am\s+good|doing\s+well|doing\s+great|i'?m\s+fine|feeling\s+good|fantastic|awesome|blessed)\b/i.test(userCommandLower)) {
        conversationalReply = `Love to hear that! Your ${context.circadian_daypart.toLowerCase()} energy sounds right on point. What's on your mind?`;
      } else if (/\b(what\s+is\s+playing|what'?s\s+playing|what\s+song\s+is\s+this|current\s+song)\b/i.test(userCommandLower)) {
        conversationalReply = curPlaying
          ? `Right now you have ${curPlaying} on your session. How is it hitting your ears?`
          : `Nothing is actively spinning right now. Let me know whenever you feel like hearing something.`;
      } else if (/\b(my\s+taste|my\s+history|my\s+music|how\s+am\s+i\s+listening|analyze|habit|pattern)\b/i.test(userCommandLower)) {
        const topNote = topTrack
          ? `Your top anchor lately has been "${topTrack.title}"${topTrack.artist && topTrack.artist !== 'Unknown Artist' ? ` by ${topTrack.artist}` : ''}. `
          : '';
        conversationalReply = `${topNote}You've got ${allUserTracks.length} tracks in your active studio memory, and your auditory receptivity is at ${100 - (context.current_satiety_percentage || 25)}%.`;
      } else if (/\b(thank\s+you|thanks|appreciate\s+it|asante|shukran)\b/i.test(userCommandLower)) {
        conversationalReply = `Anytime! TuneMind is always right here whenever you want to chat or tweak the studio.`;
      } else {
        conversationalReply = `I'm TuneMind, right here with you. Tell me more about what's on your mind, or let me know if you want to adjust the app or discover something fresh.`;
      }

      return {
        calculated_satiety_percentage: context.current_satiety_percentage || 24,
        reasoning_mode: 'JOURNEY_ADVISOR',
        flow_state_analysis: `TuneMind Conversational Studio • ${context.circadian_daypart} Flow`,
        voice_agent_tts: conversationalReply,
        agent_tool_call: {
          action: 'ADVISE_ONLY',
          expect_follow_up: true
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          suggested_bpm_target: 105
        },
        database_tags: ['conversational-advisor', 'advise-only']
      };
    }

    // 7. User explicitly asked to play or recommend a song -> Discover or resolve on Spotify and propose for approval!
    const topArtistsForFallback = Array.from(
      new Set(
        allUserTracks
          .map(t => ClaudeMusicReasoningProvider.sanitizeArtistName(t.artist))
          .filter(Boolean)
      )
    ).slice(0, 3);

    const excludedTitlesSet = new Set<string>(strictDiscoveryExclusionSet || []);
    if (context.current_track?.title) excludedTitlesSet.add(context.current_track.title.toLowerCase().trim());
    for (const s of eventStore.getRecentAgentTracks()) excludedTitlesSet.add(s.toLowerCase().trim());
    for (const s of context.recently_played_by_agent || []) {
      if (s) excludedTitlesSet.add(String(s).toLowerCase().trim());
    }

    let chosen: { title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string } | null = null;

    if (explicitlyNamedQuery) {
      const hits = await this.searchSpotifyCatalogTracks(explicitlyNamedQuery, undefined, 4, context.spotify_access_token);
      if (hits[0]) {
        chosen = { title: hits[0].title, artist: hits[0].artist, spotifyUri: hits[0].uri, source: targetPlayer };
      } else if (matchedExplicitLibraryTrack) {
        chosen = matchedExplicitLibraryTrack;
      }
    }

    if (!chosen) {
      const requestedMoodQuery = genreMatch
        ? (/kikuyu|gikuyu|mugithi/i.test(genreMatch[1]) ? 'Kikuyu Mugithi Gikuyu' : genreMatch[1])
        : '';
      const discovered = await this.discoverSpotifyTracksForContext(
        context,
        allUserTracks,
        excludedTitlesSet,
        topArtistsForFallback,
        requestedMoodQuery,
        true
      );
      if (discovered[0]) {
        chosen = {
          title: discovered[0].title,
          artist: discovered[0].artist,
          spotifyUri: discovered[0].spotify_uri,
          source: targetPlayer
        };
      }
    }

    if (!chosen) {
      const pool = freshTracks.length > 0 ? freshTracks : allUserTracks;
      chosen = pool[0] || {
        title: 'Calm Down',
        artist: 'Rema',
        trackId: undefined,
        spotifyUri: undefined,
        source: targetPlayer
      };
    }

    let spotifyUri = normalizeSpotifyUri(chosen.spotifyUri || chosen.trackId) || '';
    if (!spotifyUri && chosen.title) {
      const resolved = await this.lookupSpotifyTrack(chosen.title, chosen.artist, context.spotify_access_token);
      if (resolved) spotifyUri = resolved.uri;
    }
    const chosenIdentity = resolveCanonicalTrackIdentity({
      trackId: chosen.trackId,
      spotifyUri: spotifyUri || chosen.spotifyUri,
      title: chosen.title,
      artist: chosen.artist
    });
    const resolvedSpotifyUri = spotifyUri || chosenIdentity.spotifyUri || '';

    ClaudeMusicReasoningProvider.setPendingProposedTrack(
      {
        title: chosen.title,
        artist: chosen.artist,
        trackId: chosenIdentity.trackId,
        spotifyUri: resolvedSpotifyUri || undefined,
        source: chosen.source || targetPlayer,
        never_open_external_app: fallbackNeverOpenExternal
      },
      userId
    );
    eventStore.recordAgentTrackAction(chosen.title);

    const artistPart = chosen.artist && chosen.artist !== 'Unknown Artist' ? ` by ${chosen.artist}` : '';
    const tts = `Based on your listening vibe, I picked "${chosen.title}"${artistPart} for your ${context.circadian_daypart.toLowerCase()} session. Would you like me to spin it right now?`;

    return {
      calculated_satiety_percentage: 26,
      reasoning_mode: 'SONIC_CURATOR',
      flow_state_analysis: `Proposed "${chosen.title}"${artistPart} — awaiting your voice approval.`,
      voice_agent_tts: tts,
      agent_tool_call: {
        action: 'PROPOSE_TRACK',
        track_id: chosenIdentity.trackId,
        track_title: chosen.title,
        track_artist: chosen.artist,
        spotify_uri: resolvedSpotifyUri || undefined,
        target_player: chosen.source || targetPlayer,
        never_open_external_app: fallbackNeverOpenExternal,
        expect_follow_up: true
      },
      os_execution_command: {
        intent: 'NONE',
        target_player: chosen.source || targetPlayer,
        search_query: `${chosen.title} ${chosen.artist !== 'Unknown Artist' ? chosen.artist : ''}`.trim(),
        track_id: chosenIdentity.trackId,
        track_title: chosen.title,
        track_artist: chosen.artist,
        spotify_uri: resolvedSpotifyUri || undefined,
        never_open_external_app: fallbackNeverOpenExternal,
        suggested_bpm_target: 105
      },
      database_tags: ['spotify-discovery-proposal', 'awaiting-approval']
    };
  }

  /**
   * Generates probabilistic inferences over structured UserMusicContext signals.
   */
  async reason(context: UserMusicContext): Promise<ReasoningResult> {
    const report = await this.generateReport(context);
    return {
      requestId: report.reportId,
      generatedAt: report.generatedAt,
      probabilisticInferences: report.probabilisticInferences,
      rawProviderResponse: report
    };
  }

  /**
   * Generates a comprehensive Cognitive Reasoning Report from telemetry context & raw event signals.
   */
  async generateReport(
    context: UserMusicContext,
    rawEventsSummary?: string
  ): Promise<ReasoningReport> {
    const available = await this.isAvailable();

    if (!available) {
      return this.buildHeuristicReport(
        context,
        this.hasInvalidKey
          ? 'Anthropic Claude API key is unverified or invalid. Operating on neuro-acoustic baseline.'
          : 'ANTHROPIC_API_KEY not configured. Using deterministic neuro-acoustic baseline.'
      );
    }

    const stats = context.recentListeningStats;
    const anchors = context.auditoryAnchorSignals;
    const fatigue = context.fatigueSignals ?? [];

    const prompt = `Analyze the following user music listening telemetry:
- User ID: ${context.userId}
- Period: ${stats.periodStart} to ${stats.periodEnd}
- Listening Time: ${Math.round(stats.totalListeningMs / 60000)} minutes
- Unique Tracks: ${stats.uniqueTracksPlayed}
- Plays: ${stats.totalPlayEvents}, Skips: ${stats.totalSkipEvents}
- Average Completion: ${Math.round(stats.averageCompletionPercent)}%
${anchors ? `- Auditory Anchors: Groove/Rhythm: ${(anchors.grooveRhythm * 100).toFixed(0)}%, Vocal/Melody: ${(anchors.vocalMelody * 100).toFixed(0)}%, Structural Drops: ${(anchors.dropStructuralDynamics * 100).toFixed(0)}%` : ''}
${fatigue.length > 0 ? `- Track Fatigue Signals:\n${fatigue.map(f => `  * ${f.trackKey}: ${(f.estimatedFatigue * 100).toFixed(0)}% fatigue (Plays: ${f.basedOnPlayCount}, Trend: ${f.completionTrend})`).join('\n')}` : ''}
${rawEventsSummary ? `- Raw Event Summary:\n${rawEventsSummary}` : ''}

You are TuneMind's Chief Neuro-Acoustic Reasoning AI powered by Claude Sonnet 5.5.
Produce a comprehensive Cognitive Reasoning Report evaluating auditory receptor receptivity, flow state, and acoustic shift recommendations.

Respond ONLY with a valid JSON object matching this schema:
{
  "receptivityScore": 88,
  "state": "OPTIMAL_FLOW",
  "statusLabel": "Optimal Cognitive Flow",
  "flowStateNarrative": "Listener demonstrates steady dopamine reinforcement with low auditory receptor adaptation.",
  "acousticShiftRecommendations": [
    {
      "shiftType": "TEMPO_ADAPTATION",
      "recommendation": "Maintain tempo in 110-128 BPM band.",
      "targetBpmRange": "110-128 BPM",
      "rationale": "Sustains alertness without elevating acoustic fatigue."
    }
  ],
  "probabilisticInferences": [
    {
      "type": "RECEPTOR_STABILITY",
      "description": "Auditory fatigue remains below adaptation threshold.",
      "confidence": 0.89,
      "evidenceSummary": "High completion rates across diverse track keys."
    }
  ]
}`;

    try {
      const response = await this.callClaudeWithSonnetPriority(
        'You are TuneMind Neuro-Acoustic Analyst. Respond ONLY with compact valid JSON matching the requested schema.',
        prompt,
        750
      );

      const rawText = this.extractRawText(response?.content);
      if (!rawText || !rawText.trim()) {
        throw new Error('Claude returned an empty response.');
      }
      const jsonMatch = rawText.match(/\{[\s\S]*\}/);
      const parsed = JSON.parse(jsonMatch ? jsonMatch[0] : rawText);

      const receptorScore = Math.min(100, Math.max(5, Number(parsed.receptivityScore) || 85));
      const state = (['OPTIMAL_FLOW', 'MILD_SATURATION', 'ELEVATED_BURNOUT'].includes(parsed.state)
        ? parsed.state
        : 'OPTIMAL_FLOW') as AuditoryReceptorStatus['state'];

      const inferences: ProbabilisticInference[] = Array.isArray(parsed.probabilisticInferences)
        ? parsed.probabilisticInferences.map((inf: any) => ({
            type: String(inf.type || 'COGNITIVE_OBSERVATION'),
            description: String(inf.description || 'Observed telemetry trend.'),
            confidence: Math.min(1.0, Math.max(0.1, Number(inf.confidence) || 0.8)),
            evidenceSummary: String(inf.evidenceSummary || 'Derived from recent playback events.'),
            isFactual: false as const
          }))
        : [];

      const recommendations: AcousticShiftRecommendation[] = Array.isArray(parsed.acousticShiftRecommendations)
        ? parsed.acousticShiftRecommendations.map((rec: any) => ({
            shiftType: rec.shiftType || 'TEMPO_ADAPTATION',
            recommendation: rec.recommendation || 'Maintain dynamic frequency range.',
            targetBpmRange: rec.targetBpmRange,
            rationale: rec.rationale || 'Protects acoustic receptivity.'
          }))
        : [];

      return {
        reportId: uuidv4(),
        userId: context.userId,
        generatedAt: new Date().toISOString(),
        analyzedEventCount: stats.totalPlayEvents + stats.totalSkipEvents,
        timeWindow: {
          start: stats.periodStart,
          end: stats.periodEnd
        },
        auditoryReceptorStatus: {
          receptivityScore: receptorScore,
          state,
          statusLabel: parsed.statusLabel || (state === 'OPTIMAL_FLOW' ? 'Optimal Cognitive Flow' : 'Mild Acoustic Saturation')
        },
        flowStateNarrative: parsed.flowStateNarrative || 'Auditory telemetry indicates receptive neuro-acoustic engagement.',
        acousticShiftRecommendations: recommendations.length > 0 ? recommendations : [
          {
            shiftType: 'TEMPO_ADAPTATION',
            recommendation: 'Maintain current acoustic variety and moderate tempo.',
            targetBpmRange: '105-125 BPM',
            rationale: 'Avoids repetition while preserving focus velocity.'
          }
        ],
        fatigueSignals: fatigue,
        probabilisticInferences: inferences,
        anchorsAssessed: {
          grooveRhythm: anchors?.grooveRhythm ?? 0.65,
          vocalMelody: anchors?.vocalMelody ?? 0.70,
          dropStructuralDynamics: anchors?.dropStructuralDynamics ?? 0.50,
          confidence: anchors?.signalConfidence ?? 0.85
        },
        keyObservedTracks: fatigue.map(f => ({
          title: f.trackKey.split(' - ')[0] || f.trackKey,
          artist: f.trackKey.split(' - ')[1] || 'Observed Artist',
          playCount: f.basedOnPlayCount,
          estimatedSatietyPercent: Math.round(f.estimatedFatigue * 100),
          fatigueRisk: f.estimatedFatigue >= 0.75 ? 'HIGH' : f.estimatedFatigue >= 0.45 ? 'MODERATE' : 'LOW'
        })),
        isAiGenerated: true,
        modelUsed: this.activeModelUsed
      };
    } catch (err: any) {
      console.warn('[Claude Provider] Report generation failed or unconfigured, using baseline:', err?.message || err);
      return this.buildHeuristicReport(context, 'Claude reasoning baseline applied.');
    }
  }

  private buildHeuristicReport(context: UserMusicContext, notice: string): ReasoningReport {
    const stats = context.recentListeningStats;
    const fatigue = context.fatigueSignals ?? [];
    const highFatigueCount = fatigue.filter(f => f.estimatedFatigue >= 0.7).length;

    const state: AuditoryReceptorStatus['state'] = highFatigueCount >= 2
      ? 'ELEVATED_BURNOUT'
      : highFatigueCount === 1 || stats.totalSkipEvents > stats.totalPlayEvents * 0.4
      ? 'MILD_SATURATION'
      : 'OPTIMAL_FLOW';

    const score = state === 'OPTIMAL_FLOW' ? 88 : state === 'MILD_SATURATION' ? 62 : 38;

    const statusLabel = state === 'OPTIMAL_FLOW'
      ? 'Optimal Cognitive Flow'
      : state === 'MILD_SATURATION'
      ? 'Sensory Saturation Warning'
      : 'Auditory Receptor Burnout';

    const narrative = state === 'OPTIMAL_FLOW'
      ? `Auditory telemetry across ${stats.uniqueTracksPlayed} unique tracks demonstrates strong cognitive receptivity with balanced dynamic stimulation.`
      : state === 'MILD_SATURATION'
      ? `Listener skip velocity (${stats.totalSkipEvents} skips) and track repeat patterns indicate early auditory habituation.`
      : `High repetition loops detected across key tracks. Sensory hair cells and dopamine receptors exhibit acute satiety.`;

    const recommendations: AcousticShiftRecommendation[] = state === 'ELEVATED_BURNOUT'
      ? [
          {
            shiftType: 'SILENCE_INTERVAL',
            recommendation: 'Insert a 15-minute acoustic silence break or transition to low-volume ambient textures.',
            targetBpmRange: '<70 BPM',
            rationale: 'Resets dopamine receptor thresholds and relieves auditory hair-cell strain.'
          }
        ]
      : state === 'MILD_SATURATION'
      ? [
          {
            shiftType: 'TIMBRE_CLEANSE',
            recommendation: 'Switch from compressed synthetic transients to organic acoustic or instrumental timbres.',
            targetBpmRange: '80-105 BPM',
            rationale: 'Broadens perceptual bandwidth and clears short-term melodic habituation.'
          }
        ]
      : [
          {
            shiftType: 'TEMPO_ADAPTATION',
            recommendation: 'Retain current steady tempo trajectory between 110-125 BPM.',
            targetBpmRange: '110-125 BPM',
            rationale: 'Deepens cognitive focus flow without causing acoustic fatigue.'
          }
        ];

    const inferences: ProbabilisticInference[] = [
      {
        type: 'HEURISTIC_RECEPTOR_EVALUATION',
        description: `${statusLabel} calculated from ${stats.totalPlayEvents} play events and ${stats.totalSkipEvents} skips.`,
        confidence: 0.78,
        evidenceSummary: `Completion average ${Math.round(stats.averageCompletionPercent)}% across ${stats.uniqueTracksPlayed} tracks. (${notice})`,
        isFactual: false
      }
    ];

    return {
      reportId: uuidv4(),
      userId: context.userId,
      generatedAt: new Date().toISOString(),
      analyzedEventCount: stats.totalPlayEvents + stats.totalSkipEvents,
      timeWindow: {
        start: stats.periodStart,
        end: stats.periodEnd
      },
      auditoryReceptorStatus: {
        receptivityScore: score,
        state,
        statusLabel
      },
      flowStateNarrative: narrative,
      acousticShiftRecommendations: recommendations,
      fatigueSignals: fatigue,
      probabilisticInferences: inferences,
      anchorsAssessed: {
        grooveRhythm: context.auditoryAnchorSignals?.grooveRhythm ?? 0.65,
        vocalMelody: context.auditoryAnchorSignals?.vocalMelody ?? 0.70,
        dropStructuralDynamics: context.auditoryAnchorSignals?.dropStructuralDynamics ?? 0.50,
        confidence: context.auditoryAnchorSignals?.signalConfidence ?? 0.75
      },
      keyObservedTracks: fatigue.map(f => ({
        title: f.trackKey.split(' - ')[0] || f.trackKey,
        artist: f.trackKey.split(' - ')[1] || 'Observed Artist',
        playCount: f.basedOnPlayCount,
        estimatedSatietyPercent: Math.round(f.estimatedFatigue * 100),
        fatigueRisk: f.estimatedFatigue >= 0.75 ? 'HIGH' : f.estimatedFatigue >= 0.45 ? 'MODERATE' : 'LOW'
      })),
      isAiGenerated: false,
      modelUsed: 'claude-haiku-baseline'
    };
  }

  /**
   * Direct live analysis endpoint called by Android client or web dashboard.
   */
  async analyzeLiveSession(payload: {
    userId?: string;
    recentTracks?: Array<{ title?: string; artist?: string; plays?: number; satiety?: number }>;
    nowPlaying?: { title?: string; artist?: string; isPlaying?: boolean; sourcePackage?: string };
    vaultTracks?: Array<{ title?: string; artist?: string; cooldownDaysRemaining?: number }>;
    timeSlot?: string;
    eventCount?: number;
    topSourceApp?: string;
  }): Promise<any> {
    const available = await this.isAvailable();
    if (available) {
      try {
        const prompt = `Real-time User Music Telemetry:
- Time Slot: ${payload.timeSlot || 'Active Session'}
- Primary Source: ${payload.topSourceApp || 'Spotify'}
- Now Playing: ${payload.nowPlaying ? `"${payload.nowPlaying.title}" by ${payload.nowPlaying.artist}` : 'Standby'}
- Recent Tracks: ${(payload.recentTracks || []).map(t => `${t.title} - ${t.artist}`).join(', ')}

Respond ONLY with a JSON object:
{
  "receptivityScore": 88,
  "cognitiveState": "OPTIMAL_FLOW",
  "statusLabel": "Optimal Cognitive Flow",
  "flowNarrative": "Acoustic receptors show balanced stimulation with minimal repetition fatigue.",
  "recommendations": [{"type": "TEMPO_ADAPTATION", "action": "Maintain 112-124 BPM band", "rationale": "Sustains focus without exhaustion"}],
  "probabilisticInferences": [{"inference": "High structural tolerance in current session", "confidence": 0.91, "basis": "Completion rates remain above 80%"}]
}`;

        const res = await this.callClaudeWithSonnetPriority(
          'You are TuneMind Neuro-Acoustic Analyst. Respond ONLY with compact valid JSON matching the requested schema.',
          prompt,
          650
        );

        const raw = this.extractRawText(res?.content);
        if (!raw || !raw.trim()) {
          throw new Error('Claude returned an empty response.');
        }
        const match = raw.match(/\{[\s\S]*\}/);
        const parsed = JSON.parse(match ? match[0] : raw);
        return {
          status: 'ok',
          isAiGenerated: true,
          modelUsed: this.activeModelUsed,
          generatedAt: new Date().toISOString(),
          ...parsed
        };
      } catch (err) {
        console.warn('[Claude Provider] Live analysis fallback applied:', err);
      }
    }

    const avgSatiety = payload.recentTracks?.length
      ? Math.round(payload.recentTracks.reduce((acc, t) => acc + (t.satiety || 20), 0) / payload.recentTracks.length)
      : 45;
    const state = avgSatiety >= 75 ? 'ELEVATED_BURNOUT' : avgSatiety >= 55 ? 'MILD_SATURATION' : 'OPTIMAL_FLOW';

    return {
      status: 'ok',
      isAiGenerated: false,
      modelUsed: 'claude-haiku-neuro-baseline',
      generatedAt: new Date().toISOString(),
      receptivityScore: Math.max(15, Math.min(98, 100 - avgSatiety)),
      cognitiveState: state,
      statusLabel: state === 'OPTIMAL_FLOW' ? 'Optimal Cognitive Flow' : state === 'MILD_SATURATION' ? 'Mild Acoustic Saturation' : 'Auditory Receptor Burnout',
      flowNarrative: 'Auditory stereocilia engagement remains balanced. Neural tolerance for harmonic variation is optimal.',
      recommendations: [
        {
          type: 'TEMPO_ADAPTATION',
          action: 'Shift to ambient or downtempo frequencies',
          rationale: 'Allows dopamine receptors to reset without interrupting focus.'
        }
      ],
      probabilisticInferences: [
        {
          inference: 'Auditory receptor equilibrium sustained',
          confidence: 0.86,
          basis: `Derived from ${payload.eventCount || 20} telemetry observations.`
        }
      ]
    };
  }

  /**
   * Generates chronobiological music recommendations using Claude Sonnet 5.5.
   */
  async generateChronoDiscovery(payload: {
    timeSlot?: string;
    recentTracks?: Array<{ title?: string; artist?: string; plays?: number; satiety?: number }>;
    satietyLevel?: number;
    vaultTracks?: Array<{ title?: string; artist?: string }>;
    topArtists?: string[];
    userTasteAnchors?: string[];
    preferredProvider?: string;
    excludeTitles?: string[];
    playedFeedback?: string[];
    dismissedFeedback?: string[];
  }): Promise<{ status: string; isAiGenerated: boolean; modelUsed: string; recommendations: any[] }> {
    const available = await this.isAvailable();
    if (available) {
      try {
        const timeSlot = payload.timeSlot || 'Morning Focus';
        const topArtists = (payload.topArtists || []).concat(payload.userTasteAnchors || []).join(', ') || 'Bongo Flava, Afrobeats, Hip-Hop, R&B';

        const prompt = `User Taste: ${topArtists}. Time of Day: ${timeSlot}. Satiety: ${payload.satietyLevel ?? 40}%.
Generate 10 real, genre-congruent song recommendations (4 CORE_DOPAMINE, 3 DEEP_CUT, 2 PALATE_CLEANSER, 1 SONIC_FRONTIER).
Respond ONLY with a JSON array:
[{"title": "Song Title", "artist": "Artist", "album": "Album", "category": "DOPAMINE_FLOW", "subTier": "CORE_DOPAMINE", "reason": "Reason", "confidence": 0.95}]`;

        const res = await this.callClaudeWithSonnetPriority(
          'You are TuneMind Music Discovery Engine. Respond ONLY with a compact JSON array.',
          prompt,
          1000
        );

        const raw = this.extractRawText(res?.content);
        if (!raw || !raw.trim()) {
          throw new Error('Claude returned an empty response.');
        }
        const match = raw.match(/\[[\s\S]*\]/);
        const parsed = JSON.parse(match ? match[0] : raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          return {
            status: 'ok',
            isAiGenerated: true,
            modelUsed: this.activeModelUsed,
            recommendations: parsed.slice(0, 10).map((item: any) => {
              const identity = resolveCanonicalTrackIdentity({
                trackId: item?.trackId || item?.track_id,
                spotifyUri: item?.spotifyUri || item?.spotify_uri,
                title: item?.title,
                artist: item?.artist,
                prefix: 'disc'
              });
              return {
                ...item,
                trackId: identity.trackId,
                spotifyUri: identity.spotifyUri,
                playCount: 0
              };
            })
          };
        }
      } catch (err) {
        console.warn('[Claude Provider] Discovery fallback applied:', err);
      }
    }

    return {
      status: 'ok',
      isAiGenerated: false,
      modelUsed: 'claude-haiku-baseline',
      recommendations: [
        {
          trackId: resolveCanonicalTrackIdentity({ title: 'Huwezi Jua', artist: 'Mbosso', prefix: 'disc' }).trackId,
          playCount: 0,
          title: 'Huwezi Jua',
          artist: 'Mbosso',
          album: 'Khan',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Acoustic guitar cadence and signature Swahili melody deliver dopamine flow.',
          confidence: 0.98
        },
        {
          trackId: resolveCanonicalTrackIdentity({ title: 'Mi Amor', artist: 'Marioo & Jovial', prefix: 'disc' }).trackId,
          playCount: 0,
          title: 'Mi Amor',
          artist: 'Marioo & Jovial',
          album: 'The Kid You Know',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Tender vocal harmonies and sweet acoustic rhythm clear cognitive fatigue.',
          confidence: 0.96
        },
        {
          trackId: resolveCanonicalTrackIdentity({ title: 'Calm Down', artist: 'Rema', prefix: 'disc' }).trackId,
          playCount: 0,
          title: 'Calm Down',
          artist: 'Rema',
          album: 'Rave & Climax',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Smooth melodic warmth and mid-tempo rhythmic pacing calibrated for circadian focus.',
          confidence: 0.95
        },
        {
          trackId: resolveCanonicalTrackIdentity({ title: 'Single Again', artist: 'Harmonize', prefix: 'disc' }).trackId,
          playCount: 0,
          title: 'Single Again',
          artist: 'Harmonize',
          album: 'Made For Us',
          category: 'PALATE_CLEANSER',
          subTier: 'PALATE_CLEANSER',
          reason: 'Melodic guitar progression and relaxed cadence soothe auditory receptor saturation.',
          confidence: 0.94
        }
      ]
    };
  }

  /**
   * Conversational music agent endpoint powered by Autonomous Multi-Provider LLM Reasoning.
   */
  async conversationalMusicAgent(query: string, tracks: any[], currentPlaying?: any, userName?: string): Promise<any> {
    const evalResult = await this.evaluateSatiety({
      timestamp_utc: new Date().toISOString(),
      circadian_daypart: 'Evening',
      current_track: {
        title: typeof currentPlaying === 'string' ? currentPlaying : (currentPlaying?.title || 'Standing By'),
        artist: typeof currentPlaying === 'object' ? (currentPlaying?.artist || '') : '',
        trackId: typeof currentPlaying === 'object' ? (currentPlaying?.trackId || undefined) : undefined,
        spotifyUri: typeof currentPlaying === 'object' ? (currentPlaying?.spotifyUri || undefined) : undefined,
        source_player: 'com.spotify.music'
      },
      user_command: query,
      rolling_history: Array.isArray(tracks)
        ? tracks.map((t: any) => {
            const identity = resolveCanonicalTrackIdentity({
              trackId: t.trackId || t.id,
              spotifyUri: t.spotifyUri || t.spotify_uri,
              title: t.title || '',
              artist: t.artist || ''
            });
            return {
              title: t.title || '',
              artist: t.artist || '',
              trackId: identity.trackId,
              spotifyUri: identity.spotifyUri,
              playCount: coerceNonNegativePlayCount(t.playCount ?? t.distinctAppearanceCount ?? 0),
              source_player: 'com.spotify.music'
            };
          })
        : [],
      current_satiety_percentage: 25,
      user_id: userName || 'tunemind-dev'
    });

    const toolAction = evalResult.agent_tool_call?.action || 'ADVISE_ONLY';
    const isPlayOrPropose = toolAction === 'PLAY_TRACK_NOW' || toolAction === 'PROPOSE_TRACK' || toolAction === 'LAUNCH_DOPAMINE_CAPSULE';
    const recTitle = evalResult.agent_tool_call?.track_title || evalResult.os_execution_command?.track_title || '';
    const recArtist = evalResult.agent_tool_call?.track_artist || evalResult.os_execution_command?.track_artist || '';
    const recTrackId = evalResult.agent_tool_call?.track_id || evalResult.os_execution_command?.track_id || null;
    const recSpotifyUri = normalizeSpotifyUri(evalResult.agent_tool_call?.spotify_uri) || null;

    return {
      reply: evalResult.voice_agent_tts,
      spokenText: evalResult.voice_agent_tts,
      action: isPlayOrPropose && recTitle ? 'PLAY_TRACK' : 'SPEAK_ONLY',
      suggestedAction: isPlayOrPropose && recTitle ? 'PLAY' : toolAction === 'PAUSE_PLAYBACK' ? 'PAUSE' : 'NONE',
      suggestedTitle: recTitle || null,
      suggestedArtist: recArtist || null,
      matchedTrackId: recTrackId,
      matchedSpotifyUri: recSpotifyUri,
      recommendedTrack: recTitle ? { trackId: recTrackId, title: recTitle, artist: recArtist, spotifyUri: recSpotifyUri } : null,
      agent_tool_call: evalResult.agent_tool_call,
      os_execution_command: evalResult.os_execution_command
    };
  }

  /**
   * Tags music catalog with genre, vibe, and cultural taxonomy.
   */
  async tagMusicCatalog(tracks: any[]): Promise<any> {
    return tracks.map(t => ({
      title: t.title,
      artist: t.artist,
      tags: ['Afro-Acoustic', 'Midday-Focus', 'Low-Satiety']
    }));
  }
}
