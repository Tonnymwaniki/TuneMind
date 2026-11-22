import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { getLiveSupabaseClient } from '../../config/supabase';

export type SubscriptionTier = 'FREE' | 'TRIAL' | 'PRO';
export type SubscriptionPlanId = 'tunemind_pro_annual' | 'tunemind_pro_monthly';
export type AiFeatureKind = 'agent' | 'for_you';

export interface BillingPlanOffer {
  planId: SubscriptionPlanId;
  title: string;
  billingPeriod: 'YEARLY' | 'MONTHLY';
  trialDays: number;
  isRecommended: boolean;
  globalPriceUsd: string;
  eastAfricaPriceKes: string;
  savingsBadge?: string;
  description: string;
}

export interface UserEntitlementSnapshot {
  userId: string;
  tier: SubscriptionTier;
  hasProAccess: boolean;
  isTrial: boolean;
  planId: SubscriptionPlanId | null;
  billingSource: 'google_play' | 'none';
  trialEndsAtMs: number | null;
  currentPeriodEndMs: number | null;
  trialDaysRemaining: number;
  autoRenewing: boolean;
  verificationMode: string;
  dailyAgentLimit: number;
  dailyAgentUsed: number;
  dailyAgentRemaining: number;
  dailyForYouRefreshLimit: number;
  dailyForYouRefreshesUsed: number;
  freeForYouDailyPicks: number;
  updatedAtMs: number;
}

export interface QuotaCheckResult {
  allowed: boolean;
  entitlement: UserEntitlementSnapshot;
  code?: 'PRO_TRIAL_REQUIRED' | 'TRIAL_QUOTA_REACHED' | 'PRO_FAIR_USE_REACHED';
  message?: string;
}

export interface VerifyGooglePlayInput {
  userId: string;
  productId: string;
  purchaseToken: string;
  packageName?: string;
  orderId?: string;
}

interface StoredSubscriptionRecord {
  userId: string;
  tier: SubscriptionTier;
  planId: SubscriptionPlanId | null;
  billingSource: 'google_play' | 'none';
  trialStartedAtMs: number | null;
  trialEndsAtMs: number | null;
  currentPeriodEndMs: number | null;
  autoRenewing: boolean;
  purchaseTokenHash: string | null;
  verificationMode: string;
  updatedAtMs: number;
}

interface DailyUsageBucket {
  dateKey: string;
  agentUsed: number;
  forYouUsed: number;
}

const PACKAGE_NAME = 'com.tunemind.app';
const DAY_MS = 24 * 60 * 60 * 1000;

export const TUNEMIND_BILLING_PLANS: BillingPlanOffer[] = [
  {
    planId: 'tunemind_pro_annual',
    title: 'TuneMind Pro Yearly',
    billingPeriod: 'YEARLY',
    trialDays: 7,
    isRecommended: true,
    globalPriceUsd: '$29.99 / year',
    eastAfricaPriceKes: 'KES 1,800 / year',
    savingsBadge: 'Best Value · 7 Days Free',
    description: '7-day free trial, then billed once a year. Cancel anytime in Google Play before Day 7 at $0 charge.'
  },
  {
    planId: 'tunemind_pro_monthly',
    title: 'TuneMind Pro Monthly',
    billingPeriod: 'MONTHLY',
    trialDays: 7,
    isRecommended: false,
    globalPriceUsd: '$4.99 / month',
    eastAfricaPriceKes: 'KES 250 / month',
    savingsBadge: '7 Days Free',
    description: '7-day free trial, then billed monthly. Cancel anytime in Google Play.'
  }
];

export class SubscriptionEntitlementStore {
  private static subsByUser = new Map<string, StoredSubscriptionRecord>();
  private static usageByUser = new Map<string, DailyUsageBucket>();
  private static tokenHashToUser = new Map<string, string>();
  private static diskLoaded = false;

