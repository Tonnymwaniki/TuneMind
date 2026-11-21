import { isStrictTitleArtistMatch } from '../../api/v1/musicProfiles';
import { TrackCardStore } from '../storage/TrackCardStore';
import { SelfLearningDiscoveryEngine } from './SelfLearningDiscoveryEngine';

describe('SelfLearningDiscoveryEngine', () => {
  it('boosts genres on full high-volume plays and decays genres on early skips', async () => {
    const engine = new SelfLearningDiscoveryEngine();
    const userId = `test-user-${Date.now()}`;
    const model = await engine.getOrCreateModel(userId);

    const beforeAfro = engine.scoreGenreForContext(model, 'Afrobeats', 'Evening');
    const beforePop = engine.scoreGenreForContext(model, 'Pop', 'Evening');

    engine.applyOutcomeUpdate(model, {
      userId,
      title: 'Kese (Dance)',
      artist: 'Wizkid',
      genre: 'Afrobeats',
      outcome: 'COMPLETED',
      listenedMs: 210_000,
      durationMs: 210_000,
      volumeRatio: 0.85,
      timeSlot: 'Evening'
    });

    engine.applyOutcomeUpdate(model, {
      userId,
      title: 'Espresso',
      artist: 'Sabrina Carpenter',
      genre: 'Pop',
      outcome: 'DISMISSED',
      listenedMs: 12_000,
      durationMs: 180_000,
      volumeRatio: 0.35,
      timeSlot: 'Evening'
    });

    const afterAfro = engine.scoreGenreForContext(model, 'Afrobeats', 'Evening');
    const afterPop = engine.scoreGenreForContext(model, 'Pop', 'Evening');

    expect(afterAfro).toBeGreaterThan(beforeAfro);
    expect(afterPop).toBeLessThan(beforePop);
    expect(model.evaluatedOutcomes).toBe(2);
    expect(model.positiveOutcomes).toBe(1);
  });

  it('allocates proportional 12 slots across qualified genres + 3 global wildcards and excludes already-heard tracks', async () => {
    const engine = new SelfLearningDiscoveryEngine();
    const res = await engine.generateDailyForYouDrop({
      userId: `alloc-user-${Date.now()}`,
      dropDate: '2026-11-15@12PM',
      timezone: 'Africa/Nairobi',
      timeSlot: 'Evening',
      avgVolumeRatio: 0.78,
      recentTracks: [
        { title: 'Kese (Dance)', artist: 'Wizkid', genre: 'Afrobeats', playCount: 4, volumeRatio: 0.82 },
        { title: 'Huwezi Jua', artist: 'Mbosso', genre: 'Bongo Flava', playCount: 5, volumeRatio: 0.75 },
        { title: 'HUMBLE.', artist: 'Kendrick Lamar', genre: 'Hip-Hop', playCount: 3, volumeRatio: 0.88 }
      ],
      excludeTitles: ['Kese (Dance)', 'Huwezi Jua', 'HUMBLE.']
    });

    expect(res.status).toBe('ok');
    const totalGenreSlots = res.genreAllocation.reduce((sum, g) => sum + g.slots, 0);
    expect(totalGenreSlots).toBe(12);
    const activeGenres = new Set(res.genreAllocation.map((g) => g.genre.toLowerCase()));

    const wildcards = res.recommendations.filter((r) => r.isWildcard);
    expect(wildcards.length).toBeGreaterThan(0);
    expect(wildcards.length).toBeLessThanOrEqual(3);
    for (const w of wildcards) {
      expect(activeGenres.has(w.genre.toLowerCase())).toBe(false);
    }

    const titles = res.recommendations.map((r) => r.title.toLowerCase());
    expect(titles).not.toContain('kese (dance)');
    expect(titles).not.toContain('huwezi jua');
    expect(titles).not.toContain('humble.');
    expect(res.learningSummary.intelligenceScorePercent).toBeGreaterThanOrEqual(34);
    expect(res.learningSummary.learnedTraits.length).toBeGreaterThan(0);
  });

  it('never repeats tracks from earlier days, TrackCardStore (even 0-play Spotify/MediaObserver detections), or title/remaster variants', async () => {
    const userId = `norepeat-user-${Date.now()}`;
    await TrackCardStore.syncSnapshot(userId, {
      track_cards: [
        {
          canonical_track_key: 'soweto|victony',
          track_id: 'spotify:track:1234567890123456789012',
          spotify_uri: 'spotify:track:1234567890123456789012',
          title: 'Soweto (feat. Tempoe) - Remastered',
          artist: 'Victony, Tempoe',
          primary_source_package: 'com.spotify.music'
        }
      ]
    });

    const engine = new SelfLearningDiscoveryEngine();
    const day1 = await engine.generateDailyForYouDrop({
      userId,
      dropDate: '2026-11-14@12PM',
      timezone: 'Africa/Nairobi',
      timeSlot: 'Evening',
      recentTracks: [
        { title: 'Ku Lo Sa', artist: 'Oxlade', genre: 'Afrobeats', playCount: 0, sourceApp: 'com.spotify.music' },
        { title: 'Calm Down', artist: 'Rema', genre: 'Afrobeats', playCount: 2, sourceApp: 'com.spotify.music' }
      ],
      userTasteAnchors: ['Afrobeats', 'Bongo Flava', 'Hip-Hop']
    });

    const day1Keys = new Set(day1.recommendations.map((r) => `${r.title.toLowerCase()}::${r.artist.toLowerCase()}`));
    expect(day1Keys.has('soweto::victony')).toBe(false);
    expect(day1.recommendations.some((r) => r.title.toLowerCase().includes('ku lo sa'))).toBe(false);
    expect(day1.recommendations.some((r) => r.title.toLowerCase().includes('calm down'))).toBe(false);

    // Simulate Day 2: model shouldn't repeat any Day 1 pick
    const day2 = await engine.generateDailyForYouDrop({
      userId,
      dropDate: '2026-11-15@12PM',
      timezone: 'Africa/Nairobi',
      timeSlot: 'Evening',
      userTasteAnchors: ['Afrobeats', 'Bongo Flava', 'Hip-Hop']
    });

    for (const rec of day2.recommendations) {
      const k = `${rec.title.toLowerCase()}::${rec.artist.toLowerCase()}`;
      expect(day1Keys.has(k)).toBe(false);
    }
  });

  it('strictly rejects wrong-artist and remix mismatches in catalog identity matching', () => {
    expect(isStrictTitleArtistMatch('Soweto', 'Victony', 'Soweto', 'Victony & Tempoe')).toBe(true);
    expect(isStrictTitleArtistMatch('Free Mind', 'Tems', 'Free Mind', 'Another Random Artist')).toBe(false);
    expect(isStrictTitleArtistMatch('Calm Down', 'Rema', 'Calm Down (TikTok Remix)', 'Rema')).toBe(false);
  });

  it('computes 12:00 PM local-timezone daily drop cycle keys accurately', () => {
    const sampleUtc = new Date('2026-11-15T08:30:00.000Z'); // 11:30 AM EAT (before 12 PM)
    expect(SelfLearningDiscoveryEngine.getNoonDropCycleKey('Africa/Nairobi', undefined, sampleUtc)).toBe('2026-11-14@12PM');

    const afterNoonUtc = new Date('2026-11-15T09:05:00.000Z'); // 12:05 PM EAT (after 12 PM)
    expect(SelfLearningDiscoveryEngine.getNoonDropCycleKey('Africa/Nairobi', undefined, afterNoonUtc)).toBe('2026-11-15@12PM');
  });
});
