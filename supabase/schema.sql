-- =============================================================================
-- TuneMind Live Supabase Intelligence & Taste Genome Schema (Universal Safe Migration)
-- =============================================================================
-- Safe to run on any existing Supabase database: creates tables if missing AND
-- adds any missing columns via ALTER TABLE ... ADD COLUMN IF NOT EXISTS before indexing.

-- 1. Agent Interventions & Conversational Memory Table
CREATE TABLE IF NOT EXISTS public.agent_interventions (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    trigger_transcript TEXT NOT NULL DEFAULT '',
    computed_satiety INTEGER NOT NULL DEFAULT 25,
    agent_tts_response TEXT NOT NULL DEFAULT '',
    os_intent TEXT NOT NULL DEFAULT 'NONE',
    target_search_query TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.agent_interventions
    ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    ADD COLUMN IF NOT EXISTS trigger_transcript TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS computed_satiety INTEGER NOT NULL DEFAULT 25,
    ADD COLUMN IF NOT EXISTS agent_tts_response TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS os_intent TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS target_search_query TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_agent_interventions_user_created
    ON public.agent_interventions (user_id, created_at DESC);

-- 2. Synced Spotify & OS Listening Events Table
CREATE TABLE IF NOT EXISTS public.listening_events (
    event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    track_title TEXT NOT NULL DEFAULT 'Unknown Track',
    track_artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    spotify_uri TEXT,
    play_count INTEGER NOT NULL DEFAULT 1,
    media_source TEXT NOT NULL DEFAULT 'com.spotify.music',
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.listening_events
    ADD COLUMN IF NOT EXISTS event_id TEXT,
    ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'tunemind-default-user',
    ADD COLUMN IF NOT EXISTS track_title TEXT NOT NULL DEFAULT 'Unknown Track',
    ADD COLUMN IF NOT EXISTS track_artist TEXT NOT NULL DEFAULT 'Unknown Artist',
    ADD COLUMN IF NOT EXISTS spotify_uri TEXT,
    ADD COLUMN IF NOT EXISTS play_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS media_source TEXT NOT NULL DEFAULT 'com.spotify.music',
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS idx_listening_events_event_id_unique
    ON public.listening_events (event_id);

CREATE INDEX IF NOT EXISTS idx_listening_events_user_plays
    ON public.listening_events (user_id, play_count DESC);

-- 3. Evolving User Taste Genome Profile Table
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

-- 4. Enable Row-Level Security & Policies for Mobile + Cloud Sync
ALTER TABLE public.agent_interventions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listening_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_taste_genome ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'agent_interventions'
          AND policyname = 'Allow TuneMind read/write on agent_interventions'
    ) THEN
        CREATE POLICY "Allow TuneMind read/write on agent_interventions"
            ON public.agent_interventions FOR ALL USING (true) WITH CHECK (true);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'listening_events'
          AND policyname = 'Allow TuneMind read/write on listening_events'
    ) THEN
        CREATE POLICY "Allow TuneMind read/write on listening_events"
            ON public.listening_events FOR ALL USING (true) WITH CHECK (true);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'user_taste_genome'
          AND policyname = 'Allow TuneMind read/write on user_taste_genome'
    ) THEN
        CREATE POLICY "Allow TuneMind read/write on user_taste_genome"
            ON public.user_taste_genome FOR ALL USING (true) WITH CHECK (true);
    END IF;
END
$$;