  private static getStoreFilePath(): string {
    return path.resolve(process.cwd(), 'data', 'billing_entitlements.json');
  }

  private static ensureDiskLoaded(): void {
    if (this.diskLoaded) return;
    this.diskLoaded = true;
    try {
      const file = this.getStoreFilePath();
      if (!fs.existsSync(file)) return;
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (Array.isArray(parsed?.subscriptions)) {
        for (const row of parsed.subscriptions) {
          if (row && typeof row.userId === 'string') {
            this.subsByUser.set(row.userId, row);
            if (row.purchaseTokenHash) {
              this.tokenHashToUser.set(String(row.purchaseTokenHash), row.userId);
            }
          }
        }
      }
    } catch {
      // Non-fatal local store bootstrap
    }
  }

  private static persistToDisk(): void {
    try {
      const file = this.getStoreFilePath();
      const dir = path.dirname(file);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      const payload = {
        subscriptions: Array.from(this.subsByUser.values()).slice(-2000)
      };
      fs.writeFileSync(file, JSON.stringify(payload, null, 2), 'utf8');
    } catch {
      // Non-fatal
    }
  }

  public static normalizeUserId(userId?: string | null): string {
    const clean = String(userId || '').trim();
    return clean || 'tunemind-default-user';
  }

  public static hashToken(rawToken: string): string {
    return crypto.createHash('sha256').update(rawToken.trim()).digest('hex');
  }

  public static todayUtcDateKey(): string {
    return new Date().toISOString().slice(0, 10);
  }

  public static isPaywallEnforced(): boolean {
    const flag = (process.env.ENFORCE_PRO_PAYWALL || 'true').trim().toLowerCase();
    return flag !== 'false' && flag !== '0';
  }

  private static resolveLimitsForTier(tier: SubscriptionTier): { agentLimit: number; forYouLimit: number } {
    switch (tier) {
      case 'PRO':
        return { agentLimit: 120, forYouLimit: 5 };
      case 'TRIAL':
        return { agentLimit: 20, forYouLimit: 2 };
      default:
        return { agentLimit: 0, forYouLimit: 0 };
    }
  }

  private static getUsageBucket(userId: string): DailyUsageBucket {
    const dateKey = this.todayUtcDateKey();
    const existing = this.usageByUser.get(userId);
    if (existing && existing.dateKey === dateKey) return existing;
    const fresh: DailyUsageBucket = { dateKey, agentUsed: 0, forYouUsed: 0 };
    this.usageByUser.set(userId, fresh);
    return fresh;
  }

  private static async loadSubscriptionRecord(userId: string): Promise<StoredSubscriptionRecord> {
    this.ensureDiskLoaded();
    const cleanId = this.normalizeUserId(userId);

    const sb = getLiveSupabaseClient();
    if (sb) {
      try {
        const { data, error } = await sb
          .from('user_subscriptions')
          .select('*')
          .eq('user_id', cleanId)
          .maybeSingle();

        if (!error && data) {
          const rec: StoredSubscriptionRecord = {
            userId: cleanId,
            tier: (data.tier as SubscriptionTier) || 'FREE',
            planId: (data.plan_id as SubscriptionPlanId | null) || null,
            billingSource: (data.billing_source as 'google_play' | 'none') || 'none',
            trialStartedAtMs: data.trial_started_at ? new Date(data.trial_started_at).getTime() : null,
            trialEndsAtMs: data.trial_ends_at ? new Date(data.trial_ends_at).getTime() : null,
            currentPeriodEndMs: data.current_period_end ? new Date(data.current_period_end).getTime() : null,
            autoRenewing: Boolean(data.auto_renewing),
            purchaseTokenHash: data.purchase_token_hash ? String(data.purchase_token_hash) : null,
            verificationMode: String(data.verification_mode || 'supabase'),
            updatedAtMs: data.updated_at ? new Date(data.updated_at).getTime() : Date.now()
          };
          this.subsByUser.set(cleanId, rec);
          if (rec.purchaseTokenHash) {
            this.tokenHashToUser.set(rec.purchaseTokenHash, cleanId);
          }
          return rec;
        }
      } catch {
        // Fallback to local memory/disk store
      }
    }

    const mem = this.subsByUser.get(cleanId);
    if (mem) return mem;

    return {
      userId: cleanId,
      tier: 'FREE',
      planId: null,
      billingSource: 'none',
      trialStartedAtMs: null,
      trialEndsAtMs: null,
      currentPeriodEndMs: null,
      autoRenewing: false,
      purchaseTokenHash: null,
      verificationMode: 'default_free',
      updatedAtMs: Date.now()
    };
  }

