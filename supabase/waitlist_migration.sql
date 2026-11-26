-- TuneMind Public Waitlist Signups Table
-- Run this once in your Supabase SQL Editor (Project -> SQL Editor -> New Query -> Run).

CREATE TABLE IF NOT EXISTS public.waitlist_signups (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT NOT NULL UNIQUE,
  preferred_player TEXT NOT NULL DEFAULT 'Spotify',
  wants_closed_beta BOOLEAN NOT NULL DEFAULT FALSE,
  referral_source TEXT NOT NULL DEFAULT 'direct',
  user_agent TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_waitlist_signups_created_at
  ON public.waitlist_signups (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_waitlist_signups_referral_source
  ON public.waitlist_signups (referral_source);

-- Keep subscriber emails strictly private: only backend service_role can read/write.
ALTER TABLE public.waitlist_signups ENABLE ROW LEVEL SECURITY;
