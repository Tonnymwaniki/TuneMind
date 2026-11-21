import { SpotifyConnectionStore } from '../storage/SpotifyConnectionStore';

const SPOTIFY_API_BASE = 'https://api.spotify.com/v1';
const SPOTIFY_TOKEN_ENDPOINT = 'https://accounts.spotify.com/api/token';

export interface SpotifyTrackSummary {
  spotify_id: string;
  spotify_uri: string;
  title: string;
  artist: string;
  album: string;
  release_date?: string;
  duration_ms: number;
  popularity: number;
  explicit: boolean;
  preview_url?: string | null;
  external_url?: string | null;
}

export interface SpotifyArtistSummary {
  spotify_id: string;
  spotify_uri: string;
  name: string;
  genres: string[];
  popularity: number;
  followers_total: number;
  external_url?: string | null;
}

export interface SpotifyPlaylistSummary {
  spotify_id: string;
  spotify_uri: string;
  name: string;
  owner: string;
  track_count: number;
  description?: string;
  is_public?: boolean;
  external_url?: string | null;
}

export interface SpotifyAlbumSummary {
  spotify_id: string;
  spotify_uri: string;
  name: string;
  artist: string;
  release_date?: string;
  total_tracks: number;
  external_url?: string | null;
}

export interface SpotifyDeviceSummary {
  id: string;
  name: string;
  type: string;
  is_active: boolean;
  is_restricted: boolean;
  volume_percent: number | null;
}

export interface SpotifyPlaybackSnapshot {
  connected: boolean;
  is_playing: boolean;
  shuffle_state?: boolean;
  repeat_state?: 'off' | 'context' | 'track' | string;
  progress_ms?: number;
  active_device?: SpotifyDeviceSummary | null;
  current_track?: SpotifyTrackSummary | null;
  devices: SpotifyDeviceSummary[];
  queue: SpotifyTrackSummary[];
  note?: string;
}

interface CachedAccessToken {
  accessToken: string;
  expiresAtMs: number;
  source: 'client' | 'refresh';
}

const tokenCacheByUser = new Map<string, CachedAccessToken>();

function mapSpotifyTrack(raw: any): SpotifyTrackSummary | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  const title = String(raw.name || '').trim();
  if (!id || !title) return null;
  const artistsArr = Array.isArray(raw.artists) ? raw.artists : [];
  const artist = artistsArr
    .map((a: any) => String(a?.name || '').trim())
    .filter(Boolean)
    .join(', ') || 'Unknown Artist';
  const uri = String(raw.uri || `spotify:track:${id}`).trim();
  return {
    spotify_id: id,
    spotify_uri: uri.startsWith('spotify:') ? uri : `spotify:track:${id}`,
    title,
    artist,
    album: String(raw.album?.name || '').trim(),
    release_date: raw.album?.release_date ? String(raw.album.release_date) : undefined,
    duration_ms: Number(raw.duration_ms || 0),
    popularity: Number(raw.popularity || 0),
    explicit: Boolean(raw.explicit),
    preview_url: raw.preview_url ? String(raw.preview_url) : null,
    external_url: raw.external_urls?.spotify ? String(raw.external_urls.spotify) : null
  };
}

function mapSpotifyArtist(raw: any): SpotifyArtistSummary | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  const name = String(raw.name || '').trim();
  if (!id || !name) return null;
  return {
    spotify_id: id,
    spotify_uri: String(raw.uri || `spotify:artist:${id}`),
    name,
    genres: Array.isArray(raw.genres) ? raw.genres.map((g: any) => String(g)) : [],
    popularity: Number(raw.popularity || 0),
    followers_total: Number(raw.followers?.total || 0),
    external_url: raw.external_urls?.spotify ? String(raw.external_urls.spotify) : null
  };
}

function mapSpotifyPlaylist(raw: any): SpotifyPlaylistSummary | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  const name = String(raw.name || '').trim();
  if (!id || !name) return null;
  const trackTotal =
    typeof raw.tracks?.total === 'number'
      ? raw.tracks.total
      : typeof raw.items?.total === 'number'
      ? raw.items.total
      : 0;
  return {
    spotify_id: id,
    spotify_uri: String(raw.uri || `spotify:playlist:${id}`),
    name,
    owner: String(raw.owner?.display_name || raw.owner?.id || 'Spotify').trim(),
    track_count: Number(trackTotal || 0),
    description: raw.description ? String(raw.description).replace(/<[^>]+>/g, '').trim() : undefined,
    is_public: typeof raw.public === 'boolean' ? raw.public : undefined,
    external_url: raw.external_urls?.spotify ? String(raw.external_urls.spotify) : null
  };
}

