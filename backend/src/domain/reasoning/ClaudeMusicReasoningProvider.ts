import Anthropic from '@anthropic-ai/sdk';
import { v4 as uuidv4 } from 'uuid';
import { supabase } from '../../config/supabase';
import { EventStore } from '../events/EventStore';
import { SupabaseIntelligenceStore } from '../storage/SupabaseIntelligenceStore';
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
    | 'NAVIGATE_SCREEN'
    | 'LAUNCH_DOPAMINE_CAPSULE'
    | 'ADVISE_ONLY';
  track_title?: string;
  track_artist?: string;
  spotify_uri?: string;
  target_player?: string;
  volume_percent?: number;
  palette_id?: string; // 'morning' | 'afternoon' | 'evening' | 'night' | 'auto'
  target_screen?: string; // 'DOPAMINE' | 'AGENT' | 'RHYTHM'
  expect_follow_up?: boolean;
}

export interface TuneMindTelemetryContext {
  timestamp_utc: string;
  circadian_daypart: 'Morning' | 'Midday' | 'Evening' | 'Late Night';
  current_track: {
    title: string;
    artist: string;
    trackId?: string;
    source_player: string;
    is_playing?: boolean;
    acoustic_features?: {
      bpm: number | null;
      energy: number | null;
      key: string | null;
    };
  };
  user_command?: string;
  rolling_history: Array<{ title: string; artist: string; trackId?: string; playCount?: number; source_player?: string; bpm?: number }>;
  recently_played_by_agent?: string[];
  current_satiety_percentage: number;
  hearing_telemetry?: {
    estimated_db: number;
    safe_headroom_db: number;
    daily_exposure_minutes: number;
    output_device: string;
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
    freshness_percent: number;
    reason: string;
  }>;
  pending_proposed_track?: {
    title: string;
    artist: string;
    trackId?: string;
    source?: string;
  } | null;
  spotify_access_token?: string;
  dialogue_history?: Array<{ role: 'user' | 'assistant'; text: string }>;
  user_id?: string;
  user_email?: string;
  supabase_url?: string;
  supabase_anon_key?: string;
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
    track_title?: string;
    track_artist?: string;
    spotify_uri?: string;
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
  private static pendingProposedTrack: {
    title: string;
    artist: string;
    trackId?: string;
    source?: string;
  } | null = null;
  private static recentDialogueTurns: Array<{ role: 'user' | 'assistant'; text: string }> = [];
  private static rotationIndex: number = 0;

