-- =============================================================================
-- TuneMind Profiles, Auth Trigger & Intelligence Schema (Safe Idempotent Migration)
-- =============================================================================
-- Safe to run on any existing Supabase database: creates tables if missing AND
-- adds any missing columns via ALTER TABLE ... ADD COLUMN IF NOT EXISTS before indexing.

-- 1. User Account Profiles Table (linked to Supabase auth.users via Google / Email)
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL DEFAULT '',
    display_name TEXT NOT NULL DEFAULT 'TuneMind Listener',
    avatar_url TEXT NOT NULL DEFAULT '',
    primary_provider TEXT NOT NULL DEFAULT 'EMAIL', -- 'GOOGLE' | 'EMAIL'
    google_connected BOOLEAN NOT NULL DEFAULT FALSE,
    spotify_connected BOOLEAN NOT NULL DEFAULT FALSE, -- Connected music data source
    private_session_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    merge_duplicate_plays BOOLEAN NOT NULL DEFAULT TRUE,
    preferred_target_player TEXT NOT NULL DEFAULT '',
    taste_archetype TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS email TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT 'TuneMind Listener',
    ADD COLUMN IF NOT EXISTS avatar_url TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS primary_provider TEXT NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN IF NOT EXISTS google_connected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spotify_connected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS private_session_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS merge_duplicate_plays BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS preferred_target_player TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS taste_archetype TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_profiles_email
    ON public.profiles (LOWER(email));

CREATE OR REPLACE FUNCTION public.handle_profiles_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_profiles_updated_at ON public.profiles;
CREATE TRIGGER on_profiles_updated_at
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_profiles_updated_at();

CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    provider_raw TEXT;
    provider_clean TEXT;
    resolved_name TEXT;
    resolved_avatar TEXT;
BEGIN
    provider_raw := COALESCE(
        NEW.raw_app_meta_data ->> 'provider',
        'email'
    );

    provider_clean := CASE LOWER(provider_raw)
        WHEN 'google' THEN 'GOOGLE'
        ELSE 'EMAIL'
    END;

    resolved_name := COALESCE(
        NULLIF(TRIM(NEW.raw_user_meta_data ->> 'full_name'), ''),
        NULLIF(TRIM(NEW.raw_user_meta_data ->> 'name'), ''),
        NULLIF(SPLIT_PART(COALESCE(NEW.email, ''), '@', 1), ''),
        'TuneMind Listener'
    );

    resolved_avatar := COALESCE(
        NEW.raw_user_meta_data ->> 'avatar_url',
        NEW.raw_user_meta_data ->> 'picture',
        ''
    );

    INSERT INTO public.profiles (
        id,
        email,
        display_name,
        avatar_url,
        primary_provider,
        google_connected
    )
    VALUES (
        NEW.id,
        COALESCE(NEW.email, ''),
        resolved_name,
        resolved_avatar,
        provider_clean,
        (provider_clean = 'GOOGLE')
    )
    ON CONFLICT (id) DO UPDATE SET
        email = CASE WHEN EXCLUDED.email <> '' THEN EXCLUDED.email ELSE public.profiles.email END,
        display_name = CASE WHEN public.profiles.display_name = 'TuneMind Listener' AND EXCLUDED.display_name <> '' THEN EXCLUDED.display_name ELSE public.profiles.display_name END,
        avatar_url = CASE WHEN public.profiles.avatar_url = '' AND EXCLUDED.avatar_url <> '' THEN EXCLUDED.avatar_url ELSE public.profiles.avatar_url END,
        google_connected = public.profiles.google_connected OR EXCLUDED.google_connected;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_new_user();

INSERT INTO public.profiles (
    id,
    email,
    display_name,
    avatar_url,
    primary_provider,
    google_connected
)
SELECT
    u.id,
    COALESCE(u.email, ''),
    COALESCE(
        NULLIF(TRIM(u.raw_user_meta_data ->> 'full_name'), ''),
        NULLIF(TRIM(u.raw_user_meta_data ->> 'name'), ''),
        NULLIF(SPLIT_PART(COALESCE(u.email, ''), '@', 1), ''),
        'TuneMind Listener'
    ),
    COALESCE(u.raw_user_meta_data ->> 'avatar_url', u.raw_user_meta_data ->> 'picture', ''),
    CASE LOWER(COALESCE(u.raw_app_meta_data ->> 'provider', 'email'))
        WHEN 'google' THEN 'GOOGLE'
        ELSE 'EMAIL'
    END,
    (LOWER(COALESCE(u.raw_app_meta_data ->> 'provider', '')) = 'google')
