import { WebSocketServer, WebSocket as NodeWebSocket } from 'ws';
import { Server, IncomingMessage } from 'http';
import { verifySupabaseJwt } from '../../middleware/authMiddleware';

const MAX_CONCURRENT_VOICE_SOCKETS = 250;
const VOICE_IDLE_TIMEOUT_MS = 90_000;

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
    console.log('[VoiceStream] Android client connected to local WebSocket.');

    const apiKey = process.env.ASSEMBLYAI_API_KEY;
    if (!apiKey) {
      console.warn('[VoiceStream] ASSEMBLYAI_API_KEY is not configured in environment. Notifying client to use device STT fallback.');
      if (ws.readyState === NodeWebSocket.OPEN) {
        ws.send(JSON.stringify({ error: 'ASSEMBLYAI_KEY_NOT_CONFIGURED', message: 'Use native STT fallback' }));
      }
    }

    // Connect to AssemblyAI Real-Time endpoint (16kHz sample rate matching Android)
    const aaiSocket = new NodeWebSocket('wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000', {
      headers: { Authorization: apiKey || '' }
    });

    const pendingAudioFrames: string[] = [];

    aaiSocket.on('open', () => {
      console.log('[VoiceStream] AssemblyAI connection opened.');
      // Allow natural breathing pauses (1150ms) so multi-word artist/song requests aren't cut off early
      try {
        aaiSocket.send(JSON.stringify({ end_utterance_silence_threshold: 1150 }));
      } catch {
        // ignore if unsupported
      }
      // Flush any audio buffered while AssemblyAI was handshaking so first syllables are never lost
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

        if (response.error) {
          console.error('[VoiceStream] AssemblyAI Error:', response.error);
          return;
        }

        if (response.message_type === 'FinalTranscript') {
          const transcript = response.text;
          if (transcript && transcript.trim().length > 0) {
            // Send final transcript back to Android
            ws.send(JSON.stringify({ transcript, isFinal: true }));
            console.log(`[VoiceStream] Final Transcript: ${transcript}`);
          }
        } else if (response.message_type === 'PartialTranscript') {
          const transcript = response.text;
          if (transcript && transcript.trim().length > 0) {
            ws.send(JSON.stringify({ transcript, isFinal: false }));
          }
        }
      } catch (err) {
        console.error('[VoiceStream] Error parsing AssemblyAI message:', err);
      }
    });

    aaiSocket.on('error', (err) => {
      console.error('[VoiceStream] AssemblyAI Socket Error:', err);
      if (ws.readyState === NodeWebSocket.OPEN) {
        ws.send(JSON.stringify({ error: 'AAI_SOCKET_ERROR', message: err?.message || 'Socket error' }));
      }
    });

    let idleTimer: ReturnType<typeof setTimeout> | null = null;
    const resetIdleTimer = () => {
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => {
        ws.close(1000, 'Idle voice stream closed');
      }, VOICE_IDLE_TIMEOUT_MS);
    };
    resetIdleTimer();

    // Receive raw PCM audio buffer from Android, encode to Base64, and send to AssemblyAI
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
      console.log('[VoiceStream] Android client disconnected.');
      if (aaiSocket.readyState === NodeWebSocket.OPEN) {
        aaiSocket.send(JSON.stringify({ terminate_session: true }));
        aaiSocket.close();
      }
    });
  });
}
