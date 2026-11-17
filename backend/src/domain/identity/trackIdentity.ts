export interface TrackIdentityInput {
  trackId?: string | null;
  spotifyUri?: string | null;
  title?: string | null;
  artist?: string | null;
  prefix?: string;
}

export interface CanonicalTrackIdentity {
  trackId: string;
  spotifyUri?: string;
}

const SPOTIFY_URI_RE = /^spotify:track:([A-Za-z0-9]+)$/i;
const SPOTIFY_OPEN_URL_RE = /^https?:\/\/open\.spotify\.com\/track\/([A-Za-z0-9]+)/i;
const SPOTIFY_BASE62_RE = /^[A-Za-z0-9]{22}$/;

export function isSpotifyTrackUri(raw?: string | null): boolean {
  if (!raw) return false;
  return SPOTIFY_URI_RE.test(raw.trim());
}

export function extractSpotifyBase62Id(raw?: string | null): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  const uriMatch = trimmed.match(SPOTIFY_URI_RE);
  if (uriMatch?.[1]) return uriMatch[1];
  const urlMatch = trimmed.match(SPOTIFY_OPEN_URL_RE);
  if (urlMatch?.[1]) return urlMatch[1];
  if (SPOTIFY_BASE62_RE.test(trimmed)) return trimmed;
  return undefined;
}

export function normalizeSpotifyUri(raw?: string | null): string | undefined {
  const id = extractSpotifyBase62Id(raw);
  return id ? `spotify:track:${id}` : undefined;
}

export function slugifyPart(raw?: string | null, fallback = 'unknown'): string {
  const cleaned = (raw || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return cleaned || fallback;
}

export function buildDeterministicTrackId(
  title?: string | null,
  artist?: string | null,
  prefix = 'trk'
): string {
  const titleSlug = slugifyPart(title, 'untitled').slice(0, 48);
  const artistSlug = slugifyPart(artist, 'unknown').slice(0, 36);
  return `${prefix}:${artistSlug}::${titleSlug}`;
}

/**
 * Normalizes title/artist variations (stripping feat/remaster suffixes and secondary artists)
 * so Spotify library/API entries and device MediaSession plays converge onto one track record.
 */
export function buildNormalizedMatchKey(
  title?: string | null,
  artist?: string | null
): string {
  const cleanTitle = (title || '')
    .trim()
    .toLowerCase()
    .replace(/\s*\([^)]*\)/g, '')
    .replace(/\s*\[[^\]]*\]/g, '')
    .replace(/\s+-\s+(remaster(ed)?|radio edit|single version|live|deluxe|acoustic|explicit|clean).*$/i, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
  const rawPrimary = (artist || '')
    .trim()
    .toLowerCase()
    .split(/\s*(?:,|&|feat\.?|ft\.?|\bx\b)\s*/)[0] || '';
  const cleanArtist = rawPrimary.replace(/[^a-z0-9]+/g, ' ').trim();
  const finalArtist = cleanArtist && cleanArtist !== 'unknown artist' ? cleanArtist : '';
  return `${cleanTitle}|${finalArtist}`;
}

const UI_LIST_KEY_PREFIXES = [
  'hist_',
  'sp_like_',
  'sp_pl_',
  'sp_seed_',
  'vault_',
  'cap_',
  'fy_',
  'np_',
  'search_',
  'rec_'
];

function isEphemeralUiListKey(rawId: string): boolean {
  const lower = rawId.trim().toLowerCase();
  return UI_LIST_KEY_PREFIXES.some(prefix => lower.startsWith(prefix));
}

/**
 * Resolves a deterministic canonical trackId separated from spotifyUri.
 * - Never overloads spotifyUri with non-Spotify IDs or arbitrary slugs.
 * - Never fabricates `spotify:track:<nonSpotifyId>`.
 * - Rejects ephemeral UI Compose list keys (`hist_...`, `sp_like_...`, `vault_...`, etc.).
 */
export function resolveCanonicalTrackIdentity(input: TrackIdentityInput): CanonicalTrackIdentity {
  const rawTrackId = (input.trackId || '').trim();
  const rawSpotifyUri = (input.spotifyUri || '').trim();

  const resolvedSpotifyUri =
    normalizeSpotifyUri(rawSpotifyUri) ||
    normalizeSpotifyUri(rawTrackId);

  let resolvedTrackId = '';
  if (rawTrackId && !isEphemeralUiListKey(rawTrackId)) {
    const asSpotifyId = extractSpotifyBase62Id(rawTrackId);
    if (rawTrackId.startsWith('spotify:track:') && asSpotifyId) {
      resolvedTrackId = asSpotifyId;
    } else if (rawTrackId.startsWith('http://') || rawTrackId.startsWith('https://')) {
      resolvedTrackId = asSpotifyId || buildDeterministicTrackId(input.title, input.artist, input.prefix || 'trk');
    } else {
      resolvedTrackId = rawTrackId;
    }
  } else if (resolvedSpotifyUri) {
    resolvedTrackId = extractSpotifyBase62Id(resolvedSpotifyUri) || resolvedSpotifyUri;
  } else {
    resolvedTrackId = buildDeterministicTrackId(input.title, input.artist, input.prefix || 'trk');
  }

  return {
    trackId: resolvedTrackId,
    spotifyUri: resolvedSpotifyUri
  };
}

export function coerceNonNegativePlayCount(value: unknown): number {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.floor(n);
}
