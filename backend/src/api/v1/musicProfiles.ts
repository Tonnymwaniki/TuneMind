import { Router, Request, Response } from 'express';
import { resolveCanonicalTrackIdentity } from '../../domain/identity/trackIdentity';

const router = Router();

export interface UniversalTrackProfile {
  trackId: string;
  trackKey: string;
  cleanTitle: string;
  cleanArtist: string;
  albumName: string | null;
  releaseDate: string | null;
  albumArtUrl: string | null;
  artistImageUrl: string | null;
  genres: string[];
  popularity: number;
  previewUrl: string | null;
  audiusTrackId: string | null;
  audiusStreamUrl: string | null;
  ytmVideoId: string | null;
  deezerUrl: string | null;
  spotifyUri: string | null;
  spotifyUrl: string | null;
  source: 'deezer' | 'itunes' | 'audius' | 'lastfm' | 'mixed';
  resolvedAtMs: number;
}

export interface OpenCatalogTrackHit {
  trackId: string;
  title: string;
  artist: string;
  album: string;
  genre: string;
  popularity: number;
  explicit: boolean;
  durationMs: number;
  previewUrl: string | null;
  audiusStreamUrl: string | null;
  ytmVideoId: string | null;
  deezerUrl: string | null;
  albumArtUrl: string | null;
  artistImageUrl: string | null;
  source: string;
}

export interface OpenCatalogSearchResult {
  search_type: string;
  tracks: OpenCatalogTrackHit[];
  artists?: Array<{ id: string; name: string; genres: string[]; popularity: number; imageUrl: string | null }>;
  albums?: Array<{ id: string; name: string; artist: string; release_date: string; albumArtUrl: string | null }>;
}

interface CachedEntry<T> {
  value: T;
  expiresAtMs: number;
}

const TRACK_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const YTM_CACHE_TTL_MS = 14 * 24 * 60 * 60 * 1000;
const CATALOG_CACHE_TTL_MS = 5 * 60 * 1000;
const NEGATIVE_CACHE_TTL_MS = 20 * 60 * 1000;
const AUDIUS_HOST_TTL_MS = 30 * 60 * 1000;

const trackProfileCache = new Map<string, CachedEntry<UniversalTrackProfile | null>>();
const ytmVideoCache = new Map<string, CachedEntry<string | null>>();
const songlinkSpotifyCache = new Map<string, CachedEntry<{ spotifyUri: string | null; spotifyUrl: string | null }>>();
const catalogSearchCache = new Map<string, CachedEntry<OpenCatalogSearchResult>>();
const lastFmTagsCache = new Map<string, CachedEntry<string[]>>();
let cachedAudiusHost: CachedEntry<string | null> | null = null;

function trimMapIfNeeded<K, V>(map: Map<K, V>, maxSize: number): void {
  if (map.size <= maxSize) return;
  const excess = map.size - maxSize;
  let removed = 0;
  for (const key of map.keys()) {
    map.delete(key);
    removed++;
    if (removed >= excess) break;
  }
}

function getYoutubeApiKey(): string {
  return (process.env.YOUTUBE_API_KEY || '').trim();
}

function getLastFmApiKey(): string {
  return (process.env.LASTFM_API_KEY || '').trim();
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

  const primaryArtist =
    artist.split(/\s+(?:feat\.?|ft\.?|featuring|with|x)\s+|[,;&]/i)[0]?.trim() || artist;

  const cleanTitle = title
    .replace(/\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring)\s+[^\)\]]+[\)\]]/gi, '')
    .replace(/\s+/g, ' ')
    .trim();

  return {
    cleanTitle: cleanTitle || title || rawTitle.trim(),
    cleanArtist: isGenericArtist ? '' : primaryArtist
  };
}

function normalizeForMatch(raw: string): string {
  return (raw || '')
    .toLowerCase()
    .replace(/\([^)]*\)|\[[^\]]*\]/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function buildCanonicalKey(title: string, artist: string): string {
  return `${normalizeForMatch(title)}|${normalizeForMatch(artist)}`;
}

