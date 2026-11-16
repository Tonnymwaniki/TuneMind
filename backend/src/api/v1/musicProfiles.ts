import { Router, Request, Response } from 'express';

const router = Router();

export interface UniversalTrackProfile {
  trackKey: string;
  cleanTitle: string;
  cleanArtist: string;
  spotifyTrackId: string | null;
  spotifyUri: string | null;
  artistId: string | null;
  albumName: string | null;
  releaseDate: string | null;
  albumArtUrl: string | null;
  artistImageUrl: string | null;
  genres: string[];
  popularity: number;
  previewUrl: string | null;
  source: 'spotify' | 'deezer' | 'itunes' | 'mixed';
  resolvedAtMs: number;
}

interface CachedEntry<T> {
  value: T;
  expiresAtMs: number;
}

const TRACK_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const ARTIST_CACHE_TTL_MS = 14 * 24 * 60 * 60 * 1000;
const NEGATIVE_CACHE_TTL_MS = 30 * 60 * 1000;

const trackProfileCache = new Map<string, CachedEntry<UniversalTrackProfile | null>>();
const artistProfileCache = new Map<string, CachedEntry<{
  id: string | null;
  name: string;
  imageUrl: string | null;
  genres: string[];
  popularity: number;
} | null>>();

let cachedAppToken: { token: string; expiresAtMs: number } | null = null;

function trimMapIfNeeded<K, V>(map: Map<K, V>, maxSize: number) {
  if (map.size <= maxSize) return;
  const excess = map.size - maxSize;
  let removed = 0;
  for (const key of map.keys()) {
    map.delete(key);
    removed++;
    if (removed >= excess) break;
  }
}

export function cleanTrackAndArtistMetadata(rawTitle: string, rawArtist: string): {
  cleanTitle: string;
  cleanArtist: string;
} {
  let title = (rawTitle || '')
    .replace(/\.(mp3|m4a|flac|wav|ogg|aac|opus)$/i, '')
    .replace(/\((?:official\s*(?:music\s*)?video|official\s*audio|lyric\s*video|lyrics|visualizer|hd|4k|hq|live|clean|explicit)\)/gi, ' ')
    .replace(/\[(?:official\s*(?:music\s*)?video|official\s*audio|lyric\s*video|lyrics|visualizer|hd|4k|hq|live|clean|explicit)\]/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  let artist = (rawArtist || '')
    .replace(/\s*-\s*Topic$/i, '')
    .replace(/\s*VEVO$/i, '')
    .replace(/\s+/g, ' ')
    .trim();

  const lowerArtist = artist.toLowerCase();
  const isGenericArtist =
    !artist ||
    lowerArtist === 'unknown' ||
    lowerArtist === 'unknown artist' ||
    lowerArtist === '<unknown>' ||
    lowerArtist === 'youtube' ||
    lowerArtist === 'youtube music' ||
    lowerArtist === 'music player';

  if (isGenericArtist && title.includes(' - ')) {
    const parts = title.split(' - ');
    if (parts.length >= 2 && parts[0].trim().length >= 2) {
      artist = parts[0].trim();
      title = parts.slice(1).join(' - ').trim();
    }
  } else if (!isGenericArtist && title.toLowerCase().startsWith(`${lowerArtist} - `)) {
    title = title.substring(artist.length + 3).trim();
  }

  // Extract primary artist before "feat.", "ft.", "&", ","
  const primaryArtist = artist
    .split(/\s+(?:feat\.?|ft\.?|featuring|with|x)\s+|[,;&]/i)[0]
    ?.trim() || artist;

  // Strip trailing "(feat. ...)" from title for cleaner search match
  const cleanTitle = title
    .replace(/\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring)\s+[^\)\]]+[\)\]]/gi, '')
    .replace(/\s+/g, ' ')
    .trim();

  return {
    cleanTitle: cleanTitle || title || rawTitle.trim(),
    cleanArtist: isGenericArtist ? '' : primaryArtist
  };
}

function buildCanonicalKey(title: string, artist: string): string {
  const norm = (s: string) =>
    s
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  return `${norm(title)}|${norm(artist)}`;
}

