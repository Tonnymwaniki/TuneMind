import net from 'net';

/**
 * Validates that an outbound URL is public HTTPS/HTTP and not targeting loopback,
 * link-local (169.254.169.254 cloud metadata), or RFC1918 private networks (SSRF defense).
 */
export function isSafeExternalHttpUrl(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
      return false;
    }
    const host = parsed.hostname.trim().toLowerCase();
    if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
      return false;
    }
    if (net.isIP(host)) {
      if (host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80')) {
        return false;
      }
      const parts = host.split('.').map(Number);
      if (parts.length === 4) {
        const [a, b] = parts;
        if (
          a === 0 ||
          a === 10 ||
          a === 127 ||
          (a === 169 && b === 254) ||
          (a === 172 && b >= 16 && b <= 31) ||
          (a === 192 && b === 168)
        ) {
          return false;
        }
      }
    }
    return true;
  } catch {
    return false;
  }
}

export function sanitizePromptField(raw: unknown, maxLen = 200): string {
  return String(raw ?? '')
    .replace(/[\u0000-\u001F\u007F]/g, ' ')
    .replace(/<\/?(?:system|assistant|user_input|instructions)[^>]*>/gi, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, maxLen);
}
