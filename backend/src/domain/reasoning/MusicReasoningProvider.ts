/**
 * MusicReasoningProvider — plug-in interface for the external AI reasoning layer.
 *
 * Phase 1: Interface definition only. No implementation exists.
 * Phase 3: The actual external reasoning API will be injected here.
 *
 * The AI layer receives structured, deterministically-computed signals from the
 * AnalyticsService and returns probabilistic inferences. It does NOT calculate
 * basic telemetry metrics — those are computed by code:
 *
 *   listening duration     → AnalyticsService (code)
 *   completion percentage  → AnalyticsService (code)
 *   play count             → AnalyticsService (code)
 *   skip position          → AnalyticsService (code)
 *   replay frequency       → AnalyticsService (code)
 *
 * The AI reasons OVER those structured signals, not instead of them.
 */

// ─── Input types ─────────────────────────────────────────────────────────────

export interface ListeningStats {
  userId: string;
  periodStart: string; // ISO 8601
  periodEnd: string; // ISO 8601
  totalListeningMs: number;
  uniqueTracksPlayed: number;
  totalPlayEvents: number;
  totalSkipEvents: number;
  averageCompletionPercent: number;
}

/**
 * The three initial Auditory Anchor dimensions.
 * These are continuously-changing preference signals inferred from behaviour —
 * NOT permanent psychological labels.
 */
export interface AuditoryAnchorSignals {
  userId: string;
  computedAt: string; // ISO 8601
  /** Groove / rhythm preference signal. Range: 0.0–1.0. */
  grooveRhythm: number;
  /** Vocal / melody preference signal. Range: 0.0–1.0. */
  vocalMelody: number;
  /** Drop / structural dynamics preference signal. Range: 0.0–1.0. */
  dropStructuralDynamics: number;
  /** Confidence in the above signals, based on volume of recent evidence. */
  signalConfidence: number;
}

export interface TrackFatigueSignal {
  trackKey: string;
  userId: string;
  estimatedFatigue: number; // 0.0 = no fatigue, 1.0 = fully saturated
  confidence: number;
  basedOnPlayCount: number;
  completionTrend: 'STABLE' | 'DECLINING' | 'RECOVERING';
}

export interface UserMusicContext {
  userId: string;
  recentListeningStats: ListeningStats;
  /** Phase 3: populated once auditory anchor computation is implemented. */
  auditoryAnchorSignals?: AuditoryAnchorSignals;
  /** Phase 3: populated once fatigue signal computation is implemented. */
  fatigueSignals?: TrackFatigueSignal[];
}

// ─── Output types ─────────────────────────────────────────────────────────────

/**
 * A single probabilistic inference.
 *
 * IMPORTANT: [isFactual] is always false. All AI outputs are probabilistic inferences,
 * never facts about the user's psychology or listening behaviour.
 */
export interface ProbabilisticInference {
  type: string; // e.g. "SATURATION_SIGNAL", "RHYTHM_PREFERENCE_SHIFT"
  description: string;
  confidence: number; // 0.0 – 1.0
  evidenceSummary: string;
  /** Permanently false. Inferences are never stored as directly observed facts. */
  readonly isFactual: false;
}

export interface ReasoningResult {
  requestId: string;
  generatedAt: string; // ISO 8601
  probabilisticInferences: ProbabilisticInference[];
  /** Raw provider response preserved for debugging. Not exposed to clients. */
  rawProviderResponse?: unknown;
}

export interface AuditoryReceptorStatus {
  receptivityScore: number; // 0-100%
  state: 'OPTIMAL_FLOW' | 'MILD_SATURATION' | 'ELEVATED_BURNOUT';
  statusLabel: string; // e.g. "Optimal Cognitive Flow"
}

export interface AcousticShiftRecommendation {
  shiftType: 'TEMPO_ADAPTATION' | 'TIMBRE_CLEANSE' | 'GENRE_VARIATION' | 'SILENCE_INTERVAL';
  recommendation: string;
  targetBpmRange?: string;
  rationale: string;
}

export interface ReasoningReport {
  reportId: string;
  userId: string;
  generatedAt: string; // ISO 8601
  analyzedEventCount: number;
  timeWindow: {
    start: string | null;
    end: string | null;
  };
  auditoryReceptorStatus: AuditoryReceptorStatus;
  flowStateNarrative: string;
  acousticShiftRecommendations: AcousticShiftRecommendation[];
  fatigueSignals: TrackFatigueSignal[];
  probabilisticInferences: ProbabilisticInference[];
  anchorsAssessed: {
    grooveRhythm: number;
    vocalMelody: number;
    dropStructuralDynamics: number;
    confidence: number;
  };
  keyObservedTracks: Array<{
    title: string;
    artist: string;
    playCount: number;
    estimatedSatietyPercent: number;
    fatigueRisk: 'LOW' | 'MODERATE' | 'HIGH';
  }>;
  isAiGenerated: boolean;
  modelUsed?: string;
}

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * Plug-in interface for the external AI reasoning provider.
 *
 * Implemented by GeminiMusicReasoningProvider using @google/genai.
 */
export interface MusicReasoningProvider {
  readonly providerId: string;
  isAvailable(): Promise<boolean>;
  reason(context: UserMusicContext): Promise<ReasoningResult>;
  generateReport?(context: UserMusicContext, rawEventsSummary?: string): Promise<ReasoningReport>;
}