  private static async saveSubscriptionRecord(rec: StoredSubscriptionRecord): Promise<void> {
    this.ensureDiskLoaded();
    this.subsByUser.set(rec.userId, rec);
    if (rec.purchaseTokenHash) {
      this.tokenHashToUser.set(rec.purchaseTokenHash, rec.userId);
    }
    this.persistToDisk();

    const sb = getLiveSupabaseClient();
    if (sb) {
      try {
        await sb.from('user_subscriptions').upsert(
          {
            user_id: rec.userId,
            tier: rec.tier,
            plan_id: rec.planId,
            billing_source: rec.billingSource,
            trial_started_at: rec.trialStartedAtMs ? new Date(rec.trialStartedAtMs).toISOString() : null,
            trial_ends_at: rec.trialEndsAtMs ? new Date(rec.trialEndsAtMs).toISOString() : null,
            current_period_end: rec.currentPeriodEndMs ? new Date(rec.currentPeriodEndMs).toISOString() : null,
            auto_renewing: rec.autoRenewing,
            purchase_token_hash: rec.purchaseTokenHash,
            verification_mode: rec.verificationMode,
            updated_at: new Date(rec.updatedAtMs).toISOString()
          },
          { onConflict: 'user_id' }
        );
      } catch {
        // Safe fallback if SQL migration hasn't run yet
      }
    }
  }

  private static evaluateEffectiveTier(rec: StoredSubscriptionRecord, nowMs = Date.now()): SubscriptionTier {
    if (rec.tier === 'FREE') return 'FREE';
    const activeEnd = rec.currentPeriodEndMs ?? rec.trialEndsAtMs ?? 0;
    if (activeEnd > 0 && nowMs > activeEnd) {
      return 'FREE';
    }
    if (rec.tier === 'TRIAL') {
      if (rec.trialEndsAtMs && nowMs <= rec.trialEndsAtMs) return 'TRIAL';
      if (rec.currentPeriodEndMs && nowMs <= rec.currentPeriodEndMs && rec.autoRenewing) return 'PRO';
      return 'FREE';
    }
    return 'PRO';
  }

  public static async getEntitlement(userId: string): Promise<UserEntitlementSnapshot> {
    const cleanId = this.normalizeUserId(userId);
    const rec = await this.loadSubscriptionRecord(cleanId);
    const evaluatedTier = this.evaluateEffectiveTier(rec);
    const paywallEnforced = this.isPaywallEnforced();
    const effectiveTier: SubscriptionTier =
      !paywallEnforced && evaluatedTier === 'FREE' ? 'PRO' : evaluatedTier;
    const limits = this.resolveLimitsForTier(effectiveTier);
    const usage = this.getUsageBucket(cleanId);
    const nowMs = Date.now();
    const trialRemainingMs = rec.trialEndsAtMs ? Math.max(0, rec.trialEndsAtMs - nowMs) : 0;
    const trialDaysRemaining = effectiveTier === 'TRIAL' ? Math.max(1, Math.ceil(trialRemainingMs / DAY_MS)) : 0;

    return {
      userId: cleanId,
      tier: effectiveTier,
      hasProAccess: effectiveTier === 'PRO' || effectiveTier === 'TRIAL',
      isTrial: effectiveTier === 'TRIAL',
      planId: effectiveTier === 'FREE' ? null : rec.planId,
      billingSource: evaluatedTier === 'FREE' ? 'none' : rec.billingSource,
      trialEndsAtMs: rec.trialEndsAtMs,
      currentPeriodEndMs: rec.currentPeriodEndMs,
      trialDaysRemaining,
      autoRenewing: rec.autoRenewing,
      verificationMode: !paywallEnforced && evaluatedTier === 'FREE' ? 'paywall_disabled' : rec.verificationMode,
      dailyAgentLimit: limits.agentLimit,
      dailyAgentUsed: usage.agentUsed,
      dailyAgentRemaining: Math.max(0, limits.agentLimit - usage.agentUsed),
      dailyForYouRefreshLimit: limits.forYouLimit,
      dailyForYouRefreshesUsed: usage.forYouUsed,
      freeForYouDailyPicks: 2,
      updatedAtMs: rec.updatedAtMs
    };
  }

