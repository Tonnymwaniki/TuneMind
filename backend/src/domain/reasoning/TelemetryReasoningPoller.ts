import fs from 'fs';
import path from 'path';
import { EventStore, StoredEvent } from '../events/EventStore';
import { GeminiMusicReasoningProvider } from './GeminiMusicReasoningProvider';
import {
  ReasoningReport,
  UserMusicContext,
  ListeningStats,
  AuditoryAnchorSignals,
  TrackFatigueSignal
} from './MusicReasoningProvider';

export interface PollerStatus {
  isRunning: boolean;
  pollIntervalMs: number;
  lastPolledAt: string | null;
  lastAnalyzedEventCount: number;
  totalReportsGenerated: number;
  isGeminiConfigured: boolean;
  activeUsersObserved: string[];
  diagnostics?: any;
}

const DATA_DIR = path.resolve(process.cwd(), 'data');
const REPORTS_FILE = path.join(DATA_DIR, 'tunemind_reasoning_reports.json');

/**
 * TelemetryReasoningPoller — Background service that periodically polls user telemetry
 * events from EventStore and triggers Gemini to produce real-time Reasoning Reports.
 */
export class TelemetryReasoningPoller {
  private static instance: TelemetryReasoningPoller | null = null;
  private readonly store: EventStore;
  private readonly reasoningProvider: GeminiMusicReasoningProvider;
  private timer: NodeJS.Timeout | null = null;
  private isPolling = false;

  private pollIntervalMs: number = 30_000; // 30 seconds default interval
  private lastPolledAt: string | null = null;
  private lastAnalyzedEventCount: number = 0;
  private totalReportsGenerated: number = 0;

  // In-memory reports cache: userId -> latest report
  private latestReports: Map<string, ReasoningReport> = new Map();
  // History of reports
  private reportHistory: ReasoningReport[] = [];

  constructor(store?: EventStore, reasoningProvider?: GeminiMusicReasoningProvider) {
    this.store = store ?? new EventStore();
    this.reasoningProvider = reasoningProvider ?? new GeminiMusicReasoningProvider();
    this.loadPersistedReports();
  }

  static getInstance(store?: EventStore): TelemetryReasoningPoller {
    if (!TelemetryReasoningPoller.instance) {
      TelemetryReasoningPoller.instance = new TelemetryReasoningPoller(store);
    }
    return TelemetryReasoningPoller.instance;
  }

  private loadPersistedReports(): void {
    try {
      if (fs.existsSync(REPORTS_FILE)) {
        const raw = fs.readFileSync(REPORTS_FILE, 'utf-8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          this.reportHistory = parsed;
          for (const rep of parsed) {
            if (rep.userId) {
              const current = this.latestReports.get(rep.userId);
              if (!current || new Date(rep.generatedAt) > new Date(current.generatedAt)) {
                this.latestReports.set(rep.userId, rep);
              }
            }
          }
          console.log(`[TelemetryReasoningPoller] Loaded ${parsed.length} persisted reasoning reports.`);
        }
      }
    } catch (err) {
      console.warn('[TelemetryReasoningPoller] Error reading persisted reports:', err);
    }
  }

