import { Request, Response, NextFunction } from 'express';

export interface RateLimiterOptions {
  windowMs: number;
  max: number;
  keyPrefix?: string;
  message?: string;
  skipWhenEnvDisabled?: boolean;
}

interface RateBucket {
  count: number;
  resetAt: number;
}

const MAX_BUCKET_ENTRIES = 5000;

function extractClientIp(req: Request): string {
  const forwarded = req.headers['x-forwarded-for'];
  if (typeof forwarded === 'string' && forwarded.trim().length > 0) {
    return forwarded.split(',')[0].trim();
  }
  if (Array.isArray(forwarded) && forwarded.length > 0) {
    return forwarded[0].trim();
  }
  return req.ip || req.socket?.remoteAddress || 'unknown';
}

/**
 * In-memory fixed/sliding bucket rate limiter by client IP.
 * Suitable for single-instance Render deployments with bounded memory cleanup.
 */
export function createRateLimiter(options: RateLimiterOptions) {
  const windowMs = Math.max(1000, options.windowMs);
  const max = Math.max(1, options.max);
  const keyPrefix = options.keyPrefix || 'global';
  const message = options.message || 'Too many requests. Please slow down and try again shortly.';
  const buckets = new Map<string, RateBucket>();

  const pruneExpired = (now: number) => {
    for (const [k, bucket] of buckets.entries()) {
      if (bucket.resetAt <= now) {
        buckets.delete(k);
      }
    }
    if (buckets.size > MAX_BUCKET_ENTRIES) {
      const toRemove = buckets.size - MAX_BUCKET_ENTRIES;
      let removed = 0;
      for (const k of buckets.keys()) {
        buckets.delete(k);
        removed++;
        if (removed >= toRemove) break;
      }
    }
  };

  return (req: Request, res: Response, next: NextFunction): void => {
    if (options.skipWhenEnvDisabled !== false && process.env.DISABLE_RATE_LIMIT === 'true') {
      next();
      return;
    }

    const now = Date.now();
    if (buckets.size > 500) {
      pruneExpired(now);
    }

    const key = `${keyPrefix}:${extractClientIp(req)}`;
    let bucket = buckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      bucket = { count: 0, resetAt: now + windowMs };
      buckets.set(key, bucket);
    }

    bucket.count += 1;

    const remaining = Math.max(0, max - bucket.count);
    const resetSeconds = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));

    res.setHeader('X-RateLimit-Limit', String(max));
    res.setHeader('X-RateLimit-Remaining', String(remaining));
    res.setHeader('X-RateLimit-Reset', String(Math.ceil(bucket.resetAt / 1000)));

    if (bucket.count > max) {
      res.setHeader('Retry-After', String(resetSeconds));
      res.status(429).json({
        error: 'Too many requests',
        message,
        retry_after_seconds: resetSeconds
      });
      return;
    }

    next();
  };
}
