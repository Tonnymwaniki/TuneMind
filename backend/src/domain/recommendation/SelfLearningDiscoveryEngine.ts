import fs from 'fs';
import os from 'os';
import path from 'path';
import Anthropic from '@anthropic-ai/sdk';
import { getLiveSupabaseClient } from '../../config/supabase';

export type RecommendationOutcomeType =
  | 'PENDING'
  | 'PLAYED'
  | 'COMPLETED'
  | 'SAVED'
  | 'SKIPPED'
  | 'DISMISSED'
  | 'IGNORED';

export interface CrossAppTrackSignal {
  title: string;
  artist: string;
  genre?: string;
  playCount?: number;
  listenedMs?: number;
  durationMs?: number;
  completionRatio?: number;
  volumeRatio?: number;
  timeSlot?: string;
  sourceApp?: string;
}

export interface DiscoveryFeedbackEvent {
  userId?: string;
  title: string;
  artist: string;
  genre?: string;
  subTier?: string;
  outcome: RecommendationOutcomeType;
  listenedMs?: number;
  durationMs?: number;
  volumeRatio?: number;
  timeSlot?: string;
  dropDate?: string;
}

export interface GenreWeightEntry {
  genre: string;
  alpha: number; // Bayesian successes + prior
  beta: number;  // Bayesian misses/skips + prior
  emaScore: number; // Exponential Moving Average score [0.05..0.99]
  plays: number;
  completions: number;
  skips: number;
  dismissals: number;
  avgVolumeRatio: number;
  avgCompletionRatio: number;
  lastUpdatedMs: number;
}

export interface DailyDropRecord {
  dropDate: string;
  generatedAtMs: number;
  recommendations: ScoredDiscoveryTrack[];
}

export interface UserTasteModel {
  userId: string;
  learningIterations: number;
  evaluatedOutcomes: number;
  positiveOutcomes: number;
  genreWeights: Record<string, GenreWeightEntry>;
  timeSlotGenreAffinity: Record<string, Record<string, number>>;
  volumeByTimeSlot: Record<string, number>;
  recentDrops: DailyDropRecord[];
  updatedAtMs: number;
}

export interface LearningSummary {
  learningIterations: number;
  evaluatedOutcomes: number;
  hitRatePercent: number;
  risingGenres: string[];
  coolingGenres: string[];
  avgVolumePercent: number;
  narrative: string;
}

export interface ScoredDiscoveryTrack {
  title: string;
  artist: string;
  album: string;
  genre: string;
  category: string;
  subTier: string;
  reason: string;
  confidence: number;
  mlScore: number;
  isWildcard: boolean;
  previewUrl?: string;
  albumArtUrl?: string;
  durationMs?: number;
}

export interface DailyDropRequest {
  userId?: string;
  userEmail?: string;
  dropDate?: string;
  timeSlot?: string;
  satietyLevel?: number;
  preferredProvider?: string;
  avgVolumeRatio?: number;
  topArtists?: string[];
  userTasteAnchors?: string[];
  genreDistribution?: Record<string, number>;
  recentTracks?: CrossAppTrackSignal[];
  excludeTitles?: string[];
  yesterdayOutcomes?: DiscoveryFeedbackEvent[];
  playedFeedback?: string[];
  dismissedFeedback?: string[];
  supabaseUrl?: string;
  supabaseKey?: string;
}

export interface DailyDropResponse {
  status: 'ok';
  dropDate: string;
  isAiGenerated: boolean;
  modelUsed: string;
  learningSummary: LearningSummary;
  genreAllocation: Array<{ genre: string; slots: number; score: number }>;
  recommendations: ScoredDiscoveryTrack[];
}

const DEFAULT_FALLBACK_GENRES = ['Afrobeats', 'Bongo Flava', 'Hip-Hop', 'R&B', 'Pop'];

const ARTIST_GENRE_HINTS: Array<{ keywords: string[]; genre: string }> = [
  { keywords: ['mbosso', 'diamond', 'platnumz', 'harmonize', 'zuchu', 'marioo', 'rayvanny', 'alikiba', 'jux', 'nandy', 'otile', 'lavalava', 'kusah'], genre: 'Bongo Flava' },
  { keywords: ['burna', 'wizkid', 'rema', 'asake', 'ayra', 'tems', 'omah lay', 'davido', 'ckay', 'fireboy', 'tiwa', 'kizz daniel', 'victony', 'oxlade', 'ruger', 'buju', 'bnxn', 'olamide', 'shallipopi'], genre: 'Afrobeats' },
  { keywords: ['tyler icu', 'kabza', 'dj maphorisa', 'uncle waffles', 'tyla', 'sha sha', 'vigro', 'focalistic', 'kelvin momo', 'musa keys'], genre: 'Amapiano' },
  { keywords: ['kendrick', 'drake', 'j. cole', 'cole', 'travis scott', '21 savage', 'future', 'metro boomin', 'kanye', 'eminem', 'nas', 'baby keem', 'jid', 'lil baby', 'gunna', 'central cee', 'nyashinski', 'khaligraph'], genre: 'Hip-Hop' },
  { keywords: ['sza', 'frank ocean', 'the weeknd', 'weeknd', 'brent faiyaz', 'h.e.r.', 'daniel caesar', 'giveon', 'summer walker', 'khalid', 'ariana', 'chris brown', 'usher', 'bruno mars', 'chloe'], genre: 'R&B' },
  { keywords: ['maverick city', 'kirk franklin', 'tasha cobbs', 'sinach', 'mercy chinwo', 'nathaniel bassey', 'elevation worship', 'hillsong', 'dunsin'], genre: 'Gospel' },
  { keywords: ['dua lipa', 'taylor swift', 'billie eilish', 'sabrina carpenter', 'olivia rodrigo', 'harry styles', 'ed sheeran', 'doja cat', 'justin bieber', 'post malone'], genre: 'Pop' },
  { keywords: ['burnaboy', 'popcaan', 'vibes kartel', 'shenseea', 'koffee', 'chronixx', 'bob marley', 'sean paul', 'alkaline'], genre: 'Dancehall & Reggae' },
  { keywords: ['fally ipupa', 'koffi olomide', 'innoss', 'ferre gola', 'dadju', 'aya nakamura', 'maitre gims', 'tayc'], genre: 'Afro-Francophone' },
  { keywords: ['bad bunny', 'karol g', 'feid', 'rauw alejandro', 'rosalia', 'anitta'], genre: 'Latin & Urbano' },
  { keywords: ['fred again', 'kaytranada', 'disclosure', 'calvin harris', 'rufus du sol', 'black coffee'], genre: 'Electronic & Afro House' }
];

