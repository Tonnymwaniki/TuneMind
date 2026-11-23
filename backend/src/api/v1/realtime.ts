import { Router, Request, Response } from 'express';
import { requireAuthInProduction, resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';
import { getOpenAIApiKey } from '../../domain/reasoning/OpenAIAdapter';
import { SupabaseIntelligenceStore } from '../../domain/storage/SupabaseIntelligenceStore';
import { TrackCardStore } from '../../domain/storage/TrackCardStore';

const router = Router();

const OPENAI_CLIENT_SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';
const OPENAI_SESSIONS_URL = 'https://api.openai.com/v1/realtime/sessions';
const GEMINI_AUTH_TOKENS_URL = 'https://generativelanguage.googleapis.com/v1alpha/auth_tokens';

function sanitizeApiKey(raw: string): string {
  return raw.split(/[\r\n]/)[0].replace(/^Bearer\s+/i, '').trim();
}

function redactErrorDetails(raw: string): string {
  return raw.replace(/sk-[A-Za-z0-9_-]{12,}/g, '[redacted-key]').replace(/AIza[A-Za-z0-9_-]{12,}/g, '[redacted-key]').slice(0, 300);
}

/**
 * POST /api/v1/realtime/session
 * Validates the user's JWT, loads strictly user-scoped Supabase memory/cards,
 * and mints a short-lived ephemeral token (Gemini Live when GEMINI_API_KEY is active,
 * or OpenAI Realtime when configured/selected) so API keys stay server-side.
 */
router.post('/session', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const apiKey = sanitizeApiKey(getOpenAIApiKey());
    const geminiApiKey = sanitizeApiKey(
      process.env.GEMINI_API_KEY ||
      process.env.GOOGLE_AI_API_KEY ||
      process.env.GOOGLE_API_KEY ||
      ''
    );

    if (!apiKey && !geminiApiKey) {
      return res.status(503).json({ error: 'Neither GEMINI_API_KEY nor OPENAI_API_KEY is configured on backend' });
    }

    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(req, body.user_id, body.user_email);
    const userId = identity.userId;

    const configuredProvider = String(
      process.env.REALTIME_PROVIDER || body.provider || 'auto'
    ).trim().toLowerCase();

    const useGemini =
      (configuredProvider === 'gemini' && Boolean(geminiApiKey)) ||
      (configuredProvider !== 'openai' && Boolean(geminiApiKey));

    const [memory, cardSnap] = await Promise.all([
      SupabaseIntelligenceStore.fetchUserLongTermMemory(userId).catch(() => null),
      TrackCardStore.getSnapshot(userId).catch(() => null)
    ]);

    let activeProvider: 'gemini' | 'openai' = useGemini ? 'gemini' : 'openai';
    let clientSecret = '';
    let wsUrl = '';
    let expiresAt: string | number | null = null;
    let resolvedModel = '';
    let resolvedVoice = '';

    if (activeProvider === 'gemini') {
      const geminiModelRaw = String(
        process.env.GEMINI_LIVE_MODEL || 'models/gemini-2.5-flash-native-audio-preview-12-2025'
      ).trim();
      const geminiModel = geminiModelRaw.startsWith('models/') ? geminiModelRaw : `models/${geminiModelRaw}`;
      resolvedVoice = String(process.env.GEMINI_LIVE_VOICE || 'Aoede').trim();
      const expireIso = new Date(Date.now() + 30 * 60_000).toISOString();
      const newSessionExpireIso = new Date(Date.now() + 2 * 60_000).toISOString();

      const authTokResp = await fetch(GEMINI_AUTH_TOKENS_URL, {
        method: 'POST',
        headers: {
          'x-goog-api-key': geminiApiKey,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          uses: 1,
          expireTime: expireIso,
          newSessionExpireTime: newSessionExpireIso
        })
      });

      if (!authTokResp.ok) {
        const errText = await authTokResp.text().catch(() => '');
        return res.status(502).json({
          error: `Gemini Live token mint failed (${authTokResp.status})`,
          details: redactErrorDetails(errText)
        });
      }

      const tokJson: any = await authTokResp.json();
      clientSecret = String(tokJson?.name || '').trim();
      expiresAt = tokJson?.expireTime ?? expireIso;
      resolvedModel = geminiModel;
      wsUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=${encodeURIComponent(clientSecret)}`;
    } else {
      const model = String(body.model || process.env.OPENAI_REALTIME_MODEL || 'gpt-realtime').trim();
      const voice = String(body.voice || process.env.OPENAI_TTS_VOICE || 'coral').trim();
      resolvedVoice = voice;

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
            details: redactErrorDetails(errText)
          });
        }
        minted = await legacyResp.json();
      }

      clientSecret = minted?.value ?? minted?.client_secret?.value ?? '';
      expiresAt = minted?.expires_at ?? minted?.client_secret?.expires_at ?? null;
      resolvedModel = minted?.session?.model ?? minted?.model ?? model;
    }

    if (!clientSecret) {
      return res.status(502).json({ error: `${activeProvider} returned an empty ephemeral client secret` });
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
      provider: activeProvider,
      client_secret: clientSecret,
      ws_url: wsUrl,
      expires_at: expiresAt,
      model: resolvedModel,
      voice: resolvedVoice,
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
