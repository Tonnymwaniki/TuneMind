import React, { useState, useEffect, useRef } from 'react';
import {
  Home,
  Flame,
  History,
  User,
  Snowflake,
  Calendar,
  Layers,
  Settings,
  BarChart3,
  Play,
  Pause,
  SkipForward,
  CheckCircle2,
  AlertTriangle,
  Radio,
  Clock,
  Sparkles,
  Music2,
  Activity,
  ShieldCheck,
  RefreshCw,
  Plus,
  ArrowRight,
  Database,
  ExternalLink,
  ChevronRight,
  Sliders,
  Volume2,
  Smartphone,
  Copy,
  Check,
  Code,
  Download,
  Trash2,
  Eye,
  Server
} from 'lucide-react';

import {
  TrackItem,
  AnalyticsSummary,
  ReconstructedEpisode,
  DailyStudyTile,
  StoredRawEvent
} from './types';

import {
  initialNowPlaying,
  initialHighSatietyTracks,
  candidateCooldownTracks,
  initialPalateCleansers,
  anchorWeights,
  circadianWindows,
  studyDays,
  userProfile
} from './data/mockData';

type TabType =
  | 'home'
  | 'phone_hub'
  | 'fatigue'
  | 'cooldown'
  | 'history'
  | 'session_details'
  | 'study'
  | 'profile'
  | 'cross_app'
  | 'weekly_report'
  | 'settings';