async function getSpotifyAppAccessToken(): Promise<string | null> {
  const now = Date.now();
  if (cachedAppToken && now + 30_000 < cachedAppToken.expiresAtMs) {
    return cachedAppToken.token;
  }

  const clientId = (process.env.SPOTIFY_CLIENT_ID || 'dcfad6acbd594477b0705ecc1e6ace3a').trim();
  const clientSecret = (process.env.SPOTIFY_CLIENT_SECRET || '134028eff64c4e2cafa4b1f617463650').trim();
  if (!clientId || !clientSecret) return null;

  try {
    const basic = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const resp = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: {
        Authorization: `Basic ${basic}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: 'grant_type=client_credentials',
      signal: AbortSignal.timeout(4500)
    });
    if (!resp.ok) return null;
    const data = (await resp.json()) as { access_token?: string; expires_in?: number };
    if (!data.access_token) return null;
    cachedAppToken = {
      token: data.access_token,
      expiresAtMs: now + (data.expires_in || 3600) * 1000
    };
    return cachedAppToken.token;
  } catch {
    return null;
  }
}

async function fetchSpotifyArtistDetails(
  artistId: string,
  token: string
): Promise<{
  id: string | null;
  name: string;
  imageUrl: string | null;
  genres: string[];
  popularity: number;
} | null> {
  const now = Date.now();
  const cached = artistProfileCache.get(`id:${artistId}`);
  if (cached && cached.expiresAtMs > now) {
    return cached.value;
  }

  try {
    const resp = await fetch(`https://api.spotify.com/v1/artists/${encodeURIComponent(artistId)}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(4000)
    });
    if (!resp.ok) return null;
    const dto = (await resp.json()) as any;
    const entry = {
      id: dto.id || artistId,
      name: String(dto.name || ''),
      imageUrl: dto.images?.[0]?.url || null,
      genres: Array.isArray(dto.genres) ? dto.genres.map((g: string) => String(g)) : [],
      popularity: Number(dto.popularity || 0)
    };
    artistProfileCache.set(`id:${artistId}`, { value: entry, expiresAtMs: now + ARTIST_CACHE_TTL_MS });
    if (entry.name) {
      artistProfileCache.set(entry.name.toLowerCase(), {
        value: entry,
        expiresAtMs: now + ARTIST_CACHE_TTL_MS
      });
    }
    trimMapIfNeeded(artistProfileCache, 1000);
    return entry;
  } catch {
    return null;
  }
}