  public static async checkAndConsumeAiQuota(
    userId: string,
    feature: AiFeatureKind,
    options?: { consume?: boolean }
  ): Promise<QuotaCheckResult> {
    const cleanId = this.normalizeUserId(userId);
    const ent = await this.getEntitlement(cleanId);

    if (!this.isPaywallEnforced()) {
      return { allowed: true, entitlement: ent };
    }

    if (!ent.hasProAccess) {
      return {
        allowed: false,
        entitlement: ent,
        code: 'PRO_TRIAL_REQUIRED',
        message:
          feature === 'agent'
            ? 'Start your 7-day free trial of TuneMind Pro to unlock conversational AI music curation.'
            : 'Enjoy 2 free picks daily, or start your 7-day free trial for all 15 personalized picks and refreshes.'
      };
    }

    const usage = this.getUsageBucket(cleanId);
    if (feature === 'agent') {
      if (usage.agentUsed >= ent.dailyAgentLimit) {
        return {
          allowed: false,
          entitlement: ent,
          code: ent.isTrial ? 'TRIAL_QUOTA_REACHED' : 'PRO_FAIR_USE_REACHED',
          message: ent.isTrial
            ? 'Daily trial AI chat limit reached (20/day). Resets tomorrow — basic playback controls still work anytime.'
            : 'Daily AI fair-use limit reached for today. Resets tomorrow.'
        };
      }
      if (options?.consume !== false) {
        usage.agentUsed += 1;
      }
    } else {
      if (usage.forYouUsed >= ent.dailyForYouRefreshLimit) {
        return {
          allowed: false,
          entitlement: ent,
          code: ent.isTrial ? 'TRIAL_QUOTA_REACHED' : 'PRO_FAIR_USE_REACHED',
          message: ent.isTrial
            ? 'You have used today\'s trial For You drops. Your next drop refreshes tomorrow at 12:00 PM.'
            : 'Daily For You refresh limit reached for today.'
        };
      }
      if (options?.consume !== false) {
        usage.forYouUsed += 1;
      }
    }

    const updated = await this.getEntitlement(cleanId);
    return { allowed: true, entitlement: updated };
  }

