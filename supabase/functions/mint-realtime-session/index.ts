// Supabase Edge Function: mint-realtime-session
// Validates the caller's Supabase JWT, loads strictly user-scoped preferences/history,
// and mints a short-lived OpenAI Realtime ephemeral client secret so OPENAI_API_KEY never leaves the server.

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

const OPENAI_CLIENT_SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';
const OPENAI_SESSIONS_URL = 'https://api.openai.com/v1/realtime/sessions';

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  if (req.method !== 'POST') {
    return new Response(JSON.stringify({ error: 'Method not allowed' }), {
      status: 405,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }

  try {
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? '';
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') ?? '';
    const openAiApiKey = (Deno.env.get('OPENAI_API_KEY') ?? '').trim();

    if (!supabaseUrl || !supabaseAnonKey) {
      return new Response(JSON.stringify({ error: 'Supabase environment is not configured' }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    if (!openAiApiKey) {
      return new Response(JSON.stringify({ error: 'OPENAI_API_KEY secret is not configured in Supabase Edge Function' }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    const authHeader = req.headers.get('Authorization') ?? '';
    const token = authHeader.replace(/^Bearer\s+/i, '').trim();
    if (!token) {
      return new Response(JSON.stringify({ error: 'Missing Authorization Bearer JWT' }), {
        status: 401,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    // Bind client to the caller's JWT so RLS isolates every query strictly to auth.uid()
    const supabase = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: `Bearer ${token}` } },
      auth: { persistSession: false, autoRefreshToken: false }
    });

    const { data: userData, error: userErr } = await supabase.auth.getUser(token);
    if (userErr || !userData?.user) {
      return new Response(JSON.stringify({ error: 'Invalid or expired Supabase JWT' }), {
        status: 401,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    const user = userData.user;
    const userId = user.id;
    const userEmail = (user.email ?? '').trim().toLowerCase();
    const userFilter = userEmail
      ? `user_id.eq.${userId},user_id.eq.${userEmail}`
      : `user_id.eq.${userId}`;

    // Load strictly user-scoped preferences, taste genome, top cards, and recent interventions in parallel
    const [profileRes, genomeRes, topCardsRes, recentTurnsRes] = await Promise.all([
      supabase
        .from('profiles')
        .select('display_name, spotify_connected, private_session_enabled, merge_duplicate_plays, preferred_target_player, taste_archetype')
        .eq('id', userId)
        .maybeSingle(),
      supabase
        .from('user_taste_genome')
        .select('top_artists_json, approved_tracks_json, rejected_tracks_json')
        .or(userFilter)
        .limit(1)
        .maybeSingle(),
      supabase
        .from('track_cards')
        .select('title, artist, verified_play_count, is_in_vault, spotify_uri')
        .or(userFilter)
        .order('verified_play_count', { ascending: false })
        .limit(8),
      supabase
        .from('agent_interventions')
        .select('trigger_transcript, agent_tts_response, os_intent, created_at')
        .or(userFilter)
        .order('created_at', { ascending: false })
        .limit(4)
    ]);

    const parseJsonArray = (raw: unknown): unknown[] => {
      if (typeof raw !== 'string' || !raw.trim()) return [];
      try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
      } catch {
        return [];
      }
    };

    const reqBody = await req.json().catch(() => ({}));
    const model = String(reqBody?.model || Deno.env.get('OPENAI_REALTIME_MODEL') || 'gpt-realtime').trim();
    const voice = String(reqBody?.voice || Deno.env.get('OPENAI_TTS_VOICE') || 'coral').trim();

    // Mint ephemeral Realtime client secret (GA /v1/realtime/client_secrets -> fallback /v1/realtime/sessions)
    let minted: any = null;
    const gaResp = await fetch(OPENAI_CLIENT_SECRETS_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${openAiApiKey}`,
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
          Authorization: `Bearer ${openAiApiKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: model === 'gpt-realtime' ? 'gpt-4o-realtime-preview' : model,
          voice
        })
      });
      if (!legacyResp.ok) {
        const errText = await legacyResp.text().catch(() => '');
        return new Response(
          JSON.stringify({
            error: `Failed to mint OpenAI Realtime secret (${gaResp.status}/${legacyResp.status})`,
            details: errText.slice(0, 300)
          }),
          { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }
      minted = await legacyResp.json();
    }

    const clientSecret = minted?.value ?? minted?.client_secret?.value ?? '';
    const expiresAt = minted?.expires_at ?? minted?.client_secret?.expires_at ?? null;
    const resolvedModel = minted?.session?.model ?? minted?.model ?? model;

    if (!clientSecret) {
      return new Response(JSON.stringify({ error: 'OpenAI returned an empty ephemeral client secret' }), {
        status: 502,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    const profile = profileRes.data ?? null;
    const genome = genomeRes.data ?? null;

    return new Response(
      JSON.stringify({
        status: 'ok',
        client_secret: clientSecret,
        expires_at: expiresAt,
        model: resolvedModel,
        voice,
        user_context: {
          user_id: userId,
          email: userEmail,
          display_name: profile?.display_name ?? user.user_metadata?.full_name ?? user.user_metadata?.name ?? 'TuneMind Listener',
          taste_archetype: profile?.taste_archetype ?? '',
          preferred_target_player: profile?.preferred_target_player ?? '',
          spotify_connected_in_profile: Boolean(profile?.spotify_connected),
          private_session_enabled: Boolean(profile?.private_session_enabled),
          merge_duplicate_plays: profile?.merge_duplicate_plays ?? true,
          top_artists: parseJsonArray(genome?.top_artists_json).slice(0, 8),
          recently_approved_tracks: parseJsonArray(genome?.approved_tracks_json).slice(-6),
          recently_rejected_tracks: parseJsonArray(genome?.rejected_tracks_json).slice(-6),
          top_track_cards: topCardsRes.data ?? [],
          recent_interventions: recentTurnsRes.data ?? []
        }
      }),
      {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: err?.message || 'Unexpected Edge Function failure' }),
      {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      }
    );
  }
});
