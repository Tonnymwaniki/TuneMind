import { Router, Request, Response } from 'express';
import {
  SubscriptionEntitlementStore,
  TUNEMIND_BILLING_PLANS
} from '../../domain/billing/SubscriptionEntitlementStore';
import { requireAuthInProduction, resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';

const router = Router();

/**
 * GET /api/v1/billing/plans
 * Public plan metadata (localized Play Store prices take precedence on device).
 */
router.get('/plans', (_req: Request, res: Response) => {
  res.setHeader('Cache-Control', 'public, max-age=300, stale-while-revalidate=600');
  res.json({
    status: 'ok',
    freeForeverFeatures: [
      'Unlimited cross-app background listening tracking & real play counts',
      'Spotify, YouTube Music & local player library & playlists',
      'Play without leaving TuneMind for Spotify Premium users',
      '2 free For You song picks daily'
    ],
    proFeatures: [
      'Conversational Pulse AI Music Agent & mood curation',
      'Full 15-song For You Daily Drop + refreshes',
      'Smart Dopamine Vault & Hearing Care deep insights'
    ],
    plans: TUNEMIND_BILLING_PLANS
  });
});

/**
 * GET /api/v1/billing/entitlement
 * Returns user's current subscription tier (FREE, TRIAL, PRO) and daily AI quota usage.
 */
router.get('/entitlement', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const identity = resolveEffectiveUserIdentity(
      req,
      req.query.userId as string | undefined,
      req.query.userEmail as string | undefined
    );
    const entitlement = await SubscriptionEntitlementStore.getEntitlement(identity.userId);
    res.json({
      status: 'ok',
      entitlement
    });
  } catch (error) {
    console.error('[billing.ts] Error loading entitlement:', error);
    res.status(500).json({
      status: 'error',
      message: 'Failed to load subscription status'
    });
  }
});

/**
 * POST /api/v1/billing/google-play/verify
 * Verifies a Google Play Billing v7 subscription purchase token server-side.
 */
router.post('/google-play/verify', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(
      req,
      body.userId || body.user_id,
      body.userEmail || body.user_email
    );
    const productId = String(body.productId || body.product_id || '').trim();
    const purchaseToken = String(body.purchaseToken || body.purchase_token || '').trim();
    const packageName = String(body.packageName || body.package_name || 'com.tunemind.app').trim();

    if (!productId || !purchaseToken) {
      res.status(400).json({
        status: 'error',
        message: 'productId and purchaseToken are required'
      });
      return;
    }

    const result = await SubscriptionEntitlementStore.verifyGooglePlayPurchase({
      userId: identity.userId,
      productId,
      purchaseToken,
      packageName,
      orderId: body.orderId ? String(body.orderId) : undefined
    });

    if (!result.verified) {
      res.status(400).json({
        status: 'error',
        message: result.error || 'Could not verify Google Play purchase',
        entitlement: result.entitlement
      });
      return;
    }

    res.json({
      status: 'ok',
      verified: true,
      entitlement: result.entitlement
    });
  } catch (error) {
    console.error('[billing.ts] Error verifying Google Play purchase:', error);
    res.status(500).json({
      status: 'error',
      message: 'Google Play purchase verification failed'
    });
  }
});

/**
 * POST /api/v1/billing/google-play/restore
 * Restores active Play Store purchases queried on device.
 */
router.post('/google-play/restore', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const identity = resolveEffectiveUserIdentity(
      req,
      body.userId || body.user_id,
      body.userEmail || body.user_email
    );
    const purchases = Array.isArray(body.purchases) ? body.purchases.slice(0, 10) : [];

    for (const item of purchases) {
      const productId = String(item?.productId || '').trim();
      const purchaseToken = String(item?.purchaseToken || '').trim();
      if (!productId || !purchaseToken) continue;
      const result = await SubscriptionEntitlementStore.verifyGooglePlayPurchase({
        userId: identity.userId,
        productId,
        purchaseToken,
        packageName: String(item?.packageName || 'com.tunemind.app').trim()
      });
      if (result.verified && result.entitlement.hasProAccess) {
        res.json({
          status: 'ok',
          restored: true,
          entitlement: result.entitlement
        });
        return;
      }
    }

    const entitlement = await SubscriptionEntitlementStore.getEntitlement(identity.userId);
    res.json({
      status: 'ok',
      restored: entitlement.hasProAccess,
      entitlement
    });
  } catch (error) {
    console.error('[billing.ts] Error restoring purchases:', error);
    res.status(500).json({
      status: 'error',
      message: 'Failed to restore purchases'
    });
  }
});

/**
 * POST /api/v1/billing/google-play/rtdn
 * Google Play Real-Time Developer Notifications (Cloud Pub/Sub push endpoint).
 */
router.post('/google-play/rtdn', async (req: Request, res: Response) => {
  try {
    const expectedSecret = (process.env.GOOGLE_PLAY_RTDN_SECRET || '').trim();
    if (process.env.NODE_ENV === 'production' && !expectedSecret) {
      res.status(503).json({ status: 'error', message: 'RTDN webhook secret is not configured' });
      return;
    }
    if (expectedSecret && req.query.secret !== expectedSecret) {
      res.status(401).json({ status: 'error', message: 'Unauthorized RTDN webhook' });
      return;
    }

    const encodedData = req.body?.message?.data;
    if (typeof encodedData !== 'string' || !encodedData.trim()) {
      res.status(400).json({ status: 'error', message: 'Missing Pub/Sub message data' });
      return;
    }

    const decodedJson = JSON.parse(Buffer.from(encodedData, 'base64').toString('utf8'));
    const outcome = await SubscriptionEntitlementStore.handlePlayRtdnNotification(decodedJson);
    res.status(200).json({
      status: 'ok',
      handled: outcome.handled,
      notificationType: outcome.notificationType
    });
  } catch (error) {
    console.error('[billing.ts] Error processing RTDN notification:', error);
    res.status(400).json({ status: 'error', message: 'Malformed RTDN payload' });
  }
});

