import { GoogleGenAI } from '@google/genai';
import { v4 as uuidv4 } from 'uuid';
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

/**
 * GeminiMusicReasoningProvider — Concrete implementation of MusicReasoningProvider
 * using the official Google GenAI SDK (@google/genai) and gemini-3.1-flash-lite / gemini-3.8-flash.
 *
 * Generates probabilistic inferences, cognitive flow narratives, and acoustic steering
 * recommendations over real user telemetry events.
 *
 * Implements graceful fallback to deterministic neuro-acoustic baseline when offline,
 * rate-limited, or when API key is unconfigured or invalid.
 */
export class GeminiMusicReasoningProvider implements MusicReasoningProvider {
  readonly providerId = 'google-gemini';
  private ai: GoogleGenAI | null = null;
  private readonly preferredModels = ['gemini-3.8-flash', 'gemini-flash-latest', 'gemini-3.1-flash-lite'];
  private hasInvalidKey = false;
  private lastKeyCheckAt: number = 0;
  private activeModelUsed: string = 'gemini-3.8-flash';
  private lastErrorDetails: { status?: number | string; message?: string } | null = null;

  constructor() {
    this.initClient();
  }

  getLastDiagnostics() {
    const rawKey = process.env.GEMINI_API_KEY || process.env.API_KEY;
    return {
      hasAiClient: Boolean(this.ai),
      keyPresent: Boolean(rawKey),
      keyLength: rawKey?.length || 0,
      hasInvalidKey: this.hasInvalidKey,
      lastErrorDetails: this.lastErrorDetails,
      activeModelUsed: this.activeModelUsed
    };
  }

  private cleanKey(raw?: string): string | null {
    if (!raw) return null;
    const trimmed = raw.trim().replace(/^["']|["']$/g, '');
    if (!trimmed || trimmed === 'TODO' || trimmed === 'YOUR_API_KEY' || trimmed === 'undefined' || trimmed === 'null') {
      return null;
    }
    return trimmed;
  }

  private initClient(): void {
    const rawKey = process.env.GEMINI_API_KEY || process.env.API_KEY;
    const apiKey = this.cleanKey(rawKey);

    if (apiKey) {
      try {
        this.ai = new GoogleGenAI({
          apiKey,
          httpOptions: {
            headers: {
              'User-Agent': 'aistudio-build',
            },
          },
        });
        // Reset invalid key flag on fresh client init if key was provided
        if (Date.now() - this.lastKeyCheckAt > 60_000) {
          this.hasInvalidKey = false;
        }
      } catch (err) {
        console.info('[GeminiMusicReasoningProvider] GoogleGenAI client init skipped:', (err as Error)?.message || err);
        this.ai = null;
      }
    } else {
      this.ai = null;
    }
  }

  async isAvailable(): Promise<boolean> {
    if (this.hasInvalidKey) {
      return false;
    }
    if (!this.ai) {
      this.initClient();
    }
    const rawKey = process.env.GEMINI_API_KEY || process.env.API_KEY;
    return Boolean(this.ai && this.cleanKey(rawKey) && !this.hasInvalidKey);
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

    if (!available || !this.ai) {
      return this.buildHeuristicReport(
        context,
        this.hasInvalidKey
          ? 'Gemini API key is unverified or invalid. Operating on neuro-acoustic baseline.'
          : 'Gemini API key not configured. Using deterministic neuro-acoustic baseline.'
      );
    }

    const stats = context.recentListeningStats;
    const anchors = context.auditoryAnchorSignals;
    const fatigue = context.fatigueSignals ?? [];

    const prompt = `
Analyze the following user music listening telemetry:
- User ID: ${context.userId}
- Telemetry Period: ${stats.periodStart} to ${stats.periodEnd}
- Total Listening Time: ${Math.round(stats.totalListeningMs / 60000)} minutes
- Unique Tracks: ${stats.uniqueTracksPlayed}
- Total Plays: ${stats.totalPlayEvents}
- Total Skips: ${stats.totalSkipEvents}
- Average Completion: ${Math.round(stats.averageCompletionPercent)}%
${anchors ? `- Auditory Anchors: Groove/Rhythm: ${(anchors.grooveRhythm * 100).toFixed(0)}%, Vocal/Melody: ${(anchors.vocalMelody * 100).toFixed(0)}%, Structural Drops: ${(anchors.dropStructuralDynamics * 100).toFixed(0)}% (Confidence: ${(anchors.signalConfidence * 100).toFixed(0)}%)` : ''}
${fatigue.length > 0 ? `- Track Fatigue Signals:\n${fatigue.map(f => `  * ${f.trackKey}: ${(f.estimatedFatigue * 100).toFixed(0)}% fatigue (Plays: ${f.basedOnPlayCount}, Trend: ${f.completionTrend})`).join('\n')}` : ''}
${rawEventsSummary ? `- Raw Event Summary:\n${rawEventsSummary}` : ''}

You are TuneMind's Chief Neuro-Acoustic Reasoning AI.
Produce a comprehensive Cognitive Reasoning Report evaluating:
1. Auditory receptor receptivity and sensory adaptation (receptivityScore between 0 and 100).
2. Cognitive flow state ("OPTIMAL_FLOW", "MILD_SATURATION", or "ELEVATED_BURNOUT").
3. A punchy 2-3 sentence cognitive narrative explaining their focus and dopamine adaptation.
4. Concrete acoustic shift recommendations (tempo adaptation, timbre cleansing, silence interval).
5. Probabilistic inferences regarding repetition loops or preference shifts.

Respond ONLY with a valid JSON object matching exactly this schema:
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
}
`.trim();

    let responseText: string | undefined;
    let modelUsed = this.activeModelUsed;

    for (const modelName of this.preferredModels) {
      if (responseText) break;
      if (this.hasInvalidKey) break;

      try {
        // Enforce a 6-second timeout per model attempt so background polling never hangs
        const timeoutPromise = new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error(`Timeout calling ${modelName}`)), 6000)
        );

        const callPromise = this.ai.models.generateContent({
          model: modelName,
          contents: prompt,
          config: {
            systemInstruction:
              "You are TuneMind's Chief Neuro-Acoustic Reasoning AI protecting listeners from music satiety and burnout while maximizing cognitive flow. All inferences are probabilistic, never stated as absolute biological facts.",
            responseMimeType: 'application/json',
            temperature: 0.4,
          },
        });

        const response = await Promise.race([callPromise, timeoutPromise]);
        responseText = response?.text;

        if (responseText) {
          modelUsed = modelName;
          this.activeModelUsed = modelName;
          break;
        }
      } catch (err: any) {
        const errMsg = String(err?.message || err || '');
        const errStatus = err?.status || err?.code || err?.statusCode;
        this.lastErrorDetails = { status: errStatus, message: errMsg.slice(0, 150) };

        const isInvalidKey =
          errStatus === 400 &&
          (errMsg.includes('API_KEY_INVALID') ||
            errMsg.includes('API key not valid') ||
            errMsg.includes('INVALID_ARGUMENT'));

        const isUnauthorized = errStatus === 401 || errStatus === 403 || errMsg.includes('PERMISSION_DENIED');

        if (isInvalidKey || isUnauthorized) {
          this.hasInvalidKey = true;
          this.lastKeyCheckAt = Date.now();
          console.info(
            '[GeminiMusicReasoningProvider] Notice: Gemini API key is unverified or invalid. Gracefully operating on deterministic neuro-acoustic baseline.'
          );
          // Do not attempt other models or retry when key is invalid
          break;
        }

        // For temporary 503 (high demand) or timeouts, continue to next preferred model silently
        const isTransient = errStatus === 503 || errStatus === 429 || errMsg.includes('Timeout') || errMsg.includes('high demand');
        if (isTransient) {
          console.info(`[GeminiMusicReasoningProvider] ${modelName} transiently busy; checking alternative model...`);
        }
      }
    }

