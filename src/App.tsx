import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  Volume2,
  Play,
  Pause,
  Sparkles,
  Radio,
  Zap
} from 'lucide-react';

type AgentState = 'IDLE' | 'LISTENING' | 'THINKING' | 'SPEAKING' | 'ERROR';
type ScreenTab = 'DOPAMINE' | 'AGENT' | 'RHYTHM';

interface DopamineTrack {
  id: string;
  title: string;
  artist: string;
  freshness: number;
  playCount: number;
  bpm: number;
  energy: number;
  isCoolingDown: boolean;
}

interface ReasoningApiResponse {
  status: string;
  calculated_satiety_percentage: number;
  flow_state_analysis: string;
  voice_agent_tts: string;
  os_execution_command: {
    intent: string;
    target_player: string;
    search_query: string;
    suggested_bpm_target: number;
    track_title?: string;
    track_artist?: string;
  };
  database_tags: string[];
  voice_agent_audio_base64?: string | null;
}

const INITIAL_SONGS: DopamineTrack[] = [
  { id: '1', title: 'Anaweza Yote', artist: 'The Adventist Police Ministers', freshness: 97, playCount: 1, bpm: 98, energy: 0.72, isCoolingDown: false },
  { id: '2', title: 'Essence', artist: 'Wizkid, Tems', freshness: 94, playCount: 1, bpm: 104, energy: 0.78, isCoolingDown: false },
  { id: '3', title: 'Malaika', artist: 'Sauti Sol', freshness: 91, playCount: 2, bpm: 102, energy: 0.65, isCoolingDown: false },
  { id: '4', title: 'Last Last', artist: 'Burna Boy', freshness: 88, playCount: 2, bpm: 115, energy: 0.84, isCoolingDown: false },
  { id: '5', title: 'Calm Down', artist: 'Rema', freshness: 34, playCount: 6, bpm: 107, energy: 0.79, isCoolingDown: true },
  { id: '6', title: 'Starboy', artist: 'The Weeknd', freshness: 28, playCount: 7, bpm: 186, energy: 0.85, isCoolingDown: true },
];

const CIRCADIAN_PHASES = [
  { id: 0, label: 'MORNING FOCUS', short: 'MORNING', hours: '06:00 – 12:00', bpm: 112, color: '#00E5FF', dashOffset: 0 },
  { id: 1, label: 'MIDDAY DRIVE', short: 'MIDDAY', hours: '12:00 – 17:00', bpm: 118, color: '#10B981', dashOffset: -110 },
  { id: 2, label: 'EVENING UNWIND', short: 'EVENING', hours: '17:00 – 22:00', bpm: 98, color: '#FFB000', dashOffset: -220 },
  { id: 3, label: 'LATE-NIGHT FLOW', short: 'NIGHT', hours: '22:00 – 06:00', bpm: 86, color: '#9D4EDD', dashOffset: -330 },
];