  /**
   * Fetches a short-lived Google Play AndroidPublisher OAuth access token via RSA-SHA256 JWT assertion
   * when GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is provided. Zero extra npm packages required.
   */
  private static async getGooglePlayAccessToken(serviceAccountJsonRaw: string): Promise<string | null> {
    try {
      const creds = JSON.parse(serviceAccountJsonRaw);
      const clientEmail = String(creds?.client_email || '').trim();
      const privateKey = String(creds?.private_key || '').trim();
      if (!clientEmail || !privateKey) return null;

      const nowSec = Math.floor(Date.now() / 1000);
      const headerB64 = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
      const claimB64 = Buffer.from(
        JSON.stringify({
          iss: clientEmail,
          scope: 'https://www.googleapis.com/auth/androidpublisher',
          aud: 'https://oauth2.googleapis.com/token',
          iat: nowSec,
          exp: nowSec + 3600
        })
      ).toString('base64url');
      const signingInput = `${headerB64}.${claimB64}`;
      const signer = crypto.createSign('RSA-SHA256');
      signer.update(signingInput);
      const sigB64 = signer.sign(privateKey, 'base64url');
      const jwt = `${signingInput}.${sigB64}`;

      const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
          assertion: jwt
        })
      });
      if (!tokenRes.ok) return null;
      const body: any = await tokenRes.json();
      return typeof body?.access_token === 'string' ? body.access_token : null;
    } catch {
      return null;
    }
  }

  public static async verifyGooglePlayPurchase(input: VerifyGooglePlayInput): Promise<{
    verified: boolean;
    entitlement: UserEntitlementSnapshot;
    error?: string;
  }> {
    const cleanUserId = this.normalizeUserId(input.userId);
    const pkg = (input.packageName || PACKAGE_NAME).trim();
    const productId = String(input.productId || '').trim() as SubscriptionPlanId;
    const purchaseToken = String(input.purchaseToken || '').trim();

    if (pkg !== PACKAGE_NAME) {
      return {
        verified: false,
        entitlement: await this.getEntitlement(cleanUserId),
        error: 'Invalid packageName'
      };
    }
    if (productId !== 'tunemind_pro_annual' && productId !== 'tunemind_pro_monthly') {
      return {
        verified: false,
        entitlement: await this.getEntitlement(cleanUserId),
        error: 'Unsupported subscription productId'
      };
    }
    if (purchaseToken.length < 16) {
      return {
        verified: false,
        entitlement: await this.getEntitlement(cleanUserId),
        error: 'Invalid purchaseToken'
      };
    }

    const tokenHash = this.hashToken(purchaseToken);
    const nowMs = Date.now();
    const saRaw = (process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON || '').trim();

    if (saRaw.startsWith('{')) {
      const oauthToken = await this.getGooglePlayAccessToken(saRaw);
      if (oauthToken) {
        try {
          const apiUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(
            PACKAGE_NAME
          )}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
          const verifyRes = await fetch(apiUrl, {
            method: 'GET',
            headers: { Authorization: `Bearer ${oauthToken}` }
          });

          if (!verifyRes.ok) {
            return {
              verified: false,
              entitlement: await this.getEntitlement(cleanUserId),
              error: `Google Play verification failed (${verifyRes.status})`
            };
          }

          const subInfo: any = await verifyRes.json();
          const state = String(subInfo?.subscriptionState || '');
          const isActiveState =
            state === 'SUBSCRIPTION_STATE_ACTIVE' ||
            state === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD';

          if (!isActiveState) {
            await this.saveSubscriptionRecord({
              userId: cleanUserId,
              tier: 'FREE',
              planId: null,
              billingSource: 'google_play',
              trialStartedAtMs: null,
              trialEndsAtMs: null,
              currentPeriodEndMs: null,
              autoRenewing: false,
              purchaseTokenHash: tokenHash,
              verificationMode: `google_play_${state.toLowerCase()}`,
              updatedAtMs: nowMs
            });
            return {
              verified: false,
              entitlement: await this.getEntitlement(cleanUserId),
              error: `Subscription not active (${state})`
            };
          }

          const lineItem = Array.isArray(subInfo?.lineItems) ? subInfo.lineItems[0] : null;
          const expiryIso = lineItem?.expiryTime ? String(lineItem.expiryTime) : null;
          const expiryMs = expiryIso ? new Date(expiryIso).getTime() : nowMs + 7 * DAY_MS;
          const offerTags: string[] = Array.isArray(lineItem?.offerDetails?.offerTags)
            ? lineItem.offerDetails.offerTags
            : [];
          const isFreeTrialPhase =
            Boolean(subInfo?.testPurchase) ||
            offerTags.some((t) => String(t).toLowerCase().includes('trial')) ||
            expiryMs - nowMs <= 8 * DAY_MS;
          const autoRenew = Boolean(lineItem?.autoRenewingPlan?.autoRenewEnabled);

          await this.saveSubscriptionRecord({
            userId: cleanUserId,
            tier: isFreeTrialPhase ? 'TRIAL' : 'PRO',
            planId: productId,
            billingSource: 'google_play',
            trialStartedAtMs: isFreeTrialPhase ? nowMs : null,
            trialEndsAtMs: isFreeTrialPhase ? expiryMs : null,
            currentPeriodEndMs: expiryMs,
            autoRenewing: autoRenew,
            purchaseTokenHash: tokenHash,
            verificationMode: 'google_play_v3_verified',
            updatedAtMs: nowMs
          });

          return {
            verified: true,
            entitlement: await this.getEntitlement(cleanUserId)
          };
        } catch (err: any) {
          return {
            verified: false,
            entitlement: await this.getEntitlement(cleanUserId),
            error: err?.message || 'Google Play network verification failed'
          };
        }
      }
    }

    if (process.env.NODE_ENV === 'production') {
      return {
        verified: false,
        entitlement: await this.getEntitlement(cleanUserId),
        error: 'Google Play billing verification is not configured on the server.'
      };
    }

    // Staging / Pre-Service-Account mode (non-production only): records Play Store purchase with 7-day trial window
    const periodMs = 7 * DAY_MS;
    await this.saveSubscriptionRecord({
      userId: cleanUserId,
      tier: 'TRIAL',
      planId: productId,
      billingSource: 'google_play',
      trialStartedAtMs: nowMs,
      trialEndsAtMs: nowMs + periodMs,
      currentPeriodEndMs: nowMs + periodMs,
      autoRenewing: true,
      purchaseTokenHash: tokenHash,
      verificationMode: 'play_token_recorded_pending_service_account',
      updatedAtMs: nowMs
    });

    return {
      verified: true,
      entitlement: await this.getEntitlement(cleanUserId)
    };
  }

  public static async handlePlayRtdnNotification(rawNotification: any): Promise<{
    handled: boolean;
    notificationType?: number;
  }> {
    try {
      const subNotif = rawNotification?.subscriptionNotification;
      if (!subNotif) return { handled: false };
      const notificationType = Number(subNotif.notificationType || 0);
      const purchaseToken = String(subNotif.purchaseToken || '').trim();
      const productId = String(subNotif.subscriptionId || 'tunemind_pro_annual') as SubscriptionPlanId;
      if (!purchaseToken) return { handled: false };

      this.ensureDiskLoaded();
      const tokenHash = this.hashToken(purchaseToken);
      const mappedUserId = this.tokenHashToUser.get(tokenHash);
      if (!mappedUserId) {
        return { handled: false, notificationType };
      }

      // Expired (13), Revoked (12), On Hold (5), Paused (10)
      if ([5, 10, 12, 13].includes(notificationType)) {
        await this.saveSubscriptionRecord({
          userId: mappedUserId,
          tier: 'FREE',
          planId: null,
          billingSource: 'google_play',
          trialStartedAtMs: null,
          trialEndsAtMs: null,
          currentPeriodEndMs: null,
          autoRenewing: false,
          purchaseTokenHash: tokenHash,
          verificationMode: `rtdn_revoked_${notificationType}`,
          updatedAtMs: Date.now()
        });
        return { handled: true, notificationType };
      }

      // Purchased (4), Renewed (2), Recovered (1), Restarted (7) -> re-verify via Play API
      await this.verifyGooglePlayPurchase({
        userId: mappedUserId,
        productId,
        purchaseToken,
        packageName: PACKAGE_NAME
      });
      return { handled: true, notificationType };
    } catch {
      return { handled: false };
    }
  }
}