export default function App() {
  const [currentTab, setCurrentTab] = useState<TabType>('home');
  const [isPlaying, setIsPlaying] = useState(true);
  const [playbackProgress, setPlaybackProgress] = useState(45);
  const [currentTrack, setCurrentTrack] = useState<TrackItem>(initialNowPlaying);
  const [isLiveFromPhone, setIsLiveFromPhone] = useState(false);
  const [highSatietyList, setHighSatietyList] = useState<TrackItem[]>(initialHighSatietyTracks);
  const [palateCleansers, setPalateCleansers] = useState<TrackItem[]>(initialPalateCleansers);
  const [studyTiles, setStudyTiles] = useState<DailyStudyTile[]>(studyDays);
  const [selectedEpisode, setSelectedEpisode] = useState<ReconstructedEpisode | null>(null);
  const [showManualCooldownModal, setShowManualCooldownModal] = useState(false);
  const [analyticsData, setAnalyticsData] = useState<AnalyticsSummary | null>(null);
  const [episodesData, setEpisodesData] = useState<ReconstructedEpisode[]>([]);
  const [rawEventsList, setRawEventsList] = useState<StoredRawEvent[]>([]);
  const [totalStoredEvents, setTotalStoredEvents] = useState<number>(0);
  const [selectedRawEvent, setSelectedRawEvent] = useState<StoredRawEvent | null>(null);
  const [serverStatus, setServerStatus] = useState<'checking' | 'connected' | 'offline'>('checking');
  const [telemetryMessage, setTelemetryMessage] = useState<string | null>(null);
  const [copiedUrl, setCopiedUrl] = useState(false);
  const [copiedCurl, setCopiedCurl] = useState(false);

  // Custom Simulator Form Inputs
  const [customTitle, setCustomTitle] = useState('Espresso');
  const [customArtist, setCustomArtist] = useState('Sabrina Carpenter');
  const [customApp, setCustomApp] = useState('com.spotify.music');
  const [customBpm, setCustomBpm] = useState(104);

  const [connectedApps, setConnectedApps] = useState([
    { id: 'spotify', name: 'Spotify', pkg: 'com.spotify.music', connected: true, observations: 342, status: 'Streaming' },
    { id: 'ytmusic', name: 'YouTube Music', pkg: 'com.google.android.apps.youtube.music', connected: true, observations: 118, status: 'Streaming' },
    { id: 'applemusic', name: 'Apple Music', pkg: 'com.apple.android.music', connected: true, observations: 56, status: 'Idle' },
    { id: 'soundcloud', name: 'SoundCloud', pkg: 'com.soundcloud.android', connected: true, observations: 12, status: 'Streaming' },
    { id: 'tidal', name: 'TIDAL', pkg: 'com.aspiro.tidal', connected: false, observations: 0, status: 'Disconnected' }
  ]);

  const backendUrl = typeof window !== 'undefined' ? `${window.location.origin}/api/v1/events` : 'http://localhost:3000/api/v1/events';

  // Helper to map package name to human name
  const getAppDisplayName = (pkg: string) => {
    if (pkg.includes('spotify')) return 'Spotify';
    if (pkg.includes('youtube') || pkg.includes('music.youtube')) return 'YouTube Music';
    if (pkg.includes('apple')) return 'Apple Music';
    if (pkg.includes('soundcloud')) return 'SoundCloud';
    if (pkg.includes('tidal')) return 'TIDAL';
    if (pkg.includes('deezer')) return 'Deezer';
    return pkg;
  };

  // Helper to get app badge color
  const getAppBadgeColor = (pkg: string) => {
    if (pkg.includes('spotify')) return 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30';
    if (pkg.includes('youtube')) return 'bg-rose-500/20 text-rose-300 border-rose-500/30';
    if (pkg.includes('apple')) return 'bg-pink-500/20 text-pink-300 border-pink-500/30';
    if (pkg.includes('soundcloud')) return 'bg-amber-500/20 text-amber-300 border-amber-500/30';
    return 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30';
  };

  // Fetch all backend telemetry analytics and raw event stream
  const fetchBackendData = async () => {
    try {
      const healthRes = await fetch('/health');
      if (healthRes.ok) {
        setServerStatus('connected');
      } else {
        setServerStatus('offline');
      }

      // Fetch raw events list (newest first)
      const eventsRes = await fetch('/api/v1/events?limit=40');
      if (eventsRes.ok) {
        const data = await eventsRes.json();
        if (data.events && Array.isArray(data.events)) {
          setRawEventsList(data.events);
          setTotalStoredEvents(data.total);

          // Find the most recent playing event with track metadata to update Now Playing!
          const latestTrackEvent = data.events.find((e: StoredRawEvent) => {
            try {
              const p = JSON.parse(e.payload_json);
              return p.track && p.track.title;
            } catch {
              return false;
            }
          });

          if (latestTrackEvent) {
            try {
              const p = JSON.parse(latestTrackEvent.payload_json);
              if (p.track?.title) {
                const isStillPlaying = p.playback?.isPlaying ?? (latestTrackEvent.event_type !== 'OBSERVATION_STOPPED');
                setCurrentTrack((prev) => ({
                  id: p.track.trackId || latestTrackEvent.event_id,
                  title: p.track.title,
                  artist: p.track.artist || 'Unknown Artist',
                  albumCoverUrl: prev.title === p.track.title ? prev.albumCoverUrl : 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400',
                  genre: p.track.album ? `${p.track.album}` : 'Acoustic Stream',
                  bpm: prev.bpm || 118,
                  durationSeconds: p.track.durationMs ? Math.round(p.track.durationMs / 1000) : 210,
                  playCount: prev.playCount + 1,
                  satietyScore: prev.satietyScore,
                  fatigueCategory: prev.fatigueCategory,
                  sourceApp: getAppDisplayName(latestTrackEvent.media_source)
                }));
                setIsPlaying(isStillPlaying);
                setIsLiveFromPhone(true);
              }
            } catch (err) {
              console.warn('Error parsing latest track payload:', err);
            }
          }
        }
      }

      // Summary
      const summaryRes = await fetch('/api/v1/analytics/summary?userId=tunemind-dev');
      if (summaryRes.ok) {
        const data = await summaryRes.json();
        setAnalyticsData(data);
      }

      // Reconstructed episodes
      const episodesRes = await fetch('/api/v1/analytics/episodes?userId=tunemind-dev');
      if (episodesRes.ok) {
        const data = await episodesRes.json();
        if (data.episodes && Array.isArray(data.episodes)) {
          setEpisodesData(data.episodes);
        }
      }
    } catch (err) {
      console.warn('Backend query error:', err);
      setServerStatus('offline');
    }
  };

  // Initial fetch and continuous live polling
  useEffect(() => {
    fetchBackendData();
    const interval = setInterval(fetchBackendData, 3500);
    return () => clearInterval(interval);
  }, []);

  // Playback timer progress
  useEffect(() => {
    if (!isPlaying) return;
    const interval = setInterval(() => {
      setPlaybackProgress((prev) => (prev >= 100 ? 0 : prev + 0.5));
    }, 1000);
    return () => clearInterval(interval);
  }, [isPlaying]);

  // Send real live event to backend
  const sendLiveEvent = async (
    type: string,
    track: { title: string; artist: string; album?: string; durationSeconds?: number },
    mediaSource: string,
    isPlayingState: boolean = true
  ) => {
    try {
      const eventId = 'evt-phone-' + Math.random().toString(36).substring(2, 9);
      const payload = {
        events: [
          {
            eventId,
            userId: 'tunemind-dev',
            sessionId: 'obs-sess-phone-live',
            deviceId: 'pixel-8-pro',
            schemaVersion: '1.0',
            eventType: type,
            occurredAt: new Date().toISOString(),
            receivedAt: new Date().toISOString(),
            mediaSource,
            playback: {
              positionMs: Math.round((playbackProgress / 100) * (track.durationSeconds || 200) * 1000),
              elapsedRealtimeMs: Date.now(),
              isPlaying: isPlayingState,
              playbackSpeed: 1.0,
              playbackStateCode: isPlayingState ? 3 : 2
            },
            track: {
              trackId: 'trk-' + Math.random().toString(36).substring(2, 8),
              title: track.title,
              artist: track.artist,
              album: track.album || 'Live Observed Album',
              durationMs: (track.durationSeconds || 200) * 1000
            }
          }
        ]
      };

      const res = await fetch('/api/v1/events', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (res.ok) {
        setTelemetryMessage(`Stored live music event: "${track.title}" from ${getAppDisplayName(mediaSource)}`);
        setTimeout(() => setTelemetryMessage(null), 4000);
        fetchBackendData();
      }
    } catch (err) {
      console.error('Failed to post telemetry event:', err);
    }
  };

  const handleTogglePlay = () => {
    const nextState = !isPlaying;
    setIsPlaying(nextState);
    sendLiveEvent(
      nextState ? 'PLAYBACK_STATE_CHANGED' : 'OBSERVATION_STOPPED',
      currentTrack,
      currentTrack.sourceApp === 'YouTube Music' ? 'com.google.android.apps.youtube.music' : 'com.spotify.music',
      nextState
    );
  };

  const handleSkipTrack = () => {
    const nextTrack = candidateCooldownTracks[Math.floor(Math.random() * candidateCooldownTracks.length)];
    setCurrentTrack(nextTrack);
    setPlaybackProgress(0);
    sendLiveEvent('METADATA_CHANGED', nextTrack, nextTrack.sourceApp === 'YT Music' ? 'com.google.android.apps.youtube.music' : 'com.spotify.music');
  };

  const handleAddToCooldown = (track: TrackItem) => {
    if (highSatietyList.some((t) => t.id === track.id)) return;
    const updated: TrackItem = {
      ...track,
      cooldownActive: true,
      recoveryProgress: 0.05,
      cooldownDaysRemaining: 5.0
    };
    setHighSatietyList([updated, ...highSatietyList]);
    setShowManualCooldownModal(false);
  };

  const handleReactivateTrack = (trackId: string) => {
    setHighSatietyList(highSatietyList.filter((t) => t.id !== trackId));
  };

  const handleUpdateCooldownDays = (trackId: string, deltaDays: number) => {
    setHighSatietyList(
      highSatietyList.map((t) => {
        if (t.id === trackId) {
          const newRemaining = Math.max(0.5, Math.min(14, (t.cooldownDaysRemaining || 3) + deltaDays));
          return { ...t, cooldownDaysRemaining: parseFloat(newRemaining.toFixed(1)) };
        }
        return t;
      })
    );
  };

  const copyEndpointUrl = () => {
    navigator.clipboard.writeText(backendUrl);
    setCopiedUrl(true);
    setTimeout(() => setCopiedUrl(false), 2500);
  };

  const copyCurlSample = () => {
    const sample = `curl -X POST "${backendUrl}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "events": [
      {
        "eventId": "evt-${Date.now()}",
        "userId": "tunemind-dev",
        "sessionId": "session-phone-01",
        "deviceId": "my-phone",
        "schemaVersion": "1.0",
        "eventType": "PLAYBACK_STATE_CHANGED",
        "occurredAt": "${new Date().toISOString()}",
        "mediaSource": "com.spotify.music",
        "playback": { "isPlaying": true, "positionMs": 30000, "playbackSpeed": 1.0 },
        "track": { "title": "Song From My Phone", "artist": "Artist Name", "album": "Album" }
      }
    ]
  }'`;
    navigator.clipboard.writeText(sample);
    setCopiedCurl(true);
    setTimeout(() => setCopiedCurl(false), 2500);
  };

  const handleClearDatabase = async () => {
    if (window.confirm('Are you sure you want to purge all stored telemetry events from the database?')) {
      try {
        await fetch('/api/v1/events', { method: 'DELETE' });
        setTelemetryMessage('All events purged from database.');
        setTimeout(() => setTelemetryMessage(null), 3000);
        fetchBackendData();
      } catch (err) {
        console.error('Failed to clear database:', err);
      }
    }
  };

  return (
    <div className="flex h-screen w-full bg-slate-950 text-slate-100 overflow-hidden font-sans">
      {/* Sidebar for Desktop Navigation */}
      <aside className="hidden md:flex flex-col w-64 border-r border-slate-800/70 bg-slate-900/60 backdrop-blur-xl p-4 justify-between shrink-0">
        <div>
          {/* Logo & Platform Title */}
          <div className="flex items-center gap-3 px-3 py-3 mb-5">
            <div className="h-10 w-10 rounded-xl bg-gradient-to-tr from-cyan-500 via-indigo-600 to-fuchsia-500 p-[1.5px] shadow-lg shadow-cyan-500/20">
              <div className="h-full w-full bg-slate-950 rounded-[10px] flex items-center justify-center">
                <Music2 className="h-5 w-5 text-cyan-400" />
              </div>
            </div>
            <div>
              <h1 className="font-bold text-lg tracking-tight bg-gradient-to-r from-white via-slate-100 to-slate-400 bg-clip-text text-transparent">
                TuneMind
              </h1>
              <div className="flex items-center gap-1.5 text-xs text-cyan-400/90 font-mono">
                <span className="inline-block h-1.5 w-1.5 rounded-full bg-cyan-400 animate-pulse"></span>
                v1 Intelligence
              </div>
            </div>
          </div>

          {/* Navigation Links */}
          <nav className="space-y-1">
            <button
              onClick={() => setCurrentTab('home')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'home'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <Home className="h-4 w-4" />
              Overview
            </button>

            {/* Dedicated Phone & Live Stream Ingestion Hub */}
            <button
              onClick={() => setCurrentTab('phone_hub')}
              className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'phone_hub'
                  ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 shadow-sm shadow-emerald-500/10'
                  : 'text-emerald-400/90 hover:text-emerald-300 hover:bg-emerald-500/10'
              }`}
            >
              <div className="flex items-center gap-3">
                <Smartphone className="h-4 w-4 text-emerald-400 animate-pulse" />
                Phone Ingest Hub
              </div>
              <span className="px-1.5 py-0.5 text-[10px] font-mono rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
                Live
              </span>
            </button>

            <button
              onClick={() => setCurrentTab('history')}
              className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'history'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <div className="flex items-center gap-3">
                <History className="h-4 w-4 text-purple-400" />
                Sessions
              </div>
              <span className="text-xs font-mono text-slate-500">
                {episodesData.length}
              </span>
            </button>

            <button
              onClick={() => setCurrentTab('fatigue')}
              className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'fatigue'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <div className="flex items-center gap-3">
                <Flame className="h-4 w-4 text-amber-400" />
                Song Fatigue
              </div>
              <span className="px-1.5 py-0.5 text-[10px] font-mono rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                {highSatietyList.length} Active
              </span>
            </button>

            <button
              onClick={() => setCurrentTab('cooldown')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'cooldown'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <Snowflake className="h-4 w-4 text-cyan-400" />
              Cooldown Vault
            </button>

            <button
              onClick={() => setCurrentTab('study')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'study'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <Calendar className="h-4 w-4 text-indigo-400" />
              7-Day Study
            </button>

            <button
              onClick={() => setCurrentTab('weekly_report')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'weekly_report'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <BarChart3 className="h-4 w-4 text-purple-400" />
              Receptive Horizon
            </button>

            <button
              onClick={() => setCurrentTab('cross_app')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'cross_app'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <Layers className="h-4 w-4 text-emerald-400" />
              Connected Apps
            </button>

            <button
              onClick={() => setCurrentTab('profile')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'profile'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <User className="h-4 w-4 text-slate-300" />
              Acoustic Profile
            </button>
          </nav>
        </div>

        {/* Server status & Settings footer */}
        <div className="space-y-3 pt-4 border-t border-slate-800/60">
          <div className="px-3 py-2 rounded-xl bg-slate-950/70 border border-slate-800 text-xs flex items-center justify-between">
            <span className="text-slate-400 flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-cyan-400" />
              Durable Store
            </span>
            <span className="font-mono text-cyan-300 font-bold">
              {totalStoredEvents} events
            </span>
          </div>

          <button
            onClick={() => setCurrentTab('settings')}
            className={`w-full flex items-center gap-3 px-3.5 py-2 rounded-xl text-sm font-medium transition-all ${
              currentTab === 'settings'
                ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <Settings className="h-4 w-4" />
            Backend Settings
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col min-w-0 overflow-y-auto pb-20 md:pb-6">
        {/* Top Header Bar */}
        <header className="sticky top-0 z-30 flex items-center justify-between border-b border-slate-800/70 bg-slate-950/80 backdrop-blur-xl px-4 md:px-8 py-3.5">
          <div className="flex items-center gap-3">
            <div className="md:hidden flex items-center gap-2">
              <div className="h-8 w-8 rounded-lg bg-gradient-to-tr from-cyan-500 to-indigo-600 p-[1px]">
                <div className="h-full w-full bg-slate-950 rounded-[7px] flex items-center justify-center">
                  <Music2 className="h-4 w-4 text-cyan-400" />
                </div>
              </div>
              <span className="font-bold text-base text-slate-100">TuneMind</span>
            </div>
            <div className="hidden md:block">
              <span className="text-xs uppercase tracking-wider font-semibold text-slate-500">
                Personal Music Intelligence
              </span>
              <h2 className="text-lg font-bold text-slate-100 capitalize">
                {currentTab.replace('_', ' ')}
              </h2>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Live Observation Ticker */}
            <div
              onClick={() => setCurrentTab('phone_hub')}
              className="cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-900 hover:bg-slate-800 border border-slate-800 text-xs transition-colors"
            >
              <Radio className="h-3.5 w-3.5 text-emerald-400 animate-pulse" />
              <span className="text-slate-300 hidden sm:inline">Phone Ingest Listening</span>
              <span className="text-slate-500">|</span>
              <span className="text-cyan-300 font-mono text-[11px] font-bold">
                {totalStoredEvents} stored
              </span>
            </div>

            <a
              href="/api/download-android"
              download="android_update.zip"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 text-xs font-bold transition-all shadow-md shadow-cyan-500/20"
            >
              <Download className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Download Android Code (ZIP)</span>
              <span className="sm:hidden">Download</span>
            </a>

            <button
              onClick={() => fetchBackendData()}
              title="Refresh telemetry"
              className="p-2 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-400 hover:text-slate-200 transition-colors"
            >
              <RefreshCw className="h-4 w-4" />
            </button>
          </div>
        </header>

        {/* Telemetry notification banner if an action was fired */}
        {telemetryMessage && (
          <div className="mx-4 md:mx-8 mt-4 p-3 rounded-xl bg-emerald-950/80 border border-emerald-800 text-emerald-200 text-xs flex items-center justify-between shadow-lg shadow-emerald-950/40 animate-in fade-in">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-emerald-400 animate-pulse" />
              <span>{telemetryMessage}</span>
            </div>
            <span className="text-[10px] font-mono text-emerald-400/80">HTTP 200 OK • Saved</span>
          </div>
        )}

        {/* Page Content View Router */}
        <div className="p-4 md:p-8 space-y-6 max-w-7xl mx-auto w-full">
          {currentTab === 'home' && renderHomeView()}
          {currentTab === 'phone_hub' && renderPhoneHubView()}
          {currentTab === 'fatigue' && renderFatigueView()}
          {currentTab === 'cooldown' && renderCooldownView()}
          {currentTab === 'history' && renderHistoryView()}
          {currentTab === 'session_details' && renderSessionDetailsView()}
          {currentTab === 'study' && renderStudyView()}
          {currentTab === 'profile' && renderProfileView()}
          {currentTab === 'cross_app' && renderCrossAppView()}
          {currentTab === 'weekly_report' && renderWeeklyReportView()}
          {currentTab === 'settings' && renderSettingsView()}
        </div>
      </main>

      {/* Bottom Nav for Mobile */}
      <nav className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-slate-950/90 backdrop-blur-xl border-t border-slate-800 flex justify-around p-2">
        <button
          onClick={() => setCurrentTab('home')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'home' ? 'text-cyan-400' : 'text-slate-400'
          }`}
        >
          <Home className="h-4 w-4" />
          <span>Home</span>
        </button>
        <button
          onClick={() => setCurrentTab('phone_hub')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'phone_hub' ? 'text-emerald-400 font-bold' : 'text-slate-400'
          }`}
        >
          <Smartphone className="h-4 w-4" />
          <span>Ingest</span>
        </button>
        <button
          onClick={() => setCurrentTab('history')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'history' ? 'text-purple-400' : 'text-slate-400'
          }`}
        >
          <History className="h-4 w-4" />
          <span>Sessions</span>
        </button>
        <button
          onClick={() => setCurrentTab('fatigue')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'fatigue' ? 'text-amber-400' : 'text-slate-400'
          }`}
        >
          <Flame className="h-4 w-4" />
          <span>Fatigue</span>
        </button>
        <button
          onClick={() => setCurrentTab('study')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'study' ? 'text-indigo-400' : 'text-slate-400'
          }`}
        >
          <Calendar className="h-4 w-4" />
          <span>Study</span>
        </button>
      </nav>

      {/* Raw Event Inspector Modal */}
      {selectedRawEvent && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-2xl w-full p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
                  <Code className="h-4 w-4 text-cyan-400" />
                  Raw Ingested Event Payload
                </h3>
                <span className="text-xs font-mono text-slate-400">{selectedRawEvent.event_id}</span>
              </div>
              <button
                onClick={() => setSelectedRawEvent(null)}
                className="text-slate-400 hover:text-slate-200 text-sm p-1.5 rounded-lg bg-slate-800"
              >
                ✕
              </button>
            </div>

            <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 max-h-96 overflow-y-auto">
              <pre className="text-xs font-mono text-emerald-400/90 whitespace-pre-wrap">
                {(() => {
                  try {
                    return JSON.stringify(JSON.parse(selectedRawEvent.payload_json), null, 2);
                  } catch {
                    return selectedRawEvent.payload_json;
                  }
                })()}
              </pre>
            </div>

            <div className="flex items-center justify-between text-xs font-mono text-slate-400">
              <span>App: {selectedRawEvent.media_source}</span>
              <span>Occurred: {new Date(selectedRawEvent.occurred_at).toLocaleString()}</span>
            </div>
          </div>
        </div>
      )}

      {/* Manual Cooldown Selection Modal */}
      {showManualCooldownModal && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-lg w-full p-6 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-slate-100">Add Track to Cooldown</h3>
                <p className="text-xs text-slate-400">
                  Select candidate tracks showing high loop velocity or recent skip patterns.
                </p>
              </div>
              <button
                onClick={() => setShowManualCooldownModal(false)}
                className="text-slate-400 hover:text-slate-200 text-sm p-1.5 rounded-lg bg-slate-800"
              >
                ✕
              </button>
            </div>

            <div className="space-y-3 max-h-80 overflow-y-auto">
              {candidateCooldownTracks.map((trk) => (
                <div
                  key={trk.id}
                  className="flex items-center justify-between p-3 rounded-xl bg-slate-950/70 border border-slate-800/80 hover:border-cyan-500/40 transition-all"
                >
                  <div className="flex items-center gap-3">
                    <img
                      src={trk.albumCoverUrl}
                      alt={trk.title}
                      className="h-11 w-11 rounded-lg object-cover"
                    />
                    <div>
                      <div className="font-semibold text-sm text-slate-100">{trk.title}</div>
                      <div className="text-xs text-slate-400">{trk.artist} • {trk.genre}</div>
                      <div className="flex items-center gap-2 mt-1">
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 font-mono">
                          {trk.satietyScore}% Satiety
                        </span>
                        <span className="text-[10px] text-slate-500 font-mono">
                          {trk.playCount} plays
                        </span>
                      </div>
                    </div>
                  </div>
                  <button
                    onClick={() => handleAddToCooldown(trk)}
                    className="px-3 py-1.5 rounded-lg bg-cyan-500/20 hover:bg-cyan-500/30 text-cyan-300 border border-cyan-500/40 text-xs font-medium flex items-center gap-1.5"
                  >
                    <Snowflake className="h-3.5 w-3.5" />
                    Cool Down
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. HOME SCREEN (Linked directly to real backend music playback)
  // ─────────────────────────────────────────────────────────────────────────────
  function renderHomeView() {
    return (
      <div className="space-y-6">
        {/* Banner linking to Phone Ingestion Hub */}
        <div className="p-4 rounded-2xl bg-gradient-to-r from-emerald-950/50 via-slate-900 to-cyan-950/40 border border-emerald-500/30 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center text-emerald-400 shrink-0">
              <Smartphone className="h-5 w-5 animate-bounce" />
            </div>
            <div>
              <div className="text-sm font-bold text-white flex items-center gap-2">
                Live Phone Listening & Storage Active
                <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 font-mono">
                  Durable File Store
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                All music played on Spotify, YouTube Music, and Apple Music is captured and stored directly on the backend.
              </p>
            </div>
          </div>
          <button
            onClick={() => setCurrentTab('phone_hub')}
            className="px-4 py-2 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold text-xs flex items-center gap-1.5 shrink-0 transition-all shadow-md shadow-emerald-500/20"
          >
            Open Phone Ingest Hub <ChevronRight className="h-4 w-4" />
          </button>
        </div>

        {/* Hero Section: Now Playing & Live Acoustic Intelligence Bar */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Main Now Playing Deck */}
          <div className="lg:col-span-7 bg-gradient-to-br from-slate-900/90 via-slate-900/60 to-slate-950 rounded-2xl border border-slate-800 p-6 shadow-xl relative overflow-hidden">
            <div className="absolute top-0 right-0 w-80 h-80 bg-cyan-500/5 rounded-full blur-3xl pointer-events-none"></div>

            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-cyan-400">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-cyan-500"></span>
                </span>
                {isLiveFromPhone ? 'Live Phone Playback Stream' : 'Audio Session Capture'}
              </div>
              <span className={`text-xs font-mono px-2 py-0.5 rounded border ${getAppBadgeColor(currentTrack.sourceApp)}`}>
                {currentTrack.sourceApp}
              </span>
            </div>

            <div className="flex flex-col sm:flex-row items-center gap-6">
              <div className="relative group shrink-0">
                <img
                  src={currentTrack.albumCoverUrl}
                  alt={currentTrack.title}
                  className="h-36 w-36 sm:h-44 sm:w-44 rounded-2xl object-cover shadow-2xl shadow-cyan-950/50 border border-slate-700/60"
                />
                <div className="absolute inset-0 rounded-2xl bg-gradient-to-t from-black/60 to-transparent flex items-end p-3">
                  <span className="text-[11px] font-mono text-cyan-300 bg-slate-950/80 px-2 py-0.5 rounded-full border border-cyan-500/30">
                    {currentTrack.bpm} BPM
                  </span>
                </div>
              </div>

              <div className="flex-1 w-full space-y-4 text-center sm:text-left">
                <div>
                  <h3 className="text-2xl font-extrabold text-white tracking-tight">
                    {currentTrack.title}
                  </h3>
                  <p className="text-base text-slate-400 font-medium">{currentTrack.artist}</p>
                  <p className="text-xs text-slate-500 font-mono mt-0.5">{currentTrack.genre}</p>
                </div>

                {/* Satiety & Fatigue Tags */}
                <div className="flex flex-wrap items-center justify-center sm:justify-start gap-2 pt-1">
                  <div className="px-2.5 py-1 rounded-lg bg-amber-500/15 border border-amber-500/30 text-amber-300 text-xs font-medium flex items-center gap-1.5">
                    <Flame className="h-3.5 w-3.5" />
                    {currentTrack.fatigueCategory}
                  </div>
                  <div className="px-2.5 py-1 rounded-lg bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 text-xs font-mono">
                    Satiety: {currentTrack.satietyScore}%
                  </div>
                  <div className="px-2.5 py-1 rounded-lg bg-slate-800 border border-slate-700 text-slate-300 text-xs font-mono">
                    {currentTrack.playCount} Plays Logged
                  </div>
                </div>

                {/* Playback progress bar */}
                <div className="space-y-1.5 pt-2">
                  <div className="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
                    <div
                      className="bg-gradient-to-r from-cyan-400 to-indigo-500 h-2 rounded-full transition-all duration-300"
                      style={{ width: `${playbackProgress}%` }}
                    ></div>
                  </div>
                  <div className="flex justify-between text-[11px] font-mono text-slate-500">
                    <span>
                      {Math.floor(((playbackProgress / 100) * currentTrack.durationSeconds) / 60)}:
                      {String(
                        Math.floor(((playbackProgress / 100) * currentTrack.durationSeconds) % 60)
                      ).padStart(2, '0')}
                    </span>
                    <span>
                      {Math.floor(currentTrack.durationSeconds / 60)}:
                      {String(currentTrack.durationSeconds % 60).padStart(2, '0')}
                    </span>
                  </div>
                </div>

                {/* Playback Controls & Real Telemetry Actions */}
                <div className="flex items-center justify-center sm:justify-start gap-3 pt-1">
                  <button
                    onClick={handleTogglePlay}
                    className="px-5 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm flex items-center gap-2 shadow-lg shadow-cyan-500/25 transition-all"
                  >
                    {isPlaying ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                    {isPlaying ? 'Pause Capture' : 'Resume Playback'}
                  </button>

                  <button
                    onClick={handleSkipTrack}
                    className="p-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 transition-colors"
                    title="Simulate Next Track Skip"
                  >
                    <SkipForward className="h-4 w-4" />
                  </button>

                  <button
                    onClick={() => handleAddToCooldown(currentTrack)}
                    className="px-3.5 py-2 rounded-xl bg-slate-800/80 hover:bg-slate-800 border border-slate-700 text-xs text-slate-300 flex items-center gap-1.5"
                  >
                    <Snowflake className="h-3.5 w-3.5 text-cyan-400" />
                    Move to Cooldown
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* Quick Stats & Intelligence Engine Gauge */}
          <div className="lg:col-span-5 flex flex-col gap-4">
            {/* Satiety Index Gauge Card */}
            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-bold text-slate-100">
                  <Flame className="h-4 w-4 text-amber-400" />
                  Satiety Velocity
                </div>
                <span className="text-xs text-cyan-400 font-mono">Real-time Decay</span>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div className="text-xs text-slate-400">Critical Loops</div>
                  <div className="text-2xl font-bold text-amber-400 font-mono mt-1">
                    {highSatietyList.filter((t) => t.fatigueCategory === 'Critical Loop').length + 1}
                  </div>
                  <div className="text-[11px] text-amber-400/80 mt-0.5">High acoustic saturation</div>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div className="text-xs text-slate-400">Resting in Vault</div>
                  <div className="text-2xl font-bold text-cyan-400 font-mono mt-1">
                    {highSatietyList.length}
                  </div>
                  <div className="text-[11px] text-cyan-400/80 mt-0.5">Cooldown active</div>
                </div>
              </div>

              {/* Live Backend Telemetry Trigger */}
              <div className="p-3.5 rounded-xl bg-slate-950/90 border border-slate-800 space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-slate-400 flex items-center gap-1.5 font-medium">
                    <Database className="h-3.5 w-3.5 text-emerald-400" />
                    Persistent Event Database
                  </span>
                  <span className="font-mono text-emerald-400 font-bold">
                    {totalStoredEvents} Stored
                  </span>
                </div>
                <p className="text-[11px] text-slate-500 leading-relaxed">
                  Saved on disk at <code className="text-cyan-400">data/tunemind_events.json</code>. Reconstructed into sessions automatically.
                </p>
                <div className="flex gap-2 pt-1">
                  <button
                    onClick={() => setCurrentTab('phone_hub')}
                    className="flex-1 py-1.5 rounded-lg bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 text-xs font-mono transition-colors border border-emerald-500/30 flex items-center justify-center gap-1.5"
                  >
                    <Smartphone className="h-3.5 w-3.5" />
                    Phone Hub
                  </button>
                  <button
                    onClick={() => setCurrentTab('history')}
                    className="flex-1 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-mono transition-colors border border-slate-700 flex items-center justify-center gap-1.5"
                  >
                    <History className="h-3.5 w-3.5" />
                    Sessions ({episodesData.length})
                  </button>
                </div>
              </div>
            </div>

            {/* Quick 7-Day Study Tile */}
            <div className="bg-gradient-to-r from-indigo-950/40 to-slate-900 border border-indigo-900/40 rounded-2xl p-4 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="h-10 w-10 rounded-xl bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center text-indigo-300 font-bold font-mono">
                  D5
                </div>
                <div>
                  <div className="text-sm font-bold text-white flex items-center gap-2">
                    7-Day Baseline Study
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-indigo-500/20 text-indigo-300">
                      Day 5 Active
                    </span>
                  </div>
                  <div className="text-xs text-slate-400">74 min monitored • 0.65 entropy score</div>
                </div>
              </div>
              <button
                onClick={() => setCurrentTab('study')}
                className="p-2 rounded-xl bg-indigo-600/30 hover:bg-indigo-600/50 text-indigo-200 border border-indigo-500/40 transition-colors"
              >
                <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>

        {/* Section: Auditory Anchor Snapshot & Circadian Receptivity */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Auditory Anchors */}
          <div className="lg:col-span-6 bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <Activity className="h-4 w-4 text-cyan-400" />
                  Auditory Anchor Snapshot
                </h3>
                <p className="text-xs text-slate-400 mt-0.5">
                  Continual behavioural dimensions inferred from raw observation stream.
                </p>
              </div>
              <button
                onClick={() => setCurrentTab('profile')}
                className="text-xs text-cyan-400 hover:text-cyan-300 flex items-center gap-1 font-medium"
              >
                View Drift <ChevronRight className="h-3.5 w-3.5" />
              </button>
            </div>

            <div className="space-y-4 pt-2">
              {anchorWeights.map((anchor, idx) => (
                <div key={anchor.name} className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold text-slate-200">{anchor.name}</span>
                    <div className="flex items-center gap-2 font-mono">
                      <span className="text-cyan-400 font-bold">{anchor.percentage}%</span>
                      <span className="text-[11px] text-slate-500">({anchor.driftDelta})</span>
                    </div>
                  </div>
                  <div className="w-full bg-slate-800 rounded-full h-2">
                    <div
                      className={`h-2 rounded-full ${
                        idx === 0
                          ? 'bg-gradient-to-r from-cyan-400 to-blue-500'
                          : idx === 1
                          ? 'bg-gradient-to-r from-purple-400 to-pink-500'
                          : 'bg-gradient-to-r from-emerald-400 to-teal-500'
                      }`}
                      style={{ width: `${anchor.percentage}%` }}
                    ></div>
                  </div>
                  <div className="text-[11px] text-slate-500">{anchor.description}</div>
                </div>
              ))}
            </div>
          </div>

          {/* Circadian Receptivity Windows */}
          <div className="lg:col-span-6 bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <Clock className="h-4 w-4 text-indigo-400" />
                  Circadian Receptivity Windows
                </h3>
                <p className="text-xs text-slate-400 mt-0.5">
                  Cognitive acoustic load mapped to time of day.
                </p>
              </div>
              <span className="text-xs font-mono text-indigo-400 px-2 py-0.5 rounded bg-indigo-500/10 border border-indigo-500/30">
                Local Device Time
              </span>
            </div>

            <div className="space-y-3 pt-1">
              {circadianWindows.map((win) => (
                <div
                  key={win.title}
                  className={`p-3.5 rounded-xl border transition-all ${
                    win.isFatigueProne
                      ? 'bg-amber-950/20 border-amber-800/40'
                      : 'bg-slate-950/70 border-slate-800'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <div className="text-sm font-semibold text-slate-200">{win.title}</div>
                      <div className="text-xs text-slate-400 mt-0.5">{win.timeRange}</div>
                    </div>
                    <div className="text-right">
                      <div className="text-sm font-mono font-bold text-cyan-400">
                        {win.receptivityPercent}% Receptive
                      </div>
                      <div
                        className={`text-[10px] font-medium ${
                          win.isFatigueProne ? 'text-amber-400' : 'text-emerald-400'
                        }`}
                      >
                        {win.descriptor}
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. PHONE INGESTION HUB & LIVE STREAM SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderPhoneHubView() {
    return (
      <div className="space-y-6">
        {/* Hub Title & Description */}
        <div className="bg-gradient-to-r from-emerald-950/40 via-slate-900 to-cyan-950/40 border border-emerald-500/30 rounded-2xl p-6 space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <h2 className="text-xl font-bold text-white flex items-center gap-2">
                <Smartphone className="h-6 w-6 text-emerald-400" />
                Phone Listening & Ingestion Hub
              </h2>
              <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                TuneMind is ready to listen and store everything you play on your phone. Connect your Android app,
                stream audio from mobile browser, or use our test bench below to verify real-time listening.
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-emerald-500/20 border border-emerald-500/40 text-emerald-300 font-mono text-xs">
                <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse"></span>
                Endpoint Active
              </span>
            </div>
          </div>
        </div>

        {/* Server Endpoint Card */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <div className="lg:col-span-8 bg-slate-900/90 border border-slate-800 rounded-2xl p-6 space-y-4">
            <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
              <Server className="h-4 w-4 text-cyan-400" />
              Live Ingestion Server URL
            </h3>
            <p className="text-xs text-slate-400">
              Set this URL in your phone's TuneMind Android app (under <code>Settings &gt; Backend Ingestion Target</code>) or point your phone's webhooks here.
            </p>

            <div className="flex items-center gap-2 bg-slate-950 border border-slate-800 p-2.5 rounded-xl">
              <code className="text-xs font-mono text-emerald-400 flex-1 truncate px-2 select-all">
                {backendUrl}
              </code>
              <button
                onClick={copyEndpointUrl}
                className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs text-slate-200 font-medium flex items-center gap-1.5 shrink-0 transition-colors border border-slate-700"
              >
                {copiedUrl ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Copy className="h-3.5 w-3.5" />}
                {copiedUrl ? 'Copied!' : 'Copy URL'}
              </button>
            </div>

            {/* Laptop Code Sync Helper */}
            <div className="mt-4 p-4 rounded-xl bg-slate-950 border border-cyan-500/30 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-cyan-300 flex items-center gap-1.5">
                  <Download className="h-4 w-4 text-cyan-400" />
                  Sync Latest Code to Your Laptop (PowerShell)
                </span>
                <span className="text-[10px] font-mono text-slate-400">1-Step Sync</span>
              </div>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Because you are compiling on your laptop at <code className="text-slate-300">C:\Users\hp\Documents\TuneMind\android</code>, run this in PowerShell to pull the new Compose frontend files directly into your folder:
              </p>
              <div className="p-2.5 bg-slate-900 border border-slate-800 rounded-lg text-xs font-mono text-cyan-300 overflow-x-auto whitespace-pre">
                {`Invoke-WebRequest -Uri "${window.location.origin}/api/download-android" -OutFile "android_update.zip"; Expand-Archive android_update.zip -DestinationPath . -Force`}
              </div>
            </div>

            <div className="pt-2 flex flex-wrap gap-2">
              <button
                onClick={copyCurlSample}
                className="px-3 py-1.5 rounded-lg bg-slate-800/80 hover:bg-slate-800 text-xs text-slate-300 flex items-center gap-1.5 border border-slate-700/80"
              >
                {copiedCurl ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Code className="h-3.5 w-3.5 text-cyan-400" />}
                {copiedCurl ? 'cURL Copied!' : 'Copy cURL Test Command'}
              </button>
            </div>
          </div>

          <div className="lg:col-span-4 bg-slate-900/90 border border-slate-800 rounded-2xl p-6 space-y-3 flex flex-col justify-between">
            <div>
              <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
                <Database className="h-4 w-4 text-emerald-400" />
                Durable Storage Status
              </h3>
              <p className="text-xs text-slate-400 mt-1">
                Data persists to disk in real-time.
              </p>
            </div>

            <div className="space-y-2">
              <div className="flex justify-between text-xs font-mono">
                <span className="text-slate-400">Total Observations:</span>
                <span className="text-white font-bold">{totalStoredEvents}</span>
              </div>
              <div className="flex justify-between text-xs font-mono">
                <span className="text-slate-400">Disk Storage:</span>
                <span className="text-emerald-400">data/tunemind_events.json</span>
              </div>
              <div className="flex justify-between text-xs font-mono">
                <span className="text-slate-400">Accepted Apps:</span>
                <span className="text-slate-200">Spotify, YT Music, Apple</span>
              </div>
            </div>

            <button
              onClick={handleClearDatabase}
              className="w-full py-1.5 rounded-xl bg-red-500/10 hover:bg-red-500/20 text-red-300 text-xs font-medium border border-red-500/20 flex items-center justify-center gap-1.5 transition-colors"
            >
              <Trash2 className="h-3.5 w-3.5" />
              Reset Event Database
            </button>
          </div>
        </div>

        {/* USB Phone Run & Install Guide */}
        <div className="p-6 rounded-2xl bg-gradient-to-r from-cyan-950/40 via-slate-900 to-indigo-950/40 border border-cyan-500/30 space-y-4">
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-xl bg-cyan-500/20 border border-cyan-500/40 flex items-center justify-center text-cyan-300 font-bold">
              <Smartphone className="h-5 w-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-white flex items-center gap-2">
                Install & Run Native Android App on Your USB-Connected Phone
              </h3>
              <p className="text-xs text-slate-400">
                Follow these 2 simple steps on your laptop to push the compiled TuneMind app directly to your phone:
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-1">
            <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-cyan-400 flex items-center gap-1.5">
                  <span className="h-5 w-5 rounded-full bg-cyan-500/20 text-cyan-300 flex items-center justify-center text-xs">1</span>
                  Run install command on laptop
                </span>
                <span className="text-[10px] text-slate-500 font-mono">Terminal in project root</span>
              </div>
              <div className="bg-slate-900 p-2.5 rounded-lg border border-slate-800 text-xs font-mono text-emerald-400 select-all">
                cd android && ./gradlew installDebug
              </div>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Gradle will compile the APK with the live cloud backend URL preconfigured and install it on your connected USB phone via ADB.
              </p>
            </div>

            <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-indigo-400 flex items-center gap-1.5">
                  <span className="h-5 w-5 rounded-full bg-indigo-500/20 text-indigo-300 flex items-center justify-center text-xs">2</span>
                  Grant Notification Access on Phone
                </span>
                <span className="text-[10px] text-slate-500 font-mono">One-time Android permission</span>
              </div>
              <div className="bg-slate-900 p-2.5 rounded-lg border border-slate-800 text-xs font-mono text-indigo-300">
                Settings → Notification Access → TuneMind: ON
              </div>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Android requires this permission so TuneMind's background observer can capture track title, artist, and playback status when you play music.
              </p>
            </div>
          </div>
        </div>

        {/* Live Phone Music Simulator & Instant Playback Deck */}
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <h3 className="text-base font-bold text-white flex items-center gap-2">
                <Radio className="h-4 w-4 text-emerald-400 animate-pulse" />
                Live Music Ingestion Test Bench
              </h3>
              <p className="text-xs text-slate-400 mt-0.5">
                Simulate or emit the exact music currently playing on your phone across all supported apps.
              </p>
            </div>
            <span className="text-xs font-mono text-cyan-400">Instant Database Write</span>
          </div>

          {/* Quick presets for common phone music */}
          <div className="space-y-2 pt-1">
            <span className="text-xs font-semibold text-slate-400">Quick Music Stream Presets:</span>
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-3">
              <button
                onClick={() =>
                  sendLiveEvent(
                    'PLAYBACK_STATE_CHANGED',
                    { title: 'Birds of a Feather', artist: 'Billie Eilish', album: 'HIT ME HARD AND SOFT', durationSeconds: 196 },
                    'com.spotify.music'
                  )
                }
                className="p-3 rounded-xl bg-slate-950 border border-emerald-500/30 hover:border-emerald-500/70 text-left transition-all group"
              >
                <div className="text-[10px] font-mono text-emerald-400 flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400"></span> Spotify Stream
                </div>
                <div className="text-xs font-bold text-slate-100 mt-1 group-hover:text-cyan-300">
                  Birds of a Feather
                </div>
                <div className="text-[11px] text-slate-400">Billie Eilish</div>
              </button>

              <button
                onClick={() =>
                  sendLiveEvent(
                    'PLAYBACK_STATE_CHANGED',
                    { title: 'Espresso', artist: 'Sabrina Carpenter', album: 'Short n Sweet', durationSeconds: 175 },
                    'com.spotify.music'
                  )
                }
                className="p-3 rounded-xl bg-slate-950 border border-emerald-500/30 hover:border-emerald-500/70 text-left transition-all group"
              >
                <div className="text-[10px] font-mono text-emerald-400 flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400"></span> Spotify Stream
                </div>
                <div className="text-xs font-bold text-slate-100 mt-1 group-hover:text-cyan-300">
                  Espresso
                </div>
                <div className="text-[11px] text-slate-400">Sabrina Carpenter</div>
              </button>

              <button
                onClick={() =>
                  sendLiveEvent(
                    'PLAYBACK_STATE_CHANGED',
                    { title: 'Not Like Us', artist: 'Kendrick Lamar', album: 'Single', durationSeconds: 274 },
                    'com.google.android.apps.youtube.music'
                  )
                }
                className="p-3 rounded-xl bg-slate-950 border border-rose-500/30 hover:border-rose-500/70 text-left transition-all group"
              >
                <div className="text-[10px] font-mono text-rose-400 flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-rose-400"></span> YouTube Music
                </div>
                <div className="text-xs font-bold text-slate-100 mt-1 group-hover:text-cyan-300">
                  Not Like Us
                </div>
                <div className="text-[11px] text-slate-400">Kendrick Lamar</div>
              </button>

              <button
                onClick={() =>
                  sendLiveEvent(
                    'PLAYBACK_STATE_CHANGED',
                    { title: 'Blinding Lights', artist: 'The Weeknd', album: 'After Hours', durationSeconds: 200 },
                    'com.apple.android.music'
                  )
                }
                className="p-3 rounded-xl bg-slate-950 border border-pink-500/30 hover:border-pink-500/70 text-left transition-all group"
              >
                <div className="text-[10px] font-mono text-pink-400 flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-pink-400"></span> Apple Music
                </div>
                <div className="text-xs font-bold text-slate-100 mt-1 group-hover:text-cyan-300">
                  Blinding Lights
                </div>
                <div className="text-[11px] text-slate-400">The Weeknd</div>
              </button>
            </div>
          </div>

          {/* Custom Track Ingest Form */}
          <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 space-y-3 pt-4">
            <span className="text-xs font-semibold text-slate-300">Send Specific Track Playing on Phone:</span>
            <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div>
                <label className="text-[11px] text-slate-400 block mb-1">Track Title</label>
                <input
                  type="text"
                  value={customTitle}
                  onChange={(e) => setCustomTitle(e.target.value)}
                  placeholder="e.g. Bohemian Rhapsody"
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-cyan-500"
                />
              </div>
              <div>
                <label className="text-[11px] text-slate-400 block mb-1">Artist</label>
                <input
                  type="text"
                  value={customArtist}
                  onChange={(e) => setCustomArtist(e.target.value)}
                  placeholder="e.g. Queen"
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-cyan-500"
                />
              </div>
              <div>
                <label className="text-[11px] text-slate-400 block mb-1">Music App</label>
                <select
                  value={customApp}
                  onChange={(e) => setCustomApp(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-cyan-500"
                >
                  <option value="com.spotify.music">Spotify</option>
                  <option value="com.google.android.apps.youtube.music">YouTube Music</option>
                  <option value="com.apple.android.music">Apple Music</option>
                  <option value="com.soundcloud.android">SoundCloud</option>
                  <option value="com.aspiro.tidal">TIDAL</option>
                  <option value="com.deezer.android.app">Deezer</option>
                </select>
              </div>
              <div className="flex items-end">
                <button
                  onClick={() =>
                    sendLiveEvent(
                      'PLAYBACK_STATE_CHANGED',
                      { title: customTitle, artist: customArtist, album: 'Phone Live Stream', durationSeconds: 210 },
                      customApp
                    )
                  }
                  className="w-full py-1.5 px-3 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold text-xs flex items-center justify-center gap-1.5 shadow-md shadow-emerald-500/20 transition-all"
                >
                  <Play className="h-3.5 w-3.5" />
                  Emit Live Play
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Real-time Stored Events Stream Table */}
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
              <Activity className="h-4 w-4 text-cyan-400" />
              Live Ingested Telemetry Feed (Newest First)
            </h3>
            <span className="text-xs text-slate-400 font-mono">
              Showing last {rawEventsList.length} events
            </span>
          </div>

          <div className="space-y-2">
            {rawEventsList.length > 0 ? (
              rawEventsList.map((event) => {
                let trackInfo: any = {};
                let playbackInfo: any = {};
                try {
                  const parsed = JSON.parse(event.payload_json);
                  trackInfo = parsed.track || {};
                  playbackInfo = parsed.playback || {};
                } catch {}

                return (
                  <div
                    key={event.event_id}
                    className="p-3.5 rounded-xl bg-slate-900/70 border border-slate-800/80 hover:border-slate-700 flex flex-col sm:flex-row sm:items-center justify-between gap-3 transition-all"
                  >
                    <div className="flex items-center gap-3">
                      <div className="h-9 w-9 rounded-lg bg-slate-950 flex items-center justify-center font-mono text-xs text-slate-400 border border-slate-800 shrink-0">
                        <Music2 className="h-4 w-4 text-cyan-400" />
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-bold text-sm text-slate-100">
                            {trackInfo.title || 'Playback State Event'}
                          </span>
                          {trackInfo.artist && (
                            <span className="text-xs text-slate-400 font-medium">
                              by {trackInfo.artist}
                            </span>
                          )}
                        </div>
                        <div className="flex flex-wrap items-center gap-2 mt-0.5 text-[11px] font-mono text-slate-500">
                          <span className={`px-1.5 py-0.2 rounded border ${getAppBadgeColor(event.media_source)}`}>
                            {getAppDisplayName(event.media_source)}
                          </span>
                          <span>{event.event_type}</span>
                          <span>•</span>
                          <span>Device: {event.device_id}</span>
                          {playbackInfo.isPlaying !== undefined && (
                            <>
                              <span>•</span>
                              <span className={playbackInfo.isPlaying ? 'text-emerald-400' : 'text-amber-400'}>
                                {playbackInfo.isPlaying ? 'PLAYING' : 'PAUSED'}
                              </span>
                            </>
                          )}
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center justify-between sm:justify-end gap-3 text-right">
                      <span className="text-[11px] font-mono text-slate-500">
                        {new Date(event.occurred_at).toLocaleTimeString()}
                      </span>
                      <button
                        onClick={() => setSelectedRawEvent(event)}
                        className="px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs text-slate-300 font-mono flex items-center gap-1 border border-slate-700"
                      >
                        <Eye className="h-3 w-3" />
                        JSON
                      </button>
                    </div>
                  </div>
                );
              })
            ) : (
              <div className="p-8 text-center bg-slate-900/40 rounded-2xl border border-slate-800 text-slate-400">
                No events received yet. Play music on your phone or use the test bench above.
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. SONG FATIGUE & SATIETY SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderFatigueView() {
    return (
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-gradient-to-r from-amber-950/30 via-slate-900 to-slate-900 p-6 rounded-2xl border border-amber-900/30">
          <div>
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <Flame className="h-5 w-5 text-amber-400" />
              Song Fatigue & Satiety Monitor
            </h2>
            <p className="text-xs text-slate-400 mt-1 max-w-xl">
              Deterministic fatigue detection identifies tracks entering acoustic saturation. Tracks
              with repeat velocity, frequent skips, or high lyric load are prioritized for resting.
            </p>
          </div>
          <button
            onClick={() => setShowManualCooldownModal(true)}
            className="px-4 py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm flex items-center gap-2 shrink-0 transition-all shadow-lg shadow-cyan-500/20"
          >
            <Plus className="h-4 w-4" />
            Add Track to Cooldown
          </button>
        </div>

        {/* Active Resting Tracks */}
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
              <Snowflake className="h-4 w-4 text-cyan-400" />
              Active Cooldowns ({highSatietyList.length})
            </h3>
            <span className="text-xs text-slate-400">Automatic receptor recovery</span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {highSatietyList.map((track) => (
              <div
                key={track.id}
                className="p-4 rounded-2xl bg-slate-900/80 border border-slate-800 hover:border-slate-700 transition-all space-y-3"
              >
                <div className="flex items-start gap-3">
                  <img
                    src={track.albumCoverUrl}
                    alt={track.title}
                    className="h-14 w-14 rounded-xl object-cover shrink-0"
                  />
                  <div className="flex-1 min-w-0">
                    <h4 className="text-sm font-bold text-slate-100 truncate">{track.title}</h4>
                    <p className="text-xs text-slate-400 truncate">{track.artist}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                        {track.fatigueCategory}
                      </span>
                      <span className="text-[10px] font-mono text-slate-400">
                        {track.recentSkips} recent skips
                      </span>
                    </div>
                  </div>
                </div>

                {/* Recovery Progress Bar */}
                <div className="space-y-1.5 pt-1">
                  <div className="flex items-center justify-between text-xs font-mono">
                    <span className="text-slate-400">Recovery Progress</span>
                    <span className="text-cyan-400 font-bold">
                      {Math.round((track.recoveryProgress || 0) * 100)}%
                    </span>
                  </div>
                  <div className="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
                    <div
                      className="bg-gradient-to-r from-cyan-500 to-indigo-500 h-2 rounded-full"
                      style={{ width: `${(track.recoveryProgress || 0) * 100}%` }}
                    ></div>
                  </div>
                  <div className="flex items-center justify-between text-[11px] text-slate-500">
                    <span>{track.cooldownDaysRemaining} days remaining</span>
                    <span className="text-slate-400">Target Satiety &lt; 20%</span>
                  </div>
                </div>

                <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
                  <button
                    onClick={() => handleUpdateCooldownDays(track.id, 1)}
                    className="text-xs text-slate-400 hover:text-slate-200"
                  >
                    +1 Day Rest
                  </button>
                  <button
                    onClick={() => handleReactivateTrack(track.id)}
                    className="px-2.5 py-1 rounded-lg bg-emerald-500/20 text-emerald-300 text-xs font-medium border border-emerald-500/30 hover:bg-emerald-500/30"
                  >
                    Reactivate
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* High Satiety Candidates */}
        <div className="space-y-4 pt-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-400" />
              Impending Fatigue Watchlist
            </h3>
            <span className="text-xs text-slate-400">High loop velocity</span>
          </div>

          <div className="space-y-2.5">
            {candidateCooldownTracks.map((trk) => (
              <div
                key={trk.id}
                className="flex items-center justify-between p-3.5 rounded-xl bg-slate-900/60 border border-slate-800 hover:border-slate-700 transition-all"
              >
                <div className="flex items-center gap-3">
                  <img
                    src={trk.albumCoverUrl}
                    alt={trk.title}
                    className="h-12 w-12 rounded-xl object-cover shrink-0"
                  />
                  <div>
                    <div className="font-semibold text-sm text-slate-100">{trk.title}</div>
                    <div className="text-xs text-slate-400">{trk.artist} • {trk.sourceApp}</div>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[10px] font-mono text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded">
                        Satiety Score: {trk.satietyScore}%
                      </span>
                      <span className="text-[10px] text-slate-500 font-mono">
                        {trk.playCount} plays observed
                      </span>
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => handleAddToCooldown(trk)}
                  className="px-3.5 py-1.5 rounded-xl bg-cyan-500/20 hover:bg-cyan-500/30 text-cyan-300 border border-cyan-500/40 text-xs font-semibold flex items-center gap-1.5"
                >
                  <Snowflake className="h-3.5 w-3.5" />
                  Initiate Cooldown
                </button>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. COOLDOWN VAULT SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderCooldownView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-2">
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Snowflake className="h-5 w-5 text-cyan-400" />
            Cooldown Vault Management
          </h2>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            Songs in cooldown are suppressed from high-rotation recommendation loops to prevent listener burnout.
            TuneMind uses an exponential decay curve based on your auditory anchor profile to determine when a track
            has returned to full acoustic freshness.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Total Resting Tracks</span>
            <div className="text-2xl font-bold text-cyan-400 font-mono mt-1">
              {highSatietyList.length} Tracks
            </div>
            <div className="text-[11px] text-slate-500 mt-1">3 apps synced</div>
          </div>

          <div className="p-4 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Average Satiety Reset</span>
            <div className="text-2xl font-bold text-indigo-400 font-mono mt-1">
              4.2 Days
            </div>
            <div className="text-[11px] text-slate-500 mt-1">Standard loop recovery</div>
          </div>

          <div className="p-4 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Protection Status</span>
            <div className="text-2xl font-bold text-emerald-400 font-mono mt-1 flex items-center gap-2">
              <ShieldCheck className="h-5 w-5" /> Enforced
            </div>
            <div className="text-[11px] text-slate-500 mt-1">Auto-skipped in shuffle</div>
          </div>
        </div>

        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-slate-100">Adjust Rest Parameters</h3>
            <button
              onClick={() => setShowManualCooldownModal(true)}
              className="px-3 py-1.5 rounded-lg bg-cyan-500/20 text-cyan-300 text-xs font-medium border border-cyan-500/30 flex items-center gap-1.5"
            >
              <Plus className="h-3.5 w-3.5" /> Add Track
            </button>
          </div>

          <div className="space-y-3">
            {highSatietyList.map((track) => (
              <div
                key={track.id}
                className="p-4 rounded-2xl bg-slate-900/60 border border-slate-800 flex flex-col md:flex-row items-start md:items-center justify-between gap-4"
              >
                <div className="flex items-center gap-3">
                  <img
                    src={track.albumCoverUrl}
                    alt={track.title}
                    className="h-14 w-14 rounded-xl object-cover shrink-0"
                  />
                  <div>
                    <h4 className="text-sm font-bold text-slate-100">{track.title}</h4>
                    <p className="text-xs text-slate-400">{track.artist}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[10px] px-2 py-0.5 rounded bg-cyan-500/20 text-cyan-300 font-mono">
                        {track.cooldownDaysRemaining} days left
                      </span>
                      <span className="text-[10px] text-slate-500 font-mono">
                        {Math.round((track.recoveryProgress || 0) * 100)}% recovery
                      </span>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-3 w-full md:w-auto justify-end">
                  <div className="flex items-center gap-1 bg-slate-950 p-1 rounded-xl border border-slate-800">
                    <button
                      onClick={() => handleUpdateCooldownDays(track.id, -1)}
                      className="px-2 py-1 text-xs text-slate-400 hover:text-slate-200 rounded"
                      title="Decrease 1 Day"
                    >
                      -1d
                    </button>
                    <span className="px-2 font-mono text-xs text-cyan-300 font-bold">
                      {track.cooldownDaysRemaining}d
                    </span>
                    <button
                      onClick={() => handleUpdateCooldownDays(track.id, 1)}
                      className="px-2 py-1 text-xs text-slate-400 hover:text-slate-200 rounded"
                      title="Add 1 Day"
                    >
                      +1d
                    </button>
                  </div>

                  <button
                    onClick={() => handleReactivateTrack(track.id)}
                    className="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-slate-200 border border-slate-700"
                  >
                    Restore
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. RECONSTRUCTED SESSIONS SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderHistoryView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-2">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <History className="h-5 w-5 text-purple-400" />
              Reconstructed Playback Sessions
            </h2>
            <span className="text-xs font-mono text-cyan-400 px-2 py-0.5 rounded bg-cyan-500/10 border border-cyan-500/30">
              Live from /api/v1/analytics/episodes
            </span>
          </div>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            Derived deterministically from raw observations using wall-clock sorting and callback
            span grouping. No user identification or cloud telemetry is transmitted.
          </p>
        </div>

        {/* Source breakdown cards */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Total Observations</span>
            <div className="text-xl font-bold text-white font-mono mt-1">
              {totalStoredEvents}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Durable records</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Spotify Telemetry</span>
            <div className="text-xl font-bold text-emerald-400 font-mono mt-1">
              {analyticsData?.bySource?.['com.spotify.music'] ?? 50}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Direct callbacks</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">YouTube Music</span>
            <div className="text-xl font-bold text-red-400 font-mono mt-1">
              {analyticsData?.bySource?.['com.google.android.apps.youtube.music'] ?? 14}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Direct callbacks</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Observer Sessions</span>
            <div className="text-xl font-bold text-cyan-400 font-mono mt-1">
              {episodesData.length}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Bounded episodes</div>
          </div>
        </div>

        {/* Reconstructed Episode List */}
        <div className="space-y-3">
          <h3 className="text-base font-bold text-slate-100">Recent Playback Episodes</h3>

          {episodesData.length > 0 ? (
            episodesData.map((ep, idx) => (
              <div
                key={ep.episodeId || idx}
                onClick={() => {
                  setSelectedEpisode(ep);
                  setCurrentTab('session_details');
                }}
                className="p-4 rounded-2xl bg-slate-900/60 border border-slate-800 hover:border-cyan-500/50 hover:bg-slate-900/90 cursor-pointer transition-all space-y-3"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="h-2 w-2 rounded-full bg-cyan-400"></span>
                    <span className="font-mono text-xs text-cyan-300 font-semibold">
                      {ep.observerSessionId}
                    </span>
                    <span className="text-slate-600">•</span>
                    <span className="text-xs text-slate-400 font-mono">
                      {getAppDisplayName(ep.sourcePackage || 'com.spotify.music')}
                    </span>
                  </div>
                  <span className="text-xs px-2 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">
                    {ep.status}
                  </span>
                </div>

                <div className="flex flex-wrap items-center justify-between text-xs text-slate-400 gap-2">
                  <div className="flex items-center gap-3">
                    <span>
                      Track Appearances:{' '}
                      <strong className="text-slate-200">
                        {ep.trackAppearances?.length ?? 3}
                      </strong>
                    </span>
                    <span>
                      Raw Events:{' '}
                      <strong className="text-slate-200">{ep.eventCount}</strong>
                    </span>
                  </div>
                  <div className="text-right font-mono text-[11px] text-slate-500">
                    {ep.firstObservedAt ? new Date(ep.firstObservedAt).toLocaleTimeString() : 'Recent'}
                  </div>
                </div>

                {/* Candidate tracks inside episode */}
                {ep.trackAppearances && ep.trackAppearances.length > 0 && (
                  <div className="flex items-center gap-2 pt-1 overflow-x-auto">
                    {ep.trackAppearances.map((ta, tIdx) => (
                      <span
                        key={ta.appearanceId || tIdx}
                        className="px-2 py-1 rounded bg-slate-950 text-[11px] text-slate-300 border border-slate-800 shrink-0"
                      >
                        {ta.title || 'Unknown Track'} ({ta.artist || 'Unknown'})
                      </span>
                    ))}
                  </div>
                )}
              </div>
            ))
          ) : (
            <div className="p-8 text-center bg-slate-900/40 rounded-2xl border border-slate-800/80 space-y-3">
              <History className="h-8 w-8 text-slate-600 mx-auto" />
              <div className="text-sm text-slate-400">No episodes reconstructed yet.</div>
            </div>
          )}
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. SESSION DETAILS SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderSessionDetailsView() {
    const ep = selectedEpisode || episodesData[0];
    if (!ep) {
      return (
        <div className="p-8 text-center text-slate-400">
          No episode selected.{' '}
          <button onClick={() => setCurrentTab('history')} className="text-cyan-400 underline">
            Return to History
          </button>
        </div>
      );
    }

    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <button
            onClick={() => setCurrentTab('history')}
            className="text-xs text-cyan-400 hover:text-cyan-300 flex items-center gap-1 font-semibold"
          >
            ← Back to Sessions
          </button>
          <span className="text-xs font-mono text-slate-400">{ep.episodeId}</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <span className="text-xs font-mono text-cyan-400">Observer Connection</span>
              <h3 className="text-xl font-bold text-white">{ep.observerSessionId}</h3>
              <p className="text-xs text-slate-400 font-mono mt-0.5">{getAppDisplayName(ep.sourcePackage || '')}</p>
            </div>
            <div className="text-right">
              <span className="px-2.5 py-1 rounded bg-indigo-500/20 text-indigo-300 text-xs font-mono">
                {ep.status}
              </span>
              <div className="text-[11px] text-slate-500 font-mono mt-1">
                Start: {ep.startBoundary}
              </div>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-3 pt-2">
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800">
              <span className="text-[11px] text-slate-500">First Observed</span>
              <div className="text-xs font-mono text-slate-200 mt-0.5">
                {ep.firstObservedAt ? new Date(ep.firstObservedAt).toLocaleTimeString() : 'N/A'}
              </div>
            </div>
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800">
              <span className="text-[11px] text-slate-500">Last Observed</span>
              <div className="text-xs font-mono text-slate-200 mt-0.5">
                {ep.lastObservedAt ? new Date(ep.lastObservedAt).toLocaleTimeString() : 'N/A'}
              </div>
            </div>
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800">
              <span className="text-[11px] text-slate-500">Event Count</span>
              <div className="text-xs font-mono text-cyan-300 mt-0.5 font-bold">
                {ep.eventCount} callbacks
              </div>
            </div>
          </div>
        </div>

        {/* Track Appearances Timeline */}
        <div className="space-y-3">
          <h4 className="text-base font-bold text-slate-100">Observed Track Appearances</h4>

          <div className="space-y-3">
            {ep.trackAppearances && ep.trackAppearances.length > 0 ? (
              ep.trackAppearances.map((ta, idx) => (
                <div
                  key={ta.appearanceId || idx}
                  className="p-4 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-between"
                >
                  <div className="flex items-center gap-3">
                    <div className="h-8 w-8 rounded-lg bg-slate-800 flex items-center justify-center font-mono text-xs text-slate-400">
                      0{idx + 1}
                    </div>
                    <div>
                      <div className="text-sm font-bold text-slate-100">{ta.title || 'Untitled Track'}</div>
                      <div className="text-xs text-slate-400">{ta.artist || 'Unknown Artist'}</div>
                    </div>
                  </div>

                  <div className="text-right">
                    <span className="text-xs font-mono px-2 py-0.5 rounded bg-cyan-500/10 text-cyan-300 border border-cyan-500/20">
                      {ta.identityBasis}
                    </span>
                    <div className="text-[10px] text-slate-500 font-mono mt-1">
                      {ta.observationCount} observations
                    </div>
                  </div>
                </div>
              ))
            ) : (
              <div className="p-4 text-xs text-slate-400 bg-slate-900/40 rounded-xl">
                No individual track snapshots parsed.
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. 7-DAY BASELINE STUDY SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderStudyView() {
    return (
      <div className="space-y-6">
        <div className="bg-gradient-to-r from-indigo-950/40 via-slate-900 to-slate-900 border border-indigo-900/40 rounded-2xl p-6 space-y-2">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <Calendar className="h-5 w-5 text-indigo-400" />
              7-Day Baseline Acoustic Intelligence Study
            </h2>
            <span className="text-xs font-mono text-indigo-300 bg-indigo-500/20 px-2.5 py-1 rounded-full border border-indigo-500/30">
              Protocol v1.0
            </span>
          </div>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            TuneMind observes baseline auditory anchors over 7 consecutive days to calibrate your
            individual fatigue velocity, tempo tolerance, and circadian receptivity curve without imposing
            generic heuristics.
          </p>
        </div>

        {/* 7 Daily Tiles */}
        <div className="grid grid-cols-1 md:grid-cols-7 gap-3">
          {studyTiles.map((day) => (
            <div
              key={day.dayNumber}
              className={`p-4 rounded-xl border flex flex-col justify-between h-44 transition-all ${
                day.status === 'COMPLETED'
                  ? 'bg-slate-900 border-slate-700/80 shadow-sm'
                  : day.status === 'IN_PROGRESS'
                  ? 'bg-indigo-950/40 border-indigo-500/50 shadow-lg shadow-indigo-950/30 ring-1 ring-indigo-500/30'
                  : 'bg-slate-950/60 border-slate-800/60 opacity-60'
              }`}
            >
              <div>
                <div className="flex items-center justify-between">
                  <span className="font-bold text-sm text-slate-200">{day.dayLabel}</span>
                  <span className="font-mono text-xs text-slate-500">D{day.dayNumber}</span>
                </div>

                <div className="mt-3">
                  {day.status === 'COMPLETED' && (
                    <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded">
                      <CheckCircle2 className="h-3 w-3" /> Done
                    </span>
                  )}
                  {day.status === 'IN_PROGRESS' && (
                    <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-indigo-300 bg-indigo-500/20 px-1.5 py-0.5 rounded animate-pulse">
                      Active
                    </span>
                  )}
                  {day.status === 'SCHEDULED' && (
                    <span className="text-[10px] font-semibold text-slate-500 bg-slate-800/40 px-1.5 py-0.5 rounded">
                      Pending
                    </span>
                  )}
                </div>
              </div>

              <div className="space-y-1 text-xs font-mono">
                <div className="text-slate-300 font-semibold">{day.minutesTracked}m</div>
                <div className="text-[10px] text-slate-500">
                  {day.skipsAnalyzed} skips • {day.entropyScore}H
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* Study calibration insights */}
        <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-4">
          <h3 className="text-base font-bold text-slate-100">Calibration Progress (71%)</h3>
          <div className="w-full bg-slate-800 rounded-full h-2.5 overflow-hidden">
            <div className="bg-gradient-to-r from-indigo-500 to-cyan-400 h-2.5 rounded-full w-[71%]"></div>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2 text-xs text-slate-400">
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800/80">
              <strong className="text-slate-200 block mb-1">Entropy Stability: 0.82</strong>
              Listener maintains high exploration across electronic & synth styles during mid-day blocks.
            </div>
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800/80">
              <strong className="text-slate-200 block mb-1">Skip Velocity: 4.6 / hr</strong>
              Vocal repetition accounts for 68% of unprompted track abandonment after 18:00.
            </div>
            <div className="p-3 rounded-xl bg-slate-950 border border-slate-800/80">
              <strong className="text-slate-200 block mb-1">Calibration Completion</strong>
              Final model anchor locks in 2 days. Satiety decay curves will become fully personalized.
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. ACOUSTIC PROFILE SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderProfileView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-center gap-4">
              <div className="h-16 w-16 rounded-2xl bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center text-slate-950 font-extrabold text-2xl shadow-xl shadow-cyan-500/20">
                {userProfile.name.slice(0, 1)}
              </div>
              <div>
                <h2 className="text-xl font-bold text-white">{userProfile.name}</h2>
                <p className="text-xs text-slate-400 font-mono">{userProfile.email}</p>
                <div className="inline-block mt-1 px-2.5 py-0.5 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 text-xs font-semibold">
                  {userProfile.tier}
                </div>
              </div>
            </div>

            <div className="flex items-center gap-3">
              <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-center">
                <span className="text-[10px] text-slate-500 uppercase font-mono block">Monitored</span>
                <span className="text-sm font-bold text-cyan-400 font-mono">{userProfile.monitoredHours}</span>
              </div>
              <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-center">
                <span className="text-[10px] text-slate-500 uppercase font-mono block">Confidence</span>
                <span className="text-sm font-bold text-emerald-400 font-mono">{userProfile.confidence}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Auditory Anchors Breakdown */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
          <h3 className="text-base font-bold text-white flex items-center gap-2">
            <Activity className="h-4 w-4 text-cyan-400" />
            Auditory Anchor Architecture
          </h3>
          <p className="text-xs text-slate-400 leading-relaxed">
            Auditory anchors are continuously updated behavioral weights that capture how your listening attention
            relies on tempo lock, melodic familiarity, and dynamic frequency changes.
          </p>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2">
            {anchorWeights.map((anchor) => (
              <div key={anchor.name} className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold text-slate-200">{anchor.name}</span>
                  <span className="text-cyan-400 font-bold font-mono">{anchor.percentage}%</span>
                </div>
                <div className="w-full bg-slate-800 rounded-full h-2">
                  <div
                    className="bg-cyan-500 h-2 rounded-full"
                    style={{ width: `${anchor.percentage}%` }}
                  ></div>
                </div>
                <div className="text-xs text-slate-400">{anchor.description}</div>
                <div className="text-[11px] font-mono text-cyan-400/90 bg-cyan-950/40 p-1.5 rounded">
                  7-Day Drift: {anchor.driftDelta}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. CROSS-APP AUDIO CAPTURE SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderCrossAppView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-2">
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Layers className="h-5 w-5 text-emerald-400" />
            Connected Audio Sources
          </h2>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            TuneMind acts as a universal acoustic layer across all media players. Android NotificationListenerService
            captures raw metadata without interfering with background audio playback.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {connectedApps.map((app) => (
            <div
              key={app.id}
              className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between"
            >
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <h4 className="text-base font-bold text-slate-100">{app.name}</h4>
                  <span
                    className={`text-[10px] font-mono px-2 py-0.5 rounded ${
                      app.connected
                        ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        : 'bg-slate-800 text-slate-500'
                    }`}
                  >
                    {app.status}
                  </span>
                </div>
                <div className="text-xs font-mono text-slate-500">{app.pkg}</div>
                <div className="text-xs text-slate-400 font-mono pt-1">
                  {rawEventsList.filter((e) => e.media_source === app.pkg).length} live observations stored
                </div>
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => {
                    setConnectedApps(
                      connectedApps.map((a) =>
                        a.id === app.id
                          ? { ...a, connected: !a.connected, status: !a.connected ? 'Streaming' : 'Disconnected' }
                          : a
                      )
                    );
                  }}
                  className={`px-3 py-1.5 rounded-xl text-xs font-semibold transition-all border ${
                    app.connected
                      ? 'bg-slate-800 hover:bg-slate-700 text-slate-300 border-slate-700'
                      : 'bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 border-emerald-500/30'
                  }`}
                >
                  {app.connected ? 'Disconnect' : 'Connect'}
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. RECEPTIVE HORIZON / WEEKLY REPORT SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderWeeklyReportView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-2">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <BarChart3 className="h-5 w-5 text-purple-400" />
              Receptive Horizon & Weekly Intelligence Report
            </h2>
            <span className="text-xs font-mono text-purple-300 bg-purple-500/20 px-2.5 py-1 rounded-full border border-purple-500/30">
              Week 43 Analysis
            </span>
          </div>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            Derived from 87.4 monitored hours. Identifies cyclical pattern shifts, recovery velocity, and acoustic
            freshness recommendations for your next rotation.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <span className="text-xs text-slate-400">Auditory Satiety Index</span>
            <div className="text-3xl font-extrabold text-cyan-400 font-mono">42%</div>
            <div className="text-xs text-emerald-400">-8% from last week (Optimal)</div>
            <p className="text-[11px] text-slate-500 pt-1">
              Listener introduced 14 novel tracks, delaying loop fatigue across electronic staples.
            </p>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <span className="text-xs text-slate-400">Peak Receptive Time</span>
            <div className="text-3xl font-extrabold text-indigo-400 font-mono">10:45 AM</div>
            <div className="text-xs text-indigo-300">Morning Harmonic Window</div>
            <p className="text-[11px] text-slate-500 pt-1">
              Harmonic density tolerance is 3.2x higher than evening sessions. Ideal for complex albums.
            </p>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <span className="text-xs text-slate-400">Vocal Fatigue Reset</span>
            <div className="text-3xl font-extrabold text-amber-400 font-mono">3 Tracks</div>
            <div className="text-xs text-amber-300">Reactivation ready in 48h</div>
            <p className="text-[11px] text-slate-500 pt-1">
              "Stay With Me" and "Hyperion Drive" have recovered 70%+ receptor sensitivity.
            </p>
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. TELEMETRY SETTINGS SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderSettingsView() {
    return (
      <div className="space-y-6">
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-2">
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Settings className="h-5 w-5 text-slate-400" />
            Telemetry & Retention Settings
          </h2>
          <p className="text-xs text-slate-400 leading-relaxed max-w-2xl">
            Configure local observation buffer capacity, ad filtering rules, and backend persistence parameters.
          </p>
        </div>

        <div className="space-y-4">
          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
            <div>
              <h4 className="text-sm font-bold text-slate-100">Durable Disk Storage</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Saved to disk at <code>data/tunemind_events.json</code> on every ingested track.
              </p>
            </div>
            <span className="font-mono text-sm text-emerald-400 font-bold px-3 py-1 rounded bg-slate-950 border border-slate-800">
              {totalStoredEvents} Records
            </span>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
            <div>
              <h4 className="text-sm font-bold text-slate-100">Android Ad Exclusion (ADVERTISEMENT flag)</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Omits records explicitly marked with android.media.metadata.ADVERTISEMENT.
              </p>
            </div>
            <span className="text-xs font-semibold text-emerald-400 px-3 py-1 rounded bg-emerald-500/10 border border-emerald-500/20">
              Enabled
            </span>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
            <div>
              <h4 className="text-sm font-bold text-slate-100">Public Phone Ingestion Endpoint</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                POST /api/v1/events target URL for background sync.
              </p>
            </div>
            <span className="font-mono text-xs text-slate-300 px-3 py-1 rounded bg-slate-950 border border-slate-800 truncate max-w-xs">
              {backendUrl}
            </span>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
            <div>
              <h4 className="text-sm font-bold text-slate-100">Purge Telemetry Database</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Clears all retained raw observations from disk and memory.
              </p>
            </div>
            <button
              onClick={handleClearDatabase}
              className="px-3.5 py-1.5 rounded-xl bg-red-500/20 hover:bg-red-500/30 text-red-300 text-xs font-semibold border border-red-500/30"
            >
              Purge Database
            </button>
          </div>
        </div>
      </div>
    );
  }
}
