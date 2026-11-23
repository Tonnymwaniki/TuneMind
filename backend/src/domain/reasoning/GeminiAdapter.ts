import { retryWithBackoff } from '../../utils/retryWithBackoff';

const GEMINI_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/models';

export function getGeminiApiKey(): string {
  const key = (process.env.GEMINI_API_KEY || '').trim();
  if (!key || key.startsWith('your_')) return '';
  return key;
}

export function isGeminiActive(): boolean {
  const explicitProvider = (process.env.AI_PROVIDER || '').trim().toLowerCase();
  if (explicitProvider === 'anthropic' || explicitProvider === 'claude') {
    return false;
  }
  return Boolean(getGeminiApiKey());
}

let cachedWorkingGeminiModel: string | null = null;

export function getCandidateGeminiModels(): string[] {
  const envModel = (process.env.GEMINI_MODEL || '').trim();
  return [
    ...(cachedWorkingGeminiModel ? [cachedWorkingGeminiModel] : []),
    ...(envModel ? [envModel] : []),
    'gemini-flash-latest',
    'gemini-flash-lite-latest',
    'gemini-3.1-flash-lite',
    'gemini-3-flash-preview',
    'gemini-2.5-flash-lite',
    'gemini-2.5-flash'
  ].filter((v, idx, arr) => Boolean(v) && arr.indexOf(v) === idx);
}

function sanitizeSchemaForGemini(schema: any): any {
  if (!schema || typeof schema !== 'object') return schema;
  if (Array.isArray(schema)) return schema.map(sanitizeSchemaForGemini);
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(schema)) {
    if (k === '$schema' || k === 'additionalProperties') continue;
    out[k] = sanitizeSchemaForGemini(v);
  }
  return out;
}

function convertAnthropicMessagesToGemini(messages: any[]): any[] {
  const contents: any[] = [];
  const toolUseNameById = new Map<string, string>();

  for (const msg of messages || []) {
    const role = msg?.role === 'assistant' ? 'model' : 'user';
    const rawContent = msg?.content;

    if (typeof rawContent === 'string') {
      contents.push({
        role,
        parts: [{ text: rawContent }]
      });
      continue;
    }

    if (Array.isArray(rawContent)) {
      // Register tool_use IDs -> tool names before replaying raw Gemini parts so subsequent tool_result turns resolve exact functionResponse.name
      for (const block of rawContent) {
        if (block && typeof block === 'object' && block.type === 'tool_use' && block.id) {
          toolUseNameById.set(String(block.id), String(block.name || 'tool'));
        }
      }
      // Preserve exact raw Gemini model parts (including thoughtSignature) when replaying tool_use turns
      const rawGeminiHolder = rawContent.find((b: any) => b && Array.isArray(b._rawGeminiParts));
      if (role === 'model' && rawGeminiHolder) {
        contents.push({ role: 'model', parts: rawGeminiHolder._rawGeminiParts });
        continue;
      }
      const parts: any[] = [];
      for (const block of rawContent) {
        if (!block || typeof block !== 'object') continue;
        if (block.type === 'text' && typeof block.text === 'string') {
          parts.push({ text: block.text });
        } else if (block.type === 'tool_use') {
          const toolName = String(block.name || 'tool');
          if (block.id) {
            toolUseNameById.set(String(block.id), toolName);
          }
          parts.push({
            functionCall: {
              name: toolName,
              args: block.input && typeof block.input === 'object' ? block.input : {}
            }
          });
        } else if (block.type === 'tool_result') {
          const toolName = toolUseNameById.get(String(block.tool_use_id || '')) || String(block.name || 'tool');
          let resultPayload: any =
            typeof block.content === 'string'
              ? (() => {
                  try {
                    return JSON.parse(block.content);
                  } catch {
                    return { result: block.content };
                  }
                })()
              : block.content;

          if (Array.isArray(resultPayload)) {
            resultPayload = { items: resultPayload };
          } else if (!resultPayload || typeof resultPayload !== 'object') {
            resultPayload = { result: resultPayload ?? '' };
          }

          parts.push({
            functionResponse: {
              name: toolName,
              response: resultPayload
            }
          });
        }
      }
      if (parts.length > 0) {
        contents.push({ role, parts });
      }
    }
  }

  return contents;
}

