import { WebSocketServer, WebSocket as NodeWebSocket } from 'ws';
import { Server } from 'http';

export function setupVoiceStreamSocket(server: Server) {
  const wss = new WebSocketServer({ server, path: '/ws/stt' });

  wss.on('connection', (ws: NodeWebSocket) => {
    console.log('[VoiceStream] Android client connected to local WebSocket.');

    const apiKey = process.env.ASSEMBLYAI_API_KEY;
    if (!apiKey) {
      console.warn('[VoiceStream] ASSEMBLYAI_API_KEY is not configured in environment.');
    }

    // Connect to AssemblyAI Real-Time endpoint (16kHz sample rate matching Android)
    const aaiSocket = new NodeWebSocket('wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000', {
      headers: { Authorization: apiKey || '' }
    });

    aaiSocket.on('open', () => {
      console.log('[VoiceStream] AssemblyAI connection opened.');
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
    });

    // Receive raw PCM audio buffer from Android, encode to Base64, and send to AssemblyAI
    ws.on('message', (message: any) => {
      if (aaiSocket.readyState === NodeWebSocket.OPEN) {
        const buffer = Buffer.isBuffer(message) ? message : Buffer.from(message);
        const audioData = buffer.toString('base64');
        aaiSocket.send(JSON.stringify({ audio_data: audioData }));
      }
    });

    ws.on('close', () => {
      console.log('[VoiceStream] Android client disconnected.');
      if (aaiSocket.readyState === NodeWebSocket.OPEN) {
        aaiSocket.send(JSON.stringify({ terminate_session: true }));
        aaiSocket.close();
      }
    });
  });
}
