import { retryWithBackoff } from '../../utils/retryWithBackoff';

const OPENAI_CHAT_URL = 'https://api.openai.com/v1/chat/completions';
const OPENAI_TTS_URL = 'https://api.openai.com/v1/audio/speech';
const OPENAI_STT_URL = 'https://api.openai.com/v1/audio/transcriptions';
const STT_SAMPLE_RATE = 16_000;

let cachedWorkingOpenAIModel: string | null = null;

export function getOpenAIApiKey(): string {
  const key = (process.env.OPENAI_API_KEY || '').trim();
  if (!key || key.startsWith('your_') || key === 'TODO') return '';
  return key;
}

export function isOpenAIActive(): boolean {
  const explicitProvider = (process.env.AI_PROVIDER || '').trim().toLowerCase();
  if (explicitProvider === 'gemini' || explicitProvider === 'anthropic' || explicitProvider === 'claude') {
    return false;
  }
  return Boolean(getOpenAIApiKey());
}

export function getCandidateOpenAIModels(): string[] {
  const envModel = (process.env.OPENAI_MODEL || '').trim();
  return [
    ...(cachedWorkingOpenAIModel ? [cachedWorkingOpenAIModel] : []),
    ...(envModel ? [envModel] : []),
    'gpt-4o',
    'gpt-4.1',
    'gpt-4o-mini'
  ].filter((v, idx, arr) => Boolean(v) && arr.indexOf(v) === idx);
}

function convertMessagesToOpenAI(systemPrompt: string | undefined, messages: any[]): any[] {
  const out: any[] = [];
  if (systemPrompt && systemPrompt.trim()) {
    out.push({ role: 'system', content: systemPrompt });
  }

  for (const msg of messages || []) {
    const role = msg?.role === 'assistant' ? 'assistant' : 'user';
    const rawContent = msg?.content;

    if (typeof rawContent === 'string') {
      out.push({ role, content: rawContent });
      continue;
    }

    if (Array.isArray(rawContent)) {
      if (role === 'assistant') {
        const textParts: string[] = [];
        const toolCalls: any[] = [];
        for (const block of rawContent) {
          if (!block || typeof block !== 'object') continue;
          if (block.type === 'text' && typeof block.text === 'string' && block.text.trim()) {
            textParts.push(block.text);
          } else if (block.type === 'tool_use') {
            toolCalls.push({
              id: String(block.id || `call_${Date.now()}`),
              type: 'function',
              function: {
                name: String(block.name || 'tool'),
                arguments: JSON.stringify(block.input && typeof block.input === 'object' ? block.input : {})
              }
            });
          }
        }
        const assistantMsg: Record<string, any> = {
          role: 'assistant',
          content: textParts.length > 0 ? textParts.join('\n') : null
        };
        if (toolCalls.length > 0) {
          assistantMsg.tool_calls = toolCalls;
        }
        out.push(assistantMsg);
        continue;
      }

      // User turn: may contain tool_result blocks and/or text blocks
      const userTexts: string[] = [];
      for (const block of rawContent) {
        if (!block || typeof block !== 'object') continue;
        if (block.type === 'tool_result') {
          const contentStr =
            typeof block.content === 'string'
              ? block.content
              : JSON.stringify(block.content ?? {});
          out.push({
            role: 'tool',
            tool_call_id: String(block.tool_use_id || ''),
            content: contentStr
          });
        } else if (block.type === 'text' && typeof block.text === 'string' && block.text.trim()) {
          userTexts.push(block.text);
        }
      }
      if (userTexts.length > 0) {
        out.push({ role: 'user', content: userTexts.join('\n') });
      }
    }
  }

  return out;
}

function convertOpenAIResponseToInternalShape(openAIJson: any, modelId: string): any {
  const choice = openAIJson?.choices?.[0];
  const message = choice?.message || {};
  const contentBlocks: any[] = [];
  let hasToolUse = false;

  if (typeof message.content === 'string' && message.content.trim()) {
    contentBlocks.push({ type: 'text', text: message.content });
  }

  const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  for (const tc of toolCalls) {
    if (!tc || tc.type !== 'function' || !tc.function) continue;
    hasToolUse = true;
    let parsedArgs: Record<string, any> = {};
    try {
      parsedArgs = tc.function.arguments ? JSON.parse(tc.function.arguments) : {};
    } catch {
      parsedArgs = {};
    }
    contentBlocks.push({
      type: 'tool_use',
      id: String(tc.id || `openai_call_${Date.now()}`),
      name: String(tc.function.name || ''),
      input: parsedArgs && typeof parsedArgs === 'object' ? parsedArgs : {}
    });
  }

  if (contentBlocks.length === 0) {
    contentBlocks.push({ type: 'text', text: '' });
  }

  return {
    id: String(openAIJson?.id || `openai_${Date.now()}`),
    type: 'message',
    role: 'assistant',
    model: String(openAIJson?.model || modelId),
    stop_reason: hasToolUse ? 'tool_use' : 'end_turn',
    content: contentBlocks
  };
}

export async function callOpenAISingleShot(
  systemPrompt: string | undefined,
  userContent: string,
  maxTokens: number = 1400
): Promise<any> {
  return callOpenAIMessagesWithTools(
    systemPrompt,
    [{ role: 'user', content: userContent }],
    undefined,
    maxTokens
  );
}

