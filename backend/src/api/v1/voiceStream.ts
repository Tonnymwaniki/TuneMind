import { WebSocketServer, WebSocket as NodeWebSocket } from 'ws';
import { Server, IncomingMessage } from 'http';
import { verifySupabaseJwt } from '../../middleware/authMiddleware';
import { getOpenAIApiKey } from '../../domain/reasoning/OpenAIAdapter';

const MAX_CONCURRENT_VOICE_SOCKETS = 250;
const VOICE_IDLE_TIMEOUT_MS = 90_000;
const SAMPLE_RATE = 16_000;
const SPEECH_RMS_THRESHOLD = 260;
const SILENCE_FINALIZE_MS = 950;
const MIN_AUDIO_BYTES = SAMPLE_RATE * 2 * 0.35; // ~350ms minimum utterance
const MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 20;   // 20s max single utterance

function pcm16ToWavBuffer(pcm: Buffer, sampleRate: number = SAMPLE_RATE): Buffer {
  const header = Buffer.alloc(44);
  const byteRate = sampleRate * 2;
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16); // PCM chunk size
  header.writeUInt16LE(1, 20);  // PCM format
  header.writeUInt16LE(1, 22);  // Mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(2, 32);  // Block align
  header.writeUInt16LE(16, 34); // Bits per sample
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

function computePcm16Rms(pcm: Buffer): number {
  const sampleCount = Math.floor(pcm.length / 2);
  if (sampleCount === 0) return 0;
  let sumSq = 0;
  for (let i = 0; i < sampleCount * 2; i += 2) {
    const sample = pcm.readInt16LE(i);
    sumSq += sample * sample;
  }
  return Math.sqrt(sumSq / sampleCount);
}

async function transcribeWithOpenAI(pcm: Buffer, apiKey: string): Promise<string> {
  const wav = pcm16ToWavBuffer(pcm, SAMPLE_RATE);
  const models = [(process.env.OPENAI_STT_MODEL || 'gpt-4o-transcribe').trim(), 'gpt-4o-mini-transcribe']
    .filter((v, i, a) => Boolean(v) && a.indexOf(v) === i);

  for (const model of models) {
    try {
      const form = new FormData();
      form.append('file', new Blob([new Uint8Array(wav)], { type: 'audio/wav' }), 'utterance.wav');
      form.append('model', model);
      form.append(
        'prompt',
        'TuneMind voice music companion. Transcribe global & East African artist names, song titles, albums, and genres accurately.'
      );

      const resp = await fetch('https://api.openai.com/v1/audio/transcriptions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}` },
        body: form
      });

      if (!resp.ok) {
        const errTxt = await resp.text().catch(() => '');
        console.warn(`[VoiceStream] OpenAI STT (${model}) HTTP ${resp.status}: ${errTxt.slice(0, 200)}`);
        continue;
      }
      const json: any = await resp.json();
      const text = String(json?.text || '').trim();
      if (text) return text;
    } catch (err: any) {
      console.warn(`[VoiceStream] OpenAI STT (${model}) error:`, err?.message || err);
    }
  }
  return '';
}

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
    if (openAiKey) {
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
          const transcript = await transcribeWithOpenAI(audioBuffer, openAiKey);
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
        // Allow optional JSON control frame from client
        if (typeof message === 'string') {
          try {
            const ctrl = JSON.parse(message);
            if (ctrl?.eof || ctrl?.terminate_session) {
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
      return;
    }

    // Legacy AssemblyAI fallback when OPENAI_API_KEY is not configured
    console.log('[VoiceStream] Android client connected to AssemblyAI fallback WebSocket.');
    const apiKey = process.env.ASSEMBLYAI_API_KEY;
    if (!apiKey) {
      console.warn('[VoiceStream] Neither OPENAI_API_KEY nor ASSEMBLYAI_API_KEY is configured.');
      if (ws.readyState === NodeWebSocket.OPEN) {
        ws.send(JSON.stringify({ error: 'STT_KEY_NOT_CONFIGURED', message: 'Use native STT fallback' }));
      }
    }

    const aaiSocket = new NodeWebSocket('wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000', {
      headers: { Authorization: apiKey || '' }
    });

    const pendingAudioFrames: string[] = [];

    aaiSocket.on('open', () => {
      try {
        aaiSocket.send(JSON.stringify({ end_utterance_silence_threshold: 1150 }));
      } catch {
        // ignore
      }
      while (pendingAudioFrames.length > 0 && aaiSocket.readyState === NodeWebSocket.OPEN) {
        const b64 = pendingAudioFrames.shift();
        if (b64) {
          aaiSocket.send(JSON.stringify({ audio_data: b64 }));
        }
      }
    });

    aaiSocket.on('message', (data: any) => {
      try {
        const rawString = typeof data === 'string' ? data : Buffer.isBuffer(data) ? data.toString('utf-8') : data.toString();
        const response = JSON.parse(rawString);
        if (response.error) return;
        if (response.message_type === 'FinalTranscript' && response.text?.trim()) {
          ws.send(JSON.stringify({ transcript: response.text, isFinal: true }));
        } else if (response.message_type === 'PartialTranscript' && response.text?.trim()) {
          ws.send(JSON.stringify({ transcript: response.text, isFinal: false }));
        }
      } catch (err) {
        console.error('[VoiceStream] Error parsing AssemblyAI message:', err);
      }
    });

    aaiSocket.on('error', (err) => {
      if (ws.readyState === NodeWebSocket.OPEN) {
        ws.send(JSON.stringify({ error: 'AAI_SOCKET_ERROR', message: err?.message || 'Socket error' }));
      }
    });

    let idleTimer: ReturnType<typeof setTimeout> | null = null;
    const resetIdleTimer = () => {
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => ws.close(1000, 'Idle voice stream closed'), VOICE_IDLE_TIMEOUT_MS);
    };
    resetIdleTimer();

    ws.on('message', (message: any) => {
      resetIdleTimer();
      const buffer = Buffer.isBuffer(message) ? message : Buffer.from(message);
      const audioData = buffer.toString('base64');
      if (aaiSocket.readyState === NodeWebSocket.OPEN) {
        aaiSocket.send(JSON.stringify({ audio_data: audioData }));
      } else if (aaiSocket.readyState === NodeWebSocket.CONNECTING && pendingAudioFrames.length < 150) {
        pendingAudioFrames.push(audioData);
      }
    });

    ws.on('close', () => {
      if (idleTimer) clearTimeout(idleTimer);
      if (aaiSocket.readyState === NodeWebSocket.OPEN) {
        aaiSocket.send(JSON.stringify({ terminate_session: true }));
        aaiSocket.close();
      }
    });
  });
}
