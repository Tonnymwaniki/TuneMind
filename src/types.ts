export interface TrackItem {
  id: string;
  title: string;
  artist: string;
  albumCoverUrl: string;
  genre: string;
  bpm: number;
  durationSeconds: number;
  playCount: number;
  satietyScore: number; // 0 - 100
  fatigueCategory: 'Critical Loop' | 'Vocal Fatigue' | 'Loop Decay' | 'Preemptive' | 'Healthy Staple' | string;
  recentSkips?: number;
  sourceApp: string;
  cooldownActive?: boolean;
  recoveryProgress?: number; // 0.0 - 1.0
  cooldownDaysRemaining?: number;
}

export interface AnchorWeight {
  name: string;
  percentage: number;
  driftDelta: string;
  description: string;
}

export interface CircadianWindow {
  title: string;
  timeRange: string;
  receptivityPercent: number;
  descriptor: string;
  isFatigueProne: boolean;
}

export interface DailyStudyTile {
  dayNumber: number;
  dayLabel: string;
  status: 'COMPLETED' | 'IN_PROGRESS' | 'SCHEDULED';
  minutesTracked: number;
  skipsAnalyzed: number;
  entropyScore: number;
}

export interface AnalyticsSummary {
  userId: string;
  totalEvents: number;
  bySource: Record<string, number>;
  byEventType: Record<string, number>;
  timeRange: {
    earliest: string | null;
    latest: string | null;
  };
  sources: string[];
  sessionCreatedEventCount: number;
  distinctSessionCount: number;
  notices: string[];
}

export interface StoredRawEvent {
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

export interface ReconstructedEpisode {
  episodeId: string;
  reconstructionVersion: number;
  userId: string;
  deviceId: string;
  observerSessionId: string;
  sourcePackage: string | null;
  firstObservedAt: string | null;
  lastObservedAt: string | null;
  wallClockSpanMs: number | null;
  startBoundary: string;
  endBoundary: string;
  status: string;
  eventCount: number;
  rawEventIds: string[];
  trackAppearances: {
    appearanceId: string;
    trackId: string | null;
    title: string | null;
    artist: string | null;
    album: string | null;
    durationMs: number | null;
    firstObservedAt: string | null;
    lastObservedAt: string | null;
    observationCount: number;
    identityBasis: string;
    evidenceStatus: string;
  }[];
  hasObservationGap: boolean;
  hasTimestampAmbiguity: boolean;
  notices: string[];
}

export interface ReasoningReport {
  reportId: string;
  userId: string;
  generatedAt: string;
  analyzedEventCount: number;
  timeWindow: {
    start: string | null;
    end: string | null;
  };
  auditoryReceptorStatus: {
    receptivityScore: number;
    state: 'OPTIMAL_FLOW' | 'MILD_SATURATION' | 'ELEVATED_BURNOUT';
    statusLabel: string;
  };
  flowStateNarrative: string;
  acousticShiftRecommendations: Array<{
    shiftType: string;
    recommendation: string;
    targetBpmRange?: string;
    rationale: string;
  }>;
  fatigueSignals: Array<{
    trackKey: string;
    estimatedFatigue: number;
    confidence: number;
    basedOnPlayCount: number;
    completionTrend: string;
  }>;
  probabilisticInferences: Array<{
    type: string;
    description: string;
    confidence: number;
    evidenceSummary: string;
    isFactual: boolean;
  }>;
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
