import { WebSocketServer, WebSocket as NodeWebSocket } from 'ws';
import { Server, IncomingMessage } from 'http';
import { verifySupabaseJwt } from '../../middleware/authMiddleware';
import {
  computePcm16Rms,
  getOpenAIApiKey,
  transcribePcm16WithOpenAI
} from '../../domain/reasoning/OpenAIAdapter';

const MAX_CONCURRENT_VOICE_SOCKETS = 250;
const VOICE_IDLE_TIMEOUT_MS = 90_000;
const SAMPLE_RATE = 16_000;
const SPEECH_RMS_THRESHOLD = 120;
const SILENCE_FINALIZE_MS = 1350;
const MIN_AUDIO_BYTES = SAMPLE_RATE * 2 * 0.30; // ~300ms minimum utterance
const MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 25;   // 25s max single utterance

export function setupVoiceStreamSocket(server: Server) {
  const wss = new WebSocketServer({ server, path: '/ws/stt' });

  wss.on('connection', async (ws: NodeWebSocket, req: IncomingMessage) => {
    if (wss.clients.size > MAX_CONCURRENT_VOICE_SOCKETS) {
      ws.close(1013, 'Voice server busy — please try again shortly');
      return;
    }

    if (process.env.NODE_ENV === 'production') {
      const authHeader = typeof req.headers.authorization === 'string' ? req.headers.authorization.trim() : '';
      const headerToken = authHeader.toLowerCase().startsWith('bearer ') ? authHeader.slice(7).trim() : '';
      const urlObj = new URL(req.url || '/ws/stt', 'http://localhost');
      const queryToken = (urlObj.searchParams.get('token') || '').trim();
      const token = headerToken || queryToken;
      if (!token) {
        ws.close(1008, 'Authentication required');
        return;
      }
      const { user } = await verifySupabaseJwt(token);
      if (!user) {
        ws.close(1008, 'Invalid or expired authentication token');
        return;
      }
    }

    const openAiKey = getOpenAIApiKey();
    if (!openAiKey) {
      console.warn('[VoiceStream] OPENAI_API_KEY is not configured.');
      if (ws.readyState === NodeWebSocket.OPEN) {
        ws.send(JSON.stringify({ error: 'STT_KEY_NOT_CONFIGURED', message: 'Use native STT fallback' }));
      }
      ws.close(1011, 'OPENAI_API_KEY not configured');
      return;
    }

    console.log('[VoiceStream] Android client connected -> OpenAI gpt-4o-transcribe pipeline.');
    let pcmChunks: Buffer[] = [];
    let totalBytes = 0;
    let hasSpoken = false;
    let isTranscribing = false;
    let silenceTimer: ReturnType<typeof setTimeout> | null = null;
    let idleTimer: ReturnType<typeof setTimeout> | null = null;

    const resetIdleTimer = () => {
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => {
        ws.close(1000, 'Idle voice stream closed');
      }, VOICE_IDLE_TIMEOUT_MS);
    };
    resetIdleTimer();

    const flushAndTranscribe = async () => {
      if (silenceTimer) {
        clearTimeout(silenceTimer);
        silenceTimer = null;
      }
      if (isTranscribing || totalBytes < MIN_AUDIO_BYTES || !hasSpoken) {
        pcmChunks = [];
        totalBytes = 0;
        hasSpoken = false;
        return;
      }
      isTranscribing = true;
      const audioBuffer = Buffer.concat(pcmChunks);
      pcmChunks = [];
      totalBytes = 0;
      hasSpoken = false;

      try {
        const transcript = await transcribePcm16WithOpenAI(audioBuffer);
        if (transcript && ws.readyState === NodeWebSocket.OPEN) {
          ws.send(JSON.stringify({ transcript, isFinal: true }));
          console.log(`[VoiceStream] OpenAI Final Transcript: ${transcript}`);
        }
      } finally {
        isTranscribing = false;
      }
    };

    ws.on('message', (message: any) => {
      resetIdleTimer();
      if (typeof message === 'string') {
        try {
          const ctrl = JSON.parse(message);
          if (ctrl?.eof || ctrl?.terminate_session || ctrl?.type === 'finalize_utterance') {
            void flushAndTranscribe();
          }
        } catch {
          // ignore non-JSON text
        }
        return;
      }

      const buffer = Buffer.isBuffer(message) ? message : Buffer.from(message);
      if (buffer.length === 0) return;

      pcmChunks.push(buffer);
      totalBytes += buffer.length;

      const rms = computePcm16Rms(buffer);
      if (rms >= SPEECH_RMS_THRESHOLD) {
        hasSpoken = true;
        if (silenceTimer) {
          clearTimeout(silenceTimer);
          silenceTimer = null;
        }
      } else if (hasSpoken && !silenceTimer) {
        silenceTimer = setTimeout(() => {
          void flushAndTranscribe();
        }, SILENCE_FINALIZE_MS);
      }

      if (totalBytes >= MAX_AUDIO_BYTES) {
        void flushAndTranscribe();
      }
    });

    ws.on('close', () => {
      if (idleTimer) clearTimeout(idleTimer);
      if (silenceTimer) clearTimeout(silenceTimer);
      console.log('[VoiceStream] Android client disconnected.');
    });
  });
}
