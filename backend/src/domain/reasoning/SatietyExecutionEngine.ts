import Anthropic from '@anthropic-ai/sdk';

// ============================================================================
// 1. INPUT & OUTPUT SCHEMAS FOR THE SATIETY REASONING ENGINE
// ============================================================================

export interface TrackHistoryTelemetryItem {
  track_id: string;
  spotify_uri?: string;
  track_title: string;
  artist_name: string;
  album_name?: string;
  genre?: string;
  duration_ms: number;
  played_at_epoch_ms: number;
  listened_duration_ms?: number;
  skip_duration_ms?: number; // If skipped early, how many ms into the track the user skipped
  was_skipped?: boolean;
}

export interface SatietyEvaluationRequest {
  user_id?: string;
  circadian_daypart?: string;
  current_track?: {
    track_id?: string;
    spotify_uri?: string;
    track_title: string;
    artist_name: string;
    genre?: string;
  };
  tracks: TrackHistoryTelemetryItem[]; // Last 50 tracks from Android Room DB
}

export interface SpotifyPlaybackToolArgs {
  command: 'skip_track' | 'play_playlist' | 'change_genre' | 'pause' | 'resume' | 'maintain';
  target_uri?: string;
  search_query?: string;
  target_genre?: string;
  rationale?: string;
}

export interface NotificationToolArgs {
  orb_state: 'FATIGUE_ALERT' | 'FLOW_LOCKED' | 'SPEAKING' | 'IDLE';
  headline: string;
  subtext: string;
  satiety_score: number;
  show_heads_up_banner: boolean;
}

export interface AgentToolInvocation {
  tool_name: 'SpotifyPlaybackTool' | 'NotificationTool';
  arguments: SpotifyPlaybackToolArgs | NotificationToolArgs;
}

export interface SatietyDecisionOutput {
  state: 'fatigued' | 'focused';
  action: 'change_genre' | 'skip_track' | 'maintain';
  satiety_score: number;
  reasoning: string;
  recommended_uri?: string;
  recommended_query?: string;
  target_genre?: string;
  tool_calls: AgentToolInvocation[];
  telemetry_metrics: {
    evaluated_track_count: number;
    early_skip_ratio: number;
    consecutive_skip_streak: number;
    artist_repetition_ratio: number;
    dominant_artist: string;
  };
}

// ============================================================================
// 2. ANTHROPIC CLAUDE TOOL-CALLING JSON SCHEMA REGISTRY
// ============================================================================

export const AGENTIC_TOOL_DEFINITIONS: Anthropic.Tool[] = [
  {
    name: 'SpotifyPlaybackTool',
    description:
      'Executes OS-level Spotify playback interventions on the Android device (via Spotify App Remote SDK / MediaSession / Deep-Link Intent) when listener satiety or acoustic fatigue is detected.',
    input_schema: {
      type: 'object',
      properties: {
        command: {
          type: 'string',
          enum: ['skip_track', 'play_playlist', 'change_genre', 'pause', 'resume', 'maintain'],
          description:
            'The playback command to execute on the Android device without requiring user interaction.'
        },
        target_uri: {
          type: 'string',
          description:
            'Optional Spotify URI to launch (e.g. "spotify:track:..." or "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO").'
        },
        search_query: {
          type: 'string',
          description:
            'Clean track/artist or genre-reset playlist query for MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.'
        },
        target_genre: {
          type: 'string',
          description: 'Target acoustic genre when shifting away from a fatigued genre.'
        },
        rationale: {
          type: 'string',
          description: 'Concise psychoacoustic rationale for triggering this playback command.'
        }
      },
      required: ['command', 'rationale']
    }
  },
  {
    name: 'NotificationTool',
    description:
      'Triggers the TuneMind Android Orb UI state and updates the OS Foreground Service notification banner.',
    input_schema: {
      type: 'object',
      properties: {
        orb_state: {
          type: 'string',
          enum: ['FATIGUE_ALERT', 'FLOW_LOCKED', 'SPEAKING', 'IDLE'],
          description: 'Visual glow and animation state of the TuneMind Android Studio Orb.'
        },
        headline: {
          type: 'string',
          description: 'Short notification/orb headline (max 45 chars).'
        },
        subtext: {
          type: 'string',
          description: '1-sentence explanation displayed in the Orb telemetry bar and notification.'
        },
        satiety_score: {
          type: 'number',
          description: 'Calculated listener satiety percentage (0 to 100).'
        },
        show_heads_up_banner: {
          type: 'boolean',
          description: 'True when an automatic genre shift or track skip is being executed.'
        }
      },
      required: ['orb_state', 'headline', 'subtext', 'satiety_score', 'show_heads_up_banner']
    }
  },
  {
    name: 'EmitSatietyDecision',
    description:
      'Outputs the structured root decision node for the Reasoning and Execution Branch.',
    input_schema: {
      type: 'object',
      properties: {
        state: {
          type: 'string',
          enum: ['fatigued', 'focused'],
          description: 'Whether the listener is experiencing acoustic receptor fatigue or focused flow.'
        },
        action: {
          type: 'string',
          enum: ['change_genre', 'skip_track', 'maintain'],
          description: 'High-level intervention action selected by the reasoning tree.'
        },
        satiety_score: {
          type: 'number',
          description: 'Listener satiety score from 0 (completely fresh) to 100 (severe burnout).'
        },
        reasoning: {
          type: 'string',
          description:
            'First-principles neuro-acoustic reasoning referencing the 50-track timestamps, skip durations, and repetition density.'
        },
        recommended_uri: {
          type: 'string',
          description: 'Spotify URI of the fresh track or palate-cleansing playlist to play if fatigued.'
        },
        recommended_query: {
          type: 'string',
          description: 'Clean Spotify voice search query to auto-play if fatigued.'
        },
        target_genre: {
          type: 'string',
          description: 'Recommended genre shift if action is change_genre.'
        }
      },
      required: ['state', 'action', 'satiety_score', 'reasoning']
    }
  }
];

