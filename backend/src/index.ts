/**
 * TuneMind Backend — Entry point
 *
 * Phase 1: Express skeleton with a single route stub.
 * Phase 2: Firebase Admin SDK initialised, full event ingestion implemented.
 * Phase 3: Raw HTTP server with WebSocket upgrade handler for AssemblyAI voice stream.
 */

import './bootstrap';

import http from 'http';
import os from 'os';
import { WebSocketServer, WebSocket, RawData } from 'ws';
import { AssemblyAI } from 'assemblyai';
import express from 'express';
import { requestLogger } from './middleware/requestLogger';
import eventsRouter from './api/v1/events';
import analyticsRouter from './api/v1/analytics';
import reasoningRouter from './api/v1/reasoning';
import { EventStore } from './domain/events/EventStore';

const app = express();
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const HOST = '0.0.0.0';

// ─── Middleware ───────────────────────────────────────────────────────────────
app.use(express.json({ limit: '5mb' }));
app.use(requestLogger);

// ─── Routes ──────────────────────────────────────────────────────────────────
app.use('/api/v1/events', eventsRouter);
app.use('/api/v1/analytics', analyticsRouter);
app.use('/api/v1/reasoning', reasoningRouter);

app.get('/', (_req, res) => {
  res.json({
    status: 'online',
    service: 'TuneMind Cloud Brain (24/7 Online)',
    websocket_endpoint: '/ws/stt',
    health_endpoint: '/health',
    timestamp: new Date().toISOString(),
  });
});

app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    phase: 2,
    service: 'TuneMind Fullstack Intelligence Platform',
    timestamp: new Date().toISOString(),
  });
});

