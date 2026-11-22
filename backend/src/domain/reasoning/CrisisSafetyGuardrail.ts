/**
 * Pre-check safety classifier and crisis-resource response builder for
 * all user-facing Anthropic Claude API endpoints.
 */

export const CRISIS_SAFETY_SYSTEM_INSTRUCTION = `
CRITICAL SAFETY POLICY:
If the user expresses suicidal ideation, self-harm intent, hopelessness about staying alive, or severe acute emotional crisis:
- Do NOT respond with casual music banter, jokes, or dismissive advice.
- Respond with warmth, empathy, and immediate support resources:
  * US & Canada: Call or text 988 (Suicide & Crisis Lifeline)
  * Crisis Text Line: Text HOME to 741741
  * International: Find a local helpline at https://findahelpline.com or https://www.befrienders.org
  * Immediate danger: Call local emergency services (911, 999, 112).
`.trim();

const CRISIS_PATTERNS: RegExp[] = [
  /\b(kill\s+my\s*self|kms|end\s+my\s+life|take\s+my\s+(own\s+)?life)\b/i,
  /\b(want\s+to\s+die|wanna\s+die|wish\s+i\s+was\s+dead|wish\s+i\s+were\s+dead|better\s+off\s+dead)\b/i,
  /\b(suicid(e|al)|no\s+reason\s+to\s+live|can'?t\s+go\s+on\s+living|don'?t\s+want\s+to\s+live)\b/i,
  /\b(self[- ]?harm|hurt\s+my\s*self|cut(ting)?\s+my\s*self|overdose\s+on)\b/i
];

export function detectCrisisRisk(input: unknown): boolean {
  if (typeof input !== 'string') return false;
  const normalized = input.trim();
  if (!normalized) return false;
  return CRISIS_PATTERNS.some((pattern) => pattern.test(normalized));
}

export function extractTextForCrisisScan(payload: unknown): string {
  if (!payload || typeof payload !== 'object') return String(payload || '');
  const obj = payload as Record<string, unknown>;
  const candidates = [
    obj.query,
    obj.message,
    obj.prompt,
    obj.mood,
    obj.user_note,
    obj.notes,
    obj.context
  ];
  return candidates
    .filter((v): v is string => typeof v === 'string' && v.trim().length > 0)
    .join('\n');
}

export function buildCrisisSupportMessage(userName?: string): string {
  const greeting = userName && userName.trim() ? `${userName.trim()}, ` : '';
  return (
    `${greeting}I'm really glad you reached out, and I want you to be safe. ` +
    `You don't have to carry this alone — please connect with someone who can support you right now:\n\n` +
    `• US & Canada: Call or text 988 (Suicide & Crisis Lifeline, free & 24/7)\n` +
    `• Crisis Text Line: Text HOME to 741741\n` +
    `• International Helplines: https://findahelpline.com or https://www.befrienders.org\n` +
    `• Immediate Danger: Call your local emergency services (911, 999, or 112)\n\n` +
    `Please reach out to one of these resources or a trusted person near you.`
  );
}

export function buildCrisisAgentChatResponse(userName?: string) {
  const supportText = buildCrisisSupportMessage(userName);
  return {
    safety_intercepted: true,
    crisis_resources: {
      us_canada_lifeline: '988',
      crisis_text_line: 'Text HOME to 741741',
      international_url: 'https://findahelpline.com',
      befrienders_url: 'https://www.befrienders.org'
    },
    reply: supportText,
    spoken_response: supportText,
    voice_agent_tts:
      "I'm really glad you reached out. You don't have to go through this alone. Please call or text 988 in the US or Canada, visit findahelpline.com for international support, or contact your local emergency services right away.",
    action: 'NONE',
    recommended_tracks: []
  };
}