// ============================================================================
// 3. SATIETY REASONING ENGINE (CLAUDE 3.5 HAIKU / SONNET + HEURISTIC TREE)
// ============================================================================

export class SatietyExecutionEngine {
  private anthropic: Anthropic;

  constructor() {
    this.anthropic = new Anthropic();
  }

  /**
   * Computes deterministic acoustic fatigue metrics from the last 50 Room DB tracks
   * before invoking Claude 3.5 Haiku's tool-calling reasoning tree.
   */
  public computeTelemetryMetrics(tracks: TrackHistoryTelemetryItem[]) {
    const window = tracks.slice(0, 50);
    if (window.length === 0) {
      return {
        evaluated_track_count: 0,
        early_skip_ratio: 0,
        consecutive_skip_streak: 0,
        artist_repetition_ratio: 0,
        dominant_artist: 'None',
        fresh_candidate: null as TrackHistoryTelemetryItem | null
      };
    }

    let earlySkipCount = 0;
    let consecutiveSkipStreak = 0;
    let streakBroken = false;
    const artistCounts = new Map<string, number>();
    const trackCounts = new Map<string, number>();

    for (const item of window) {
      const duration = item.duration_ms || 180_000;
      const skipMs = item.skip_duration_ms ?? item.listened_duration_ms;
      const isEarlySkip =
        item.was_skipped === true ||
        (typeof skipMs === 'number' && skipMs > 0 && skipMs < Math.min(35_000, duration * 0.25));

      if (isEarlySkip) {
        earlySkipCount++;
        if (!streakBroken) consecutiveSkipStreak++;
      } else {
        streakBroken = true;
      }

      const artistKey = (item.artist_name || 'Unknown Artist').trim();
      artistCounts.set(artistKey, (artistCounts.get(artistKey) || 0) + 1);

      const titleKey = (item.track_title || '').trim().toLowerCase();
      if (titleKey) {
        trackCounts.set(titleKey, (trackCounts.get(titleKey) || 0) + 1);
      }
    }

    let dominantArtist = 'None';
    let maxArtistPlays = 0;
    for (const [artist, count] of artistCounts.entries()) {
      if (count > maxArtistPlays) {
        maxArtistPlays = count;
        dominantArtist = artist;
      }
    }

    const earlySkipRatio = Number((earlySkipCount / window.length).toFixed(2));
    const artistRepetitionRatio = Number((maxArtistPlays / window.length).toFixed(2));

    // Find a low-play-count track from a non-dominant artist as a fresh recovery candidate
    const freshCandidate =
      window
        .slice()
        .reverse()
        .find(
          t =>
            t.artist_name !== dominantArtist &&
            (trackCounts.get((t.track_title || '').trim().toLowerCase()) || 1) <= 1
        ) || window[window.length - 1];

    return {
      evaluated_track_count: window.length,
      early_skip_ratio: earlySkipRatio,
      consecutive_skip_streak: consecutiveSkipStreak,
      artist_repetition_ratio: artistRepetitionRatio,
      dominant_artist: dominantArtist,
      fresh_candidate: freshCandidate
    };
  }

