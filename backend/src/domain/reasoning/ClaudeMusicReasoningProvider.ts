import Anthropic from '@anthropic-ai/sdk';
import { v4 as uuidv4 } from 'uuid';
import { supabase } from '../../config/supabase';
import { EventStore } from '../events/EventStore';
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
  private activeModelUsed: string = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
  private lastErrorDetails: { status?: number | string; message?: string } | null = null;
  private spotifyAccessToken: { token: string; expiresAt: number } | null = null;

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
    this.activeModelUsed = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
  }

  getLastDiagnostics() {
    const rawKey = process.env.ANTHROPIC_API_KEY;
    return {
      hasAiClient: Boolean(this.anthropic),
      keyPresent: Boolean(rawKey && rawKey !== 'your_key_here'),
      keyLength: rawKey?.length || 0,
      hasInvalidKey: this.hasInvalidKey,
      lastErrorDetails: this.lastErrorDetails,
      activeModelUsed: this.activeModelUsed
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

  private getSystemPrompt(): string {
    return `You are TuneMind, an autonomous psychoacoustic music intelligence, personal audio advisor, and full-app voice controller on Android.
You have real-time access to the listener's complete telemetry:
- 'current_track': Currently playing or active session track.
- 'fresh_unplayed_user_tracks': Real songs from the listener's library with low satiety (high dopamine readiness).
- 'overplayed_high_satiety_tracks': Songs the listener has been looping heavily that risk receptor habituation.
- 'vault_tracks': Songs resting in the Cooldown Vault and their recovery percentage.
- 'dopamine_capsule': A pre-compacted 3-track capsule tailored for the listener's current circadian daypart.
- 'hearing_telemetry': Live decibel exposure ('estimated_db'), safe headroom ('safe_headroom_db'), output device, and ear-fatigue guidance.
- 'recent_dialogue_turns': Rolling multi-turn conversation memory.

YOUR CAPABILITIES & DECISION RULES:
1. TRUE CONVERSATIONAL & PSYCHOACOUSTIC ADVISOR ('ADVISE_ONLY'):
   - When the user asks about their music journey, listening habits, ear fatigue, decibels, hearing safety, why a song feels stale, or just wants to chat/reflect:
   - Reason deeply and conversationally over their real data (mentioning specific track names, play counts, dB levels, or vault recovery % when relevant).
   - Set 'agent_tool_call.action' to "ADVISE_ONLY" and 'os_execution_command.intent' to "NONE". Do NOT force a random song proposal if they are asking for advice or insight!
   - If you ask a natural follow-up question, set 'agent_tool_call.expect_follow_up' to true.

2. DOPAMINE CAPSULE BRIEFING ('LAUNCH_DOPAMINE_CAPSULE'):
   - When the user asks what you have stored/prepared for them today, asks for their morning/session capsule, or asks for a curated dopamine session:
   - Present the compacted tracks from 'dopamine_capsule' (or top 'fresh_unplayed_user_tracks'), explain the micro-pattern why you compacted them, and ask if they want to kick off the capsule with the lead track.
   - Set 'agent_tool_call.action' to "LAUNCH_DOPAMINE_CAPSULE", populate 'track_title' and 'track_artist' with the #1 capsule track, and set 'expect_follow_up' to true.

3. SMART SONG PLAYBACK — DIRECT NAMED REQUEST vs. CONSULTATIVE SUGGESTION:
   - DIRECT REQUEST ('PLAY_TRACK_NOW'): If the user explicitly names a specific song or artist to play (e.g. "Play Utaniinua", "Play Wakadinali", "Play Calm Down") OR approves your previous suggestion ("yes", "play it", "go ahead"), set 'agent_tool_call.action' to "PLAY_TRACK_NOW" and 'os_execution_command.intent' to "OS_MEDIA_NEXT_AND_DEEP_LINK" so it plays immediately!
   - CONSULTATIVE SUGGESTION ('PROPOSE_TRACK'): If the user asks generally for a song without naming a specific title (e.g. "Play me a song", "Suggest something fresh", "Change the song", "Give me a vibe"), pick the best track from 'fresh_unplayed_user_tracks', explain in 1 vivid clause why it fits their dopamine/circadian state, and ask if they want you to play it. Set 'agent_tool_call.action' to "PROPOSE_TRACK", 'os_execution_command.intent' to "NONE", and 'expect_follow_up' to true.

4. FULL APP & OS TOOL EXECUTION:
   - STOP / PAUSE: User asks to stop, pause, hold, mute, or quiet the music -> 'action': "PAUSE_PLAYBACK", 'intent': "OS_MEDIA_PAUSE", 'expect_follow_up': false.
   - RESUME / CONTINUE: User asks to resume, unpause, or continue playing -> 'action': "RESUME_PLAYBACK", 'intent': "OS_MEDIA_RESUME", 'expect_follow_up': false.
   - SKIP NEXT / PREVIOUS: User asks for next track or previous track -> 'action': "SKIP_NEXT" (or "SKIP_PREVIOUS"), 'intent': "OS_MEDIA_NEXT" (or "OS_MEDIA_PREV").
   - VOLUME & SAFE HEARING CONTROL: User asks to lower/raise volume, set volume to X%, or protect their ears -> 'action': "SET_VOLUME_PERCENT", 'volume_percent': 10..100 (e.g. 45 for safe ear recovery, 75 for boost).
   - COOLDOWN VAULT CONTROL: User asks to lock/quarantine/rest an overplayed song in the vault -> 'action': "QUARANTINE_TO_VAULT", populate 'track_title' & 'track_artist'. User asks to unlock/restore a song from the vault -> 'action': "RESTORE_FROM_VAULT", populate 'track_title'.
   - ATMOSPHERE / THEME SHIFT: User asks to change the theme, mood color, or studio atmosphere -> 'action': "SHIFT_ATMOSPHERE", 'palette_id': "morning" (Glacier Dawn), "afternoon" (Emerald Velocity), "evening" (Solar Ember), "night" (Synaptic Violet), or "auto".
   - SCREEN NAVIGATION: User asks to open/show the Dopamine Vault, Rhythm DNA, or Agent screen -> 'action': "NAVIGATE_SCREEN", 'target_screen': "DOPAMINE" | "RHYTHM" | "AGENT".

Keep 'voice_agent_tts' natural, warm, insightful, and concise (1-3 spoken sentences, max 38 words).
Respond ONLY with raw valid JSON matching this schema:
{
  "calculated_satiety_percentage": 32,
  "reasoning_mode": "JOURNEY_ADVISOR",
  "flow_state_analysis": "1 concise studio telemetry insight for the UI console",
  "voice_agent_tts": "Natural spoken response to the user",
  "agent_tool_call": {
    "action": "ADVISE_ONLY",
    "track_title": "",
    "track_artist": "",
    "spotify_uri": "",
    "target_player": "com.spotify.music",
    "volume_percent": 60,
    "palette_id": "auto",
    "target_screen": "AGENT",
    "expect_follow_up": true
  },
  "os_execution_command": {
    "intent": "NONE",
    "target_player": "com.spotify.music",
    "search_query": "",
    "track_title": "",
    "track_artist": "",
    "spotify_uri": "",
    "suggested_bpm_target": 108
  },
  "database_tags": ["autonomous-agent"]
}`;
  }

  private async getSpotifyAccessToken(): Promise<string | null> {
    const clientId = process.env.SPOTIFY_CLIENT_ID;
    const clientSecret = process.env.SPOTIFY_CLIENT_SECRET;

    if (!clientId || !clientSecret) {
      return null;
    }

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

  public async lookupSpotifyTrack(query: string, artist?: string): Promise<{ trackId: string; uri: string; title: string; artist: string } | null> {
    const token = await this.getSpotifyAccessToken();
    if (!token) return null;

    try {
      const q = artist ? `track:${query} artist:${artist}` : query;
      const searchRes = await fetch(`https://api.spotify.com/v1/search?q=${encodeURIComponent(q)}&type=track&limit=1`, {
        headers: { Authorization: `Bearer ${token}` }
      });

      const searchData = (await searchRes.json()) as any;
      const item = searchData.tracks?.items?.[0];

      if (item && item.uri) {
        console.log(`[Spotify Lookup] 🎯 Resolved exact Spotify URI for "${query}": ${item.uri} (${item.name} by ${item.artists?.[0]?.name})`);
        return {
          trackId: item.id,
          uri: item.uri,
          title: item.name,
          artist: item.artists?.[0]?.name || ''
        };
      }
    } catch (error) {
      console.warn('[Spotify Lookup] Error resolving Spotify track:', error);
    }
    return null;
  }

  private rememberTurn(userText: string, assistantText: string) {
    ClaudeMusicReasoningProvider.recentDialogueTurns.push(
      { role: 'user', text: userText },
      { role: 'assistant', text: assistantText }
    );
    if (ClaudeMusicReasoningProvider.recentDialogueTurns.length > 10) {
      ClaudeMusicReasoningProvider.recentDialogueTurns = ClaudeMusicReasoningProvider.recentDialogueTurns.slice(-10);
    }
  }

  public async evaluateSatiety(context: TuneMindTelemetryContext): Promise<TuneMindReasoningResponse> {
    const eventStore = new EventStore();
    const userCommandRaw = (context.user_command || '').trim();
    const userCommandLower = userCommandRaw.toLowerCase();
    const targetPlayerDefault = context.current_track?.source_player || 'com.spotify.music';

    // ─── 1. DETERMINISTIC FAST-PATH FOR EXPLICIT STOP / PAUSE COMMANDS ─────────
    const isExplicitPause = /\b(pause|stop|hold on|quiet|mute|silence|shut up|simama|nyamaza|kill the music|stop playing|stop the song|pause the song|pause music|stop music)\b/i.test(userCommandLower) &&
      !/\b(don'?t\s+stop|never\s+stop)\b/i.test(userCommandLower);

    if (isExplicitPause) {
      ClaudeMusicReasoningProvider.pendingProposedTrack = null;
      const tts = 'Paused your playback. I am right here whenever you want to talk about your music journey or spin something new.';
      this.rememberTurn(userCommandRaw, tts);
      console.log('[Claude Provider] ⏸️ Explicit STOP/PAUSE command detected — executing OS_MEDIA_PAUSE immediately.');
      return {
        calculated_satiety_percentage: context.current_satiety_percentage || 28,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Playback paused on voice command • Studio standing by.',
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'PAUSE_PLAYBACK',
          target_player: targetPlayerDefault,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_PAUSE',
          target_player: targetPlayerDefault,
          search_query: '',
          suggested_bpm_target: 90
        },
        database_tags: ['voice-pause-command', 'direct-execution']
      };
    }

    // ─── 2. DETERMINISTIC FAST-PATH FOR RESUME / UNPAUSE ──────────────────────
    const isExplicitResume = /\b(resume|unpause|continue playing|keep playing|start playing again)\b/i.test(userCommandLower);
    if (isExplicitResume) {
      const tts = 'Resuming your music right now.';
      this.rememberTurn(userCommandRaw, tts);
      return {
        calculated_satiety_percentage: context.current_satiety_percentage || 28,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: 'Playback resumed via voice command.',
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'RESUME_PLAYBACK',
          target_player: targetPlayerDefault,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_RESUME',
          target_player: targetPlayerDefault,
          search_query: '',
          suggested_bpm_target: 105
        },
        database_tags: ['voice-resume-command']
      };
    }

    // ─── 3. DETERMINISTIC FAST-PATH FOR APPROVAL OF PENDING PROPOSED TRACK ────
    const pending = ClaudeMusicReasoningProvider.pendingProposedTrack;
    const isRejected = /\b(no|nope|nah|not that|don'?t|another|different|change|switch|skip|else|next)\b/i.test(userCommandLower);
    const isApproval = pending !== null && !isRejected && (
      /\b(yes|yeah|yep|yup|ya|sure|ok|okay|alright|fine|play|play it|play that|go ahead|do it|put it on|let'?s go|sounds good|good|perfect|ndio|sawa|haya|please|approve|confirm|start|spin it)\b/i.test(userCommandLower) ||
      /^(guess|yet|chess|yea|yah|yess)\b/i.test(userCommandLower) ||
      userCommandLower.includes(pending.title.toLowerCase().slice(0, 5))
    );

    if (isApproval && pending) {
      ClaudeMusicReasoningProvider.pendingProposedTrack = null;
      eventStore.recordAgentTrackAction(pending.title);
      const artistPart = pending.artist && pending.artist !== 'Unknown Artist' ? ` by ${pending.artist}` : '';
      const tts = `Awesome, spinning "${pending.title}"${artistPart} for you right now.`;
      this.rememberTurn(userCommandRaw, tts);

      console.log(`[Claude Provider] ✅ USER APPROVED PROPOSED TRACK: Playing "${pending.title}"${artistPart}`);
      return {
        calculated_satiety_percentage: 22,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Approved "${pending.title}" — launching peak dopamine playback.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: pending.trackId || '',
          target_player: pending.source || targetPlayerDefault,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: pending.source || targetPlayerDefault,
          search_query: `${pending.title} ${pending.artist !== 'Unknown Artist' ? pending.artist : ''}`.trim(),
          track_title: pending.title,
          track_artist: pending.artist,
          spotify_uri: pending.trackId || '',
          suggested_bpm_target: 108
        },
        database_tags: ['instant-dialog-approval', 'background-play']
      };
    }

    if (pending) {
      eventStore.recordAgentTrackAction(pending.title);
      ClaudeMusicReasoningProvider.pendingProposedTrack = null;
    }

    // ─── 4. MERGE REAL LISTENING HISTORY: LIVE CLIENT TELEMETRY + EVENTSTORE ──
    const historyMap = new Map<string, any>();

    if (Array.isArray(context.rolling_history)) {
      for (const t of context.rolling_history) {
        if (t.title && t.title !== 'Active Session' && t.title !== 'Standing By' && t.title !== 'Deep Flow State') {
          const key = `${t.title.toLowerCase().trim()} - ${(t.artist || '').toLowerCase().trim()}`;
          if (!historyMap.has(key)) {
            historyMap.set(key, {
              title: t.title.trim(),
              artist: (t.artist || 'Unknown Artist').trim(),
              trackId: t.trackId || undefined,
              source: (t as any).source_player || 'com.spotify.music',
              playCount: t.playCount || 1
            });
          }
        }
      }
    }

    try {
      const storedTracks = eventStore.getRecentObservedTracks(50);
      for (const t of storedTracks) {
        if (t.title) {
          const key = `${t.title.toLowerCase().trim()} - ${(t.artist || '').toLowerCase().trim()}`;
          if (!historyMap.has(key)) {
            historyMap.set(key, t);
          } else {
            const existing = historyMap.get(key);
            if (!existing.trackId && t.trackId) {
              existing.trackId = t.trackId;
            }
          }
        }
      }
    } catch (e) {
      console.warn('[Claude Provider] Could not load EventStore history:', e);
    }

    const allUserTracks = Array.from(historyMap.values());

    // ─── 5. PARTITION FRESH vs. OVERPLAYED & COMPACT DOPAMINE CAPSULE ─────────
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

    let freshTracks = allUserTracks.filter(t => !excludedSet.has(t.title.toLowerCase().trim()) && (t.playCount || 1) <= 4);
    if (freshTracks.length === 0) {
      freshTracks = allUserTracks.filter(t => !excludedSet.has(t.title.toLowerCase().trim()));
    }
    if (freshTracks.length === 0 && allUserTracks.length > 0) {
      const curTitle = (context.current_track?.title || '').toLowerCase().trim();
      freshTracks = allUserTracks.filter(t => t.title.toLowerCase().trim() !== curTitle);
      if (freshTracks.length === 0) freshTracks = [...allUserTracks];
    }

    if (freshTracks.length > 1) {
      const offset = ClaudeMusicReasoningProvider.rotationIndex % freshTracks.length;
      ClaudeMusicReasoningProvider.rotationIndex++;
      freshTracks = [...freshTracks.slice(offset), ...freshTracks.slice(0, offset)];
    }

    const overplayedTracks = allUserTracks
      .filter(t => (t.playCount || 1) >= 4)
      .sort((a, b) => (b.playCount || 1) - (a.playCount || 1))
      .slice(0, 8);

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

    // Check if the user explicitly named a specific song or artist to play right now!
    // e.g., "play Utaniinua", "play Wakadinali", "play Mbosso", vs generic "play me a song", "play something"
    const genericPlayPattern = /^\s*(please\s+)?(play|spin|put on|give me|suggest|recommend|find)(\s+me)?\s+(a|some|any|another|different|fresh|good|nice|new)?\s*(music|song|songs|track|vibe|tune|something|anything|bongo|afrobeats|hip hop|gospel)?\s*(for me|please|right now|now)?\s*$/i;
    const startsWithPlayVerb = /^\s*(please\s+)?(play|spin|put on)\s+(.+)$/i.exec(userCommandRaw);
    const explicitlyNamedQuery = (!genericPlayPattern.test(userCommandRaw) && startsWithPlayVerb)
      ? startsWithPlayVerb[3].replace(/\b(right now|now|for me|please|on spotify)\b/gi, '').trim()
      : null;

    // Also check if userCommand mentions an exact track title from their library alongside "play"
    const matchedExplicitLibraryTrack = explicitlyNamedQuery
      ? allUserTracks.find(t =>
          t.title.toLowerCase() === explicitlyNamedQuery.toLowerCase() ||
          explicitlyNamedQuery.toLowerCase().includes(t.title.toLowerCase()) ||
          t.title.toLowerCase().includes(explicitlyNamedQuery.toLowerCase()) ||
          (t.artist && t.artist !== 'Unknown Artist' && explicitlyNamedQuery.toLowerCase().includes(t.artist.toLowerCase()))
        )
      : null;

    const claudePayload = {
      circadian_daypart: context.circadian_daypart,
      current_track: context.current_track,
      user_command: userCommandRaw,
      explicit_named_play_request: explicitlyNamedQuery || null,
      matched_library_track_for_explicit_request: matchedExplicitLibraryTrack || null,
      fresh_unplayed_user_tracks: freshTracks.slice(0, 20),
      overplayed_high_satiety_tracks: overplayedTracks,
      vault_tracks: context.vault_tracks || [],
      dopamine_capsule: compactedCapsule,
      hearing_telemetry: context.hearing_telemetry || {
        estimated_db: 68,
        safe_headroom_db: 17,
        daily_exposure_minutes: 45,
        output_device: 'Phone / Wireless Audio',
        safety_guidance: 'Listening exposure within safe acoustic threshold (<80 dB).'
      },
      recently_suggested_or_played_titles: Array.from(excludedSet).slice(0, 15),
      recent_dialogue_turns: ClaudeMusicReasoningProvider.recentDialogueTurns.slice(-8),
      current_satiety_percentage: context.current_satiety_percentage
    };

    const available = await this.isAvailable();
    if (!available) {
      return this.buildSmartHistoryFallbackResponse(
        context,
        freshTracks,
        allUserTracks,
        overplayedTracks,
        compactedCapsule,
        explicitlyNamedQuery,
        matchedExplicitLibraryTrack,
        eventStore
      );
    }

    console.log('[Claude Provider] ⚡ Dispatching full telemetry & tool-calling reasoning to Claude...');

    try {
      const targetModel = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
      const systemPrompt = this.getSystemPrompt();
      let response;
      try {
        response = await this.anthropic.messages.create({
          model: targetModel,
          max_tokens: 1400,
          system: systemPrompt,
          messages: [{ role: 'user', content: JSON.stringify(claudePayload) }]
        });
        this.activeModelUsed = targetModel;
      } catch (modelErr: any) {
        const errMsg = String(modelErr?.message || modelErr);
        if (errMsg.includes('not_found_error') || errMsg.includes('404')) {
          try {
            response = await this.anthropic.messages.create({
              model: 'claude-3-5-haiku-20241022',
              max_tokens: 1000,
              system: systemPrompt,
              messages: [{ role: 'user', content: JSON.stringify(claudePayload) }]
            });
            this.activeModelUsed = 'claude-3-5-haiku-20241022';
          } catch {
            response = await this.anthropic.messages.create({
              model: 'claude-3-haiku-20240307',
              max_tokens: 1000,
              system: systemPrompt,
              messages: [{ role: 'user', content: JSON.stringify(claudePayload) }]
            });
            this.activeModelUsed = 'claude-3-haiku-20240307';
          }
        } else {
          throw modelErr;
        }
      }

      const rawText = this.extractRawText(response?.content);
      if (!rawText || rawText.trim() === '') {
        throw new Error('Claude returned an empty response.');
      }

      const jsonMatch = rawText.match(/\{[\s\S]*\}/);
      const cleanJson = jsonMatch ? jsonMatch[0] : rawText;
      const parsed = JSON.parse(cleanJson);

      let ttsText = String(
        parsed.voice_agent_tts ??
        parsed.agent_tts_response ??
        parsed.reply ??
        ''
      ).trim();

      const toolCallRaw = parsed.agent_tool_call || {};
      let action: AgentToolCall['action'] = toolCallRaw.action || 'ADVISE_ONLY';

      // Enforce user preference: if user explicitly named a specific song/artist ("Play [Name]"), execute PLAY_TRACK_NOW immediately
      if (explicitlyNamedQuery && (action === 'PROPOSE_TRACK' || action === 'ADVISE_ONLY')) {
        action = 'PLAY_TRACK_NOW';
      }

      const rawSearch = parsed.os_execution_command?.search_query || toolCallRaw.track_title || explicitlyNamedQuery || '';
      let trackTitle = toolCallRaw.track_title || parsed.os_execution_command?.track_title || parsed.track_title || '';
      let trackArtist = toolCallRaw.track_artist || parsed.os_execution_command?.track_artist || parsed.track_artist || '';
      let spotifyUri = toolCallRaw.spotify_uri || parsed.os_execution_command?.spotify_uri || parsed.spotify_uri || '';

      if (spotifyUri && (spotifyUri.includes('Optional') || spotifyUri.includes('if available') || spotifyUri === 'none' || spotifyUri === 'null')) {
        spotifyUri = '';
      }

      if (explicitlyNamedQuery && matchedExplicitLibraryTrack && !trackTitle) {
        trackTitle = matchedExplicitLibraryTrack.title;
        trackArtist = matchedExplicitLibraryTrack.artist;
        spotifyUri = matchedExplicitLibraryTrack.trackId || '';
      } else if (explicitlyNamedQuery && !trackTitle) {
        trackTitle = explicitlyNamedQuery;
      }

      // If proposing a track or launching capsule and Claude didn't populate trackTitle, bind from capsule/freshTracks
      if ((action === 'PROPOSE_TRACK' || action === 'LAUNCH_DOPAMINE_CAPSULE') && !trackTitle) {
        const chosen = action === 'LAUNCH_DOPAMINE_CAPSULE' && compactedCapsule[0]
          ? compactedCapsule[0]
          : (freshTracks[0] || allUserTracks[0]);
        if (chosen) {
          trackTitle = chosen.title;
          trackArtist = chosen.artist;
          if (chosen.trackId) spotifyUri = chosen.trackId;
        }
      }

      // Resolve Spotify URI if we are playing or proposing a track
      if (!spotifyUri && trackTitle && (action === 'PLAY_TRACK_NOW' || action === 'PROPOSE_TRACK' || action === 'LAUNCH_DOPAMINE_CAPSULE')) {
        const titleLower = trackTitle.toLowerCase().trim();
        const matchedHistoryTrack = allUserTracks.find(
          h => h.title && (
            h.title.toLowerCase().trim() === titleLower ||
            titleLower.includes(h.title.toLowerCase().trim()) ||
            h.title.toLowerCase().trim().includes(titleLower)
          )
        );
        if (matchedHistoryTrack?.trackId) {
          const tid = String(matchedHistoryTrack.trackId).trim();
          if (tid.startsWith('spotify:track:') || tid.startsWith('https://open.spotify.com/track/')) {
            spotifyUri = tid;
          } else if (/^[A-Za-z0-9]{22}$/.test(tid)) {
            spotifyUri = `spotify:track:${tid}`;
          }
          if (!trackArtist || trackArtist === 'Unknown Artist') {
            trackArtist = matchedHistoryTrack.artist || '';
          }
        }
      }

      if (!spotifyUri && trackTitle && (action === 'PLAY_TRACK_NOW' || action === 'PROPOSE_TRACK' || action === 'LAUNCH_DOPAMINE_CAPSULE')) {
        const cleanArtist = trackArtist && trackArtist !== 'Unknown Artist' ? trackArtist : undefined;
        const resolved = await this.lookupSpotifyTrack(trackTitle, cleanArtist);
        if (resolved) {
          spotifyUri = resolved.uri;
          if (explicitlyNamedQuery) {
            trackTitle = resolved.title;
          }
          if (!trackArtist || trackArtist === 'Unknown Artist') {
            trackArtist = resolved.artist;
          }
        }
      }

      const targetPlayer = toolCallRaw.target_player || parsed.os_execution_command?.target_player || targetPlayerDefault;

      // Map AgentToolCall action to OsExecutionCommand intent
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
              title: trackTitle,
              artist: trackArtist,
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
          ? `Playing "${trackTitle}"${trackArtist ? ` by ${trackArtist}` : ''} right now.`
          : action === 'PAUSE_PLAYBACK'
          ? 'Paused your playback.'
          : 'I am synced to your listening telemetry. Tell me what you want to explore next.';
      }

      const expectFollowUp = Boolean(
        action === 'PROPOSE_TRACK' ||
        action === 'LAUNCH_DOPAMINE_CAPSULE' ||
        (action === 'ADVISE_ONLY' && (toolCallRaw.expect_follow_up || ttsText.includes('?')))
      );

      this.rememberTurn(userCommandRaw, ttsText);

      const finalToolCall: AgentToolCall = {
        action,
        track_title: trackTitle || undefined,
        track_artist: trackArtist || undefined,
        spotify_uri: spotifyUri || undefined,
        target_player: targetPlayer,
        volume_percent: typeof toolCallRaw.volume_percent === 'number' ? toolCallRaw.volume_percent : undefined,
        palette_id: toolCallRaw.palette_id || undefined,
        target_screen: toolCallRaw.target_screen || undefined,
        expect_follow_up: expectFollowUp
      };

      return {
        calculated_satiety_percentage: Number(
          parsed.calculated_satiety_percentage ??
          context.current_satiety_percentage ??
          32
        ),
        reasoning_mode: parsed.reasoning_mode || (action === 'ADVISE_ONLY' ? 'JOURNEY_ADVISOR' : 'SONIC_CURATOR'),
        flow_state_analysis: String(
          parsed.flow_state_analysis ??
          (action === 'ADVISE_ONLY'
            ? 'Psychoacoustic Journey & Telemetry Advisor active.'
            : `Studio Action: ${action}`)
        ),
        voice_agent_tts: ttsText,
        agent_tool_call: finalToolCall,
        os_execution_command: {
          intent: resolvedIntent,
          target_player: targetPlayer,
          search_query: `${trackTitle} ${trackArtist && trackArtist !== 'Unknown Artist' ? trackArtist : ''}`.trim() || rawSearch,
          track_title: trackTitle,
          track_artist: trackArtist,
          spotify_uri: spotifyUri,
          suggested_bpm_target: Number(parsed.os_execution_command?.suggested_bpm_target || 108)
        },
        database_tags: Array.isArray(parsed.database_tags)
          ? parsed.database_tags
          : ['autonomous-agent', action.toLowerCase()]
      };
    } catch (error: any) {
      console.error('[Claude Provider] API Call failed, using smart telemetry fallback:', error?.message || error);
      const msg = String(error?.message || error);
      if (msg.includes('401') || msg.includes('auth') || msg.includes('api_key')) {
        this.hasInvalidKey = true;
      }
      return this.buildSmartHistoryFallbackResponse(
        context,
        freshTracks,
        allUserTracks,
        overplayedTracks,
        compactedCapsule,
        explicitlyNamedQuery,
        matchedExplicitLibraryTrack,
        eventStore
      );
    }
  }

  private buildSmartHistoryFallbackResponse(
    context: TuneMindTelemetryContext,
    freshTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    allUserTracks: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>,
    overplayedTracks: Array<{ title: string; artist: string; playCount?: number }>,
    compactedCapsule: Array<{ title: string; artist: string; trackId?: string; freshness_percent: number; reason: string }>,
    explicitlyNamedQuery: string | null,
    matchedExplicitLibraryTrack: any | null,
    eventStore: EventStore
  ): TuneMindReasoningResponse {
    const userCommandLower = (context.user_command || '').toLowerCase();
    const targetPlayer = context.current_track?.source_player || 'com.spotify.music';

    // 1. Volume / Hearing Health commands
    if (/\b(volume|louder|quieter|lower|turn down|turn up|ear|hearing|decibel|db|loud)\b/i.test(userCommandLower)) {
      const wantsLower = /\b(lower|down|quieter|soft|protect|safe|ear|hearing|loud)\b/i.test(userCommandLower);
      const targetVol = wantsLower ? 45 : 75;
      const estDb = context.hearing_telemetry?.estimated_db ?? 68;
      const headroom = context.hearing_telemetry?.safe_headroom_db ?? 17;
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
    if (/\b(vault|quarantine|lock|cooldown|cool down|rest this song)\b/i.test(userCommandLower)) {
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

    // 5. Dopamine Capsule or Listening Journey / Pattern Advice
    if (/\b(capsule|stored for me|prepared for me|morning|journey|pattern|habit|advice|advise|analyze|overplay|fatigue|how am i listening)\b/i.test(userCommandLower)) {
      const topFresh = compactedCapsule[0] || freshTracks[0];
      const topOverplayed = overplayedTracks[0];
      if (topFresh) {
        ClaudeMusicReasoningProvider.pendingProposedTrack = {
          title: topFresh.title,
          artist: topFresh.artist,
          trackId: topFresh.trackId,
          source: targetPlayer
        };
      }
      const overplayedNote = topOverplayed
        ? `You have been looping "${topOverplayed.title}" heavily (${topOverplayed.playCount} plays), which builds receptor fatigue. `
        : '';
      const capsuleNote = topFresh
        ? `For your ${context.circadian_daypart} flow, I compacted "${topFresh.title}" by ${topFresh.artist} at peak freshness. Want me to spin it for you right now?`
        : `Your auditory receptivity is balanced across ${allUserTracks.length} tracked songs.`;

      return {
        calculated_satiety_percentage: 30,
        reasoning_mode: 'DOPAMINE_CAPSULE',
        flow_state_analysis: `Compacted ${compactedCapsule.length} peak-dopamine tracks for ${context.circadian_daypart} flow.`,
        voice_agent_tts: `${overplayedNote}${capsuleNote}`,
        agent_tool_call: {
          action: topFresh ? 'LAUNCH_DOPAMINE_CAPSULE' : 'ADVISE_ONLY',
          track_title: topFresh?.title,
          track_artist: topFresh?.artist,
          spotify_uri: topFresh?.trackId,
          target_player: targetPlayer,
          expect_follow_up: Boolean(topFresh)
        },
        os_execution_command: {
          intent: 'NONE',
          target_player: targetPlayer,
          search_query: topFresh ? `${topFresh.title} ${topFresh.artist}`.trim() : '',
          track_title: topFresh?.title,
          track_artist: topFresh?.artist,
          spotify_uri: topFresh?.trackId || '',
          suggested_bpm_target: 108
        },
        database_tags: ['dopamine-capsule', 'journey-advisor']
      };
    }

    // 6. Direct Named Song Request -> Play Immediately!
    if (explicitlyNamedQuery) {
      const chosen = matchedExplicitLibraryTrack || {
        title: explicitlyNamedQuery,
        artist: '',
        trackId: '',
        source: targetPlayer
      };
      const artistPart = chosen.artist && chosen.artist !== 'Unknown Artist' ? ` by ${chosen.artist}` : '';
      const tts = `Playing "${chosen.title}"${artistPart} for you right now.`;
      eventStore.recordAgentTrackAction(chosen.title);
      return {
        calculated_satiety_percentage: 20,
        reasoning_mode: 'DIRECT_EXECUTION',
        flow_state_analysis: `Direct launch: spinning "${chosen.title}"${artistPart}.`,
        voice_agent_tts: tts,
        agent_tool_call: {
          action: 'PLAY_TRACK_NOW',
          track_title: chosen.title,
          track_artist: chosen.artist,
          spotify_uri: chosen.trackId || '',
          target_player: chosen.source || targetPlayer,
          expect_follow_up: false
        },
        os_execution_command: {
          intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
          target_player: chosen.source || targetPlayer,
          search_query: `${chosen.title} ${chosen.artist || ''}`.trim(),
          track_title: chosen.title,
          track_artist: chosen.artist,
          spotify_uri: chosen.trackId || '',
          suggested_bpm_target: 108
        },
        database_tags: ['direct-named-play']
      };
    }

    // 7. Consultative Song Recommendation -> Propose & Wait for Approval
    const pool = freshTracks.length > 0 ? freshTracks : allUserTracks;
    const chosen = pool[0] || {
      title: 'Utaniinua',
      artist: 'Paul Clement',
      trackId: undefined,
      source: targetPlayer
    };

    let spotifyUri = '';
    if (chosen.trackId) {
      const tid = String(chosen.trackId).trim();
      if (tid.startsWith('spotify:track:') || tid.startsWith('https://open.spotify.com/track/')) {
        spotifyUri = tid;
      } else if (/^[A-Za-z0-9]{22}$/.test(tid)) {
        spotifyUri = `spotify:track:${tid}`;
      }
    }

    ClaudeMusicReasoningProvider.pendingProposedTrack = {
      title: chosen.title,
      artist: chosen.artist,
      trackId: spotifyUri || undefined,
      source: chosen.source || targetPlayer
    };
    eventStore.recordAgentTrackAction(chosen.title);

    const artistPart = chosen.artist && chosen.artist !== 'Unknown Artist' ? ` by ${chosen.artist}` : '';
    const tts = `"${chosen.title}"${artistPart} is sitting at peak dopamine freshness for your ${context.circadian_daypart.toLowerCase()} flow. Should I spin it for you right now?`;

    return {
      calculated_satiety_percentage: 32,
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
      database_tags: ['smart-history-proposal', 'awaiting-approval']
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
      const targetModel = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
      const response = await this.anthropic.messages.create({
        model: targetModel,
        max_tokens: 800,
        system: this.getSystemPrompt(),
        messages: [{ role: 'user', content: prompt }]
      });

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
        modelUsed: targetModel
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

        const targetModel = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
        const res = await this.anthropic.messages.create({
          model: targetModel,
          max_tokens: 600,
          system: this.getSystemPrompt(),
          messages: [{ role: 'user', content: prompt }]
        });

        const raw = this.extractRawText(res?.content);
        if (!raw || !raw.trim()) {
          throw new Error('Claude returned an empty response.');
        }
        const match = raw.match(/\{[\s\S]*\}/);
        const parsed = JSON.parse(match ? match[0] : raw);
        return {
          status: 'ok',
          isAiGenerated: true,
          modelUsed: targetModel,
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

        const targetModel = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
        const res = await this.anthropic.messages.create({
          model: targetModel,
          max_tokens: 1000,
          system: this.getSystemPrompt(),
          messages: [{ role: 'user', content: prompt }]
        });

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
            modelUsed: targetModel,
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
   * Conversational music agent endpoint powered by Claude Haiku.
   */
  async conversationalMusicAgent(query: string, tracks: any[], currentPlaying?: any, userName?: string): Promise<any> {
    const available = await this.isAvailable();
    if (available) {
      try {
        const prompt = `User Query: "${query}". Listener: ${userName || 'User'}. Current Playing: ${JSON.stringify(currentPlaying || {})}.
Recent Catalog: ${tracks.slice(0, 15).map(t => `${t.title} by ${t.artist}`).join(', ')}.
Respond with conversational audiophile assistance in JSON:
{"reply": "short audiophile advice", "suggestedAction": "NEXT_TRACK" | "PLAY" | "PAUSE" | "NONE", "recommendedTrack": {"title": "Title", "artist": "Artist"}}`;

        const targetModel = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';
        const res = await this.anthropic.messages.create({
          model: targetModel,
          max_tokens: 400,
          system: this.getSystemPrompt(),
          messages: [{ role: 'user', content: prompt }]
        });

        const raw = this.extractRawText(res?.content);
        if (!raw || !raw.trim()) {
          throw new Error('Claude returned an empty response.');
        }
        const match = raw.match(/\{[\s\S]*\}/);
        return JSON.parse(match ? match[0] : raw);
      } catch (err) {
        console.warn('[Claude Provider] Agent chat fallback applied:', err);
      }
    }

    return {
      reply: `I've analyzed your acoustic telemetry. Transitioning to a fresh timbre to protect auditory clarity.`,
      suggestedAction: 'NONE',
      recommendedTrack: null
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