function isStrictTitleArtistMatch(
  expectedTitle: string,
  expectedArtist: string,
  candidateTitle: string,
  candidateArtist: string
): boolean {
  const normExpTitle = normalizeForMatch(expectedTitle);
  const normCandTitle = normalizeForMatch(candidateTitle);
  if (!normExpTitle || !normCandTitle) return false;

  const titleMatched =
    normCandTitle === normExpTitle ||
    (normExpTitle.length >= 4 && (normCandTitle.includes(normExpTitle) || normExpTitle.includes(normCandTitle)));

  const expTitleWords = new Set(normExpTitle.split(/\s+/).filter(w => w.length >= 2));
  const candTitleWords = new Set(normCandTitle.split(/\s+/).filter(w => w.length >= 2));
  let overlap = 0;
  expTitleWords.forEach(w => {
    if (candTitleWords.has(w)) overlap++;
  });
  const wordRatio = overlap / Math.max(1, Math.min(expTitleWords.size, candTitleWords.size));

  if (!titleMatched && wordRatio < 0.65) return false;

  const normExpArtist = normalizeForMatch(expectedArtist);
  const normCandArtist = normalizeForMatch(candidateArtist);
  if (!normExpArtist) return titleMatched || wordRatio >= 0.75;
  return normCandArtist.includes(normExpArtist) || normExpArtist.includes(normCandArtist);
}

async function fetchJsonWithTimeout(url: string, timeoutMs = 4000, headers?: Record<string, string>): Promise<any | null> {
  try {
    const resp = await fetch(url, {
      headers: { Accept: 'application/json', ...(headers || {}) },
      signal: AbortSignal.timeout(timeoutMs)
    });
    if (!resp.ok) return null;
    return await resp.json();
  } catch {
    return null;
  }
}

async function resolveAudiusHost(): Promise<string | null> {
  const now = Date.now();
  if (cachedAudiusHost && cachedAudiusHost.expiresAtMs > now) {
    return cachedAudiusHost.value;
  }
  const data = await fetchJsonWithTimeout('https://api.audius.co', 3000);
  const list: unknown[] = Array.isArray(data?.data) ? data.data : [];
  const host = typeof list[0] === 'string' && list[0].startsWith('http') ? list[0].replace(/\/+$/, '') : null;
  cachedAudiusHost = { value: host, expiresAtMs: now + AUDIUS_HOST_TTL_MS };
  return host;
}

async function searchDeezerTracks(
  query: string,
  limit = 10,
  offset = 0,
  excludeExplicit = false
): Promise<OpenCatalogTrackHit[]> {
  if (!query.trim()) return [];
  const boundedLimit = Math.min(20, Math.max(1, limit));
  const data = await fetchJsonWithTimeout(
    `https://api.deezer.com/search?q=${encodeURIComponent(query)}&limit=${boundedLimit}&index=${Math.max(0, offset)}`,
    4000
  );
  const rows: any[] = Array.isArray(data?.data) ? data.data : [];
  return rows
    .filter(item => item && item.title && (!excludeExplicit || !Boolean(item.explicit_lyrics)))
    .map(item => {
      const title = String(item.title || '').trim();
      const artist = String(item.artist?.name || 'Unknown Artist').trim();
      const canonical = resolveCanonicalTrackIdentity({
        trackId: item.id ? `deezer:${item.id}` : undefined,
        title,
        artist
      });
      return {
        trackId: canonical.trackId,
        title,
        artist,
        album: String(item.album?.title || '').trim(),
        genre: '',
        popularity: Math.min(98, Math.max(25, Math.round(Number(item.rank || 300000) / 10000))),
        explicit: Boolean(item.explicit_lyrics),
        durationMs: Number(item.duration || 0) * 1000,
        previewUrl: typeof item.preview === 'string' && item.preview.startsWith('http') ? item.preview : null,
        audiusStreamUrl: null,
        ytmVideoId: null,
        deezerUrl: typeof item.link === 'string' ? item.link : item.id ? `https://www.deezer.com/track/${item.id}` : null,
        albumArtUrl: item.album?.cover_big || item.album?.cover_medium || item.album?.cover || null,
        artistImageUrl: item.artist?.picture_big || item.artist?.picture_medium || item.artist?.picture || null,
        source: 'deezer'
      };
    });
}