async function resolveViaSpotify(
  cleanTitle: string,
  cleanArtist: string,
  explicitUri?: string
): Promise<Partial<UniversalTrackProfile> | null> {
  const token = await getSpotifyAppAccessToken();
  if (!token) return null;

  try {
    let trackDto: any = null;
    const uriMatch = (explicitUri || '').match(/spotify:track:([A-Za-z0-9]{22})/);
    if (uriMatch?.[1]) {
      const trResp = await fetch(`https://api.spotify.com/v1/tracks/${uriMatch[1]}`, {
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(4500)
      });
      if (trResp.ok) {
        trackDto = await trResp.json();
      }
    }

    if (!trackDto && cleanTitle) {
      const queries = cleanArtist
        ? [`track:${cleanTitle} artist:${cleanArtist}`, `${cleanTitle} ${cleanArtist}`]
        : [cleanTitle];

      for (const q of queries) {
        const url = `https://api.spotify.com/v1/search?q=${encodeURIComponent(q)}&type=track,artist&limit=2`;
        const resp = await fetch(url, {
          headers: { Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(4500)
        });
        if (!resp.ok) continue;
        const data = (await resp.json()) as any;
        const candidate = data?.tracks?.items?.[0];
        if (candidate) {
          trackDto = candidate;
          const inlineArtist = data?.artists?.items?.[0];
          if (inlineArtist && inlineArtist.images?.[0]?.url) {
            artistProfileCache.set(String(inlineArtist.name || '').toLowerCase(), {
              value: {
                id: inlineArtist.id || null,
                name: String(inlineArtist.name || ''),
                imageUrl: inlineArtist.images?.[0]?.url || null,
                genres: Array.isArray(inlineArtist.genres) ? inlineArtist.genres : [],
                popularity: Number(inlineArtist.popularity || 0)
              },
              expiresAtMs: Date.now() + ARTIST_CACHE_TTL_MS
            });
          }
          break;
        }
      }
    }

    if (!trackDto) return null;

    const primaryArtist = trackDto.artists?.[0];
    const artistId: string | null = primaryArtist?.id || null;
    const artistName: string = primaryArtist?.name || cleanArtist;

    let artistDetails =
      (artistId ? artistProfileCache.get(`id:${artistId}`)?.value : null) ||
      (artistName ? artistProfileCache.get(artistName.toLowerCase())?.value : null) ||
      null;

    if ((!artistDetails || !artistDetails.imageUrl) && artistId) {
      artistDetails = await fetchSpotifyArtistDetails(artistId, token);
    }

    return {
      cleanTitle: trackDto.name || cleanTitle,
      cleanArtist: artistName || cleanArtist,
      spotifyTrackId: trackDto.id || null,
      spotifyUri: trackDto.uri || explicitUri || null,
      artistId,
      albumName: trackDto.album?.name || null,
      releaseDate: trackDto.album?.release_date || null,
      albumArtUrl: trackDto.album?.images?.[0]?.url || null,
      artistImageUrl: artistDetails?.imageUrl || null,
      genres: artistDetails?.genres || [],
      popularity: Number(trackDto.popularity || 0),
      previewUrl: trackDto.preview_url || null,
      source: 'spotify'
    };
  } catch {
    return null;
  }
}

async function resolveViaDeezer(
  cleanTitle: string,
  cleanArtist: string
): Promise<Partial<UniversalTrackProfile> | null> {
  if (!cleanTitle) return null;
  try {
    const q = cleanArtist ? `${cleanTitle} ${cleanArtist}` : cleanTitle;
    const resp = await fetch(`https://api.deezer.com/search?q=${encodeURIComponent(q)}&limit=3`, {
      signal: AbortSignal.timeout(4000)
    });
    if (!resp.ok) return null;
    const data = (await resp.json()) as any;
    const item = data?.data?.[0];
    if (!item) return null;

    const albumArtUrl = item.album?.cover_big || item.album?.cover_medium || item.album?.cover || null;
    const artistImageUrl =
      item.artist?.picture_big || item.artist?.picture_medium || item.artist?.picture || null;

    return {
      cleanTitle: item.title || cleanTitle,
      cleanArtist: item.artist?.name || cleanArtist,
      albumName: item.album?.title || null,
      albumArtUrl,
      artistImageUrl,
      previewUrl: item.preview || null,
      source: 'deezer'
    };
  } catch {
    return null;
  }
}

async function resolveViaItunes(
  cleanTitle: string,
  cleanArtist: string
): Promise<Partial<UniversalTrackProfile> | null> {
  if (!cleanTitle) return null;
  try {
    const q = cleanArtist ? `${cleanTitle} ${cleanArtist}` : cleanTitle;
    const resp = await fetch(
      `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&media=music&entity=song&limit=2`,
      { signal: AbortSignal.timeout(4000) }
    );
    if (!resp.ok) return null;
    const data = (await resp.json()) as any;
    const item = data?.results?.[0];
    if (!item) return null;

    const highResArt = typeof item.artworkUrl100 === 'string'
      ? item.artworkUrl100.replace('100x100bb', '600x600bb')
      : null;

    return {
      cleanTitle: item.trackName || cleanTitle,
      cleanArtist: item.artistName || cleanArtist,
      albumName: item.collectionName || null,
      releaseDate: item.releaseDate ? String(item.releaseDate).slice(0, 10) : null,
      albumArtUrl: highResArt,
      genres: item.primaryGenreName ? [String(item.primaryGenreName).toLowerCase()] : [],
      previewUrl: item.previewUrl || null,
      source: 'itunes'
    };
  } catch {
    return null;
  }
}

export async function resolveUniversalTrackProfile(
  rawTitle: string,
  rawArtist: string,
  explicitUri?: string
): Promise<UniversalTrackProfile | null> {
  const { cleanTitle, cleanArtist } = cleanTrackAndArtistMetadata(rawTitle, rawArtist);
  if (!cleanTitle && !explicitUri) return null;

  const key = buildCanonicalKey(cleanTitle, cleanArtist);
  const now = Date.now();
  const cached = trackProfileCache.get(key);
  if (cached && cached.expiresAtMs > now) {
    return cached.value;
  }

  // Tier 1: Spotify Public Catalog (album cover + artist portrait + genres + popularity + URI)
  const sp = await resolveViaSpotify(cleanTitle, cleanArtist, explicitUri);

  // Fill gaps via Deezer / iTunes (essential for YouTube Music / local / indie songs or missing previews)
  let dz: Partial<UniversalTrackProfile> | null = null;
  let it: Partial<UniversalTrackProfile> | null = null;

  if (!sp || !sp.albumArtUrl || !sp.artistImageUrl || !sp.previewUrl) {
    dz = await resolveViaDeezer(cleanTitle, sp?.cleanArtist || cleanArtist);
  }
  if ((!sp?.genres || sp.genres.length === 0) && !sp?.albumArtUrl && !dz?.albumArtUrl) {
    it = await resolveViaItunes(cleanTitle, sp?.cleanArtist || dz?.cleanArtist || cleanArtist);
  }

  if (!sp && !dz && !it) {
    trackProfileCache.set(key, { value: null, expiresAtMs: now + NEGATIVE_CACHE_TTL_MS });
    trimMapIfNeeded(trackProfileCache, 2500);
    return null;
  }

  const profile: UniversalTrackProfile = {
    trackKey: key,
    cleanTitle: sp?.cleanTitle || dz?.cleanTitle || it?.cleanTitle || cleanTitle,
    cleanArtist: sp?.cleanArtist || dz?.cleanArtist || it?.cleanArtist || cleanArtist,
    spotifyTrackId: sp?.spotifyTrackId || null,
    spotifyUri: sp?.spotifyUri || explicitUri || null,
    artistId: sp?.artistId || null,
    albumName: sp?.albumName || dz?.albumName || it?.albumName || null,
    releaseDate: sp?.releaseDate || it?.releaseDate || null,
    albumArtUrl: sp?.albumArtUrl || dz?.albumArtUrl || it?.albumArtUrl || null,
    artistImageUrl: sp?.artistImageUrl || dz?.artistImageUrl || null,
    genres: (sp?.genres && sp.genres.length > 0 ? sp.genres : it?.genres) || [],
    popularity: sp?.popularity || 0,
    previewUrl: sp?.previewUrl || dz?.previewUrl || it?.previewUrl || null,
    source: sp && (dz || it) ? 'mixed' : sp ? 'spotify' : dz ? 'deezer' : 'itunes',
    resolvedAtMs: now
  };

  trackProfileCache.set(key, { value: profile, expiresAtMs: now + TRACK_CACHE_TTL_MS });
  trimMapIfNeeded(trackProfileCache, 2500);
  return profile;
}

/**
 * POST /api/v1/music/resolve-profile
 * Resolves a single track played from ANY music player (Spotify, YouTube Music, Apple Music,
 * Boomplay, Audiomack, local MP3 player, etc.) into full Spotify/Deezer/iTunes track + artist profile.
 */
router.post('/resolve-profile', async (req: Request, res: Response) => {
  try {
    const title = String(req.body?.title || '').trim();
    const artist = String(req.body?.artist || '').trim();
    const spotifyUri = req.body?.spotify_uri ? String(req.body.spotify_uri).trim() : undefined;

    if (!title && !spotifyUri) {
      return res.status(400).json({ error: 'Missing track title or spotify_uri' });
    }

    const profile = await resolveUniversalTrackProfile(title, artist, spotifyUri);
    return res.json({
      found: Boolean(profile),
      profile
    });
  } catch (err: any) {
    console.error('[MusicProfiles] /resolve-profile error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to resolve track profile' });
  }
});