/**
 * GET /api/v1/billing/trial-reminder
 * Checks whether the authenticated user's free trial converts within 48-72 hours
 * and returns the renewal date, price, and one-tap Google Play cancel link.
 */
router.get('/trial-reminder', requireAuthInProduction, async (req: Request, res: Response) => {
  try {
    const identity = resolveEffectiveUserIdentity(
      req,
      req.query.userId as string | undefined,
      req.query.userEmail as string | undefined
    );
    const entitlement = await SubscriptionEntitlementStore.getEntitlement(identity.userId);
    const nowMs = Date.now();
    const expiresMs = entitlement.trialEndsAtMs ?? entitlement.currentPeriodEndMs ?? NaN;
    const hoursRemaining = Number.isFinite(expiresMs) ? (expiresMs - nowMs) / (1000 * 60 * 60) : null;
    const isTrialExpiringSoon =
      entitlement.tier === 'TRIAL' &&
      hoursRemaining !== null &&
      hoursRemaining > 0 &&
      hoursRemaining <= 72;

    const sku = entitlement.planId || 'tunemind_pro_monthly';
    const matchedPlan = TUNEMIND_BILLING_PLANS.find((p) => p.planId === sku) || TUNEMIND_BILLING_PLANS[0];
    const renewalIso = Number.isFinite(expiresMs) ? new Date(expiresMs).toISOString() : null;
    const cancelUrl = `https://play.google.com/store/account/subscriptions?sku=${encodeURIComponent(sku)}&package=com.tunemind.app`;

    res.json({
      status: 'ok',
      reminderDue: isTrialExpiringSoon,
      hoursRemaining: hoursRemaining !== null ? Math.round(hoursRemaining) : null,
      renewalDate: renewalIso,
      priceDisplay: matchedPlan?.globalPriceUsd || '$4.99/month',
      cancelUrl,
      message: isTrialExpiringSoon && renewalIso
        ? `Your TuneMind Pro free trial ends on ${renewalIso}. It will renew at ${matchedPlan?.globalPriceUsd || '$4.99/month'} unless you cancel beforehand.`
        : null
    });
  } catch (error) {
    console.error('[billing.ts] Error checking trial reminder:', error);
    res.status(500).json({ status: 'error', message: 'Failed to check trial reminder status' });
  }
});

/**
 * POST /api/v1/billing/trials/send-reminders
 * Cron-callable endpoint (protected by TRIAL_REMINDER_CRON_SECRET in production)
 * to dispatch 48-72h free-trial expiration reminders via configured email webhook.
 */
router.post('/trials/send-reminders', async (req: Request, res: Response) => {
  const expectedSecret = (process.env.TRIAL_REMINDER_CRON_SECRET || '').trim();
  if (process.env.NODE_ENV === 'production' && !expectedSecret) {
    res.status(503).json({ status: 'error', message: 'TRIAL_REMINDER_CRON_SECRET is not configured' });
    return;
  }
  if (expectedSecret && req.headers['x-cron-secret'] !== expectedSecret) {
    res.status(401).json({ status: 'error', message: 'Unauthorized cron trigger' });
    return;
  }

  try {
    const { getSupabaseAdminClient } = await import('../../config/supabase');
    const admin = getSupabaseAdminClient();
    if (!admin) {
      res.json({ status: 'ok', dispatched: 0, note: 'Supabase admin client not configured' });
      return;
    }

    const now = new Date();
    const windowStart = new Date(now.getTime() + 48 * 60 * 60 * 1000).toISOString();
    const windowEnd = new Date(now.getTime() + 72 * 60 * 60 * 1000).toISOString();

    const { data: trials, error } = await admin
      .from('billing_entitlements')
      .select('user_id, product_id, expires_at')
      .eq('tier', 'TRIAL')
      .gte('expires_at', windowStart)
      .lte('expires_at', windowEnd);

    if (error) {
      res.status(500).json({ status: 'error', message: error.message });
      return;
    }

    const webhookUrl = (process.env.TRIAL_REMINDER_WEBHOOK_URL || '').trim();
    let dispatched = 0;

    for (const row of trials || []) {
      const sku = String(row.product_id || 'tunemind_pro_monthly');
      const matchedPlan = TUNEMIND_BILLING_PLANS.find((p) => p.planId === sku) || TUNEMIND_BILLING_PLANS[0];
      const cancelUrl = `https://play.google.com/store/account/subscriptions?sku=${encodeURIComponent(sku)}&package=com.tunemind.app`;

      if (webhookUrl) {
        await fetch(webhookUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            userId: row.user_id,
            renewalDate: row.expires_at,
            price: matchedPlan?.globalPriceUsd || '$4.99/month',
            cancelUrl
          })
        }).catch(() => {});
      }
      dispatched += 1;
    }

    res.json({
      status: 'ok',
      windowStart,
      windowEnd,
      dispatched
    });
  } catch (error) {
    console.error('[billing.ts] Error dispatching trial reminders:', error);
    res.status(500).json({ status: 'error', message: 'Failed to dispatch trial reminders' });
  }
});

export default router;