async function searchItunesTracks(
  query: string,
  limit = 10,
  excludeExplicit = false
): Promise<OpenCatalogTrackHit[]> {
  if (!query.trim()) return [];
  const boundedLimit = Math.min(20, Math.max(1, limit));
  const data = await fetchJsonWithTimeout(
    `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&media=music&entity=song&limit=${boundedLimit}`,
    4000
  );
  const rows: any[] = Array.isArray(data?.results) ? data.results : [];
  return rows
    .filter(item => {
      if (!item || !item.trackName) return false;
      const isExp = String(item.trackExplicitness || '').toLowerCase() === 'explicit';
      return !excludeExplicit || !isExp;
    })
    .map(item => {
      const title = String(item.trackName || '').trim();
      const artist = String(item.artistName || 'Unknown Artist').trim();
      const highResArt =
        typeof item.artworkUrl100 === 'string' ? item.artworkUrl100.replace('100x100bb', '600x600bb') : null;
      const canonical = resolveCanonicalTrackIdentity({
        trackId: item.trackId ? `itunes:${item.trackId}` : undefined,
        title,
        artist
      });
      return {
        trackId: canonical.trackId,
        title,
        artist,
        album: String(item.collectionName || '').trim(),
        genre: String(item.primaryGenreName || '').trim(),
        popularity: 62,
        explicit: String(item.trackExplicitness || '').toLowerCase() === 'explicit',
        durationMs: Number(item.trackTimeMillis || 0),
        previewUrl: typeof item.previewUrl === 'string' && item.previewUrl.startsWith('http') ? item.previewUrl : null,
        audiusStreamUrl: null,
        ytmVideoId: null,
        deezerUrl: null,
        albumArtUrl: highResArt,
        artistImageUrl: null,
        source: 'itunes'
      };
    });
}

async function searchAudiusTracks(
  query: string,
  limit = 6
): Promise<Array<OpenCatalogTrackHit & { audiusTrackId: string }>> {
  if (!query.trim()) return [];
  const host = await resolveAudiusHost();
  if (!host) return [];
  const data = await fetchJsonWithTimeout(
    `${host}/v1/tracks/search?query=${encodeURIComponent(query)}&limit=${Math.min(12, Math.max(1, limit))}&app_name=TuneMind`,
    3500
  );
  const rows: any[] = Array.isArray(data?.data) ? data.data : [];
  return rows
    .filter(item => item && item.id && item.title)
    .map(item => {
      const title = String(item.title || '').trim();
      const artist = String(item.user?.name || 'Independent Artist').trim();
      const audiusTrackId = String(item.id);
      const streamUrl = `${host}/v1/tracks/${encodeURIComponent(audiusTrackId)}/stream?app_name=TuneMind`;
      const canonical = resolveCanonicalTrackIdentity({
        trackId: `audius:${audiusTrackId}`,
        title,
        artist
      });
      return {
        trackId: canonical.trackId,
        audiusTrackId,
        title,
        artist,
        album: '',
        genre: String(item.genre || '').trim(),
        popularity: Math.min(90, Math.max(40, Math.round(Number(item.play_count || 1000) / 500))),
        explicit: false,
        durationMs: Number(item.duration || 0) * 1000,
        previewUrl: null,
        audiusStreamUrl: streamUrl,
        ytmVideoId: null,
        deezerUrl: null,
        albumArtUrl: item.artwork?.['480x480'] || item.artwork?.['150x150'] || null,
        artistImageUrl: item.user?.profile_picture?.['480x480'] || item.user?.profile_picture?.['150x150'] || null,
        source: 'audius'
      };
    });
}

async function fetchLastFmArtistTags(artistName: string): Promise<string[]> {
  const apiKey = getLastFmApiKey();
  const clean = artistName.trim();
  if (!apiKey || !clean) return [];

  const cacheKey = clean.toLowerCase();
  const now = Date.now();
  const cached = lastFmTagsCache.get(cacheKey);
  if (cached && cached.expiresAtMs > now) return cached.value;

  const data = await fetchJsonWithTimeout(
    `https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&artist=${encodeURIComponent(clean)}&api_key=${encodeURIComponent(apiKey)}&format=json`,
    3000
  );
  const rawTags: any[] = Array.isArray(data?.toptags?.tag) ? data.toptags.tag : [];
  const tags = rawTags
    .map(t => String(t?.name || '').trim())
    .filter(t => t.length >= 2 && t.length <= 32 && !/seen live|favorites|my favorite/i.test(t))
    .slice(0, 4);

  lastFmTagsCache.set(cacheKey, { value: tags, expiresAtMs: now + YTM_CACHE_TTL_MS });
  trimMapIfNeeded(lastFmTagsCache, 1000);
  return tags;
}

