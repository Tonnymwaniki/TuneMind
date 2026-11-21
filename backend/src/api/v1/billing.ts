import { Router, Request, Response } from 'express';
import {
  SubscriptionEntitlementStore,
  TUNEMIND_BILLING_PLANS
} from '../../domain/billing/SubscriptionEntitlementStore';
import { resolveEffectiveUserIdentity } from '../../middleware/authMiddleware';

const router = Router();

/**
 * GET /api/v1/billing/plans
 * Public plan metadata (localized Play Store prices take precedence on device).
 */
router.get('/plans', (_req: Request, res: Response) => {
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
router.get('/entitlement', async (req: Request, res: Response) => {
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
router.post('/google-play/verify', async (req: Request, res: Response) => {
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
router.post('/google-play/restore', async (req: Request, res: Response) => {
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

export default router;