  private buildSystemPrompt(): string {
    return `You are TuneMind's Autonomous Satiety Reasoning & Execution Engine.
You analyze a listener's last 50 tracks from their local Android Room Database (including timestamps, track durations, and skip durations) and trigger OS-level execution tools.

DECISION TREE RULES:
1. DETECT FATIGUE ("state": "fatigued"):
   - Triggered when ANY of the following hold:
     a) Early-skip ratio >= 0.30 (user is skipping tracks within the first 30 seconds).
     b) Consecutive skip streak >= 2 on the most recent tracks.
     c) Artist or track repetition ratio >= 0.35 (heavy looping causing dopaminergic receptor habituation).
   - Action Selection when "fatigued":
     * Choose "action": "change_genre" if artist/genre repetition is high (>= 0.35) or early-skip ratio >= 0.40.
     * Choose "action": "skip_track" if the user is in an acute skip streak (>= 2 consecutive early skips).
2. DETECT FOCUSED FLOW ("state": "focused"):
   - Triggered when completion durations are healthy, early-skip ratio < 0.30, and variety is balanced.
   - Choose "action": "maintain".

MANDATORY TOOL INVOCATION:
You MUST invoke all three tools in your response:
1. 'EmitSatietyDecision': Output the structured {"state": "fatigued|focused", "action": "change_genre|skip_track|maintain", "satiety_score": 0..100, "reasoning": "..."} decision.
2. 'SpotifyPlaybackTool':
   - If "action" == "skip_track", set command="skip_track" (or "play_playlist" with a fresh track/URI).
   - If "action" == "change_genre", set command="change_genre" and provide a fresh 'search_query' and 'target_uri' from the listener's low-satiety tracks or a palate-cleansing Spotify playlist URI.
   - If "action" == "maintain", set command="maintain".
3. 'NotificationTool':
   - Update the TuneMind Android Orb UI ('orb_state': "FATIGUE_ALERT" if fatigued else "FLOW_LOCKED").`;
  }

