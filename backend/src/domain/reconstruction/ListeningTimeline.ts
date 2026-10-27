/**
 * ListeningTimeline — clean presentation layer over EpisodeReconstructor.
 *
 * Strips internal raw event IDs from the reconstruction response while
 * preserving all semantic fields needed for a chronological timeline view.
 */

import { EpisodeReconstructor, RECONSTRUCTION_VERSION } from './EpisodeReconstructor';
import type {
  EpisodeBoundaryType,
  EpisodeStatus,
  TrackIdentityBasis,
  TrackAppearanceEvidence,
} from './EpisodeReconstructor';

// ─── Timeline Types (public-facing, no rawEventIds) ───────────────────────────

export interface TimelineTrackAppearance {
  appearanceId: string;
  sourcePackage: string | null;
  trackId: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  firstObservedAt: string | null;
  lastObservedAt: string | null;
  observationCount: number;
  identityBasis: TrackIdentityBasis;
  evidenceStatus: TrackAppearanceEvidence;
}

export interface TimelineEpisode {
  episodeId: string;
  sourcePackage: string | null;
  observerSessionId: string;
  firstObservedAt: string | null;
  lastObservedAt: string | null;
  wallClockSpanMs: number | null;
  startBoundary: EpisodeBoundaryType;
  endBoundary: EpisodeBoundaryType;
  status: EpisodeStatus;
  eventCount: number;
  trackAppearances: TimelineTrackAppearance[];
  hasObservationGap: boolean;
  hasTimestampAmbiguity: boolean;
  usedIsPlayingFallback: boolean;
  notices: string[];
}

export interface TimelineResult {
  userId: string;
  timelineVersion: number;
  episodes: TimelineEpisode[];
  totalEpisodes: number;
  totalEventsProcessed: number;
  totalEventsExcluded: number;
  excludedEmptySessionIdCount: number;
  excludedReason: string[];
  notices: string[];
}

export class ListeningTimeline {
  private reconstructor: EpisodeReconstructor;

  constructor(reconstructor?: EpisodeReconstructor) {
    this.reconstructor = reconstructor ?? new EpisodeReconstructor();
  }

  /**
   * Build a chronological timeline of listening episodes.
   * Delegates reconstruction to EpisodeReconstructor and strips internal IDs.
   */
  build(userId?: string | null): TimelineResult {
    const raw = this.reconstructor.reconstruct(userId);

    const episodes: TimelineEpisode[] = raw.episodes.map(ep => ({
      episodeId: ep.episodeId,
      sourcePackage: ep.sourcePackage,
      observerSessionId: ep.observerSessionId,
      firstObservedAt: ep.firstObservedAt,
      lastObservedAt: ep.lastObservedAt,
      wallClockSpanMs: ep.wallClockSpanMs,
      startBoundary: ep.startBoundary,
      endBoundary: ep.endBoundary,
      status: ep.status,
      eventCount: ep.eventCount,
      trackAppearances: ep.trackAppearances.map(ta => ({
        appearanceId: ta.appearanceId,
        sourcePackage: ta.sourcePackage,
        trackId: ta.trackId,
        title: ta.title,
        artist: ta.artist,
        album: ta.album,
        durationMs: ta.durationMs,
        firstObservedAt: ta.firstObservedAt,
        lastObservedAt: ta.lastObservedAt,
        observationCount: ta.observationCount,
        identityBasis: ta.identityBasis,
        evidenceStatus: ta.evidenceStatus,
      })),
      hasObservationGap: ep.hasObservationGap,
      hasTimestampAmbiguity: ep.hasTimestampAmbiguity,
      usedIsPlayingFallback: ep.usedIsPlayingFallback,
      notices: ep.notices,
    }));

    return {
      userId: raw.userId,
      timelineVersion: RECONSTRUCTION_VERSION,
      episodes,
      totalEpisodes: raw.totalEpisodes,
      totalEventsProcessed: raw.totalEventsProcessed,
      totalEventsExcluded: raw.totalEventsExcluded,
      excludedEmptySessionIdCount: raw.excludedEmptySessionIdCount,
      excludedReason: raw.excludedReason,
      notices: raw.notices,
    };
  }
}