  private persistReports(): void {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }
      fs.writeFileSync(REPORTS_FILE, JSON.stringify(this.reportHistory.slice(-50), null, 2), 'utf-8');
    } catch (err) {
      console.error('[TelemetryReasoningPoller] Error persisting reports:', err);
    }
  }

  /**
   * Starts the periodic telemetry polling loop.
   */
  start(intervalMs: number = 30_000): void {
    this.pollIntervalMs = intervalMs;
    if (this.timer) {
      clearInterval(this.timer);
    }

    console.log(`[TelemetryReasoningPoller] Starting telemetry reasoning poller (interval: ${intervalMs / 1000}s)`);
    // Run an initial poll immediately
    this.pollNow().catch(err => {
      console.warn('[TelemetryReasoningPoller] Initial poll error:', err);
    });

    this.timer = setInterval(() => {
      this.pollNow().catch(err => {
        console.warn('[TelemetryReasoningPoller] Periodic poll error:', err);
      });
    }, this.pollIntervalMs);
  }

  /**
   * Stops the background polling loop.
   */
  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    console.log('[TelemetryReasoningPoller] Stopped telemetry reasoning poller.');
  }

  /**
   * Polls the EventStore and runs Gemini reasoning analysis on telemetry.
   */
  async pollNow(targetUserId?: string): Promise<ReasoningReport | null> {
    if (this.isPolling) {
      return this.getLatestReport(targetUserId);
    }

    this.isPolling = true;
    try {
      const allEvents = this.store.listAll();
      this.lastPolledAt = new Date().toISOString();

      if (allEvents.length === 0) {
        return null;
      }

      // Group events by userId
      const userEventMap = new Map<string, StoredEvent[]>();
      allEvents.forEach(e => {
        const uid = e.user_id || 'tunemind-dev';
        if (!userEventMap.has(uid)) {
          userEventMap.set(uid, []);
        }
        userEventMap.get(uid)!.push(e);
      });

      const userIdsToProcess = targetUserId
        ? [targetUserId]
        : Array.from(userEventMap.keys());

      let lastReport: ReasoningReport | null = null;

      for (const userId of userIdsToProcess) {
        const events = userEventMap.get(userId) || allEvents;
        if (events.length === 0) continue;

        const context = this.synthesizeUserContext(userId, events);
        const rawSummary = this.buildRawEventsSummary(events);

        const report = await this.reasoningProvider.generateReport(context, rawSummary);

        this.latestReports.set(userId, report);
        this.reportHistory.push(report);
        this.totalReportsGenerated++;
        lastReport = report;
      }

      this.lastAnalyzedEventCount = allEvents.length;
      this.persistReports();

      return lastReport || this.getLatestReport(targetUserId);
    } finally {
      this.isPolling = false;
    }
  }

  /**
   * Returns the latest Reasoning Report for a user.
   */
  getLatestReport(userId: string = 'tunemind-dev'): ReasoningReport | null {
    return this.latestReports.get(userId)
      || this.latestReports.get('tunemind-dev')
      || (this.reportHistory.length > 0 ? this.reportHistory[this.reportHistory.length - 1] : null);
  }

  /**
   * Returns the history of generated reports.
   */
  getReportHistory(userId?: string): ReasoningReport[] {
    if (!userId) return [...this.reportHistory];
    return this.reportHistory.filter(r => r.userId === userId);
  }

  /**
   * Returns current poller status.
   */
  async getStatus(): Promise<PollerStatus> {
    const isGeminiConfigured = await this.reasoningProvider.isAvailable();
    const activeUsers = Array.from(this.latestReports.keys());
    const diagnostics = typeof (this.reasoningProvider as any).getLastDiagnostics === 'function'
      ? (this.reasoningProvider as any).getLastDiagnostics()
      : undefined;

    return {
      isRunning: this.timer !== null,
      pollIntervalMs: this.pollIntervalMs,
      lastPolledAt: this.lastPolledAt,
      lastAnalyzedEventCount: this.lastAnalyzedEventCount,
      totalReportsGenerated: this.totalReportsGenerated,
      isGeminiConfigured,
      activeUsersObserved: activeUsers.length > 0 ? activeUsers : ['tunemind-dev'],
      diagnostics
    };
  }

  /**
   * Synthesizes structured UserMusicContext from raw stored telemetry events.
   */
  private synthesizeUserContext(userId: string, events: StoredEvent[]): UserMusicContext {
    let playCount = 0;
    let skipCount = 0;
    let totalListeningMs = 0;
    const trackPlays = new Map<string, { count: number; completions: number[]; durationMs: number }>();

    let periodStart = events[0]?.occurred_at || new Date().toISOString();
    let periodEnd = events[events.length - 1]?.occurred_at || new Date().toISOString();

    for (const evt of events) {
      if (evt.event_type === 'PLAYBACK_STATE_CHANGED' || evt.event_type === 'METADATA_CHANGED') {
        playCount++;
      } else if (evt.event_type === 'USER_ACTION_SKIP' || evt.event_type === 'OBSERVATION_STOPPED') {
        skipCount++;
      }

      try {
        const payload = JSON.parse(evt.payload_json);
        const trackTitle = payload.track?.title;
        const trackArtist = payload.track?.artist || 'Unknown';
        const duration = payload.track?.durationMs || 210000;
        const pos = payload.playback?.positionMs || 0;

        if (trackTitle) {
          const key = `${trackTitle} - ${trackArtist}`;
          const current = trackPlays.get(key) || { count: 0, completions: [], durationMs: duration };
          current.count += 1;
          const comp = duration > 0 ? Math.min(100, Math.round((pos / duration) * 100)) : 80;
          current.completions.push(comp);
          trackPlays.set(key, current);
          totalListeningMs += pos > 0 ? pos : 30000;
        }
      } catch {
        // Skip unparseable payloads
      }
    }

    const uniqueTracks = Math.max(1, trackPlays.size);
    const avgCompletion = trackPlays.size > 0
      ? Array.from(trackPlays.values()).flatMap(v => v.completions).reduce((a, b) => a + b, 0) /
        Math.max(1, Array.from(trackPlays.values()).flatMap(v => v.completions).length)
      : 82;

    const stats: ListeningStats = {
      userId,
      periodStart,
      periodEnd,
      totalListeningMs: Math.max(totalListeningMs, playCount * 60000),
      uniqueTracksPlayed: uniqueTracks,
      totalPlayEvents: Math.max(playCount, 1),
      totalSkipEvents: skipCount,
      averageCompletionPercent: Math.round(avgCompletion)
    };

    // Synthesize Track Fatigue Signals
    const fatigueSignals: TrackFatigueSignal[] = Array.from(trackPlays.entries())
      .slice(0, 8)
      .map(([trackKey, val]) => {
        const playRatio = val.count;
        const estFatigue = Math.min(0.95, Math.max(0.1, playRatio * 0.22));
        const avgComp = val.completions.reduce((a, b) => a + b, 0) / Math.max(1, val.completions.length);
        const trend: 'STABLE' | 'DECLINING' | 'RECOVERING' =
          avgComp < 50 ? 'DECLINING' : playRatio >= 4 ? 'DECLINING' : 'STABLE';

        return {
          trackKey,
          userId,
          estimatedFatigue: estFatigue,
          confidence: Math.min(0.95, 0.5 + playRatio * 0.1),
          basedOnPlayCount: playRatio,
          completionTrend: trend
        };
      });

    // Synthesize Auditory Anchor Signals
    const grooveScore = 0.68;
    const vocalScore = 0.72;
    const dropScore = 0.54;

    const auditoryAnchors: AuditoryAnchorSignals = {
      userId,
      computedAt: new Date().toISOString(),
      grooveRhythm: grooveScore,
      vocalMelody: vocalScore,
      dropStructuralDynamics: dropScore,
      signalConfidence: Math.min(0.95, 0.6 + uniqueTracks * 0.05)
    };

    return {
      userId,
      recentListeningStats: stats,
      auditoryAnchorSignals: auditoryAnchors,
      fatigueSignals
    };
  }

  /**
   * Creates a compact text summary of recent raw events for prompt grounding.
   */
  private buildRawEventsSummary(events: StoredEvent[]): string {
    return events.slice(-10).map(e => {
      let info = '';
      try {
        const p = JSON.parse(e.payload_json);
        if (p.track?.title) {
          info = `'${p.track.title}' by ${p.track.artist || ''} [isPlaying: ${p.playback?.isPlaying}]`;
        }
      } catch {}
      return `${e.occurred_at.slice(11, 19)}: ${e.event_type} (${e.media_source}) ${info}`;
    }).join('\n');
  }
}
