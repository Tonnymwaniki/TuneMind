import { TrackItem, AnchorWeight, CircadianWindow, DailyStudyTile } from '../types';

export const initialNowPlaying: TrackItem = {
  id: 'np-001',
  title: 'Midnight Thoughts',
  artist: 'Luna Rivers',
  albumCoverUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=400',
  genre: 'Lo-Fi Atmosphere',
  bpm: 84,
  durationSeconds: 216,
  playCount: 9,
  satietyScore: 58,
  fatigueCategory: 'Vocal Fatigue',
  recentSkips: 3,
  sourceApp: 'Spotify'
};

export const initialHighSatietyTracks: TrackItem[] = [
  {
    id: 'trk-1',
    title: 'Stay With Me',
    artist: 'The Neon Hearts',
    albumCoverUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400',
    genre: 'Synth Pop',
    bpm: 128,
    durationSeconds: 204,
    playCount: 14,
    satietyScore: 84,
    fatigueCategory: 'Critical Loop',
    recentSkips: 4,
    cooldownActive: true,
    recoveryProgress: 0.42,
    cooldownDaysRemaining: 3.5,
    sourceApp: 'Spotify'
  },
  {
    id: 'trk-2',
    title: 'Hyperion Drive',
    artist: 'Solar Pulse',
    albumCoverUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=400',
    genre: 'Cyber Electro',
    bpm: 132,
    durationSeconds: 230,
    playCount: 16,
    satietyScore: 76,
    fatigueCategory: 'Loop Decay',
    recentSkips: 2,
    cooldownActive: true,
    recoveryProgress: 0.20,
    cooldownDaysRemaining: 4.0,
    sourceApp: 'Spotify'
  },
  {
    id: 'trk-3',
    title: 'Velvet Horizons',
    artist: 'Kaelen',
    albumCoverUrl: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=400',
    genre: 'Neo-Soul',
    bpm: 92,
    durationSeconds: 188,
    playCount: 7,
    satietyScore: 62,
    fatigueCategory: 'Preemptive',
    recentSkips: 1,
    cooldownActive: true,
    recoveryProgress: 0.80,
    cooldownDaysRemaining: 1.0,
    sourceApp: 'Apple Music'
  }
];

export const candidateCooldownTracks: TrackItem[] = [
  {
    id: 'cand-1',
    title: 'Neon Horizon',
    artist: 'Vektor Drift',
    albumCoverUrl: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400',
    genre: 'Synthwave',
    bpm: 128,
    durationSeconds: 240,
    playCount: 19,
    satietyScore: 78,
    fatigueCategory: 'Critical Loop',
    recentSkips: 3,
    sourceApp: 'Spotify'
  },
  {
    id: 'cand-2',
    title: 'Ghost in the Machine',
    artist: 'Komorebi',
    albumCoverUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=400',
    genre: 'Lo-Fi Beats',
    bpm: 82,
    durationSeconds: 175,
    playCount: 14,
    satietyScore: 64,
    fatigueCategory: 'Vocal Fatigue',
    recentSkips: 2,
    sourceApp: 'YT Music'
  },
  {
    id: 'cand-3',
    title: 'Solar Flare',
    artist: 'Aura Void',
    albumCoverUrl: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400',
    genre: 'Ambient Chill',
    bpm: 110,
    durationSeconds: 210,
    playCount: 8,
    satietyScore: 45,
    fatigueCategory: 'Healthy Staple',
    recentSkips: 0,
    sourceApp: 'Spotify'
  }
];

export const initialPalateCleansers: TrackItem[] = [
  {
    id: 'pc-1',
    title: 'Electric Dreams',
    artist: 'The Wanderers',
    albumCoverUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400',
    genre: 'Synth Pop • High Energy',
    bpm: 128,
    durationSeconds: 194,
    playCount: 11,
    satietyScore: 18,
    fatigueCategory: 'Matches groove + novelty',
    sourceApp: 'Spotify'
  },
  {
    id: 'pc-2',
    title: 'Better Days',
    artist: 'Juno Vale',
    albumCoverUrl: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=400',
    genre: 'Indie Pop • Warm Vocals',
    bpm: 126,
    durationSeconds: 205,
    playCount: 5,
    satietyScore: 22,
    fatigueCategory: 'Matches vocal anchor',
    sourceApp: 'Spotify'
  },
  {
    id: 'pc-3',
    title: 'Lost in Motion',
    artist: 'Solar State',
    albumCoverUrl: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=400',
    genre: 'Electronic • Dynamic Build',
    bpm: 124,
    durationSeconds: 230,
    playCount: 3,
    satietyScore: 15,
    fatigueCategory: 'Matches structure anchor',
    sourceApp: 'Spotify'
  }
];

export const anchorWeights: AnchorWeight[] = [
  { name: 'Groove & Rhythm Anchor', percentage: 38, driftDelta: '+6%', description: 'Rhythm, bass, tempo lock' },
  { name: 'Vocal & Melody Anchor', percentage: 34, driftDelta: '-7% reset', description: 'Vocals, harmony, linguistic load' },
  { name: 'Dynamic & Structure Anchor', percentage: 28, driftDelta: '+1% stable', description: 'Build-ups, drops, frequency balance' }
];

export const circadianWindows: CircadianWindow[] = [
  { title: 'Morning Window (08:00 – 12:00)', timeRange: 'Sweet Spot: 10:30 AM – 1:00 PM', receptivityPercent: 92, descriptor: 'High Harmonic Tolerance', isFatigueProne: false },
  { title: 'Midday Drive (13:00 – 18:00)', timeRange: '41% Habitual Loops Zone', receptivityPercent: 68, descriptor: 'Groove & Dynamic Tolerance', isFatigueProne: false },
  { title: 'Late Night (> 22:00)', timeRange: 'Low BPM Ambient Advised', receptivityPercent: 24, descriptor: 'Treble & Vocal Clashing Alert', isFatigueProne: true }
];

export const studyDays: DailyStudyTile[] = [
  { dayNumber: 1, dayLabel: 'Mon', status: 'COMPLETED', minutesTracked: 142, skipsAnalyzed: 6, entropyScore: 0.88 },
  { dayNumber: 2, dayLabel: 'Tue', status: 'COMPLETED', minutesTracked: 95, skipsAnalyzed: 3, entropyScore: 0.72 },
  { dayNumber: 3, dayLabel: 'Wed', status: 'COMPLETED', minutesTracked: 180, skipsAnalyzed: 8, entropyScore: 0.94 },
  { dayNumber: 4, dayLabel: 'Thu', status: 'COMPLETED', minutesTracked: 110, skipsAnalyzed: 4, entropyScore: 0.81 },
  { dayNumber: 5, dayLabel: 'Fri', status: 'IN_PROGRESS', minutesTracked: 74, skipsAnalyzed: 2, entropyScore: 0.65 },
  { dayNumber: 6, dayLabel: 'Sat', status: 'SCHEDULED', minutesTracked: 0, skipsAnalyzed: 0, entropyScore: 0.0 },
  { dayNumber: 7, dayLabel: 'Sun', status: 'SCHEDULED', minutesTracked: 0, skipsAnalyzed: 0, entropyScore: 0.0 }
];

export const userProfile = {
  name: 'Tonny',
  email: 'tonny@tunemind.neuro',
  tier: 'Acoustic Intelligence Tier 1 • Active',
  monitoredHours: '87.4h',
  syncedApps: '3 Apps',
  confidence: '91%'
};