export async function callOpenAIMessagesWithTools(
  systemPrompt: string | undefined,
  messages: any[],
  tools?: any[],
  maxTokens: number = 1400
): Promise<any> {
  const apiKey = getOpenAIApiKey();
  if (!apiKey) {
    throw new Error('OPENAI_API_KEY is not configured');
  }

  const candidateModels = getCandidateOpenAIModels();
  const openAIMessages = convertMessagesToOpenAI(systemPrompt, messages);
  const openAITools = Array.isArray(tools) && tools.length > 0
    ? tools.map((t) => ({
        type: 'function',
        function: {
          name: String(t.name || ''),
          description: String(t.description || ''),
          parameters: t.input_schema || { type: 'object', properties: {} }
        }
      }))
    : undefined;

  let lastErr: any = null;
  for (const modelId of candidateModels) {
    try {
      const bodyPayload: Record<string, any> = {
        model: modelId,
        messages: openAIMessages,
        max_tokens: maxTokens,
        temperature: 0.45
      };

      if (openAITools && openAITools.length > 0) {
        bodyPayload.tools = openAITools;
        bodyPayload.tool_choice = 'auto';
        bodyPayload.parallel_tool_calls = true;
      }

      const json: any = await retryWithBackoff(
        async () => {
          const resp = await fetch(OPENAI_CHAT_URL, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${apiKey}`
            },
            body: JSON.stringify(bodyPayload)
          });
          if (!resp.ok) {
            const errText = await resp.text().catch(() => '');
            if (resp.status === 404 || resp.status === 400 || resp.status === 401 || resp.status === 403) {
              return { __nonRetryableError: true, status: resp.status, errText };
            }
            const err: any = new Error(`OpenAI HTTP ${resp.status}: ${errText.slice(0, 300)}`);
            err.status = resp.status;
            throw err;
          }
          return resp.json();
        },
        { maxAttempts: 3, baseDelayMs: 350, maxDelayMs: 4_000 }
      );

      if (json?.__nonRetryableError) {
        const err: any = new Error(`OpenAI HTTP ${json.status}: ${String(json.errText || '').slice(0, 300)}`);
        err.status = json.status;
        throw err;
      }

      cachedWorkingOpenAIModel = modelId;
      return convertOpenAIResponseToInternalShape(json, modelId);
    } catch (err: any) {
      lastErr = err;
      if (cachedWorkingOpenAIModel === modelId) {
        cachedWorkingOpenAIModel = null;
      }
      const status = Number(err?.status || 0);
      if (status === 401 || status === 403) {
        throw err;
      }
      continue;
    }
  }

  throw lastErr || new Error('All OpenAI models failed');
}

/**
 * Generates warm, human-sounding speech MP3 (base64) via OpenAI gpt-4o-mini-tts.
 * Returns null gracefully on timeout/error so Android falls back to native TTS.
 */
export async function generateOpenAISpeechBase64(text: string): Promise<string | null> {
  const apiKey = getOpenAIApiKey();
  const cleanText = String(text || '').trim();
  if (!apiKey || !cleanText) return null;

  const model = (process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts').trim();
  const voice = (process.env.OPENAI_TTS_VOICE || 'coral').trim();

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 7_000);
    const resp = await fetch(OPENAI_TTS_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model,
        voice,
        input: cleanText.slice(0, 900),
        response_format: 'mp3',
        instructions: 'Speak like a warm, perceptive, natural human music friend — conversational, never robotic.'
      }),
      signal: controller.signal
    });
    clearTimeout(timer);

    if (!resp.ok) {
      return null;
    }
    const arrBuf = await resp.arrayBuffer();
    if (!arrBuf || arrBuf.byteLength === 0) return null;
    return Buffer.from(arrBuf).toString('base64');
  } catch {
    return null;
  }
}

export function computePcm16Rms(pcm: Buffer): number {
  const sampleCount = Math.floor(pcm.length / 2);
  if (sampleCount === 0) return 0;
  let sumSq = 0;
  for (let i = 0; i < sampleCount * 2; i += 2) {
    const sample = pcm.readInt16LE(i);
    sumSq += sample * sample;
  }
  return Math.sqrt(sumSq / sampleCount);
}

function pcm16ToWavBuffer(pcm: Buffer, sampleRate: number = STT_SAMPLE_RATE): Buffer {
  const header = Buffer.alloc(44);
  const byteRate = sampleRate * 2;
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

/**
 * Transcribes 16kHz 16-bit mono PCM audio via OpenAI gpt-4o-transcribe
 * (with dynamic artist/music context hints and gpt-4o-mini-transcribe fallback).
 */
export async function transcribePcm16WithOpenAI(
  pcm: Buffer,
  artistHints: string[] = []
): Promise<string> {
  const apiKey = getOpenAIApiKey();
  if (!apiKey || pcm.length < 3200) return '';

  const wav = pcm16ToWavBuffer(pcm, STT_SAMPLE_RATE);
  const models = [(process.env.OPENAI_STT_MODEL || 'gpt-4o-transcribe').trim(), 'gpt-4o-mini-transcribe']
    .filter((v, i, a) => Boolean(v) && a.indexOf(v) === i);

  const hintsText = artistHints
    .map((a) => String(a || '').trim())
    .filter(Boolean)
    .slice(0, 20)
    .join(', ');
  const prompt = hintsText
    ? `TuneMind voice music companion. Transcribe global & East African artist names, song titles, albums, and genres accurately. Listener artists: ${hintsText}.`
    : 'TuneMind voice music companion. Transcribe global & East African artist names, song titles, albums, and genres accurately.';

  for (const model of models) {
    try {
      const form = new FormData();
      form.append('file', new Blob([new Uint8Array(wav)], { type: 'audio/wav' }), 'utterance.wav');
      form.append('model', model);
      form.append('prompt', prompt);

      const resp = await fetch(OPENAI_STT_URL, {
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