// ─── 404 handler for HTTP routes ─────────────────────────────────────────────
app.use((_req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// ─── 1. Create the raw HTTP server using the Express app ────────────────────
const server = http.createServer(app);

// ─── 2. Create the WebSocket server (detached from auto-handling) ────────────
const wss = new WebSocketServer({ noServer: true });

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

// ─── 4. Handle the active WebSocket connection with AssemblyAI Universal v3 ─
wss.on('connection', async (ws: WebSocket) => {
  console.log('[VoiceStream] 🟢 Android client connected via WebSocket.');

  let isAssemblyReady = false;
  let chunkCount = 0;
  const pendingAudioBuffer: Buffer[] = [];

  const apiKey = (process.env.ASSEMBLYAI_API_KEY || '').trim();
  if (!apiKey) {
    console.warn('[VoiceStream] ASSEMBLYAI_API_KEY is missing or empty. Please check your .env/.env.local.');
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ error: 'ASSEMBLYAI_KEY_NOT_CONFIGURED', message: 'ASSEMBLYAI_API_KEY not set' }));
    }
    return;
  }

  let hasTriggeredReasoning = false;
  let silenceTimer: NodeJS.Timeout | null = null;
  let latestPartialText = '';

  let clientContext: {
    current_track?: { title: string; artist: string; trackId?: string; source_player?: string };
    recent_tracks?: Array<{ title: string; artist: string; trackId?: string; source?: string; playCount?: number }>;
    recently_played_by_agent?: string[];
  } | null = null;

  const triggerReasoningForTranscript = async (finalText: string) => {
    if (hasTriggeredReasoning || !finalText.trim()) return;
    hasTriggeredReasoning = true;
    if (silenceTimer) {
      clearTimeout(silenceTimer);
      silenceTimer = null;
    }

    console.log(`\n[STT] ⚡ Finalized Transcript: "${finalText}"`);

    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'transcript',
        text: finalText,
        transcript: finalText,
        isFinal: true
      }));
    }

    try {
      console.log('[VoiceStream] ⚡ Triggering Fast Claude Pipeline...');
      const { ClaudeMusicReasoningProvider } = await import('./domain/reasoning/ClaudeMusicReasoningProvider');
      const provider = new ClaudeMusicReasoningProvider();

      const eventStore = new EventStore();
      const storedTracks = eventStore.getRecentObservedTracks(50);

      const liveCurrent = clientContext?.current_track;
      const liveRecents = clientContext?.recent_tracks || [];

      const effectiveCurrentTrack = (liveCurrent?.title && liveCurrent.title !== 'Standing By' && liveCurrent.title !== 'Active Session' && liveCurrent.title !== 'Deep Flow State')
        ? {
            title: liveCurrent.title,
            artist: liveCurrent.artist || '',
            source_player: liveCurrent.source_player || 'com.spotify.music'
          }
        : (storedTracks[0] || {
            title: 'Active Session',
            artist: 'TuneMind Observer',
            source_player: 'com.spotify.music'
          });

      const effectiveHistory = liveRecents.length > 0
        ? liveRecents.slice(0, 50).map(t => ({
            title: t.title,
            artist: t.artist,
            trackId: t.trackId,
            playCount: t.playCount,
            source_player: t.source || 'com.spotify.music'
          }))
        : storedTracks.slice(0, 50).map(t => ({
            title: t.title,
            artist: t.artist,
            trackId: t.trackId,
            playCount: t.playCount,
            source_player: t.source
          }));

      const hour = new Date().getHours();
      const circadianDaypart = hour >= 6 && hour < 12 ? 'Morning'
        : hour >= 12 && hour < 17 ? 'Midday'
        : hour >= 17 && hour < 22 ? 'Evening'
        : 'Late Night';

      const response = await provider.evaluateSatiety({
        timestamp_utc: new Date().toISOString(),
        circadian_daypart: circadianDaypart,
        current_track: {
          title: effectiveCurrentTrack.title,
          artist: effectiveCurrentTrack.artist,
          source_player: (effectiveCurrentTrack as any).source_player || (effectiveCurrentTrack as any).source || 'com.spotify.music'
        },
        user_command: finalText,
        rolling_history: effectiveHistory,
        recently_played_by_agent: clientContext?.recently_played_by_agent || [],
        current_satiety_percentage: 50
      });

      console.log('[VoiceStream] Claude reasoning completed:', response.voice_agent_tts);
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
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'command',
          action: 'THINKING_COMPLETE',
          payload: "I heard you. Let me know which vibe you'd like next."
        }));
      }
    }
  };

  // Connect to AssemblyAI Universal v3 streaming WebSocket endpoint
  const aaiUrl = 'wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&format_turns=true';
  const aaiSocket = new WebSocket(aaiUrl, {
    headers: { Authorization: apiKey }
  });

  aaiSocket.on('open', () => {
    console.log('[STT] 🟢 AssemblyAI v3 Real-Time connection opened.');
  });

  aaiSocket.on('error', (error: Error) => {
    console.error('[STT] AssemblyAI Error:', error);
    if (!hasTriggeredReasoning && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ error: 'ASSEMBLYAI_ERROR', message: error.message }));
    }
  });

  aaiSocket.on('message', async (data: RawData) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'Begin' || msg.message_type === 'SessionBegins') {
        const sessionId = msg.id || msg.session_id || 'v3-session';
        console.log(`[STT] Session started: ${sessionId}`);
        isAssemblyReady = true;

        if (pendingAudioBuffer.length > 0 && aaiSocket.readyState === WebSocket.OPEN) {
          console.log(`[STT] ⚡ Flushing ${pendingAudioBuffer.length} buffered pre-session audio chunks to AssemblyAI...`);
          while (pendingAudioBuffer.length > 0) {
            const chunk = pendingAudioBuffer.shift();
            if (chunk) {
              try {
                aaiSocket.send(chunk);
              } catch (_e) {}
            }
          }
        }
      } else if (msg.type === 'Turn' || msg.message_type === 'FinalTranscript' || msg.message_type === 'PartialTranscript') {
        const text = (msg.transcript || msg.text || '').trim();
        if (!text || hasTriggeredReasoning) return;

        const isFinal = Boolean(msg.end_of_turn || msg.message_type === 'FinalTranscript');

        if (isFinal) {
          await triggerReasoningForTranscript(text);
        } else {
          latestPartialText = text;
          process.stdout.write(`\r[STT] Partial: "${text}"`);
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
              type: 'transcript',
              text: text,
              transcript: text,
              isFinal: false
            }));
          }

          // Smart silence finalizer: if user stops speaking for 1100ms after saying a command, finalize immediately
          if (silenceTimer) clearTimeout(silenceTimer);
          silenceTimer = setTimeout(() => {
            if (!hasTriggeredReasoning && latestPartialText.trim().length >= 3) {
              triggerReasoningForTranscript(latestPartialText);
            }
          }, 1100);
        }
      } else if (msg.type === 'Error') {
        console.error('[STT] AssemblyAI Server Error:', msg);
        if (!hasTriggeredReasoning && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ error: 'ASSEMBLYAI_ERROR', message: msg.error || 'Server Error' }));
        }
      }
    } catch (e) {
      console.warn('[STT] Failed to parse message from AssemblyAI:', e);
    }
  });

  // Handle incoming messages from the Android client
  ws.on('message', (message: RawData) => {
    // If it's a string, it may be JSON client telemetry (now playing, recent tracks)
    if (typeof message === 'string' || (Buffer.isBuffer(message) && message.length > 0 && message[0] === 123)) { // 123 is '{'
      try {
        const textData = message.toString();
        const json = JSON.parse(textData);
        if (json.type === 'client_context') {
          clientContext = json;
          const recentsList = Array.isArray(json.recent_tracks) ? json.recent_tracks : [];
          console.log(`[VoiceStream] 📱 Received live client playback context: Now Playing: "${json.current_track?.title}" by "${json.current_track?.artist}", Recent tracks: ${recentsList.length}`);
          if (recentsList.length > 0) {
            console.log(`[VoiceStream] 🎵 Phone History Sample: ${recentsList.slice(0, 8).map((r: any) => `"${r.title}" by ${r.artist}`).join(' | ')}`);
            try {
              const store = new EventStore();
              store.syncClientTracks(recentsList);
            } catch (_err) {}
          }
          return;
        }
      } catch (_e) {
        // Not a JSON text command; treat as PCM audio buffer below
      }
    }

    if (Buffer.isBuffer(message)) {
      chunkCount++;
      if (chunkCount % 20 === 0) {
        console.log(`[VoiceStream] 🔵 Streaming audio chunk... (x${chunkCount})`);
      }

      if (isAssemblyReady && aaiSocket.readyState === WebSocket.OPEN) {
        try {
          aaiSocket.send(message);
        } catch (e) {
          console.error('[VoiceStream] Failed to push audio to AAI:', e);
        }
      } else {
        // Buffer audio chunks until AssemblyAI session starts so 0ms of user speech is lost!
        if (pendingAudioBuffer.length < 150) {
          pendingAudioBuffer.push(Buffer.from(message));
        }
      }
    }
  });

  ws.on('close', () => {
    console.log('[VoiceStream] 🔴 Android client disconnected.');
    isAssemblyReady = false;
    if (aaiSocket.readyState === WebSocket.OPEN) {
      try {
        aaiSocket.send(JSON.stringify({ type: 'Terminate' }));
        aaiSocket.close();
      } catch (_: unknown) {}
    }
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
