import { ClaudeMusicReasoningProvider } from './ClaudeMusicReasoningProvider';
import { TrackCardStore } from '../storage/TrackCardStore';
import { SpotifyUserApiClient } from '../spotify/SpotifyUserApiClient';
import { SupabaseIntelligenceStore } from '../storage/SupabaseIntelligenceStore';

describe('ClaudeMusicReasoningProvider v1 Spotify Premium Agent', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('isDirectPlaySongCommand keeps conversational mood expressions in proposal dialogue while allowing explicit play on Spotify Premium Connect', () => {
    expect(
      ClaudeMusicReasoningProvider.isDirectPlaySongCommand({
        userCommandRaw: 'I feel stressed today',
        hasSpotifyPremiumConnected: true
      })
    ).toBe(false);

    expect(
      ClaudeMusicReasoningProvider.isDirectPlaySongCommand({
        userCommandRaw: "I'm feeling tired after work, maybe play something chill?",
        hasSpotifyPremiumConnected: true
      })
    ).toBe(false);

    expect(
      ClaudeMusicReasoningProvider.isDirectPlaySongCommand({
        userCommandRaw: 'Play Calm Down by Rema',
        hasSpotifyPremiumConnected: true
      })
    ).toBe(true);
  });

  test('executes dual-source TrackCard + Spotify tools, redacts raw OAuth token from Claude prompt, and proposes mood track conversationally', async () => {
    const uid = 'v1-agent-test-user';
    await TrackCardStore.syncSnapshot(uid, {
      track_cards: [
        {
          canonical_track_key: 'overplayed banger::artist x',
          track_id: 'trk_overplayed',
          title: 'Overplayed Banger',
          artist: 'Artist X',
          genre: 'Afrobeats',
          is_in_vault: false
        }
      ],
      play_events: [
        { play_event_id: 'ev_1', canonical_track_key: 'overplayed banger::artist x', played_at_ms: 1000, listened_ms: 180000 },
        { play_event_id: 'ev_2', canonical_track_key: 'overplayed banger::artist x', played_at_ms: 2000, listened_ms: 180000 },
        { play_event_id: 'ev_3', canonical_track_key: 'overplayed banger::artist x', played_at_ms: 3000, listened_ms: 180000 }
      ]
    });

    jest.spyOn(SupabaseIntelligenceStore, 'syncTracksAndTelemetryToSupabase').mockResolvedValue(undefined as any);
    jest.spyOn(SupabaseIntelligenceStore, 'recordAgentIntervention').mockResolvedValue(undefined as any);
    jest.spyOn(SupabaseIntelligenceStore, 'fetchUserLongTermMemory').mockResolvedValue({
      userId: uid,
      isLiveSupabaseConnected: false,
      intelligenceMaturityLevel: 'CALIBRATED',
      totalHistoricalInterventions: 4,
      totalTracksLearned: 12,
      approvedTracks: [],
      rejectedTracks: [],
      topArtistsOverTime: [{ artist: 'Sauti Sol', totalPlays: 12 }],
      topTracksOverTime: [],
      recentInterventions: [],
      allListenedTitles: [],
      allListenedUris: [],
      circadianPatternNotes: ['Calm evening rotation']
    });

    jest.spyOn(SpotifyUserApiClient.prototype, 'hasLiveAuth').mockResolvedValue(true);
    jest.spyOn(SpotifyUserApiClient.prototype, 'searchSpotify').mockResolvedValue({
      connected: true,
      tracks: [
        {
          spotify_id: '0123456789abcdefghijkl',
          spotify_uri: 'spotify:track:0123456789abcdefghijkl',
          title: 'Suzanna',
          artist: 'Sauti Sol',
          album: 'Midnight Train',
          duration_ms: 228000,
          popularity: 74,
          explicit: false
        }
      ],
      artists: [],
      playlists: [],
      albums: []
    });

    const provider = new ClaudeMusicReasoningProvider();
    jest.spyOn(provider, 'isAvailable').mockResolvedValue(true);
    jest.spyOn(provider, 'lookupOpenCatalogTrack').mockResolvedValue({
      trackId: 'spotify:track:0123456789abcdefghijkl',
      title: 'Suzanna',
      artist: 'Sauti Sol',
      album: 'Midnight Train',
      spotifyUri: 'spotify:track:0123456789abcdefghijkl'
    });

    const capturedPrompts: string[] = [];
    const callSpy = jest
      .spyOn(provider as any, 'callClaudeMessagesWithTools')
      .mockImplementationOnce((async (_sys: any, msgs: any[]) => {
        capturedPrompts.push(JSON.stringify(msgs));
        return {
          content: [
            { type: 'tool_use', id: 'tc_cards', name: 'query_tunemind_track_cards', input: { filter: 'overplayed' } },
            { type: 'tool_use', id: 'tc_sp', name: 'search_spotify_live', input: { query: 'Sauti Sol acoustic calm', types: ['track'] } }
          ]
        };
      }) as any)
      .mockImplementationOnce((async (_sys: any, msgs: any[]) => {
        capturedPrompts.push(JSON.stringify(msgs));
        return {
          content: [
            {
              type: 'tool_use',
              id: 'tc_submit',
              name: 'submit_agent_turn',
              input: {
                voice_agent_tts: "Sounds like you need some breathing room. Your heavy Afrobeats track is sitting at high repetition, so how about something warm and grounded like Suzanna by Sauti Sol? Want me to put that on, or go even softer?",
                flow_state_analysis: 'Mood curation: calm acoustic unwind after overplayed track',
                calculated_satiety_percentage: 24,
                action: 'PROPOSE_TRACK',
                track_title: 'Suzanna',
                track_artist: 'Sauti Sol',
                spotify_uri: 'spotify:track:0123456789abcdefghijkl',
                never_open_external_app: true,
                expect_follow_up: true
              }
            }
          ]
        };
      }) as any);

    const secretToken = 'SECRET_SPOTIFY_BEARER_DO_NOT_LEAK_TO_CLAUDE';
    const result = await provider.evaluateSatiety({
      user_id: uid,
      timestamp_utc: new Date().toISOString(),
      circadian_daypart: 'Evening',
      current_track: { title: 'Standing By', artist: '', source_player: 'com.spotify.music' },
      user_command: 'I feel stressed today',
      rolling_history: [],
      current_satiety_percentage: 50,
      spotify_access_token: secretToken,
      app_state: { spotify_premium_connected: true, stay_in_tunemind_enabled: true }
    });

    expect(callSpy).toHaveBeenCalledTimes(2);
    expect(capturedPrompts.join(' ')).not.toContain(secretToken);
    expect(capturedPrompts[1]).toContain('Overplayed Banger');
    expect(capturedPrompts[1]).toContain('spotify:track:0123456789abcdefghijkl');
    expect(result.agent_tool_call?.action).toBe('PROPOSE_TRACK');
    expect(result.agent_tool_call?.track_title).toBe('Suzanna');
    expect(result.agent_tool_call?.spotify_uri).toBe('spotify:track:0123456789abcdefghijkl');
    expect(result.agent_tool_call?.expect_follow_up).toBe(true);
    expect(result.os_execution_command.intent).toBe('NONE');
  });

  test('maps PLAY_SPOTIFY_CONTEXT and SEEK_TO_POSITION to OS_SPOTIFY_CONTEXT and OS_MEDIA_SEEK cleanly', async () => {
    const uid = 'v1-agent-ctx-user';
    jest.spyOn(SupabaseIntelligenceStore, 'syncTracksAndTelemetryToSupabase').mockResolvedValue(undefined as any);
    jest.spyOn(SupabaseIntelligenceStore, 'recordAgentIntervention').mockResolvedValue(undefined as any);
    jest.spyOn(SupabaseIntelligenceStore, 'fetchUserLongTermMemory').mockResolvedValue({
      userId: uid,
      isLiveSupabaseConnected: false,
      intelligenceMaturityLevel: 'DEVELOPING',
      totalHistoricalInterventions: 1,
      totalTracksLearned: 2,
      approvedTracks: [],
      rejectedTracks: [],
      topArtistsOverTime: [],
      topTracksOverTime: [],
      recentInterventions: [],
      allListenedTitles: [],
      allListenedUris: [],
      circadianPatternNotes: []
    });
    jest.spyOn(SpotifyUserApiClient.prototype, 'hasLiveAuth').mockResolvedValue(true);

    const provider = new ClaudeMusicReasoningProvider();
    jest.spyOn(provider, 'isAvailable').mockResolvedValue(true);

    jest.spyOn(provider as any, 'callClaudeMessagesWithTools').mockResolvedValueOnce({
      content: [
        {
          type: 'tool_use',
          id: 'tc_ctx',
          name: 'submit_agent_turn',
          input: {
            voice_agent_tts: 'Putting on your Discover Weekly playlist right inside TuneMind with shuffle turned on.',
            flow_state_analysis: 'Playing Spotify playlist context silently',
            calculated_satiety_percentage: 20,
            action: 'PLAY_SPOTIFY_CONTEXT',
            playlist_name: 'Discover Weekly',
            context_uri: 'https://open.spotify.com/playlist/37i9dQZEVXcQ9COmYvdajy',
            shuffle_enabled: true,
            never_open_external_app: true,
            expect_follow_up: false
          }
        }
      ]
    });

    const ctxResult = await provider.evaluateSatiety({
      user_id: uid,
      timestamp_utc: new Date().toISOString(),
      circadian_daypart: 'Morning',
      current_track: { title: 'Standing By', artist: '', source_player: 'com.spotify.music' },
      user_command: 'Play my Discover Weekly on shuffle',
      rolling_history: [],
      current_satiety_percentage: 50,
      app_state: { spotify_premium_connected: true }
    });

    expect(ctxResult.agent_tool_call?.action).toBe('PLAY_SPOTIFY_CONTEXT');
    expect(ctxResult.agent_tool_call?.context_uri).toBe('spotify:playlist:37i9dQZEVXcQ9COmYvdajy');
    expect(ctxResult.agent_tool_call?.shuffle_enabled).toBe(true);
    expect(ctxResult.os_execution_command.intent).toBe('OS_SPOTIFY_CONTEXT');
    expect(ctxResult.os_execution_command.context_uri).toBe('spotify:playlist:37i9dQZEVXcQ9COmYvdajy');

    jest.spyOn(provider as any, 'callClaudeMessagesWithTools').mockResolvedValueOnce({
      content: [
        {
          type: 'tool_use',
          id: 'tc_seek',
          name: 'submit_agent_turn',
          input: {
            voice_agent_tts: 'Jumping forward 30 seconds.',
            flow_state_analysis: 'Seeking playback +30s',
            calculated_satiety_percentage: 25,
            action: 'SEEK_TO_POSITION',
            seek_delta_ms: 30000,
            expect_follow_up: false
          }
        }
      ]
    });

    const seekResult = await provider.evaluateSatiety({
      user_id: uid,
      timestamp_utc: new Date().toISOString(),
      circadian_daypart: 'Morning',
      current_track: { title: 'Suzanna', artist: 'Sauti Sol', source_player: 'com.spotify.music' },
      user_command: 'Skip ahead 30 seconds',
      rolling_history: [],
      current_satiety_percentage: 50,
      app_state: { spotify_premium_connected: true }
    });

    expect(seekResult.agent_tool_call?.action).toBe('SEEK_TO_POSITION');
    expect(seekResult.agent_tool_call?.seek_delta_ms).toBe(30000);
    expect(seekResult.os_execution_command.intent).toBe('OS_MEDIA_SEEK');
    expect(seekResult.os_execution_command.seek_delta_ms).toBe(30000);
  });
});
