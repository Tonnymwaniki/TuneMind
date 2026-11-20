import { Router, Request, Response } from 'express';
import { requireAuth } from '../../middleware/authMiddleware';
import { SpotifyConnectionStore } from '../../domain/storage/SpotifyConnectionStore';

const router = Router();

const CLIENT_ID_REGEX = /^[A-Za-z0-9]{16,64}$/;

/**
 * GET /api/v1/spotify/client-config
 * Public endpoint returning TuneMind's optional app-default Spotify PKCE Client ID.
 * Client ID is public by OAuth 2.0 PKCE design; no client_secret is ever exposed.
 */
router.get('/client-config', (_req: Request, res: Response) => {
  const rawClientId = String(process.env.SPOTIFY_CLIENT_ID || '').trim();
  const isValid = CLIENT_ID_REGEX.test(rawClientId);
  res.json({
    configured: isValid,
    client_id: isValid ? rawClientId : null,
    redirect_uri: 'tunemind://spotify-callback'
  });
});

/**
 * GET /api/v1/spotify/connection
 * Restores the authenticated user's encrypted Spotify PKCE connection (Client ID + refresh token)
 * so users never need to reconnect after reinstalling TuneMind or switching phones.
 */
router.get('/connection', requireAuth, async (req: Request, res: Response) => {
  try {
    const userId = req.authUser!.id;
    const connection = await SpotifyConnectionStore.getConnection(userId);
    if (!connection) {
      res.json({
        status: 'ok',
        connected: false,
        connection: null
      });
      return;
    }

    res.json({
      status: 'ok',
      connected: true,
      connection: {
        client_id: connection.clientId,
        refresh_token: connection.refreshToken,
        display_name: connection.displayName,
        product: connection.product,
        last_device_name: connection.lastDeviceName,
        connected_at: connection.connectedAt,
        last_refreshed_at: connection.lastRefreshedAt
      }
    });
  } catch (err: any) {
    console.error('[Spotify API] GET /connection error:', err);
    res.status(500).json({ error: err?.message || 'Failed to restore Spotify connection' });
  }
});

/**
 * POST /api/v1/spotify/connection
 * Encrypts (AES-256-GCM) and persists the authenticated user's Spotify PKCE refresh token & metadata.
 */
router.post('/connection', requireAuth, async (req: Request, res: Response) => {
  try {
    const userId = req.authUser!.id;
    const body = req.body || {};
    const refreshToken = String(body.refresh_token || '').trim();
    const clientId = String(body.client_id || '').trim();
    const displayName = String(body.display_name || '').trim().slice(0, 120);
    const product = String(body.product || '').trim().toLowerCase().slice(0, 32);
    const lastDeviceName = String(body.last_device_name || '').trim().slice(0, 120);
    const connectedAt = typeof body.connected_at === 'string' && body.connected_at.trim()
      ? body.connected_at.trim()
      : undefined;

    if (!refreshToken || refreshToken.length < 16 || refreshToken.length > 2048) {
      res.status(400).json({ error: 'A valid Spotify refresh_token is required.' });
      return;
    }
    if (clientId && !CLIENT_ID_REGEX.test(clientId)) {
      res.status(400).json({ error: 'Invalid Spotify client_id format.' });
      return;
    }

    const result = await SpotifyConnectionStore.upsertConnection(userId, {
      clientId,
      refreshToken,
      displayName,
      product,
      lastDeviceName,
      connectedAt
    });

    res.json({
      status: 'ok',
      persisted_to_supabase: result.persistedToSupabase,
      display_name: result.record.displayName,
      product: result.record.product,
      last_refreshed_at: result.record.lastRefreshedAt
    });
  } catch (err: any) {
    console.error('[Spotify API] POST /connection error:', err);
    res.status(500).json({ error: err?.message || 'Failed to save Spotify connection' });
  }
});

/**
 * DELETE /api/v1/spotify/connection
 * Removes the authenticated user's encrypted Spotify cloud backup when they disconnect.
 */
router.delete('/connection', requireAuth, async (req: Request, res: Response) => {
  try {
    const userId = req.authUser!.id;
    const result = await SpotifyConnectionStore.deleteConnection(userId);
    res.json({
      status: 'ok',
      deleted_from_supabase: result.deletedFromSupabase
    });
  } catch (err: any) {
    console.error('[Spotify API] DELETE /connection error:', err);
    res.status(500).json({ error: err?.message || 'Failed to delete Spotify connection' });
  }
});

export default router;
