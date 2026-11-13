import { Router, Request, Response } from 'express';

const router = Router();

/**
 * POST /api/v1/spotify/token
 * Exchanges an OAuth 2.0 Authorization Code (with optional PKCE code_verifier) for
 * a Spotify Access Token and Refresh Token.
 */
router.post('/token', async (req: Request, res: Response) => {
  try {
    const { code, redirect_uri, code_verifier, client_id } = req.body || {};
    if (!code) {
      return res.status(400).json({ error: 'Missing authorization code' });
    }

    const effectiveClientId = (client_id || process.env.SPOTIFY_CLIENT_ID || '').trim();
    const clientSecret = (process.env.SPOTIFY_CLIENT_SECRET || '').trim();
    const effectiveRedirectUri = (redirect_uri || process.env.SPOTIFY_REDIRECT_URI || 'tunemind://spotify-callback').trim();

    if (!effectiveClientId) {
      return res.status(400).json({ error: 'SPOTIFY_CLIENT_ID is not configured' });
    }

    const bodyParams = new URLSearchParams({
      grant_type: 'authorization_code',
      code: String(code),
      redirect_uri: effectiveRedirectUri,
      client_id: effectiveClientId
    });

    if (code_verifier) {
      bodyParams.set('code_verifier', String(code_verifier));
    }

    const headers: Record<string, string> = {
      'Content-Type': 'application/x-www-form-urlencoded'
    };

    if (clientSecret && !code_verifier) {
      headers['Authorization'] =
        'Basic ' + Buffer.from(`${effectiveClientId}:${clientSecret}`).toString('base64');
    }

    const tokenRes = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers,
      body: bodyParams
    });

    const data = (await tokenRes.json()) as any;
    if (!tokenRes.ok) {
      return res.status(tokenRes.status).json(data);
    }

    return res.status(200).json(data);
  } catch (err: any) {
    console.error('[Spotify Auth API] /token exchange error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to exchange Spotify authorization code' });
  }
});

/**
 * POST /api/v1/spotify/refresh
 * Silently refreshes an expired Spotify Access Token using a Refresh Token.
 */
router.post('/refresh', async (req: Request, res: Response) => {
  try {
    const { refresh_token, client_id } = req.body || {};
    if (!refresh_token) {
      return res.status(400).json({ error: 'Missing refresh_token' });
    }

    const effectiveClientId = (client_id || process.env.SPOTIFY_CLIENT_ID || '').trim();
    const clientSecret = (process.env.SPOTIFY_CLIENT_SECRET || '').trim();

    if (!effectiveClientId) {
      return res.status(400).json({ error: 'SPOTIFY_CLIENT_ID is not configured' });
    }

    const bodyParams = new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: String(refresh_token),
      client_id: effectiveClientId
    });

    const headers: Record<string, string> = {
      'Content-Type': 'application/x-www-form-urlencoded'
    };

    if (clientSecret) {
      headers['Authorization'] =
        'Basic ' + Buffer.from(`${effectiveClientId}:${clientSecret}`).toString('base64');
    }

    const refreshRes = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers,
      body: bodyParams
    });

    const data = (await refreshRes.json()) as any;
    if (!refreshRes.ok) {
      return res.status(refreshRes.status).json(data);
    }

    return res.status(200).json(data);
  } catch (err: any) {
    console.error('[Spotify Auth API] /refresh error:', err?.message || err);
    return res.status(500).json({ error: 'Failed to refresh Spotify access token' });
  }
});

export default router;
