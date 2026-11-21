-- TuneMind Subscription & Entitlement Schema (Supabase PostgreSQL)
-- Stores verified Google Play Billing entitlements without ever storing raw sensitive tokens in logs.

CREATE TABLE IF NOT EXISTS public.user_subscriptions (
  user_id TEXT PRIMARY KEY,
  tier TEXT NOT NULL DEFAULT 'FREE' CHECK (tier IN ('FREE', 'TRIAL', 'PRO')),
  plan_id TEXT NULL,
  billing_source TEXT NOT NULL DEFAULT 'none' CHECK (billing_source IN ('none', 'google_play')),
  trial_started_at TIMESTAMPTZ NULL,
  trial_ends_at TIMESTAMPTZ NULL,
  current_period_end TIMESTAMPTZ NULL,
  auto_renewing BOOLEAN NOT NULL DEFAULT FALSE,
  purchase_token_hash TEXT NULL,
  verification_mode TEXT NOT NULL DEFAULT 'default_free',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_token_hash
  ON public.user_subscriptions (purchase_token_hash);

ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Service role full access to user_subscriptions" ON public.user_subscriptions;
CREATE POLICY "Service role full access to user_subscriptions"
  ON public.user_subscriptions
  FOR ALL
  USING (auth.role() = 'service_role')
  WITH CHECK (auth.role() = 'service_role');

DROP POLICY IF EXISTS "Users can read own subscription status" ON public.user_subscriptions;
CREATE POLICY "Users can read own subscription status"
  ON public.user_subscriptions
  FOR SELECT
  USING (auth.uid()::text = user_id);
