import fs from 'fs';
import os from 'os';
import path from 'path';
import { callGeminiSingleShot, isGeminiActive } from '../reasoning/GeminiAdapter';
import { callOpenAISingleShot, isOpenAIActive } from '../reasoning/OpenAIAdapter';
import { getLiveSupabaseClient } from '../../config/supabase';
import { SupabaseIntelligenceStore } from '../storage/SupabaseIntelligenceStore';
import { TrackCardStore } from '../storage/TrackCardStore';
import { EventStore } from '../events/EventStore';
import { SpotifyUserApiClient } from '../spotify/SpotifyUserApiClient';
import {
  isStrictTitleArtistMatch,
  resolveUniversalTrackProfile
} from '../../api/v1/musicProfiles';
import {
  buildDeterministicTrackId,
  buildNormalizedMatchKey,
  coerceNonNegativePlayCount,
  extractSpotifyBase62Id,
  normalizeSpotifyUri,
  resolveCanonicalTrackIdentity
} from '../identity/trackIdentity';

export type RecommendationOutcomeType =
  | 'PENDING'
  | 'PLAYED'
  | 'COMPLETED'
  | 'SAVED'
  | 'SKIPPED'
  | 'DISMISSED'
  | 'IGNORED';

export interface CrossAppTrackSignal {
  trackId?: string;
  spotifyUri?: string;
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
  playedAtMs?: number;
}

