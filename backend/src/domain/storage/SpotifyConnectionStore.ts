import crypto from 'crypto';
import { getSupabaseAdminClient } from '../../config/supabase';

export interface StoredSpotifyConnection {
  userId: string;
  clientId: string;
  refreshToken: string;
  displayName: string;
  product: string;
  lastDeviceName: string;
  connectedAt: string;
  lastRefreshedAt: string;
}

export interface UpsertSpotifyConnectionInput {
  clientId: string;
  refreshToken: string;
  displayName?: string;
  product?: string;
  lastDeviceName?: string;
  connectedAt?: string;
}

const ENCRYPTION_VERSION = 'v1';
const memoryFallbackStore = new Map<string, StoredSpotifyConnection>();
let warnedFallbackKey = false;

/**
 * Derives a 32-byte AES-256 key from SPOTIFY_TOKEN_ENCRYPTION_KEY.
 * Falls back to a deterministic SHA-256 derivation of SUPABASE_SERVICE_ROLE_KEY / SUPABASE_JWT_SECRET
 * if SPOTIFY_TOKEN_ENCRYPTION_KEY is not yet configured in the environment.
 */
function resolveEncryptionKey(): Buffer | null {
  const dedicated = (process.env.SPOTIFY_TOKEN_ENCRYPTION_KEY || '').trim();
  if (dedicated.length >= 16) {
    return crypto.createHash('sha256').update(`tunemind-spotify-v1:${dedicated}`).digest();
  }

  const fallbackSeed = (
    process.env.SUPABASE_SERVICE_ROLE_KEY ||
    process.env.SUPABASE_JWT_SECRET ||
    ''
  ).trim();

  if (fallbackSeed.length >= 16) {
    if (!warnedFallbackKey) {
      warnedFallbackKey = true;
      console.warn(
        '[SpotifyConnectionStore] SPOTIFY_TOKEN_ENCRYPTION_KEY is not set; using derived server key fallback. Set SPOTIFY_TOKEN_ENCRYPTION_KEY in production for key isolation.'
      );
    }
    return crypto.createHash('sha256').update(`tunemind-spotify-fallback-v1:${fallbackSeed}`).digest();
  }

  return null;
}

export function encryptSpotifyToken(plaintext: string): string {
  const key = resolveEncryptionKey();
  if (!key) {
    throw new Error('Server encryption key is not configured (set SPOTIFY_TOKEN_ENCRYPTION_KEY).');
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return [
    ENCRYPTION_VERSION,
    iv.toString('base64url'),
    authTag.toString('base64url'),
    encrypted.toString('base64url')
  ].join(':');
}

export function decryptSpotifyToken(payload: string): string | null {
  const key = resolveEncryptionKey();
  if (!key) return null;

  const parts = String(payload || '').trim().split(':');
  if (parts.length !== 4 || parts[0] !== ENCRYPTION_VERSION) {
    return null;
  }

  try {
    const iv = Buffer.from(parts[1], 'base64url');
    const authTag = Buffer.from(parts[2], 'base64url');
    const ciphertext = Buffer.from(parts[3], 'base64url');
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(authTag);
    const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    return decrypted.toString('utf8');
  } catch (err) {
    console.warn('[SpotifyConnectionStore] Failed to decrypt stored Spotify refresh token:', err);
    return null;
  }
}

export class SpotifyConnectionStore {
  static async upsertConnection(
    userId: string,
    input: UpsertSpotifyConnectionInput
  ): Promise<{ persistedToSupabase: boolean; record: StoredSpotifyConnection }> {
    const nowIso = new Date().toISOString();
    const record: StoredSpotifyConnection = {
      userId,
      clientId: (input.clientId || '').trim(),
      refreshToken: (input.refreshToken || '').trim(),
      displayName: (input.displayName || '').trim(),
      product: (input.product || '').trim().toLowerCase(),
      lastDeviceName: (input.lastDeviceName || '').trim(),
      connectedAt: input.connectedAt || nowIso,
      lastRefreshedAt: nowIso
    };

    const adminClient = getSupabaseAdminClient();
    if (!adminClient) {
      memoryFallbackStore.set(userId, record);
      return { persistedToSupabase: false, record };
    }

    const encryptedRefreshToken = encryptSpotifyToken(record.refreshToken);
    const { error } = await adminClient
      .from('user_spotify_connections')
      .upsert(
        {
          user_id: userId,
          spotify_client_id: record.clientId,
          encrypted_refresh_token: encryptedRefreshToken,
          spotify_display_name: record.displayName,
          spotify_product: record.product,
          last_device_name: record.lastDeviceName,
          connected_at: record.connectedAt,
          last_refreshed_at: record.lastRefreshedAt,
          updated_at: nowIso
        },
        { onConflict: 'user_id' }
      );

    if (error) {
      console.error('[SpotifyConnectionStore] Supabase upsert error:', error.message);
      memoryFallbackStore.set(userId, record);
      return { persistedToSupabase: false, record };
    }

    // Best-effort sync of profiles.spotify_connected flag
    try {
      await adminClient
        .from('profiles')
        .update({ spotify_connected: true, updated_at: nowIso })
        .eq('id', userId);
    } catch {
      // Non-fatal
    }

    memoryFallbackStore.set(userId, record);
    return { persistedToSupabase: true, record };
  }

  static async getConnection(userId: string): Promise<StoredSpotifyConnection | null> {
    const adminClient = getSupabaseAdminClient();
    if (adminClient) {
      const { data, error } = await adminClient
        .from('user_spotify_connections')
        .select(
          'user_id, spotify_client_id, encrypted_refresh_token, spotify_display_name, spotify_product, last_device_name, connected_at, last_refreshed_at'
        )
        .eq('user_id', userId)
        .maybeSingle();

      if (!error && data) {
        const decrypted = decryptSpotifyToken(String(data.encrypted_refresh_token || ''));
        if (decrypted) {
          return {
            userId: String(data.user_id),
            clientId: String(data.spotify_client_id || ''),
            refreshToken: decrypted,
            displayName: String(data.spotify_display_name || ''),
            product: String(data.spotify_product || ''),
            lastDeviceName: String(data.last_device_name || ''),
            connectedAt: String(data.connected_at || new Date().toISOString()),
            lastRefreshedAt: String(data.last_refreshed_at || new Date().toISOString())
          };
        }
      } else if (error) {
        console.warn('[SpotifyConnectionStore] Supabase read notice:', error.message);
      }
    }

    return memoryFallbackStore.get(userId) || null;
  }

  static async deleteConnection(userId: string): Promise<{ deletedFromSupabase: boolean }> {
    memoryFallbackStore.delete(userId);
    const adminClient = getSupabaseAdminClient();
    if (!adminClient) {
      return { deletedFromSupabase: false };
    }

    const { error } = await adminClient
      .from('user_spotify_connections')
      .delete()
      .eq('user_id', userId);

    try {
      await adminClient
        .from('profiles')
        .update({ spotify_connected: false, updated_at: new Date().toISOString() })
        .eq('id', userId);
    } catch {
      // Non-fatal
    }

    if (error) {
      console.warn('[SpotifyConnectionStore] Supabase delete notice:', error.message);
      return { deletedFromSupabase: false };
    }

    return { deletedFromSupabase: true };
  }
}
