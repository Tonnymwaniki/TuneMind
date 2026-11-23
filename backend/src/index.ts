/**
 * TuneMind Backend — Entry point
 *
 * Phase 1: Express skeleton with a single route stub.
 * Phase 2: Firebase Admin SDK initialised, full event ingestion implemented.
 * Phase 3: Raw HTTP server with WebSocket upgrade handler for OpenAI voice stream.
 */

import './bootstrap';

import http from 'http';
import os from 'os';
import { WebSocketServer, WebSocket, RawData } from 'ws';
import app from './app';
import { EventStore } from './domain/events/EventStore';
import { ClaudeMusicReasoningProvider, type TuneMindTelemetryContext } from './domain/reasoning/ClaudeMusicReasoningProvider';
import {
  computePcm16Rms,
  generateOpenAISpeechBase64,
  getOpenAIApiKey,
  transcribePcm16WithOpenAI
} from './domain/reasoning/OpenAIAdapter';
import { SupabaseIntelligenceStore } from './domain/storage/SupabaseIntelligenceStore';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const HOST = '0.0.0.0';

// ─── 1. Create the raw HTTP server using the Express app ────────────────────
const server = http.createServer(app);

// ─── 2. Create the WebSocket server (detached from auto-handling) ────────────
const wss = new WebSocketServer({
  noServer: true,
  maxPayload: 1024 * 1024 // 1 MB frame guard for full studio library/stats context
});

// ─── 3. Intercept the network upgrade request ────────────────────────────────
server.on('upgrade', (request, socket, head) => {
  const pathname = request.url?.split('?')[0];
  if (pathname === '/ws/stt') {
    wss.handleUpgrade(request, socket, head, (ws: WebSocket) => {
      wss.emit('connection', ws, request);
    });
  } else {
    socket.destroy();
  }
});