function mapSpotifyAlbum(raw: any): SpotifyAlbumSummary | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  const name = String(raw.name || '').trim();
  if (!id || !name) return null;
  const artist = Array.isArray(raw.artists)
    ? raw.artists.map((a: any) => String(a?.name || '').trim()).filter(Boolean).join(', ')
    : '';
  return {
    spotify_id: id,
    spotify_uri: String(raw.uri || `spotify:album:${id}`),
    name,
    artist: artist || 'Unknown Artist',
    release_date: raw.release_date ? String(raw.release_date) : undefined,
    total_tracks: Number(raw.total_tracks || 0),
    external_url: raw.external_urls?.spotify ? String(raw.external_urls.spotify) : null
  };
}

export class SpotifyUserApiClient {
  private readonly userId: string;
  private readonly clientAccessToken?: string;

  constructor(userId: string, clientAccessToken?: string) {
    this.userId = String(userId || '').trim() || 'tunemind-default-user';
    const cleanToken = String(clientAccessToken || '').trim();
    this.clientAccessToken = cleanToken.length >= 20 ? cleanToken : undefined;
    if (this.clientAccessToken) {
      tokenCacheByUser.set(this.userId, {
        accessToken: this.clientAccessToken,
        expiresAtMs: Date.now() + 25 * 60 * 1000,
        source: 'client'
      });
    }
  }

  public static clearCachedToken(userId: string): void {
    tokenCacheByUser.delete(String(userId || '').trim());
  }

  private async resolveAccessToken(forceRefresh: boolean = false): Promise<string | null> {
    if (!forceRefresh) {
      if (this.clientAccessToken) {
        return this.clientAccessToken;
      }
      const cached = tokenCacheByUser.get(this.userId);
      if (cached && cached.accessToken && Date.now() < cached.expiresAtMs - 60_000) {
        return cached.accessToken;
      }
    }

    const stored = await SpotifyConnectionStore.getConnection(this.userId);
    if (!stored || !stored.refreshToken || !stored.clientId) {
      return !forceRefresh && this.clientAccessToken ? this.clientAccessToken : null;
    }

    try {
      const form = new URLSearchParams();
      form.set('grant_type', 'refresh_token');
      form.set('refresh_token', stored.refreshToken);
      form.set('client_id', stored.clientId);

      const res = await fetch(SPOTIFY_TOKEN_ENDPOINT, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Accept: 'application/json'
        },
        body: form.toString(),
        signal: AbortSignal.timeout(7000)
      });

      if (!res.ok) {
        return null;
      }

      const data = (await res.json()) as any;
      const newToken = String(data?.access_token || '').trim();
      const rotatedRefresh = String(data?.refresh_token || '').trim();
      const expiresIn = Math.max(60, Number(data?.expires_in || 3600));
      if (!newToken) return null;

      tokenCacheByUser.set(this.userId, {
        accessToken: newToken,
        expiresAtMs: Date.now() + expiresIn * 1000,
        source: 'refresh'
      });

      if (rotatedRefresh) {
        await SpotifyConnectionStore.upsertConnection(this.userId, {
          clientId: stored.clientId,
          refreshToken: rotatedRefresh,
          displayName: stored.displayName,
          product: stored.product,
          lastDeviceName: stored.lastDeviceName,
          connectedAt: stored.connectedAt
        }).catch(() => {});
      }

