-- Enable UUID extension if not already enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Core Listening Telemetry
CREATE TABLE IF NOT EXISTS listening_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id TEXT UNIQUE,
    user_id TEXT NOT NULL,
    session_id TEXT,
    device_id TEXT,
    event_type TEXT NOT NULL, -- e.g., 'PLAY', 'SKIP', 'PAUSE'
    track_id TEXT,
    track_title TEXT NOT NULL,
    artist TEXT NOT NULL,
    spotify_uri TEXT,
    play_count INTEGER NOT NULL DEFAULT 0,
    media_source TEXT, -- e.g., 'com.spotify.music'
    occurred_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Acoustic Telemetry (Biological Impact)
CREATE TABLE IF NOT EXISTS acoustic_telemetry (
    track_id TEXT PRIMARY KEY, -- e.g., canonical track_id
    bpm INT,
    energy FLOAT,
    musical_key TEXT,
    valence FLOAT,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Claude Memory (Agent Interventions)
CREATE TABLE IF NOT EXISTS agent_interventions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id TEXT NOT NULL,
    trigger_transcript TEXT,
    computed_satiety INT,
    agent_tts_response TEXT,
    os_intent TEXT,
    target_search_query TEXT,
    track_id TEXT,
    spotify_uri TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for fast Claude lookups
CREATE INDEX IF NOT EXISTS idx_listening_user ON listening_events(user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_listening_track_id ON listening_events(track_id);
CREATE INDEX IF NOT EXISTS idx_interventions_user ON agent_interventions(user_id, created_at DESC);

-- Self-Learning Discovery: Per-User Contextual Genre/Time/Volume Taste Weights
CREATE TABLE IF NOT EXISTS ml_user_taste_models (
    user_id TEXT PRIMARY KEY,
    learning_iterations INT NOT NULL DEFAULT 0,
    evaluated_outcomes INT NOT NULL DEFAULT 0,
    positive_outcomes INT NOT NULL DEFAULT 0,
    genre_weights JSONB NOT NULL DEFAULT '{}'::jsonb,
    time_slot_affinity JSONB NOT NULL DEFAULT '{}'::jsonb,
    volume_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Self-Learning Discovery: Daily Recommendation Impressions & Outcomes
CREATE TABLE IF NOT EXISTS discovery_recommendation_outcomes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id TEXT NOT NULL,
    drop_date DATE NOT NULL,
    track_id TEXT,
    track_title TEXT NOT NULL,
    artist TEXT NOT NULL,
    spotify_uri TEXT,
    genre TEXT NOT NULL,
    sub_tier TEXT NOT NULL,
    outcome_status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING | PLAYED | COMPLETED | SAVED | SKIPPED | DISMISSED | IGNORED
    completion_ratio FLOAT DEFAULT 0,
    volume_ratio FLOAT DEFAULT 0,
    time_slot TEXT,
    listened_ms BIGINT DEFAULT 0,
    track_duration_ms BIGINT DEFAULT 0,
    reward_score FLOAT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_discovery_outcomes_user_date
    ON discovery_recommendation_outcomes(user_id, drop_date DESC);