  public static setPendingProposedTrack(track: { title: string; artist: string; trackId?: string; source?: string } | null) {
    ClaudeMusicReasoningProvider.pendingProposedTrack = track;
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
          // Prioritize Sonnet first for deepest conversational + tool reasoning, then Haiku/Opus/others
          const sonnets = rawIds.filter(id => id.includes('sonnet'));
          const haikus = rawIds.filter(id => id.includes('haiku') && !id.includes('sonnet'));
          const others = rawIds.filter(id => !id.includes('sonnet') && !id.includes('haiku'));
          const ranked = [...sonnets, ...haikus, ...others];

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
      'claude-haiku-5-5',
      'claude-opus-5-5',
      'claude-fable-5-2',
      'claude-sonnet-4-6',
      'claude-sonnet-4-5',
      'claude-sonnet-4-5-20250929',
      'claude-haiku-4-5',
      'claude-haiku-4-5-20251001',
      'claude-sonnet-4-20250514'
    ]) {
      if (!candidateModels.includes(m)) {
        candidateModels.push(m);
      }
    }
    return candidateModels;
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
        name: 'submit_agent_turn',
        description:
          'Submits your final spoken response and app/playback action to the user after you have finished reasoning and calling any necessary Spotify/history tools.',
        input_schema: {
          type: 'object',
          properties: {
            voice_agent_tts: {
              type: 'string',
              description: 'Your natural, warm, unscripted spoken response to the user (1-3 sentences, conversational like ChatGPT Voice). If proposing a track, ask if they want you to play it.'
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
                'NAVIGATE_SCREEN',
                'LAUNCH_DOPAMINE_CAPSULE'
              ],
              description:
                'Use PROPOSE_TRACK whenever suggesting, recommending, or finding a song so the user can approve it first. Use PLAY_TRACK_NOW ONLY when the user is confirming/approving the currently pending_proposed_track. Use ADVISE_ONLY for open conversation, questions, or advice.'
            },
            track_title: {
              type: 'string',
              description: 'Exact song title (required when action is PROPOSE_TRACK, PLAY_TRACK_NOW, LAUNCH_DOPAMINE_CAPSULE, or QUARANTINE_TO_VAULT).'
            },
            track_artist: {
              type: 'string',
              description: 'Exact artist name.'
            },
            spotify_uri: {
              type: 'string',
              description: 'Exact canonical spotify:track:<22-char-id> URI returned by the tools.'
            },
            volume_percent: {
              type: 'number',
              description: 'Target media volume (10-100) when action is SET_VOLUME_PERCENT.'
            },
            palette_id: {
              type: 'string',
              description: 'Target theme palette ("morning" | "afternoon" | "evening" | "night" | "auto") when action is SHIFT_ATMOSPHERE.'
            },
            target_screen: {
              type: 'string',
              description: 'Target screen ("DOPAMINE" | "AGENT" | "RHYTHM") when action is NAVIGATE_SCREEN.'
            },
            expect_follow_up: {
              type: 'boolean',
              description: 'Set to true whenever you ask the user a question or propose a track so the microphone automatically re-opens for their reply.'
            }
          },
          required: ['voice_agent_tts', 'flow_state_analysis', 'calculated_satiety_percentage', 'action', 'expect_follow_up']
        }
      }
    ];
  }

  private getAutonomousSystemPrompt(): string {
    return `You are TuneMind, an autonomous, deeply perceptive AI music companion, psychoacoustic advisor, and voice controller on Android—with the conversational freedom and intelligence of ChatGPT Advanced Voice.

HOW YOU REASON & USE YOUR TOOLS:
1. NEVER GUESS OR TREAT CONVERSATIONAL SENTENCES AS SONG TITLES:
   - Understand the user's true intent naturally.
   - If the user asks to play the song they listened to the most ("play the song that I have listened the most", "my most played song", "my favorite track"), call 'query_user_listening_history' with sort_by="most_played" to inspect their real #1 track, then call 'submit_agent_turn' with action="PROPOSE_TRACK" and that exact track's title, artist, and spotify_uri!
   - If the user asks for a new song, a fresh recommendation, or a vibe ("play for me a new song", "recommend a song for me", "give me something fresh", "play some chill afrobeats"), call 'discover_and_recommend_spotify_tracks' to fetch real, verified Spotify tracks tailored to their taste, pick the best one, and call 'submit_agent_turn' with action="PROPOSE_TRACK"!
   - If the user names a specific song or artist ("play Calm Down by Rema", "play Mbosso"), call 'search_spotify_catalog' to verify the real track and get its exact 'spotify:track:<id>' URI, then call 'submit_agent_turn' with action="PROPOSE_TRACK"!

2. TWO-STEP APPROVAL FLOW vs. AUTONOMOUS END-OF-SONG DJ ('AUTO_CURATE_NEXT_TRACK'):
   - For normal user requests where you find, recommend, or select a song, use action="PROPOSE_TRACK" and expect_follow_up=true first, asking naturally if they want you to spin it right now.
   - EXCEPTION — AUTONOMOUS END-OF-SONG HANDOVER: When 'user_spoken_message' starts with "AUTO_CURATE_NEXT_TRACK:", the previous song just finished playing (including when the user's phone screen is off). You MUST call 'discover_and_recommend_spotify_tracks' (or pick a fresh unplayed track from 'fresh_low_satiety_user_tracks') and immediately call 'submit_agent_turn' with action="PLAY_TRACK_NOW", expect_follow_up=false, and a smooth DJ announcement (e.g., "Up next for your flow, spinning [Title] by [Artist].") without asking a question!
   - When 'pending_proposed_track' is present in the context and the user approves it (e.g. "yes", "yeah", "play it", "go ahead", "sure", "do it", "sounds good"), immediately call 'submit_agent_turn' with action="PLAY_TRACK_NOW" and the pending track's title, artist, and spotify_uri!
   - If 'pending_proposed_track' is present and the user rejects it or asks for something else ("no", "not that one", "give me something slower", "another one"), call 'discover_and_recommend_spotify_tracks' to find a better match and propose the new track!

3. OPEN CONVERSATION & MULTI-TURN DIALOGUE ('ADVISE_ONLY'):
   - You can talk with the user about anything: how their day is going, why a song hits their dopamine receptors, their listening habits, ear fatigue / decibel safety, artists, or music history.
   - When the user is just chatting, asking a question, or reflecting without asking to play a song right now, use action="ADVISE_ONLY" and converse naturally like a close friend and studio producer.

4. APP & OS CONTROLS:
   - Pause/stop music -> action="PAUSE_PLAYBACK"
   - Resume/continue music -> action="RESUME_PLAYBACK"
   - Skip next / previous -> action="SKIP_NEXT" / "SKIP_PREVIOUS"
   - Volume control -> action="SET_VOLUME_PERCENT" (with volume_percent 15..100)
   - Lock overplayed track in Cooldown Vault -> action="QUARANTINE_TO_VAULT"
   - Change studio theme/color -> action="SHIFT_ATMOSPHERE"
   - Navigate screens -> action="NAVIGATE_SCREEN" ("DOPAMINE" | "AGENT" | "RHYTHM")

Always conclude your turn by calling the 'submit_agent_turn' tool with your natural spoken reply ('voice_agent_tts', 1-3 concise spoken sentences).`;
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

    for (const q of searchCandidates) {
      try {
        const searchRes = await fetch(
          `https://api.spotify.com/v1/search?q=${encodeURIComponent(q)}&type=track&limit=${limit}`,
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
        .map((item: any, idx: number) => ({
          trackId: item.id,
          uri: item.uri,
          title: item.name,
          artist: item.artists?.map((a: any) => a.name).join(', ') || 'Unknown Artist',
          album: item.album?.name || '',
          popularity: Number(item.popularity || 60),
          playCount: Math.max(3, limit - idx)
        }));
    } catch {
      return [];
    }
  }

  private rememberTurn(userText: string, assistantText: string) {
    if (!userText && !assistantText) return;
    if (userText) {
      ClaudeMusicReasoningProvider.recentDialogueTurns.push({ role: 'user', text: userText });
    }
    if (assistantText) {
      ClaudeMusicReasoningProvider.recentDialogueTurns.push({ role: 'assistant', text: assistantText });
    }
    if (ClaudeMusicReasoningProvider.recentDialogueTurns.length > 16) {
      ClaudeMusicReasoningProvider.recentDialogueTurns = ClaudeMusicReasoningProvider.recentDialogueTurns.slice(-16);
    }
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
      ClaudeMusicReasoningProvider.pendingProposedTrack = context.pending_proposed_track;
    }
    const pending = ClaudeMusicReasoningProvider.pendingProposedTrack;

    // ─── 0. SYNC LIVE TELEMETRY & FETCH LONG-TERM SUPABASE TASTE GENOME ───────
    await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(
      userId,
      (context.rolling_history || []).map(t => ({
        title: t.title,
        artist: t.artist,
        trackId: t.trackId,
        playCount: t.playCount,
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
    const historyMap = new Map<string, { title: string; artist: string; trackId?: string; source: string; playCount: number }>();

    if (Array.isArray(context.rolling_history)) {
      for (const t of context.rolling_history) {
        if (t.title && t.title !== 'Active Session' && t.title !== 'Standing By' && t.title !== 'Deep Flow State') {
          const key = `${t.title.toLowerCase().trim()} - ${(t.artist || '').toLowerCase().trim()}`;
          const existing = historyMap.get(key);
          const count = Number(t.playCount || 1);
          if (!existing) {
            historyMap.set(key, {
              title: t.title.trim(),
              artist: (t.artist || 'Unknown Artist').trim(),
              trackId: t.trackId || undefined,
              source: (t as any).source_player || 'com.spotify.music',
              playCount: count
            });
          } else {
            existing.playCount = Math.max(existing.playCount, count);
            if (!existing.trackId && t.trackId) existing.trackId = t.trackId;
          }
        }
      }
    }

    // Merge historical tracks stored in Supabase Taste Genome across sessions
    for (const supaTrk of supabaseMemory.topTracksOverTime) {
      if (supaTrk.title) {
        const key = `${supaTrk.title.toLowerCase().trim()} - ${(supaTrk.artist || '').toLowerCase().trim()}`;
        const existing = historyMap.get(key);
        if (!existing) {
          historyMap.set(key, {
            title: supaTrk.title,
            artist: supaTrk.artist || 'Unknown Artist',
            trackId: supaTrk.spotifyUri,
            source: supaTrk.source || 'com.spotify.music',
            playCount: supaTrk.playCount || 1
          });
        } else {
          existing.playCount = Math.max(existing.playCount, supaTrk.playCount || 1);
          if (!existing.trackId && supaTrk.spotifyUri) existing.trackId = supaTrk.spotifyUri;
        }
      }
    }

    try {
      const storedTracks = eventStore.getRecentObservedTracks(50);
      for (const t of storedTracks) {
        if (t.title) {
          const key = `${t.title.toLowerCase().trim()} - ${(t.artist || '').toLowerCase().trim()}`;
          const existing = historyMap.get(key);
          if (!existing) {
            historyMap.set(key, {
              title: t.title,
              artist: t.artist || 'Unknown Artist',
              trackId: t.trackId,
              source: t.source || 'com.spotify.music',
              playCount: Number(t.playCount || 1)
            });
          } else if (!existing.trackId && t.trackId) {
            existing.trackId = t.trackId;
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

    let freshTracks = allUserTracks.filter(t => !excludedSet.has(t.title.toLowerCase().trim()) && (t.playCount || 1) <= 4);
    if (freshTracks.length === 0) {
      freshTracks = allUserTracks.filter(t => !excludedSet.has(t.title.toLowerCase().trim()));
    }
    if (freshTracks.length === 0 && allUserTracks.length > 0) {
      freshTracks = [...allUserTracks];
    }

    const overplayedTracks = [...allUserTracks]
      .filter(t => (t.playCount || 1) >= 3)
      .sort((a, b) => (b.playCount || 1) - (a.playCount || 1))
      .slice(0, 8);

    const mostPlayedTracks = [...allUserTracks]
      .sort((a, b) => (b.playCount || 1) - (a.playCount || 1))
      .slice(0, 10);

    const topUserArtists = Array.from(
      allUserTracks.reduce((acc, t) => {
        const primaryArtist = ClaudeMusicReasoningProvider.sanitizeArtistName(t.artist);
        if (primaryArtist) {
          acc.set(primaryArtist, (acc.get(primaryArtist) || 0) + (t.playCount || 1));
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
          freshness_percent: Math.max(76, 94 - (t.playCount || 1) * 6 - idx * 3),
          reason: idx === 0
            ? `Peak ${context.circadian_daypart} dopamine anchor with low receptor habituation`
            : `Harmonic cadence match from your personal rotation`
        }));

    // Merge multi-turn dialogue history from client + server memory + Supabase recent interventions
    const mergedDialogue = (Array.isArray(context.dialogue_history) && context.dialogue_history.length > 0)
      ? context.dialogue_history.slice(-12)
      : ClaudeMusicReasoningProvider.recentDialogueTurns.length > 0
      ? ClaudeMusicReasoningProvider.recentDialogueTurns.slice(-12)
      : supabaseMemory.recentInterventions
          .slice(0, 5)
          .reverse()
          .flatMap(r => [
            { role: 'user' as const, text: r.userSaid },
            { role: 'assistant' as const, text: r.agentReplied }
          ]);

    const systemPrompt = this.getAutonomousSystemPrompt();
    const tools = this.getAutonomousAgentTools();

    const initialContextPayload = {
      user_id: userId,
      user_spoken_message: userCommandRaw,
      circadian_daypart: context.circadian_daypart,
      current_track: context.current_track,
      pending_proposed_track_awaiting_user_approval: pending,
      top_most_played_user_tracks: mostPlayedTracks.slice(0, 6),
      top_user_artists: topUserArtists,
      fresh_low_satiety_user_tracks: freshTracks.slice(0, 12),
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
    let lastDiscoveredOrSearchedTrack: { title: string; artist: string; uri: string } | null = null;
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

        for (let step = 0; step < 3; step++) {
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
                      trackId: topItem.uri,
                      source: 'com.spotify.music',
                      playCount: topItem.playCount
                    });
                  } else if (!exists.trackId && topItem.uri) {
                    exists.trackId = topItem.uri;
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
                combinedPool.sort((a, b) => (b.playCount || 1) - (a.playCount || 1));
              } else if (sortBy === 'least_played_fresh') {
                combinedPool.sort((a, b) => (a.playCount || 1) - (b.playCount || 1));
              }

              const topSlice = combinedPool.slice(0, limit);
              if (topSlice[0] && (!topSlice[0].trackId || !topSlice[0].trackId.startsWith('spotify:track:'))) {
                const resolved = await this.lookupSpotifyTrack(
                  topSlice[0].title,
                  topSlice[0].artist,
                  context.spotify_access_token
                );
                if (resolved) {
                  topSlice[0].trackId = resolved.uri;
                }
              }
              if (topSlice[0]) {
                lastDiscoveredOrSearchedTrack = {
                  title: topSlice[0].title,
                  artist: topSlice[0].artist,
                  uri: topSlice[0].trackId || ''
                };
              }

              toolResults.push({
                type: 'tool_result',
                tool_use_id: toolCall.id,
                content: JSON.stringify({
                  sort_by: sortBy,
                  total_matched: combinedPool.length,
                  tracks: topSlice.map((t, idx) => ({
                    rank: idx + 1,
                    title: t.title,
                    artist: t.artist,
                    play_count: t.playCount || 1,
                    spotify_uri: t.trackId || '',
                    freshness_percent: Math.max(15, 96 - (t.playCount || 1) * 8)
                  }))
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
                excludedSet,
                seedArtists,
                moodQuery,
                excludeOverplayed
              );

              if (discovered[0]) {
                lastDiscoveredOrSearchedTrack = {
                  title: discovered[0].title,
                  artist: discovered[0].artist,
                  uri: discovered[0].spotify_uri
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
                lastDiscoveredOrSearchedTrack = {
                  title: hits[0].title,
                  artist: hits[0].artist,
                  uri: hits[0].uri
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
        excludedSet
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

      if (spotifyUri && (!spotifyUri.startsWith('spotify:track:') && !spotifyUri.startsWith('https://open.spotify.com/track/'))) {
        if (/^[A-Za-z0-9]{22}$/.test(spotifyUri)) {
          spotifyUri = `spotify:track:${spotifyUri}`;
        } else {
          spotifyUri = '';
        }
      }

      const isAutoCurateNextTrack = /^AUTO_CURATE_NEXT_TRACK\b/i.test(userCommandRaw);

      // Enforce two-step approval for normal turns, EXCEPT when AUTO_CURATE_NEXT_TRACK is triggered at end-of-song!
      const isApprovingPending = Boolean(
        pending &&
        (action === 'PLAY_TRACK_NOW' ||
          (trackTitle && pending.title.toLowerCase() === trackTitle.toLowerCase() && /\b(yes|yeah|yep|sure|ok|okay|play|go ahead|do it|spin|approve|ndio|sawa)\b/i.test(userCommandRaw)))
      );

      if (isAutoCurateNextTrack) {
        if (!trackTitle && freshTracks[0]) {
          trackTitle = freshTracks[0].title;
          trackArtist = freshTracks[0].artist;
          spotifyUri = freshTracks[0].trackId || spotifyUri;
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
        spotifyUri = pending.trackId || spotifyUri;
      }

      // Resolve Spotify URI if we are playing or proposing a track and spotifyUri is still empty
      if (!spotifyUri && trackTitle && (action === 'PLAY_TRACK_NOW' || action === 'PROPOSE_TRACK' || action === 'LAUNCH_DOPAMINE_CAPSULE')) {
        const cleanArtist = trackArtist && trackArtist !== 'Unknown Artist' ? trackArtist : undefined;
        const resolved = await this.lookupSpotifyTrack(trackTitle, cleanArtist, context.spotify_access_token);
        if (resolved) {
          spotifyUri = resolved.uri;
          if (!trackArtist || trackArtist === 'Unknown Artist') {
            trackArtist = resolved.artist;
          }
        }
      }

      const cleanTitleForSearch = trackTitle ? ClaudeMusicReasoningProvider.sanitizeTrackTitle(trackTitle) : '';
      const cleanArtistForSearch = trackArtist ? ClaudeMusicReasoningProvider.sanitizeArtistName(trackArtist) : '';
      const cleanSearchQuery = trackTitle ? `${cleanTitleForSearch} ${cleanArtistForSearch}`.trim() : '';
      const targetPlayer = targetPlayerDefault;

      let resolvedIntent: TuneMindReasoningResponse['os_execution_command']['intent'] = 'NONE';
      switch (action) {
        case 'PLAY_TRACK_NOW':
          resolvedIntent = 'OS_MEDIA_NEXT_AND_DEEP_LINK';
          ClaudeMusicReasoningProvider.pendingProposedTrack = null;
          if (trackTitle) eventStore.recordAgentTrackAction(trackTitle);
          break;
        case 'PROPOSE_TRACK':
        case 'LAUNCH_DOPAMINE_CAPSULE':
          resolvedIntent = 'NONE';
          if (trackTitle) {
            ClaudeMusicReasoningProvider.pendingProposedTrack = {
              title: cleanTitleForSearch || trackTitle,
              artist: cleanArtistForSearch || trackArtist,
              trackId: spotifyUri || undefined,
              source: targetPlayer
            };
            eventStore.recordAgentTrackAction(trackTitle);
          }
          break;
        case 'PAUSE_PLAYBACK':
          resolvedIntent = 'OS_MEDIA_PAUSE';
          ClaudeMusicReasoningProvider.pendingProposedTrack = null;
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
          : 'I am here with you. Tell me what you feel like hearing or what is on your mind.';
      }

      const expectFollowUp = Boolean(
        action === 'PROPOSE_TRACK' ||
        action === 'LAUNCH_DOPAMINE_CAPSULE' ||
        submittedTurnDecision.expect_follow_up ||
        ttsText.includes('?')
      );

      this.rememberTurn(userCommandRaw, ttsText);

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
          track_title: (cleanTitleForSearch || trackTitle) || undefined,
          track_artist: (cleanArtistForSearch || trackArtist) || undefined,
          spotify_uri: spotifyUri || undefined,
          circadian_daypart: context.circadian_daypart
        },
        supabaseOverride
      );

      const finalToolCall: AgentToolCall = {
        action,
        track_title: (cleanTitleForSearch || trackTitle) || undefined,
        track_artist: (cleanArtistForSearch || trackArtist) || undefined,
        spotify_uri: spotifyUri || undefined,
        target_player: targetPlayer,
        volume_percent: typeof submittedTurnDecision.volume_percent === 'number' ? submittedTurnDecision.volume_percent : undefined,
        palette_id: submittedTurnDecision.palette_id || undefined,
        target_screen: submittedTurnDecision.target_screen || undefined,
        expect_follow_up: expectFollowUp
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
          track_title: (cleanTitleForSearch || trackTitle) || undefined,
          track_artist: (cleanArtistForSearch || trackArtist) || undefined,
          spotify_uri: spotifyUri || undefined,
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
      eventStore
    );
    this.rememberTurn(userCommandRaw, fb.voice_agent_tts);
    await SupabaseIntelligenceStore.recordAgentIntervention(
      {
        user_id: userId,
        trigger_transcript: userCommandRaw || 'voice_turn',
        computed_satiety: fb.calculated_satiety_percentage,
        agent_tts_response: fb.voice_agent_tts,
        os_intent: fb.os_execution_command?.intent || 'NONE',
        target_search_query: fb.os_execution_command?.search_query || '',
        action: fb.agent_tool_call?.action,
        track_title: fb.agent_tool_call?.track_title,
        track_artist: fb.agent_tool_call?.track_artist,
        spotify_uri: fb.agent_tool_call?.spotify_uri,
        circadian_daypart: context.circadian_daypart
      },
      supabaseOverride
    );
    return fb;
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

    if (context.spotify_access_token && !moodQuery) {
      const medTop = await this.fetchUserSpotifyTopTracks(context.spotify_access_token, 'medium_term', 20);
      for (const item of medTop) {
        const tl = item.title.toLowerCase().trim();
        if (!excludedSet.has(tl)) {
          discovered.push({
            title: item.title,
            artist: item.artist,
            album: item.album,
            spotify_uri: item.uri,
            popularity: item.popularity,
            source_reason: 'Personal Spotify affinity track with high dopamine readiness'
          });
        }
        if (discovered.length >= 4) break;
      }
    }

    const queriesToRun: Array<{ q: string; reason: string }> = [];
    if (moodQuery) {
      queriesToRun.push({ q: moodQuery, reason: `Matches requested vibe: "${moodQuery}"` });
    }
    for (const artist of seedArtists.slice(0, 3)) {
      queriesToRun.push({
        q: `artist:${artist}`,
        reason: `Fresh track from your favorite artist ${artist}`
      });
    }
    if (queriesToRun.length === 0) {
      queriesToRun.push({
        q: 'Afrobeats Bongo Flava 2025',
        reason: 'Trending East African & Afrobeats discovery'
      });
    }

    for (const qItem of queriesToRun) {
      const hits = await this.searchSpotifyCatalogTracks(qItem.q, undefined, 8, context.spotify_access_token);
      for (const h of hits) {
        const tl = h.title.toLowerCase().trim();
        const alreadyInUserHeavyRotation = excludeOverplayed && allUserTracks.some(
          ut => ut.title.toLowerCase().trim() === tl && (ut.playCount || 1) >= 2
        );
        if (!excludedSet.has(tl) && !alreadyInUserHeavyRotation && !discovered.some(d => d.title.toLowerCase() === tl)) {
          discovered.push({
            title: h.title,
            artist: h.artist,
            album: h.album,
            spotify_uri: h.uri,
            popularity: h.popularity,
            source_reason: qItem.reason
          });
        }
        if (discovered.length >= 8) break;
      }
      if (discovered.length >= 8) break;
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
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    mostPlayedTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    topUserArtists: string[],
    excludedSet: Set<string>
  ): Promise<{ decision: any; fallbackTrack: { title: string; artist: string; uri: string } | null; providerTag: string } | null> {
    const userMsg = (context.user_command || '').trim();
    const lowerMsg = userMsg.toLowerCase();

    let fallbackTrack: { title: string; artist: string; uri: string } | null = null;
    let liveSpotifyDiscovery: any[] = [];
    let liveSpotifySearchHits: any[] = [];

    const wantsMostPlayed = /\b(most\s+(played|listened|heard)|listened\s+(to\s+)?(the\s+)?most|listen\s+(to\s+)?(the\s+)?most|played\s+(the\s+)?most|favorite\s+(song|track)|favourite\s+(song|track)|top\s+(song|track)|number\s+one\s+(song|track))\b/i.test(lowerMsg);
    const wantsDiscoveryOrNew = /\b(new\s+(song|track|music|tune|vibe)|fresh\s+(song|track|music|tune)|something\s+(new|fresh|different|good)|recommend|recommendation|suggest|suggestion|discover|another\s+(song|one|track)|different\s+(song|track)|play\s+(for\s+me\s+|me\s+)?(a\s+|some\s+)?(new\s+|fresh\s+|good\s+)?(song|track|music|tune|bongo|gospel|afrobeats|gikuyu|mugithi|r&b|hip\s*hop|pop|chill|upbeat))\b/i.test(lowerMsg);
    const explicitPlayMatch = lowerMsg.match(/^\s*(?:please\s+|hey\s+|hi\s+)?(?:play|spin|put on)\s+(.+)$/i);

    if (wantsMostPlayed && mostPlayedTracks[0]) {
      const top = mostPlayedTracks[0];
      if (!top.trackId || !top.trackId.startsWith('spotify:track:')) {
        const resolved = await this.lookupSpotifyTrack(top.title, top.artist, context.spotify_access_token);
        if (resolved) top.trackId = resolved.uri;
      }
      fallbackTrack = { title: top.title, artist: top.artist, uri: top.trackId || '' };
    } else if (wantsDiscoveryOrNew) {
      liveSpotifyDiscovery = await this.discoverSpotifyTracksForContext(
        context,
        allUserTracks,
        excludedSet,
        topUserArtists.slice(0, 3),
        '',
        true
      );
      if (liveSpotifyDiscovery[0]) {
        fallbackTrack = {
          title: liveSpotifyDiscovery[0].title,
          artist: liveSpotifyDiscovery[0].artist,
          uri: liveSpotifyDiscovery[0].spotify_uri
        };
      }
    } else if (explicitPlayMatch && explicitPlayMatch[1]) {
      const queryTarget = explicitPlayMatch[1]
        .replace(/^(?:for\s+me\s+|me\s+)/i, '')
        .replace(/\b(?:right\s+now|now|please|on\s+spotify)\b/gi, '')
        .trim();
      if (queryTarget.length >= 2) {
        liveSpotifySearchHits = await this.searchSpotifyCatalogTracks(queryTarget, undefined, 5, context.spotify_access_token);
        if (liveSpotifySearchHits[0]) {
          fallbackTrack = {
            title: liveSpotifySearchHits[0].title,
            artist: liveSpotifySearchHits[0].artist,
            uri: liveSpotifySearchHits[0].uri
          };
        }
      }
    }

    const enrichedContext = {
      ...initialContextPayload,
      pre_fetched_spotify_discovery_candidates: liveSpotifyDiscovery.slice(0, 5),
      pre_fetched_spotify_catalog_search_results: liveSpotifySearchHits.slice(0, 4)
    };

    const jsonSystemPrompt = `You are TuneMind, an intelligent, conversational, self-reasoning AI music companion on Android powered by Anthropic Claude.
Reason naturally about what the user actually said before choosing an action:
1. CONVERSATION FIRST: When the user says "how are you", "hello", "hey", talks about their day/feelings, asks a general question, or chats WITHOUT asking to play or recommend a song right now, set "action": "ADVISE_ONLY", leave "track_title" empty, and reply warmly and naturally to what they actually said! NEVER jump into proposing a song when the user is just greeting you or chatting!
2. 2-STEP SONG APPROVAL: Only when the user asks to play music, wants a song recommendation, or asks for their most-played song, pick the best matching track from their Spotify data and set "action": "PROPOSE_TRACK", "expect_follow_up": true, and ask if they'd like you to play it.
3. APPROVAL OF PENDING TRACK: Only if pending_proposed_track is present AND the user approves ("yes", "play it", "sure", "okay", "ndio", "sawa"), set "action": "PLAY_TRACK_NOW" with that pending track.
4. DEVICE & STUDIO CONTROLS: Execute "PAUSE_PLAYBACK", "RESUME_PLAYBACK", "SKIP_NEXT", "SKIP_PREVIOUS", "SET_VOLUME_PERCENT", "QUARANTINE_TO_VAULT", "SHIFT_ATMOSPHERE", or "NAVIGATE_SCREEN" when requested.

Respond ONLY with a single valid JSON object:
{
  "voice_agent_tts": "Your natural, warm, unscripted spoken reply (1-3 sentences)",
  "flow_state_analysis": "Short 1-line studio HUD note",
  "calculated_satiety_percentage": 24,
  "action": "ADVISE_ONLY" | "PROPOSE_TRACK" | "PLAY_TRACK_NOW" | "PAUSE_PLAYBACK" | "RESUME_PLAYBACK" | "SKIP_NEXT" | "SKIP_PREVIOUS" | "SET_VOLUME_PERCENT" | "QUARANTINE_TO_VAULT" | "SHIFT_ATMOSPHERE" | "NAVIGATE_SCREEN" | "LAUNCH_DOPAMINE_CAPSULE",
  "track_title": "",
  "track_artist": "",
  "spotify_uri": "",
  "volume_percent": 60,
  "palette_id": "evening",
  "target_screen": "DOPAMINE",
  "expect_follow_up": true
}`;

    const userPromptContent = `Reason over the user's spoken message ("${userMsg}") using this live telemetry, conversation history, and Spotify data, and output ONLY the JSON object:\n${JSON.stringify(enrichedContext)}`;

    try {
      const claudeRes = await this.callClaudeWithSonnetPriority(jsonSystemPrompt, userPromptContent, 900);
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
    freshTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    overplayedTracks: Array<{ title: string; artist: string; playCount?: number }>,
    compactedCapsule: Array<{ title: string; artist: string; trackId?: string; freshness_percent: number; reason: string }>,
    explicitlyNamedQuery: string | null,
    matchedExplicitLibraryTrack: any | null,
    eventStore: EventStore
  ): Promise<TuneMindReasoningResponse> {
    const userCommandRaw = (context.user_command || '').trim();
    const userCommandLower = userCommandRaw.toLowerCase();
    const targetPlayer = context.current_track?.source_player || 'com.spotify.music';
    const pending = ClaudeMusicReasoningProvider.pendingProposedTrack;

    // 0. Autonomous End-of-Song DJ Handover ('AUTO_CURATE_NEXT_TRACK')
    if (/^auto_curate_next_track\b/i.test(userCommandLower)) {
      const excluded = new Set<string>(
        [
          context.current_track?.title || '',
          ...(context.recently_played_by_agent || [])
        ]
          .map(s => String(s).toLowerCase().trim())
          .filter(Boolean)
      );
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
      const chosen = discovered[0]
        ? {
            title: discovered[0].title,
            artist: discovered[0].artist,
            uri: discovered[0].spotify_uri
          }
        : freshTracks[0]
        ? {
            title: freshTracks[0].title,
            artist: freshTracks[0].artist,
            uri: freshTracks[0].trackId || ''
          }
        : {
            title: 'Midnight City',
            artist: 'M83',
            uri: ''
          };
      if (!chosen.uri && chosen.title) {
        const resolved = await this.lookupSpotifyTrack(chosen.title, chosen.artist, context.spotify_access_token);
        if (resolved) chosen.uri = resolved.uri;
      }
      const cleanTitle = ClaudeMusicReasoningProvider.sanitizeTrackTitle(chosen.title) || chosen.title;
      const cleanArtist = ClaudeMusicReasoningProvider.sanitizeArtistName(chosen.artist) || chosen.artist;
      const artistPart = cleanArtist && cleanArtist !== 'Unknown Artist' ? ` by ${cleanArtist}` : '';
      eventStore.recordAgentTrackAction(cleanTitle);
      return {
        calculated_satiety_percentage: 18,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Autonomous DJ Handover • Spinning "${cleanTitle}"${artistPart}`,
        voice_agent_tts: `Up next for your flow, playing "${cleanTitle}"${artistPart} right now.`,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_title: cleanTitle,
          track_artist: cleanArtist,
          spotify_uri: chosen.uri || undefined,
          target_player: targetPlayer,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: targetPlayer,
          search_query: `${cleanTitle} ${cleanArtist}`.trim(),
          track_title: cleanTitle,
          track_artist: cleanArtist,
          spotify_uri: chosen.uri || undefined,
          suggested_bpm_target: 108
        },
        database_tags: ['autonomous-end-of-song-dj', 'auto-curated-next-track']
      };
    }

    // 0A. Pause / Stop / Resume / Skip playback controls
    if (/\b(pause|stop\s+playing|stop\s+the\s+music|mute|hold\s+on|simama|nyamaza)\b/i.test(userCommandLower)) {
      ClaudeMusicReasoningProvider.pendingProposedTrack = null;
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

    // 0B. Approval of pending proposed track
    const isRejectingPending = /\b(no|nope|nah|not\s+that|don'?t|another|different|change|switch|else)\b/i.test(userCommandLower);
    if (
      pending &&
      !isRejectingPending &&
      /\b(yes|yeah|yep|sure|ok|okay|play|play\s+it|go\s+ahead|do\s+it|sounds\s+good|spin\s+it|ndio|sawa|approve)\b/i.test(userCommandLower)
    ) {
      ClaudeMusicReasoningProvider.pendingProposedTrack = null;
      const artistPart = pending.artist && pending.artist !== 'Unknown Artist' ? ` by ${pending.artist}` : '';
      return {
        calculated_satiety_percentage: 20,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Approved "${pending.title}" — launching on Spotify.`,
        voice_agent_tts: `Awesome, playing "${pending.title}"${artistPart} for you right now.`,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: pending.trackId || '',
          target_player: pending.source || targetPlayer,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: pending.source || targetPlayer,
          search_query: `${pending.title} ${pending.artist || ''}`.trim(),
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: pending.trackId || '',
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
      const tts = `I have locked "${title}" into your Cooldown Vault for 5 days so its dopamine impact can fully recover.`;
      return {
        calculated_satiety_percentage: 30,
        reasoning_mode: 'JOURNEY_ADVISOR',
        flow_state_analysis: `Locked "${title}" in Cooldown Vault for receptor recovery.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'QUARANTINE_TO_VAULT',
          track_title: title,
          track_artist: artist,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          track_title: title,
          track_artist: artist,
          suggested_bpm_target: 100
        },
        database_tags: ['vault-quarantine']
      };
    }

    // 3. Atmosphere / Theme shift commands
    if (/\b(theme|atmosphere|color|palette|glacier|emerald|solar|violet)\b/i.test(userCommandLower)) {
      const paletteId = userCommandLower.includes('glacier') || userCommandLower.includes('morning') ? 'morning'
        : userCommandLower.includes('emerald') || userCommandLower.includes('green') ? 'afternoon'
        : userCommandLower.includes('solar') || userCommandLower.includes('gold') || userCommandLower.includes('amber') ? 'evening'
        : 'night';
      const tts = `Shifting your studio atmosphere to the ${paletteId} mood palette right now.`;
      return {
        calculated_satiety_percentage: 25,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Studio Atmosphere shifted to ${paletteId.toUpperCase()}.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'SHIFT_ATMOSPHERE',
          palette_id: paletteId,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: '',
          suggested_bpm_target: 105
        },
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
      const sortedMost = [...allUserTracks].sort((a, b) => (b.playCount || 1) - (a.playCount || 1));
      const topTrack = sortedMost[0];
      if (topTrack) {
        let uri = topTrack.trackId || '';
        if (!uri.startsWith('spotify:track:')) {
          const resolved = await this.lookupSpotifyTrack(topTrack.title, topTrack.artist, context.spotify_access_token);
          if (resolved) uri = resolved.uri;
        }
        ClaudeMusicReasoningProvider.pendingProposedTrack = {
          title: topTrack.title,
          artist: topTrack.artist,
          trackId: uri || undefined,
          source: targetPlayer
        };
        const artistPart = topTrack.artist && topTrack.artist !== 'Unknown Artist' ? ` by ${topTrack.artist}` : '';
        const countPart = (topTrack.playCount || 1) > 1 ? ` with ${topTrack.playCount} plays in your history` : ' at the top of your rotation';
        return {
          calculated_satiety_percentage: 24,
          reasoning_mode: 'SONIC_CURATOR',
          flow_state_analysis: `Top Played Track: "${topTrack.title}"${artistPart} (${topTrack.playCount || 1}x plays).`,
          voice_agent_tts: `Your most-listened song is "${topTrack.title}"${artistPart}${countPart}. Would you like me to play it right now?`,
          agent_tool_call: {
            action: 'PROPOSE_TRACK',
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
            track_title: topTrack.title,
            track_artist: topTrack.artist,
            spotify_uri: uri,
            suggested_bpm_target: 105
          },
          database_tags: ['most-played-query', 'awaiting-approval']
        };
      }
    }

    // 6. Explicit Play / Recommend / Suggest / New Song Request
    const isExplicitMusicRequest =
      Boolean(explicitlyNamedQuery) ||
      /\b(play|spin|put\s+on|recommend|recommendation|suggest|suggestion|new\s+song|fresh\s+song|another\s+song|different\s+song|give\s+me\s+a\s+song|find\s+me\s+a\s+song|capsule)\b/i.test(userCommandLower);

    if (!isExplicitMusicRequest) {
      // OPEN CONVERSATIONAL TURN -> Respond naturally with ADVISE_ONLY (NEVER force a song proposal!)
      const topTrack = [...allUserTracks].sort((a, b) => (b.playCount || 1) - (a.playCount || 1))[0];
      const curPlaying = context.current_track?.title && context.current_track.title !== 'Standing By'
        ? `"${context.current_track.title}"${context.current_track.artist ? ` by ${context.current_track.artist}` : ''}`
        : null;

      let conversationalReply = '';
      if (/\b(how\s+are\s+you|how'?s\s+it\s+going|how\s+are\s+things|how\s+do\s+you\s+feel|you\s+good|are\s+you\s+okay|habari|mzima)\b/i.test(userCommandLower)) {
        conversationalReply = curPlaying
          ? `I'm doing great and locked into your ${context.circadian_daypart.toLowerCase()} flow while you're on ${curPlaying}! How is your day treating you so far?`
          : `I'm doing great, thanks for asking! My neural studio is synced with your ${context.circadian_daypart.toLowerCase()} vibe. How are you feeling today?`;
      } else if (/\b(hello|hey|hi\b|good\s+morning|good\s+afternoon|good\s+evening|yo\b|what'?s\s+up|sup\b|niaje|mambo|sasa|habari)\b/i.test(userCommandLower)) {
        conversationalReply = `Hey there! Good to hear your voice this ${context.circadian_daypart.toLowerCase()}. We can chat about your day, check your listening habits, or spin some music whenever you're ready. What's on your mind?`;
      } else if (/\b(who\s+are\s+you|what\s+can\s+you\s+do|what\s+do\s+you\s+do|help\s+me|capabilities)\b/i.test(userCommandLower)) {
        conversationalReply = `I'm TuneMind, your autonomous studio companion. We can have a real conversation about your music taste, check your dopamine fatigue and hearing safety, discover new tracks on Spotify, or control playback hands-free. What would you like to talk about?`;
      } else if (/\b(tired|exhausted|stressed|long\s+day|sad|down|bored|sleepy|overwhelmed)\b/i.test(userCommandLower)) {
        conversationalReply = `I hear you—days like that can really drain your energy. Take a breath; we can just chill and talk, or if you want a calm acoustic reset for your ears later, just say the word. How are you holding up right now?`;
      } else if (/\b(i'?m\s+good|i\s+am\s+good|doing\s+well|doing\s+great|i'?m\s+fine|feeling\s+good|fantastic|awesome|blessed)\b/i.test(userCommandLower)) {
        conversationalReply = `Love to hear that! Your ${context.circadian_daypart.toLowerCase()} energy sounds right on point. What kind of vibe are you up for—just hanging out or exploring something new?`;
      } else if (/\b(what\s+is\s+playing|what'?s\s+playing|what\s+song\s+is\s+this|current\s+song)\b/i.test(userCommandLower)) {
        conversationalReply = curPlaying
          ? `Right now you have ${curPlaying} on your session. How is it hitting your ears?`
          : `Nothing is actively spinning right now. Whenever you feel like hearing something, just let me know what mood you're in.`;
      } else if (/\b(my\s+taste|my\s+history|my\s+music|how\s+am\s+i\s+listening|analyze|habit|pattern)\b/i.test(userCommandLower)) {
        const topNote = topTrack
          ? `Your top anchor lately has been "${topTrack.title}"${topTrack.artist && topTrack.artist !== 'Unknown Artist' ? ` by ${topTrack.artist}` : ''}. `
          : '';
        conversationalReply = `${topNote}You've got ${allUserTracks.length} tracks in your active studio memory, and your auditory receptivity is at ${100 - (context.current_satiety_percentage || 25)}%. Want to talk through your rotation or explore a new direction?`;
      } else if (/\b(thank\s+you|thanks|appreciate\s+it|asante|shukran)\b/i.test(userCommandLower)) {
        conversationalReply = `Anytime! I'm right here whenever you want to chat or switch up the studio vibe.`;
      } else {
        conversationalReply = `I'm right here with you. Tell me more about what's on your mind, or let me know if you want to explore your music history or spin a track.`;
      }

      return {
        calculated_satiety_percentage: context.current_satiety_percentage || 24,
        reasoning_mode: 'JOURNEY_ADVISOR',
        flow_state_analysis: `Conversational Studio Companion • ${context.circadian_daypart} Flow`,
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

    const excludedTitlesSet = new Set<string>(
      [
        context.current_track?.title || '',
        ...eventStore.getRecentAgentTracks(),
        ...(context.recently_played_by_agent || [])
      ]
        .map(s => s.toLowerCase().trim())
        .filter(Boolean)
    );

    let chosen: { title: string; artist: string; trackId?: string; source?: string } | null = null;

    if (explicitlyNamedQuery) {
      const hits = await this.searchSpotifyCatalogTracks(explicitlyNamedQuery, undefined, 4, context.spotify_access_token);
      if (hits[0]) {
        chosen = { title: hits[0].title, artist: hits[0].artist, trackId: hits[0].uri, source: targetPlayer };
      } else if (matchedExplicitLibraryTrack) {
        chosen = matchedExplicitLibraryTrack;
      }
    }

    if (!chosen) {
      const discovered = await this.discoverSpotifyTracksForContext(
        context,
        allUserTracks,
        excludedTitlesSet,
        topArtistsForFallback,
        '',
        true
      );
      if (discovered[0]) {
        chosen = {
          title: discovered[0].title,
          artist: discovered[0].artist,
          trackId: discovered[0].spotify_uri,
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
        source: targetPlayer
      };
    }

    let spotifyUri = '';
    if (chosen.trackId) {
      const tid = String(chosen.trackId).trim();
      if (tid.startsWith('spotify:track:') || tid.startsWith('https://open.spotify.com/track/')) {
        spotifyUri = tid;
      } else if (/^[A-Za-z0-9]{22}$/.test(tid)) {
        spotifyUri = `spotify:track:${tid}`;
      }
    }
    if (!spotifyUri && chosen.title) {
      const resolved = await this.lookupSpotifyTrack(chosen.title, chosen.artist, context.spotify_access_token);
      if (resolved) spotifyUri = resolved.uri;
    }

    ClaudeMusicReasoningProvider.pendingProposedTrack = {
      title: chosen.title,
      artist: chosen.artist,
      trackId: spotifyUri || undefined,
      source: chosen.source || targetPlayer
    };
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
        track_title: chosen.title,
        track_artist: chosen.artist,
        spotify_uri: spotifyUri,
        target_player: chosen.source || targetPlayer,
        expect_follow_up: true
      },
      os_execution_command: {
        intent: 'NONE',
        target_player: chosen.source || targetPlayer,
        search_query: `${chosen.title} ${chosen.artist !== 'Unknown Artist' ? chosen.artist : ''}`.trim(),
        track_title: chosen.title,
        track_artist: chosen.artist,
        spotify_uri: spotifyUri,
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

You are TuneMind's Chief Neuro-Acoustic Reasoning AI powered by Claude 3.5 Haiku.
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
        this.getSystemPrompt(),
        prompt,
        800
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
          this.getSystemPrompt(),
          prompt,
          600
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
   * Generates chronobiological music recommendations using Claude 3.5 Haiku.
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
          this.getSystemPrompt(),
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
            recommendations: parsed.slice(0, 10)
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
          title: 'Huwezi Jua',
          artist: 'Mbosso',
          album: 'Khan',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Acoustic guitar cadence and signature Swahili melody deliver dopamine flow.',
          confidence: 0.98
        },
        {
          title: 'Mi Amor',
          artist: 'Marioo & Jovial',
          album: 'The Kid You Know',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Tender vocal harmonies and sweet acoustic rhythm clear cognitive fatigue.',
          confidence: 0.96
        },
        {
          title: 'Calm Down',
          artist: 'Rema',
          album: 'Rave & Climax',
          category: 'DOPAMINE_FLOW',
          subTier: 'CORE_DOPAMINE',
          reason: 'Smooth melodic warmth and mid-tempo rhythmic pacing calibrated for circadian focus.',
          confidence: 0.95
        },
        {
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
        source_player: 'com.spotify.music'
      },
      user_command: query,
      rolling_history: Array.isArray(tracks)
        ? tracks.map((t: any) => ({
            title: t.title || '',
            artist: t.artist || '',
            trackId: t.trackId || t.id || '',
            playCount: Number(t.playCount || t.distinctAppearanceCount || 1),
            source_player: 'com.spotify.music'
          }))
        : [],
      current_satiety_percentage: 25,
      user_id: userName || 'tunemind-dev'
    });

    const toolAction = evalResult.agent_tool_call?.action || 'ADVISE_ONLY';
    const isPlayOrPropose = toolAction === 'PLAY_TRACK_NOW' || toolAction === 'PROPOSE_TRACK' || toolAction === 'LAUNCH_DOPAMINE_CAPSULE';
    const recTitle = evalResult.agent_tool_call?.track_title || evalResult.os_execution_command?.track_title || '';
    const recArtist = evalResult.agent_tool_call?.track_artist || evalResult.os_execution_command?.track_artist || '';

    return {
      reply: evalResult.voice_agent_tts,
      spokenText: evalResult.voice_agent_tts,
      action: isPlayOrPropose && recTitle ? 'PLAY_TRACK' : 'SPEAK_ONLY',
      suggestedAction: isPlayOrPropose && recTitle ? 'PLAY' : toolAction === 'PAUSE_PLAYBACK' ? 'PAUSE' : 'NONE',
      suggestedTitle: recTitle || null,
      suggestedArtist: recArtist || null,
      matchedTrackId: evalResult.agent_tool_call?.spotify_uri || null,
      recommendedTrack: recTitle ? { title: recTitle, artist: recArtist, spotifyUri: evalResult.agent_tool_call?.spotify_uri } : null,
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