export async function resolveYouTubeMusicVideoId(rawTitle: string, rawArtist: string): Promise<string | null> {
  const apiKey = getYoutubeApiKey();
  if (!apiKey) return null;

  const { cleanTitle, cleanArtist } = cleanTrackAndArtistMetadata(rawTitle, rawArtist);
  if (!cleanTitle) return null;

  const key = buildCanonicalKey(cleanTitle, cleanArtist);
  const now = Date.now();
  const cached = ytmVideoCache.get(key);
  if (cached && cached.expiresAtMs > now) {
    return cached.value;
  }

  const query = cleanArtist ? `${cleanTitle} ${cleanArtist} official audio` : `${cleanTitle} official audio`;
  const data = await fetchJsonWithTimeout(
    `https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoCategoryId=10&maxResults=3&q=${encodeURIComponent(query)}&key=${encodeURIComponent(apiKey)}`,
    3500
  );

  const items: any[] = Array.isArray(data?.items) ? data.items : [];
  const best = items.find(it => it?.id?.videoId) || null;
  const videoId: string | null = best?.id?.videoId ? String(best.id.videoId) : null;

  ytmVideoCache.set(key, {
    value: videoId,
    expiresAtMs: now + (videoId ? YTM_CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS)
  });
  trimMapIfNeeded(ytmVideoCache, 2500);
  return videoId;
}

export async function resolveSpotifyUriViaSonglink(
  sourceUrls: Array<string | null | undefined>
): Promise<{ spotifyUri: string | null; spotifyUrl: string | null }> {
  const candidates = sourceUrls
    .map(u => String(u || '').trim())
    .filter(u => u.startsWith('http'));
  if (candidates.length === 0) {
    return { spotifyUri: null, spotifyUrl: null };
  }

  const now = Date.now();
  for (const sourceUrl of candidates) {
    const cached = songlinkSpotifyCache.get(sourceUrl);
    if (cached && cached.expiresAtMs > now) {
      if (cached.value.spotifyUri || cached.value.spotifyUrl) {
        return cached.value;
      }
      continue;
    }

    const data = await fetchJsonWithTimeout(
      `https://api.song.link/v1-alpha.1/links?url=${encodeURIComponent(sourceUrl)}`,
      3200
    );
    const spUrlRaw: string | null = data?.linksByPlatform?.spotify?.url
      ? String(data.linksByPlatform.spotify.url).trim()
      : null;
    const match = spUrlRaw ? /\/track\/([A-Za-z0-9]{22})/i.exec(spUrlRaw) : null;
    const spotifyUri = match ? `spotify:track:${match[1]}` : null;
    const spotifyUrl = match ? `https://open.spotify.com/track/${match[1]}` : spUrlRaw;
    const result = { spotifyUri, spotifyUrl };

    songlinkSpotifyCache.set(sourceUrl, {
      value: result,
      expiresAtMs: now + (spotifyUri || spotifyUrl ? YTM_CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS)
    });
    trimMapIfNeeded(songlinkSpotifyCache, 2500);
    if (spotifyUri || spotifyUrl) {
      return result;
    }
  }

  return { spotifyUri: null, spotifyUrl: null };
}