    if (!responseText) {
      return this.buildHeuristicReport(
        context,
        this.hasInvalidKey
          ? 'Gemini API key is unverified; using neuro-acoustic baseline.'
          : 'Gemini reasoning models transiently busy; fallback heuristic applied.'
      );
    }

    try {
      const parsed = JSON.parse(responseText);

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
          statusLabel:
            parsed.statusLabel ||
            (state === 'OPTIMAL_FLOW'
              ? 'Optimal Cognitive Flow'
              : state === 'MILD_SATURATION'
              ? 'Mild Acoustic Saturation'
              : 'Auditory Receptor Burnout')
        },
        flowStateNarrative:
          parsed.flowStateNarrative || 'Auditory telemetry indicates receptive neuro-acoustic engagement.',
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
        modelUsed
      };
    } catch {
      return this.buildHeuristicReport(context, 'Gemini telemetry parsed with neuro-acoustic baseline.');
    }
  }

  /**
   * Deterministic neuro-acoustic baseline used when offline, unverified key, or during network transients.
   */
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
      modelUsed: 'deterministic-heuristic'
    };
  }

  /**
   * Direct live analysis endpoint called by Android client or web dashboard proxy.
   * Leverages Gemini 3.8 Flash with the backend's API_KEY.
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
    if (!available || !this.ai) {
      this.initClient();
    }

    const tracksSummary = (payload.recentTracks || [])
      .map(t => `- "${t.title || 'Unknown'}" by ${t.artist || 'Unknown'} (${t.plays || 1} plays, ${t.satiety || 20}% satiety)`)
      .join('\n');

    const vaultSummary = (payload.vaultTracks || [])
      .map(v => `- "${v.title}" by ${v.artist} (${v.cooldownDaysRemaining}d recovery remaining)`)
      .join('\n');

    const prompt = `
Real-time User Music Telemetry Payload:
- Time Slot: ${payload.timeSlot || 'Active Listening Session'}
- Primary Source: ${payload.topSourceApp || 'Spotify'}
- Total Observed Events: ${payload.eventCount || 24}
- Now Playing: ${payload.nowPlaying ? `"${payload.nowPlaying.title}" by ${payload.nowPlaying.artist} [Playing: ${payload.nowPlaying.isPlaying}]` : 'Standby / Idle'}
- Recent Active Tracks:
${tracksSummary || '- No recent tracks recorded yet.'}
- Quarantined High-Satiety Vault:
${vaultSummary || '- No tracks currently quarantined.'}

Act as TuneMind's Chief Neuro-Acoustic Reasoning AI.
Generate a real-time cognitive reasoning synthesis:
1. receptivityScore: 0 to 100 representing auditory receptor freshness.
2. cognitiveState: OPTIMAL_FLOW, MILD_SATURATION, or ELEVATED_BURNOUT.
3. statusLabel: A concise 2-4 word technical status headline.
4. flowNarrative: 2-3 sentence cognitive analysis of the listener's focus trajectory and dopamine adaptation.
5. recommendations: 2 actionable acoustic recommendations (tempo shift, frequency balance, rest interval).
6. probabilisticInferences: 2 probabilistic deductions about listening fatigue or harmonic patterns.

Respond ONLY with a valid JSON object matching:
{
  "receptivityScore": 88,
  "cognitiveState": "OPTIMAL_FLOW",
  "statusLabel": "Optimal Cognitive Flow",
  "flowNarrative": "Acoustic receptors show balanced stimulation with minimal repetition fatigue.",
  "recommendations": [
    {
      "type": "TEMPO_ADAPTATION",
      "action": "Maintain 112-124 BPM frequency band",
      "rationale": "Sustains focus without cognitive exhaustion"
    }
  ],
  "probabilisticInferences": [
    {
      "inference": "High structural tolerance in current session",
      "confidence": 0.91,
      "basis": "Completion rates remain above 80%"
    }
  ]
}
`.trim();

    if (this.ai && !this.hasInvalidKey) {
      for (const model of this.preferredModels) {
        try {
          const timeoutPromise = new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error(`Timeout calling ${model}`)), 8000)
          );

          const callPromise = this.ai.models.generateContent({
            model,
            contents: prompt,
            config: {
              systemInstruction:
                "You are TuneMind's Chief Neuro-Acoustic Reasoning AI protecting listeners from music satiety and burnout while maximizing cognitive flow.",
              responseMimeType: 'application/json',
              temperature: 0.35,
            },
          });

          const res = await Promise.race([callPromise, timeoutPromise]);
          const text = res?.text;
          if (text) {
            const parsed = JSON.parse(text);
            return {
              status: 'ok',
              isAiGenerated: true,
              modelUsed: model,
              generatedAt: new Date().toISOString(),
              ...parsed
            };
          }
        } catch (err: any) {
          const msg = String(err?.message || err);
          console.info(`[GeminiMusicReasoningProvider] Model ${model} returned: ${msg.slice(0, 100)}`);
          if (msg.includes('API_KEY_INVALID') || msg.includes('PERMISSION_DENIED')) {
            this.hasInvalidKey = true;
            break;
          }
        }
      }
    }

    // Deterministic fallback if API key is not yet set or transiently offline
    const trackCount = payload.recentTracks?.length || 0;
    const avgSatiety = payload.recentTracks?.length
      ? Math.round(payload.recentTracks.reduce((acc, t) => acc + (t.satiety || 20), 0) / payload.recentTracks.length)
      : 45;

    const state = avgSatiety >= 75 ? 'ELEVATED_BURNOUT' : avgSatiety >= 55 ? 'MILD_SATURATION' : 'OPTIMAL_FLOW';
    const score = 100 - avgSatiety;

    return {
      status: 'ok',
      isAiGenerated: false,
      modelUsed: 'neuro-acoustic-baseline',
      generatedAt: new Date().toISOString(),
      receptivityScore: Math.max(15, Math.min(98, score)),
      cognitiveState: state,
      statusLabel: state === 'OPTIMAL_FLOW' ? 'Optimal Cognitive Flow' : state === 'MILD_SATURATION' ? 'Mild Acoustic Saturation' : 'Auditory Receptor Burnout',
      flowNarrative: state === 'OPTIMAL_FLOW'
        ? `Auditory stereocilia engagement remains pristine across ${trackCount} tracked tracks. Neural tolerance for harmonic variation is optimal.`
        : `Sensory adaptation detected across frequent loops. Satiety index is at ${avgSatiety}%. A frequency timbre shift is recommended.`,
      recommendations: [
        {
          type: 'TEMPO_ADAPTATION',
          action: 'Shift to ambient or downtempo frequencies',
          rationale: 'Allows dopamine receptors to reset without interrupting focus.'
        },
        {
          type: 'TIMBRE_CLEANSING',
          action: 'Introduce acoustic instruments or binaural soundscapes',
          rationale: 'Breaks synthesized sub-bass monotony.'
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
   * Generates chronobiological, auto-evolving music recommendations based on
   * time of day, auditory receptor satiety, and observed listening growth.
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
    if (!available || !this.ai) {
      this.initClient();
    }

    const timeSlot = payload.timeSlot || 'Morning Focus';
    const satiety = payload.satietyLevel ?? 45;
    const observedArtists = (payload.topArtists || []).filter(Boolean);
    const userAnchors = (payload.userTasteAnchors || []).filter(Boolean);
    const excludeTitles: string[] = (payload.excludeTitles || []).map((t: string) => String(t).trim()).filter(Boolean);
    const positiveFeedback: string[] = (payload.playedFeedback || []).map((t: string) => String(t).trim()).filter(Boolean);
    const negativeFeedback: string[] = (payload.dismissedFeedback || []).map((t: string) => String(t).trim()).filter(Boolean);

    // User taste anchors take highest precedence over raw observed tracks
    const allArtists = Array.from(new Set([...userAnchors, ...observedArtists]));
    const artistsSummary = allArtists.slice(0, 8).join(', ') || 'Bongo Flava, Afrobeats, Hip-Hop, R&B, Pop';

    const recentTracksSummary = (payload.recentTracks || [])
      .slice(0, 8)
      .map(t => `- "${t.title}" by ${t.artist} (${t.plays || 1} plays, satiety ~${t.satiety || 30}%)`)
      .join('\n') || '- Emerging listening baseline.';

    const vaultSummary = (payload.vaultTracks || [])
      .slice(0, 6)
      .map(v => `- "${v.title}" by ${v.artist}`)
      .join(', ') || 'None';

    const exclusionListStr = excludeTitles.length > 0
      ? excludeTitles.slice(0, 60).map(t => `"${t}"`).join(', ')
      : 'None';

    const positiveStr = positiveFeedback.length > 0
      ? positiveFeedback.slice(0, 10).join('; ')
      : 'User actively enjoys their primary taste anchors.';

    const negativeStr = negativeFeedback.length > 0
      ? negativeFeedback.slice(0, 10).join('; ')
      : 'None recorded yet.';

    const prompt = `
CRITICAL USER TASTE DIRECTIVE:
You are TuneMind's Cognitive Audio Discovery AI.
The user is an active music listener with this demonstrated rotation and taste:
- TOP DEMONSTRATED ARTISTS & TASTE ANCHORS: ${artistsSummary}
- RECENT OBSERVED TRACKS IN ROTATION:
${recentTracksSummary}
- CURRENT TIME OF DAY: ${timeSlot}
- AUDITORY SATIETY: ${satiety}% (0% = fresh, 100% = saturated)
- COOLDOWN RESTING TRACKS (do NOT recommend): ${vaultSummary}

REINFORCEMENT LEARNING SIGNALS (AUTONOMOUS TASTE CALIBRATION):
- POSITIVE SIGNALS (User played or saved these gems with high dopamine affinity): ${positiveStr}
  -> DIRECTIVE: Strongly reinforce and expand upon the acoustic signatures, melodies, and kindred artists of these tracks.
- NEGATIVE SIGNALS (User dismissed or skipped these — avoid these vibes): ${negativeStr}
  -> DIRECTIVE: Steer away from rhythms, arrangements, or moods matching these dismissed tracks.
- STRICT EXCLUSION LIST (DO NOT RECOMMEND ANY OF THESE TITLES — THEY ARE ALREADY IN CRATE OR ROTATION):
  [${exclusionListStr}]

ABSOLUTE REQUIREMENTS:
1. NEVER GUESS OR GENERATE RANDOM GENERIC AMBIENT/INSTRUMENTAL/INDIE TRACKS (e.g. Brian Eno, Marconi Union, Tycho) UNLESS THE USER'S HISTORY EXPLICITLY LISTS THEM!
2. All 10 recommended tracks MUST belong to the EXACT SAME or CLOSELY ADJACENT musical universe, style, and genre as their demonstrated artists above (${artistsSummary}).
   - If the user listens to Bongo Flava / East African Afro-Pop (e.g. Mbosso, Diamond Platnumz, Harmonize, Rayvanny, Zuchu, Marioo, Ali Kiba, Jux, Otile Brown, Nandy, Kusah, Lava Lava): recommend genuine, beloved Bongo Flava hits from Mbosso, Diamond Platnumz, Marioo, Rayvanny, Harmonize, Zuchu, Alikiba, Otile Brown, etc.! NEVER substitute Western ambient music for Bongo Flava!
   - If the user listens to Afrobeats/Amapiano: recommend celebrated songs from Burna Boy, Asake, Tems, Rema, Wizkid, Ayra Starr, Omah Lay, Fireboy DML, Kabza De Small, etc.
   - If the user listens to Hip-Hop/Rap: recommend Kendrick Lamar, J. Cole, Drake, Travis Scott, 21 Savage, Future, Gunna, Metro Boomin, Baby Keem, etc.
   - If the user listens to R&B/Soul: recommend SZA, Frank Ocean, Brent Faiyaz, Daniel Caesar, Giveon, Summer Walker, Steve Lacy, etc.
3. Every song must be a REAL, HIGH-QUALITY track by real artists. No hallucinations. Do not repeat any artist more than twice across the 10 recommendations.

GENERATE EXACTLY 10 DIVERSE TRACKS STRUCTURED ACROSS 4 SUB-TIERS:
- 4 tracks: "CORE_DOPAMINE" (category: "DOPAMINE_FLOW") -> Direct bullseye hits matching their favorite artists.
- 3 tracks: "DEEP_CUT" (category: "NOVEL_EXPLORATION" or "DOPAMINE_FLOW") -> High-quality b-sides or rising artists in the genre.
- 2 tracks: "PALATE_CLEANSER" (category: "PALATE_CLEANSER") -> Stripped-back acoustic, tender ballad, or fatigue-reset track within the genre.
- 1 track: "SONIC_FRONTIER" (category: "NOVEL_EXPLORATION" or "CHRONO_RESTORE") -> Controlled adjacent crossover (e.g., Afro-Bongo Amapiano fusion).

Output strictly a valid JSON array of 10 objects matching this schema:
[
  {
    "title": "Song Title",
    "artist": "Artist Name",
    "album": "Album Name",
    "category": "DOPAMINE_FLOW" | "PALATE_CLEANSER" | "CHRONO_RESTORE" | "NOVEL_EXPLORATION",
    "subTier": "CORE_DOPAMINE" | "DEEP_CUT" | "PALATE_CLEANSER" | "SONIC_FRONTIER",
    "reason": "1 concise sentence explaining the acoustic match and dopamine fit for ${timeSlot}.",
    "confidence": 0.95
  }
]
`.trim();

    if (this.ai && !this.hasInvalidKey) {
      for (const model of this.preferredModels) {
        try {
          const timeoutPromise = new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error(`Timeout calling ${model}`)), 14000)
          );

          const callPromise = this.ai.models.generateContent({
            model,
            contents: prompt,
            config: {
              systemInstruction:
                "You are TuneMind's Chief Cognitive Music Discovery AI. Output strictly valid JSON arrays only. Recommend real, popular, genre-congruent songs matching user taste. Never repeat excluded songs.",
              responseMimeType: 'application/json',
              temperature: 0.35,
            },
          });

          const res = await Promise.race([callPromise, timeoutPromise]);
          const text = res?.text;
          if (text) {
            const clean = text.replace(/```json/g, '').replace(/```/g, '').trim();
            const parsed = JSON.parse(clean);
            if (Array.isArray(parsed) && parsed.length > 0) {
              const excludedSet = new Set(excludeTitles.map(t => t.toLowerCase()));
              const validRecs = parsed
                .filter(item => {
                  const titleLower = String(item.title || '').trim().toLowerCase();
                  return titleLower.length > 0 && !excludedSet.has(titleLower);
                })
                .map(item => ({
                  title: String(item.title || 'Discovery Track'),
                  artist: String(item.artist || 'Featured Artist'),
                  album: item.album ? String(item.album) : null,
                  category: String(item.category || 'DOPAMINE_FLOW'),
                  subTier: String(item.subTier || 'CORE_DOPAMINE'),
                  reason: String(item.reason || `Curated for your ${timeSlot} cognitive state based on your listening pattern.`),
                  confidence: Math.min(1.0, Math.max(0.7, Number(item.confidence) || 0.92))
                }));

              if (validRecs.length >= 4) {
                return {
                  status: 'ok',
                  isAiGenerated: true,
                  modelUsed: model,
                  recommendations: validRecs.slice(0, 10)
                };
              }
            }
          }
        } catch (err: any) {
          const msg = String(err?.message || err);
          console.info(`[GeminiMusicReasoningProvider] Discovery model ${model}: ${msg.slice(0, 100)}`);
          if (msg.includes('API_KEY_INVALID') || msg.includes('PERMISSION_DENIED')) {
            this.hasInvalidKey = true;
            break;
          }
        }
      }
    }

    // Genre-Aware Adaptive Baseline Fallback (10 genre-congruent tracks per taste palette)
    const lowerArtists = artistsSummary.toLowerCase();
    const isBongo = lowerArtists.includes('bongo') || lowerArtists.includes('mbosso') || lowerArtists.includes('diamond') || lowerArtists.includes('platnumz') || lowerArtists.includes('harmonize') || lowerArtists.includes('rayvanny') || lowerArtists.includes('zuchu') || lowerArtists.includes('marioo') || lowerArtists.includes('kiba') || lowerArtists.includes('kusah') || lowerArtists.includes('jux') || lowerArtists.includes('otile') || lowerArtists.includes('nandy') || lowerArtists.includes('swahili') || lowerArtists.includes('east africa');
    const isAfro = lowerArtists.includes('afro') || lowerArtists.includes('amapiano') || lowerArtists.includes('burna') || lowerArtists.includes('wizkid') || lowerArtists.includes('rema') || lowerArtists.includes('asake') || lowerArtists.includes('tems');
    const isHipHop = lowerArtists.includes('hip') || lowerArtists.includes('rap') || lowerArtists.includes('drake') || lowerArtists.includes('kendrick') || lowerArtists.includes('cole') || lowerArtists.includes('travis');
    const isRnB = lowerArtists.includes('r&b') || lowerArtists.includes('soul') || lowerArtists.includes('sza') || lowerArtists.includes('ocean') || lowerArtists.includes('faiyaz');

    let fullLibrary: Array<{
      title: string;
      artist: string;
      album: string;
      category: string;
      subTier: string;
      reason: string;
      confidence: number;
    }>;

    if (isBongo) {
      fullLibrary = [
        {
          title: "Huwezi Jua",
          artist: "Mbosso",
          album: "Khan",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Soulful acoustic guitar chords and signature Mbosso romantic cadence deliver immediate emotional entrainment.",
          confidence: 0.98
        },
        {
          title: "Mi Amor",
          artist: "Marioo & Jovial",
          album: "The Kid You Know",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Tender Swahili vocal harmonies and sweet acoustic rhythm clear cognitive auditory fatigue.",
          confidence: 0.96
        },
        {
          title: "Komasava",
          artist: "Diamond Platnumz",
          album: "Komasava",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "High-energy Afro-Bongo rhythm and infectious hook elevate daytime dopamine alertness.",
          confidence: 0.97
        },
        {
          title: "Sukari",
          artist: "Zuchu",
          album: "I Am Zuchu",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Playful taarab-infused acoustic cadence and bright melodic hooks provide sustained listening joy.",
          confidence: 0.95
        },
        {
          title: "Amepotea",
          artist: "Mbosso",
          album: "Khan",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Introspective acoustic ballad highlighting rich Swahili lyricism and melancholy string arrangements.",
          confidence: 0.94
        },
        {
          title: "Enjoy",
          artist: "Jux & Diamond Platnumz",
          album: "Enjoy",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Effortless Afro-Bongo bounce with smooth vocal trade-offs that maintain active flow state.",
          confidence: 0.93
        },
        {
          title: "Single Again",
          artist: "Harmonize",
          album: "Made For Us",
          category: "CHRONO_RESTORE",
          subTier: "DEEP_CUT",
          reason: "Melodic guitar progression and relatable emotional phrasing tailored for chrono-recovery.",
          confidence: 0.94
        },
        {
          title: "Dah!",
          artist: "Nandy",
          album: "Dah!",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Airy, gentle vocal production resets acoustic receptor saturation without breaking genre continuity.",
          confidence: 0.95
        },
        {
          title: "Inatosha",
          artist: "Marioo",
          album: "The Kid You Know",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Warm mid-tempo guitar arpeggios soothe high-frequency auditory fatigue.",
          confidence: 0.92
        },
        {
          title: "Kelebe",
          artist: "Rayvanny & Innoss'B",
          album: "Sound from Africa",
          category: "NOVEL_EXPLORATION",
          subTier: "SONIC_FRONTIER",
          reason: "Exciting cross-border Congolese Rumba meets Bongo Flava fusion expands your listening horizon.",
          confidence: 0.91
        }
      ];
    } else if (isAfro) {
      fullLibrary = [
        {
          title: "Last Last",
          artist: "Burna Boy",
          album: "Love, Damini",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Toni Braxton sample cadence and syncopated groove triggers instantaneous dopamine entrainment.",
          confidence: 0.97
        },
        {
          title: "Calm Down",
          artist: "Rema",
          album: "Rave & Climax",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Smooth melodic warmth and mid-tempo rhythmic pacing calibrated for circadian focus.",
          confidence: 0.96
        },
        {
          title: "Lonely At The Top",
          artist: "Asake",
          album: "Work of Art",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Acoustic log drum undertones and introspective vocal harmonies deliver supreme cognitive comfort.",
          confidence: 0.95
        },
        {
          title: "Rush",
          artist: "Ayra Starr",
          album: "19 & Dangerous",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Punchy percussion and magnetic hook provide sustained listening pleasure.",
          confidence: 0.94
        },
        {
          title: "Soweto",
          artist: "Victony & Tempoe",
          album: "Outlaw",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Subtle flute hooks and delicate rhythmic pacing keep listening receptors active.",
          confidence: 0.93
        },
        {
          title: "Ku Lo Sa",
          artist: "Oxlade",
          album: "Ku Lo Sa",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Intricate falsetto and warm percussion create an expansive, resonant acoustic space.",
          confidence: 0.92
        },
        {
          title: "Essence",
          artist: "Wizkid & Tems",
          album: "Made in Lagos",
          category: "CHRONO_RESTORE",
          subTier: "DEEP_CUT",
          reason: "Sun-drenched saxophone cadence and smooth duet harmonies induce circadian tranquility.",
          confidence: 0.95
        },
        {
          title: "Free Mind",
          artist: "Tems",
          album: "For Broken Ears",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Intimate acoustic guitar chords and raw vocal dynamics clear auditory receptor habituation.",
          confidence: 0.96
        },
        {
          title: "Understand",
          artist: "Omah Lay",
          album: "Boy Alone",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Gentle mid-tempo bounce and reflective cadence allow ear rest between high-energy sessions.",
          confidence: 0.93
        },
        {
          title: "Mnike",
          artist: "Tyler ICU & Tumelo.za",
          album: "Mnike",
          category: "NOVEL_EXPLORATION",
          subTier: "SONIC_FRONTIER",
          reason: "Deep, hypnotic Amapiano shaker patterns introduce sonic novelty while respecting your groove palate.",
          confidence: 0.91
        }
      ];
    } else if (isHipHop) {
      fullLibrary = [
        {
          title: "Middle Child",
          artist: "J. Cole",
          album: "Middle Child",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Crisp horn fanfare and punchy 808 cadence stimulate dopamine release and sustained flow.",
          confidence: 0.96
        },
        {
          title: "First Person Shooter",
          artist: "Drake & J. Cole",
          album: "For All The Dogs",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Dynamic two-part beat switch and rapid flow maintain intense cognitive engagement.",
          confidence: 0.95
        },
        {
          title: "HUMBLE.",
          artist: "Kendrick Lamar",
          album: "DAMN.",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Aggressive piano riff and bass-heavy structure provide immediate alertness boost.",
          confidence: 0.97
        },
        {
          title: "SICKO MODE",
          artist: "Travis Scott",
          album: "ASTROWORLD",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Triple-act tempo shift and lush low-end frequencies create rich auditory immersion.",
          confidence: 0.94
        },
        {
          title: "family ties",
          artist: "Baby Keem & Kendrick Lamar",
          album: "The Melodic Blue",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Dynamic brass modulation and beat shifts expand your listening horizon.",
          confidence: 0.93
        },
        {
          title: "Surround Sound",
          artist: "JID, 21 Savage & Baby Tate",
          album: "The Forever Story",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Aretha Franklin sample flips into high-precision technical cadence.",
          confidence: 0.92
        },
        {
          title: "a lot",
          artist: "21 Savage & J. Cole",
          album: "i am > i was",
          category: "CHRONO_RESTORE",
          subTier: "DEEP_CUT",
          reason: "Soulful backing vocal loop and laid-back cadence balance temporal energy.",
          confidence: 0.94
        },
        {
          title: "PRIDE.",
          artist: "Kendrick Lamar",
          album: "DAMN.",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Warbled guitars and relaxed vocal pacing provide complete ear fatigue relief.",
          confidence: 0.95
        },
        {
          title: "LOVE.",
          artist: "Kendrick Lamar & Zacari",
          album: "DAMN.",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Soothing falsetto vocal lines and minimalist production reset auditory saturation.",
          confidence: 0.93
        },
        {
          title: "Redrum",
          artist: "21 Savage",
          album: "american dream",
          category: "NOVEL_EXPLORATION",
          subTier: "SONIC_FRONTIER",
          reason: "Haunting vintage vocal sample fused with contemporary trap beats expands aesthetic boundaries.",
          confidence: 0.91
        }
      ];
    } else {
      fullLibrary = [
        {
          title: "Blinding Lights",
          artist: "The Weeknd",
          album: "After Hours",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Driving 80s synth progression and relentless groove deliver peak engagement.",
          confidence: 0.96
        },
        {
          title: "Kill Bill",
          artist: "SZA",
          album: "SOS",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Bouncy bassline and crisp vocal hooks trigger sustained auditory pleasure.",
          confidence: 0.96
        },
        {
          title: "Levitating",
          artist: "Dua Lipa",
          album: "Future Nostalgia",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Nu-disco funk rhythm and energetic tempo expand current listening patterns.",
          confidence: 0.94
        },
        {
          title: "As It Was",
          artist: "Harry Styles",
          album: "Harry's House",
          category: "DOPAMINE_FLOW",
          subTier: "CORE_DOPAMINE",
          reason: "Bright bell chimes and driving indie-pop drum beat elevate daily focus.",
          confidence: 0.93
        },
        {
          title: "Snooze",
          artist: "SZA",
          album: "SOS",
          category: "CHRONO_RESTORE",
          subTier: "DEEP_CUT",
          reason: "Velvety chord progressions and laid-back tempo harmonize with your circadian rhythm.",
          confidence: 0.95
        },
        {
          title: "Bad Habit",
          artist: "Steve Lacy",
          album: "Gemini Rights",
          category: "NOVEL_EXPLORATION",
          subTier: "DEEP_CUT",
          reason: "Lo-fi guitar hooks and funky harmonies push taste boundaries with zero friction.",
          confidence: 0.93
        },
        {
          title: "Stay",
          artist: "The Kid LAROI & Justin Bieber",
          album: "F*CK LOVE 3",
          category: "CHRONO_RESTORE",
          subTier: "DEEP_CUT",
          reason: "Upbeat tempo and bright harmonic cadence boost cognitive alertness.",
          confidence: 0.92
        },
        {
          title: "Redbone",
          artist: "Childish Gambino",
          album: "Awaken, My Love!",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Warm 70s soul bassline and falsetto reset compressed ear fatigue.",
          confidence: 0.96
        },
        {
          title: "Pink + White",
          artist: "Frank Ocean",
          album: "Blonde",
          category: "PALATE_CLEANSER",
          subTier: "PALATE_CLEANSER",
          reason: "Stripped piano and acoustic guitar reset sensory saturation effortlessly.",
          confidence: 0.97
        },
        {
          title: "Dead Man Walking",
          artist: "Brent Faiyaz",
          album: "Wasteland",
          category: "NOVEL_EXPLORATION",
          subTier: "SONIC_FRONTIER",
          reason: "Lush orchestral strings and slow cadence deliver perfect temporal balance.",
          confidence: 0.92
        }
      ];
    }

    // Filter out any songs already in crate/history
    const excludedSet = new Set(excludeTitles.map(t => t.toLowerCase()));
    const filteredFallback = fullLibrary.filter(t => !excludedSet.has(t.title.toLowerCase()));
    const finalRecs = (filteredFallback.length >= 4 ? filteredFallback : fullLibrary).slice(0, 10);

    return {
      status: 'ok',
      isAiGenerated: false,
      modelUsed: 'heuristic-taste-aligned-10x',
      recommendations: finalRecs
    };
  }

  /**
   * Examines a catalog of user timeline tracks and tags them with Genre, Language, and Vibes using Gemini.
   */
  async tagMusicCatalog(tracks: Array<{ id: string; title: string; artist: string }>): Promise<Record<string, { genre: string; language: string; vibes: string[] }>> {
    if (!tracks || tracks.length === 0) return {};
    const tagsMap: Record<string, { genre: string; language: string; vibes: string[] }> = {};

    const available = await this.isAvailable();
    if (!available || !this.ai) {
      // Offline fallback classifier
      for (const t of tracks) {
        tagsMap[t.id] = this.offlineHeuristicTag(t.title, t.artist);
      }
      return tagsMap;
    }

    const batch = tracks.slice(0, 50);
    const prompt = `You are an expert East African and global musicologist.
Examine this list of songs from a Kenyan user's music library and accurately classify each song by its true musical genre and language:
${JSON.stringify(batch.map(t => ({ id: t.id, title: t.title, artist: t.artist })), null, 2)}

Categories to use:
- genre: "Gikuyu / Mugithi" (Samidoh, De'Mathew, Joyce wa Mamaa, Salim, Gatutura, Kikuyu folk/guitar), "Bongo Flava" (Diamond, Zuchu, Rayvanny, Harmonize, Marioo, Swahili), "Gospel" (Worship, Praise, Kikuyu/Swahili/English church anthems, Mercy Masika, Sinach, Guardian Angel), "Afrobeats" (Burna Boy, Wizkid, Asake, Rema), "Gengetone / Kenyan Urban" (Sauti Sol, Mejja, Wakadinali, Nyashinski), "Pop / R&B", "Hip Hop", "Other"
- language: "Kikuyu", "Swahili", "English", "Yoruba", "Other"
- vibes: 2-3 short mood tags

Return JSON array format:
[
  { "id": "...", "genre": "...", "language": "...", "vibes": ["..."] }
]`;

    try {
      const res = await this.ai.models.generateContent({
        model: 'gemini-2.5-flash',
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          temperature: 0.2
        }
      });
      const text = res?.text;
      if (text) {
        const arr = JSON.parse(text);
        if (Array.isArray(arr)) {
          for (const item of arr) {
            if (item && item.id) {
              tagsMap[item.id] = {
                genre: item.genre || 'Other',
                language: item.language || 'Other',
                vibes: Array.isArray(item.vibes) ? item.vibes : []
              };
            }
          }
        }
      }
    } catch (e) {
      console.warn('[GeminiMusicReasoningProvider] LLM tagging failed, applying fallback:', (e as Error).message);
    }

    // Fill remaining with offline tagger
    for (const t of tracks) {
      if (!tagsMap[t.id]) {
        tagsMap[t.id] = this.offlineHeuristicTag(t.title, t.artist);
      }
    }

    return tagsMap;
  }

  /**
   * Conversational music agent: reasons over user's query and their tagged timeline catalog.
   */
  async conversationalMusicAgent(
    query: string,
    tracks: Array<{ id: string; title: string; artist: string; genre?: string; language?: string }>,
    currentPlaying?: string,
    userName: string = 'Tonny'
  ): Promise<{
    matchedTrackId: string | null;
    suggestedTitle: string | null;
    suggestedArtist: string | null;
    spokenText: string;
    action: string;
    genre: string;
  }> {
    const qLower = query.toLowerCase().trim();

    // Negation check
    if (['no', 'stop', 'cancel', 'not that', 'never mind', 'pause', 'hold on'].some(n => qLower === n || qLower.startsWith(n + ' '))) {
      return {
        matchedTrackId: null,
        suggestedTitle: null,
        suggestedArtist: null,
        spokenText: "Understood, cancelled. What would you like to hear instead?",
        action: "CANCEL",
        genre: "None"
      };
    }

    const available = await this.isAvailable();
    if (available && this.ai) {
      const sampleTracks = tracks.slice(0, 70).map(t => ({
        id: t.id,
        title: t.title,
        artist: t.artist,
        genre: t.genre || 'Unclassified',
        language: t.language || 'Unclassified'
      }));

      const prompt = `You are TuneMind Voice Agent, a conversational music assistant for ${userName} in Kenya.
User Spoken Query: "${query}"
Currently Playing: "${currentPlaying || 'None'}"
User's Real Timeline Catalog (${tracks.length} songs total):
${JSON.stringify(sampleTracks, null, 2)}

Instructions:
1. If the user asks for a specific genre or language (e.g. "Gikuyu song in timeline", "Gospel", "Bongo", "Afrobeats"), inspect the catalog and select the BEST matching song from their catalog that is truly that genre!
2. If the user asks to "change that song", pick a fresh energetic alternative from their catalog.
3. If the user asks an auditory/fatigue question, provide a concise 1-sentence answer without playing.
4. Keep the spokenText short, conversational, and natural like ChatGPT Voice or Siri (e.g., "Playing 'Wendo Wi Cama' by Samidoh from your Gikuyu collection.").

Return JSON format:
{
  "matchedTrackId": "string or null",
  "suggestedTitle": "string",
  "suggestedArtist": "string",
  "spokenText": "short spoken natural response",
  "action": "PLAY_TRACK" or "CHANGE_TRACK" or "SPEAK_ONLY" or "CANCEL",
  "genre": "identified genre"
}`;

      try {
        const res = await this.ai.models.generateContent({
          model: 'gemini-2.5-flash',
          contents: prompt,
          config: {
            responseMimeType: 'application/json',
            temperature: 0.3
          }
        });
        const text = res?.text;
        if (text) {
          const parsed = JSON.parse(text);
          return {
            matchedTrackId: parsed.matchedTrackId || null,
            suggestedTitle: parsed.suggestedTitle || null,
            suggestedArtist: parsed.suggestedArtist || null,
            spokenText: parsed.spokenText || `Playing ${parsed.suggestedTitle || 'selected track'}.`,
            action: parsed.action || 'PLAY_TRACK',
            genre: parsed.genre || 'Curated'
          };
        }
      } catch (e) {
        console.warn('[conversationalMusicAgent] LLM failed, using offline reasoning:', (e as Error).message);
      }
    }

    // High quality offline fallback
    return this.offlineConversationalFallback(query, tracks, currentPlaying, userName);
  }

  private offlineHeuristicTag(title: string, artist: string): { genre: string; language: string; vibes: string[] } {
    const combined = `${title} ${artist}`.toLowerCase();
    const GIKUYU_ARTISTS = ['samidoh', 'salim', 'demathew', "de'mathew", 'mamaa', 'gatutura', 'kamande', 'musaimo', 'kigio', 'njoroge', 'tony young', 'kioi', 'karwimbo', 'wanjiku', 'muthoni', 'njeri', 'waithaka', 'kamaru'];
    const GIKUYU_WORDS = ['wendo', 'ngoro', 'mwanake', 'ciugo', 'kiriro', 'mutumia', 'ndoto', 'mamaa', 'mugithi', 'kigooco', 'mwathani', 'ngai', 'murata', 'kanyoni'];
    const BONGO_ARTISTS = ['diamond', 'platnumz', 'harmonize', 'rayvanny', 'zuchu', 'mbosso', 'marioo', 'jux', 'aslay', 'nandy', 'kusah', 'barnaba', 'alikiba'];
    const GOSPEL_WORDS = ['gospel', 'worship', 'praise', 'mwema', 'hallelujah', 'amen', 'jesus', 'god', 'kigame', 'masika', 'sinach', 'guardian angel', 'shusho', 'wanjiru'];
    const AFRO_ARTISTS = ['burna', 'wizkid', 'davido', 'rema', 'asake', 'ayra', 'omah lay', 'fireboy', 'tems', 'kizz daniel', 'ruger'];

    if (GIKUYU_ARTISTS.some(a => combined.includes(a)) || GIKUYU_WORDS.some(w => combined.includes(w))) {
      return { genre: 'Gikuyu / Mugithi', language: 'Kikuyu', vibes: ['Acoustic', 'Mugithi', 'Heritage'] };
    }
    if (BONGO_ARTISTS.some(a => combined.includes(a))) {
      return { genre: 'Bongo Flava', language: 'Swahili', vibes: ['Smooth', 'Romantic', 'Dance'] };
    }
    if (GOSPEL_WORDS.some(w => combined.includes(w))) {
      return { genre: 'Gospel', language: 'Swahili/English', vibes: ['Uplifting', 'Worship', 'Praise'] };
    }
    if (AFRO_ARTISTS.some(a => combined.includes(a))) {
      return { genre: 'Afrobeats', language: 'English/Pidgin', vibes: ['High Dopamine', 'Groove'] };
    }
    return { genre: 'Pop / R&B', language: 'English', vibes: ['Melodic', 'Radio'] };
  }

  private offlineConversationalFallback(
    query: string,
    tracks: Array<{ id: string; title: string; artist: string; genre?: string; language?: string }>,
    currentPlaying?: string,
    userName: string = 'Tonny'
  ): {
    matchedTrackId: string | null;
    suggestedTitle: string | null;
    suggestedArtist: string | null;
    spokenText: string;
    action: string;
    genre: string;
  } {
    const q = query.toLowerCase();

    // Check Gikuyu
    if (q.includes('gikuyu') || q.includes('kikuyu') || q.includes('mugithi')) {
      const match = tracks.find(t => {
        const tag = this.offlineHeuristicTag(t.title, t.artist);
        return tag.genre.includes('Gikuyu') || tag.language === 'Kikuyu';
      });
      if (match) {
        return {
          matchedTrackId: match.id,
          suggestedTitle: match.title,
          suggestedArtist: match.artist,
          spokenText: `Playing '${match.title}' by ${match.artist} from your Gikuyu timeline collection.`,
          action: 'PLAY_TRACK',
          genre: 'Gikuyu / Mugithi'
        };
      }
      return {
        matchedTrackId: 'fallback_gikuyu',
        suggestedTitle: 'Wendo Wi Cama',
        suggestedArtist: 'Samidoh & Joyce wa Mamaa',
        spokenText: "Playing 'Wendo Wi Cama' by Samidoh from your Gikuyu favorites.",
        action: 'PLAY_TRACK',
        genre: 'Gikuyu / Mugithi'
      };
    }

    // Check Gospel
    if (q.includes('gospel') || q.includes('worship') || q.includes('praise')) {
      const match = tracks.find(t => {
        const tag = this.offlineHeuristicTag(t.title, t.artist);
        return tag.genre.includes('Gospel');
      });
      if (match) {
        return {
          matchedTrackId: match.id,
          suggestedTitle: match.title,
          suggestedArtist: match.artist,
          spokenText: `Playing '${match.title}' by ${match.artist} for your Gospel worship.`,
          action: 'PLAY_TRACK',
          genre: 'Gospel'
        };
      }
      return {
        matchedTrackId: 'fallback_gospel',
        suggestedTitle: 'Mwema',
        suggestedArtist: 'Mercy Masika',
        spokenText: "Playing 'Mwema' by Mercy Masika for your worship.",
        action: 'PLAY_TRACK',
        genre: 'Gospel'
      };
    }

    // Check Bongo
    if (q.includes('bongo')) {
      const match = tracks.find(t => {
        const tag = this.offlineHeuristicTag(t.title, t.artist);
        return tag.genre.includes('Bongo');
      });
      if (match) {
        return {
          matchedTrackId: match.id,
          suggestedTitle: match.title,
          suggestedArtist: match.artist,
          spokenText: `Playing '${match.title}' by ${match.artist} for your Bongo flow.`,
          action: 'PLAY_TRACK',
          genre: 'Bongo Flava'
        };
      }
      return {
        matchedTrackId: 'fallback_bongo',
        suggestedTitle: 'Sukari',
        suggestedArtist: 'Zuchu',
        spokenText: "Playing 'Sukari' by Zuchu.",
        action: 'PLAY_TRACK',
        genre: 'Bongo Flava'
      };
    }

    // Timeline match
    const candidate = tracks.find(t => t.title && t.title !== currentPlaying) || tracks[0];
    if (candidate) {
      return {
        matchedTrackId: candidate.id,
        suggestedTitle: candidate.title,
        suggestedArtist: candidate.artist,
        spokenText: `Playing '${candidate.title}' by ${candidate.artist} from your timeline.`,
        action: 'PLAY_TRACK',
        genre: candidate.genre || 'Timeline'
      };
    }

    return {
      matchedTrackId: null,
      suggestedTitle: 'Sukari',
      suggestedArtist: 'Zuchu',
      spokenText: "Playing high-dopamine Bongo track for you.",
      action: 'PLAY_TRACK',
      genre: 'Bongo'
    };
  }
}




