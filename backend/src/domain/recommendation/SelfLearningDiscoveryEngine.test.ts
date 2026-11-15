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

  it('allocates 2 songs per detected genre + 2 global wildcards and excludes already-heard tracks', async () => {
    const engine = new SelfLearningDiscoveryEngine();
    const res = await engine.generateDailyForYouDrop({
      userId: `alloc-user-${Date.now()}`,
      dropDate: '2026-11-14',
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
    expect(res.recommendations.length).toBeGreaterThanOrEqual(8);
    expect(res.genreAllocation.every((g) => g.slots === 2)).toBe(true);
    const titles = res.recommendations.map((r) => r.title.toLowerCase());
    expect(titles).not.toContain('kese (dance)');
    expect(titles).not.toContain('huwezi jua');
    expect(titles).not.toContain('humble.');
    expect(res.recommendations.some((r) => r.isWildcard)).toBe(true);
  });
});
