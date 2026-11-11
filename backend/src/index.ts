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

// ─── 4. Handle the active WebSocket connection with AssemblyAI Real-Time ────
wss.on('connection', async (ws: WebSocket) => {
  console.log('[VoiceStream] 🟢 Android client connected via WebSocket.');

  const aai = new AssemblyAI({
    apiKey: process.env.ASSEMBLYAI_API_KEY || ''
  });

  // Initialize AssemblyAI Real-Time Service (16kHz is standard for Android AudioRecord)
  const rt = aai.realtime.createService({ sampleRate: 16000 });

  rt.on('open', () => console.log('[STT] AssemblyAI connection opened.'));

  rt.on('error', (error) => {
    console.error('[STT] AssemblyAI Error:', error);
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify({ error: 'ASSEMBLYAI_ERROR', message: (error as Error)?.message || 'STT Error' }));
    }
  });

  rt.on('transcript', async (transcript) => {
    if (!transcript.text) return;

    if (transcript.message_type === 'FinalTranscript') {
      console.log(`[STT] Final Transcript: "${transcript.text}"`);

      // 1. Send the text back to Android so the UI updates
      ws.send(JSON.stringify({
        type: 'transcript',
        text: transcript.text,
        transcript: transcript.text,
        isFinal: true
      }));

      // 2. Trigger the Claude Reasoning Pipeline internally
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
          user_command: transcript.text,
          rolling_history: [],
          current_satiety_percentage: 50
        });

        console.log('[VoiceStream] Claude reasoning completed:', response.voice_agent_tts);
        ws.send(JSON.stringify({
          type: 'command',
          action: 'THINKING_COMPLETE',
          payload: response.voice_agent_tts,
          data: response
        }));
      } catch (e) {
        console.error('[VoiceStream] Error triggering reasoning:', e);
        ws.send(JSON.stringify({
          type: 'command',
          action: 'THINKING_COMPLETE',
          payload: "I heard you. Connecting to reasoning engine."
        }));
      }
    } else if (transcript.message_type === 'PartialTranscript') {
      ws.send(JSON.stringify({
        type: 'transcript',
        text: transcript.text,
        transcript: transcript.text,
        isFinal: false
      }));
    }
  });

  try {
    await rt.connect();
  } catch (e) {
    console.error('[STT] Failed to connect to AssemblyAI:', e);
  }

  ws.on('message', (message: RawData) => {
    if (Buffer.isBuffer(message)) {
      // Pipe the raw Android audio chunk directly into AssemblyAI
      const arrayBuffer = message.buffer.slice(message.byteOffset, message.byteOffset + message.byteLength) as ArrayBuffer;
      rt.sendAudio(arrayBuffer);
    }
  });

  ws.on('close', () => {
    console.log('[VoiceStream] 🔴 Android client disconnected.');
    try {
      rt.close();
    } catch (_: unknown) {}
  });
});

// ─── 5. Start the server ─────────────────────────────────────────────────────
server.listen(PORT, HOST, () => {
  console.log(`TuneMind backend listening on ${HOST}:${PORT} (Phase 2 — with WebSocket STT)`);
});

export default app;