export async function resolveUniversalTrackProfile(
  rawTitle: string,
  rawArtist: string
): Promise<UniversalTrackProfile | null> {
  const { cleanTitle, cleanArtist } = cleanTrackAndArtistMetadata(rawTitle, rawArtist);
  if (!cleanTitle) return null;

  const key = buildCanonicalKey(cleanTitle, cleanArtist);
  const now = Date.now();
  const cached = trackProfileCache.get(key);
  if (cached && cached.expiresAtMs > now) {
    return cached.value;
  }

  const searchQuery = cleanArtist ? `${cleanTitle} ${cleanArtist}` : cleanTitle;
  const [dzHits, itHits, audiusHits, lastFmTags, ytmVideoId] = await Promise.all([
    searchDeezerTracks(searchQuery, 4),
    searchItunesTracks(searchQuery, 3),
    searchAudiusTracks(searchQuery, 3),
    cleanArtist ? fetchLastFmArtistTags(cleanArtist) : Promise.resolve([]),
    resolveYouTubeMusicVideoId(cleanTitle, cleanArtist)
  ]);

  const dz = dzHits.find(h => isStrictTitleArtistMatch(cleanTitle, cleanArtist, h.title, h.artist)) || dzHits[0] || null;
  const it = itHits.find(h => isStrictTitleArtistMatch(cleanTitle, cleanArtist, h.title, h.artist)) || itHits[0] || null;
  const au = audiusHits.find(h => isStrictTitleArtistMatch(cleanTitle, cleanArtist, h.title, h.artist)) || null;

  if (!dz && !it && !au && !ytmVideoId) {
    trackProfileCache.set(key, { value: null, expiresAtMs: now + NEGATIVE_CACHE_TTL_MS });
    trimMapIfNeeded(trackProfileCache, 2500);
    return null;
  }

  const resolvedTitle = dz?.title || it?.title || au?.title || cleanTitle;
  const resolvedArtist = dz?.artist || it?.artist || au?.artist || cleanArtist;
  const canonical = resolveCanonicalTrackIdentity({
    trackId: dz?.trackId || it?.trackId || au?.trackId,
    title: resolvedTitle,
    artist: resolvedArtist
  });

  const genreList = Array.from(
    new Set(
      [...(lastFmTags || []), it?.genre || '', au?.genre || '']
        .map(g => g.trim())
        .filter(Boolean)
    )
  ).slice(0, 4);

  const sourcesUsed = [dz ? 'deezer' : '', it ? 'itunes' : '', au ? 'audius' : ''].filter(Boolean);
  const ytmWatchUrl = ytmVideoId ? `https://music.youtube.com/watch?v=${ytmVideoId}` : null;
  const { spotifyUri, spotifyUrl } = await resolveSpotifyUriViaSonglink([
    dz?.deezerUrl || null,
    ytmWatchUrl
  ]);

  const profile: UniversalTrackProfile = {
    trackId: canonical.trackId,
    trackKey: key,
    cleanTitle: resolvedTitle,
    cleanArtist: resolvedArtist,
    albumName: dz?.album || it?.album || null,
    releaseDate: null,
    albumArtUrl: it?.albumArtUrl || dz?.albumArtUrl || au?.albumArtUrl || null,
    artistImageUrl: dz?.artistImageUrl || au?.artistImageUrl || null,
    genres: genreList,
    popularity: dz?.popularity || it?.popularity || au?.popularity || 55,
    previewUrl: dz?.previewUrl || it?.previewUrl || null,
    audiusTrackId: au?.audiusTrackId || null,
    audiusStreamUrl: au?.audiusStreamUrl || null,
    ytmVideoId,
    deezerUrl: dz?.deezerUrl || null,
    spotifyUri,
    spotifyUrl,
    source: sourcesUsed.length > 1 ? 'mixed' : ((sourcesUsed[0] as UniversalTrackProfile['source']) || 'deezer'),
    resolvedAtMs: now
  };

  trackProfileCache.set(key, { value: profile, expiresAtMs: now + TRACK_CACHE_TTL_MS });
  trimMapIfNeeded(trackProfileCache, 2500);
  return profile;
}

