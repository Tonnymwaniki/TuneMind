import { Router, Request, Response } from 'express';
import { getLiveSupabaseClient, getSupabaseAdminClient, isServiceRoleConfigured } from '../../config/supabase';
import { requireAuth } from '../../middleware/authMiddleware';
import { EventStore } from '../../domain/events/EventStore';

const router = Router();

/**
 * GET /api/v1/account/me
 * Returns the authenticated TuneMind account identity and cloud profile row.
 */
router.get('/me', requireAuth, async (req: Request, res: Response) => {
  try {
    const user = req.authUser!;
    const client = getSupabaseAdminClient() || getLiveSupabaseClient();

    let profile: Record<string, unknown> | null = null;
    if (client) {
      const { data } = await client
        .from('profiles')
        .select('id, email, display_name, avatar_url, primary_provider, google_connected, spotify_connected, private_session_enabled, merge_duplicate_plays, preferred_target_player, taste_archetype, created_at, updated_at')
        .eq('id', user.id)
        .maybeSingle();
      if (data) {
        profile = data as Record<string, unknown>;
      }
    }

    res.json({
      status: 'ok',
      user: {
        id: user.id,
        email: user.email || '',
        provider: user.provider || 'email'
      },
      profile
    });
  } catch (err: any) {
    console.error('[Account API] GET /me failed:', err);
    res.status(500).json({ error: err?.message || 'Failed to fetch account profile' });
  }
});

/**
 * DELETE /api/v1/account
 * Permanently wipes the authenticated user's cloud intelligence data, profile,
 * support feedback, local server events, and deletes their Supabase auth user
 * (when SUPABASE_SERVICE_ROLE_KEY is configured).
 */
router.delete('/', requireAuth, async (req: Request, res: Response) => {
  const user = req.authUser!;
  const identifiers = Array.from(
    new Set(
      [user.id, user.email?.toLowerCase()]
        .map(v => (v || '').trim())
        .filter(Boolean)
    )
  );

  const wipedTables: string[] = [];
  const tableErrors: Array<{ table: string; error: string }> = [];

  try {
    const adminClient = getSupabaseAdminClient();
    const dbClient = adminClient || getLiveSupabaseClient();

    if (dbClient) {
      // Delete user-owned rows keyed by text user_id (supports both UUID and legacy email identifiers)
      const userIdTables = [
        'listening_events',
        'agent_interventions',
        'user_taste_genome',
        'support_feedback',
        'ml_user_taste_models',
        'discovery_recommendation_outcomes'
      ];

      for (const table of userIdTables) {
        try {
          const { error } = await dbClient
            .from(table)
            .delete()
            .in('user_id', identifiers);
          if (error) {
            // Table may be optional (e.g. ml_user_taste_models) or RLS restricted when service key missing
            tableErrors.push({ table, error: error.message });
          } else {
            wipedTables.push(table);
          }
        } catch (err: any) {
          tableErrors.push({ table, error: err?.message || 'Unknown error' });
        }
      }

      // Delete UUID-keyed Spotify encrypted connection row
      try {
        const { error } = await dbClient
          .from('user_spotify_connections')
          .delete()
          .eq('user_id', user.id);
        if (error) {
          tableErrors.push({ table: 'user_spotify_connections', error: error.message });
        } else {
          wipedTables.push('user_spotify_connections');
        }
      } catch (err: any) {
        tableErrors.push({ table: 'user_spotify_connections', error: err?.message || 'Unknown error' });
      }

      // Delete UUID-keyed profile row
      try {
        const { error } = await dbClient
          .from('profiles')
          .delete()
          .eq('id', user.id);
        if (error) {
          tableErrors.push({ table: 'profiles', error: error.message });
        } else {
          wipedTables.push('profiles');
        }
      } catch (err: any) {
        tableErrors.push({ table: 'profiles', error: err?.message || 'Unknown error' });
      }
    }

    // Also delete local EventStore rows for this user
    let removedLocalEvents = 0;
    try {
      const store = new EventStore();
      removedLocalEvents = store.deleteByUserIds(identifiers);
    } catch (err) {
      console.warn('[Account API] Local EventStore cleanup notice:', err);
    }

    // Delete auth.users record when service role key is present
    let authUserDeleted = false;
    let authDeleteMessage = isServiceRoleConfigured()
      ? 'Auth user deleted.'
      : 'Profile and user tables deleted; set SUPABASE_SERVICE_ROLE_KEY on backend to also purge auth.users record automatically.';

    if (adminClient) {
      const { error: deleteAuthError } = await adminClient.auth.admin.deleteUser(user.id);
      if (deleteAuthError) {
        authDeleteMessage = `Admin deleteUser warning: ${deleteAuthError.message}`;
        console.warn('[Account API]', authDeleteMessage);
      } else {
        authUserDeleted = true;
      }
    }

    res.status(200).json({
      status: 'deleted',
      user_id: user.id,
      auth_user_deleted: authUserDeleted,
      auth_message: authDeleteMessage,
      wiped_tables: wipedTables,
      removed_local_events: removedLocalEvents,
      warnings: tableErrors.length > 0 ? tableErrors : undefined
    });
  } catch (err: any) {
    console.error('[Account API] DELETE / failed:', err);
    res.status(500).json({
      status: 'error',
      error: err?.message || 'Failed to delete account'
    });
  }
});

export default router;
