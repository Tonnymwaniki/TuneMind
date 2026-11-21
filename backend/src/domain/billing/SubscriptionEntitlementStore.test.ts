import request from 'supertest';
import { createApp } from '../../app';
import { SubscriptionEntitlementStore } from './SubscriptionEntitlementStore';

describe('TuneMind Monetization, 7-Day Trial & Cost Protection', () => {
  test('GET /api/v1/billing/plans returns Yearly ($29.99 / KES 1,800) and Monthly ($4.99 / KES 250) 7-day trial plans', async () => {
    const app = createApp();
    const res = await request(app).get('/api/v1/billing/plans');
    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.plans)).toBe(true);
    const annual = res.body.plans.find((p: any) => p.planId === 'tunemind_pro_annual');
    const monthly = res.body.plans.find((p: any) => p.planId === 'tunemind_pro_monthly');
    expect(annual).toBeDefined();
    expect(annual.trialDays).toBe(7);
    expect(annual.globalPriceUsd).toContain('29.99');
    expect(annual.eastAfricaPriceKes).toContain('1,800');
    expect(monthly).toBeDefined();
    expect(monthly.trialDays).toBe(7);
    expect(monthly.eastAfricaPriceKes).toContain('250');
  });

  test('Free users get 2 For You catalog picks at $0 Claude cost and 402 on Agent / Refresh until starting 7-day trial', async () => {
    const app = createApp();
    const userId = `billing-free-${Date.now()}`;

    const dropRes = await request(app)
      .post('/api/v1/discovery/daily-drop')
      .send({
        userId,
        timezone: 'Africa/Nairobi',
        topArtists: ['Sauti Sol', 'Samidoh']
      });
    expect(dropRes.status).toBe(200);
    expect(dropRes.body.isFreeLimitedDrop).toBe(true);
    expect(Array.isArray(dropRes.body.recommendations)).toBe(true);
    expect(dropRes.body.recommendations.length).toBeLessThanOrEqual(2);
    expect(dropRes.body.recommendations.length).toBeGreaterThanOrEqual(1);

    const refreshRes = await request(app)
      .post('/api/v1/discovery/daily-drop')
      .send({ userId, forceRefresh: true });
    expect(refreshRes.status).toBe(402);
    expect(refreshRes.body.code).toBe('PRO_TRIAL_REQUIRED');

    const agentRes = await request(app)
      .post('/api/v1/reasoning/evaluate-satiety')
      .send({
        user_id: userId,
        voice_transcript: 'I feel stressed today, what should I listen to?'
      });
    expect(agentRes.status).toBe(402);
    expect(agentRes.body.code).toBe('PRO_TRIAL_REQUIRED');
  });

  test('Google Play verify activates 7-day Trial with 20/day Agent cap and RTDN expiration downgrades cleanly', async () => {
    const app = createApp();
    const userId = `billing-trial-${Date.now()}`;
    const rawToken = `play_test_token_${Date.now()}_abcdef123456`;

    const verifyRes = await request(app)
      .post('/api/v1/billing/google-play/verify')
      .send({
        userId,
        productId: 'tunemind_pro_annual',
        purchaseToken: rawToken,
        packageName: 'com.tunemind.app'
      });

    expect(verifyRes.status).toBe(200);
    expect(verifyRes.body.verified).toBe(true);
    expect(verifyRes.body.entitlement.tier).toBe('TRIAL');
    expect(verifyRes.body.entitlement.hasProAccess).toBe(true);
    expect(verifyRes.body.entitlement.dailyAgentLimit).toBe(20);
    expect(verifyRes.body.entitlement.trialDaysRemaining).toBeGreaterThanOrEqual(6);
    expect(JSON.stringify(verifyRes.body)).not.toContain(rawToken);

    // Exhaust 20 daily trial agent turns cleanly -> 21st returns TRIAL_QUOTA_REACHED
    for (let i = 0; i < 20; i++) {
      const q = await SubscriptionEntitlementStore.checkAndConsumeAiQuota(userId, 'agent');
      expect(q.allowed).toBe(true);
    }
    const over = await SubscriptionEntitlementStore.checkAndConsumeAiQuota(userId, 'agent');
    expect(over.allowed).toBe(false);
    expect(over.code).toBe('TRIAL_QUOTA_REACHED');

    // Simulate Google Play RTDN notificationType 13 (SUBSCRIPTION_EXPIRED)
    const rtdnRes = await SubscriptionEntitlementStore.handlePlayRtdnNotification({
      subscriptionNotification: {
        notificationType: 13,
        purchaseToken: rawToken,
        subscriptionId: 'tunemind_pro_annual'
      }
    });
    expect(rtdnRes.handled).toBe(true);

    const afterExpiry = await SubscriptionEntitlementStore.getEntitlement(userId);
    expect(afterExpiry.tier).toBe('FREE');
    expect(afterExpiry.hasProAccess).toBe(false);
  });

  test('ENFORCE_PRO_PAYWALL=false grants hasProAccess=true on GET /entitlement for pre-launch testing', async () => {
    const prev = process.env.ENFORCE_PRO_PAYWALL;
    process.env.ENFORCE_PRO_PAYWALL = 'false';
    try {
      const app = createApp();
      const res = await request(app).get(`/api/v1/billing/entitlement?userId=dev-${Date.now()}`);
      expect(res.status).toBe(200);
      expect(res.body.entitlement.hasProAccess).toBe(true);
      expect(res.body.entitlement.tier).toBe('PRO');
      expect(res.body.entitlement.verificationMode).toBe('paywall_disabled');
    } finally {
      if (prev === undefined) delete process.env.ENFORCE_PRO_PAYWALL;
      else process.env.ENFORCE_PRO_PAYWALL = prev;
    }
  });
});