export async function searchOpenMusicCatalog(
  query: string,
  searchType: 'track' | 'artist' | 'album' | 'mood' = 'track',
  artist?: string,
  limit = 8,
  excludeExplicit = false,
  offset = 0
): Promise<OpenCatalogSearchResult> {
  const cleanQuery = (query || '').trim();
  const cleanArtist = (artist || '').trim();
  const fullQ = cleanArtist && !cleanQuery.toLowerCase().includes(cleanArtist.toLowerCase())
    ? `${cleanQuery} ${cleanArtist}`.trim()
    : cleanQuery;
  if (!fullQ) return { search_type: searchType, tracks: [] };

  const boundedLimit = Math.min(15, Math.max(1, Number(limit) || 8));
  const cacheKey = `${searchType}::${fullQ.toLowerCase()}::${boundedLimit}::${excludeExplicit}::${offset}`;
  const now = Date.now();
  const cached = catalogSearchCache.get(cacheKey);
  if (cached && cached.expiresAtMs > now) return cached.value;

  if (searchType === 'artist') {
    const artistData = await fetchJsonWithTimeout(
      `https://api.deezer.com/search/artist?q=${encodeURIComponent(fullQ)}&limit=3`,
      3500
    );
    const artistRows: any[] = Array.isArray(artistData?.data) ? artistData.data : [];
    const artists = artistRows.map(a => ({
      id: String(a.id),
      name: String(a.name || ''),
      genres: [] as string[],
      popularity: Math.min(99, Math.max(40, Math.round(Math.log10(Math.max(10, Number(a.nb_fan || 100))) * 15))),
      imageUrl: a.picture_big || a.picture_medium || null
    }));

    let tracks: OpenCatalogTrackHit[] = [];
    if (artists[0]?.id) {
      const topData = await fetchJsonWithTimeout(
        `https://api.deezer.com/artist/${encodeURIComponent(artists[0].id)}/top?limit=${boundedLimit}`,
        3500
      );
      const topRows: any[] = Array.isArray(topData?.data) ? topData.data : [];
      tracks = topRows
        .filter(item => item && item.title && (!excludeExplicit || !Boolean(item.explicit_lyrics)))
        .map(item => {
          const title = String(item.title || '').trim();
          const itemArtist = String(item.artist?.name || artists[0].name).trim();
          return {
            trackId: resolveCanonicalTrackIdentity({ trackId: `deezer:${item.id}`, title, artist: itemArtist }).trackId,
            title,
            artist: itemArtist,
            album: String(item.album?.title || '').trim(),
            genre: '',
            popularity: 72,
            explicit: Boolean(item.explicit_lyrics),
            durationMs: Number(item.duration || 0) * 1000,
            previewUrl: typeof item.preview === 'string' && item.preview.startsWith('http') ? item.preview : null,
            audiusStreamUrl: null,
            ytmVideoId: null,
            deezerUrl: item.link || (item.id ? `https://www.deezer.com/track/${item.id}` : null),
            albumArtUrl: item.album?.cover_big || item.album?.cover_medium || null,
            artistImageUrl: artists[0].imageUrl,
            source: 'deezer'
          };
        });
    }
    if (tracks.length === 0) {
      tracks = await searchDeezerTracks(fullQ, boundedLimit, offset, excludeExplicit);
    }
    const result: OpenCatalogSearchResult = { search_type: 'artist', artists, tracks };
    catalogSearchCache.set(cacheKey, { value: result, expiresAtMs: now + CATALOG_CACHE_TTL_MS });
    return result;
  }

  const [dzHits, itHits, auHits] = await Promise.all([
    searchDeezerTracks(fullQ, boundedLimit, offset, excludeExplicit),
    searchItunesTracks(fullQ, Math.min(8, boundedLimit), excludeExplicit),
    searchAudiusTracks(fullQ, 4)
  ]);

  const mergedMap = new Map<string, OpenCatalogTrackHit>();
  const addOrMerge = (hit: OpenCatalogTrackHit) => {
    const k = buildCanonicalKey(hit.title, hit.artist);
    const prev = mergedMap.get(k);
    if (!prev) {
      mergedMap.set(k, hit);
    } else {
      mergedMap.set(k, {
        ...prev,
        genre: prev.genre || hit.genre,
        previewUrl: prev.previewUrl || hit.previewUrl,
        audiusStreamUrl: prev.audiusStreamUrl || hit.audiusStreamUrl,
        deezerUrl: prev.deezerUrl || hit.deezerUrl,
        albumArtUrl: prev.albumArtUrl || hit.albumArtUrl,
        artistImageUrl: prev.artistImageUrl || hit.artistImageUrl
      });
    }
  };

  for (const h of dzHits) addOrMerge(h);
  for (const h of itHits) addOrMerge(h);
  for (const h of auHits) addOrMerge(h);

  const tracks = Array.from(mergedMap.values()).slice(0, boundedLimit);
  if (tracks[0] && !tracks[0].ytmVideoId && getYoutubeApiKey()) {
    tracks[0].ytmVideoId = await resolveYouTubeMusicVideoId(tracks[0].title, tracks[0].artist);
  }

  const result: OpenCatalogSearchResult = { search_type: searchType, tracks };
  catalogSearchCache.set(cacheKey, { value: result, expiresAtMs: now + CATALOG_CACHE_TTL_MS });
  trimMapIfNeeded(catalogSearchCache, 600);
  return result;
}