export default function App() {
  // Default to Center Voice Orb ('AGENT')
  const [activeScreen, setActiveScreen] = useState<ScreenTab>('AGENT');
  const [vaultFilter, setVaultFilter] = useState<'FRESH' | 'COOLING'>('FRESH');
  const [selectedPhaseIdx, setSelectedPhaseIdx] = useState<number>(1);
  const [targetPlayer, setTargetPlayer] = useState<string>('com.spotify.music');

  const [songs, setSongs] = useState<DopamineTrack[]>(INITIAL_SONGS);
  const [currentTrack, setCurrentTrack] = useState<DopamineTrack>(INITIAL_SONGS[0]);
  const [agentState, setAgentState] = useState<AgentState>('IDLE');
  const [satietyPercentage, setSatietyPercentage] = useState<number>(22);
  const [liveTranscript, setLiveTranscript] = useState<string>('');
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // Touch swipe tracking for mobile ergonomics
  const touchStartX = useRef<number | null>(null);

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (touchStartX.current === null) return;
    const deltaX = e.changedTouches[0].clientX - touchStartX.current;
    if (Math.abs(deltaX) > 65) {
      if (deltaX > 0) {
        // Swipe Right -> go left in screens
        if (activeScreen === 'AGENT') setActiveScreen('DOPAMINE');
        else if (activeScreen === 'RHYTHM') setActiveScreen('AGENT');
      } else {
        // Swipe Left -> go right in screens
        if (activeScreen === 'DOPAMINE') setActiveScreen('AGENT');
        else if (activeScreen === 'AGENT') setActiveScreen('RHYTHM');
      }
    }
    touchStartX.current = null;
  };

  const targetOrbColor = {
    LISTENING: '#00E5FF',
    THINKING: '#7000FF',
    SPEAKING: '#FFB000',
    ERROR: '#FF2A55',
    IDLE: '#00E5FF',
  }[agentState];

  const satietyColor = satietyPercentage > 75 ? '#FF2A55' : satietyPercentage > 50 ? '#FFB000' : '#00F0FF';

  const speakNative = (text: string, onDone?: () => void) => {
    if (!('speechSynthesis' in window)) {
      setTimeout(() => onDone?.(), 2200);
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 1.06;
    utterance.onend = () => onDone?.();
    utterance.onerror = () => onDone?.();
    window.speechSynthesis.speak(utterance);
  };

  const executeReasoning = async (command: string) => {
    setAgentState('THINKING');
    setLiveTranscript(command);

    try {
      const response = await fetch('/api/v1/reasoning/evaluate-satiety', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          timestamp_utc: new Date().toISOString(),
          circadian_daypart: CIRCADIAN_PHASES[selectedPhaseIdx].label,
          current_track: {
            title: currentTrack.title,
            artist: currentTrack.artist,
            source_player: targetPlayer,
          },
          user_command: command,
          rolling_history: songs.map(s => ({
            title: s.title,
            artist: s.artist,
            bpm: s.bpm,
          })),
          current_satiety_percentage: satietyPercentage,
        }),
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data: ReasoningApiResponse = await response.json();

      setSatietyPercentage(data.calculated_satiety_percentage || 24);
      setLiveTranscript(data.voice_agent_tts);
      setAgentState('SPEAKING');

      if (data.os_execution_command?.track_title) {
        const matched = songs.find(
          s => s.title.toLowerCase() === data.os_execution_command.track_title?.toLowerCase()
        );
        if (matched && data.os_execution_command.intent === 'OS_MEDIA_NEXT_AND_DEEP_LINK') {
          setCurrentTrack(matched);
        }
      }

      speakNative(data.voice_agent_tts, () => {
        setAgentState('IDLE');
      });
    } catch {
      const fallbackLine = `How about "${songs[1].title}" by ${songs[1].artist}? It's sitting at 94% freshness in your Dopamine Vault—want me to play it now?`;
      setLiveTranscript(fallbackLine);
      setAgentState('SPEAKING');
      speakNative(fallbackLine, () => setAgentState('IDLE'));
    }
  };

  const handleOrbClick = () => {
    if (agentState === 'SPEAKING' || agentState === 'THINKING') {
      window.speechSynthesis?.cancel();
      setAgentState('LISTENING');
      setLiveTranscript('Listening to you...');
      setTimeout(() => {
        executeReasoning('Play me a fresh song from my rotation');
      }, 1800);
      return;
    }

    if (agentState === 'IDLE') {
      setAgentState('LISTENING');
      setLiveTranscript('Listening to you...');

      if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
        const SpeechRec = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
        try {
          const recognition = new SpeechRec();
          recognition.continuous = false;
          recognition.interimResults = true;
          recognition.lang = 'en-US';

          let captured = '';
          recognition.onresult = (event: any) => {
            captured = Array.from(event.results)
              .map((res: any) => res[0].transcript)
              .join('');
            setLiveTranscript(captured);
          };

          recognition.onend = () => {
            executeReasoning(captured.trim() || 'Play me a song');
          };

          recognition.onerror = () => {
            executeReasoning('Play me a song');
          };

          recognition.start();
          return;
        } catch {
          // Fallback below
        }
      }

      setTimeout(() => {
        executeReasoning('Play me a song');
      }, 1700);
    }
  };

  const handlePlaySongCard = (track: DopamineTrack) => {
    setCurrentTrack(track);
    setSatietyPercentage(100 - track.freshness);
    const spoken = `Playing ${track.title} by ${track.artist} now.`;
    setLiveTranscript(spoken);
    setAgentState('SPEAKING');
    speakNative(spoken, () => setAgentState('IDLE'));
  };

  const handleAskAboutSong = (track: DopamineTrack) => {
    setActiveScreen('AGENT');
    executeReasoning(`Tell me about ${track.title} by ${track.artist} and play it if it fits my flow`);
  };

  const freshSongs = songs.filter(s => !s.isCoolingDown);
  const coolingSongs = songs.filter(s => s.isCoolingDown);
  const displayedVaultSongs = vaultFilter === 'FRESH' ? freshSongs : coolingSongs;
  const avgFreshness = Math.round(songs.reduce((acc, s) => acc + s.freshness, 0) / songs.length);
  const activePhase = CIRCADIAN_PHASES[selectedPhaseIdx];

  return (
    <div
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
      className="min-h-screen bg-[#050508] text-white flex flex-col justify-between relative overflow-hidden select-none font-sans"
    >
      <audio ref={audioRef} className="hidden" />

      {/* MAIN 3-SCREEN CANVAS */}
      <main className="flex-1 w-full max-w-md mx-auto px-5 pt-6 pb-28 flex flex-col">
        {/* SCREEN 0: DOPAMINE VAULT (FRESH VS OVERPLAYED) */}
        {activeScreen === 'DOPAMINE' && (
          <div className="flex-1 flex flex-col space-y-4 animate-fadeIn">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-[11px] font-bold tracking-[0.2em] text-[#00E5FF] block">
                  DOPAMINE VAULT
                </span>
                <h1 className="text-2xl font-bold text-white mt-0.5">Fresh vs. Overplayed</h1>
              </div>
              <div className="px-3 py-1.5 rounded-2xl bg-[#00E5FF]/12 border border-[#00E5FF]/35 text-[#00E5FF] text-[11px] font-bold tracking-wider">
                {avgFreshness}% FRESH
              </div>
            </div>

            {/* Dopamine Readiness Ring Card */}
            <div className="rounded-[28px] bg-white/[0.04] border border-white/[0.08] p-4 flex items-center gap-4">
              <div className="relative w-16 h-16 flex items-center justify-center shrink-0">
                <svg className="w-full h-full -rotate-90" viewBox="0 0 72 72">
                  <circle
                    cx="36"
                    cy="36"
                    r="28"
                    stroke="rgba(255,255,255,0.08)"
                    strokeWidth="6"
                    fill="none"
                  />
                  <circle
                    cx="36"
                    cy="36"
                    r="28"
                    stroke={vaultFilter === 'FRESH' ? '#00E5FF' : '#FFB000'}
                    strokeWidth="6"
                    fill="none"
                    strokeDasharray={`${(avgFreshness / 100) * 175} 175`}
                    strokeLinecap="round"
                  />
                </svg>
                <span className="absolute text-sm font-bold text-white">{avgFreshness}%</span>
              </div>
              <div>
                <h2 className="text-sm font-bold text-white">
                  {vaultFilter === 'FRESH'
                    ? `${freshSongs.length} Peak Dopamine Tracks`
                    : `${coolingSongs.length} Tracks Cooling Down`}
                </h2>
                <p className="text-xs text-white/60 mt-1 leading-relaxed">
                  {vaultFilter === 'FRESH'
                    ? 'Tap any song to play immediately or tap Ask to talk to your agent.'
                    : 'Resting from heavy rotation so they hit like the first time.'}
                </p>
              </div>
            </div>

            {/* 2-Pill Filter Toggle */}
            <div className="grid grid-cols-2 gap-1.5 p-1 rounded-full bg-white/[0.05] border border-white/[0.08]">
              <button
                onClick={() => setVaultFilter('FRESH')}
                className={`py-2.5 rounded-full text-[11px] font-bold tracking-wider transition-all ${
                  vaultFilter === 'FRESH'
                    ? 'bg-[#00E5FF]/20 text-white border border-[#00E5FF]/45'
                    : 'text-white/50 hover:text-white'
                }`}
              >
                ⚡ PEAK DOPAMINE ({freshSongs.length})
              </button>
              <button
                onClick={() => setVaultFilter('COOLING')}
                className={`py-2.5 rounded-full text-[11px] font-bold tracking-wider transition-all ${
                  vaultFilter === 'COOLING'
                    ? 'bg-[#FFB000]/20 text-white border border-[#FFB000]/45'
                    : 'text-white/50 hover:text-white'
                }`}
              >
                ⏳ COOLING DOWN ({coolingSongs.length})
              </button>
            </div>

            {/* Interactive Song Cards */}
            <div className="space-y-2.5 flex-1 overflow-y-auto pr-0.5">
              {displayedVaultSongs.map(song => {
                const isPlayingNow = currentTrack.id === song.id;
                const accent = isPlayingNow ? '#10B981' : song.isCoolingDown ? '#FFB000' : '#00E5FF';
                return (
                  <div
                    key={song.id}
                    onClick={() => handlePlaySongCard(song)}
                    className="rounded-3xl bg-white/[0.04] hover:bg-white/[0.07] border border-white/[0.08] p-3.5 flex items-center gap-3.5 cursor-pointer transition-all active:scale-[0.98]"
                    style={{
                      borderColor: isPlayingNow ? 'rgba(16,185,129,0.45)' : undefined,
                    }}
                  >
                    {/* 48x48 Play Ring */}
                    <div className="relative w-12 h-12 flex items-center justify-center shrink-0">
                      <svg className="w-full h-full -rotate-90" viewBox="0 0 48 48">
                        <circle cx="24" cy="24" r="20" fill={`${accent}22`} />
                        <circle
                          cx="24"
                          cy="24"
                          r="20"
                          stroke={accent}
                          strokeWidth="3"
                          fill="none"
                          strokeDasharray={`${(song.freshness / 100) * 125} 125`}
                          strokeLinecap="round"
                        />
                      </svg>
                      {isPlayingNow ? (
                        <Pause className="w-4 h-4 absolute" style={{ color: accent }} />
                      ) : (
                        <Play className="w-4 h-4 absolute ml-0.5" style={{ color: accent }} />
                      )}
                    </div>

                    {/* Title & Artist */}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-bold text-white truncate">{song.title}</p>
                      <p className="text-xs text-white/60 truncate">{song.artist}</p>
                      <span
                        className="inline-block mt-1 px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wider"
                        style={{ backgroundColor: `${accent}24`, color: accent }}
                      >
                        {isPlayingNow
                          ? 'NOW PLAYING'
                          : song.isCoolingDown
                          ? `COOLING • ${song.playCount}x`
                          : `${song.freshness}% FRESH`}
                      </span>
                    </div>

                    {/* Ask Agent Button */}
                    <button
                      onClick={e => {
                        e.stopPropagation();
                        handleAskAboutSong(song);
                      }}
                      className="min-w-[54px] min-h-[44px] px-3 py-2 rounded-2xl bg-white/[0.06] hover:bg-white/[0.12] border border-white/[0.12] text-[#00E5FF] text-xs font-semibold shrink-0"
                    >
                      🎙 Ask
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* SCREEN 1: CENTER HOME — 100% DEDICATED LIVING VOICE ORB */}
        {activeScreen === 'AGENT' && (
          <div className="flex-1 flex flex-col justify-between items-center py-2">
            {/* Top Now Syncing Pill */}
            <div className="w-full rounded-[28px] bg-white/[0.04] border border-white/[0.08] px-5 py-3.5 text-center">
              <div className="flex items-center justify-center gap-1.5">
                <span
                  className="w-2 h-2 rounded-full animate-pulse"
                  style={{ backgroundColor: targetOrbColor }}
                />
                <span
                  className="text-[10px] font-bold tracking-[0.2em] uppercase"
                  style={{ color: targetOrbColor }}
                >
                  NOW SYNCING
                </span>
              </div>
              <h2 className="text-base font-bold text-white mt-1 truncate">{currentTrack.title}</h2>
              <p className="text-xs text-white/60 truncate">{currentTrack.artist}</p>
            </div>

            {/* Center 300px Living Voice Orb & Satiety Arc */}
            <div className="flex flex-col items-center my-auto">
              <div className="relative w-72 h-72 flex items-center justify-center">
                {/* Ambient Glow */}
                <div
                  className="absolute w-52 h-52 rounded-full blur-[55px] pointer-events-none transition-all duration-700"
                  style={{ backgroundColor: `${targetOrbColor}3D` }}
                />

                {/* Satiety & Dopamine Arc */}
                <svg className="absolute inset-0 w-full h-full -rotate-90 pointer-events-none" viewBox="0 0 240 240">
                  <circle
                    cx="120"
                    cy="120"
                    r="104"
                    stroke="rgba(255,255,255,0.06)"
                    strokeWidth="6"
                    fill="none"
                    strokeDasharray="490 170"
                    strokeLinecap="round"
                  />
                  <circle
                    cx="120"
                    cy="120"
                    r="104"
                    stroke={satietyColor}
                    strokeWidth="6"
                    fill="none"
                    strokeDasharray={`${((100 - satietyPercentage) / 100) * 490} 660`}
                    strokeLinecap="round"
                    className="transition-all duration-700"
                  />
                </svg>

                {/* Interactive Living Orb Sphere */}
                <button
                  onClick={handleOrbClick}
                  className="relative w-38 h-38 rounded-full flex items-center justify-center cursor-pointer transition-transform duration-300 active:scale-92"
                  style={{
                    background: `radial-gradient(circle at 35% 35%, rgba(255,255,255,0.45) 0%, ${targetOrbColor} 62%, rgba(5,5,8,0.95) 100%)`,
                    boxShadow: `0 0 48px ${targetOrbColor}66`,
                  }}
                >
                  <div className="w-20 h-20 rounded-full bg-white/20 backdrop-blur-sm flex items-center justify-center">
                    {agentState === 'LISTENING' && <Mic className="w-8 h-8 text-black animate-pulse" />}
                    {agentState === 'THINKING' && <Sparkles className="w-8 h-8 text-white animate-spin" />}
                    {agentState === 'SPEAKING' && <Volume2 className="w-8 h-8 text-black animate-bounce" />}
                    {agentState === 'IDLE' && <Radio className="w-7 h-7 text-white" />}
                  </div>
                </button>
              </div>

              <span
                className="text-[11px] font-bold tracking-[0.2em] uppercase mt-2"
                style={{ color: agentState === 'IDLE' ? 'rgba(255,255,255,0.5)' : targetOrbColor }}
              >
                {agentState === 'IDLE'
                  ? 'TAP ORB TO TALK'
                  : agentState === 'LISTENING'
                  ? 'LISTENING TO YOU...'
                  : agentState === 'THINKING'
                  ? 'SYNTHESIZING FLOW...'
                  : 'AGENT SPEAKING • TAP TO INTERRUPT'}
              </span>
            </div>

            {/* Bottom Live Dialogue Card (Above Floating Dock) */}
            <div className="w-full min-h-[76px] flex items-end">
              {(agentState !== 'IDLE' || liveTranscript) && (
                <div className="w-full rounded-[28px] bg-white/[0.04] border border-white/[0.08] p-4 text-center">
                  <span
                    className="text-[10px] font-bold tracking-[0.2em] uppercase block"
                    style={{ color: targetOrbColor }}
                  >
                    {agentState === 'IDLE' ? 'LAST DIALOGUE' : agentState}
                  </span>
                  <p className="text-sm text-white/90 mt-1.5 leading-relaxed">
                    "{liveTranscript || 'Tap the Orb and tell me what you feel like hearing.'}"
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* SCREEN 2: RHYTHM DNA (24H CIRCADIAN SONIC RING & POWER ROTATION) */}
        {activeScreen === 'RHYTHM' && (
          <div className="flex-1 flex flex-col space-y-4 animate-fadeIn">
            <div className="flex items-center justify-between">
              <div>
                <span
                  className="text-[11px] font-bold tracking-[0.2em] block"
                  style={{ color: activePhase.color }}
                >
                  RHYTHM DNA
                </span>
                <h1 className="text-2xl font-bold text-white mt-0.5">Circadian Sonic Flow</h1>
              </div>
              <div
                className="px-3 py-1.5 rounded-2xl text-[10px] font-bold tracking-wider border"
                style={{
                  backgroundColor: `${activePhase.color}20`,
                  borderColor: `${activePhase.color}55`,
                  color: activePhase.color,
                }}
              >
                ☁️ CLOUD BRAIN
              </div>
            </div>

            {/* 24-Hour Circadian Sonic Ring */}
            <div className="rounded-[28px] bg-white/[0.04] border border-white/[0.08] p-4 flex flex-col items-center">
              <div className="relative w-40 h-40 flex items-center justify-center">
                <svg className="w-full h-full -rotate-90" viewBox="0 0 160 160">
                  {CIRCADIAN_PHASES.map((phase, idx) => {
                    const isSelected = idx === selectedPhaseIdx;
                    return (
                      <circle
                        key={phase.id}
                        cx="80"
                        cy="80"
                        r="64"
                        stroke={phase.color}
                        strokeOpacity={isSelected ? 0.95 : 0.22}
                        strokeWidth={isSelected ? '11' : '6'}
                        fill="none"
                        strokeDasharray="88 314"
                        strokeDashoffset={phase.dashOffset}
                        strokeLinecap="round"
                      />
                    );
                  })}
                </svg>
                <div className="text-center">
                  <span
                    className="text-[10px] font-bold tracking-wider block"
                    style={{ color: activePhase.color }}
                  >
                    {activePhase.label}
                  </span>
                  <span className="text-2xl font-bold text-white block mt-0.5">
                    {activePhase.bpm} BPM
                  </span>
                  <span className="text-[11px] text-white/60 block">{activePhase.hours} • 68 dB</span>
                </div>
              </div>

              {/* 4 Phase Selector Pills */}
              <div className="grid grid-cols-4 gap-1.5 w-full mt-3">
                {CIRCADIAN_PHASES.map((phase, idx) => {
                  const isSelected = idx === selectedPhaseIdx;
                  return (
                    <button
                      key={phase.id}
                      onClick={() => setSelectedPhaseIdx(idx)}
                      className="py-2 rounded-xl text-[10px] font-bold tracking-wider border transition-all"
                      style={{
                        backgroundColor: isSelected ? `${phase.color}28` : 'rgba(255,255,255,0.03)',
                        borderColor: isSelected ? `${phase.color}80` : 'rgba(255,255,255,0.07)',
                        color: isSelected ? '#FFFFFF' : 'rgba(255,255,255,0.5)',
                      }}
                    >
                      {phase.short}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Power Rotation Header */}
            <div className="flex items-center justify-between pt-1">
              <span className="text-[11px] font-bold tracking-wider text-white/80">
                🔥 POWER ROTATION • {activePhase.short}
              </span>
              <span className="text-[10px] font-bold" style={{ color: activePhase.color }}>
                TAP TO PLAY
              </span>
            </div>

            {/* Power Rotation Tracks */}
            <div className="space-y-2 flex-1 overflow-y-auto">
              {songs.slice(0, 4).map((song, idx) => (
                <div
                  key={song.id}
                  onClick={() => handlePlaySongCard(song)}
                  className="rounded-3xl bg-white/[0.04] hover:bg-white/[0.07] border border-white/[0.08] p-3 flex items-center gap-3 cursor-pointer transition-all"
                >
                  <div
                    className="w-10 h-10 rounded-full flex items-center justify-center text-xs font-bold border shrink-0"
                    style={{
                      backgroundColor: `${activePhase.color}22`,
                      borderColor: `${activePhase.color}66`,
                      color: activePhase.color,
                    }}
                  >
                    {idx + 1}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-bold text-white truncate">{song.title}</p>
                    <p className="text-xs text-white/60 truncate">{song.artist}</p>
                  </div>
                  <button
                    onClick={e => {
                      e.stopPropagation();
                      handleAskAboutSong(song);
                    }}
                    className="min-w-[52px] min-h-[40px] px-2.5 py-1.5 rounded-xl bg-white/[0.06] text-xs font-semibold"
                    style={{ color: activePhase.color }}
                  >
                    🎙 Ask
                  </button>
                </div>
              ))}
            </div>

            {/* Target Player Strip */}
            <div className="rounded-[24px] bg-white/[0.04] border border-white/[0.08] p-3.5 space-y-2.5">
              <div className="flex items-center justify-between text-[11px]">
                <span className="font-bold text-white/80 tracking-wider">🎧 TARGET PLAYER</span>
                <span className="text-[#10B981] font-semibold">68 dB Safe Headroom</span>
              </div>
              <div className="grid grid-cols-3 gap-2">
                {[
                  { pkg: 'com.spotify.music', label: 'Spotify' },
                  { pkg: 'com.google.android.apps.youtube.music', label: 'YouTube Music' },
                  { pkg: 'com.apple.android.music', label: 'Apple Music' },
                ].map(p => {
                  const isSel = targetPlayer === p.pkg;
                  return (
                    <button
                      key={p.pkg}
                      onClick={() => setTargetPlayer(p.pkg)}
                      className={`py-2.5 rounded-xl text-[11px] font-bold border transition-all ${
                        isSel
                          ? 'bg-[#00E5FF]/20 text-white border-[#00E5FF]/55'
                          : 'bg-white/[0.03] text-white/55 border-white/[0.08]'
                      }`}
                    >
                      {p.label}
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}
      </main>

      {/* CENTERED FLOATING GLASS PILL DOCK */}
      <div className="fixed bottom-5 left-1/2 -translate-x-1/2 z-50">
        <div className="h-[60px] w-[312px] rounded-[32px] bg-[#10121C]/90 backdrop-blur-xl border border-white/[0.14] shadow-2xl p-1.5 grid grid-cols-3 items-center">
          {/* DOPAMINE */}
          <button
            onClick={() => setActiveScreen('DOPAMINE')}
            className={`h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all ${
              activeScreen === 'DOPAMINE'
                ? 'bg-[#00E5FF]/18 text-white border border-[#00E5FF]/45'
                : 'text-white/55 hover:text-white'
            }`}
          >
            <span className="w-1.5 h-1.5 rounded-full bg-[#10B981]" />
            <span>DOPAMINE</span>
          </button>

          {/* CENTER AGENT ORB */}
          <button
            onClick={() => {
              if (activeScreen === 'AGENT') handleOrbClick();
              else setActiveScreen('AGENT');
            }}
            className={`h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all ${
              activeScreen === 'AGENT'
                ? 'bg-[#00E5FF]/20 text-white border border-[#00E5FF]/50'
                : 'text-white/65 hover:text-white'
            }`}
          >
            <span
              className="w-2.5 h-2.5 rounded-full animate-pulse"
              style={{ backgroundColor: targetOrbColor }}
            />
            <span>{agentState !== 'IDLE' ? agentState : 'AGENT'}</span>
          </button>

          {/* RHYTHM */}
          <button
            onClick={() => setActiveScreen('RHYTHM')}
            className={`h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all ${
              activeScreen === 'RHYTHM'
                ? 'bg-[#9D4EDD]/22 text-white border border-[#9D4EDD]/50'
                : 'text-white/55 hover:text-white'
            }`}
          >
            <span>RHYTHM</span>
          </button>
        </div>
      </div>
    </div>
  );
}