  public async evaluateLast50Tracks(
    request: SatietyEvaluationRequest
  ): Promise<SatietyDecisionOutput> {
    const last50 = (request.tracks || []).slice(0, 50);
    const metrics = this.computeTelemetryMetrics(last50);

    const compactTracks = last50.map((t, idx) => ({
      index: idx + 1,
      title: t.track_title,
      artist: t.artist_name,
      uri: t.spotify_uri || `spotify:track:${t.track_id}`,
      played_at_ms: t.played_at_epoch_ms,
      duration_ms: t.duration_ms,
      skip_duration_ms: t.skip_duration_ms ?? null,
      was_skipped: Boolean(t.was_skipped)
    }));

    const userPrompt = JSON.stringify({
      circadian_daypart: request.circadian_daypart || 'Active Session',
      current_track: request.current_track || compactTracks[0] || null,
      computed_telemetry_metrics: {
        evaluated_track_count: metrics.evaluated_track_count,
        early_skip_ratio: metrics.early_skip_ratio,
        consecutive_skip_streak: metrics.consecutive_skip_streak,
        artist_repetition_ratio: metrics.artist_repetition_ratio,
        dominant_artist: metrics.dominant_artist
      },
      last_50_tracks: compactTracks
    });

    const apiKey = (process.env.ANTHROPIC_API_KEY || '').trim();
    if (apiKey && apiKey !== 'your_key_here') {
      try {
        const modelsToTry = [
          'claude-3-5-haiku-20241022',
          'claude-sonnet-4-20250514',
          'claude-3-7-sonnet-20250219',
          'claude-3-haiku-20240307'
        ];

        let response: Anthropic.Message | null = null;
        for (const modelId of modelsToTry) {
          try {
            response = await this.anthropic.messages.create({
              model: modelId,
              max_tokens: 1024,
              system: this.buildSystemPrompt(),
              tools: AGENTIC_TOOL_DEFINITIONS,
              tool_choice: { type: 'any' },
              messages: [{ role: 'user', content: userPrompt }]
            });
            break;
          } catch (modelErr: any) {
            const msg = String(modelErr?.message || modelErr);
            if (msg.includes('404') || msg.includes('not_found')) continue;
            throw modelErr;
          }
        }

        if (response && Array.isArray(response.content)) {
          let decisionBlock: any = null;
          let playbackToolCall: SpotifyPlaybackToolArgs | null = null;
          let notificationToolCall: NotificationToolArgs | null = null;

          for (const block of response.content) {
            if (block.type === 'tool_use') {
              if (block.name === 'EmitSatietyDecision') {
                decisionBlock = block.input;
              } else if (block.name === 'SpotifyPlaybackTool') {
                playbackToolCall = block.input as SpotifyPlaybackToolArgs;
              } else if (block.name === 'NotificationTool') {
                notificationToolCall = block.input as NotificationToolArgs;
              }
            }
          }

          if (decisionBlock || playbackToolCall) {
            const state: 'fatigued' | 'focused' =
              decisionBlock?.state === 'fatigued' ||
              playbackToolCall?.command === 'skip_track' ||
              playbackToolCall?.command === 'change_genre'
                ? 'fatigued'
                : 'focused';

            const action: 'change_genre' | 'skip_track' | 'maintain' =
              decisionBlock?.action ||
              (playbackToolCall?.command === 'change_genre'
                ? 'change_genre'
                : playbackToolCall?.command === 'skip_track'
                ? 'skip_track'
                : 'maintain');

            const satietyScore = Number(
              decisionBlock?.satiety_score ??
                notificationToolCall?.satiety_score ??
                (state === 'fatigued' ? 78 : 28)
            );

            const reasoning = String(
              decisionBlock?.reasoning ||
                playbackToolCall?.rationale ||
                notificationToolCall?.subtext ||
                'Evaluated 50-track local Room listening history for auditory receptor saturation.'
            );

            const fallbackQuery = metrics.fresh_candidate
              ? `${metrics.fresh_candidate.track_title} ${metrics.fresh_candidate.artist_name}`.trim()
              : 'Afro Acoustic Chill';

            const finalPlaybackArgs: SpotifyPlaybackToolArgs = playbackToolCall || {
              command: action === 'maintain' ? 'maintain' : action,
              target_uri:
                decisionBlock?.recommended_uri ||
                metrics.fresh_candidate?.spotify_uri ||
                'spotify:playlist:37i9dQZF1DX4sWSpwq3LiO',
              search_query: decisionBlock?.recommended_query || fallbackQuery,
              target_genre: decisionBlock?.target_genre || 'Acoustic Flow',
              rationale: reasoning
            };

            const finalNotificationArgs: NotificationToolArgs = notificationToolCall || {
              orb_state: state === 'fatigued' ? 'FATIGUE_ALERT' : 'FLOW_LOCKED',
              headline:
                state === 'fatigued'
                  ? `Satiety Alert (${satietyScore}%) • ${action === 'change_genre' ? 'Shifting Genre' : 'Skipping Track'}`
                  : `Cognitive Flow Locked (${satietyScore}% Satiety)`,
              subtext: reasoning,
              satiety_score: satietyScore,
              show_heads_up_banner: state === 'fatigued'
            };

            return {
              state,
              action,
              satiety_score: satietyScore,
              reasoning,
              recommended_uri: finalPlaybackArgs.target_uri,
              recommended_query: finalPlaybackArgs.search_query,
              target_genre: finalPlaybackArgs.target_genre,
              tool_calls: [
                { tool_name: 'SpotifyPlaybackTool', arguments: finalPlaybackArgs },
                { tool_name: 'NotificationTool', arguments: finalNotificationArgs }
              ],
              telemetry_metrics: {
                evaluated_track_count: metrics.evaluated_track_count,
                early_skip_ratio: metrics.early_skip_ratio,
                consecutive_skip_streak: metrics.consecutive_skip_streak,
                artist_repetition_ratio: metrics.artist_repetition_ratio,
                dominant_artist: metrics.dominant_artist
              }
            };
          }
        }
      } catch (err: any) {
        console.warn(
          '[SatietyExecutionEngine] Claude tool-use call failed, falling back to deterministic tree:',
          err?.message || err
        );
      }
    }

    // Deterministic Psychoacoustic Fallback Tree if offline or API key unconfigured
    return this.evaluateDeterministicTree(metrics);
  }

