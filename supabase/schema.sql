-- =============================================================================
-- TuneMind Production Database Schema (Idempotent + Scale Indexes + Strict RLS)
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ─── Shared Triggers & Helpers ────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.handle_profiles_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- ─── 1. User Profiles (Linked to Supabase auth.users via Google / Email) ──────
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL DEFAULT '',
    display_name TEXT NOT NULL DEFAULT 'TuneMind Listener',
    avatar_url TEXT NOT NULL DEFAULT '',
    primary_provider TEXT NOT NULL DEFAULT 'EMAIL',
    google_connected BOOLEAN NOT NULL DEFAULT FALSE,
    spotify_connected BOOLEAN NOT NULL DEFAULT FALSE,
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
    provider_raw := COALESCE(NEW.raw_app_meta_data ->> 'provider', 'email');
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
EXCEPTION
    WHEN OTHERS THEN
        RAISE WARNING 'handle_new_user profile sync skipped for user %: %', NEW.id, SQLERRM;
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

DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
CREATE POLICY "Users can view own profile"
    ON public.profiles FOR SELECT
    TO authenticated
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
CREATE POLICY "Users can insert own profile"
    ON public.profiles FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
CREATE POLICY "Users can update own profile"
    ON public.profiles FOR UPDATE
    TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "Users can delete own profile" ON public.profiles;
CREATE POLICY "Users can delete own profile"
    ON public.profiles FOR DELETE
    TO authenticated
    USING (auth.uid() = id);

-- ─── 2. Agent Interventions & Conversational Memory ───────────────────────────
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

ALTER TABLE public.agent_interventions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow TuneMind read/write on agent_interventions" ON public.agent_interventions;
DROP POLICY IF EXISTS "Users can manage own agent_interventions" ON public.agent_interventions;
CREATE POLICY "Users can manage own agent_interventions"
    ON public.agent_interventions FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 3. Synced Cross-App Listening Events & Permanent User Library ───────────