FROM auth.users u
ON CONFLICT (id) DO NOTHING;

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'profiles'
          AND policyname = 'Users can view own profile'
    ) THEN
        CREATE POLICY "Users can view own profile"
            ON public.profiles FOR SELECT
            TO authenticated
            USING (auth.uid() = id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'profiles'
          AND policyname = 'Users can insert own profile'
    ) THEN
        CREATE POLICY "Users can insert own profile"
            ON public.profiles FOR INSERT
            TO authenticated
            WITH CHECK (auth.uid() = id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'profiles'
          AND policyname = 'Users can update own profile'
    ) THEN
        CREATE POLICY "Users can update own profile"
            ON public.profiles FOR UPDATE
            TO authenticated
            USING (auth.uid() = id)
            WITH CHECK (auth.uid() = id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'profiles'
          AND policyname = 'Users can delete own profile'
    ) THEN
        CREATE POLICY "Users can delete own profile"
            ON public.profiles FOR DELETE
            TO authenticated
            USING (auth.uid() = id);
    END IF;
END
$$;

-- 2. Agent Interventions & Conversational Memory Table
CREATE TABLE IF NOT EXISTS public.agent_interventions (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    trigger_transcript TEXT NOT NULL DEFAULT '',
    computed_satiety INTEGER NOT NULL DEFAULT 25,
    agent_tts_response TEXT NOT NULL DEFAULT '',
    os_intent TEXT NOT NULL DEFAULT 'NONE',
    target_search_query TEXT NOT NULL DEFAULT '',
    track_id TEXT,
    spotify_uri TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.agent_interventions
    ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    ADD COLUMN IF NOT EXISTS trigger_transcript TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS computed_satiety INTEGER NOT NULL DEFAULT 25,
    ADD COLUMN IF NOT EXISTS agent_tts_response TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS os_intent TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS target_search_query TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS track_id TEXT,
    ADD COLUMN IF NOT EXISTS spotify_uri TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_agent_interventions_user_created
    ON public.agent_interventions (user_id, created_at DESC);

-- 3. Synced Spotify & OS Listening Events Table
CREATE TABLE IF NOT EXISTS public.listening_events (
    event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    track_id TEXT,
    track_title TEXT NOT NULL DEFAULT 'Unknown Track',
    track_artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    spotify_uri TEXT,
    play_count INTEGER NOT NULL DEFAULT 0,
    media_source TEXT NOT NULL DEFAULT 'com.spotify.music',
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.listening_events
    ADD COLUMN IF NOT EXISTS event_id TEXT,
    ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    ADD COLUMN IF NOT EXISTS track_id TEXT,
    ADD COLUMN IF NOT EXISTS track_title TEXT NOT NULL DEFAULT 'Unknown Track',
    ADD COLUMN IF NOT EXISTS track_artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    ADD COLUMN IF NOT EXISTS spotify_uri TEXT,
    ADD COLUMN IF NOT EXISTS play_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS media_source TEXT NOT NULL DEFAULT 'com.spotify.music',
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.listening_events
    ALTER COLUMN play_count SET DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS idx_listening_events_event_id_unique
    ON public.listening_events (event_id);

CREATE INDEX IF NOT EXISTS idx_listening_events_track_id
    ON public.listening_events (track_id);

CREATE INDEX IF NOT EXISTS idx_listening_events_user_plays
    ON public.listening_events (user_id, play_count DESC);

-- 4. Evolving User Taste Genome Profile Table
CREATE TABLE IF NOT EXISTS public.user_taste_genome (
    user_id TEXT PRIMARY KEY,
    top_tracks_json TEXT NOT NULL DEFAULT '[]',
    top_artists_json TEXT NOT NULL DEFAULT '[]',
    approved_tracks_json TEXT NOT NULL DEFAULT '[]',
    rejected_tracks_json TEXT NOT NULL DEFAULT '[]',
    hearing_telemetry_json TEXT,
    vault_tracks_json TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.user_taste_genome
    ADD COLUMN IF NOT EXISTS user_id TEXT,
    ADD COLUMN IF NOT EXISTS top_tracks_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS top_artists_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS approved_tracks_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS rejected_tracks_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS hearing_telemetry_json TEXT,
    ADD COLUMN IF NOT EXISTS vault_tracks_json TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_taste_genome_user_id_unique
    ON public.user_taste_genome (user_id);

-- 5. Enable Row-Level Security & User-Scoped Policies
ALTER TABLE public.agent_interventions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listening_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_taste_genome ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow TuneMind read/write on agent_interventions" ON public.agent_interventions;
DROP POLICY IF EXISTS "Users can manage own agent_interventions" ON public.agent_interventions;
CREATE POLICY "Users can manage own agent_interventions"
    ON public.agent_interventions FOR ALL
    USING (auth.uid()::text = user_id)
    WITH CHECK (auth.uid()::text = user_id);

DROP POLICY IF EXISTS "Allow TuneMind read/write on listening_events" ON public.listening_events;
DROP POLICY IF EXISTS "Users can manage own listening_events" ON public.listening_events;
CREATE POLICY "Users can manage own listening_events"
    ON public.listening_events FOR ALL
    USING (auth.uid()::text = user_id)
    WITH CHECK (auth.uid()::text = user_id);

DROP POLICY IF EXISTS "Allow TuneMind read/write on user_taste_genome" ON public.user_taste_genome;
DROP POLICY IF EXISTS "Users can manage own user_taste_genome" ON public.user_taste_genome;
CREATE POLICY "Users can manage own user_taste_genome"
    ON public.user_taste_genome FOR ALL
    USING (auth.uid()::text = user_id)
    WITH CHECK (auth.uid()::text = user_id);

-- 6. User Support & Bug Feedback Reports Table
CREATE TABLE IF NOT EXISTS public.support_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL DEFAULT 'anonymous',
    user_email TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL DEFAULT 'Other',
    message TEXT NOT NULL,
    diagnostics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    app_version TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_support_feedback_created
    ON public.support_feedback (created_at DESC);

ALTER TABLE public.support_feedback ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can submit support feedback" ON public.support_feedback;
CREATE POLICY "Users can submit support feedback"
    ON public.support_feedback FOR INSERT
    TO authenticated, anon
    WITH CHECK (true);

DROP POLICY IF EXISTS "Users can view own support feedback" ON public.support_feedback;
CREATE POLICY "Users can view own support feedback"
    ON public.support_feedback FOR SELECT
    TO authenticated
    USING (auth.uid()::text = user_id);

-- 7. Self-Learning Discovery: Per-User Contextual Genre/Time/Volume Taste Models
CREATE TABLE IF NOT EXISTS public.ml_user_taste_models (
    user_id TEXT PRIMARY KEY,
    learning_iterations INTEGER NOT NULL DEFAULT 0,
    evaluated_outcomes INTEGER NOT NULL DEFAULT 0,
    positive_outcomes INTEGER NOT NULL DEFAULT 0,
    genre_weights JSONB NOT NULL DEFAULT '{}'::jsonb,
    time_slot_affinity JSONB NOT NULL DEFAULT '{}'::jsonb,
    volume_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.ml_user_taste_models
    ADD COLUMN IF NOT EXISTS user_id TEXT,
    ADD COLUMN IF NOT EXISTS learning_iterations INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS evaluated_outcomes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS positive_outcomes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS genre_weights JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS time_slot_affinity JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS volume_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS idx_ml_user_taste_models_user_id
    ON public.ml_user_taste_models (user_id);

ALTER TABLE public.ml_user_taste_models ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own ml_user_taste_models" ON public.ml_user_taste_models;
CREATE POLICY "Users can manage own ml_user_taste_models"
    ON public.ml_user_taste_models FOR ALL
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- 8. Self-Learning Discovery: Daily Drop Slate & Recommendation Outcomes
CREATE TABLE IF NOT EXISTS public.discovery_recommendation_outcomes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    drop_date TEXT NOT NULL,
    track_id TEXT,
    track_title TEXT NOT NULL,
    artist TEXT NOT NULL,
    spotify_uri TEXT,
    genre TEXT NOT NULL DEFAULT 'Discovery',
    sub_tier TEXT NOT NULL DEFAULT 'GENRE_PICK',
    outcome_status TEXT NOT NULL DEFAULT 'PENDING',
    completion_ratio DOUBLE PRECISION DEFAULT 0,
    volume_ratio DOUBLE PRECISION DEFAULT 0,
    time_slot TEXT DEFAULT 'Evening',
    listened_ms BIGINT DEFAULT 0,
    track_duration_ms BIGINT DEFAULT 0,
    reward_score DOUBLE PRECISION DEFAULT 0,
    reason TEXT DEFAULT '',
    confidence DOUBLE PRECISION DEFAULT 0.9,
    preview_url TEXT,
    album_art_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.discovery_recommendation_outcomes
    ADD COLUMN IF NOT EXISTS user_id TEXT,
    ADD COLUMN IF NOT EXISTS drop_date TEXT,
    ADD COLUMN IF NOT EXISTS track_id TEXT,
    ADD COLUMN IF NOT EXISTS track_title TEXT,
    ADD COLUMN IF NOT EXISTS artist TEXT,
    ADD COLUMN IF NOT EXISTS spotify_uri TEXT,
    ADD COLUMN IF NOT EXISTS genre TEXT NOT NULL DEFAULT 'Discovery',
    ADD COLUMN IF NOT EXISTS sub_tier TEXT NOT NULL DEFAULT 'GENRE_PICK',
    ADD COLUMN IF NOT EXISTS outcome_status TEXT NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS completion_ratio DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS volume_ratio DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS time_slot TEXT DEFAULT 'Evening',
    ADD COLUMN IF NOT EXISTS listened_ms BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS track_duration_ms BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reward_score DOUBLE PRECISION DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reason TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION DEFAULT 0.9,
    ADD COLUMN IF NOT EXISTS preview_url TEXT,
    ADD COLUMN IF NOT EXISTS album_art_url TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_discovery_outcomes_user_date
    ON public.discovery_recommendation_outcomes (user_id, drop_date DESC, updated_at DESC);

ALTER TABLE public.discovery_recommendation_outcomes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own discovery_recommendation_outcomes" ON public.discovery_recommendation_outcomes;
CREATE POLICY "Users can manage own discovery_recommendation_outcomes"
    ON public.discovery_recommendation_outcomes FOR ALL
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));