      return newToken;
    } catch {
      return null;
    }
  }

  private async spotifyGet<T = any>(
    pathWithQuery: string
  ): Promise<{ ok: boolean; status: number; data: T | null; error?: string }> {
    let token = await this.resolveAccessToken(false);
    if (!token) {
      return {
        ok: false,
        status: 401,
        data: null,
        error: 'Spotify OAuth token unavailable for this user.'
      };
    }

    const perform = async (bearer: string) => {
      return fetch(`${SPOTIFY_API_BASE}${pathWithQuery}`, {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${bearer}`,
          Accept: 'application/json'
        },
        signal: AbortSignal.timeout(7500)
      });
    };

    try {
      let res = await perform(token);
      if (res.status === 401) {
        token = await this.resolveAccessToken(true);
        if (token) {
          res = await perform(token);
        }
      }

      if (res.status === 204) {
        return { ok: true, status: 204, data: null };
      }

      if (!res.ok) {
        const text = await res.text().catch(() => '');
        return {
          ok: false,
          status: res.status,
          data: null,
          error: text ? `Spotify HTTP ${res.status}: ${text.slice(0, 180)}` : `Spotify HTTP ${res.status}`
        };
      }

      const json = (await res.json()) as T;
      return { ok: true, status: res.status, data: json };
    } catch (err: any) {
      return {
        ok: false,
        status: 500,
        data: null,
        error: err?.message || 'Spotify request failed'
      };
    }
  }

  public async hasLiveAuth(): Promise<boolean> {
    const token = await this.resolveAccessToken(false);
    return Boolean(token);
  }

  public async searchSpotify(params: {
    query: string;
    types?: Array<'track' | 'artist' | 'playlist' | 'album'>;
    limit?: number;
    kidsSafeOnly?: boolean;
  }): Promise<{
    connected: boolean;
    tracks: SpotifyTrackSummary[];
    artists: SpotifyArtistSummary[];
    playlists: SpotifyPlaylistSummary[];
    albums: SpotifyAlbumSummary[];
    error?: string;
  }> {
    const q = String(params.query || '').trim();
    if (!q) {
      return { connected: false, tracks: [], artists: [], playlists: [], albums: [], error: 'Empty search query.' };
    }

    const validTypes = (params.types && params.types.length > 0 ? params.types : ['track', 'artist', 'playlist'])
      .filter((t): t is 'track' | 'artist' | 'playlist' | 'album' =>
        ['track', 'artist', 'playlist', 'album'].includes(t)
      );
    const limit = Math.min(12, Math.max(1, Number(params.limit || 6)));
    const searchParams = new URLSearchParams({
      q,
      type: validTypes.join(','),
      limit: String(limit)
    });

    const resp = await this.spotifyGet<any>(`/search?${searchParams.toString()}`);
    if (!resp.ok || !resp.data) {
      return {
        connected: resp.status !== 401,
        tracks: [],
        artists: [],
        playlists: [],
        albums: [],
        error: resp.error
      };
    }

    let tracks = (Array.isArray(resp.data?.tracks?.items) ? resp.data.tracks.items : [])
      .map(mapSpotifyTrack)
      .filter((t: SpotifyTrackSummary | null): t is SpotifyTrackSummary => Boolean(t));

    if (params.kidsSafeOnly) {
      tracks = tracks.filter((t: SpotifyTrackSummary) => !t.explicit);
    }

    const artists = (Array.isArray(resp.data?.artists?.items) ? resp.data.artists.items : [])
      .map(mapSpotifyArtist)
      .filter((a: SpotifyArtistSummary | null): a is SpotifyArtistSummary => Boolean(a));

    const playlists = (Array.isArray(resp.data?.playlists?.items) ? resp.data.playlists.items : [])
      .map(mapSpotifyPlaylist)
      .filter((p: SpotifyPlaylistSummary | null): p is SpotifyPlaylistSummary => Boolean(p));

    const albums = (Array.isArray(resp.data?.albums?.items) ? resp.data.albums.items : [])
      .map(mapSpotifyAlbum)
      .filter((a: SpotifyAlbumSummary | null): a is SpotifyAlbumSummary => Boolean(a));

    return { connected: true, tracks, artists, playlists, albums };
  }

  public async getTopArtists(
    timeRange: 'short_term' | 'medium_term' | 'long_term' = 'medium_term',
    limit: number = 12
  ): Promise<{ connected: boolean; artists: SpotifyArtistSummary[]; error?: string }> {
    const safeLimit = Math.min(25, Math.max(1, limit));
    const resp = await this.spotifyGet<any>(`/me/top/artists?time_range=${timeRange}&limit=${safeLimit}`);
    if (!resp.ok || !resp.data) {
      return { connected: resp.status !== 401, artists: [], error: resp.error };
    }
    const artists = (Array.isArray(resp.data?.items) ? resp.data.items : [])
      .map(mapSpotifyArtist)
      .filter((a: SpotifyArtistSummary | null): a is SpotifyArtistSummary => Boolean(a));
    return { connected: true, artists };
  }

  public async getTopTracks(
    timeRange: 'short_term' | 'medium_term' | 'long_term' = 'medium_term',
    limit: number = 15
  ): Promise<{ connected: boolean; tracks: SpotifyTrackSummary[]; error?: string }> {
    const safeLimit = Math.min(25, Math.max(1, limit));
    const resp = await this.spotifyGet<any>(`/me/top/tracks?time_range=${timeRange}&limit=${safeLimit}`);
    if (!resp.ok || !resp.data) {
      return { connected: resp.status !== 401, tracks: [], error: resp.error };
    }
    const tracks = (Array.isArray(resp.data?.items) ? resp.data.items : [])
      .map(mapSpotifyTrack)
      .filter((t: SpotifyTrackSummary | null): t is SpotifyTrackSummary => Boolean(t));
    return { connected: true, tracks };
  }

  public async getSavedLikedTracks(
    limit: number = 20,
    offset: number = 0
  ): Promise<{ connected: boolean; total: number; tracks: SpotifyTrackSummary[]; error?: string }> {
    const safeLimit = Math.min(30, Math.max(1, limit));
    const safeOffset = Math.max(0, offset);
    const resp = await this.spotifyGet<any>(`/me/tracks?limit=${safeLimit}&offset=${safeOffset}`);
    if (!resp.ok || !resp.data) {
      return { connected: resp.status !== 401, total: 0, tracks: [], error: resp.error };
    }
    const tracks = (Array.isArray(resp.data?.items) ? resp.data.items : [])
      .map((item: any) => mapSpotifyTrack(item?.track || item))
      .filter((t: SpotifyTrackSummary | null): t is SpotifyTrackSummary => Boolean(t));
    return {
      connected: true,
      total: Number(resp.data?.total || tracks.length),
      tracks
    };
  }

  public async getRecentlyPlayed(
    limit: number = 20
  ): Promise<{
    connected: boolean;
    items: Array<{ played_at: string; track: SpotifyTrackSummary }>;
    error?: string;
  }> {
    const safeLimit = Math.min(30, Math.max(1, limit));
    const resp = await this.spotifyGet<any>(`/me/player/recently-played?limit=${safeLimit}`);
    if (!resp.ok || !resp.data) {
      return { connected: resp.status !== 401, items: [], error: resp.error };
    }
    const items = (Array.isArray(resp.data?.items) ? resp.data.items : [])
      .map((it: any) => {
        const track = mapSpotifyTrack(it?.track);
        if (!track) return null;
        return {
          played_at: String(it?.played_at || ''),
          track
        };
      })
      .filter((x: { played_at: string; track: SpotifyTrackSummary } | null): x is { played_at: string; track: SpotifyTrackSummary } => Boolean(x));
    return { connected: true, items };
  }

  public async getUserPlaylists(
    limit: number = 25
  ): Promise<{ connected: boolean; playlists: SpotifyPlaylistSummary[]; error?: string }> {
    const safeLimit = Math.min(40, Math.max(1, limit));
    const resp = await this.spotifyGet<any>(`/me/playlists?limit=${safeLimit}`);
    if (!resp.ok || !resp.data) {
      return { connected: resp.status !== 401, playlists: [], error: resp.error };
    }
    const playlists = (Array.isArray(resp.data?.items) ? resp.data.items : [])
      .map(mapSpotifyPlaylist)
      .filter((p: SpotifyPlaylistSummary | null): p is SpotifyPlaylistSummary => Boolean(p));
    return { connected: true, playlists };
  }

  public async getPlaylistTracks(
    playlistIdOrUri: string,
    limit: number = 25
  ): Promise<{
    connected: boolean;
    playlist_id: string;
    context_uri: string;
    restricted: boolean;
    tracks: SpotifyTrackSummary[];
    note?: string;
    error?: string;
  }> {
    const cleanId = String(playlistIdOrUri || '')
      .replace(/^spotify:playlist:/i, '')
      .replace(/^https?:\/\/open\.spotify\.com\/playlist\//i, '')
      .split('?')[0]
      .trim();

    if (!cleanId) {
      return {
        connected: false,
        playlist_id: '',
        context_uri: '',
        restricted: false,
        tracks: [],
        error: 'Invalid Spotify playlist ID.'
      };
    }

    const contextUri = `spotify:playlist:${cleanId}`;
    const safeLimit = Math.min(40, Math.max(1, limit));

    // Try Feb 2026 `/items` endpoint first, then fallback to `/tracks`
    let resp = await this.spotifyGet<any>(`/playlists/${encodeURIComponent(cleanId)}/items?limit=${safeLimit}`);
    if (!resp.ok && resp.status === 404) {
      resp = await this.spotifyGet<any>(`/playlists/${encodeURIComponent(cleanId)}/tracks?limit=${safeLimit}`);
    }

    if (!resp.ok || !resp.data) {
      const restricted = resp.status === 403 || resp.status === 404;
      return {
        connected: resp.status !== 401,
        playlist_id: cleanId,
        context_uri: contextUri,
        restricted,
        tracks: [],
        note: restricted
          ? 'Spotify restricts third-party apps from listing tracks inside editorial or other users playlists, but TuneMind can still play this entire playlist directly using its Spotify context URI (PLAY_SPOTIFY_CONTEXT)!'
          : resp.error
      };
    }

    const rawItems = Array.isArray(resp.data?.items) ? resp.data.items : [];
    const tracks = rawItems
      .map((item: any) => mapSpotifyTrack(item?.item || item?.track || item))
      .filter((t: SpotifyTrackSummary | null): t is SpotifyTrackSummary => Boolean(t));

    return {
      connected: true,
      playlist_id: cleanId,
      context_uri: contextUri,
      restricted: false,
      tracks
    };
  }

  public async getPlaybackStateAndDevices(): Promise<SpotifyPlaybackSnapshot> {
    const [playerResp, devicesResp, queueResp] = await Promise.all([
      this.spotifyGet<any>('/me/player'),
      this.spotifyGet<any>('/me/player/devices'),
      this.spotifyGet<any>('/me/player/queue')
    ]);

    if (playerResp.status === 401 && devicesResp.status === 401) {
      return {
        connected: false,
        is_playing: false,
        devices: [],
        queue: [],
        note: playerResp.error || 'Spotify session not connected.'
      };
    }

    const mapDevice = (d: any): SpotifyDeviceSummary => ({
      id: String(d?.id || ''),
      name: String(d?.name || 'Spotify Device'),
      type: String(d?.type || 'Smartphone'),
      is_active: Boolean(d?.is_active),
      is_restricted: Boolean(d?.is_restricted),
      volume_percent: typeof d?.volume_percent === 'number' ? d.volume_percent : null
    });

    const devices: SpotifyDeviceSummary[] = Array.isArray(devicesResp.data?.devices)
      ? devicesResp.data.devices.map(mapDevice).filter((d: SpotifyDeviceSummary) => Boolean(d.id))
      : [];

    const activeDevice = playerResp.data?.device
      ? mapDevice(playerResp.data.device)
      : devices.find((d) => d.is_active) || null;

    const currentTrack = playerResp.data?.item ? mapSpotifyTrack(playerResp.data.item) : null;
    const queue = Array.isArray(queueResp.data?.queue)
      ? queueResp.data.queue
          .slice(0, 12)
          .map(mapSpotifyTrack)
          .filter((t: SpotifyTrackSummary | null): t is SpotifyTrackSummary => Boolean(t))
      : [];

    return {
      connected: true,
      is_playing: Boolean(playerResp.data?.is_playing),
      shuffle_state: typeof playerResp.data?.shuffle_state === 'boolean' ? playerResp.data.shuffle_state : undefined,
      repeat_state: playerResp.data?.repeat_state ? String(playerResp.data.repeat_state) : undefined,
      progress_ms: typeof playerResp.data?.progress_ms === 'number' ? playerResp.data.progress_ms : undefined,
      active_device: activeDevice,
      current_track: currentTrack,
      devices,
      queue
    };
  }
}
