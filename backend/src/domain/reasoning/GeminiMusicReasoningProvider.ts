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
  private readonly preferredModels = ['gemini-3.8-flash', 'gemini-2.5-flash', 'gemini-3.1-flash-lite'];
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
}