function convertGeminiResponseToAnthropicShape(geminiJson: any, modelId: string): any {
  const candidate = geminiJson?.candidates?.[0];
  const parts = Array.isArray(candidate?.content?.parts) ? candidate.content.parts : [];
  const contentBlocks: any[] = [];
  let hasToolUse = false;
  let attachedRawParts = false;

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i];
    if (part?.text && typeof part.text === 'string' && !part?.thought) {
      contentBlocks.push({ type: 'text', text: part.text });
    }
    if (part?.functionCall && typeof part.functionCall === 'object') {
      hasToolUse = true;
      const attachRaw = !attachedRawParts;
      attachedRawParts = true;
      contentBlocks.push({
        type: 'tool_use',
        id: `gemini_call_${Date.now()}_${i}`,
        name: String(part.functionCall.name || ''),
        input: part.functionCall.args && typeof part.functionCall.args === 'object' ? part.functionCall.args : {},
        ...(attachRaw ? { _rawGeminiParts: parts } : {})
      });
    }
  }

  if (contentBlocks.length === 0) {
    contentBlocks.push({ type: 'text', text: '' });
  }

  return {
    id: `gemini_${Date.now()}`,
    type: 'message',
    role: 'assistant',
    model: modelId,
    stop_reason: hasToolUse ? 'tool_use' : 'end_turn',
    content: contentBlocks
  };
}

export async function callGeminiSingleShot(
  systemPrompt: string | undefined,
  userContent: string,
  maxTokens: number = 1400
): Promise<any> {
  return callGeminiMessagesWithTools(
    systemPrompt,
    [{ role: 'user', content: userContent }],
    undefined,
    maxTokens
  );
}

export async function callGeminiMessagesWithTools(
  systemPrompt: string | undefined,
  messages: any[],
  tools?: any[],
  maxTokens: number = 1400
): Promise<any> {
  const apiKey = getGeminiApiKey();
  if (!apiKey) {
    throw new Error('GEMINI_API_KEY is not configured');
  }

  const candidateModels = getCandidateGeminiModels();
  const contents = convertAnthropicMessagesToGemini(messages);
  const functionDeclarations = Array.isArray(tools) && tools.length > 0
    ? tools.map((t) => ({
        name: String(t.name || ''),
        description: String(t.description || ''),
        parameters: sanitizeSchemaForGemini(t.input_schema || { type: 'object', properties: {} })
      }))
    : undefined;

  let lastErr: any = null;
  for (const modelId of candidateModels) {
    try {
      const url = `${GEMINI_BASE_URL}/${encodeURIComponent(modelId)}:generateContent?key=${encodeURIComponent(apiKey)}`;
      const bodyPayload: Record<string, any> = {
        contents,
        generationConfig: {
          maxOutputTokens: maxTokens,
          temperature: 0.4
        }
      };

      if (systemPrompt && systemPrompt.trim()) {
        bodyPayload.systemInstruction = {
          parts: [{ text: systemPrompt }]
        };
      }
      if (functionDeclarations && functionDeclarations.length > 0) {
        bodyPayload.tools = [{ functionDeclarations }];
        bodyPayload.toolConfig = {
          functionCallingConfig: {
            mode: 'AUTO'
          }
        };
      }

      const json: any = await retryWithBackoff(
        async () => {
          const resp = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bodyPayload)
          });
          if (!resp.ok) {
            const errText = await resp.text().catch(() => '');
            if (resp.status === 404 || resp.status === 400 || resp.status === 401 || resp.status === 403) {
              return { __nonRetryableError: true, status: resp.status, errText };
            }
            const err: any = new Error(`Gemini HTTP ${resp.status}: ${errText.slice(0, 300)}`);
            err.status = resp.status;
            throw err;
          }
          return resp.json();
        },
        { maxAttempts: 3, baseDelayMs: 400, maxDelayMs: 4_500 }
      );

      if (json?.__nonRetryableError) {
        const err: any = new Error(`Gemini HTTP ${json.status}: ${String(json.errText || '').slice(0, 300)}`);
        err.status = json.status;
        throw err;
      }

      cachedWorkingGeminiModel = modelId;
      return convertGeminiResponseToAnthropicShape(json, modelId);
    } catch (err: any) {
      lastErr = err;
      if (cachedWorkingGeminiModel === modelId) {
        cachedWorkingGeminiModel = null;
      }
      const status = Number(err?.status || 0);
      if (status === 401 || status === 403) {
        throw err;
      }
      continue;
    }
  }

  throw lastErr || new Error('All Gemini models failed');
}
