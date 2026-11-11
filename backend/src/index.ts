/**
 * TuneMind Backend — Entry point
 *
 * Phase 1: Express skeleton with a single route stub.
 * Phase 2: Firebase Admin SDK initialised, full event ingestion implemented.
 * Phase 3: Raw HTTP server with WebSocket upgrade handler for AssemblyAI voice stream.
 */

import './bootstrap';

import http from 'http';
import { WebSocketServer, WebSocket, RawData } from 'ws';
import { AssemblyAI } from 'assemblyai';
import express from 'express';
import { requestLogger } from './middleware/requestLogger';
import eventsRouter from './api/v1/events';
import analyticsRouter from './api/v1/analytics';
import reasoningRouter from './api/v1/reasoning';

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

  const apiKey = (process.env.ASSEMBLYAI_API_KEY || '').trim();
  if (!apiKey) {
    console.warn('[VoiceStream] ASSEMBLYAI_API_KEY is missing or empty. Please check your .env/.env.local.');
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ error: 'ASSEMBLYAI_KEY_NOT_CONFIGURED', message: 'ASSEMBLYAI_API_KEY not set' }));
    }
    return;
  }

  // Connect to AssemblyAI Universal v3 streaming WebSocket endpoint
  const aaiSocket = new WebSocket('wss://streaming.assemblyai.com/v3/ws?sample_rate=16000', {
    headers: { Authorization: apiKey }
  });

  aaiSocket.on('open', () => {
    console.log('[STT] 🟢 AssemblyAI v3 Real-Time connection opened.');
  });

  aaiSocket.on('error', (error: Error) => {
    console.error('[STT] AssemblyAI Error:', error);
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ error: 'ASSEMBLYAI_ERROR', message: error.message }));
    }
  });

  aaiSocket.on('message', async (data: RawData) => {
    try {
      const msg = JSON.parse(data.toString());

      // We must wait for the session to officially begin before sending audio
      if (msg.type === 'Begin' || msg.message_type === 'SessionBegins') {
        const sessionId = msg.id || msg.session_id || 'v3-session';
        console.log(`[STT] Session started: ${sessionId}`);
        isAssemblyReady = true;
      } else if (msg.type === 'Turn' || msg.message_type === 'FinalTranscript' || msg.message_type === 'PartialTranscript') {
        const text = (msg.transcript || msg.text || '').trim();
        if (!text) return;

        const isFinal = Boolean(msg.end_of_turn || msg.message_type === 'FinalTranscript');

        if (isFinal) {
          console.log(`\n[STT] Final Transcript: "${text}"`);

          // 1. Send the text back to Android so the UI updates
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
              type: 'transcript',
              text: text,
              transcript: text,
              isFinal: true
            }));
          }

          // 2. Trigger the Claude Reasoning Pipeline
          try {
            console.log('[VoiceStream] Triggering Claude Pipeline...');
            const { ClaudeMusicReasoningProvider } = await import('./domain/reasoning/ClaudeMusicReasoningProvider');
            const provider = new ClaudeMusicReasoningProvider();
            const response = await provider.evaluateSatiety({
              timestamp_utc: new Date().toISOString(),
              circadian_daypart: 'Midday',
              current_track: {
                title: 'Active Session',
                artist: 'TuneMind Observer',
                source_player: 'com.spotify.music'
              },
              user_command: text,
              rolling_history: [],
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
                payload: "I heard you. Connecting to reasoning engine."
              }));
            }
          }
        } else {
          // Log partial transcripts to see it working in real-time
          process.stdout.write(`\r[STT] Partial: "${text}"`);
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
              type: 'transcript',
              text: text,
              transcript: text,
              isFinal: false
            }));
          }
        }
      } else if (msg.type === 'Error') {
        console.error('[STT] AssemblyAI Server Error:', msg);
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ error: 'ASSEMBLYAI_ERROR', message: msg.error || 'Server Error' }));
        }
      }
    } catch (e) {
      console.warn('[STT] Failed to parse message from AssemblyAI:', e);
    }
  });

  // Safety valve: Only send if AssemblyAI session is actually ready
  ws.on('message', (message: RawData) => {
    if (Buffer.isBuffer(message)) {
      chunkCount++;
      // Log every 20th chunk just to prove audio is flowing without spamming the terminal
      if (chunkCount % 20 === 0) {
        console.log(`[VoiceStream] 🔵 Streaming audio chunk... (x${chunkCount})`);
      }

      if (isAssemblyReady && aaiSocket.readyState === WebSocket.OPEN) {
        try {
          aaiSocket.send(message);
        } catch (e) {
          console.error('[VoiceStream] Failed to push audio to AAI:', e);
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
});

export default app;
