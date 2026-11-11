import http from 'http';
import { WebSocketServer, WebSocket, RawData } from 'ws';
import { AssemblyAI } from 'assemblyai';
import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import rawEventsRouter from './backend/src/api/v1/events';
import rawAnalyticsRouter from './backend/src/api/v1/analytics';
import rawReasoningRouter from './backend/src/api/v1/reasoning';
import { TelemetryReasoningPoller } from './backend/src/domain/reasoning/TelemetryReasoningPoller';

import fs from 'fs';

process.env['NODE_ENV'] = process.env['NODE_ENV'] || 'development';

// Automatically parse and populate .env / .env.local if present in workspace root or backend
const envCandidates = ['.env.local', '.env', 'backend/.env.local', 'backend/.env'];
for (const envFile of envCandidates) {
  if (fs.existsSync(envFile)) {
    try {
      const envFileContent = fs.readFileSync(envFile, 'utf-8');
      for (const rawLine of envFileContent.split('\n')) {
        const line = rawLine.trim();
        if (!line || line.startsWith('#')) continue;
        const eqIdx = line.indexOf('=');
        if (eqIdx > 0) {
          const k = line.slice(0, eqIdx).trim();
          let v = line.slice(eqIdx + 1).trim();
          if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.slice(1, -1);
          }
          if (!process.env[k]) {
            process.env[k] = v;
          }
        }
      }
      console.log(`[server.ts] Loaded environment variables from ${envFile}`);
    } catch (err) {
      console.warn(`[server.ts] Error reading ${envFile}:`, err);
    }
  }
}

// Normalize API_KEY and GEMINI_API_KEY
if (process.env.API_KEY && !process.env.GEMINI_API_KEY) {
  process.env.GEMINI_API_KEY = process.env.API_KEY;
} else if (process.env.GEMINI_API_KEY && !process.env.API_KEY) {
  process.env.API_KEY = process.env.GEMINI_API_KEY;
}

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const getRouter = (r: any) => (typeof r === 'function' ? r : r?.default || r);
const eventsRouter = getRouter(rawEventsRouter);
const analyticsRouter = getRouter(rawAnalyticsRouter);
const reasoningRouter = getRouter(rawReasoningRouter);

async function startServer() {
  const app = express();
  // AI Studio requires the dev server to run on port 3000, 0.0.0.0
  const PORT = 3000;
  const HOST = '0.0.0.0';

  app.use(express.json({ limit: '5mb' }));

  // Mount API routes
  app.use('/api/v1/events', eventsRouter);
  app.use('/api/v1/analytics', analyticsRouter);
  app.use('/api/v1/reasoning', reasoningRouter);

  app.get('/health', (_req, res) => {
    res.json({
      status: 'ok',
      service: 'TuneMind Fullstack Intelligence Platform',
      phase: 2,
      timestamp: new Date().toISOString()
    });
  });

  app.get('/api/download-android', (_req, res) => {
    try {
      execSync('python3 -c "import zipfile, os; zf = zipfile.ZipFile(\'android_update.zip\', \'w\', zipfile.ZIP_DEFLATED); [zf.write(os.path.join(r, f), os.path.relpath(os.path.join(r, f), \'android\')) for r, d, files in os.walk(\'android\') for f in files]; zf.close()"');
    } catch (e) {
      console.error('Error generating android zip:', e);
    }
    const zipPath = path.resolve(process.cwd(), 'android_update.zip');
    res.download(zipPath, 'android_update.zip');
  });


  // Vite integration
  const isDev = process.env['NODE_ENV'] !== 'production';

  if (isDev) {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        host: '0.0.0.0',
        port: PORT
      },
      appType: 'spa'
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.resolve(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.resolve(distPath, 'index.html'));
    });
  }

  // 1. Create the raw HTTP server using the Express app
  const server = http.createServer(app);

  // 2. Create the WebSocket server (detached from auto-handling)
  const wss = new WebSocketServer({ noServer: true });

  // 3. Intercept the network upgrade request
  server.on('upgrade', (request, socket, head) => {
    const pathname = request.url?.split('?')[0];
    if (pathname === '/ws/stt') {
      wss.handleUpgrade(request, socket, head, (ws: WebSocket) => {
        wss.emit('connection', ws, request);
      });
    }
  });

  // 4. Handle the active WebSocket connection with AssemblyAI Real-Time
  wss.on('connection', async (ws: WebSocket) => {
    console.log('[VoiceStream] 🟢 Android client connected via WebSocket.');

    const aai = new AssemblyAI({
      apiKey: process.env.ASSEMBLYAI_API_KEY || ''
    });

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
          const { ClaudeMusicReasoningProvider } = await import('./backend/src/domain/reasoning/ClaudeMusicReasoningProvider');
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

  // 5. Start the server
  server.listen(PORT, HOST, () => {
    console.log(`TuneMind server running on http://${HOST}:${PORT} [${process.env['NODE_ENV']}] (with WebSocket STT)`);
    // Start background telemetry reasoning poller
    try {
      TelemetryReasoningPoller.getInstance().start(30000);
    } catch (e) {
      console.warn('Could not start TelemetryReasoningPoller:', e);
    }
  });
}

startServer().catch((err) => {
  console.error('Failed to start TuneMind server:', err);
  process.exit(1);
});
