import { SpotifyUserApiClient } from './SpotifyUserApiClient';
import { SpotifyConnectionStore } from '../storage/SpotifyConnectionStore';

describe('SpotifyUserApiClient', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    SpotifyUserApiClient.clearCachedToken('test-user-1');
    jest.restoreAllMocks();
  });

  test('searchSpotify filters out explicit tracks when kidsSafeOnly is true', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        tracks: {
          items: [
            {
              id: 'clean12345678901234567',
              uri: 'spotify:track:clean12345678901234567',
              name: 'Soft Acoustic Breeze',
              artists: [{ name: 'Mali Acoustic' }],
              album: { name: 'Calm Morning', release_date: '2024-01-01' },
              duration_ms: 195000,
              popularity: 68,
              explicit: false
            },
            {
              id: 'explc12345678901234567',
              uri: 'spotify:track:explc12345678901234567',
              name: 'Late Night Explicit',
              artists: [{ name: 'Club Artist' }],
              album: { name: 'Afterhours', release_date: '2024-02-01' },
              duration_ms: 210000,
              popularity: 75,
              explicit: true
            }
          ]
        },
        artists: { items: [] },
        playlists: { items: [] }
      })
    }) as any;

    const client = new SpotifyUserApiClient('test-user-1', 'mock_client_spotify_token_1234567890');
    const result = await client.searchSpotify({
      query: 'calm',
      types: ['track'],
      limit: 6,
      kidsSafeOnly: true
    });

    expect(result.connected).toBe(true);
    expect(result.tracks).toHaveLength(1);
    expect(result.tracks[0].title).toBe('Soft Acoustic Breeze');
    expect(result.tracks[0].spotify_uri).toBe('spotify:track:clean12345678901234567');
  });

  test('retries once on HTTP 401 by refreshing encrypted Spotify refresh token', async () => {
    const mockRecord = {
      userId: 'test-user-1',
      clientId: 'client_abc1234567890',
      refreshToken: 'refresh_tok_xyz',
      displayName: 'Alex',
      product: 'premium',
      lastDeviceName: 'Pixel Phone',
      connectedAt: new Date().toISOString(),
      lastRefreshedAt: new Date().toISOString()
    };
    jest.spyOn(SpotifyConnectionStore, 'getConnection').mockResolvedValue(mockRecord);
    jest.spyOn(SpotifyConnectionStore, 'upsertConnection').mockResolvedValue({
      persistedToSupabase: false,
      record: mockRecord
    });

    const fetchMock = jest
      .fn()
      // 1st API call -> 401
      .mockResolvedValueOnce({
        ok: false,
        status: 401,
        text: async () => 'Token expired'
      })
      // Refresh token POST
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          access_token: 'fresh_spotify_token_abcdefghij',
          expires_in: 3600
        })
      })
      // Retry GET /me/playlists
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          items: [
            {
              id: 'pl_1',
              uri: 'spotify:playlist:pl_1',
              name: 'Nairobi Chill',
              owner: { display_name: 'Alex' },
              tracks: { total: 18 }
            }
          ]
        })
      });

    global.fetch = fetchMock as any;

    const client = new SpotifyUserApiClient('test-user-1', 'expired_client_token_1234567890');
    const playlistsRes = await client.getUserPlaylists(10);

    expect(playlistsRes.connected).toBe(true);
    expect(playlistsRes.playlists).toHaveLength(1);
    expect(playlistsRes.playlists[0].name).toBe('Nairobi Chill');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  test('handles restricted editorial/followed playlist gracefully while returning playable context_uri', async () => {
    global.fetch = jest
      .fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 403,
        text: async () => 'Forbidden'
      });

    const client = new SpotifyUserApiClient('test-user-1', 'valid_client_spotify_token_12345');
    const res = await client.getPlaylistTracks('spotify:playlist:37i9dQZEVXcQ9COmYvdajy', 20);

    expect(res.restricted).toBe(true);
    expect(res.context_uri).toBe('spotify:playlist:37i9dQZEVXcQ9COmYvdajy');
    expect(res.tracks).toEqual([]);
    expect(res.note).toContain('PLAY_SPOTIFY_CONTEXT');
  });

  test('getPlaybackStateAndDevices returns active device, devices list, and queue', async () => {
    global.fetch = jest
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          is_playing: true,
          shuffle_state: true,
          repeat_state: 'context',
          progress_ms: 42000,
          device: { id: 'dev_phone', name: 'Pixel 8', type: 'Smartphone', is_active: true, is_restricted: false, volume_percent: 70 },
          item: {
            id: 'trk_111111111111111111',
            uri: 'spotify:track:trk_111111111111111111',
            name: 'Suzanna',
            artists: [{ name: 'Sauti Sol' }],
            album: { name: 'Midnight Train' },
            duration_ms: 230000,
            popularity: 72,
            explicit: false
          }
        })
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          devices: [
            { id: 'dev_phone', name: 'Pixel 8', type: 'Smartphone', is_active: true, is_restricted: false, volume_percent: 70 },
            { id: 'dev_speaker', name: 'Living Room Speaker', type: 'Speaker', is_active: false, is_restricted: false, volume_percent: 55 }
          ]
        })
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          queue: [
            {
              id: 'trk_222222222222222222',
              uri: 'spotify:track:trk_222222222222222222',
              name: 'My Baby',
              artists: [{ name: 'Bien' }],
              album: { name: 'Alusa' },
              duration_ms: 200000,
              popularity: 70,
              explicit: false
            }
          ]
        })
      }) as any;

    const client = new SpotifyUserApiClient('test-user-1', 'valid_client_spotify_token_12345');
    const snap = await client.getPlaybackStateAndDevices();

    expect(snap.connected).toBe(true);
    expect(snap.is_playing).toBe(true);
    expect(snap.shuffle_state).toBe(true);
    expect(snap.repeat_state).toBe('context');
    expect(snap.active_device?.name).toBe('Pixel 8');
    expect(snap.devices).toHaveLength(2);
    expect(snap.current_track?.title).toBe('Suzanna');
    expect(snap.queue[0].title).toBe('My Baby');
  });
});