export interface DiscoveryFeedbackEvent {
  userId?: string;
  trackId?: string;
  spotifyUri?: string;
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

export interface AcousticCentroid {
  targetBpm: number;
  targetDurationSec: number;
  preferredVolumeRatio: number;
  sampleCount: number;
}

export interface UserTasteModel {
  userId: string;
  learningIterations: number;
  evaluatedOutcomes: number;
  positiveOutcomes: number;
  genreWeights: Record<string, GenreWeightEntry>;
  timeSlotGenreAffinity: Record<string, Record<string, number>>;
  volumeByTimeSlot: Record<string, number>;
  artistGenreMap?: Record<string, string>;
  acousticByTimeSlot?: Record<string, AcousticCentroid>;
  recentDrops: DailyDropRecord[];
  recommendedTrackKeys?: string[];
  recommendedTrackIds?: string[];
  recommendedSpotifyUris?: string[];
  updatedAtMs: number;
}

export type EvolutionStage =
  | 'STAGE_1_CALIBRATING'
  | 'STAGE_2_PATTERN_LOCK'
  | 'STAGE_3_DEEP_TASTE_SYNC'
  | 'STAGE_4_PREDICTIVE_ORACLE';

export interface LearningSummary {
  learningIterations: number;
  evaluatedOutcomes: number;
  hitRatePercent: number;
  intelligenceScorePercent: number;
  evolutionStage: EvolutionStage;
  evolutionLabel: string;
  learnedTraits: string[];
  risingGenres: string[];
  coolingGenres: string[];
  avgVolumePercent: number;
  narrative: string;
}

export interface ScoredDiscoveryTrack {
  trackId: string;
  spotifyUri?: string;
  playCount?: number;
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
  bpm?: number;
  acousticCosineScore?: number;
}

export interface DailyDropRequest {
  userId?: string;
  userEmail?: string;
  dropDate?: string;
  forceRefresh?: boolean;
  timezone?: string;
  localHour?: number;
  timeSlot?: string;
  satietyLevel?: number;
  preferredProvider?: string;
  avgVolumeRatio?: number;
  topArtists?: string[];
  userTasteAnchors?: string[];
  genreDistribution?: Record<string, number>;
  artistGenres?: Record<string, string>;
  recentTracks?: CrossAppTrackSignal[];
  vaultTracks?: Array<{ title?: string; artist?: string; cooldownDaysRemaining?: number }>;
  excludeTitles?: string[];
  excludeKeys?: string[];
  excludedSpotifyUris?: string[];
  excludedTrackIds?: string[];
  spotifyAccessToken?: string;
  yesterdayOutcomes?: DiscoveryFeedbackEvent[];
  playedFeedback?: string[];
  dismissedFeedback?: string[];
  skipClaude?: boolean;
  maxTotalRecommendations?: number;
  supabaseUrl?: string;
  supabaseKey?: string;
}

export interface DailyDropResponse {
  status: 'ok';
  dropDate: string;
  nextRefreshLocalLabel: string;
  isAiGenerated: boolean;
  modelUsed: string;
  wildcardCount: number;
  learningSummary: LearningSummary;
  genreAllocation: Array<{ genre: string; slots: number; score: number }>;
  recommendations: ScoredDiscoveryTrack[];
}

const DEFAULT_FALLBACK_GENRES = ['Afrobeats', 'Bongo Flava', 'Hip-Hop', 'R&B', 'Pop'];

const ARTIST_GENRE_HINTS: Array<{ keywords: string[]; genre: string }> = [
  {
    keywords: [
      'samidoh', 'salim', 'mighty salim', 'salim junior', 'salim young', 'demathew', "de'mathew",
      'joyce wa mamaa', 'gatutura', 'kigia', 'kamaru', 'mike rua', 'kamande', 'musaimo', 'tony young',
      'mugithi', 'wendo', 'murata', 'shiro wa gp', 'carol wanjiku', 'jose gatutura', 'ndung\'u',
      'timmy tdat kikuyu', 'muigai wa njoroge', 'wanjiku wa kamau', 'karangu muraya', 'waithaka',
      'benga', 'gikuyu', 'kikuyu', 'ruracio', 'matharaiti', 'ciothe', 'kigooco'
    ],
    genre: 'Gikuyu / Mugithi'
  },
  {
    keywords: [
      'mbosso', 'diamond', 'platnumz', 'harmonize', 'zuchu', 'marioo', 'rayvanny', 'alikiba',
      'jux', 'juma jux', 'nandy', 'otile', 'lavalava', 'lava lava', 'kusah', 'jay melody',
      'barnaba', 'aslay', 'd voice', 'yammi', 'kontawa', 'chino kidd', 'macvoice', 'ibraah',
      'darassa', 'roma mkatoliki', 'maua sama', 'stamina', 'bongo'
    ],
    genre: 'Bongo Flava'
  },
  {
    keywords: [
      'mercy masika', 'reuben kigame', 'guardian angel', 'evelyn wanjiru', 'christina shusho',
      'gloria muliro', 'paul mwai', 'florence andenyi', 'eunice njeri', 'moji shortbabaa',
      'jabidii', 'solomon mkubwa', 'rose muhando', 'bahati', 'willy paul', 'ambassada',
      'size 8', 'kambua', 'ringtone', 'angel benard', 'goodluck gozbert', 'joel lwaga',
      'maverick city', 'kirk franklin', 'tasha cobbs', 'sinach', 'mercy chinwo', 'nathaniel bassey',
      'elevation worship', 'hillsong', 'dunsin', 'moses bliss', 'joe mettle', 'gospel', 'worship',
      'tenzi', 'kwaya', 'injili', 'sifa', 'yesu', 'baba yetu'
    ],
    genre: 'Gospel'
  },
  {
    keywords: [
      'nyashinski', 'khaligraph', 'wakadinali', 'sauti sol', 'bien', 'bensoul', 'nviiri',
      'buruklyn boyz', 'boutross', 'trio mio', 'mejja', 'sailors', 'ethic', 'matata',
      'breeder lw', 'scar mkadinali', 'domani munga', 'sewer sydaa', 'ochungulo', 'boondocks',
      'mbithi', 'savara', 'chimano', 'fancy fingers', 'bridget blue', 'karun', 'nikita kering',
      'xenia manasseh', 'njerae', 'maandy', 'ssaru', 'femi one', 'muthoni drummer queen',
      'gengetone', 'arbantone', 'kenyan'
    ],
    genre: 'Kenyan Pop & Gengetone'
  },
  {
    keywords: [
      'ohangla', 'prince indah', 'emma jalamo', 'odongo swagg', 'tony nyadundo', 'osogo winyo',
      'luo', 'musa jakadalla', 'freddy jakadongo'
    ],
    genre: 'Ohangla & Benga'
  },
  {
    keywords: [
      'fally ipupa', 'koffi olomide', 'innoss', 'ferre gola', 'awilo', 'werrason', 'papa wemba',
      'franco luambo', 'tabu ley', 'zaiko langa', 'hearitier watanabe', 'lingala', 'rumba', 'soukous'
    ],
    genre: 'Lingala & Rumba'
  },
  {
    keywords: [
      'burna', 'wizkid', 'rema', 'asake', 'ayra', 'tems', 'omah lay', 'davido', 'ckay',
      'fireboy', 'tiwa', 'kizz daniel', 'victony', 'oxlade', 'ruger', 'buju', 'bnxn',
      'olamide', 'shallipopi', 'seyi vibez', 'joeboy', 'tekno', 'yemi alade', 'patoranking',
      'simi', 'adekunle gold', 'mayorkun', 'young jonn', 'flavour', 'phyno', 'afrobeat'
    ],
    genre: 'Afrobeats'
  },
  {
    keywords: [
      'tyler icu', 'kabza', 'dj maphorisa', 'uncle waffles', 'tyla', 'sha sha', 'vigro',
      'focalistic', 'kelvin momo', 'musa keys', 'mthunzi', 'de mthuda', 'samthing soweto',
      'daliwonga', 'young stunna', 'ami faku', 'boohle', 'amapiano'
    ],
    genre: 'Amapiano'
  },
  {
    keywords: [
      'kendrick', 'drake', 'j. cole', 'travis scott', '21 savage', 'future', 'metro boomin',
      'kanye', 'eminem', 'nas', 'baby keem', 'jid', 'lil baby', 'gunna', 'central cee',
      'playboi carti', 'tyler, the creator', 'asap rocky', 'jay-z', 'lil wayne', 'nicki minaj',
      'migos', 'offset', 'don toliver', 'dave', 'stormzy', 'skepta', 'little simz'
    ],
    genre: 'Hip-Hop'
  },
  {
    keywords: [
      'sza', 'frank ocean', 'the weeknd', 'weeknd', 'brent faiyaz', 'h.e.r.', 'daniel caesar',
      'giveon', 'summer walker', 'khalid', 'ariana', 'chris brown', 'usher', 'bruno mars',
      'kehlani', 'tory lanez', 'bryson tiller', 'snoh aalegra', 'jorja smith', 'victoria monet',
      'steve lacy', 'erykah badu', 'd\'angelo', 'miguel', 'alicia keys'
    ],
    genre: 'R&B'
  },
  {
    keywords: [
      'dua lipa', 'taylor swift', 'billie eilish', 'sabrina carpenter', 'olivia rodrigo',
      'harry styles', 'ed sheeran', 'doja cat', 'justin bieber', 'post malone', 'chappell roan',
      'adele', 'coldplay', 'maroon 5', 'beyonce', 'rihanna', 'katy perry', 'charlie puth'
    ],
    genre: 'Pop'
  },
  {
    keywords: [
      'popcaan', 'vybz kartel', 'shenseea', 'koffee', 'chronixx', 'bob marley', 'sean paul',
      'alkaline', 'spice', 'shaggy', 'damian marley', 'protoje', 'busysignal', 'skillibeng',
      'masicka', 'reggae', 'dancehall'
    ],
    genre: 'Dancehall & Reggae'
  },
  {
    keywords: [
      'dadju', 'aya nakamura', 'maitre gims', 'tayc', 'franglish', 'niska', 'stromae', 'tiakola'
    ],
    genre: 'Afro-Francophone'
  },
  {
    keywords: [
      'bad bunny', 'karol g', 'feid', 'rauw alejandro', 'rosalia', 'anitta', 'j balvin',
      'maluma', 'shakira', 'peso pluma', 'ozuna'
    ],
    genre: 'Latin & Urbano'
  },
  {
    keywords: [
      'black coffee', 'fred again', 'kaytranada', 'disclosure', 'calvin harris', 'rufus du sol',
      'peggy gou', 'keinemusik', 'anyma', 'shimza', 'sun-el musician', 'da capo'
    ],
    genre: 'Electronic & Afro House'
  },
  {
    keywords: [
      'yussef dayes', 'robert glasper', 'kamasi washington', 'alfa mist', 'ezra collective',
      'badbadnotgood', 'tom misch', 'hiatus kaiyote'
    ],
    genre: 'Nu-Jazz & Neo-Soul'
  },
  {
    keywords: [
      'ana frango', 'seu jorge', 'joao gilberto', 'tim maia', 'gilberto gil', 'marisa monte', 'laufey'
    ],
    genre: 'Bossa Nova & MPB'
  },
  {
    keywords: [
      'khruangbin', 'men i trust', 'tame impala', 'faye webster', 'mac demarco', 'mitski', 'glass animals'
    ],
    genre: 'Alternative Indie'
  }
];

const CURATED_GLOBAL_DISCOVERY_BANK_RAW: Array<Omit<ScoredDiscoveryTrack, 'trackId'>> = [
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
  { title: 'Mwema', artist: 'Mercy Masika', album: 'Mwema', genre: 'Gospel', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Warm East African Swahili worship cadence built for grounded calm.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'Nadeka', artist: 'Guardian Angel', album: 'Nadeka', genre: 'Gospel', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Upbeat Kenyan praise groove that elevates focus without fatigue.', confidence: 0.92, mlScore: 0.92, isWildcard: false },

  // Gikuyu / Mugithi
  { title: 'Murata Wa Thiururuka', artist: 'Samidoh', album: 'Mugithi Essentials', genre: 'Gikuyu / Mugithi', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Crisp acoustic Kikuyu guitar plucking tuned to your East African melodic cadence.', confidence: 0.95, mlScore: 0.95, isWildcard: false },
  { title: 'Wendo Ni Uriru', artist: 'Joyce Wa Mamaa', album: 'Gikuyu Classics', genre: 'Gikuyu / Mugithi', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Warm high-life bassline and vocal storytelling that reset overplayed rotations.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Nindacokia Ngatho', artist: 'John De\'Mathew', album: 'Timeless Mugithi', genre: 'Gikuyu / Mugithi', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Classic Benga-Mugithi chord progression with steady restorative groove.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Mumbi', artist: 'Jose Gatutura', album: 'Mugithi Live', genre: 'Gikuyu / Mugithi', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Rhythmic acoustic guitar pocket crafted for steady Mugithi flow.', confidence: 0.92, mlScore: 0.92, isWildcard: false },

  // Kenyan Pop & Gengetone
  { title: 'Malaika', artist: 'Nyashinski', album: 'Lucky You', genre: 'Kenyan Pop & Gengetone', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Silky Kenyan Afro-pop phrasing balancing warm chords and crisp percussion.', confidence: 0.94, mlScore: 0.94, isWildcard: false },
  { title: 'Suzanna', artist: 'Sauti Sol', album: 'Midnight Train', genre: 'Kenyan Pop & Gengetone', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Warm Nairobi harmonies and live guitar groove for effortless full listens.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Inauma', artist: 'Bien', album: 'Alusa Why Are You Topless', genre: 'Kenyan Pop & Gengetone', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Sharp Nairobi groove paired with expressive East African vocal depth.', confidence: 0.92, mlScore: 0.92, isWildcard: false },
  { title: 'Siskii', artist: 'Wakadinali', album: 'Ndani Ya Cockpit 3', genre: 'Kenyan Pop & Gengetone', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Punchy Kenyan hip-hop rhythm built for high-focus energy bursts.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Lingala & Rumba
  { title: 'Eloko Oyo', artist: 'Fally Ipupa', album: 'Tokooos', genre: 'Lingala & Rumba', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Crisp Congolese sebene guitars paired with modern Afro-urban groove.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Maboko Pamba', artist: 'Koffi Olomide', album: 'Légende', genre: 'Lingala & Rumba', category: 'CHRONO_RESTORE', subTier: 'GENRE_PICK', reason: 'Lush golden-era Kinshasa rumba harmonies with warm horn swells.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Ohangla & Benga
  { title: 'Girwa Ni', artist: 'Prince Indah', album: 'Puonj Maghera', genre: 'Ohangla & Benga', category: 'DOPAMINE_FLOW', subTier: 'GENRE_PICK', reason: 'Energetic lakeside Ohangla accordion and percussion groove.', confidence: 0.93, mlScore: 0.93, isWildcard: false },
  { title: 'Nyako Miya', artist: 'Emma Jalamo', album: 'Ohangla Hits', genre: 'Ohangla & Benga', category: 'PALATE_CLEANSER', subTier: 'GENRE_PICK', reason: 'Deep traditional Benga bassline and festive lakeside rhythm.', confidence: 0.91, mlScore: 0.91, isWildcard: false },

  // Global Frontier Wildcards (distinct non-overlapping global scenes)
  { title: 'N\'y Pense Plus', artist: 'Tayc', album: 'Fleur Froide', genre: 'Afro-Francophone', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Parisian Afro-lovin grooves merging silky French vocals with warm percussive swing.', confidence: 0.91, mlScore: 0.91, isWildcard: true },
  { title: 'Morena', artist: 'Kaytranada & Kali Uchis', album: 'TIMELESS', genre: 'Electronic & Afro House', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Montreal-Colombian bounce bridging warm basslines and crisp electronic syncopation.', confidence: 0.92, mlScore: 0.92, isWildcard: true },
  { title: 'DtMF', artist: 'Bad Bunny', album: 'DeBÍ TiRAR MáS FOToS', genre: 'Latin & Urbano', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Nostalgic Puerto Rican plena rhythms meeting modern low-end punch.', confidence: 0.91, mlScore: 0.91, isWildcard: true },
  { title: 'Blooming', artist: 'Koffee', album: 'Gifted', genre: 'Dancehall & Reggae', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Sun-soaked Jamaican acoustic groove designed for an instant sonic reset.', confidence: 0.90, mlScore: 0.90, isWildcard: true },
  { title: 'Black Classical Music', artist: 'Yussef Dayes', album: 'Black Classical Music', genre: 'Nu-Jazz & Neo-Soul', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Live London breakbeat drums and shimmering Rhodes chords matching your rhythmic focus.', confidence: 0.91, mlScore: 0.91, isWildcard: true },
  { title: 'Time (You and I)', artist: 'Khruangbin', album: 'Mordechai', genre: 'Alternative Indie', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Hypnotic Thai-funk guitars and laid-back bass pocket for zero-fatigue flow.', confidence: 0.90, mlScore: 0.90, isWildcard: true },
  { title: 'Drive Me Crazy', artist: 'Black Coffee & Msaki', album: 'Subconsciously', genre: 'Electronic & Afro House', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Deep South African house atmosphere crafted for expansive late sessions.', confidence: 0.92, mlScore: 0.92, isWildcard: true },
  { title: 'Amiga da Minha Mulher', artist: 'Seu Jorge', album: 'Músicas para Churrasco', genre: 'Bossa Nova & MPB', category: 'NOVEL_EXPLORATION', subTier: 'GLOBAL_WILDCARD', reason: 'Genre Leap: Brazilian samba-soul brass and warm acoustic bounce to refresh your ear.', confidence: 0.89, mlScore: 0.89, isWildcard: true }
];

const CURATED_GLOBAL_DISCOVERY_BANK: ScoredDiscoveryTrack[] = CURATED_GLOBAL_DISCOVERY_BANK_RAW.map((item) => ({
  ...item,
  trackId: buildDeterministicTrackId(item.title, item.artist, 'disc'),
  playCount: 0
}));

export class SelfLearningDiscoveryEngine {
  private static storePath = path.join(os.tmpdir(), 'tunemind-ml-taste-store.json');
  private static memoryCache: Map<string, UserTasteModel> = new Map();
  private static loadedDisk = false;
  private static discoveredClaudeModels: string[] | null = null;
  private static lastClaudeDiscoveryMs = 0;
  private static cachedWorkingClaudeModel: string | null = null;
  private anthropic: any = null;

  constructor() {
    if (!isGeminiActive()) {
      const key = (process.env.ANTHROPIC_API_KEY || '').trim();
      if (key.length > 10 && !key.startsWith('your_')) {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const AnthropicSdk = require('@anthropic-ai/sdk').default || require('@anthropic-ai/sdk');
        this.anthropic = new AnthropicSdk({ apiKey: key });
      }
    }
  }

  public static normalizeUserId(userId?: string, userEmail?: string): string {
    const raw = (userId || userEmail || 'tunemind-default-user').trim().toLowerCase();
    return raw.replace(/[^a-z0-9@._-]/g, '_') || 'tunemind-default-user';
  }

  public static getTodayDateKey(now = new Date()): string {
    return now.toISOString().slice(0, 10);
  }

  /**
   * Computes the 12:00 PM (Noon) local-timezone cycle key:
   * - Before 12:00 PM local time -> yesterday's YYYY-MM-DD@12PM drop remains active
   * - At or after 12:00 PM local time -> today's YYYY-MM-DD@12PM drop is active
   */
  public static getNoonDropCycleKey(
    timezone?: string,
    localHourOverride?: number,
    now = new Date()
  ): string {
    let year = now.getUTCFullYear();
    let month = now.getUTCMonth() + 1;
    let day = now.getUTCDate();
    let hour = now.getUTCHours();

    if (timezone && timezone.trim().length > 0) {
      try {
        const parts = new Intl.DateTimeFormat('en-CA', {
          timeZone: timezone.trim(),
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          hour12: false
        }).formatToParts(now);
        const map: Record<string, string> = {};
        for (const p of parts) {
          map[p.type] = p.value;
        }
        year = parseInt(map.year || String(year), 10);
        month = parseInt(map.month || String(month), 10);
        day = parseInt(map.day || String(day), 10);
        const parsedHour = parseInt(map.hour || String(hour), 10);
        hour = parsedHour === 24 ? 0 : parsedHour;
      } catch {
        // Fallback cleanly to UTC date parts if timezone string is invalid
      }
    }

    if (typeof localHourOverride === 'number' && Number.isFinite(localHourOverride)) {
      hour = Math.max(0, Math.min(23, Math.floor(localHourOverride)));
    }

    const baseDate = new Date(Date.UTC(year, month - 1, day));
    if (hour < 12) {
      baseDate.setUTCDate(baseDate.getUTCDate() - 1);
    }
    return `${baseDate.toISOString().slice(0, 10)}@12PM`;
  }

  public static inferGenre(
    title: string,
    artist: string,
    explicitGenre?: string,
    cachedArtistGenreMap?: Record<string, string>
  ): string {
    if (explicitGenre && explicitGenre.trim().length > 1 && explicitGenre.toLowerCase() !== 'unknown' && explicitGenre.toLowerCase() !== 'pop & global') {
      return this.canonicalGenreName(explicitGenre);
    }
    const cleanArtistKey = artist.trim().toLowerCase().split(/[,&]|feat\.?|ft\.?/i)[0].trim();
    if (cachedArtistGenreMap && cleanArtistKey && cachedArtistGenreMap[cleanArtistKey]) {
      return cachedArtistGenreMap[cleanArtistKey];
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
    if (lower.includes('ohangla') || lower.includes('luo')) return 'Ohangla & Benga';
    if (lower.includes('gikuyu') || lower.includes('kikuyu') || lower.includes('mugithi') || lower.includes('benga')) return 'Gikuyu / Mugithi';
    if (lower.includes('gengetone') || lower.includes('arbantone') || lower.includes('kenyan pop') || lower.includes('kenyan hip')) return 'Kenyan Pop & Gengetone';
    if (lower.includes('lingala') || lower.includes('rumba') || lower.includes('soukous') || lower.includes('congolese')) return 'Lingala & Rumba';
    if (lower.includes('bongo') || lower.includes('swahili pop') || lower.includes('tanzanian')) return 'Bongo Flava';
    if (lower.includes('amapiano')) return 'Amapiano';
    if (lower.includes('afro') && (lower.includes('house') || lower.includes('electronic'))) return 'Electronic & Afro House';
    if (lower.includes('afro') && lower.includes('franc')) return 'Afro-Francophone';
    if (lower.includes('afro') || lower.includes('alté') || lower.includes('alte')) return 'Afrobeats';
    if (lower.includes('jazz') || lower.includes('neo-soul') || lower.includes('neo soul')) return 'Nu-Jazz & Neo-Soul';
    if (lower.includes('bossa') || lower.includes('mpb') || lower.includes('samba')) return 'Bossa Nova & MPB';
    if (lower.includes('indie') || lower.includes('alt')) return 'Alternative Indie';
    if (lower.includes('hip') || lower.includes('rap') || lower.includes('trap') || lower.includes('drill')) return 'Hip-Hop';
    if (lower.includes('r&b') || lower.includes('rnb') || lower.includes('soul')) return 'R&B';
    if (lower.includes('gospel') || lower.includes('worship') || lower.includes('ccm')) return 'Gospel';
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
        const [{ data }, { data: outcomeRows }] = await Promise.all([
          sb.from('ml_user_taste_models').select('*').eq('user_id', userId).maybeSingle(),
          sb
            .from('discovery_recommendation_outcomes')
            .select('drop_date, track_id, spotify_uri, track_title, artist, genre, sub_tier, outcome_status, reason, confidence, preview_url, album_art_url, track_duration_ms, updated_at')
            .eq('user_id', userId)
            .order('updated_at', { ascending: false })
            .limit(1000)
        ]);

        const dropsByDate = new Map<string, ScoredDiscoveryTrack[]>();
        const permKeys = new Set<string>();
        const permTrackIds = new Set<string>();
        const permUris = new Set<string>();

        if (Array.isArray(outcomeRows)) {
          for (const row of outcomeRows) {
            const dKey = String(row.drop_date || '').trim();
            const rTitle = String(row.track_title || '').trim();
            const rArtist = String(row.artist || '').trim();
            if (!rTitle || !rArtist) continue;

            const identity = resolveCanonicalTrackIdentity({
              trackId: row.track_id,
              spotifyUri: row.spotify_uri,
              title: rTitle,
              artist: rArtist,
              prefix: 'disc'
            });
            permKeys.add(this.normalizeKey(rTitle, rArtist));
            const normMatch = buildNormalizedMatchKey(rTitle, rArtist);
            if (normMatch) permKeys.add(normMatch);
            if (identity.trackId && !identity.trackId.startsWith('disc:') && !identity.trackId.startsWith('trk:')) {
              permTrackIds.add(identity.trackId);
            }
            if (identity.spotifyUri) permUris.add(identity.spotifyUri.toLowerCase());

            if (!dKey) continue;
            const list = dropsByDate.get(dKey) || [];
            if (!list.some((x) => this.normalizeKey(x.title, x.artist) === this.normalizeKey(rTitle, rArtist))) {
              const isWild = String(row.sub_tier || '').toUpperCase() === 'GLOBAL_WILDCARD';
              const conf = Number(row.confidence || 0.9);
              list.push({
                trackId: identity.trackId,
                spotifyUri: identity.spotifyUri,
                playCount: 0,
                title: rTitle,
                artist: rArtist,
                album: '',
                genre: String(row.genre || 'Discovery'),
                category: isWild ? 'NOVEL_EXPLORATION' : 'DOPAMINE_FLOW',
                subTier: isWild ? 'GLOBAL_WILDCARD' : 'GENRE_PICK',
                reason: String(row.reason || 'Previously dropped from your long-term taste model.'),
                confidence: conf,
                mlScore: conf,
                isWildcard: isWild,
                previewUrl: row.preview_url || undefined,
                albumArtUrl: row.album_art_url || undefined,
                durationMs: Number(row.track_duration_ms || 0) || undefined
              });
            }
            dropsByDate.set(dKey, list);
          }
        }
        const hydratedDrops: DailyDropRecord[] = Array.from(dropsByDate.entries())
          .slice(0, 14)
          .map(([dropDate, recommendations]) => ({
            dropDate,
            generatedAtMs: Date.now(),
            recommendations
          }));

        if (data || hydratedDrops.length > 0 || permKeys.size > 0) {
          const hydrated: UserTasteModel = {
            userId,
            learningIterations: Number(data?.learning_iterations || 0),
            evaluatedOutcomes: Number(data?.evaluated_outcomes || 0),
            positiveOutcomes: Number(data?.positive_outcomes || 0),
            genreWeights: (data?.genre_weights as Record<string, GenreWeightEntry>) || {},
            timeSlotGenreAffinity: (data?.time_slot_affinity as Record<string, Record<string, number>>) || {},
            volumeByTimeSlot: (data?.volume_profile as Record<string, number>) || {},
            recentDrops: hydratedDrops,
            recommendedTrackKeys: Array.from(permKeys).slice(-5000),
            recommendedTrackIds: Array.from(permTrackIds).slice(-3000),
            recommendedSpotifyUris: Array.from(permUris).slice(-3000),
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

  public applyCrossAppSignals(
    model: UserTasteModel,
    signals: CrossAppTrackSignal[],
    artistGenresMap?: Record<string, string>
  ): void {
    if (!signals || signals.length === 0) return;
    if (!model.artistGenreMap) model.artistGenreMap = {};
    if (artistGenresMap) {
      for (const [k, v] of Object.entries(artistGenresMap)) {
        const cleanK = k.trim().toLowerCase();
        const cleanV = SelfLearningDiscoveryEngine.canonicalGenreName(v);
        if (cleanK && cleanV && cleanV.toLowerCase() !== 'pop & global') {
          model.artistGenreMap[cleanK] = cleanV;
        }
      }
    }
    if (!model.acousticByTimeSlot) model.acousticByTimeSlot = {};

    const nowMs = Date.now();
    for (const sig of signals.slice(0, 60)) {
      if (!sig.title || !sig.artist) continue;
      const plays = coerceNonNegativePlayCount(sig.playCount);
      if (plays <= 0) continue; // Only actually played tracks (playCount > 0) shape taste weights

      const genre = SelfLearningDiscoveryEngine.inferGenre(
        sig.title,
        sig.artist,
        sig.genre,
        model.artistGenreMap
      );
      if (genre === 'Pop & Global') continue;

      const entry = this.ensureGenreEntry(model, genre);
      const timeSlot = sig.timeSlot || 'Evening';

      const comp =
        typeof sig.completionRatio === 'number' && sig.completionRatio > 0
          ? Math.min(1.0, Math.max(0.05, sig.completionRatio))
          : sig.durationMs && sig.durationMs > 0 && sig.listenedMs && sig.listenedMs > 0
            ? Math.min(1.0, Math.max(0.05, sig.listenedMs / sig.durationMs))
            : 0.78;

      const vol =
        typeof sig.volumeRatio === 'number' && sig.volumeRatio > 0
          ? Math.min(1.0, Math.max(0.05, sig.volumeRatio))
          : entry.avgVolumeRatio;

      // Exponential time-decay half-life (14 days) so current taste beats stale history
      const ageDays = sig.playedAtMs && sig.playedAtMs > 0
        ? Math.max(0, (nowMs - sig.playedAtMs) / (24 * 3600 * 1000))
        : 0;
      const recencyWeight = Math.exp((-Math.LN2 * ageDays) / 14);

      // Piecewise engagement quality weight: finishing tracks to the end (+1.25) vs skipping early (-0.75)
      const engagementQuality =
        comp >= 0.80 ? 1.25
        : comp >= 0.60 ? 0.85
        : comp >= 0.25 ? 0.25
        : -0.75;

      const slotBaselineVol = model.volumeByTimeSlot[timeSlot] ?? 0.62;
      const volumeFactor = vol >= slotBaselineVol ? 1.15 : vol < 0.30 ? 0.65 : 1.0;

      const weightedDelta = Math.log2(plays + 1) * engagementQuality * volumeFactor * recencyWeight;
      if (weightedDelta >= 0) {
        const boost = Math.min(1.15, 0.24 * weightedDelta);
        entry.alpha = Math.min(90, entry.alpha + boost * 0.55);
        entry.emaScore = Math.min(0.99, entry.emaScore * 0.84 + Math.min(0.97, 0.60 + boost * 0.32) * 0.16);
      } else {
        const penalty = Math.min(0.85, Math.abs(weightedDelta) * 0.45);
        entry.beta = Math.min(90, entry.beta + penalty);
        entry.emaScore = Math.max(0.10, entry.emaScore * 0.88 + 0.22 * 0.12);
        entry.skips += 1;
      }

      entry.plays += 1;
      if (comp >= 0.68) entry.completions += 1;
      entry.avgVolumeRatio = entry.avgVolumeRatio * 0.80 + vol * 0.20;
      entry.avgCompletionRatio = entry.avgCompletionRatio * 0.80 + comp * 0.20;
      entry.lastUpdatedMs = nowMs;

      if (!model.timeSlotGenreAffinity[timeSlot]) {
        model.timeSlotGenreAffinity[timeSlot] = {};
      }
      const prevSlot = model.timeSlotGenreAffinity[timeSlot][genre] ?? 0.52;
      const slotTarget = weightedDelta >= 0 ? Math.min(0.98, 0.64 + comp * 0.30) : Math.max(0.12, 0.35 - Math.abs(weightedDelta) * 0.15);
      model.timeSlotGenreAffinity[timeSlot][genre] = prevSlot * 0.80 + slotTarget * 0.20;

      const prevVol = model.volumeByTimeSlot[timeSlot] ?? vol;
      model.volumeByTimeSlot[timeSlot] = prevVol * 0.80 + vol * 0.20;

      // Update per-timeSlot acoustic centroid from completed plays
      if (comp >= 0.60 && sig.durationMs && sig.durationMs >= 60_000) {
        const durSec = Math.round(sig.durationMs / 1000);
        const existingCent = model.acousticByTimeSlot[timeSlot] || {
          targetBpm: timeSlot.toLowerCase().includes('morning') ? 108 : timeSlot.toLowerCase().includes('night') ? 90 : 102,
          targetDurationSec: durSec,
          preferredVolumeRatio: vol,
          sampleCount: 0
        };
        existingCent.targetDurationSec = Math.round(existingCent.targetDurationSec * 0.78 + durSec * 0.22);
        existingCent.preferredVolumeRatio = existingCent.preferredVolumeRatio * 0.78 + vol * 0.22;
        existingCent.sampleCount += 1;
        model.acousticByTimeSlot[timeSlot] = existingCent;
      }
    }

    model.learningIterations += 1;
  }

  /**
   * Computes UCB + Bayesian contextual score for ranking genres and candidate songs.
   * Damps UCB exploration bonus when skips/dismissals outweigh completions.
   */
  public scoreGenreForContext(model: UserTasteModel, genre: string, timeSlot: string): number {
    const entry = model.genreWeights[genre];
    if (!entry) return 0.55; // Slight optimistic exploration prior for unseen genres
    const bayesianMean = entry.alpha / (entry.alpha + entry.beta);
    const totalTrials = entry.alpha + entry.beta;
    const negativeSkew = entry.dismissals > 0 || entry.skips > entry.completions;
    const ucbExplorationBonus = negativeSkew
      ? 0
      : Math.min(0.14, Math.sqrt((1.2 * Math.log(model.learningIterations + 2)) / totalTrials));
    const slotAffinity = model.timeSlotGenreAffinity[timeSlot]?.[genre] ?? 0.55;
    const negativePenalty = entry.dismissals * 0.08 + Math.max(0, entry.skips - entry.completions) * 0.04;
    const combined = 0.45 * bayesianMean + 0.30 * entry.emaScore + 0.25 * slotAffinity + ucbExplorationBonus - negativePenalty;
    return Math.min(0.99, Math.max(0.05, combined));
  }

  public buildLearningSummary(model: UserTasteModel, currentTimeSlot = 'Evening'): LearningSummary {
    const sorted = Object.values(model.genreWeights)
      .map((g) => ({
        genre: g.genre,
        score: this.scoreGenreForContext(model, g.genre, currentTimeSlot),
        skips: g.skips + g.dismissals,
        plays: g.plays,
        completions: g.completions,
        avgVol: g.avgVolumeRatio
      }))
      .sort((a, b) => b.score - a.score);

    const risingGenres = sorted.filter((g) => g.score >= 0.55).slice(0, 4).map((g) => g.genre);
    const coolingGenres = sorted.filter((g) => g.score < 0.46 || g.skips > 0).slice(0, 3).map((g) => g.genre);
    const hitRate = model.evaluatedOutcomes > 0
      ? Math.round((model.positiveOutcomes / model.evaluatedOutcomes) * 100)
      : 0;
    const slotVol = model.volumeByTimeSlot[currentTimeSlot]
      ?? (sorted.length > 0 ? sorted.reduce((acc, g) => acc + g.avgVol, 0) / sorted.length : 0.65);
    const avgVolumePercent = Math.round(slotVol * 100);

    // Progressive intelligence quotient scaling smoothly from 34% -> 99% as AI trains
    const rawIq =
      34 +
      Math.min(28, model.learningIterations * 2.2) +
      Math.min(22, model.evaluatedOutcomes * 2.5) +
      Math.min(10, sorted.length * 1.8) +
      (hitRate >= 65 ? 5 : 0);
    const intelligenceScorePercent = Math.min(99, Math.max(34, Math.round(rawIq)));

    let evolutionStage: EvolutionStage = 'STAGE_1_CALIBRATING';
    let evolutionLabel = 'Calibrating';
    if (model.learningIterations >= 25 || (model.evaluatedOutcomes >= 12 && hitRate >= 60)) {
      evolutionStage = 'STAGE_4_PREDICTIVE_ORACLE';
      evolutionLabel = 'Predictive Oracle';
    } else if (model.learningIterations >= 12 || model.evaluatedOutcomes >= 5) {
      evolutionStage = 'STAGE_3_DEEP_TASTE_SYNC';
      evolutionLabel = 'Deep Taste Sync';
    } else if (model.learningIterations >= 3 || sorted.length >= 2) {
      evolutionStage = 'STAGE_2_PATTERN_LOCK';
      evolutionLabel = 'Pattern Lock';
    }

    const learnedTraits: string[] = [];
    if (risingGenres.length > 0) {
      const lead = sorted[0];
      learnedTraits.push(`${lead.genre} leads your ${currentTimeSlot.toLowerCase()} affinity at ${Math.round(lead.score * 100)}% match.`);
    }
    learnedTraits.push(`Sweet-spot volume sits around ${avgVolumePercent}% during ${currentTimeSlot.toLowerCase()} listening.`);
    if (coolingGenres.length > 0) {
      learnedTraits.push(`Trimming exposure to ${coolingGenres.join(', ')} based on recent skips & dismissals.`);
    } else if (model.evaluatedOutcomes > 0) {
      learnedTraits.push(`${hitRate}% positive follow-through across ${model.evaluatedOutcomes} evaluated AI picks.`);
    }

    const narrative = model.evaluatedOutcomes > 0
      ? `Trained across ${model.learningIterations} updates (${hitRate}% match accuracy). ${evolutionLabel} honed in on ${risingGenres.slice(0, 3).join(', ') || 'your multi-genre flow'}.`
      : `Learning from your multi-app rotations (${sorted.length} active ${sorted.length === 1 ? 'genre' : 'genres'}). Drops refresh daily at 12:00 PM local time.`;

    return {
      learningIterations: model.learningIterations,
      evaluatedOutcomes: model.evaluatedOutcomes,
      hitRatePercent: hitRate,
      intelligenceScorePercent,
      evolutionStage,
      evolutionLabel,
      learnedTraits: learnedTraits.slice(0, 3),
      risingGenres,
      coolingGenres,
      avgVolumePercent,
      narrative
    };
  }

  /**
   * Selects top active user genres and assigns 2 songs per genre (8-10 total) + 2 Global Frontier wildcards.
   */
  /**
   * Minimum-Evidence Entrance Gate + Proportional 12-Slot Dominance Allocation.
   * Ensures a single exploratory/accidental play in a new genre cannot prematurely hijack the 12 picks,
   * and distributes 12 picks proportionally across qualified genres (fewer or more than 6 depending on user taste).
   */
  public computeGenreAllocation(
    model: UserTasteModel,
    payload: DailyDropRequest,
    timeSlot: string
  ): Array<{ genre: string; slots: number; score: number }> {
    const evidenceMap = new Map<string, { evidence: number; completions: number; plays: number }>();
    const bumpEvidence = (rawGenre: string, weight: number, completed = false, playInc = 1) => {
      const g = SelfLearningDiscoveryEngine.canonicalGenreName(rawGenre);
      if (!g || g === 'Pop & Global') return;
      const cur = evidenceMap.get(g) || { evidence: 0, completions: 0, plays: 0 };
      cur.evidence += weight;
      if (completed) cur.completions += 1;
      cur.plays += playInc;
      evidenceMap.set(g, cur);
    };

    if (payload.genreDistribution) {
      for (const [g, count] of Object.entries(payload.genreDistribution)) {
        if (count > 0) bumpEvidence(g, Math.min(8, count * 0.6), count >= 2, Math.max(1, Math.round(count)));
      }
    }

    for (const t of payload.recentTracks || []) {
      const plays = coerceNonNegativePlayCount(t.playCount);
      if (plays <= 0) continue;
      const g = SelfLearningDiscoveryEngine.inferGenre(t.title, t.artist, t.genre, model.artistGenreMap);
      const comp = typeof t.completionRatio === 'number' && t.completionRatio > 0
        ? t.completionRatio
        : (t.durationMs && t.listenedMs && t.durationMs > 0 ? t.listenedMs / t.durationMs : 0.78);
      const qualityWeight = (comp >= 0.65 ? 1.2 : comp >= 0.25 ? 0.5 : 0.1) * Math.log2(plays + 1);
      bumpEvidence(g, qualityWeight, comp >= 0.65, plays);
    }

    for (const a of [...(payload.topArtists || []), ...(payload.userTasteAnchors || [])]) {
      const inferred = SelfLearningDiscoveryEngine.inferGenre('', a, undefined, model.artistGenreMap);
      if (inferred !== 'Pop & Global') bumpEvidence(inferred, 1.4, true, 1);
    }

    for (const [g, entry] of Object.entries(model.genreWeights)) {
      if (entry.plays > 0 || entry.completions > 0) {
        bumpEvidence(g, Math.max(0.5, entry.completions * 1.1 + entry.plays * 0.4), entry.completions > 0, entry.plays);
      }
    }

    // Apply Entrance Gate: exclude single-play experimental blips unless overall pool is tiny
    const allEntries = Array.from(evidenceMap.entries());
    const qualifiedEntries = allEntries.filter(
      ([, stats]) => stats.evidence >= 1.6 || stats.completions >= 2 || stats.plays >= 2
    );
    const activeEntries = qualifiedEntries.length > 0
      ? qualifiedEntries
      : (allEntries.length > 0 ? allEntries : DEFAULT_FALLBACK_GENRES.map((g, i) => [g, { evidence: 4 - i * 0.5, completions: 2, plays: 2 }] as const));

    const maxEvidence = Math.max(1, ...activeEntries.map(([, s]) => s.evidence));
    const ranked = activeEntries
      .map(([genre, stats]) => {
        const banditScore = this.scoreGenreForContext(model, genre, timeSlot);
        const evidenceShare = stats.evidence / maxEvidence;
        const dominanceScore = Number((banditScore * 0.52 + evidenceShare * 0.48).toFixed(3));
        return { genre, score: dominanceScore };
      })
      .sort((a, b) => b.score - a.score)
      .slice(0, 6);

    // Proportional Hamilton Apportionment strictly summing to 12 picks
    const targetTotalGenreSlots = 12;
    const weightSum = Math.max(0.01, ranked.reduce((s, r) => s + Math.pow(r.score, 1.35), 0));
    const exactQuotas = ranked.map((r) => {
      const raw = (Math.pow(r.score, 1.35) / weightSum) * targetTotalGenreSlots;
      const floored = Math.max(1, Math.floor(raw));
      return { ...r, floored, remainder: raw - Math.floor(raw) };
    });

    // Adjust down if min-1 caused > 12, then distribute remaining slots by largest fractional remainder
    while (exactQuotas.reduce((s, q) => s + q.floored, 0) > targetTotalGenreSlots) {
      const idx = exactQuotas.length - 1;
      if (exactQuotas[idx].floored > 1) {
        exactQuotas[idx].floored -= 1;
      } else {
        exactQuotas.pop();
      }
    }

    let remaining = targetTotalGenreSlots - exactQuotas.reduce((s, q) => s + q.floored, 0);
    const byRemainder = [...exactQuotas].sort((a, b) => b.remainder - a.remainder);
    let ptr = 0;
    while (remaining > 0 && byRemainder.length > 0) {
      byRemainder[ptr % byRemainder.length].floored += 1;
      remaining -= 1;
      ptr += 1;
    }

    return exactQuotas.map((q) => ({
      genre: q.genre,
      slots: q.floored,
      score: q.score
    }));
  }

  private normalizeTitleOnly(title: string): string {
    return (title || '')
      .trim()
      .toLowerCase()
      .replace(/\s*\([^)]*\)/g, '')
      .replace(/\s*\[[^\]]*\]/g, '')
      .replace(/\s+-\s+(remaster(ed)?|radio edit|single version|live|deluxe|acoustic|explicit|clean).*$/i, '')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim()
      .replace(/\s+/g, '');
  }

  private normalizeArtistPrimary(artist: string): string {
    const primary = (artist || '')
      .trim()
      .toLowerCase()
      .split(/\s*(?:,|&|feat\.?|ft\.?|featuring|with|\bx\b)\s*/i)[0] || '';
    const cleaned = primary.replace(/[^a-z0-9]+/g, '');
    return cleaned === 'unknownartist' || cleaned === 'unknown' ? '' : cleaned;
  }

  private normalizeKey(title: string, artist: string): string {
    return `${this.normalizeTitleOnly(title)}::${this.normalizeArtistPrimary(artist)}`;
  }

  private isExcludedTrack(
    title: string,
    artist: string,
    excludedTitlesLower: Set<string>,
    excludedKeys: Set<string>,
    spotifyUri?: string | null,
    trackId?: string | null,
    excludedUris?: Set<string>,
    excludedIds?: Set<string>
  ): boolean {
    const rawTitleLower = title.trim().toLowerCase();
    const normTitleCompact = this.normalizeTitleOnly(title);
    if (!rawTitleLower && !normTitleCompact) return true;
    if (rawTitleLower && excludedTitlesLower.has(rawTitleLower)) return true;
    if (normTitleCompact && excludedTitlesLower.has(normTitleCompact)) return true;

    const kCompact = this.normalizeKey(title, artist);
    const kPipe = buildNormalizedMatchKey(title, artist);
    if (kCompact && excludedKeys.has(kCompact)) return true;
    if (kPipe && excludedKeys.has(kPipe)) return true;

    const normUri = normalizeSpotifyUri(spotifyUri);
    if (normUri && excludedUris?.has(normUri.toLowerCase())) return true;
    const rawId = (trackId || '').trim();
    if (rawId && !rawId.startsWith('disc:') && !rawId.startsWith('trk:') && excludedIds?.has(rawId)) return true;
    return false;
  }

  /**
   * Computes multi-factor Mathematical Acoustic Cosine Similarity + Satiety Readiness score.
   */
  public scoreCandidateAcousticCosine(
    model: UserTasteModel,
    candidate: ScoredDiscoveryTrack,
    timeSlot: string,
    highPlayArtistsLower: Set<string>
  ): number {
    const genreScore = this.scoreGenreForContext(model, candidate.genre, timeSlot);
    const cent = model.acousticByTimeSlot?.[timeSlot];
    const targetBpm = cent?.targetBpm || (timeSlot.toLowerCase().includes('morning') ? 108 : timeSlot.toLowerCase().includes('night') ? 90 : 102);
    const targetDurSec = cent?.targetDurationSec || 205;

    let acousticFit = 0.84;
    if (candidate.bpm && candidate.bpm > 40) {
      const candDurSec = candidate.durationMs ? candidate.durationMs / 1000 : targetDurSec;
      const uVec = [targetBpm / 180, targetDurSec / 360];
      const cVec = [candidate.bpm / 180, candDurSec / 360];
      const dot = uVec[0] * cVec[0] + uVec[1] * cVec[1];
      const mag = Math.sqrt(uVec[0] ** 2 + uVec[1] ** 2) * Math.sqrt(cVec[0] ** 2 + cVec[1] ** 2);
      const cos = mag > 0 ? Math.min(1, Math.max(0.4, dot / mag)) : 0.85;
      const bpmDeltaPenalty = Math.min(0.18, Math.abs(candidate.bpm - targetBpm) / 220);
      acousticFit = Math.max(0.55, cos - bpmDeltaPenalty);
    }

    const artistLower = candidate.artist.trim().toLowerCase().split(/[,&]|feat\.?|ft\.?/i)[0].trim();
    const artistSatietyPenalty = highPlayArtistsLower.has(artistLower) ? 0.08 : 0;

    const combined =
      0.40 * genreScore +
      0.32 * acousticFit +
      0.28 * Math.min(0.99, candidate.confidence || 0.90) -
      artistSatietyPenalty;

    return Number(Math.min(0.99, Math.max(0.60, combined)).toFixed(3));
  }

  /**
   * Grounds candidate songs strictly against Spotify (when connected), Deezer, iTunes & Songlink.
   * Rejects mismatched or hallucinated tracks instead of emitting wrong artist/song pairings.
   */
  private async verifyWithCatalog(
    candidate: ScoredDiscoveryTrack,
    options?: { spotifyClient?: SpotifyUserApiClient | null; fromCuratedBank?: boolean }
  ): Promise<{ track: ScoredDiscoveryTrack; verified: boolean }> {
    const baseIdentity = resolveCanonicalTrackIdentity({
      trackId: candidate.trackId,
      spotifyUri: candidate.spotifyUri,
      title: candidate.title,
      artist: candidate.artist,
      prefix: 'disc'
    });

    let matchedTitle = candidate.title.trim();
    let matchedArtist = candidate.artist.trim();
    let matchedAlbum = (candidate.album || '').trim();
    let matchedSpotifyUri = baseIdentity.spotifyUri;
    let matchedTrackId = baseIdentity.trackId;
    let matchedPreviewUrl = candidate.previewUrl;
    let matchedAlbumArtUrl = candidate.albumArtUrl;
    let matchedDurationMs = candidate.durationMs;
    let matchedBpm = candidate.bpm;
    let catalogConfirmed = false;
    let anyNetworkSucceeded = false;

    // 1. Live Spotify Search verification & authentic spotify:track:<id> attachment (when Spotify auth available)
    if (options?.spotifyClient) {
      try {
        const quotedQ = `track:"${candidate.title}" artist:"${candidate.artist}"`;
        let spRes = await options.spotifyClient.searchSpotify({
          query: quotedQ,
          types: ['track'],
          limit: 5
        });
        if (spRes.connected) anyNetworkSucceeded = true;
        let spHit = spRes.tracks.find((t) =>
          isStrictTitleArtistMatch(candidate.title, candidate.artist, t.title, t.artist)
        );
        if (!spHit && spRes.connected) {
          const broadRes = await options.spotifyClient.searchSpotify({
            query: `${candidate.title} ${candidate.artist}`,
            types: ['track'],
            limit: 6
          });
          spHit = broadRes.tracks.find((t) =>
            isStrictTitleArtistMatch(candidate.title, candidate.artist, t.title, t.artist)
          );
        }
        if (spHit) {
          catalogConfirmed = true;
          matchedTitle = spHit.title;
          matchedArtist = spHit.artist;
          matchedAlbum = spHit.album || matchedAlbum;
          matchedSpotifyUri = spHit.spotify_uri;
          matchedTrackId = spHit.spotify_id;
          matchedDurationMs = spHit.duration_ms || matchedDurationMs;
          matchedPreviewUrl = spHit.preview_url || matchedPreviewUrl;
        }
      } catch {
        // Non-fatal; continue to universal open catalogs
      }
    }

    // 2. Strict Universal Catalog (Deezer + iTunes + Songlink for Spotify URI if still missing)
    try {
      const profile = await resolveUniversalTrackProfile(candidate.title, candidate.artist);
      if (profile && isStrictTitleArtistMatch(candidate.title, candidate.artist, profile.cleanTitle, profile.cleanArtist)) {
        catalogConfirmed = true;
        anyNetworkSucceeded = true;
        if (!matchedSpotifyUri && profile.spotifyUri) {
          matchedSpotifyUri = profile.spotifyUri;
        }
        if (matchedTrackId.startsWith('disc:') || matchedTrackId.startsWith('trk:')) {
          matchedTrackId = profile.trackId;
        }
        if (!matchedAlbum && profile.albumName) matchedAlbum = profile.albumName;
        if (!matchedPreviewUrl && profile.previewUrl) matchedPreviewUrl = profile.previewUrl;
        if (!matchedAlbumArtUrl && profile.albumArtUrl) matchedAlbumArtUrl = profile.albumArtUrl;
        if (!options?.spotifyClient || !matchedSpotifyUri) {
          matchedTitle = profile.cleanTitle || matchedTitle;
          matchedArtist = profile.cleanArtist || matchedArtist;
        }
      }
    } catch {
      // Non-fatal
    }

    // 3. Direct strict Deezer check + BPM enrichment
    try {
      const q = encodeURIComponent(`${candidate.title} ${candidate.artist}`);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2400);
      const resp = await fetch(`https://api.deezer.com/search?q=${q}&limit=6`, {
        signal: controller.signal
      });
      clearTimeout(timer);
      if (resp.ok) {
        anyNetworkSucceeded = true;
        const json: any = await resp.json();
        const rows: any[] = Array.isArray(json?.data) ? json.data : [];
        const match = rows.find((item: any) =>
          isStrictTitleArtistMatch(
            candidate.title,
            candidate.artist,
            String(item?.title || ''),
            String(item?.artist?.name || '')
          )
        ) || null;

        if (match && match.title) {
          catalogConfirmed = true;
          if (match.id) {
            try {
              const bpmCtrl = new AbortController();
              const bpmTimer = setTimeout(() => bpmCtrl.abort(), 1400);
              const detailRes = await fetch(`https://api.deezer.com/track/${match.id}`, { signal: bpmCtrl.signal });
              clearTimeout(bpmTimer);
              if (detailRes.ok) {
                const detail: any = await detailRes.json();
                if (typeof detail?.bpm === 'number' && detail.bpm > 40) {
                  matchedBpm = Math.round(detail.bpm);
                }
              }
            } catch {
              // Non-fatal optional BPM enrichment
            }
          }
          if (matchedTrackId.startsWith('disc:') || matchedTrackId.startsWith('trk:')) {
            matchedTrackId = match.id ? `deezer:${match.id}` : matchedTrackId;
          }
          if (!matchedAlbum && match.album?.title) matchedAlbum = String(match.album.title).trim();
          if (!matchedPreviewUrl && typeof match.preview === 'string' && match.preview.startsWith('http')) {
            matchedPreviewUrl = match.preview;
          }
          if (!matchedAlbumArtUrl && typeof match.album?.cover_medium === 'string') {
            matchedAlbumArtUrl = match.album.cover_medium;
          }
          if (!matchedDurationMs && typeof match.duration === 'number') {
            matchedDurationMs = match.duration * 1000;
          }
        }
      }
    } catch {
      // Non-fatal
    }

    const finalIdentity = resolveCanonicalTrackIdentity({
      trackId: matchedTrackId,
      spotifyUri: matchedSpotifyUri,
      title: matchedTitle,
      artist: matchedArtist,
      prefix: 'disc'
    });

    // Strict accuracy guard:
    // - LLM candidates MUST be confirmed by at least one real music catalog matching BOTH title and artist.
    // - Vetted curated-bank tracks pass only if catalog confirmed OR external network was completely unavailable.
    const verified = catalogConfirmed || (Boolean(options?.fromCuratedBank) && !anyNetworkSucceeded);

    return {
      verified,
      track: {
        ...candidate,
        trackId: finalIdentity.trackId,
        spotifyUri: finalIdentity.spotifyUri,
        playCount: 0,
        bpm: matchedBpm,
        title: matchedTitle,
        artist: matchedArtist,
        album: matchedAlbum,
        previewUrl: matchedPreviewUrl,
        albumArtUrl: matchedAlbumArtUrl,
        durationMs: matchedDurationMs
      }
    };
  }

  public async recordFeedback(event: DiscoveryFeedbackEvent, supabaseOverride?: { supabaseUrl?: string; supabaseKey?: string }): Promise<LearningSummary> {
    const userId = SelfLearningDiscoveryEngine.normalizeUserId(event.userId);
    const model = await this.getOrCreateModel(userId, supabaseOverride);
    this.applyOutcomeUpdate(model, event);
    await this.saveModel(model, supabaseOverride);

    const dropDate = event.dropDate || SelfLearningDiscoveryEngine.getTodayDateKey();
    const genre = SelfLearningDiscoveryEngine.inferGenre(event.title, event.artist, event.genre);
    const identity = resolveCanonicalTrackIdentity({
      trackId: event.trackId,
      spotifyUri: event.spotifyUri,
      title: event.title,
      artist: event.artist,
      prefix: 'disc'
    });
    const completionRatio = event.durationMs && event.durationMs > 0 ? Math.min(1, (event.listenedMs || 0) / event.durationMs) : 0;
    const rewardScore =
      event.outcome === 'SAVED' ? 1.4
      : event.outcome === 'COMPLETED' ? 1.2
      : event.outcome === 'PLAYED' ? (completionRatio >= 0.65 ? 1.0 : 0.6)
      : event.outcome === 'SKIPPED' ? -0.65
      : event.outcome === 'DISMISSED' ? -0.85
      : -0.2;

    const sb = getLiveSupabaseClient(supabaseOverride?.supabaseUrl, supabaseOverride?.supabaseKey);
    if (sb) {
      try {
        const { data: existingRows } = await sb
          .from('discovery_recommendation_outcomes')
          .select('id')
          .eq('user_id', userId)
          .eq('drop_date', dropDate)
          .ilike('track_title', event.title.trim())
          .limit(1);

        const rowPayload = {
          user_id: userId,
          drop_date: dropDate,
          track_id: identity.trackId,
          spotify_uri: identity.spotifyUri || null,
          track_title: event.title.trim(),
          artist: event.artist.trim(),
          genre,
          sub_tier: event.subTier || 'GENRE_PICK',
          outcome_status: event.outcome,
          completion_ratio: completionRatio,
          volume_ratio: event.volumeRatio || 0,
          time_slot: event.timeSlot || 'Evening',
          listened_ms: event.listenedMs || 0,
          track_duration_ms: event.durationMs || 0,
          reward_score: rewardScore,
          updated_at: new Date().toISOString()
        };

        if (Array.isArray(existingRows) && existingRows[0]?.id) {
          await sb.from('discovery_recommendation_outcomes').update(rowPayload).eq('id', existingRows[0].id);
        } else {
          await sb.from('discovery_recommendation_outcomes').insert(rowPayload);
        }
      } catch {
        // Ignore if table not migrated yet
      }
    }

    // Mirror explicit Locks / Saves & Passes / Dismissals into long-term Supabase Taste Genome
    if (event.outcome === 'SAVED' || event.outcome === 'COMPLETED' || event.outcome === 'DISMISSED' || event.outcome === 'SKIPPED') {
      const actionTag = (event.outcome === 'SAVED' || event.outcome === 'COMPLETED') ? 'APPROVED_TRACK' : 'REJECTED_TRACK';
      SupabaseIntelligenceStore.recordAgentIntervention(
        {
          user_id: userId,
          trigger_transcript: `FOR_YOU_${event.subTier || 'GENRE_PICK'}_${event.outcome}`,
          computed_satiety: actionTag === 'APPROVED_TRACK' ? 18 : 82,
          agent_tts_response: `${event.outcome} ${event.title} by ${event.artist} (${genre})`,
          os_intent: actionTag,
          target_search_query: `${event.title} by ${event.artist}`,
          action: actionTag,
          track_id: identity.trackId,
          track_title: event.title,
          track_artist: event.artist,
          spotify_uri: identity.spotifyUri,
          circadian_daypart: event.timeSlot || 'Evening'
        },
        supabaseOverride
      ).catch(() => {});
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

  private async getCandidateClaudeModels(): Promise<string[]> {
    const now = Date.now();
    const apiKey = (process.env.ANTHROPIC_API_KEY || '').trim();
    const candidates: string[] = [];

    if (SelfLearningDiscoveryEngine.cachedWorkingClaudeModel) {
      candidates.push(SelfLearningDiscoveryEngine.cachedWorkingClaudeModel);
    }

    if (
      apiKey.length > 10 &&
      !apiKey.startsWith('your_') &&
      (!SelfLearningDiscoveryEngine.discoveredClaudeModels ||
        now - SelfLearningDiscoveryEngine.lastClaudeDiscoveryMs > 5 * 60_000)
    ) {
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 4000);
        const res = await fetch('https://api.anthropic.com/v1/models?limit=30', {
          method: 'GET',
          headers: {
            'x-api-key': apiKey,
            'anthropic-version': '2023-06-01'
          },
          signal: controller.signal
        });
        clearTimeout(timer);
        if (res.ok) {
          const body: any = await res.json();
          const ids = Array.isArray(body?.data)
            ? body.data.map((m: any) => String(m?.id || '').trim()).filter((id: string) => id.startsWith('claude-'))
            : [];
          if (ids.length > 0) {
            // Prioritize cost-efficient Claude Haiku first, with Sonnet as fallback
            const haiku55 = ids.filter((id: string) => id.includes('haiku') && id.includes('5-5'));
            const otherHaikus = ids.filter((id: string) => id.includes('haiku') && !id.includes('5-5'));
            const sonnets = ids.filter((id: string) => id.includes('sonnet'));
            const rest = ids.filter((id: string) => !id.includes('haiku') && !id.includes('sonnet'));
            SelfLearningDiscoveryEngine.discoveredClaudeModels = [...haiku55, ...otherHaikus, ...sonnets, ...rest];
            SelfLearningDiscoveryEngine.lastClaudeDiscoveryMs = now;
          }
        }
      } catch {
        // Non-fatal fallback to curated prioritized list
      }
    }

    for (const id of SelfLearningDiscoveryEngine.discoveredClaudeModels || []) {
      if (!candidates.includes(id)) candidates.push(id);
    }

    const envModel = (process.env.ANTHROPIC_MODEL || '').trim();
    if (envModel && !candidates.includes(envModel)) candidates.unshift(envModel);

    for (const id of [
      'claude-haiku-5-5',
      'claude-haiku-4-5',
      'claude-haiku-4-5-20251001',
      'claude-3-5-haiku-20241022',
      'claude-sonnet-5-5',
      'claude-sonnet-4-6',
      'claude-sonnet-4-5',
      'claude-sonnet-4-5-20250929'
    ]) {
      if (!candidates.includes(id)) candidates.push(id);
    }

    return candidates;
  }

  public async generateDailyForYouDrop(payload: DailyDropRequest): Promise<DailyDropResponse> {
    const userId = SelfLearningDiscoveryEngine.normalizeUserId(payload.userId, payload.userEmail);
    const supabaseOverride = { supabaseUrl: payload.supabaseUrl, supabaseKey: payload.supabaseKey };
    const model = await this.getOrCreateModel(userId, supabaseOverride);
    const dropDate = payload.dropDate || SelfLearningDiscoveryEngine.getNoonDropCycleKey(payload.timezone, payload.localHour);
    const timeSlot = payload.timeSlot || 'Evening';

    // 1. Online Self-Learning Step: cache real genres + evaluate outcomes + cross-app telemetry
    if (!model.artistGenreMap) model.artistGenreMap = {};
    if (payload.artistGenres) {
      for (const [k, v] of Object.entries(payload.artistGenres)) {
        const cleanK = k.trim().toLowerCase();
        const cleanV = SelfLearningDiscoveryEngine.canonicalGenreName(v);
        if (cleanK && cleanV && cleanV.toLowerCase() !== 'pop & global') {
          model.artistGenreMap[cleanK] = cleanV;
        }
      }
    }

    // Batch-classify any unknown played regional/global artists ONCE and cache persistently (skip for Free $0-AI mode)
    if (!payload.skipClaude && (isOpenAIActive() || isGeminiActive() || this.anthropic) && Array.isArray(payload.recentTracks) && payload.recentTracks.length > 0) {
      const unknownArtists = Array.from(
        new Set(
          payload.recentTracks
            .filter((t) => coerceNonNegativePlayCount(t.playCount) > 0 && t.artist && !t.artist.toLowerCase().includes('unknown'))
            .map((t) => t.artist.trim().split(/[,&]|feat\.?|ft\.?/i)[0].trim())
            .filter((a) => a.length >= 2 && SelfLearningDiscoveryEngine.inferGenre('', a, undefined, model.artistGenreMap) === 'Pop & Global')
        )
      ).slice(0, 14);
      if (unknownArtists.length > 0) {
        try {
          const clsPrompt = `Classify each music artist into ONE primary canonical genre (prefer exact East African/African & global labels like "Gikuyu / Mugithi", "Bongo Flava", "Gospel", "Kenyan Pop & Gengetone", "Ohangla & Benga", "Lingala & Rumba", "Afrobeats", "Amapiano", "Hip-Hop", "R&B", "Dancehall & Reggae", "Pop"). Return ONLY JSON object {"artist":"genre"}:\n${unknownArtists.join(', ')}`;
          const clsResp = isOpenAIActive()
            ? await callOpenAISingleShot(undefined, clsPrompt, 260)
            : isGeminiActive()
            ? await callGeminiSingleShot(undefined, clsPrompt, 260)
            : await (async () => {
                const models = await this.getCandidateClaudeModels();
                const primaryModel = models[0] || 'claude-haiku-4-5';
                return this.anthropic!.messages.create({
                  model: primaryModel,
                  max_tokens: 260,
                  messages: [{ role: 'user', content: clsPrompt }]
                });
              })();
          const clsText = (clsResp.content || []).filter((b: any) => b.type === 'text').map((b: any) => b.text).join('\n').trim();
          const objMatch = clsText.match(/\{[\s\S]*\}/);
          const mapParsed = JSON.parse(objMatch ? objMatch[0] : clsText);
          if (mapParsed && typeof mapParsed === 'object') {
            for (const [art, gen] of Object.entries(mapParsed)) {
              const canon = SelfLearningDiscoveryEngine.canonicalGenreName(String(gen || ''));
              if (canon && canon !== 'Pop & Global') {
                model.artistGenreMap[art.trim().toLowerCase()] = canon;
              }
            }
          }
        } catch {
          // Non-fatal fallback
        }
      }
    }

    if (Array.isArray(payload.yesterdayOutcomes) && payload.yesterdayOutcomes.length > 0) {
      for (const out of payload.yesterdayOutcomes) {
        this.applyOutcomeUpdate(model, { ...out, userId });
      }
    }
    if (Array.isArray(payload.recentTracks) && payload.recentTracks.length > 0) {
      this.applyCrossAppSignals(model, payload.recentTracks, payload.artistGenres);
    }

    // 2. Build Proportional Genre Allocation (Entrance Gate + strictly 12 songs weighted by true dominance)
    const genreAllocation = this.computeGenreAllocation(model, payload, timeSlot);
    const learningSummary = this.buildLearningSummary(model, timeSlot);

    // Build Comprehensive Zero-Repeat Exclusion Sets BEFORE cache check so cached picks matching newly played/detected songs are also caught!
    const excludedTitlesLower = new Set<string>();
    const excludedKeys = new Set<string>();
    const excludedUris = new Set<string>();
    const excludedIds = new Set<string>();
    const highPlayArtistsLower = new Set<string>();

    const addExclusionEntry = (
      rawTitle?: string | null,
      rawArtist?: string | null,
      rawUri?: string | null,
      rawId?: string | null
    ) => {
      const cleanT = String(rawTitle || '').trim();
      const cleanA = String(rawArtist || '').trim();
      if (cleanT) {
        excludedTitlesLower.add(cleanT.toLowerCase());
        const compactT = this.normalizeTitleOnly(cleanT);
        if (compactT) excludedTitlesLower.add(compactT);
        if (cleanA) {
          excludedKeys.add(this.normalizeKey(cleanT, cleanA));
          const normMatch = buildNormalizedMatchKey(cleanT, cleanA);
          if (normMatch) excludedKeys.add(normMatch);
        }
      }
      const normUri = normalizeSpotifyUri(rawUri);
      if (normUri) excludedUris.add(normUri.toLowerCase());
      const cleanId = String(rawId || '').trim();
      if (cleanId && !cleanId.startsWith('disc:') && !cleanId.startsWith('trk:')) {
        excludedIds.add(cleanId);
        const asId = extractSpotifyBase62Id(cleanId);
        if (asId) excludedUris.add(`spotify:track:${asId}`.toLowerCase());
      }
    };

    for (const t of payload.excludeTitles || []) {
      const raw = String(t || '').trim();
      if (!raw) continue;
      if (raw.includes('|') || raw.includes('::')) {
        const sep = raw.includes('|') ? '|' : '::';
        const parts = raw.split(sep);
        addExclusionEntry(parts[0], parts.slice(1).join(' '), null, null);
      } else {
        addExclusionEntry(raw, null, null, null);
      }
    }
    for (const k of payload.excludeKeys || []) {
      const raw = String(k || '').trim();
      if (!raw) continue;
      excludedKeys.add(raw.toLowerCase());
      const sep = raw.includes('|') ? '|' : raw.includes('::') ? '::' : '';
      if (sep) {
        const [tPart, aPart] = raw.split(sep);
        addExclusionEntry(tPart, aPart, null, null);
      }
    }
    for (const u of payload.excludedSpotifyUris || []) {
      const norm = normalizeSpotifyUri(u);
      if (norm) excludedUris.add(norm.toLowerCase());
    }
    for (const id of payload.excludedTrackIds || []) {
      const cleanId = String(id || '').trim();
      if (cleanId) excludedIds.add(cleanId);
    }

    // Exclude ALL detected/played tracks from Android MediaObserver & Spotify (not just playCount > 0)
    for (const r of payload.recentTracks || []) {
      addExclusionEntry(r.title, r.artist, r.spotifyUri, r.trackId);
      const plays = coerceNonNegativePlayCount(r.playCount);
      if (plays >= 3 && r.artist) {
        highPlayArtistsLower.add(r.artist.trim().toLowerCase().split(/[,&]|feat\.?|ft\.?/i)[0].trim());
      }
    }
    for (const v of payload.vaultTracks || []) {
      addExclusionEntry(v.title, v.artist, null, null);
    }
    // Permanent previously recommended track keys (all drops ever recorded, even older than 14 days)
    for (const k of model.recommendedTrackKeys || []) {
      const raw = String(k || '').trim();
      if (!raw) continue;
      excludedKeys.add(raw.toLowerCase());
      const sep = raw.includes('|') ? '|' : raw.includes('::') ? '::' : '';
      if (sep) {
        const [tPart, aPart] = raw.split(sep);
        if (tPart) excludedTitlesLower.add(tPart.toLowerCase());
      }
    }
    for (const u of model.recommendedSpotifyUris || []) {
      const norm = normalizeSpotifyUri(u);
      if (norm) excludedUris.add(norm.toLowerCase());
    }
    for (const id of model.recommendedTrackIds || []) {
      if (id) excludedIds.add(id);
    }
    for (const pastDrop of model.recentDrops || []) {
      if (pastDrop.dropDate === dropDate) continue;
      for (const prev of pastDrop.recommendations || []) {
        addExclusionEntry(prev.title, prev.artist, prev.spotifyUri, prev.trackId);
      }
    }

    // Load permanent backend TrackCards, LongTermMemory (MediaObserver + Spotify), and EventStore
    const spotifyClient = new SpotifyUserApiClient(userId, payload.spotifyAccessToken);
    const [trackCardSnap, longTermMem, hasSpotifyAuth] = await Promise.all([
      TrackCardStore.getSnapshot(userId).catch(() => ({ source: 'memory' as const, track_cards: [], playlists: [] })),
      SupabaseIntelligenceStore.fetchUserLongTermMemory(userId, supabaseOverride).catch(() => null),
      spotifyClient.hasLiveAuth().catch(() => false)
    ]);

    for (const card of trackCardSnap.track_cards || []) {
      addExclusionEntry(card.title, card.artist, card.spotify_uri, card.track_id);
      if (card.canonical_track_key) excludedKeys.add(card.canonical_track_key.toLowerCase());
      if ((card.verified_play_count || 0) >= 3 && card.artist) {
        highPlayArtistsLower.add(card.artist.trim().toLowerCase().split(/[,&]|feat\.?|ft\.?/i)[0].trim());
      }
    }
    if (longTermMem) {
      for (const t of longTermMem.allListenedTitles || []) {
        addExclusionEntry(t, null, null, null);
      }
      for (const u of longTermMem.allListenedUris || []) {
        const norm = normalizeSpotifyUri(u);
        if (norm) excludedUris.add(norm.toLowerCase());
      }
      for (const lt of [...(longTermMem.topTracksOverTime || []), ...(longTermMem.approvedTracks || []), ...(longTermMem.rejectedTracks || [])]) {
        addExclusionEntry(lt.title, lt.artist, (lt as any).spotifyUri, lt.trackId);
      }
    }
    try {
      const eventStore = new EventStore();
      for (const ev of eventStore.getRecentObservedTracks(500, userId)) {
        addExclusionEntry(ev.title, ev.artist, ev.spotifyUri, ev.trackId);
      }
    } catch {
      // Non-fatal
    }
    if (hasSpotifyAuth) {
      const [spRecent, spTop, spLiked] = await Promise.all([
        spotifyClient.getRecentlyPlayed(30).catch(() => ({ connected: false, items: [] })),
        spotifyClient.getTopTracks('medium_term', 25).catch(() => ({ connected: false, tracks: [] })),
        spotifyClient.getSavedLikedTracks(30, 0).catch(() => ({ connected: false, total: 0, tracks: [] }))
      ]);
      for (const it of spRecent.items || []) {
        addExclusionEntry(it.track.title, it.track.artist, it.track.spotify_uri, it.track.spotify_id);
      }
      for (const tr of [...(spTop.tracks || []), ...(spLiked.tracks || [])]) {
        addExclusionEntry(tr.title, tr.artist, tr.spotify_uri, tr.spotify_id);
      }
    }

    // Cost Protection: return today's cached 12:00 PM drop if still valid, verified, and unexcluded
    const requestedCap = typeof payload.maxTotalRecommendations === 'number' && payload.maxTotalRecommendations > 0
      ? Math.floor(payload.maxTotalRecommendations)
      : null;
    const minCachedThreshold = requestedCap ? Math.min(requestedCap, 2) : 10;
    const existingDropForCycle = (model.recentDrops || []).find(
      (d) => d.dropDate === dropDate && Array.isArray(d.recommendations) && d.recommendations.length >= minCachedThreshold
    );
    const forceRefreshCooldownMs = 15 * 60_000;
    if (
      existingDropForCycle &&
      (!payload.forceRefresh || Date.now() - (existingDropForCycle.generatedAtMs || 0) < forceRefreshCooldownMs)
    ) {
      const cleanCached = existingDropForCycle.recommendations
        .filter((r) => {
          const id = resolveCanonicalTrackIdentity({
            trackId: r.trackId,
            spotifyUri: r.spotifyUri,
            title: r.title,
            artist: r.artist,
            prefix: 'disc'
          });
          return !this.isExcludedTrack(
            r.title,
            r.artist,
            excludedTitlesLower,
            excludedKeys,
            id.spotifyUri,
            id.trackId,
            excludedUris,
            excludedIds
          );
        })
        .map((r) => {
          const id = resolveCanonicalTrackIdentity({
            trackId: r.trackId,
            spotifyUri: r.spotifyUri,
            title: r.title,
            artist: r.artist,
            prefix: 'disc'
          });
          return {
            ...r,
            trackId: id.trackId,
            spotifyUri: id.spotifyUri,
            playCount: coerceNonNegativePlayCount(r.playCount)
          };
        });

      const finalCached = requestedCap ? cleanCached.slice(0, requestedCap) : cleanCached;
      if (finalCached.length >= minCachedThreshold) {
        return {
          status: 'ok',
          dropDate,
          nextRefreshLocalLabel: '12:00 PM daily (your timezone)',
          isAiGenerated: !payload.skipClaude,
          modelUsed: 'cached-daily-drop',
          wildcardCount: finalCached.filter((r) => r.isWildcard).length,
          learningSummary,
          genreAllocation,
          recommendations: finalCached
        };
      }
    }

    const activeGenresLower = new Set(genreAllocation.map((g) => g.genre.toLowerCase()));

    const desiredWildcards = 3;
    const genreTargetCount = genreAllocation.reduce((sum, g) => sum + g.slots, 0); // Always 12 across active user genres
    const totalTargetCount = genreTargetCount + desiredWildcards;
    let candidates: Array<ScoredDiscoveryTrack & { _fromBank?: boolean }> = [];
    let isAiGenerated = false;
    let modelUsed = payload.skipClaude ? 'free-open-catalog-2picks' : 'tunemind-contextual-bandit';

    // 4. AI Deep Reasoning Synthesis (Single cost-capped Haiku-first pass per daily cycle; skipped for Free $0 mode)
    if (!payload.skipClaude && (isOpenAIActive() || isGeminiActive() || this.anthropic)) {
      const allocationInstruction = genreAllocation
        .map((g) => `- ${g.slots} UNHEARD real songs in "${g.genre}" (learned affinity ${(g.score * 100).toFixed(0)}%)`)
        .join('\n');

      const outcomeFeedback = [
        ...(payload.yesterdayOutcomes || []).slice(0, 12).map(
          (o) => `${o.title} by ${o.artist} [${o.genre || 'Music'}]: ${o.outcome} (listened ${Math.round(((o.listenedMs || 0) / Math.max(1, o.durationMs || 180000)) * 100)}%, volume ${Math.round((o.volumeRatio || 0.6) * 100)}%)`
        ),
        ...(payload.playedFeedback || []).slice(0, 6).map((s) => `PLAYED & LIKED: ${s}`),
        ...(payload.dismissedFeedback || []).slice(0, 6).map((s) => `DISMISSED/SKIPPED: ${s}`)
      ].join('\n');

      const foreignSceneHints = [
        'Afro-Francophone',
        'Electronic & Afro House',
        'Latin & Urbano',
        'Dancehall & Reggae',
        'Nu-Jazz & Neo-Soul',
        'Bossa Nova & MPB',
        'Alternative Indie'
      ].filter((g) => !activeGenresLower.has(g.toLowerCase()));

      const excludedPromptSample = Array.from(excludedTitlesLower).filter((x) => x.length >= 2).slice(0, 180).join(', ');

      const prompt = `TuneMind Daily Drop (${dropDate}, ${timeSlot}, Vol ${payload.avgVolumeRatio ? Math.round(payload.avgVolumeRatio * 100) : learningSummary.avgVolumePercent}%).
Top Artists: ${(payload.topArtists || []).concat(payload.userTasteAnchors || []).slice(0, 8).join(', ') || 'Diverse global'}
Feedback: ${outcomeFeedback.split('\n').slice(0, 6).join('; ') || 'Calibrating'}
STRICT ACCURACY RULE: Every song MUST have its 100% authentic exact official title and exact official artist. NEVER guess or swap artists.
Quota (${totalTargetCount} UNHEARD real songs = ${genreTargetCount} across your genres + 3 global wildcards):
${allocationInstruction}
- 3 "GLOBAL_WILDCARD" songs in 3 COMPLETELY NEW DISTINCT genres across the world NOT in [${genreAllocation.map((g) => g.genre).join(', ')}] (e.g., ${foreignSceneHints.slice(0, 5).join(', ')}), each with 1 crisp bridge reason.
NEVER repeat any excluded song: ${excludedPromptSample}
Return ONLY a raw JSON array:
[{"title":"","artist":"","album":"","genre":"","category":"DOPAMINE_FLOW|PALATE_CLEANSER|CHRONO_RESTORE|NOVEL_EXPLORATION","subTier":"GENRE_PICK|GLOBAL_WILDCARD","isWildcard":false,"reason":"1 short sentence","confidence":0.93}]`;

      if (isOpenAIActive() || isGeminiActive()) {
        try {
          const aiResp = isOpenAIActive()
            ? await callOpenAISingleShot(undefined, prompt, 1850)
            : await callGeminiSingleShot(undefined, prompt, 1850);
          const rawText = (aiResp.content || [])
            .filter((b: any) => b.type === 'text')
            .map((b: any) => b.text)
            .join('\n')
            .trim();
          const match = rawText.match(/\[[\s\S]*\]/);
          const parsed = JSON.parse(match ? match[0] : rawText);
          if (Array.isArray(parsed) && parsed.length > 0) {
            isAiGenerated = true;
            for (const row of parsed) {
              if (!row) continue;
              const title = String(row.title || '').trim();
              const artist = String(row.artist || '').trim();
              if (!title || !artist) continue;
              const genre = SelfLearningDiscoveryEngine.inferGenre(title, artist, row.genre);
              const isWildcard = Boolean(row.isWildcard || row.subTier === 'GLOBAL_WILDCARD') && !activeGenresLower.has(genre.toLowerCase());
              const learnedGenreScore = this.scoreGenreForContext(model, genre, timeSlot);
              const llmConf = Math.min(0.99, Math.max(0.75, Number(row.confidence || 0.91)));
              const mlScore = Number((llmConf * 0.55 + learnedGenreScore * 0.45).toFixed(3));
              const identity = resolveCanonicalTrackIdentity({
                trackId: row.trackId || row.track_id,
                spotifyUri: row.spotifyUri || row.spotify_uri,
                title,
                artist,
                prefix: 'disc'
              });
              candidates.push({
                trackId: identity.trackId,
                spotifyUri: identity.spotifyUri,
                playCount: 0,
                title,
                artist,
                album: String(row.album || '').trim(),
                genre,
                category: String(row.category || (isWildcard ? 'NOVEL_EXPLORATION' : 'DOPAMINE_FLOW')),
                subTier: isWildcard ? 'GLOBAL_WILDCARD' : 'GENRE_PICK',
                reason: String(row.reason || `Picked for your ${timeSlot} ${genre} rotation.`).trim(),
                confidence: mlScore,
                mlScore,
                isWildcard,
                _fromBank: false
              });
            }
          }
        } catch (err) {
          console.warn('[SelfLearningDiscovery] Gemini discovery warning:', (err as Error)?.message || err);
        }
      } else {
        const candidateModels = await this.getCandidateClaudeModels();
        for (const candidateModelId of candidateModels.slice(0, 2)) {
        try {
          const aiResp = await this.anthropic.messages.create({
            model: candidateModelId,
            max_tokens: 1850,
            messages: [{ role: 'user', content: prompt }]
          });
          const rawText = (aiResp.content || [])
            .filter((b: any) => b?.type === 'text')
            .map((b: any) => b.text)
            .join('\n')
            .trim();

          const match = rawText.match(/\[[\s\S]*\]/);
          const parsed = JSON.parse(match ? match[0] : rawText);
          if (Array.isArray(parsed) && parsed.length > 0) {
            SelfLearningDiscoveryEngine.cachedWorkingClaudeModel = candidateModelId;
            isAiGenerated = true;
            modelUsed = `${candidateModelId}+contextual-bandit`;
            for (const row of parsed) {
              const title = String(row.title || '').trim();
              const artist = String(row.artist || '').trim();
              if (!title || !artist) continue;
              if (this.isExcludedTrack(title, artist, excludedTitlesLower, excludedKeys, row.spotifyUri || row.spotify_uri, row.trackId || row.track_id, excludedUris, excludedIds)) continue;

              const genre = SelfLearningDiscoveryEngine.inferGenre(title, artist, row.genre);
              const isWildcard = Boolean(row.isWildcard || row.subTier === 'GLOBAL_WILDCARD') && !activeGenresLower.has(genre.toLowerCase());
              const learnedGenreScore = this.scoreGenreForContext(model, genre, timeSlot);
              const llmConf = Math.min(0.99, Math.max(0.75, Number(row.confidence || 0.91)));
              const mlScore = Number((llmConf * 0.55 + learnedGenreScore * 0.45).toFixed(3));

              const identity = resolveCanonicalTrackIdentity({
                trackId: row.trackId || row.track_id,
                spotifyUri: row.spotifyUri || row.spotify_uri,
                title,
                artist,
                prefix: 'disc'
              });

              candidates.push({
                trackId: identity.trackId,
                spotifyUri: identity.spotifyUri,
                playCount: 0,
                title,
                artist,
                album: String(row.album || '').trim(),
                genre,
                category: String(row.category || (isWildcard ? 'NOVEL_EXPLORATION' : 'DOPAMINE_FLOW')),
                subTier: isWildcard ? 'GLOBAL_WILDCARD' : 'GENRE_PICK',
                reason: String(row.reason || `Picked for your ${timeSlot} ${genre} rotation.`).trim(),
                confidence: mlScore,
                mlScore,
                isWildcard,
                _fromBank: false
              });
            }
            if (candidates.length > 0) break;
          }
        } catch (err) {
          console.warn(`[SelfLearningDiscovery] Model ${candidateModelId} notice:`, (err as Error)?.message);
        }
        }
      }
    }

    // 5. Catalog-first verification: verify LLM picks FIRST and discard any unverified/mismatched title-artist pairs
    const activeSpotifyClient = hasSpotifyAuth ? spotifyClient : null;
    const verifiedLlmCandidates: ScoredDiscoveryTrack[] = [];
    if (candidates.length > 0) {
      const llmChecked = await Promise.all(
        candidates.map((c) => this.verifyWithCatalog(c, { spotifyClient: activeSpotifyClient, fromCuratedBank: Boolean(c._fromBank) }))
      );
      for (const res of llmChecked) {
        if (!res.verified) continue;
        if (
          this.isExcludedTrack(
            res.track.title,
            res.track.artist,
            excludedTitlesLower,
            excludedKeys,
            res.track.spotifyUri,
            res.track.trackId,
            excludedUris,
            excludedIds
          )
        ) {
          continue;
        }
        verifiedLlmCandidates.push(res.track);
      }
    }

    // Assemble proportional genre picks + 3 uncharted wildcards from verified LLM pool, then verified bank backfill
    const finalGenrePicks: ScoredDiscoveryTrack[] = [];
    const seenDropKeys = new Set<string>();
    const bankQueue: Array<{ track: ScoredDiscoveryTrack; targetType: 'genre' | 'wildcard' }> = [];

    for (const alloc of genreAllocation) {
      let countForAlloc = 0;
      for (const c of verifiedLlmCandidates) {
        if (countForAlloc >= alloc.slots) break;
        if (c.isWildcard || c.genre.toLowerCase() !== alloc.genre.toLowerCase()) continue;
        const k = this.normalizeKey(c.title, c.artist);
        if (seenDropKeys.has(k)) continue;
        seenDropKeys.add(k);
        finalGenrePicks.push({ ...c, isWildcard: false, subTier: 'GENRE_PICK' });
        countForAlloc += 1;
      }

      if (countForAlloc < alloc.slots) {
        const bankMatches = CURATED_GLOBAL_DISCOVERY_BANK.filter(
          (b) => !b.isWildcard && b.genre.toLowerCase() === alloc.genre.toLowerCase()
        );
        for (const b of bankMatches) {
          if (countForAlloc >= alloc.slots) break;
          const k = this.normalizeKey(b.title, b.artist);
          if (seenDropKeys.has(k) || this.isExcludedTrack(b.title, b.artist, excludedTitlesLower, excludedKeys, b.spotifyUri, b.trackId, excludedUris, excludedIds)) continue;
          seenDropKeys.add(k);
          const mlScore = Number((b.confidence * 0.5 + alloc.score * 0.5).toFixed(3));
          bankQueue.push({
            track: { ...b, confidence: mlScore, mlScore, isWildcard: false, subTier: 'GENRE_PICK' },
            targetType: 'genre'
          });
          countForAlloc += 1;
        }
      }
    }

    // Top up remaining genre slots if any active genre pool ran tight
    let projectedGenreCount = finalGenrePicks.length + bankQueue.filter((x) => x.targetType === 'genre').length;
    if (projectedGenreCount < genreTargetCount) {
      for (const b of CURATED_GLOBAL_DISCOVERY_BANK.filter((x) => !x.isWildcard)) {
        if (projectedGenreCount >= genreTargetCount) break;
        const k = this.normalizeKey(b.title, b.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(b.title, b.artist, excludedTitlesLower, excludedKeys, b.spotifyUri, b.trackId, excludedUris, excludedIds)) continue;
        seenDropKeys.add(k);
        const score = this.scoreGenreForContext(model, b.genre, timeSlot);
        const mlScore = Number((b.confidence * 0.5 + score * 0.5).toFixed(3));
        bankQueue.push({
          track: { ...b, confidence: mlScore, mlScore, isWildcard: false, subTier: 'GENRE_PICK' },
          targetType: 'genre'
        });
        projectedGenreCount += 1;
      }
    }

    // Collect 3 Wildcards strictly from genres NOT in user's active genres (prefer 3 distinct genres)
    const finalWildcards: ScoredDiscoveryTrack[] = [];
    const seenWildcardGenres = new Set<string>();

    for (const c of verifiedLlmCandidates) {
      if (finalWildcards.length >= desiredWildcards) break;
      if (!c.isWildcard || activeGenresLower.has(c.genre.toLowerCase())) continue;
      if (seenWildcardGenres.has(c.genre.toLowerCase())) continue;
      const k = this.normalizeKey(c.title, c.artist);
      if (seenDropKeys.has(k)) continue;
      seenDropKeys.add(k);
      seenWildcardGenres.add(c.genre.toLowerCase());
      finalWildcards.push({ ...c, isWildcard: true, subTier: 'GLOBAL_WILDCARD' });
    }

    let projectedWildcardCount = finalWildcards.length;
    if (projectedWildcardCount < desiredWildcards) {
      const bankWildcards = CURATED_GLOBAL_DISCOVERY_BANK.filter(
        (b) => b.isWildcard && !activeGenresLower.has(b.genre.toLowerCase())
      );
      for (const w of bankWildcards) {
        if (projectedWildcardCount >= desiredWildcards) break;
        if (seenWildcardGenres.has(w.genre.toLowerCase())) continue;
        const k = this.normalizeKey(w.title, w.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(w.title, w.artist, excludedTitlesLower, excludedKeys, w.spotifyUri, w.trackId, excludedUris, excludedIds)) continue;
        seenDropKeys.add(k);
        seenWildcardGenres.add(w.genre.toLowerCase());
        bankQueue.push({
          track: { ...w, isWildcard: true, subTier: 'GLOBAL_WILDCARD' },
          targetType: 'wildcard'
        });
        projectedWildcardCount += 1;
      }
      for (const w of bankWildcards) {
        if (projectedWildcardCount >= desiredWildcards) break;
        const k = this.normalizeKey(w.title, w.artist);
        if (seenDropKeys.has(k) || this.isExcludedTrack(w.title, w.artist, excludedTitlesLower, excludedKeys, w.spotifyUri, w.trackId, excludedUris, excludedIds)) continue;
        seenDropKeys.add(k);
        bankQueue.push({
          track: { ...w, isWildcard: true, subTier: 'GLOBAL_WILDCARD' },
          targetType: 'wildcard'
        });
        projectedWildcardCount += 1;
      }
    }

    if (bankQueue.length > 0) {
      const verifiedBank = await Promise.all(
        bankQueue.map(async (item) => {
          const checked = await this.verifyWithCatalog(item.track, {
            spotifyClient: activeSpotifyClient,
            fromCuratedBank: true
          });
          return { ...checked, targetType: item.targetType };
        })
      );
      for (const vb of verifiedBank) {
        if (!vb.verified) continue;
        if (
          this.isExcludedTrack(
            vb.track.title,
            vb.track.artist,
            excludedTitlesLower,
            excludedKeys,
            vb.track.spotifyUri,
            vb.track.trackId,
            excludedUris,
            excludedIds
          )
        ) {
          continue;
        }
        if (vb.targetType === 'wildcard') {
          finalWildcards.push(vb.track);
        } else {
          finalGenrePicks.push(vb.track);
        }
      }
    }

    const rankedVerified = [...finalGenrePicks, ...finalWildcards]
      .filter(
        (tr) =>
          !this.isExcludedTrack(
            tr.title,
            tr.artist,
            excludedTitlesLower,
            excludedKeys,
            tr.spotifyUri,
            tr.trackId,
            excludedUris,
            excludedIds
          )
      )
      .map((tr) => {
        const cosineScore = this.scoreCandidateAcousticCosine(model, tr, timeSlot, highPlayArtistsLower);
        return {
          ...tr,
          acousticCosineScore: cosineScore,
          mlScore: cosineScore,
          confidence: cosineScore
        };
      })
      .sort((a, b) => {
        if (a.isWildcard !== b.isWildcard) return a.isWildcard ? 1 : -1;
        return b.mlScore - a.mlScore;
      });

    const verified = requestedCap
      ? rankedVerified.filter((tr) => !tr.isWildcard).slice(0, requestedCap).length >= requestedCap
        ? rankedVerified.filter((tr) => !tr.isWildcard).slice(0, requestedCap)
        : rankedVerified.slice(0, requestedCap)
      : rankedVerified;

    // Permanently remember every emitted track so future days NEVER repeat them even after 14+ days
    const updatedKeys = new Set<string>(model.recommendedTrackKeys || []);
    const updatedIds = new Set<string>(model.recommendedTrackIds || []);
    const updatedUris = new Set<string>(model.recommendedSpotifyUris || []);
    for (const item of verified) {
      updatedKeys.add(this.normalizeKey(item.title, item.artist));
      const normMatch = buildNormalizedMatchKey(item.title, item.artist);
      if (normMatch) updatedKeys.add(normMatch);
      if (item.trackId && !item.trackId.startsWith('disc:') && !item.trackId.startsWith('trk:')) {
        updatedIds.add(item.trackId);
      }
      if (item.spotifyUri) {
        updatedUris.add(item.spotifyUri.toLowerCase());
      }
    }
    model.recommendedTrackKeys = Array.from(updatedKeys).slice(-5000);
    model.recommendedTrackIds = Array.from(updatedIds).slice(-3000);
    model.recommendedSpotifyUris = Array.from(updatedUris).slice(-3000);

    // Record daily drop into model history + save updated model & Supabase slate snapshot
    model.recentDrops = [
      { dropDate, generatedAtMs: Date.now(), recommendations: verified },
      ...model.recentDrops.filter((d) => d.dropDate !== dropDate)
    ].slice(0, 14);
    await this.saveModel(model, supabaseOverride);

    const sb = getLiveSupabaseClient(supabaseOverride?.supabaseUrl, supabaseOverride?.supabaseKey);
    if (sb && verified.length > 0) {
      try {
        const { data: alreadySeeded } = await sb
          .from('discovery_recommendation_outcomes')
          .select('track_title, artist')
          .eq('user_id', userId)
          .eq('drop_date', dropDate);
        const existingSet = new Set(
          (Array.isArray(alreadySeeded) ? alreadySeeded : []).map((r: any) =>
            this.normalizeKey(String(r.track_title || ''), String(r.artist || ''))
          )
        );
        const rowsToInsert = verified
          .filter((item) => !existingSet.has(this.normalizeKey(item.title, item.artist)))
          .map((item) => ({
            user_id: userId,
            drop_date: dropDate,
            track_id: item.trackId,
            spotify_uri: item.spotifyUri || null,
            track_title: item.title,
            artist: item.artist,
            genre: item.genre,
            sub_tier: item.subTier,
            outcome_status: 'PENDING',
            completion_ratio: 0,
            volume_ratio: payload.avgVolumeRatio || 0,
            time_slot: timeSlot,
            listened_ms: 0,
            track_duration_ms: item.durationMs || 0,
            reward_score: 0,
            reason: item.reason,
            confidence: item.mlScore,
            preview_url: item.previewUrl || null,
            album_art_url: item.albumArtUrl || null
          }));
        if (rowsToInsert.length > 0) {
          await sb.from('discovery_recommendation_outcomes').insert(rowsToInsert);
        }
      } catch {
        // Safe non-blocking fallback if table wasn't provisioned yet
      }
    }

    return {
      status: 'ok',
      dropDate,
      nextRefreshLocalLabel: '12:00 PM daily (your timezone)',
      isAiGenerated,
      modelUsed,
      wildcardCount: verified.filter((r) => r.isWildcard).length,
      learningSummary: this.buildLearningSummary(model, timeSlot),
      genreAllocation,
      recommendations: verified
    };
  }
}
