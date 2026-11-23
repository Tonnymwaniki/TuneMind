import { Router, Request, Response } from 'express';
import { requireAuthInProduction, resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';
import { getOpenAIApiKey } from '../../domain/reasoning/OpenAIAdapter';
import { SupabaseIntelligenceStore } from '../../domain/storage/SupabaseIntelligenceStore';
import { TrackCardStore } from '../../domain/storage/TrackCardStore';

const router = Router();

const OPENAI_CLIENT_SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';
const OPENAI_SESSIONS_URL = 'https://api.openai.com/v1/realtime/sessions';

/**
 * POST /api/v1/realtime/session
 * Validates the user's JWT, loads strictly user-scoped Supabase memory/cards,
 * and mints a short-lived OpenAI Realtime ephemeral token so OPENAI_API_KEY stays server-side.
 */
router.post('/session', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const apiKey = getOpenAIApiKey();
    if (!apiKey) {
      return res.status(503).json({ error: 'OPENAI_API_KEY is not configured on backend' });
    }

    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
    const userId = identity.userId;

    const model = String(body.model || process.env.OPENAI_REALTIME_MODEL || 'gpt-realtime').trim();
    const voice = String(body.voice || process.env.OPENAI_TTS_VOICE || 'coral').trim();

    const [memory, cardSnap] = await Promise.all([
      SupabaseIntelligenceStore.fetchUserLongTermMemory(userId).catch(() => null),
      TrackCardStore.getSnapshot(userId).catch(() => null)
    ]);

    let minted: any = null;
    const gaResp = await fetch(OPENAI_CLIENT_SECRETS_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        expires_after: { anchor: 'created_at', seconds: 600 },
        session: {
          type: 'realtime',
          model,
          audio: {
            output: { voice }
          }
        }
      })
    });

    if (gaResp.ok) {
      minted = await gaResp.json();
    } else {
      const legacyResp = await fetch(OPENAI_SESSIONS_URL, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${apiKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: model === 'gpt-realtime' ? 'gpt-4o-realtime-preview' : model,
          voice
        })
      });
      if (!legacyResp.ok) {
        const errText = await legacyResp.text().catch(() => '');
        return res.status(502).json({
          error: `OpenAI Realtime mint failed (${gaResp.status}/${legacyResp.status})`,
          details: errText.slice(0, 300)
        });
      }
      minted = await legacyResp.json();
    }

    const clientSecret = minted?.value ?? minted?.client_secret?.value ?? '';
    const expiresAt = minted?.expires_at ?? minted?.client_secret?.expires_at ?? null;
    const resolvedModel = minted?.session?.model ?? minted?.model ?? model;

    if (!clientSecret) {
      return res.status(502).json({ error: 'OpenAI returned an empty ephemeral client secret' });
    }

    const topCards = (cardSnap?.track_cards || [])
      .slice()
      .sort((a, b) => (b.verified_play_count || 0) - (a.verified_play_count || 0))
      .slice(0, 8)
      .map(c => ({
        title: c.title,
        artist: c.artist,
        verified_play_count: c.verified_play_count || 0,
        is_in_vault: Boolean(c.is_in_vault),
        spotify_uri: c.spotify_uri || null
      }));

    return res.json({
      status: 'ok',
      client_secret: clientSecret,
      expires_at: expiresAt,
      model: resolvedModel,
      voice,
      user_context: {
        user_id: userId,
        email: identity.userEmail || '',
        top_artists: memory?.topArtistsOverTime?.slice(0, 8) || [],
        recently_approved_tracks: memory?.approvedTracks?.slice(-6) || [],
        recently_rejected_tracks: memory?.rejectedTracks?.slice(-6) || [],
        top_track_cards: topCards,
        recent_interventions: memory?.recentInterventions?.slice(0, 4) || []
      }
    });
  } catch (err: any) {
    console.error('[Realtime] Session mint error:', err?.message || err);
    return res.status(500).json({ error: err?.message || 'Failed to mint OpenAI Realtime session' });
  }
});

export default router;