// ─── 4. Handle the active WebSocket connection with OpenAI gpt-4o-transcribe ─
wss.on('connection', async (ws: WebSocket) => {
  console.log('[VoiceStream] 🟢 Android client connected via WebSocket (OpenAI STT + gpt-4o).');

  const openAiKey = getOpenAIApiKey();
  if (!openAiKey) {
    console.warn('[VoiceStream] OPENAI_API_KEY is missing or empty.');
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ error: 'OPENAI_KEY_NOT_CONFIGURED', message: 'OPENAI_API_KEY not set' }));
    }
    return;
  }

  const SAMPLE_RATE = 16_000;
  const SPEECH_RMS_THRESHOLD = 120;
  const SILENCE_FINALIZE_MS = 1350;
  const MIN_AUDIO_BYTES = SAMPLE_RATE * 2 * 0.30; // ~300ms
  const MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 25;   // 25s max

  let chunkCount = 0;
  let pcmChunks: Buffer[] = [];
  let totalBytes = 0;
  let hasSpoken = false;
  let isTranscribing = false;
  let hasTriggeredReasoning = false;
  let silenceTimer: NodeJS.Timeout | null = null;

  const normalizeMusicTranscript = (raw: string): string => {
    let cleaned = raw.trim().replace(/\s+/g, ' ');
    // If wake phrase leaked from pre-roll audio ahead of the real command, strip only the leading wake phrase
    const strippedWake = cleaned
      .replace(/^(?:hi|hey|hello|ok|okay)?\s*(?:tune\s*mind|tomb\s*mind|to\s*mind|two\s*mind|tune\s*my)\b[,:.!?\s]+(?=\S)/gi, '')
      .trim();
    if (strippedWake.length >= 2) {
      cleaned = strippedWake;
    }
    cleaned = cleaned
      .replace(/\b(tomb\s*mind|to\s*mind|two\s*mind|tune\s*my)\b/gi, 'TuneMind')
      .replace(/\bplay\s*list\b/gi, 'playlist')
      .replace(/\b(clean|claim|blame|flame|let|leave|bleed|plane|plan)\s+me\s+a\s+song\b/gi, 'play me a song')
      .replace(/\b(please|playing|played|place)\s+(me\s+)?a\s+song\b/gi, 'play me a song')
      .replace(/\bplay\s+(mia|mere|near|mayor)\s+song\b/gi, 'play me a song')
      .replace(/\b(kikuyo|kiku\s*you|keekoo|kikoo|gikuyo|geekuyo|kick\s*you(?=\s+(song|songs|music|track|tune|playlist|benga|mugithi))|key\s*cue(?=\s+(song|songs|music|track|tune|playlist)))\b/gi, 'Kikuyu')
      .replace(/\b(mugiti|mugizi|mogithi|mugihti)\b/gi, 'Mugithi')
      .replace(/\b(co\s*co\s*melon|coco\s*mellan)\b/gi, 'Cocomelon')
      .replace(/\b(bongo\s+flower|bongo\s+lover|congo\s+flava|bongo\s+flavor)\b/gi, 'Bongo Flava')
      .replace(/\b(add|put|save)\s+(?:this|that|the)?\s*(?:current\s+)?(some|son|sun|phone)\b/gi, '$1 this current song')
      .replace(/\b(wrestling\s+vault|resting\s+(vote|bowl|volt))\b/gi, 'Resting Vault')
      .replace(/\b(slip\s+timer|sleep\s+time\s+a)\b/gi, 'sleep timer')
      .replace(/\b(change|switch)\s+(the|this)\s+(some|son|sun|phone)\b/gi, '$1 $2 song')
      .replace(/\bplay\s+(some|son|sun)\b/gi, 'play a song');
    return cleaned.trim();
  };

  const buildDynamicKeyterms = (ctx: typeof clientContext): string[] => {
    const terms = new Set<string>([
      'TuneMind', 'Spotify', 'YouTube Music', 'Boomplay', 'Audiomack',
      'TuneMind playlist', 'Resting Vault', 'Dopamine Capsule', 'For You',
      'Kids Mode', 'sleep timer', 'ndio', 'sawa'
    ]);
    const pushTerm = (val: unknown) => {
      const s = String(val || '').replace(/[\r\n]+/g, ' ').trim();
      if (s.length >= 2 && s.length <= 48 && terms.size < 80) {
        terms.add(s);
      }
    };
    if (ctx?.current_track) {
      pushTerm(ctx.current_track.artist);
      pushTerm(ctx.current_track.title);
    }
    for (const pl of (ctx?.tunemind_playlists || []).slice(0, 15)) {
      pushTerm(pl?.name);
    }
    for (const tr of ((ctx?.cross_app_library || []) as any[]).slice(0, 15)) {
      pushTerm((tr as any)?.artist);
      pushTerm((tr as any)?.title);
    }
    for (const r of (ctx?.recent_tracks || []).slice(0, 20)) {
      pushTerm(r?.artist);
      pushTerm(r?.title);
    }
    for (const f of (ctx?.for_you_picks || []).slice(0, 10)) {
      pushTerm((f as any)?.artist);
    }
    return Array.from(terms).slice(0, 80);
  };

  let clientContext: (Partial<TuneMindTelemetryContext> & {
    recent_tracks?: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; source?: string; playCount?: number }>;
    local_time?: string;
    local_hour?: number;
    local_minute?: number;
    timezone?: string;
    cross_app_library?: TuneMindTelemetryContext['cross_app_library'];
    detected_player_tabs?: TuneMindTelemetryContext['detected_player_tabs'];
    tunemind_playlists?: TuneMindTelemetryContext['tunemind_playlists'];
    kids_mode_enabled?: boolean;
    active_sleep_timer_minutes?: number;
    spotify_access_token?: string;
    spotify_device_name?: string;
  }) | null = null;

  const daypartFromHour = (h: number): 'Morning' | 'Midday' | 'Evening' | 'Late Night' => {
    if (h >= 6 && h < 12) return 'Morning';
    if (h >= 12 && h < 17) return 'Midday';
    if (h >= 17 && h < 22) return 'Evening';
    return 'Late Night';
  };

  const resolveClientTimeContext = (ctx: typeof clientContext) => {
    const rawHour = Number(ctx?.local_hour);
    let hour = Number.isFinite(rawHour) && rawHour >= 0 && rawHour <= 23 ? Math.floor(rawHour) : NaN;
    let minute = Number.isFinite(Number(ctx?.local_minute)) ? Math.floor(Number(ctx?.local_minute)) : NaN;
    const tz = (ctx?.timezone || '').trim();
    if (Number.isNaN(hour) && tz) {
      try {
        const parts = new Intl.DateTimeFormat('en-US', {
          timeZone: tz,
          hour: '2-digit',
          minute: '2-digit',
          hour12: false
        }).formatToParts(new Date());
        const hp = Number(parts.find(p => p.type === 'hour')?.value);
        const mp = Number(parts.find(p => p.type === 'minute')?.value);
        if (Number.isFinite(hp)) hour = hp % 24;
        if (Number.isFinite(mp)) minute = mp % 60;
      } catch {
        // Ignore invalid IANA timezone and fall back below
      }
    }
    if (Number.isNaN(hour)) hour = new Date().getHours();
    if (Number.isNaN(minute)) minute = new Date().getMinutes();

    const rawDaypart = ctx?.circadian_daypart;
    const validDayparts = ['Morning', 'Midday', 'Evening', 'Late Night'];
    const circadianDaypart = (rawDaypart && validDayparts.includes(rawDaypart))
      ? rawDaypart
      : daypartFromHour(hour);
    const localTime = ctx?.local_time || `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
    return { hour, minute, localTime, timezone: tz || undefined, circadianDaypart };
  };

  const triggerReasoningForTranscript = async (rawFinalText: string) => {
    const finalText = normalizeMusicTranscript(rawFinalText);
    if (hasTriggeredReasoning || !finalText.trim()) return;
    hasTriggeredReasoning = true;
    if (silenceTimer) {
      clearTimeout(silenceTimer);
      silenceTimer = null;
    }

    console.log(`\n[STT] ⚡ Finalized OpenAI Transcript: "${finalText}" (raw: "${rawFinalText}")`);

    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'transcript',
        text: finalText,
        transcript: finalText,
        isFinal: true
      }));
    }

    try {
      console.log('[VoiceStream] ⚡ Triggering Fast-Path Autonomous Tool-Calling Claude Pipeline...');
      const provider = new ClaudeMusicReasoningProvider();

      if (clientContext?.pending_proposed_track && clientContext.pending_proposed_track.title) {
        ClaudeMusicReasoningProvider.setPendingProposedTrack(
          clientContext.pending_proposed_track,
          clientContext.user_id || clientContext.user_email || 'tunemind-default-user'
        );
      }

      const eventStore = new EventStore();
      const storedTracks = eventStore.getRecentObservedTracks(50);

      const liveCurrent = clientContext?.current_track;
      const liveRecents = clientContext?.recent_tracks || [];

      const effectiveCurrentTrack = (liveCurrent?.title && liveCurrent.title !== 'Standing By' && liveCurrent.title !== 'Active Session' && liveCurrent.title !== 'Deep Flow State')
        ? {
            title: liveCurrent.title,
            artist: liveCurrent.artist || '',
            trackId: liveCurrent.trackId,
            spotifyUri: liveCurrent.spotifyUri,
            source_player: liveCurrent.source_player || 'unknown_player',
            is_playing: liveCurrent.is_playing
          }
        : (storedTracks[0] || {
            title: 'Active Session',
            artist: 'TuneMind Observer',
            source_player: 'unknown_player'
          });

      const effectiveHistory = liveRecents.length > 0
        ? liveRecents.slice(0, 60).map(t => ({
            title: t.title,
            artist: t.artist,
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            playCount: t.playCount,
            source_player: t.source || 'unknown_player'
          }))
        : storedTracks.slice(0, 50).map(t => ({
            title: t.title,
            artist: t.artist,
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            playCount: t.playCount,
            source_player: t.source
          }));

      const timeCtx = resolveClientTimeContext(clientContext);

      const response = await provider.evaluateSatiety({
        timestamp_utc: new Date().toISOString(),
        circadian_daypart: timeCtx.circadianDaypart,
        local_time: timeCtx.localTime,
        local_hour: timeCtx.hour,
        local_minute: timeCtx.minute,
        timezone: timeCtx.timezone,
        current_track: {
          title: effectiveCurrentTrack.title,
          artist: effectiveCurrentTrack.artist,
          trackId: (effectiveCurrentTrack as any).trackId,
          spotifyUri: (effectiveCurrentTrack as any).spotifyUri,
          source_player: (effectiveCurrentTrack as any).source_player || (effectiveCurrentTrack as any).source || 'unknown_player',
          is_playing: (effectiveCurrentTrack as any).is_playing
        },
        user_command: finalText,
        rolling_history: effectiveHistory,
        recently_played_by_agent: clientContext?.recently_played_by_agent || [],
        current_satiety_percentage: 50,
        hearing_telemetry: clientContext?.hearing_telemetry,
        vault_tracks: clientContext?.vault_tracks,
        dopamine_capsule: clientContext?.dopamine_capsule,
        pending_proposed_track: clientContext?.pending_proposed_track,
        dialogue_history: clientContext?.dialogue_history,
        user_id: clientContext?.user_id,
        user_email: clientContext?.user_email,
        supabase_url: clientContext?.supabase_url,
        supabase_anon_key: clientContext?.supabase_anon_key,
        for_you_picks: clientContext?.for_you_picks,
        cross_app_library: clientContext?.cross_app_library,
        detected_player_tabs: clientContext?.detected_player_tabs,
        tunemind_playlists: clientContext?.tunemind_playlists,
        kids_mode_enabled: clientContext?.kids_mode_enabled,
        active_sleep_timer_minutes: clientContext?.active_sleep_timer_minutes,
        listening_stats: clientContext?.listening_stats,
        player_preferences: clientContext?.player_preferences,
        app_state: clientContext?.app_state,
        spotify_access_token: clientContext?.spotify_access_token,
        spotify_device_name: clientContext?.spotify_device_name
      });

      if (response.voice_agent_tts) {
        (response as any).voice_agent_audio_base64 = await generateOpenAISpeechBase64(response.voice_agent_tts);
      }

      console.log('[VoiceStream] OpenAI reasoning completed:', response.voice_agent_tts);
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'command',
          action: 'THINKING_COMPLETE',
          payload: response.voice_agent_tts,
          data: response
        }));
      }
    } catch (e) {
      console.error('[VoiceStream] Error triggering reasoning:', e);
      const errText = "I caught that, but hit a quick snag connecting. Say it again for me?";
      const errAudio = await generateOpenAISpeechBase64(errText).catch(() => null);
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'command',
          action: 'THINKING_COMPLETE',
          payload: errText,
          data: {
            voice_agent_tts: errText,
            voice_agent_audio_base64: errAudio,
            agent_tool_call: { action: 'ADVISE_ONLY', expect_follow_up: true },
            os_execution_command: { intent: 'NONE', target_player: 'com.spotify.music', search_query: '' }
          }
        }));
      }
    }
  };

  const flushAudioAndTranscribe = async (fallbackTranscriptHint: string = '') => {
    if (silenceTimer) {
      clearTimeout(silenceTimer);
      silenceTimer = null;
    }
    if (hasTriggeredReasoning || isTranscribing) return;

    if (totalBytes < MIN_AUDIO_BYTES || !hasSpoken) {
      if (fallbackTranscriptHint.trim().length >= 2) {
        await triggerReasoningForTranscript(fallbackTranscriptHint);
      }
      return;
    }

    isTranscribing = true;
    const audioBuffer = Buffer.concat(pcmChunks);
    pcmChunks = [];
    totalBytes = 0;

    try {
      const hints = buildDynamicKeyterms(clientContext).slice(0, 20);
      const transcript = await transcribePcm16WithOpenAI(audioBuffer, hints);
      const bestText = transcript.trim() || fallbackTranscriptHint.trim();
      if (bestText.length >= 2) {
        await triggerReasoningForTranscript(bestText);
      }
    } finally {
      isTranscribing = false;
    }
  };

  // Handle incoming messages from the Android client
  ws.on('message', (message: RawData, isBinary: boolean) => {
    // Parse text frames (or JSON client_context) first
    if (!isBinary || typeof message === 'string') {
      try {
        const textData = message.toString();
        const json = JSON.parse(textData);
        if (json.type === 'finalize_utterance') {
          const fallbackHint = typeof json.transcript === 'string' ? json.transcript.trim() : '';
          void flushAudioAndTranscribe(fallbackHint);
          return;
        }
        if (json.type === 'client_context') {
          clientContext = json;
          const recentsList = Array.isArray(json.recent_tracks) ? json.recent_tracks : [];
          console.log(`[VoiceStream] 📱 Received live client playback context: Now Playing: "${json.current_track?.title}" by "${json.current_track?.artist}", Recent tracks: ${recentsList.length}`);
          if (recentsList.length > 0) {
            console.log(`[VoiceStream] 🎵 Phone History Sample: ${recentsList.slice(0, 8).map((r: any) => `"${r.title}" by ${r.artist}`).join(' | ')}`);
            try {
              const store = new EventStore();
              store.syncClientTracks(recentsList);
              const uid = SupabaseIntelligenceStore.normalizeUserId(json.user_id, json.user_email);
              SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(uid, recentsList, {
                hearingTelemetry: json.hearing_telemetry,
                vaultTracks: json.vault_tracks,
                override: {
                  supabaseUrl: json.supabase_url,
                  supabaseKey: json.supabase_anon_key
                }
              }).catch(() => {});
            } catch (_err) {}
          }
          return;
        }
      } catch (_e) {
        // If a client sent binary data without setting the binary opcode, fall through below
      }
    }

    const rawBuf = Buffer.isBuffer(message)
      ? message
      : Array.isArray(message)
        ? Buffer.concat(message)
        : Buffer.from(message as ArrayBuffer);

    if (rawBuf.length > 0 && !hasTriggeredReasoning) {
      chunkCount++;
      if (chunkCount % 20 === 0) {
        console.log(`[VoiceStream] 🔵 Buffering audio chunk... (x${chunkCount})`);
      }

      pcmChunks.push(rawBuf);
      totalBytes += rawBuf.length;

      const rms = computePcm16Rms(rawBuf);
      if (rms >= SPEECH_RMS_THRESHOLD) {
        hasSpoken = true;
        if (silenceTimer) {
          clearTimeout(silenceTimer);
          silenceTimer = null;
        }
      } else if (hasSpoken && !silenceTimer) {
        silenceTimer = setTimeout(() => {
          void flushAudioAndTranscribe();
        }, SILENCE_FINALIZE_MS);
      }

      if (totalBytes >= MAX_AUDIO_BYTES) {
        void flushAudioAndTranscribe();
      }
    }
  });

  ws.on('close', () => {
    console.log('[VoiceStream] 🔴 Android client disconnected.');
    hasTriggeredReasoning = true;
    if (silenceTimer) { clearTimeout(silenceTimer); silenceTimer = null; }
  });
});

// ─── 5. Start the server ─────────────────────────────────────────────────────
server.listen(PORT, HOST, () => {
  console.log(`TuneMind backend listening on ${HOST}:${PORT} (Phase 2 — with WebSocket STT)`);

  const interfaces = os.networkInterfaces();
  const lanUrls: string[] = [];
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name] || []) {
      if (net.family === 'IPv4' && !net.internal) {
        lanUrls.push(`http://${net.address}:${PORT}`);
      }
    }
  }

  console.log('===================================================================');
  if (lanUrls.length > 0) {
    console.log('📡 WIRELESS MODE (Same Wi-Fi / Hotspot — NO USB CABLE NEEDED):');
    console.log('   Tap the top card in the TuneMind phone app and enter:');
    for (const url of lanUrls) {
      console.log(`   👉 ${url}`);
    }
  }
  console.log('🌍 GLOBAL ONLINE MODE (Works on 4G/5G anywhere without Wi-Fi):');
  console.log('   Run in a 2nd terminal: npm run tunnel');
  console.log('   Then paste the https://xxxx.loca.lt URL into the phone app!');
  console.log('===================================================================');
});

export default app;
