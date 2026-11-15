import crypto from 'crypto';
import { Request, Response, NextFunction } from 'express';
import { getLiveSupabaseClient, getSupabaseAdminClient } from '../config/supabase';
import { SupabaseIntelligenceStore } from '../domain/storage/SupabaseIntelligenceStore';

export interface AuthenticatedUser {
  id: string;
  email?: string;
  provider?: string;
}

declare global {
  namespace Express {
    interface Request {
      authUser?: AuthenticatedUser;
    }
  }
}

interface CachedTokenEntry {
  user: AuthenticatedUser;
  expiresAtMs: number;
}

const TOKEN_CACHE_TTL_MS = 60_000;
const MAX_CACHE_ENTRIES = 1000;
const verifiedTokenCache = new Map<string, CachedTokenEntry>();

function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export function clearAuthCacheForTests(): void {
  verifiedTokenCache.clear();
}

export function extractBearerToken(req: Request): string | null {
  const authHeader = req.headers.authorization || req.headers.Authorization;
  if (typeof authHeader !== 'string') return null;
  const trimmed = authHeader.trim();
  if (!trimmed.toLowerCase().startsWith('bearer ')) return null;
  const token = trimmed.slice(7).trim();
  return token.length > 0 ? token : null;
}

export async function verifySupabaseJwt(token: string): Promise<{
  user: AuthenticatedUser | null;
  serverConfigured: boolean;
}> {
  const client = getSupabaseAdminClient() || getLiveSupabaseClient();
  if (!client) {
    return { user: null, serverConfigured: false };
  }

  const key = hashToken(token);
  const now = Date.now();
  const cached = verifiedTokenCache.get(key);
  if (cached && cached.expiresAtMs > now) {
    return { user: cached.user, serverConfigured: true };
  }

  try {
    const { data, error } = await client.auth.getUser(token);
    if (error || !data?.user?.id) {
      verifiedTokenCache.delete(key);
      return { user: null, serverConfigured: true };
    }

    const user: AuthenticatedUser = {
      id: data.user.id,
      email: data.user.email ? data.user.email.trim().toLowerCase() : undefined,
      provider: typeof data.user.app_metadata?.provider === 'string'
        ? data.user.app_metadata.provider
        : undefined
    };

    if (verifiedTokenCache.size >= MAX_CACHE_ENTRIES) {
      for (const [k, v] of verifiedTokenCache.entries()) {
        if (v.expiresAtMs <= now) {
          verifiedTokenCache.delete(k);
        }
      }
      if (verifiedTokenCache.size >= MAX_CACHE_ENTRIES) {
        const oldestKey = verifiedTokenCache.keys().next().value;
        if (oldestKey) verifiedTokenCache.delete(oldestKey);
      }
    }

    verifiedTokenCache.set(key, {
      user,
      expiresAtMs: now + TOKEN_CACHE_TTL_MS
    });

    return { user, serverConfigured: true };
  } catch (err) {
    console.warn('[AuthMiddleware] Token verification error:', err);
    return { user: null, serverConfigured: true };
  }
}

/**
 * Verifies Authorization: Bearer <jwt> when provided and attaches req.authUser,
 * while allowing unauthenticated guest or local-dev requests to proceed.
 */
export async function optionalAuth(req: Request, _res: Response, next: NextFunction): Promise<void> {
  const token = extractBearerToken(req);
  if (!token) {
    next();
    return;
  }

  const { user } = await verifySupabaseJwt(token);
  if (user) {
    req.authUser = user;
  }
  next();
}

/**
 * Enforces a verified Supabase Bearer JWT on protected endpoints (e.g. account deletion).
 */
export async function requireAuth(req: Request, res: Response, next: NextFunction): Promise<void> {
  const token = extractBearerToken(req);
  if (!token) {
    res.status(401).json({
      error: 'Unauthorized',
      message: 'Authorization Bearer token is required.'
    });
    return;
  }

  const { user, serverConfigured } = await verifySupabaseJwt(token);
  if (!serverConfigured) {
    res.status(503).json({
      error: 'AuthUnavailable',
      message: 'Supabase authentication is not configured on the server.'
    });
    return;
  }

  if (!user) {
    res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid or expired authentication session.'
    });
    return;
  }

  req.authUser = user;
  next();
}

/**
 * Resolves the effective user identifier for data queries/writes.
 * When a verified JWT is present, authenticated identity always takes precedence
 * over spoofable body/query user_id or user_email fields.
 */
export function resolveEffectiveUserIdentity(
  req: Request,
  rawUserId?: string,
  rawUserEmail?: string
): { userId: string; userEmail: string; isAuthenticated: boolean } {
  if (req.authUser?.id) {
    return {
      userId: req.authUser.id,
      userEmail: req.authUser.email || '',
      isAuthenticated: true
    };
  }

  return {
    userId: SupabaseIntelligenceStore.normalizeUserId(rawUserId, rawUserEmail),
    userEmail: (rawUserEmail || '').trim().toLowerCase(),
    isAuthenticated: false
  };
}
