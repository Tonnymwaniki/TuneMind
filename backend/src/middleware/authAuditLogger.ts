import { Request } from 'express';

export interface AuthAuditEvent {
  timestamp: string;
  event: 'AUTH_SUCCESS' | 'AUTH_FAILURE' | 'ANOMALOUS_AUTH_BURST';
  ip: string;
  path: string;
  userAgent: string;
  appVersion: string;
  userId?: string;
  provider?: string;
  reason?: string;
}

const BURST_WINDOW_MS = 5 * 60_000;
const BURST_THRESHOLD = 8;
const failureBursts = new Map<string, { count: number; windowStartMs: number }>();

export function recordAuthAuditEvent(
  req: Request,
  event: 'AUTH_SUCCESS' | 'AUTH_FAILURE',
  details: { userId?: string; provider?: string; reason?: string } = {}
): void {
  const ip = req.ip || req.socket?.remoteAddress || 'unknown';
  const now = Date.now();
  const userAgent = String(req.headers['user-agent'] || '').slice(0, 120);
  const appVersion = String(req.headers['x-app-version'] || req.headers['x-tunemind-version'] || '').slice(0, 40);

  let emittedEvent: AuthAuditEvent['event'] = event;

  if (event === 'AUTH_FAILURE') {
    const existing = failureBursts.get(ip);
    if (!existing || now - existing.windowStartMs > BURST_WINDOW_MS) {
      failureBursts.set(ip, { count: 1, windowStartMs: now });
    } else {
      existing.count += 1;
      if (existing.count >= BURST_THRESHOLD) {
        emittedEvent = 'ANOMALOUS_AUTH_BURST';
      }
    }
    if (failureBursts.size > 5_000) {
      for (const [k, v] of failureBursts.entries()) {
        if (now - v.windowStartMs > BURST_WINDOW_MS) failureBursts.delete(k);
      }
    }
  } else {
    failureBursts.delete(ip);
  }

  const payload: AuthAuditEvent = {
    timestamp: new Date(now).toISOString(),
    event: emittedEvent,
    ip,
    path: req.originalUrl || req.path,
    userAgent,
    appVersion,
    ...(details.userId ? { userId: details.userId } : {}),
    ...(details.provider ? { provider: details.provider } : {}),
    ...(details.reason ? { reason: details.reason } : {})
  };

  if (emittedEvent === 'ANOMALOUS_AUTH_BURST') {
    console.warn('[AUTH_AUDIT]', JSON.stringify(payload));
  } else if (emittedEvent === 'AUTH_FAILURE') {
    console.info('[AUTH_AUDIT]', JSON.stringify(payload));
  }
}