export async function discoverOpenMusicRecommendations(params: {
  seedArtists: string[];
  moodQuery?: string;
  excludeExplicit?: boolean;
  offset?: number;
  excludeNormalizedTitles?: Set<string>;
}): Promise<Array<OpenCatalogTrackHit & { source_reason: string }>> {
  const {
    seedArtists,
    moodQuery = '',
    excludeExplicit = false,
    offset = 0,
    excludeNormalizedTitles = new Set<string>()
  } = params;

  const discovered: Array<OpenCatalogTrackHit & { source_reason: string }> = [];
  const seenKeys = new Set<string>();

  const isNovel = (title: string, artist: string, explicit: boolean): boolean => {
    if (excludeExplicit && explicit) return false;
    const normTitle = normalizeForMatch(title);
    if (!normTitle || excludeNormalizedTitles.has(normTitle)) return false;
    const key = buildCanonicalKey(title, artist);
    if (seenKeys.has(key)) return false;
    seenKeys.add(key);
    return true;
  };

  const cleanMood = moodQuery.trim();
  const isKikuyuQuery = /\b(kikuyu|gikuyu|mugithi)\b/i.test(cleanMood);
  const isKidsQuery =
    excludeExplicit ||
    /\b(kids?|children|nursery|lullab(?:y|ies)|toddler|baby|cocomelon|disney|akili|ubongo|bedtime)\b/i.test(cleanMood);

  // 1. Mood / Cultural / Kids targeted open catalog queries first
  if (cleanMood || isKidsQuery) {
    const moodQueries: Array<{ q: string; reason: string }> = isKikuyuQuery
      ? [
          { q: 'Samidoh', reason: 'Authentic Kikuyu / Mugithi discovery matching your request' },
          { q: 'Jose Gatutura', reason: 'Fresh Kikuyu Mugithi track matching your mood' },
          { q: 'Kikuyu Gospel Grace Mwai', reason: 'Kikuyu / Gikuyu catalog discovery matching your request' }
        ]
      : isKidsQuery
      ? [
          { q: cleanMood ? `${cleanMood} kids` : 'Super Simple Songs', reason: 'Kid-safe family music matching your request' },
          { q: 'Cocomelon nursery rhymes', reason: 'Popular kid-friendly song' },
          { q: 'kids bedtime lullaby', reason: 'Gentle family-safe track for children' }
        ]
      : [{ q: cleanMood, reason: `Matches requested vibe: "${cleanMood}"` }];

    for (const mq of moodQueries) {
      const res = await searchOpenMusicCatalog(mq.q, 'track', undefined, 8, excludeExplicit, 0);
      for (const hit of res.tracks) {
        if (isNovel(hit.title, hit.artist, hit.explicit)) {
          discovered.push({ ...hit, source_reason: mq.reason });
        }
        if (discovered.length >= 8) break;
      }
      if (discovered.length >= 8) break;
    }
  }

  // 2. Deezer Related Artists + Last.fm artist.getSimilar graph (zero Spotify key!)
  const seeds = seedArtists.map(s => s.trim()).filter(Boolean).slice(0, 3);
  const lastFmKey = getLastFmApiKey();

  for (const seedName of seeds) {
    if (discovered.length >= 10) break;

    const dzArtistData = await fetchJsonWithTimeout(
      `https://api.deezer.com/search/artist?q=${encodeURIComponent(seedName)}&limit=1`,
      3000
    );
    const seedId = dzArtistData?.data?.[0]?.id;
    const relatedCandidates: Array<{ id?: string; name: string }> = [];

    if (seedId) {
      const relData = await fetchJsonWithTimeout(
        `https://api.deezer.com/artist/${encodeURIComponent(String(seedId))}/related?limit=4`,
        3000
      );
      for (const rel of relData?.data || []) {
        if (rel?.id && rel?.name) {
          relatedCandidates.push({ id: String(rel.id), name: String(rel.name) });
        }
      }
    }

    if (lastFmKey && relatedCandidates.length < 3) {
      const lfmRel = await fetchJsonWithTimeout(
        `https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist=${encodeURIComponent(seedName)}&limit=4&api_key=${encodeURIComponent(lastFmKey)}&format=json`,
        3000
      );
      for (const rel of lfmRel?.similarartists?.artist || []) {
        if (rel?.name && !relatedCandidates.some(r => r.name.toLowerCase() === String(rel.name).toLowerCase())) {
          relatedCandidates.push({ name: String(rel.name) });
        }
      }
    }

    for (const rel of relatedCandidates.slice(0, 3)) {
      if (discovered.length >= 10) break;
      if (rel.id) {
        const topData = await fetchJsonWithTimeout(
          `https://api.deezer.com/artist/${encodeURIComponent(rel.id)}/top?limit=8`,
          3000
        );
        for (const item of topData?.data || []) {
          const title = String(item?.title || '').trim();
          const artist = String(item?.artist?.name || rel.name).trim();
          const explicit = Boolean(item?.explicit_lyrics);
          if (title && isNovel(title, artist, explicit)) {
            discovered.push({
              trackId: resolveCanonicalTrackIdentity({ trackId: `deezer:${item.id}`, title, artist }).trackId,
              title,
              artist,
              album: String(item?.album?.title || '').trim(),
              genre: '',
              popularity: 68,
              explicit,
              durationMs: Number(item?.duration || 0) * 1000,
              previewUrl: typeof item?.preview === 'string' && item.preview.startsWith('http') ? item.preview : null,
              audiusStreamUrl: null,
              ytmVideoId: null,
              deezerUrl: item?.link || `https://www.deezer.com/track/${item.id}`,
              albumArtUrl: item?.album?.cover_big || item?.album?.cover_medium || null,
              artistImageUrl: null,
              source: 'deezer',
              source_reason: `Fresh kindred artist to ${seedName} (${rel.name})`
            });
          }
          if (discovered.length >= 10) break;
        }
      } else {
        const res = await searchOpenMusicCatalog(rel.name, 'artist', undefined, 5, excludeExplicit, 0);
        for (const hit of res.tracks) {
          if (isNovel(hit.title, hit.artist, hit.explicit)) {
            discovered.push({ ...hit, source_reason: `Similar artist to ${seedName} (${rel.name})` });
          }
        }
      }
    }
  }

  // 3. Fallback curated search rotation across Afro / Soul / R&B / Indie
  if (discovered.length < 4) {
    const fallbackPool = cleanMood
      ? [cleanMood]
      : ['afrobeats chill', 'amapiano groove', 'neo soul r&b', 'east african acoustic'];
    const pickQ = fallbackPool[offset % fallbackPool.length];
    const extra = await searchOpenMusicCatalog(pickQ, 'track', undefined, 8, excludeExplicit, (offset % 3) * 5);
    for (const hit of extra.tracks) {
      if (isNovel(hit.title, hit.artist, hit.explicit)) {
        discovered.push({ ...hit, source_reason: cleanMood ? `Matches "${cleanMood}"` : 'Fresh catalog rotation outside daily repeats' });
      }
      if (discovered.length >= 10) break;
    }
  }

  return discovered.slice(0, 10);
}