const CURATED_GLOBAL_DISCOVERY_BANK: Array<ScoredDiscoveryTrack> = [
  // Afrobeats
  { title: 'Kese (Dance)', artist: 'Wizkid', album: 'Morayo', genre: 'Afrobeats', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Velvety Lagos percussion and hypnotic bassline crafted for effortless repeat listening.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'MMS', artist: 'Asake & Wizkid', album: 'Lungu Boy', genre: 'Afrobeats', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Warm brass accents and smooth mid-tempo Afro-fusion groove.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Commas', artist: 'Ayra Starr', album: 'The Year I Turned 21', genre: 'Afrobeats', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Crisp vocal pocket and uplifting percussive bounce.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Tshwala Bam', artist: 'TitoM, Yuppe & Burna Boy', album: 'Tshwala Bam Remix', genre: 'Afrobeats', category: 'NOVEL_EXPLORATION', subTier: 'GENRE_PICK', reason: 'High-energy crossover groove blending West and Southern African rhythms.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Bongo Flava
  { title: 'Nitaubeba', artist: 'Harmonize', album: 'Made For Us', genre: 'Bongo Flava', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Heartfelt Swahili guitar phrasing with warm acoustic dynamics.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'Mapenzi', artist: 'Marioo', album: 'The Godson', genre: 'Bongo Flava', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Lush East African Afro-pop production built for mellow focus.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Shu!', artist: 'Diamond Platnumz & Chley', album: 'First Of All', genre: 'Bongo Flava', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Infectious Bongo-Amapiano hybrid pulse with crisp log drums.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Honey', artist: 'Zuchu', album: 'Peace and Money', genre: 'Bongo Flava', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Silky Swahili vocals over airy guitar chords to refresh ear fatigue.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Amapiano
  { title: 'Push 2 Start', artist: 'Tyla', album: 'TYLA +', genre: 'Amapiano', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Popiano groove with crisp shaker pockets and smooth vocal swing.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'Kwelinye', artist: 'Kelvin Momo & Babalwa M', album: 'Kurhula', genre: 'Amapiano', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Deep private-school Amapiano chords with soothing atmospheric warmth.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Imithandazo', artist: 'Kabza De Small & Mthunzi', album: 'Isimo', genre: 'Amapiano', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Soulful choral harmonies anchored by signature Johannesburg log drums.', confidence: 0.93, mlScore: 0.93, isWildcard: false },

  // Hip-Hop
  { title: 'luther', artist: 'Kendrick Lamar & SZA', album: 'GNX', genre: 'Hip-Hop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Warm vintage soul sample balanced with effortless vocal chemistry.', confidence: 0.95, mlScore: 0.95, isWildcard: false },
  { title: '30 for 30', artist: 'SZA & Kendrick Lamar', album: 'SOS Deluxe: LANA', genre: 'Hip-Hop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Crisp pocket bounce blending sharp lyricism and lush melodies.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'cLOUDs', artist: 'J. Cole', album: 'cLOUDs', genre: 'Hip-Hop', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Introspective boom-bap textures ideal for high-focus listening.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'BAND4BAND', artist: 'Central Cee & Lil Baby', album: 'CAN\'T RUSH GREATNESS', genre: 'Hip-Hop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Clean high-volume drill cadence with punchy low-end clarity.', confidence: 0.90, mlScore: 0.90, isWildcard: false },

  // R&B
  { title: 'BMF', artist: 'SZA', album: 'LANA', genre: 'R&B', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Sunlit bossa-inspired R&B pocket that refreshes saturated listening queues.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Pistachios', artist: 'Brent Faiyaz', album: 'Larger Than Life', genre: 'R&B', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Velvet nocturnal vocals with sparse bass warmth.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Wait For Me', artist: 'Giveon', album: 'Give Or Take', genre: 'R&B', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Rich baritone delivery and classic analog chord progressions.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Pop
  { title: 'Birds of a Feather', artist: 'Billie Eilish', album: 'HIT ME HARD AND SOFT', genre: 'Pop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Euphoric indie-pop lift with crisp stereo shimmer.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'Espresso', artist: 'Sabrina Carpenter', album: 'Short n\' Sweet', genre: 'Pop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Upbeat funk-pop groove built for morning momentum.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Timeless', artist: 'The Weeknd & Playboi Carti', album: 'Hurry Up Tomorrow', genre: 'Pop', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Wide synth atmosphere engineered for loud immersive playback.', confidence: 0.93, mlScore: 0.93, isWildcard: false },

  // Gospel
  { title: 'Firm Foundation (He Won\'t)', artist: 'Maverick City Music', album: 'The Maverick Way', genre: 'Gospel', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Expansive uplifting harmonies that restore calm clarity.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Olorun Agbaye', artist: 'Nathaniel Bassey', album: 'Hallelujah Again', genre: 'Gospel', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Resonant horn and choir swells for deep reflective listening.', confidence: 0.92, mlScore: 0.92, isWildcard: false },

  // Global Frontier Wildcards (worldwide sounds)
  { title: 'N\'y Pense Plus', artist: 'Tayc', album: 'Fleur Froide', genre: 'Afro-Francophone', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Parisian Afro-lovin grooves merging silky French vocals with warm African percussion.', confidence: 0.91, mlScore: 0.91, isWildcard: true },
  { title: 'Morena', artist: 'Kaytranada & Kali Uchis', album: 'TIMELESS', genre: 'Electronic & Afro House', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Montreal-Colombian bounce bridging R&B warmth and crisp dancefloor syncopation.', confidence: 0.92, mlScore: 0.92, isWildcard: true },
  { title: 'DtMF', artist: 'Bad Bunny', album: 'DeBÍ TiRAR MáS FOToS', genre: 'Latin & Urbano', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Nostalgic Puerto Rican plena rhythms meeting modern bass weight.', confidence: 0.91, mlScore: 0.91, isWildcard: true },
  { title: 'Blooming', artist: 'Koffee', album: 'Gifted', genre: 'Dancehall & Reggae', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Sun-soaked Jamaican acoustic groove for an instant sonic reset.', confidence: 0.90, mlScore: 0.90, isWildcard: true },
  { title: 'You\'ve Got It', artist: 'Vedo', album: 'For You', genre: 'R&B', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Late-night slow-jam minimalism tuned for mellow headphones focus.', confidence: 0.89, mlScore: 0.89, isWildcard: true },
  { title: 'Drive Me Crazy', artist: 'Black Coffee & Msaki', album: 'Subconsciously', genre: 'Electronic & Afro House', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'World Discovery: Deep South African house atmosphere crafted for long flow sessions.', confidence: 0.92, mlScore: 0.92, isWildcard: true }
];

export class SelfLearningDiscoveryEngine {
  private static storePath = path.join(os.tmpdir(), 'tunemind-ml-taste-store.json');
  private static memoryCache: Map<string, UserTasteModel> = new Map();
  private static loadedDisk = false;
  private anthropic: Anthropic | null = null;

  constructor() {
    const key = (process.env.ANTHROPIC_API_KEY || '').trim();
    if (key.length > 10 && !key.startsWith('your_')) {
      this.anthropic = new Anthropic({ apiKey: key });
    }
  }

  public static normalizeUserId(userId?: string, userEmail?: string): string {
    const raw = (userId || userEmail || 'tunemind-default-user').trim().toLowerCase();
    return raw.replace(/[^a-z0-9@._-]/g, '_') || 'tunemind-default-user';
  }

  public static getTodayDateKey(now = new Date()): string {
    return now.toISOString().slice(0, 10);
  }

  public static inferGenre(title: string, artist: string, explicitGenre?: string): string {
    if (explicitGenre && explicitGenre.trim().length > 1 && explicitGenre.toLowerCase() !== 'unknown') {
      return this.canonicalGenreName(explicitGenre);
    }
    const combined = `${artist} ${title}`.toLowerCase();
    for (const rule of ARTIST_GENRE_HINTS) {
      if (rule.keywords.some((k) => combined.includes(k))) {
        return rule.genre;
      }
    }
    return 'Pop & Global';
  }

  public static canonicalGenreName(raw: string): string {
    const lower = raw.trim().toLowerCase();
    if (lower.includes('bongo') || lower.includes('swahili')) return 'Bongo Flava';
    if (lower.includes('amapiano')) return 'Amapiano';
    if (lower.includes('afro') && (lower.includes('house') || lower.includes('electronic'))) return 'Electronic & Afro House';
    if (lower.includes('afro') && lower.includes('franc')) return 'Afro-Francophone';
    if (lower.includes('afro')) return 'Afrobeats';
    if (lower.includes('hip') || lower.includes('rap') || lower.includes('trap') || lower.includes('drill')) return 'Hip-Hop';
    if (lower.includes('r&b') || lower.includes('rnb') || lower.includes('soul')) return 'R&B';
    if (lower.includes('gospel') || lower.includes('worship')) return 'Gospel';
    if (lower.includes('latin') || lower.includes('reggaeton') || lower.includes('urbano')) return 'Latin & Urbano';
    if (lower.includes('reggae') || lower.includes('dancehall')) return 'Dancehall & Reggae';
    if (lower.includes('pop')) return 'Pop';
    return raw.trim();
  }

  private static loadDiskCacheOnce(): void {
    if (this.loadedDisk) return;
    this.loadedDisk = true;
    try {
      if (fs.existsSync(this.storePath)) {
        const parsed = JSON.parse(fs.readFileSync(this.storePath, 'utf8'));
        if (parsed && typeof parsed === 'object') {
          for (const [uid, model] of Object.entries(parsed)) {
            this.memoryCache.set(uid, model as UserTasteModel);
          }
        }
      }
    } catch {
      // Non-fatal fallback
    }
  }

  private static persistDiskCache(): void {
    try {
      const obj: Record<string, UserTasteModel> = {};
      for (const [k, v] of this.memoryCache.entries()) {
        obj[k] = v;
      }
      fs.writeFileSync(this.storePath, JSON.stringify(obj), 'utf8');
    } catch {
      // Non-fatal on ephemeral filesystems
    }
  }

  public async getOrCreateModel(
    userId: string,
    supabaseOverride?: { supabaseUrl?: string; supabaseKey?: string }
  ): Promise<UserTasteModel> {
    SelfLearningDiscoveryEngine.loadDiskCacheOnce();
    const existing = SelfLearningDiscoveryEngine.memoryCache.get(userId);
    if (existing) return existing;

    const sb = getLiveSupabaseClient(supabaseOverride?.supabaseUrl, supabaseOverride?.supabaseKey);
    if (sb) {
      try {
        const { data } = await sb
          .from('ml_user_taste_models')
          .select('*')
          .eq('user_id', userId)
          .maybeSingle();
        if (data) {
          const hydrated: UserTasteModel = {
            userId,
            learningIterations: Number(data.learning_iterations || 0),
            evaluatedOutcomes: Number(data.evaluated_outcomes || 0),
            positiveOutcomes: Number(data.positive_outcomes || 0),
            genreWeights: (data.genre_weights as Record<string, GenreWeightEntry>) || {},
            timeSlotGenreAffinity: (data.time_slot_affinity as Record<string, Record<string, number>>) || {},
            volumeByTimeSlot: (data.volume_profile as Record<string, number>) || {},
            recentDrops: [],
            updatedAtMs: Date.now()
          };
          SelfLearningDiscoveryEngine.memoryCache.set(userId, hydrated);
          return hydrated;
        }
      } catch {
        // Table may not exist in older Supabase projects; continue with disk/memory store
      }
    }

    const fresh: UserTasteModel = {
      userId,
      learningIterations: 0,
      evaluatedOutcomes: 0,
      positiveOutcomes: 0,
      genreWeights: {},
      timeSlotGenreAffinity: {},
      volumeByTimeSlot: {},
      recentDrops: [],
      updatedAtMs: Date.now()
    };
    SelfLearningDiscoveryEngine.memoryCache.set(userId, fresh);
    return fresh;
  }

  private async saveModel(
    model: UserTasteModel,
    supabaseOverride?: { supabaseUrl?: string; supabaseKey?: string }
  ): Promise<void> {
    model.updatedAtMs = Date.now();
    SelfLearningDiscoveryEngine.memoryCache.set(model.userId, model);
    SelfLearningDiscoveryEngine.persistDiskCache();

    const sb = getLiveSupabaseClient(supabaseOverride?.supabaseUrl, supabaseOverride?.supabaseKey);
    if (sb) {
      try {
        await sb.from('ml_user_taste_models').upsert({
          user_id: model.userId,
          learning_iterations: model.learningIterations,
          evaluated_outcomes: model.evaluatedOutcomes,
          positive_outcomes: model.positiveOutcomes,
          genre_weights: model.genreWeights,
          time_slot_affinity: model.timeSlotGenreAffinity,
          volume_profile: model.volumeByTimeSlot,
          updated_at: new Date(model.updatedAtMs).toISOString()
        });
      } catch {
        // Safe non-blocking fallback if table wasn't provisioned yet
      }
    }
  }

  private ensureGenreEntry(model: UserTasteModel, genre: string): GenreWeightEntry {
    if (!model.genreWeights[genre]) {
      model.genreWeights[genre] = {
        genre,
        alpha: 2.0, // Neutral Beta(2,2) prior -> 0.50 baseline
        beta: 2.0,
        emaScore: 0.55,
        plays: 0,
        completions: 0,
        skips: 0,
        dismissals: 0,
        avgVolumeRatio: 0.65,
        avgCompletionRatio: 0.70,
        lastUpdatedMs: Date.now()
      };
    }
    return model.genreWeights[genre];
  }

  /**
   * Online Contextual Reinforcement Update:
   * Evaluates explicit recommendation outcomes + passive cross-app listening behavior
   * (play frequency, listen completion %, volume ratio, time of day).
   */
  public applyOutcomeUpdate(model: UserTasteModel, event: DiscoveryFeedbackEvent): void {
    const genre = SelfLearningDiscoveryEngine.inferGenre(event.title, event.artist, event.genre);
    const entry = this.ensureGenreEntry(model, genre);
    const timeSlot = event.timeSlot || 'Evening';
    const duration = Math.max(0, Number(event.durationMs || 0));
    const listened = Math.max(0, Number(event.listenedMs || 0));
    const completion =
      duration > 0 && listened > 0
        ? Math.min(1.0, Math.max(0.05, listened / duration))
        : event.outcome === 'COMPLETED'
          ? 0.95
          : event.outcome === 'SKIPPED'
            ? 0.12
            : event.outcome === 'DISMISSED'
              ? 0.05
              : event.outcome === 'IGNORED'
                ? 0.20
                : 0.75;

    const volRatio =
      typeof event.volumeRatio === 'number' && event.volumeRatio > 0
        ? Math.min(1.0, Math.max(0.05, event.volumeRatio))
        : entry.avgVolumeRatio;

    // Compute scalar reward in [-1.0, +1.5]
    let reward = 0;
    let isPositive = false;
    switch (event.outcome) {
      case 'SAVED':
        reward = 1.35 + (volRatio >= 0.65 ? 0.15 : 0);
        isPositive = true;
        entry.plays += 1;
        entry.completions += 1;
        break;
      case 'COMPLETED':
        reward = 1.15 + (volRatio >= 0.60 ? 0.20 : 0.05);
        isPositive = true;
        entry.plays += 1;
        entry.completions += 1;
        break;
      case 'PLAYED':
        if (completion >= 0.65) {
          reward = 0.95 + (volRatio >= 0.65 ? 0.2 : 0);
          isPositive = true;
          entry.completions += 1;
        } else if (completion <= 0.22 && listened > 0) {
          reward = -0.55;
          entry.skips += 1;
        } else {
          reward = 0.60;
          isPositive = true;
        }
        entry.plays += 1;
        break;
      case 'SKIPPED':
        reward = -0.65;
        entry.skips += 1;
        break;
      case 'DISMISSED':
        reward = -0.85;
        entry.dismissals += 1;
        break;
      case 'IGNORED':
        reward = -0.20;
        break;
      default:
        return;
    }

    // Beta posterior + EMA update
    if (reward > 0) {
      entry.alpha = Math.min(80, entry.alpha + reward);
      entry.emaScore = Math.min(0.99, entry.emaScore * 0.78 + Math.min(0.99, 0.65 + reward * 0.22) * 0.22);
    } else {
      entry.beta = Math.min(80, entry.beta + Math.abs(reward));
      entry.emaScore = Math.max(0.08, entry.emaScore * 0.78 + Math.max(0.10, 0.45 + reward * 0.30) * 0.22);
    }

    entry.avgVolumeRatio = entry.avgVolumeRatio * 0.75 + volRatio * 0.25;
    entry.avgCompletionRatio = entry.avgCompletionRatio * 0.75 + completion * 0.25;
    entry.lastUpdatedMs = Date.now();

    // Update timeSlot -> genre affinity matrix
    if (!model.timeSlotGenreAffinity[timeSlot]) {
      model.timeSlotGenreAffinity[timeSlot] = {};
    }
    const prevSlotAffinity = model.timeSlotGenreAffinity[timeSlot][genre] ?? 0.5;
    const slotTarget = reward > 0 ? Math.min(0.99, 0.65 + reward * 0.22) : Math.max(0.08, 0.35 + reward * 0.22);
    model.timeSlotGenreAffinity[timeSlot][genre] = prevSlotAffinity * 0.75 + slotTarget * 0.25;

    // Track preferred listening volume by time of day
    const prevSlotVol = model.volumeByTimeSlot[timeSlot] ?? volRatio;
    model.volumeByTimeSlot[timeSlot] = prevSlotVol * 0.75 + volRatio * 0.25;

    model.evaluatedOutcomes += 1;
    if (isPositive) {
      model.positiveOutcomes += 1;
    }
    model.learningIterations += 1;
  }

  public applyCrossAppSignals(model: UserTasteModel, signals: CrossAppTrackSignal[]): void {
    if (!signals || signals.length === 0) return;

    for (const sig of signals.slice(0, 40)) {
      if (!sig.title || !sig.artist) continue;
      const genre = SelfLearningDiscoveryEngine.inferGenre(sig.title, sig.artist, sig.genre);
      const entry = this.ensureGenreEntry(model, genre);
      const timeSlot = sig.timeSlot || 'Evening';
      const plays = Math.max(1, Number(sig.playCount || 1));

      const comp =
        typeof sig.completionRatio === 'number' && sig.completionRatio > 0
          ? Math.min(1.0, Math.max(0.1, sig.completionRatio))
          : sig.durationMs && sig.durationMs > 0 && sig.listenedMs && sig.listenedMs > 0
            ? Math.min(1.0, Math.max(0.1, sig.listenedMs / sig.durationMs))
            : 0.78;

      const vol =
        typeof sig.volumeRatio === 'number' && sig.volumeRatio > 0
          ? Math.min(1.0, Math.max(0.1, sig.volumeRatio))
          : entry.avgVolumeRatio;

      // Natural organic listening across apps reinforces that genre + volume + time slot
      const signalBoost = Math.min(0.95, 0.22 * Math.log2(plays + 1) * (0.6 + 0.4 * comp) + (vol >= 0.65 ? 0.1 : 0));
      entry.alpha = Math.min(80, entry.alpha + signalBoost * 0.45);
      entry.emaScore = Math.min(0.99, entry.emaScore * 0.85 + Math.min(0.96, 0.58 + signalBoost * 0.35) * 0.15);
      entry.plays += 1;
      if (comp >= 0.68) entry.completions += 1;
      entry.avgVolumeRatio = entry.avgVolumeRatio * 0.82 + vol * 0.18;
      entry.avgCompletionRatio = entry.avgCompletionRatio * 0.82 + comp * 0.18;
      entry.lastUpdatedMs = Date.now();

      if (!model.timeSlotGenreAffinity[timeSlot]) {
        model.timeSlotGenreAffinity[timeSlot] = {};
      }
      const prevSlot = model.timeSlotGenreAffinity[timeSlot][genre] ?? 0.5;
      model.timeSlotGenreAffinity[timeSlot][genre] = Math.min(0.99, prevSlot * 0.82 + 0.85 * 0.18);

      const prevVol = model.volumeByTimeSlot[timeSlot] ?? vol;
      model.volumeByTimeSlot[timeSlot] = prevVol * 0.82 + vol * 0.18;
    }

    model.learningIterations += 1;
  }

  /**
   * Computes UCB + Bayesian contextual score for ranking genres and candidate songs.
   */
  public scoreGenreForContext(model: UserTasteModel, genre: string, timeSlot: string): number {
    const entry = model.genreWeights[genre];
    if (!entry) return 0.55; // Slight optimistic exploration prior for unseen genres
    const bayesianMean = entry.alpha / (entry.alpha + entry.beta);
    const totalTrials = entry.alpha + entry.beta;
    const ucbExplorationBonus = Math.min(0.14, Math.sqrt((1.2 * Math.log(model.learningIterations + 2)) / totalTrials));
    const slotAffinity = model.timeSlotGenreAffinity[timeSlot]?.[genre] ?? 0.55;
    const combined = 0.45 * bayesianMean + 0.30 * entry.emaScore + 0.25 * slotAffinity + ucbExplorationBonus;
    return Math.min(0.99, Math.max(0.05, combined));
  }

  public buildLearningSummary(model: UserTasteModel, currentTimeSlot = 'Evening'): LearningSummary {
    const sorted = Object.values(model.genreWeights)
      .map((g) => ({
        genre: g.genre,
        score: this.scoreGenreForContext(model, g.genre, currentTimeSlot),
        skips: g.skips + g.dismissals,
        avgVol: g.avgVolumeRatio
      }))
      .sort((a, b) => b.score - a.score);

    const risingGenres = sorted.filter((g) => g.score >= 0.55).slice(0, 4).map((g) => g.genre);
    const coolingGenres = sorted.filter((g) => g.score < 0.46 || g.skips > 1).slice(0, 2).map((g) => g.genre);
    const hitRate = model.evaluatedOutcomes > 0
      ? Math.round((model.positiveOutcomes / model.evaluatedOutcomes) * 100)
      : 0;
    const slotVol = model.volumeByTimeSlot[currentTimeSlot]
      ?? (sorted.length > 0 ? sorted.reduce((acc, g) => acc + g.avgVol, 0) / sorted.length : 0.65);

    const narrative = model.evaluatedOutcomes > 0
      ? `Trained across ${model.learningIterations} updates (${hitRate}% recommendation match rate). Strongest ${currentTimeSlot.toLowerCase()} affinity: ${risingGenres.join(', ') || 'multi-genre flow'}.`
      : `Calibrating across ${risingGenres.join(', ') || 'your multi-app genres'} based on play depth, time of day, and listening volume.`;

    return {
      learningIterations: model.learningIterations,
      evaluatedOutcomes: model.evaluatedOutcomes,
      hitRatePercent: hitRate,
      risingGenres,
      coolingGenres,
      avgVolumePercent: Math.round(slotVol * 100),
      narrative
    };
  }

  /**
   * Selects top active user genres and assigns 2 songs per genre (8-10 total) + 2 Global Frontier wildcards.
   */
  public computeGenreAllocation(
    model: UserTasteModel,
    payload: DailyDropRequest,
    timeSlot: string
  ): Array<{ genre: string; slots: number; score: number }> {
    const observedGenres = new Set<string>();

    if (payload.genreDistribution) {
      for (const [g, count] of Object.entries(payload.genreDistribution)) {
        if (count > 0) observedGenres.add(SelfLearningDiscoveryEngine.canonicalGenreName(g));
      }
    }
    for (const t of payload.recentTracks || []) {
      observedGenres.add(SelfLearningDiscoveryEngine.inferGenre(t.title, t.artist, t.genre));
    }
    for (const a of [...(payload.topArtists || []), ...(payload.userTasteAnchors || [])]) {
      const inferred = SelfLearningDiscoveryEngine.inferGenre('', a);
      if (inferred !== 'Pop & Global') observedGenres.add(inferred);
    }
    for (const g of Object.keys(model.genreWeights)) {
      observedGenres.add(g);
    }

    const candidates = observedGenres.size > 0
      ? Array.from(observedGenres).filter((g) => g !== 'Pop & Global')
      : DEFAULT_FALLBACK_GENRES;

    const finalPool = candidates.length > 0 ? candidates : DEFAULT_FALLBACK_GENRES;
    const ranked = finalPool
      .map((genre) => ({
        genre,
        score: Number(this.scoreGenreForContext(model, genre, timeSlot).toFixed(3))
      }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 5); // Up to 5 top genres -> 2 picks each = up to 10 genre picks + 2 wildcards (10-12 tracks)

    return ranked.map((r) => ({ genre: r.genre, slots: 2, score: r.score }));
  }

  private normalizeKey(title: string, artist: string): string {
    return `${title.trim().toLowerCase().replace(/[^a-z0-9]/g, '')}::${artist.trim().toLowerCase().replace(/[^a-z0-9]/g, '')}`;
  }

  private isExcludedTrack(title: string, artist: string, excludedTitlesLower: Set<string>, excludedKeys: Set<string>): boolean {
    const cleanT = title.trim().toLowerCase();
    if (!cleanT) return true;
    if (excludedTitlesLower.has(cleanT)) return true;
    if (excludedKeys.has(this.normalizeKey(title, artist))) return true;
    return false;
  }

  /**
   * Ground candidate songs against Deezer's public catalog so TuneMind never serves fake/hallucinated songs
   * and attaches real previewUrl, album, albumArtUrl, and durationMs.
   */
  private async verifyWithCatalog(candidate: ScoredDiscoveryTrack): Promise<ScoredDiscoveryTrack> {
    try {
      const q = encodeURIComponent(`${candidate.title} ${candidate.artist}`);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 3000);
      const resp = await fetch(`https://api.deezer.com/search?q=${q}&limit=2`, {
        signal: controller.signal
      });
      clearTimeout(timer);
      if (resp.ok) {
        const json: any = await resp.json();
        const match = Array.isArray(json?.data) ? json.data[0] : null;
        if (match && match.title) {
          return {
            ...candidate,
            title: String(match.title || candidate.title).trim(),
            artist: String(match.artist?.name || candidate.artist).trim(),
            album: String(match.album?.title || candidate.album || '').trim(),
            previewUrl: typeof match.preview === 'string' && match.preview.startsWith('http') ? match.preview : candidate.previewUrl,
            albumArtUrl: typeof match.album?.cover_medium === 'string' ? match.album.cover_medium : candidate.albumArtUrl,
            durationMs: typeof match.duration === 'number' ? match.duration * 1000 : candidate.durationMs
          };
        }
      }
    } catch {
      // Non-fatal fallback
    }
    return candidate;
  }

  public async recordFeedback(event: DiscoveryFeedbackEvent, supabaseOverride?: { supabaseUrl?: string; supabaseKey?: string }): Promise<LearningSummary> {
    const userId = SelfLearningDiscoveryEngine.normalizeUserId(event.userId);
    const model = await this.getOrCreateModel(userId, supabaseOverride);
    this.applyOutcomeUpdate(model, event);
    await this.saveModel(model, supabaseOverride);

    const sb = getLiveSupabaseClient(supabaseOverride?.supabaseUrl, supabaseOverride?.supabaseKey);
    if (sb) {
      try {
        await sb.from('discovery_recommendation_outcomes').insert({
          user_id: userId,
          drop_date: event.dropDate || SelfLearningDiscoveryEngine.getTodayDateKey(),
          track_title: event.title,
          artist: event.artist,
          genre: SelfLearningDiscoveryEngine.inferGenre(event.title, event.artist, event.genre),
          sub_tier: event.subTier || 'GENRE_PICK',
          outcome_status: event.outcome,
          completion_ratio: event.durationMs && event.durationMs > 0 ? Math.min(1, (event.listenedMs || 0) / event.durationMs) : 0,
          volume_ratio: event.volumeRatio || 0,
          time_slot: event.timeSlot || 'Evening',
          listened_ms: event.listenedMs || 0,
          track_duration_ms: event.durationMs || 0
        });
      } catch {
        // Ignore if table not migrated yet
      }
    }

    return this.buildLearningSummary(model, event.timeSlot || 'Evening');
  }

  public async getInsights(userIdRaw?: string, supabaseOverride?: { supabaseUrl?: string; supabaseKey?: string }): Promise<{
    userId: string;
    summary: LearningSummary;
    topGenres: Array<{ genre: string; scorePercent: number; plays: number; skips: number; avgVolumePercent: number }>;
  }> {
    const userId = SelfLearningDiscoveryEngine.normalizeUserId(userIdRaw);
    const model = await this.getOrCreateModel(userId, supabaseOverride);
    const summary = this.buildLearningSummary(model);
    const topGenres = Object.values(model.genreWeights)
      .map((g) => ({
        genre: g.genre,
        scorePercent: Math.round(this.scoreGenreForContext(model, g.genre, 'Evening') * 100),
        plays: g.plays,
        skips: g.skips + g.dismissals,
        avgVolumePercent: Math.round(g.avgVolumeRatio * 100)
      }))
      .sort((a, b) => b.scorePercent - a.scorePercent)
      .slice(0, 8);

    return { userId, summary, topGenres };
  }

  public async generateDailyForYouDrop(payload: DailyDropRequest): Promise<DailyDropResponse> {
    const userId = SelfLearningDiscoveryEngine.normalizeUserId(payload.userId, payload.userEmail);
    const supabaseOverride = { supabaseUrl: payload.supabaseUrl, supabaseKey: payload.supabaseKey };
    const model = await this.getOrCreateModel(userId, supabaseOverride);
    const dropDate = payload.dropDate || SelfLearningDiscoveryEngine.getTodayDateKey();
    const timeSlot = payload.timeSlot || 'Evening';

    // 1. Online Self-Learning Step: evaluate yesterday's outcomes + passive cross-app telemetry first
    if (Array.isArray(payload.yesterdayOutcomes) && payload.yesterdayOutcomes.length > 0) {
      for (const out of payload.yesterdayOutcomes) {
        this.applyOutcomeUpdate(model, { ...out, userId });
      }
    }
    if (Array.isArray(payload.recentTracks) && payload.recentTracks.length > 0) {
      this.applyCrossAppSignals(model, payload.recentTracks);
    }

    // 2. Build genre allocation (2 songs per active genre + 2 Global Frontier wildcards)
    const genreAllocation = this.computeGenreAllocation(model, payload, timeSlot);
    const learningSummary = this.buildLearningSummary(model, timeSlot);

    // 3. Build strict zero-repeat exclusion set from ALL user library/history/past drops
    const excludedTitlesLower = new Set<string>();
    const excludedKeys = new Set<string>();
    for (const t of payload.excludeTitles || []) {
      const clean = t.trim().toLowerCase();
      if (clean) excludedTitlesLower.add(clean);
    }
    for (const r of payload.recentTracks || []) {
      if (r.title) excludedTitlesLower.add(r.title.trim().toLowerCase());
      if (r.title && r.artist) excludedKeys.add(this.normalizeKey(r.title, r.artist));
    }

    const totalTargetCount = Math.min(12, Math.max(8, genreAllocation.length * 2 + 2));
    let candidates: ScoredDiscoveryTrack[] = [];
    let isAiGenerated = false;
    let modelUsed = 'tunemind-bandit-selector';

    // 4. AI Deep Reasoning Synthesis (Claude Sonnet/Haiku)
    if (this.anthropic) {
      try {
        const allocationInstruction = genreAllocation
          .map((g) => `- 2 UNHEARD songs in "${g.genre}" (learned affinity score ${(g.score * 100).toFixed(0)}%)`)
          .join('\n');

        const outcomeFeedback = [
          ...(payload.yesterdayOutcomes || []).slice(0, 10).map(
            (o) => `${o.title} by ${o.artist} [${o.genre || 'Music'}]: ${o.outcome} (listened ${Math.round(((o.listenedMs || 0) / Math.max(1, o.durationMs || 180000)) * 100)}%, volume ${Math.round((o.volumeRatio || 0.6) * 100)}%)`
          ),
          ...(payload.playedFeedback || []).slice(0, 6).map((s) => `PLAYED & LIKED: ${s}`),
          ...(payload.dismissedFeedback || []).slice(0, 6).map((s) => `DISMISSED/SKIPPED: ${s}`)
        ].join('\n');

        const prompt = `You are TuneMind's Self-Learning Global Music Discovery Engine for date ${dropDate}.
LISTENER CONTEXT:
- Active Time of Day: ${timeSlot}
- Preferred Listening Volume Now: ${payload.avgVolumeRatio ? Math.round(payload.avgVolumeRatio * 100) : learningSummary.avgVolumePercent}%
- Satiety / Ear Fatigue Level: ${payload.satietyLevel ?? 38}%
- Learning Insights: ${learningSummary.narrative}
- Top Artists Observed Across All Apps: ${(payload.topArtists || []).concat(payload.userTasteAnchors || []).slice(0, 12).join(', ') || 'Diverse global listener'}

RECENT FEEDBACK & YESTERDAY SCORECARD:
${outcomeFeedback || 'First calibration run — maximize discovery accuracy across all detected genres.'}

DAILY SLATE QUOTA (pick ${totalTargetCount} real songs the listener has NOT played yet):
${allocationInstruction}
- Plus 2 "GLOBAL_WILDCARD" songs from worldwide scenes adjacent to their taste, energy, and volume profile!

STRICT EXCLUSIONS (DO NOT recommend any song on this list — user already knows or played them):
${Array.from(excludedTitlesLower).slice(0, 90).join(', ')}

Respond ONLY with a JSON array (no markdown fences):
[
  {
    "title": "Exact Song Title",
    "artist": "Exact Artist Name",
    "album": "Album Name",
    "genre": "Genre Name",
    "category": "DOPAMINE_FLOW" | "PALATE_CLEANSER" | "CHRONO_RESTORE" | "NOVEL_EXPLORATION",
    "subTier": "GENRE_PICK" | "GLOBAL_WILDCARD",
    "isWildcard": false,
    "reason": "1 concise sentence linking their time-of-day, volume, or play pattern to this song.",
    "confidence": 0.94
  }
]`;

        const aiResp = await this.anthropic.messages.create({
          model: 'claude-3-5-haiku-20241022',
          max_tokens: 1800,
          messages: [{ role: 'user', content: prompt }]
        });
        const rawText = aiResp.content
          .filter((b): b is Anthropic.TextBlock => b.type === 'text')
          .map((b) => b.text)
          .join('\n')
          .trim();

        const match = rawText.match(/\[[\s\S]*\]/);
        const parsed = JSON.parse(match ? match[0] : rawText);
        if (Array.isArray(parsed)) {
          isAiGenerated = true;
          modelUsed = 'claude-3-5-haiku+contextual-bandit';
          for (const row of parsed) {
            const title = String(row.title || '').trim();
            const artist = String(row.artist || '').trim();
            if (!title || !artist) continue;
            if (this.isExcludedTrack(title, artist, excludedTitlesLower, excludedKeys)) continue;

            const genre = SelfLearningDiscoveryEngine.inferGenre(title, artist, row.genre);
            const learnedGenreScore = this.scoreGenreForContext(model, genre, timeSlot);
            const llmConf = Math.min(0.99, Math.max(0.75, Number(row.confidence || 0.91)));
            const mlScore = Number((llmConf * 0.55 + learnedGenreScore * 0.45).toFixed(3));
            const isWildcard = Boolean(row.isWildcard || row.subTier === 'GLOBAL_WILDCARD');

            candidates.push({
              title,
              artist,
              album: String(row.album || '').trim(),
              genre,
              category: String(row.category || (isWildcard ? 'NOVEL_EXPLORATION' : 'DOPAMINE_FLOW')),
              subTier: isWildcard ? 'GLOBAL_WILDCARD' : 'GENRE_PICK',
              reason: String(row.reason || `Picked for your ${timeSlot} ${genre} rotation.`).trim(),
              confidence: mlScore,
              mlScore,
              isWildcard
            });
          }
        }
      } catch (err) {
        console.warn('[SelfLearningDiscovery] Claude call fallback notice:', (err as Error)?.message);
      }
    }

    // 5. Ensure exact 2-per-genre + 2 global wildcards using curated global bank when slots remain
    const genreCounts: Record<string, number> = {};
    for (const c of candidates) {
      if (!c.isWildcard) {
        genreCounts[c.genre] = (genreCounts[c.genre] || 0) + 1;
      }
    }

    const seenDropKeys = new Set<string>(candidates.map((c) => this.normalizeKey(c.title, c.artist)));

    for (const alloc of genreAllocation) {
      const currentForGenre = genreCounts[alloc.genre] || 0;
      let needed = Math.max(0, alloc.slots - currentForGenre);
      if (needed <= 0) continue;

      const bankMatches = CURATED_GLOBAL_DISCOVERY_BANK.filter(
        (b) => !b.isWildcard && b.genre.toLowerCase() === alloc.genre.toLowerCase()
      );
      for (const b of bankMatches) {
        if (needed <= 0) break;
        const k = this.normalizeKey(b.title, b.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(b.title, b.artist, excludedTitlesLower, excludedKeys)) continue;
        seenDropKeys.add(k);
        const mlScore = Number((b.confidence * 0.5 + alloc.score * 0.5).toFixed(3));
        candidates.push({ ...b, confidence: mlScore, mlScore });
        needed -= 1;
      }
    }

    let wildcardCount = candidates.filter((c) => c.isWildcard).length;
    if (wildcardCount < 2) {
      const wildcards = CURATED_GLOBAL_DISCOVERY_BANK.filter((b) => b.isWildcard);
      for (const w of wildcards) {
        if (wildcardCount >= 2) break;
        const k = this.normalizeKey(w.title, w.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(w.title, w.artist, excludedTitlesLower, excludedKeys)) continue;
        seenDropKeys.add(k);
        candidates.push(w);
        wildcardCount += 1;
      }
    }

    // Top up up to totalTargetCount with highest-ranked non-excluded global songs if needed
    if (candidates.length < Math.min(10, totalTargetCount)) {
      for (const b of CURATED_GLOBAL_DISCOVERY_BANK) {
        if (candidates.length >= Math.min(10, totalTargetCount)) break;
        const k = this.normalizeKey(b.title, b.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(b.title, b.artist, excludedTitlesLower, excludedKeys)) continue;
        seenDropKeys.add(k);
        candidates.push(b);
      }
    }

    // 6. Real-catalog verification (parallel with tight 3s timeout per track)
    const sliced = candidates.slice(0, totalTargetCount);
    const verified = await Promise.all(sliced.map((c) => this.verifyWithCatalog(c)));

    // Record daily drop into model history + save updated model
    model.recentDrops = [
      { dropDate, generatedAtMs: Date.now(), recommendations: verified },
      ...model.recentDrops.filter((d) => d.dropDate !== dropDate)
    ].slice(0, 14);
    await this.saveModel(model, supabaseOverride);

    return {
      status: 'ok',
      dropDate,
      isAiGenerated,
      modelUsed,
      learningSummary: this.buildLearningSummary(model, timeSlot),
      genreAllocation,
      recommendations: verified
    };
  }
}