/**
 * POST /api/v1/music/resolve-batch
 * Resolves up to 20 tracks in parallel using shared backend cache + Spotify/Deezer/iTunes cascade.
 */
router.post('/resolve-batch', async (req: Request, res: Response) => {
  try {
    const rawTracks = Array.isArray(req.body?.tracks) ? req.body.tracks.slice(0, 20) : [];
    if (rawTracks.length === 0) {
      return res.json({ profiles: [] });
    }

    const results: Array<{
      query_title: string;
      query_artist: string;
      profile: UniversalTrackProfile | null;
    }> = [];

    // Process in small concurrent waves of 5 to keep Spotify/Deezer happy
    for (let i = 0; i < rawTracks.length; i += 5) {
      const wave = rawTracks.slice(i, i + 5);
      const settled = await Promise.all(
        wave.map(async (item: any) => {
          const title = String(item?.title || '').trim();
          const artist = String(item?.artist || '').trim();
          const spotifyUri = item?.spotify_uri ? String(item.spotify_uri).trim() : undefined;
          const profile = await resolveUniversalTrackProfile(title, artist, spotifyUri);
          return {
            query_title: title,
            query_artist: artist,
            profile
          };
        })
      );
      results.push(...settled);
    }

    return res.json({ profiles: results });
  } catch (err: any) {
    console.error('[MusicProfiles] /resolve-batch error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to batch resolve track profiles' });
  }
});

export default router;