  private evaluateDeterministicTree(
    metrics: ReturnType<SatietyExecutionEngine['computeTelemetryMetrics']>
  ): SatietyDecisionOutput {
    const isGenreFatigued =
      metrics.artist_repetition_ratio >= 0.35 || metrics.early_skip_ratio >= 0.4;
    const isSkipFatigued =
      metrics.consecutive_skip_streak >= 2 || metrics.early_skip_ratio >= 0.28;

    const state: 'fatigued' | 'focused' =
      isGenreFatigued || isSkipFatigued ? 'fatigued' : 'focused';
    const action: 'change_genre' | 'skip_track' | 'maintain' = isGenreFatigued
      ? 'change_genre'
      : isSkipFatigued
      ? 'skip_track'
      : 'maintain';

    const satietyScore =
      state === 'fatigued'
        ? Math.min(
            96,
            Math.round(
              45 +
                metrics.early_skip_ratio * 60 +
                metrics.artist_repetition_ratio * 45 +
                metrics.consecutive_skip_streak * 8
            )
          )
        : Math.max(14, Math.round(20 + metrics.artist_repetition_ratio * 30));

    const freshTrack = metrics.fresh_candidate;
    const recommendedQuery = freshTrack
      ? `${freshTrack.track_title} ${freshTrack.artist_name}`.trim()
      : 'Chill Afro Acoustic Reset';
    const recommendedUri =
      freshTrack?.spotify_uri || 'spotify:playlist:37i9dQZF1DX4sWSpwq3LiO';

    const reasoning =
      action === 'change_genre'
        ? `Detected ${Math.round(metrics.artist_repetition_ratio * 100)}% artist concentration (${metrics.dominant_artist}) and ${Math.round(metrics.early_skip_ratio * 100)}% early-skip rate across your last ${metrics.evaluated_track_count} tracks. Shifting genre to "${recommendedQuery}" to reset auditory receptor sensitivity.`
        : action === 'skip_track'
        ? `Detected ${metrics.consecutive_skip_streak} consecutive early skips (${Math.round(metrics.early_skip_ratio * 100)}% skip ratio). Automatically skipping to "${recommendedQuery}" to restore dopamine flow.`
        : `Auditory receptivity is locked in flow (${100 - satietyScore}% freshness across ${metrics.evaluated_track_count} local Room tracks). Maintaining current playback trajectory.`;

    return {
      state,
      action,
      satiety_score: satietyScore,
      reasoning,
      recommended_uri: recommendedUri,
      recommended_query: recommendedQuery,
      target_genre: action === 'change_genre' ? 'Fresh Dopamine Reset' : undefined,
      tool_calls: [
        {
          tool_name: 'SpotifyPlaybackTool',
          arguments: {
            command: action === 'maintain' ? 'maintain' : action,
            target_uri: recommendedUri,
            search_query: recommendedQuery,
            target_genre: 'Fresh Dopamine Reset',
            rationale: reasoning
          }
        },
        {
          tool_name: 'NotificationTool',
          arguments: {
            orb_state: state === 'fatigued' ? 'FATIGUE_ALERT' : 'FLOW_LOCKED',
            headline:
              state === 'fatigued'
                ? `Satiety ${satietyScore}% • Auto-${action === 'change_genre' ? 'Genre Shift' : 'Skip'}`
                : `Flow State Active (${satietyScore}% Satiety)`,
            subtext: reasoning,
            satiety_score: satietyScore,
            show_heads_up_banner: state === 'fatigued'
          }
        }
      ],
      telemetry_metrics: {
        evaluated_track_count: metrics.evaluated_track_count,
        early_skip_ratio: metrics.early_skip_ratio,
        consecutive_skip_streak: metrics.consecutive_skip_streak,
        artist_repetition_ratio: metrics.artist_repetition_ratio,
        dominant_artist: metrics.dominant_artist
      }
    };
  }
}