/**
 * POST /api/v1/music/resolve-profile
 * Resolves a single track played from ANY music player into Deezer/iTunes/Audius/Last.fm/YouTube profile.
 */
router.post('/resolve-profile', async (req: Request, res: Response) => {
  try {
    const title = String(req.body?.title || '').trim();
    const artist = String(req.body?.artist || '').trim();
    if (!title) {
      return res.status(400).json({ error: 'Missing track title' });
    }
    const profile = await resolveUniversalTrackProfile(title, artist);
    return res.json({ found: Boolean(profile), profile });
  } catch (err: any) {
    console.error('[MusicProfiles] /resolve-profile error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to resolve track profile' });
  }
});

/**
 * POST /api/v1/music/resolve-batch
 * Resolves up to 20 cross-app tracks concurrently using zero-approval open catalogs.
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

    for (let i = 0; i < rawTracks.length; i += 5) {
      const wave = rawTracks.slice(i, i + 5);
      const settled = await Promise.all(
        wave.map(async (item: any) => {
          const title = String(item?.title || '').trim();
          const artist = String(item?.artist || '').trim();
          const profile = await resolveUniversalTrackProfile(title, artist);
          return { query_title: title, query_artist: artist, profile };
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

/**
 * GET /api/v1/music/search?q=...&type=track|artist&limit=8&offset=0
 */
router.get('/search', async (req: Request, res: Response) => {
  try {
    const q = String(req.query.q || '').trim();
    if (!q) return res.status(400).json({ error: 'Missing search query parameter q' });
    const rawType = String(req.query.type || 'track').trim().toLowerCase();
    const searchType = rawType === 'artist' ? 'artist' : 'track';
    const limit = Math.min(15, Math.max(1, parseInt(String(req.query.limit || '8'), 10) || 8));
    const offset = Math.max(0, parseInt(String(req.query.offset || '0'), 10) || 0);
    const excludeExplicit = String(req.query.kids_safe || 'false') === 'true';

    const result = await searchOpenMusicCatalog(q, searchType, undefined, limit, excludeExplicit, offset);
    return res.json(result);
  } catch (err: any) {
    console.error('[MusicProfiles] /search error:', err?.message || err);
    return res.status(500).json({ error: 'Catalog search failed' });
  }
});

/**
 * GET /api/v1/music/resolve-playable?title=...&artist=...
 * Resolves exact playable endpoints for Hybrid Mode:
 * - audiusStreamUrl (full song in-app)
 * - previewUrl (30s iTunes/Deezer hook in-app)
 * - ytmVideoId (exact song inside YouTube Music app)
 * - deezerUrl (exact track page in Deezer)
 */
router.get('/resolve-playable', async (req: Request, res: Response) => {
  try {
    const title = String(req.query.title || '').trim();
    const artist = String(req.query.artist || '').trim();
    if (!title) return res.status(400).json({ error: 'Missing title query parameter' });
    const profile = await resolveUniversalTrackProfile(title, artist);
    return res.json({ found: Boolean(profile), profile });
  } catch (err: any) {
    console.error('[MusicProfiles] /resolve-playable error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to resolve playable sources' });
  }
});

export default router;
