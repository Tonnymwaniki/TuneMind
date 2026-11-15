import crypto from 'crypto';
import { Router, Request, Response } from 'express';
import { getLiveSupabaseClient, getSupabaseAdminClient } from '../../config/supabase';

const router = Router();

export interface SupportFeedbackRecord {
  id: string;
  user_id: string;
  user_email: string;
  category: string;
  message: string;
  diagnostics_json: Record<string, unknown>;
  app_version: string;
  status: 'OPEN' | 'REVIEWED';
  created_at: string;
}

const ALLOWED_CATEGORIES = new Set([
  'Bug Report',
  'Spotify Sync',
  'Voice & Agent',
  'Feature Idea',
  'Account & Privacy',
  'Other'
]);

const MAX_IN_MEMORY_FEEDBACK = 200;
const recentFeedbackBuffer: SupportFeedbackRecord[] = [];

function sanitizeShortString(value: unknown, maxLen: number, fallback = ''): string {
  if (typeof value !== 'string') return fallback;
  const cleaned = value.trim().replace(/\s+/g, ' ');
  return cleaned.length > 0 ? cleaned.slice(0, maxLen) : fallback;
}

/**
 * POST /api/v1/support/feedback
 * Accepts structured user bug reports, Spotify sync feedback, and diagnostics
 * from the Android SupportFeedbackScreen. Verified Bearer token overrides spoofed IDs.
 */
router.post('/feedback', async (req: Request, res: Response) => {
  try {
    const body = (req.body && typeof req.body === 'object') ? req.body as Record<string, unknown> : {};
    const rawMessage = typeof body.message === 'string' ? body.message.trim() : '';

    if (rawMessage.length < 3) {
      res.status(400).json({
        status: 'error',
        error: 'Validation error',
        message: 'Feedback message must be at least 3 characters.'
      });
      return;
    }

    if (rawMessage.length > 4000) {
      res.status(400).json({
        status: 'error',
        error: 'Validation error',
        message: 'Feedback message cannot exceed 4000 characters.'
      });
      return;
    }

    const rawCategory = sanitizeShortString(body.category, 60, 'Other');
    const category = ALLOWED_CATEGORIES.has(rawCategory) ? rawCategory : rawCategory;

    const diagnosticsObj: Record<string, unknown> =
      body.diagnostics && typeof body.diagnostics === 'object' && !Array.isArray(body.diagnostics)
        ? body.diagnostics as Record<string, unknown>
        : {};

    const appVersion = sanitizeShortString(
      diagnosticsObj.app_version ?? body.app_version,
      40,
      ''
    );

    const effectiveUserId = req.authUser?.id
      || sanitizeShortString(body.user_id, 120, 'anonymous');
    const effectiveUserEmail = req.authUser?.email
      || sanitizeShortString(body.user_email, 180, '').toLowerCase();

    const id = crypto.randomUUID();
    const createdAt = new Date().toISOString();

    const record: SupportFeedbackRecord = {
      id,
      user_id: effectiveUserId,
      user_email: effectiveUserEmail,
      category,
      message: rawMessage,
      diagnostics_json: diagnosticsObj,
      app_version: appVersion,
      status: 'OPEN',
      created_at: createdAt
    };

    recentFeedbackBuffer.unshift(record);
    if (recentFeedbackBuffer.length > MAX_IN_MEMORY_FEEDBACK) {
      recentFeedbackBuffer.length = MAX_IN_MEMORY_FEEDBACK;
    }

    let persisted = false;
    const client = getSupabaseAdminClient() || getLiveSupabaseClient();
    if (client) {
      try {
        const { error } = await client
          .from('support_feedback')
          .insert(record);
        if (error) {
          console.warn('[Support API] Supabase insert warning:', error.message);
        } else {
          persisted = true;
        }
      } catch (dbErr) {
        console.warn('[Support API] Supabase insert failed:', dbErr);
      }
    }

    console.log(`[Support API] Received ${category} feedback (${id}) from ${effectiveUserEmail || effectiveUserId} | persisted=${persisted}`);

    res.status(201).json({
      status: 'received',
      id,
      category,
      persisted,
      created_at: createdAt
    });
  } catch (err: any) {
    console.error('[Support API] POST /feedback error:', err);
    res.status(500).json({
      status: 'error',
      error: err?.message || 'Failed to record support feedback'
    });
  }
});

/**
 * GET /api/v1/support/feedback
 * Returns either the caller's own submitted tickets (when authenticated) or dev buffer.
 */
router.get('/feedback', async (req: Request, res: Response) => {
  const isDev = process.env.NODE_ENV === 'development';
  if (!isDev && !req.authUser) {
    res.status(401).json({ error: 'Authentication required to view support tickets.' });
    return;
  }

  const userId = req.authUser?.id;
  const client = getSupabaseAdminClient() || getLiveSupabaseClient();

  if (client && userId) {
    try {
      const { data, error } = await client
        .from('support_feedback')
        .select('id, category, message, app_version, status, created_at')
        .eq('user_id', userId)
        .order('created_at', { ascending: false })
        .limit(25);
      if (!error && Array.isArray(data)) {
        res.json({ status: 'ok', tickets: data });
        return;
      }
    } catch (err) {
      console.warn('[Support API] Failed querying Supabase tickets:', err);
    }
  }

  const filtered = userId
    ? recentFeedbackBuffer.filter(r => r.user_id === userId)
    : recentFeedbackBuffer.slice(0, 25);

  res.json({
    status: 'ok',
    tickets: filtered.map(({ id, category, message, app_version, status, created_at }) => ({
      id,
      category,
      message,
      app_version,
      status,
      created_at
    }))
  });
});

export default router;