CREATE TABLE IF NOT EXISTS public.listening_events (
    event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    track_id TEXT,
    track_title TEXT NOT NULL DEFAULT 'Unknown Track',
    track_artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    spotify_uri TEXT,
    play_count INTEGER NOT NULL DEFAULT 0,
    media_source TEXT NOT NULL DEFAULT 'unknown_player',
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
    ADD COLUMN IF NOT EXISTS media_source TEXT NOT NULL DEFAULT 'unknown_player',
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.listening_events
    ALTER COLUMN play_count SET DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS idx_listening_events_event_id_unique
    ON public.listening_events (event_id);

CREATE INDEX IF NOT EXISTS idx_listening_events_track_id
    ON public.listening_events (track_id);

CREATE INDEX IF NOT EXISTS idx_listening_events_user_plays
    ON public.listening_events (user_id, play_count DESC);

CREATE INDEX IF NOT EXISTS idx_listening_events_user_observed
    ON public.listening_events (user_id, observed_at DESC);

ALTER TABLE public.listening_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow TuneMind read/write on listening_events" ON public.listening_events;
DROP POLICY IF EXISTS "Users can manage own listening_events" ON public.listening_events;
CREATE POLICY "Users can manage own listening_events"
    ON public.listening_events FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 4. Evolving User Taste Genome ────────────────────────────────────────────
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

ALTER TABLE public.user_taste_genome ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow TuneMind read/write on user_taste_genome" ON public.user_taste_genome;
DROP POLICY IF EXISTS "Users can manage own user_taste_genome" ON public.user_taste_genome;
CREATE POLICY "Users can manage own user_taste_genome"
    ON public.user_taste_genome FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 5. User Support & Bug Feedback Reports ───────────────────────────────────
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

ALTER TABLE public.support_feedback
    ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'anonymous',
    ADD COLUMN IF NOT EXISTS user_email TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS category TEXT NOT NULL DEFAULT 'Other',
    ADD COLUMN IF NOT EXISTS message TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS diagnostics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS app_version TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_support_feedback_created
    ON public.support_feedback (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_feedback_user_created
    ON public.support_feedback (user_id, created_at DESC);

ALTER TABLE public.support_feedback ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can submit support feedback" ON public.support_feedback;
CREATE POLICY "Users can submit support feedback"
    ON public.support_feedback FOR INSERT
    TO authenticated, anon
    WITH CHECK (char_length(message) BETWEEN 1 AND 5000);

DROP POLICY IF EXISTS "Users can view own support feedback" ON public.support_feedback;
CREATE POLICY "Users can view own support feedback"
    ON public.support_feedback FOR SELECT
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 6. Self-Learning Discovery: Per-User Contextual Taste Weights ────────────
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
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 7. Self-Learning Discovery: Daily Drop Slate & Outcomes ──────────────────
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

CREATE INDEX IF NOT EXISTS idx_discovery_outcomes_lookup
    ON public.discovery_recommendation_outcomes (user_id, drop_date, LOWER(track_title));

ALTER TABLE public.discovery_recommendation_outcomes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own discovery_recommendation_outcomes" ON public.discovery_recommendation_outcomes;
CREATE POLICY "Users can manage own discovery_recommendation_outcomes"
    ON public.discovery_recommendation_outcomes FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 8. Shared Acoustic Telemetry Cache ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.acoustic_telemetry (
    track_id TEXT PRIMARY KEY,
    bpm INTEGER,
    energy DOUBLE PRECISION,
    musical_key TEXT,
    valence DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.acoustic_telemetry ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Authenticated users can read acoustic_telemetry" ON public.acoustic_telemetry;
CREATE POLICY "Authenticated users can read acoustic_telemetry"
    ON public.acoustic_telemetry FOR SELECT
    TO authenticated
    USING (true);

-- ─── 9. Permanent Cross-App Track Library (All Players, Zero Pruning) ────────
CREATE TABLE IF NOT EXISTS public.cross_app_track_library (
    user_id TEXT NOT NULL,
    canonical_track_key TEXT NOT NULL,
    track_id TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT DEFAULT '',
    genre TEXT DEFAULT 'Music',
    duration_ms BIGINT DEFAULT 0,
    primary_source_package TEXT NOT NULL DEFAULT 'unknown_player',
    source_packages_csv TEXT NOT NULL DEFAULT '',
    play_count INTEGER NOT NULL DEFAULT 0,
    total_listened_ms BIGINT NOT NULL DEFAULT 0,
    first_played_at BIGINT NOT NULL DEFAULT 0,
    last_played_at BIGINT NOT NULL DEFAULT 0,
    is_liked BOOLEAN NOT NULL DEFAULT FALSE,
    is_in_vault BOOLEAN NOT NULL DEFAULT FALSE,
    album_art_url TEXT,
    preview_url TEXT,
    audius_stream_url TEXT,
    ytm_video_id TEXT,
    deezer_url TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, canonical_track_key)
);

ALTER TABLE public.cross_app_track_library
    ADD COLUMN IF NOT EXISTS user_id TEXT,
    ADD COLUMN IF NOT EXISTS canonical_track_key TEXT,
    ADD COLUMN IF NOT EXISTS track_id TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    ADD COLUMN IF NOT EXISTS album TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS genre TEXT DEFAULT 'Music',
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS primary_source_package TEXT NOT NULL DEFAULT 'unknown_player',
    ADD COLUMN IF NOT EXISTS source_packages_csv TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS play_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_listened_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS first_played_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_played_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS is_liked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_in_vault BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS album_art_url TEXT,
    ADD COLUMN IF NOT EXISTS preview_url TEXT,
    ADD COLUMN IF NOT EXISTS audius_stream_url TEXT,
    ADD COLUMN IF NOT EXISTS ytm_video_id TEXT,
    ADD COLUMN IF NOT EXISTS deezer_url TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_cross_app_library_user_recent
    ON public.cross_app_track_library (user_id, last_played_at DESC);

CREATE INDEX IF NOT EXISTS idx_cross_app_library_user_plays
    ON public.cross_app_track_library (user_id, play_count DESC);

ALTER TABLE public.cross_app_track_library ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own cross_app_track_library" ON public.cross_app_track_library;
CREATE POLICY "Users can manage own cross_app_track_library"
    ON public.cross_app_track_library FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

-- ─── 10. Encrypted Spotify PKCE Cloud Persistence (Never Reconnect Again) ─────
CREATE TABLE IF NOT EXISTS public.user_spotify_connections (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    spotify_client_id TEXT NOT NULL DEFAULT '',
    encrypted_refresh_token TEXT NOT NULL,
    spotify_display_name TEXT NOT NULL DEFAULT '',
    spotify_product TEXT NOT NULL DEFAULT '',
    last_device_name TEXT NOT NULL DEFAULT '',
    connected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.user_spotify_connections
    ADD COLUMN IF NOT EXISTS spotify_client_id TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS encrypted_refresh_token TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS spotify_display_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS spotify_product TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS last_device_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS connected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DROP TRIGGER IF EXISTS on_user_spotify_connections_updated_at ON public.user_spotify_connections;
CREATE TRIGGER on_user_spotify_connections_updated_at
    BEFORE UPDATE ON public.user_spotify_connections
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_profiles_updated_at();

ALTER TABLE public.user_spotify_connections ENABLE ROW LEVEL SECURITY;

-- Strict security: encrypted refresh tokens are NEVER directly readable via client anon keys.
-- Only the TuneMind backend (using SUPABASE_SERVICE_ROLE_KEY after verifying the user's JWT)
-- reads/writes encrypted rows. Authenticated owners may still delete their own row directly.
DROP POLICY IF EXISTS "Users can delete own spotify connection" ON public.user_spotify_connections;
CREATE POLICY "Users can delete own spotify connection"
    ON public.user_spotify_connections FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ─── 11. Permanent Relational Track Cards, Verified Play Ledger & Playlists ────
-- Every song TuneMind encounters across ANY player (Spotify, YouTube Music, Boomplay,
-- Audiomack, Local Audio, or TuneMind) is stored ONCE as a canonical Track Card.
-- Play counts are derived strictly from permanent `track_play_events` via trigger so
-- every Track Card across Library, History, and Playlists displays 100% accurate plays.

CREATE TABLE IF NOT EXISTS public.track_cards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    canonical_track_key TEXT NOT NULL,
    track_id TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    artist TEXT NOT NULL DEFAULT '',
    album TEXT NOT NULL DEFAULT '',
    genre TEXT NOT NULL DEFAULT 'Music',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    spotify_uri TEXT,
    ytm_video_id TEXT,
    album_art_url TEXT,
    preview_url TEXT,
    primary_source_package TEXT NOT NULL DEFAULT 'unknown_player',
    source_packages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    verified_play_count INTEGER NOT NULL DEFAULT 0,
    total_listened_ms BIGINT NOT NULL DEFAULT 0,
    first_played_at TIMESTAMPTZ,
    last_played_at TIMESTAMPTZ,
    is_liked BOOLEAN NOT NULL DEFAULT FALSE,
    is_in_vault BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, canonical_track_key)
);

CREATE INDEX IF NOT EXISTS idx_track_cards_user_plays
    ON public.track_cards (user_id, verified_play_count DESC);

CREATE INDEX IF NOT EXISTS idx_track_cards_user_recent
    ON public.track_cards (user_id, last_played_at DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_track_cards_user_liked
    ON public.track_cards (user_id, is_liked) WHERE is_liked = TRUE;

CREATE TABLE IF NOT EXISTS public.track_play_events (
    play_event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    track_card_id UUID NOT NULL REFERENCES public.track_cards(id) ON DELETE CASCADE,
    source_package TEXT NOT NULL DEFAULT 'unknown_player',
    source_app_name TEXT NOT NULL DEFAULT 'Music Player',
    played_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    listened_ms BIGINT NOT NULL DEFAULT 0,
    track_duration_ms BIGINT NOT NULL DEFAULT 0,
    completion_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
    was_skipped BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_track_play_events_card
    ON public.track_play_events (track_card_id, played_at DESC);

CREATE INDEX IF NOT EXISTS idx_track_play_events_user_time
    ON public.track_play_events (user_id, played_at DESC);

CREATE OR REPLACE FUNCTION public.sync_track_card_play_stats()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    target_card_id UUID;
BEGIN
    target_card_id := COALESCE(NEW.track_card_id, OLD.track_card_id);
    IF target_card_id IS NULL THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    UPDATE public.track_cards tc
    SET
        verified_play_count = stats.play_cnt,
        total_listened_ms = stats.total_ms,
        first_played_at = stats.first_at,
        last_played_at = COALESCE(stats.last_at, tc.last_played_at),
        updated_at = NOW()
    FROM (
        SELECT
            COUNT(*)::INTEGER AS play_cnt,
            COALESCE(SUM(listened_ms), 0)::BIGINT AS total_ms,
            MIN(played_at) AS first_at,
            MAX(played_at) AS last_at
        FROM public.track_play_events
        WHERE track_card_id = target_card_id
          AND was_skipped = FALSE
    ) AS stats
    WHERE tc.id = target_card_id;

    RETURN COALESCE(NEW, OLD);
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_track_card_play_stats ON public.track_play_events;
CREATE TRIGGER trg_sync_track_card_play_stats
    AFTER INSERT OR UPDATE OR DELETE ON public.track_play_events
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_track_card_play_stats();

CREATE TABLE IF NOT EXISTS public.user_playlists (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    playlist_origin TEXT NOT NULL DEFAULT 'TUNEMIND',
    external_playlist_id TEXT,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    mood_or_audience TEXT NOT NULL DEFAULT '',
    cover_image_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_playlists_user_origin
    ON public.user_playlists (user_id, playlist_origin, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.user_playlist_items (
    entry_id TEXT PRIMARY KEY,
    playlist_id TEXT NOT NULL REFERENCES public.user_playlists(id) ON DELETE CASCADE,
    track_card_id UUID NOT NULL REFERENCES public.track_cards(id) ON DELETE CASCADE,
    position_index INTEGER NOT NULL DEFAULT 0,
    source_player TEXT NOT NULL DEFAULT 'unknown_player',
    added_by TEXT NOT NULL DEFAULT 'USER',
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_playlist_items_playlist_pos
    ON public.user_playlist_items (playlist_id, position_index ASC);

CREATE INDEX IF NOT EXISTS idx_user_playlist_items_track_card
    ON public.user_playlist_items (track_card_id);

ALTER TABLE public.track_cards ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_play_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_playlist_items ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own track_cards" ON public.track_cards;
CREATE POLICY "Users can manage own track_cards"
    ON public.track_cards FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

DROP POLICY IF EXISTS "Users can manage own track_play_events" ON public.track_play_events;
CREATE POLICY "Users can manage own track_play_events"
    ON public.track_play_events FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

DROP POLICY IF EXISTS "Users can manage own user_playlists" ON public.user_playlists;
CREATE POLICY "Users can manage own user_playlists"
    ON public.user_playlists FOR ALL
    TO authenticated
    USING (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id))
    WITH CHECK (auth.uid()::text = user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(user_id));

DROP POLICY IF EXISTS "Users can manage own user_playlist_items" ON public.user_playlist_items;
CREATE POLICY "Users can manage own user_playlist_items"
    ON public.user_playlist_items FOR ALL
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.user_playlists p
            WHERE p.id = playlist_id
              AND (auth.uid()::text = p.user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(p.user_id))
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.user_playlists p
            WHERE p.id = playlist_id
              AND (auth.uid()::text = p.user_id OR LOWER(COALESCE(auth.jwt() ->> 'email', '')) = LOWER(p.user_id))
        )
    );


