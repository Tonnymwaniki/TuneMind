import React, { useState, useEffect } from 'react';
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
  Volume2
} from 'lucide-react';

import {
  TrackItem,
  AnalyticsSummary,
  ReconstructedEpisode,
  DailyStudyTile
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
  const [playbackProgress, setPlaybackProgress] = useState(45); // percentage
  const [currentTrack, setCurrentTrack] = useState<TrackItem>(initialNowPlaying);
  const [highSatietyList, setHighSatietyList] = useState<TrackItem[]>(initialHighSatietyTracks);
  const [palateCleansers, setPalateCleansers] = useState<TrackItem[]>(initialPalateCleansers);
  const [studyTiles, setStudyTiles] = useState<DailyStudyTile[]>(studyDays);
  const [selectedEpisode, setSelectedEpisode] = useState<ReconstructedEpisode | null>(null);
  const [showManualCooldownModal, setShowManualCooldownModal] = useState(false);
  const [analyticsData, setAnalyticsData] = useState<AnalyticsSummary | null>(null);
  const [episodesData, setEpisodesData] = useState<ReconstructedEpisode[]>([]);
  const [serverStatus, setServerStatus] = useState<'checking' | 'connected' | 'offline'>('checking');
  const [telemetryMessage, setTelemetryMessage] = useState<string | null>(null);
  const [connectedApps, setConnectedApps] = useState([
    { id: 'spotify', name: 'Spotify', pkg: 'com.spotify.music', connected: true, observations: 342, status: 'Streaming' },
    { id: 'ytmusic', name: 'YouTube Music', pkg: 'com.google.android.apps.youtube.music', connected: true, observations: 118, status: 'Streaming' },
    { id: 'applemusic', name: 'Apple Music', pkg: 'com.apple.android.music', connected: true, observations: 56, status: 'Idle' },
    { id: 'soundcloud', name: 'SoundCloud', pkg: 'com.soundcloud.android', connected: false, observations: 0, status: 'Disconnected' }
  ]);

  // Fetch backend telemetry analytics on load
  const fetchAnalytics = async () => {
    try {
      const healthRes = await fetch('/health');
      if (healthRes.ok) {
        setServerStatus('connected');
      } else {
        setServerStatus('offline');
      }

      const summaryRes = await fetch('/api/v1/analytics/summary?userId=tunemind-dev');
      if (summaryRes.ok) {
        const data = await summaryRes.json();
        setAnalyticsData(data);
      }

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

  useEffect(() => {
    fetchAnalytics();
  }, []);

  // Playback timer ticker simulation
  useEffect(() => {
    if (!isPlaying) return;
    const interval = setInterval(() => {
      setPlaybackProgress((prev) => (prev >= 100 ? 0 : prev + 0.5));
    }, 1000);
    return () => clearInterval(interval);
  }, [isPlaying]);

  // Send real live event to backend
  const sendLiveEvent = async (type: string, track: TrackItem) => {
    try {
      const eventId = 'evt-live-' + Math.random().toString(36).substring(2, 9);
      const payload = {
        events: [
          {
            eventId,
            userId: 'tunemind-dev',
            sessionId: 'obs-sess-live-01',
            deviceId: 'pixel-8-pro',
            schemaVersion: '1.0',
            eventType: type,
            occurredAt: new Date().toISOString(),
            receivedAt: new Date().toISOString(),
            mediaSource: track.sourceApp === 'YT Music' ? 'com.google.android.apps.youtube.music' : 'com.spotify.music',
            payload: {
              playback: {
                positionMs: Math.round((playbackProgress / 100) * track.durationSeconds * 1000),
                elapsedRealtimeMs: Date.now(),
                isPlaying: type !== 'OBSERVATION_STOPPED',
                playbackSpeed: 1.0,
                playbackStateCode: type === 'OBSERVATION_STOPPED' ? 2 : 3
              },
              track: {
                trackId: track.id,
                title: track.title,
                artist: track.artist,
                album: 'Live Session Stream',
                durationMs: track.durationSeconds * 1000
              }
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
        setTelemetryMessage(`Accepted ${type} telemetry event for "${track.title}"`);
        setTimeout(() => setTelemetryMessage(null), 3500);
        fetchAnalytics();
      }
    } catch (err) {
      console.error('Failed to post telemetry event:', err);
    }
  };

  const handleTogglePlay = () => {
    const nextState = !isPlaying;
    setIsPlaying(nextState);
    sendLiveEvent(nextState ? 'PLAYBACK_STATE_CHANGED' : 'OBSERVATION_STOPPED', currentTrack);
  };

  const handleSkipTrack = () => {
    sendLiveEvent('PLAYBACK_STATE_CHANGED', currentTrack);
    const nextTrack = candidateCooldownTracks[Math.floor(Math.random() * candidateCooldownTracks.length)];
    setCurrentTrack(nextTrack);
    setPlaybackProgress(0);
    sendLiveEvent('METADATA_CHANGED', nextTrack);
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

  return (
    <div className="flex h-screen w-full bg-slate-950 text-slate-100 overflow-hidden font-sans">
      {/* Sidebar for Desktop Navigation */}
      <aside className="hidden md:flex flex-col w-64 border-r border-slate-800/70 bg-slate-900/60 backdrop-blur-xl p-4 justify-between shrink-0">
        <div>
          {/* Logo & Platform Title */}
          <div className="flex items-center gap-3 px-3 py-3 mb-6">
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
              onClick={() => setCurrentTab('history')}
              className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all ${
                currentTab === 'history'
                  ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
              }`}
            >
              <History className="h-4 w-4" />
              Reconstructed Sessions
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
            <span className="text-slate-400">Backend API</span>
            <span
              className={`flex items-center gap-1.5 font-medium ${
                serverStatus === 'connected' ? 'text-emerald-400' : 'text-amber-400'
              }`}
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  serverStatus === 'connected' ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'
                }`}
              ></span>
              {serverStatus === 'connected' ? 'Port 3000 OK' : 'Checking'}
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
            Telemetry Settings
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
                Listening Pattern Intelligence
              </span>
              <h2 className="text-lg font-bold text-slate-100 capitalize">
                {currentTab.replace('_', ' ')}
              </h2>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Live Observation Ticker */}
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-900 border border-slate-800 text-xs">
              <Radio className="h-3.5 w-3.5 text-cyan-400 animate-pulse" />
              <span className="text-slate-300 hidden sm:inline">Telemetry Active</span>
              <span className="text-slate-500">|</span>
              <span className="text-slate-400 font-mono text-[11px]">
                {analyticsData?.totalEvents ?? 28} raw events
              </span>
            </div>

            <button
              onClick={() => fetchAnalytics()}
              title="Refresh telemetry"
              className="p-2 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-400 hover:text-slate-200 transition-colors"
            >
              <RefreshCw className="h-4 w-4" />
            </button>
          </div>
        </header>

        {/* Telemetry notification banner if an action was fired */}
        {telemetryMessage && (
          <div className="mx-4 md:mx-8 mt-4 p-3 rounded-xl bg-cyan-950/80 border border-cyan-800/80 text-cyan-200 text-xs flex items-center justify-between shadow-lg shadow-cyan-950/40 animate-in fade-in">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-cyan-400 animate-pulse" />
              <span>{telemetryMessage}</span>
            </div>
            <span className="text-[10px] font-mono text-cyan-400/80">HTTP 200 OK</span>
          </div>
        )}

        {/* Page Content View Router */}
        <div className="p-4 md:p-8 space-y-6 max-w-7xl mx-auto w-full">
          {currentTab === 'home' && renderHomeView()}
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
          onClick={() => setCurrentTab('fatigue')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'fatigue' ? 'text-amber-400' : 'text-slate-400'
          }`}
        >
          <Flame className="h-4 w-4" />
          <span>Fatigue</span>
        </button>
        <button
          onClick={() => setCurrentTab('cooldown')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'cooldown' ? 'text-cyan-400' : 'text-slate-400'
          }`}
        >
          <Snowflake className="h-4 w-4" />
          <span>Cooldown</span>
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
          onClick={() => setCurrentTab('study')}
          className={`flex flex-col items-center gap-1 p-2 rounded-lg text-xs ${
            currentTab === 'study' ? 'text-indigo-400' : 'text-slate-400'
          }`}
        >
          <Calendar className="h-4 w-4" />
          <span>Study</span>
        </button>
      </nav>

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
  // 1. HOME SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderHomeView() {
    return (
      <div className="space-y-6">
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
                Live Audio Session Capture
              </div>
              <span className="text-xs font-mono text-slate-400 px-2 py-0.5 rounded bg-slate-800/80 border border-slate-700/60">
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
                    {currentTrack.playCount} Total Plays
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
              <div className="p-3 rounded-xl bg-slate-950/90 border border-slate-800 space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-slate-400 flex items-center gap-1.5">
                    <Database className="h-3.5 w-3.5 text-cyan-400" />
                    SQLite Telemetry Buffer
                  </span>
                  <span className="font-mono text-cyan-300">
                    {analyticsData?.totalEvents ?? 28} / 500 records
                  </span>
                </div>
                <p className="text-[11px] text-slate-500 leading-relaxed">
                  Raw observations retain 500 rows. Reconstructed on demand without external network calls.
                </p>
                <button
                  onClick={() => sendLiveEvent('METADATA_CHANGED', currentTrack)}
                  className="w-full py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-mono transition-colors border border-slate-700 flex items-center justify-center gap-1.5"
                >
                  <Activity className="h-3.5 w-3.5 text-cyan-400" />
                  Emit Observation Callback
                </button>
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

        {/* Palate Cleanser Recommendations Section */}
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-base font-bold text-white flex items-center gap-2">
                <Sparkles className="h-4 w-4 text-cyan-400" />
                Acoustic Palate Cleansers
              </h3>
              <p className="text-xs text-slate-400 mt-0.5">
                Low satiety tracks engineered to reset auditory receptors while preserving groove momentum.
              </p>
            </div>
            <button
              onClick={() => setCurrentTab('fatigue')}
              className="text-xs text-cyan-400 hover:text-cyan-300 font-medium flex items-center gap-1"
            >
              Fatigue Deck <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {palateCleansers.map((pc) => (
              <div
                key={pc.id}
                className="p-4 rounded-xl bg-slate-950/80 border border-slate-800 hover:border-cyan-500/40 transition-all flex flex-col justify-between"
              >
                <div className="flex items-start gap-3">
                  <img
                    src={pc.albumCoverUrl}
                    alt={pc.title}
                    className="h-14 w-14 rounded-xl object-cover shrink-0"
                  />
                  <div>
                    <div className="text-sm font-bold text-slate-100">{pc.title}</div>
                    <div className="text-xs text-slate-400">{pc.artist}</div>
                    <div className="text-[10px] text-cyan-400/90 font-mono mt-1">
                      {pc.genre}
                    </div>
                  </div>
                </div>

                <div className="mt-4 pt-3 border-t border-slate-800/80 flex items-center justify-between">
                  <span className="text-[11px] text-emerald-400 font-mono font-medium">
                    {pc.fatigueCategory}
                  </span>
                  <button
                    onClick={() => {
                      setCurrentTrack(pc);
                      setIsPlaying(true);
                      setPlaybackProgress(0);
                      sendLiveEvent('PLAYBACK_STATE_CHANGED', pc);
                    }}
                    className="px-2.5 py-1 rounded-lg bg-cyan-500/20 hover:bg-cyan-500/30 text-cyan-300 text-xs font-medium flex items-center gap-1 border border-cyan-500/30"
                  >
                    <Play className="h-3 w-3" /> Play
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
  // 2. SONG FATIGUE & SATIETY SCREEN
  // ─────────────────────────────────────────────────────────────────────────────
  function renderFatigueView() {
    return (
      <div className="space-y-6">
        {/* Header & Overview */}
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
  // 3. COOLDOWN VAULT SCREEN
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
  // 4. RECONSTRUCTED SESSIONS & HISTORY SCREEN
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
            Derived on demand from retained raw observations using deterministic wall-clock sorting and callback
            span grouping. No user identification or cloud telemetry is transmitted.
          </p>
        </div>

        {/* Source breakdown cards */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Total Observations</span>
            <div className="text-xl font-bold text-white font-mono mt-1">
              {analyticsData?.totalEvents ?? 28}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Retained rows</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Spotify Telemetry</span>
            <div className="text-xl font-bold text-emerald-400 font-mono mt-1">
              {analyticsData?.bySource?.['com.spotify.music'] ?? 21}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Direct callbacks</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">YouTube Music</span>
            <div className="text-xl font-bold text-red-400 font-mono mt-1">
              {analyticsData?.bySource?.['com.google.android.apps.youtube.music'] ?? 7}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Direct callbacks</div>
          </div>
          <div className="p-3.5 rounded-xl bg-slate-900 border border-slate-800">
            <span className="text-xs text-slate-400">Observer Sessions</span>
            <div className="text-xl font-bold text-cyan-400 font-mono mt-1">
              {analyticsData?.distinctSessionCount ?? 7}
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5">Bounded connections</div>
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
                      {ep.sourcePackage || 'com.spotify.music'}
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
              <button
                onClick={() => sendLiveEvent('PLAYBACK_STATE_CHANGED', currentTrack)}
                className="px-4 py-2 rounded-xl bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-xs font-semibold"
              >
                Emit Sample Event
              </button>
            </div>
          )}
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. SESSION DETAILS SCREEN
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
              <p className="text-xs text-slate-400 font-mono mt-0.5">{ep.sourcePackage}</p>
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
  // 6. 7-DAY BASELINE STUDY SCREEN
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
  // 7. ACOUSTIC PROFILE SCREEN
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
  // 8. CROSS-APP AUDIO CAPTURE SCREEN
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
                  {app.observations} observations logged
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
  // 9. RECEPTIVE HORIZON / WEEKLY REPORT SCREEN
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
  // 10. TELEMETRY SETTINGS SCREEN
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
              <h4 className="text-sm font-bold text-slate-100">Local Observation Retention Cap</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Maximum observations stored in private database before FIFO rotation.
              </p>
            </div>
            <span className="font-mono text-sm text-cyan-400 font-bold px-3 py-1 rounded bg-slate-950 border border-slate-800">
              500 Records
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
              <h4 className="text-sm font-bold text-slate-100">Backend Server Ingestion Target</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Target endpoint for sync worker batch uploads (POST /api/v1/events).
              </p>
            </div>
            <span className="font-mono text-xs text-slate-300 px-3 py-1 rounded bg-slate-950 border border-slate-800">
              http://0.0.0.0:3000
            </span>
          </div>

          <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
            <div>
              <h4 className="text-sm font-bold text-slate-100">Purge Local Event Buffer</h4>
              <p className="text-xs text-slate-400 mt-0.5">
                Clears all retained raw observations from memory. Derived summaries will reset.
              </p>
            </div>
            <button
              onClick={() => {
                setTelemetryMessage('Telemetry observation buffer purged.');
                setTimeout(() => setTelemetryMessage(null), 3000);
              }}
              className="px-3 py-1.5 rounded-xl bg-red-500/20 hover:bg-red-500/30 text-red-300 text-xs font-semibold border border-red-500/30"
            >
              Purge Buffer
            </button>
          </div>
        </div>
      </div>
    );
  }
}
