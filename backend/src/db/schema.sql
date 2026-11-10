-- Enable UUID extension if not already enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Core Listening Telemetry
CREATE TABLE IF NOT EXISTS listening_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id TEXT NOT NULL,
    session_id TEXT,
    event_type TEXT NOT NULL, -- e.g., 'PLAY', 'SKIP', 'PAUSE'
    track_title TEXT NOT NULL,
    artist TEXT NOT NULL,
    media_source TEXT, -- e.g., 'com.spotify.music'
    occurred_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Acoustic Telemetry (Biological Impact)
CREATE TABLE IF NOT EXISTS acoustic_telemetry (
    track_id TEXT PRIMARY KEY, -- e.g., "title::artist"
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
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for fast Claude lookups
CREATE INDEX IF NOT EXISTS idx_listening_user ON listening_events(user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_interventions_user ON agent_interventions(user_id, created_at DESC);
