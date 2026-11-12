import React, { useState, useRef } from 'react';
import {
  Mic,
  Volume2,
  Play,
  Pause,
  Sparkles,
  Radio,
  X
} from 'lucide-react';

type AgentState = 'IDLE' | 'LISTENING' | 'THINKING' | 'SPEAKING' | 'ERROR';
type ScreenTab = 'DOPAMINE' | 'AGENT' | 'RHYTHM';
type PaletteId = 'morning' | 'afternoon' | 'evening' | 'night';

interface AtmospherePalette {
  id: PaletteId;
  name: string;
  moodLabel: string;
  bgGradient: string;
  cardBg: string;
  primaryAccent: string;
  secondaryAccent: string;
}

const PALETTES: Record<PaletteId, AtmospherePalette> = {
  morning: {
    id: 'morning',
    name: 'Glacier Dawn',
    moodLabel: 'Calm Sanctuary',
    bgGradient: 'from-[#05101F] via-[#0A1B32] to-[#030912]',
    cardBg: 'rgba(16, 36, 66, 0.72)',
    primaryAccent: '#00E5FF',
    secondaryAccent: '#38BDF8',
  },
  afternoon: {
    id: 'afternoon',
    name: 'Emerald Velocity',
    moodLabel: 'Electric Groove',
    bgGradient: 'from-[#04130E] via-[#092118] to-[#020B08]',
    cardBg: 'rgba(14, 46, 34, 0.72)',
    primaryAccent: '#10B981',
    secondaryAccent: '#00E5FF',
  },
  evening: {
    id: 'evening',
    name: 'Solar Ember',
    moodLabel: 'Golden Uplift',
    bgGradient: 'from-[#160A07] via-[#24110C] to-[#0E0504]',
    cardBg: 'rgba(50, 24, 17, 0.72)',
    primaryAccent: '#FFB000',
    secondaryAccent: '#FF5E36',
  },
  night: {
    id: 'night',
    name: 'Synaptic Violet',
    moodLabel: 'Midnight Reflective',
    bgGradient: 'from-[#080516] via-[#120B29] to-[#04020C]',
    cardBg: 'rgba(27, 17, 58, 0.72)',
    primaryAccent: '#A855F7',
    secondaryAccent: '#00E5FF',
  },
};

interface DopamineTrack {
  id: string;
  title: string;
  artist: string;
  freshness: number;
  playCount: number;
  bpm: number;
  energy: number;
  moodPalette: PaletteId;
  moodName: string;
  isCoolingDown: boolean;
}

const INITIAL_SONGS: DopamineTrack[] = [
  { id: '1', title: 'Anaweza Yote', artist: 'The Adventist Police Ministers', freshness: 97, playCount: 1, bpm: 98, energy: 0.72, moodPalette: 'evening', moodName: 'Golden Uplift', isCoolingDown: false },
  { id: '2', title: 'Essence', artist: 'Wizkid, Tems', freshness: 94, playCount: 1, bpm: 104, energy: 0.78, moodPalette: 'afternoon', moodName: 'Electric Groove', isCoolingDown: false },
  { id: '3', title: 'Malaika', artist: 'Sauti Sol', freshness: 91, playCount: 2, bpm: 102, energy: 0.65, moodPalette: 'morning', moodName: 'Calm Sanctuary', isCoolingDown: false },
  { id: '4', title: 'Last Last', artist: 'Burna Boy', freshness: 88, playCount: 2, bpm: 115, energy: 0.84, moodPalette: 'afternoon', moodName: 'Electric Groove', isCoolingDown: false },
  { id: '5', title: 'Calm Down', artist: 'Rema', freshness: 34, playCount: 6, bpm: 107, energy: 0.79, moodPalette: 'evening', moodName: 'Golden Uplift', isCoolingDown: true },
  { id: '6', title: 'Starboy', artist: 'The Weeknd', freshness: 28, playCount: 7, bpm: 186, energy: 0.85, moodPalette: 'night', moodName: 'Midnight Reflective', isCoolingDown: true },
];

const CIRCADIAN_PHASES: { id: number; paletteId: PaletteId; label: string; short: string; hours: string; bpm: number; color: string; dashOffset: number }[] = [
  { id: 0, paletteId: 'morning', label: 'MORNING FOCUS', short: 'MORNING', hours: '06:00 – 12:00', bpm: 112, color: '#00E5FF', dashOffset: 0 },
  { id: 1, paletteId: 'afternoon', label: 'MIDDAY DRIVE', short: 'MIDDAY', hours: '12:00 – 17:00', bpm: 118, color: '#10B981', dashOffset: -110 },
  { id: 2, paletteId: 'evening', label: 'EVENING UNWIND', short: 'EVENING', hours: '17:00 – 22:00', bpm: 98, color: '#FFB000', dashOffset: -220 },
  { id: 3, paletteId: 'night', label: 'LATE-NIGHT FLOW', short: 'NIGHT', hours: '22:00 – 06:00', bpm: 86, color: '#A855F7', dashOffset: -330 },
];

export default function App() {
  const [activeScreen, setActiveScreen] = useState<ScreenTab>('AGENT');
  const [vaultFilter, setVaultFilter] = useState<'FRESH' | 'COOLING'>('FRESH');
  const [selectedPhaseIdx, setSelectedPhaseIdx] = useState<number>(1);
  const [activePaletteId, setActivePaletteId] = useState<PaletteId>('evening');
  const [isAutoTheme, setIsAutoTheme] = useState<boolean>(true);
  const [targetPlayer, setTargetPlayer] = useState<string>('com.spotify.music');

  const [songs, setSongs] = useState<DopamineTrack[]>(INITIAL_SONGS);
  const [currentTrack, setCurrentTrack] = useState<DopamineTrack>(INITIAL_SONGS[0]);
  const [activeSyncSong, setActiveSyncSong] = useState<DopamineTrack | null>(null);
  const [agentState, setAgentState] = useState<AgentState>('IDLE');
  const [satietyPercentage, setSatietyPercentage] = useState<number>(18);
  const [liveTranscript, setLiveTranscript] = useState<string>('');
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const theme = PALETTES[activePaletteId];

  const cycleAtmospherePalette = () => {
    const order: PaletteId[] = ['morning', 'afternoon', 'evening', 'night'];
    const next = order[(order.indexOf(activePaletteId) + 1) % order.length];
    setIsAutoTheme(false);
    setActivePaletteId(next);
  };

  // Touch swipe tracking
  const touchStartX = useRef<number | null>(null);
  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  };
  const handleTouchEnd = (e: React.TouchEvent) => {
    if (touchStartX.current === null) return;
    const deltaX = e.changedTouches[0].clientX - touchStartX.current;
    if (Math.abs(deltaX) > 70) {
      if (deltaX > 0) {
        if (activeScreen === 'AGENT') setActiveScreen('DOPAMINE');
        else if (activeScreen === 'RHYTHM') setActiveScreen('AGENT');
      } else {
        if (activeScreen === 'DOPAMINE') setActiveScreen('AGENT');
        else if (activeScreen === 'AGENT') setActiveScreen('RHYTHM');
      }
    }
    touchStartX.current = null;
  };

  const targetOrbColor = {
    LISTENING: '#00E5FF',
    THINKING: '#A855F7',
    SPEAKING: '#FFB000',
    ERROR: '#FF2A55',
    IDLE: theme.primaryAccent,
  }[agentState];

  const satietyColor = satietyPercentage > 75 ? '#FF2A55' : satietyPercentage > 50 ? '#FFB000' : theme.secondaryAccent;

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

  const handleOrbClick = () => {
    if (agentState === 'SPEAKING' || agentState === 'THINKING') {
      window.speechSynthesis?.cancel();
      setAgentState('LISTENING');
      setLiveTranscript('Listening to you...');
      return;
    }
    setAgentState('LISTENING');
    setLiveTranscript('Listening to you...');
    setTimeout(() => {
      const nextFresh = songs.find(s => !s.isCoolingDown && s.id !== currentTrack.id) || songs[0];
      const proposal = `How about "${nextFresh.title}" by ${nextFresh.artist}? It's at ${nextFresh.freshness}% dopamine freshness with a ${nextFresh.moodName.toLowerCase()} vibe—want me to spin it right now?`;
      setLiveTranscript(proposal);
      setAgentState('SPEAKING');
      speakNative(proposal, () => setAgentState('IDLE'));
    }, 1500);
  };

  const handlePlaySongButtonOnly = (track: DopamineTrack) => {
    setCurrentTrack(track);
    if (isAutoTheme) {
      setActivePaletteId(track.moodPalette);
    }
    setSatietyPercentage(100 - track.freshness);
    const spoken = `Playing ${track.title} by ${track.artist} now.`;
    setLiveTranscript(spoken);
    setAgentState('SPEAKING');
    speakNative(spoken, () => setAgentState('IDLE'));
  };

  const handleNeuralBreakdown = (track: DopamineTrack) => {
    setActiveSyncSong(null);
    setActiveScreen('AGENT');
    if (isAutoTheme) {
      setActivePaletteId(track.moodPalette);
    }
    const proposal = `${track.title} by ${track.artist} is sitting at ${track.freshness}% dopamine freshness with a ${track.moodName.toLowerCase()} vibe. Want me to spin it for you right now?`;
    setLiveTranscript(proposal);
    setAgentState('SPEAKING');
    speakNative(proposal, () => {
      setAgentState('LISTENING');
      setLiveTranscript('Listening for your approval ("Yes, play it")...');
    });
  };

  const handleToggleCooldown = (track: DopamineTrack) => {
    setSongs(prev =>
      prev.map(s =>
        s.id === track.id
          ? { ...s, isCoolingDown: !s.isCoolingDown, freshness: s.isCoolingDown ? 92 : 30 }
          : s
      )
    );
    setActiveSyncSong(null);
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
      className={`min-h-screen bg-gradient-to-b ${theme.bgGradient} text-white flex flex-col justify-between relative overflow-hidden select-none font-sans transition-colors duration-700`}
    >
      <audio ref={audioRef} className="hidden" />

      {/* Ambient Adaptive Atmosphere Halo */}
      <div
        className="fixed -top-16 right-0 w-72 h-72 rounded-full blur-[95px] pointer-events-none transition-all duration-700"
        style={{ backgroundColor: `${theme.primaryAccent}33` }}
      />

      {/* MAIN 3-SCREEN CANVAS */}
      <main className="flex-1 w-full max-w-md mx-auto px-5 pt-6 pb-28 flex flex-col relative z-10">
        {/* SCREEN 0: DOPAMINE VAULT */}
        {activeScreen === 'DOPAMINE' && (
          <div className="flex-1 flex flex-col space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <span
                  className="text-[11px] font-bold tracking-[0.2em] block"
                  style={{ color: theme.primaryAccent }}
                >
                  DOPAMINE VAULT
                </span>
                <h1 className="text-2xl font-bold text-white mt-0.5">Fresh vs. Overplayed</h1>
              </div>

              <button
                onClick={cycleAtmospherePalette}
                className="px-3 py-1.5 rounded-2xl text-[10px] font-bold tracking-wider border flex items-center gap-1.5 transition-all active:scale-95"
                style={{
                  backgroundColor: `${theme.primaryAccent}22`,
                  borderColor: `${theme.primaryAccent}66`,
                  color: '#FFFFFF',
                }}
              >
                <span className="w-2 h-2 rounded-full" style={{ backgroundColor: theme.primaryAccent }} />
                {theme.name.toUpperCase()}
              </button>
            </div>

            {/* Dopamine Readiness Hero Card */}
            <div
              className="rounded-[28px] border p-4 flex items-center gap-4"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}44`,
              }}
            >
              <div className="relative w-16 h-16 flex items-center justify-center shrink-0">
                <svg className="w-full h-full -rotate-90" viewBox="0 0 72 72">
                  <circle cx="36" cy="36" r="28" stroke="rgba(255,255,255,0.08)" strokeWidth="6" fill="none" />
                  <circle
                    cx="36"
                    cy="36"
                    r="28"
                    stroke={vaultFilter === 'FRESH' ? theme.primaryAccent : '#FFB000'}
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
                <p className="text-xs text-white/65 mt-1 leading-relaxed">
                  Tap ▶ to play a song, or tap SYNC for neural track actions & mood sync.
                </p>
              </div>
            </div>

            {/* 2-Pill Filter Toggle */}
            <div className="grid grid-cols-2 gap-1.5 p-1 rounded-full bg-white/[0.05] border border-white/[0.08]">
              <button
                onClick={() => setVaultFilter('FRESH')}
                className="py-2.5 rounded-full text-[11px] font-bold tracking-wider transition-all"
                style={{
                  backgroundColor: vaultFilter === 'FRESH' ? `${theme.primaryAccent}33` : 'transparent',
                  border: vaultFilter === 'FRESH' ? `1px solid ${theme.primaryAccent}88` : '1px solid transparent',
                  color: vaultFilter === 'FRESH' ? '#FFFFFF' : 'rgba(255,255,255,0.55)',
                }}
              >
                ⚡ PEAK DOPAMINE ({freshSongs.length})
              </button>
              <button
                onClick={() => setVaultFilter('COOLING')}
                className="py-2.5 rounded-full text-[11px] font-bold tracking-wider transition-all"
                style={{
                  backgroundColor: vaultFilter === 'COOLING' ? 'rgba(255,176,0,0.22)' : 'transparent',
                  border: vaultFilter === 'COOLING' ? '1px solid rgba(255,176,0,0.6)' : '1px solid transparent',
                  color: vaultFilter === 'COOLING' ? '#FFFFFF' : 'rgba(255,255,255,0.55)',
                }}
              >
                ⏳ COOLING DOWN ({coolingSongs.length})
              </button>
            </div>

            {/* Scroll-Safe Song Cards (Row is NOT clickable; only ▶ Play and SYNC buttons fire) */}
            <div className="space-y-2.5 flex-1 overflow-y-auto pr-0.5">
              {displayedVaultSongs.map(song => {
                const isPlayingNow = currentTrack.id === song.id;
                const accent = isPlayingNow ? '#10B981' : song.isCoolingDown ? '#FFB000' : theme.primaryAccent;
                return (
                  <div
                    key={song.id}
                    className="rounded-3xl border p-3.5 flex items-center gap-3.5 transition-all"
                    style={{
                      backgroundColor: isPlayingNow ? `${accent}22` : theme.cardBg,
                      borderColor: isPlayingNow ? `${accent}88` : `${theme.primaryAccent}33`,
                    }}
                  >
                    {/* Dedicated 48x48 Play Button */}
                    <button
                      onClick={() => handlePlaySongButtonOnly(song)}
                      className="relative w-12 h-12 rounded-full flex items-center justify-center shrink-0 cursor-pointer active:scale-92 transition-transform"
                    >
                      <svg className="w-full h-full -rotate-90" viewBox="0 0 48 48">
                        <circle cx="24" cy="24" r="20" fill={`${accent}28`} />
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
                    </button>

                    {/* Title & Artist */}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-bold text-white truncate">{song.title}</p>
                      <p className="text-xs text-white/65 truncate">{song.artist}</p>
                      <span
                        className="inline-block mt-1 px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wider"
                        style={{ backgroundColor: `${accent}26`, color: accent }}
                      >
                        {isPlayingNow
                          ? 'NOW PLAYING'
                          : song.isCoolingDown
                          ? `COOLING • ${song.playCount}x`
                          : `${song.freshness}% FRESH`}
                      </span>
                    </div>

                    {/* Upgraded Sculpted Neural Sonic SYNC Button */}
                    <button
                      onClick={() => setActiveSyncSong(song)}
                      className="min-w-[78px] min-h-[44px] px-3 py-2 rounded-2xl border flex items-center justify-center gap-2 cursor-pointer active:scale-95 transition-all shrink-0"
                      style={{
                        background: `linear-gradient(90deg, ${accent}28, ${theme.secondaryAccent}22)`,
                        borderColor: `${accent}88`,
                      }}
                    >
                      <span className="flex items-center gap-[2px] h-3.5">
                        <span className="w-[2.5px] h-2 rounded-full animate-pulse" style={{ backgroundColor: accent }} />
                        <span className="w-[2.5px] h-3.5 rounded-full animate-pulse" style={{ backgroundColor: theme.secondaryAccent }} />
                        <span className="w-[2.5px] h-2.5 rounded-full animate-pulse" style={{ backgroundColor: accent }} />
                        <span className="w-[2.5px] h-3 rounded-full animate-pulse" style={{ backgroundColor: theme.secondaryAccent }} />
                      </span>
                      <span className="text-[10px] font-extrabold tracking-widest text-white">SYNC</span>
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
            {/* Top Now Syncing & Atmosphere Header Pill */}
            <div
              className="w-full rounded-[28px] border px-5 py-3.5"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}55`,
              }}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-1.5">
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

                <button
                  onClick={cycleAtmospherePalette}
                  className="px-2.5 py-1 rounded-xl text-[9px] font-bold tracking-wider border transition-all active:scale-95"
                  style={{
                    backgroundColor: `${theme.primaryAccent}24`,
                    borderColor: `${theme.primaryAccent}66`,
                    color: '#FFFFFF',
                  }}
                >
                  ✦ {theme.name.toUpperCase()}
                </button>
              </div>
              <h2 className="text-base font-bold text-white mt-1.5 truncate text-center">
                {currentTrack.title}
              </h2>
              <p className="text-xs text-white/65 truncate text-center">
                {currentTrack.artist} • {currentTrack.moodName}
              </p>
            </div>

            {/* Center 300px Living Voice Orb & Satiety Arc */}
            <div className="flex flex-col items-center my-auto">
              <div className="relative w-72 h-72 flex items-center justify-center">
                <div
                  className="absolute w-56 h-56 rounded-full blur-[60px] pointer-events-none transition-all duration-700"
                  style={{ backgroundColor: `${targetOrbColor}44` }}
                />

                <svg className="absolute inset-0 w-full h-full -rotate-90 pointer-events-none" viewBox="0 0 240 240">
                  <circle
                    cx="120"
                    cy="120"
                    r="104"
                    stroke="rgba(255,255,255,0.07)"
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

                <button
                  onClick={handleOrbClick}
                  className="relative w-38 h-38 rounded-full flex items-center justify-center cursor-pointer transition-transform duration-300 active:scale-92"
                  style={{
                    background: `radial-gradient(circle at 35% 35%, rgba(255,255,255,0.48) 0%, ${targetOrbColor} 60%, ${theme.secondaryAccent} 100%)`,
                    boxShadow: `0 0 52px ${targetOrbColor}77`,
                  }}
                >
                  <div className="w-20 h-20 rounded-full bg-white/25 backdrop-blur-sm flex items-center justify-center">
                    {agentState === 'LISTENING' && <Mic className="w-8 h-8 text-black animate-pulse" />}
                    {agentState === 'THINKING' && <Sparkles className="w-8 h-8 text-white animate-spin" />}
                    {agentState === 'SPEAKING' && <Volume2 className="w-8 h-8 text-black animate-bounce" />}
                    {agentState === 'IDLE' && <Radio className="w-7 h-7 text-white" />}
                  </div>
                </button>
              </div>

              <span
                className="text-[11px] font-bold tracking-[0.2em] uppercase mt-2"
                style={{ color: agentState === 'IDLE' ? 'rgba(255,255,255,0.65)' : targetOrbColor }}
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

            {/* Bottom Live Dialogue Card */}
            <div className="w-full min-h-[76px] flex items-end">
              {(agentState !== 'IDLE' || liveTranscript) && (
                <div
                  className="w-full rounded-[28px] border p-4 text-center"
                  style={{
                    backgroundColor: theme.cardBg,
                    borderColor: `${theme.primaryAccent}44`,
                  }}
                >
                  <span
                    className="text-[10px] font-bold tracking-[0.2em] uppercase block"
                    style={{ color: targetOrbColor }}
                  >
                    {agentState === 'IDLE' ? 'LAST DIALOGUE' : agentState}
                  </span>
                  <p className="text-sm text-white/95 mt-1.5 leading-relaxed">
                    "{liveTranscript}"
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* SCREEN 2: RHYTHM DNA & ADAPTIVE ATMOSPHERE ENGINE */}
        {activeScreen === 'RHYTHM' && (
          <div className="flex-1 flex flex-col space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <span
                  className="text-[11px] font-bold tracking-[0.2em] block"
                  style={{ color: theme.primaryAccent }}
                >
                  RHYTHM DNA
                </span>
                <h1 className="text-2xl font-bold text-white mt-0.5">Circadian Sonic Flow</h1>
              </div>
              <div
                className="px-3 py-1.5 rounded-2xl text-[10px] font-bold tracking-wider border"
                style={{
                  backgroundColor: `${theme.primaryAccent}22`,
                  borderColor: `${theme.primaryAccent}55`,
                  color: theme.primaryAccent,
                }}
              >
                ☁️ CLOUD BRAIN
              </div>
            </div>

            {/* Adaptive Atmosphere Engine Switcher Card */}
            <div
              className="rounded-[28px] border p-4 space-y-3"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}44`,
              }}
            >
              <div className="flex items-center justify-between">
                <div>
                  <span
                    className="text-[10px] font-bold tracking-wider block"
                    style={{ color: theme.primaryAccent }}
                  >
                    ✦ ADAPTIVE ATMOSPHERE ENGINE
                  </span>
                  <span className="text-sm font-bold text-white">
                    {theme.name} • {theme.moodLabel}
                  </span>
                </div>
                <button
                  onClick={() => {
                    setIsAutoTheme(true);
                    setActivePaletteId(currentTrack.moodPalette);
                  }}
                  className="px-2.5 py-1 rounded-xl text-[10px] font-bold border"
                  style={{
                    backgroundColor: isAutoTheme ? 'rgba(16,185,129,0.2)' : 'rgba(255,255,255,0.06)',
                    borderColor: isAutoTheme ? 'rgba(16,185,129,0.6)' : 'rgba(255,255,255,0.15)',
                    color: isAutoTheme ? '#10B981' : '#FFFFFF',
                  }}
                >
                  {isAutoTheme ? 'AUTO ON' : 'SET AUTO'}
                </button>
              </div>

              <div className="grid grid-cols-4 gap-1.5">
                {[
                  { id: 'morning' as PaletteId, label: 'Dawn Cyan', color: '#00E5FF' },
                  { id: 'afternoon' as PaletteId, label: 'Emerald', color: '#10B981' },
                  { id: 'evening' as PaletteId, label: 'Solar Amber', color: '#FFB000' },
                  { id: 'night' as PaletteId, label: 'Violet Night', color: '#A855F7' },
                ].map(p => {
                  const isSel = activePaletteId === p.id;
                  return (
                    <button
                      key={p.id}
                      onClick={() => {
                        setIsAutoTheme(false);
                        setActivePaletteId(p.id);
                      }}
                      className="py-2 rounded-xl text-[10px] font-bold border transition-all"
                      style={{
                        backgroundColor: isSel ? `${p.color}33` : `${p.color}12`,
                        borderColor: isSel ? p.color : `${p.color}44`,
                        color: isSel ? '#FFFFFF' : p.color,
                      }}
                    >
                      {p.label}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* 24-Hour Circadian Sonic Ring */}
            <div
              className="rounded-[28px] border p-4 flex flex-col items-center"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}33`,
              }}
            >
              <div className="relative w-36 h-36 flex items-center justify-center">
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
                  <span className="text-xl font-bold text-white block mt-0.5">
                    {activePhase.bpm} BPM
                  </span>
                  <span className="text-[10px] text-white/65 block">{activePhase.hours}</span>
                </div>
              </div>

              <div className="grid grid-cols-4 gap-1.5 w-full mt-3">
                {CIRCADIAN_PHASES.map((phase, idx) => {
                  const isSelected = idx === selectedPhaseIdx;
                  return (
                    <button
                      key={phase.id}
                      onClick={() => {
                        setSelectedPhaseIdx(idx);
                        setActivePaletteId(phase.paletteId);
                      }}
                      className="py-2 rounded-xl text-[10px] font-bold tracking-wider border transition-all"
                      style={{
                        backgroundColor: isSelected ? `${phase.color}28` : 'rgba(255,255,255,0.03)',
                        borderColor: isSelected ? `${phase.color}80` : 'rgba(255,255,255,0.07)',
                        color: isSelected ? '#FFFFFF' : 'rgba(255,255,255,0.55)',
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
              <span className="text-[11px] font-bold tracking-wider text-white/85">
                🔥 POWER ROTATION • {activePhase.short}
              </span>
              <span className="text-[10px] font-bold" style={{ color: activePhase.color }}>
                TAP ▶ TO PLAY
              </span>
            </div>

            {/* Scroll-Safe Power Rotation Tracks */}
            <div className="space-y-2 flex-1 overflow-y-auto">
              {songs.slice(0, 4).map(song => (
                <div
                  key={song.id}
                  className="rounded-3xl border p-3 flex items-center gap-3 transition-all"
                  style={{
                    backgroundColor: theme.cardBg,
                    borderColor: `${theme.primaryAccent}33`,
                  }}
                >
                  <button
                    onClick={() => handlePlaySongButtonOnly(song)}
                    className="w-11 h-11 rounded-full flex items-center justify-center text-xs font-bold border shrink-0 cursor-pointer active:scale-92"
                    style={{
                      backgroundColor: `${activePhase.color}22`,
                      borderColor: `${activePhase.color}66`,
                      color: activePhase.color,
                    }}
                  >
                    <Play className="w-4 h-4 ml-0.5" style={{ color: activePhase.color }} />
                  </button>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-bold text-white truncate">{song.title}</p>
                    <p className="text-xs text-white/65 truncate">{song.artist}</p>
                  </div>
                  <button
                    onClick={() => setActiveSyncSong(song)}
                    className="min-w-[74px] min-h-[42px] px-3 py-1.5 rounded-2xl border flex items-center justify-center gap-1.5 cursor-pointer active:scale-95"
                    style={{
                      background: `linear-gradient(90deg, ${activePhase.color}28, ${theme.secondaryAccent}22)`,
                      borderColor: `${activePhase.color}77`,
                    }}
                  >
                    <span className="text-[10px] font-extrabold tracking-widest text-white">SYNC</span>
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </main>

      {/* NEURAL TRACK INTELLIGENCE MODAL SHEET */}
      {activeSyncSong && (
        <div
          onClick={() => setActiveSyncSong(null)}
          className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-end justify-center p-4"
        >
          <div
            onClick={e => e.stopPropagation()}
            className="w-full max-w-md rounded-[32px] border p-5 space-y-4 shadow-2xl"
            style={{
              backgroundColor: '#121024',
              borderColor: `${theme.primaryAccent}88`,
            }}
          >
            <div className="flex items-center justify-between">
              <span
                className="text-[10px] font-bold tracking-[0.2em]"
                style={{ color: theme.primaryAccent }}
              >
                NEURAL TRACK INTELLIGENCE
              </span>
              <button
                onClick={() => setActiveSyncSong(null)}
                className="w-8 h-8 rounded-full bg-white/10 flex items-center justify-center text-white/70 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex items-center gap-4">
              <div
                className="w-14 h-14 rounded-full border-2 flex items-center justify-center text-sm font-extrabold"
                style={{
                  borderColor: theme.primaryAccent,
                  color: '#FFFFFF',
                  backgroundColor: `${theme.primaryAccent}22`,
                }}
              >
                {activeSyncSong.freshness}%
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-lg font-bold text-white truncate">{activeSyncSong.title}</h3>
                <p className="text-xs text-white/65 truncate">
                  {activeSyncSong.artist} • {activeSyncSong.moodName}
                </p>
              </div>
            </div>

            <div className="space-y-2.5 pt-1">
              <button
                onClick={() => handleNeuralBreakdown(activeSyncSong)}
                className="w-full p-3.5 rounded-2xl border text-left flex items-center gap-3.5 transition-all active:scale-98"
                style={{
                  backgroundColor: `${theme.primaryAccent}28`,
                  borderColor: `${theme.primaryAccent}88`,
                }}
              >
                <div
                  className="w-10 h-10 rounded-full flex items-center justify-center text-xs font-extrabold"
                  style={{ backgroundColor: `${theme.primaryAccent}33`, color: theme.primaryAccent }}
                >
                  AI
                </div>
                <div>
                  <span className="text-sm font-bold text-white block">
                    Neural Voice Breakdown & Dialogue
                  </span>
                  <span className="text-xs text-white/65">
                    Agent analyzes this track's vibe & asks before spinning
                  </span>
                </div>
              </button>

              <button
                onClick={() => {
                  const s = activeSyncSong;
                  setActiveSyncSong(null);
                  handlePlaySongButtonOnly(s);
                }}
                className="w-full p-3.5 rounded-2xl bg-white/[0.05] border border-white/[0.1] text-left flex items-center gap-3.5"
              >
                <div className="w-10 h-10 rounded-full bg-[#10B981]/20 text-[#10B981] flex items-center justify-center text-xs font-extrabold">
                  ▶
                </div>
                <div>
                  <span className="text-sm font-bold text-white block">Spin Track Immediately</span>
                  <span className="text-xs text-white/65">Launch straight into playback with voice intro</span>
                </div>
              </button>

              <button
                onClick={() => {
                  setIsAutoTheme(false);
                  setActivePaletteId(activeSyncSong.moodPalette);
                  setActiveSyncSong(null);
                }}
                className="w-full p-3.5 rounded-2xl bg-white/[0.05] border border-white/[0.1] text-left flex items-center gap-3.5"
              >
                <div
                  className="w-10 h-10 rounded-full flex items-center justify-center text-xs font-extrabold"
                  style={{ backgroundColor: `${theme.secondaryAccent}22`, color: theme.secondaryAccent }}
                >
                  ✦
                </div>
                <div>
                  <span className="text-sm font-bold text-white block">
                    Shift App Atmosphere to {activeSyncSong.moodName}
                  </span>
                  <span className="text-xs text-white/65">
                    Adapt TuneMind's colors & glow to this track's mood
                  </span>
                </div>
              </button>

              <button
                onClick={() => handleToggleCooldown(activeSyncSong)}
                className="w-full p-3.5 rounded-2xl bg-white/[0.05] border border-white/[0.1] text-left flex items-center gap-3.5"
              >
                <div className="w-10 h-10 rounded-full bg-[#FFB000]/20 text-[#FFB000] flex items-center justify-center text-xs font-extrabold">
                  {activeSyncSong.isCoolingDown ? '⚡' : '❄️'}
                </div>
                <div>
                  <span className="text-sm font-bold text-white block">
                    {activeSyncSong.isCoolingDown ? 'Restore to Peak Dopamine' : 'Rest in Cooldown Vault'}
                  </span>
                  <span className="text-xs text-white/65">
                    {activeSyncSong.isCoolingDown
                      ? 'Mark track as fresh and ready for rotation'
                      : 'Prevent ear fatigue by resting this track'}
                  </span>
                </div>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* CENTERED FLOATING GLASS PILL DOCK */}
      <div className="fixed bottom-5 left-1/2 -translate-x-1/2 z-40">
        <div
          className="h-[60px] w-[316px] rounded-[32px] backdrop-blur-xl border shadow-2xl p-1.5 grid grid-cols-3 items-center"
          style={{
            backgroundColor: 'rgba(12, 14, 24, 0.92)',
            borderColor: `${theme.primaryAccent}55`,
          }}
        >
          <button
            onClick={() => setActiveScreen('DOPAMINE')}
            className="h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all"
            style={{
              backgroundColor: activeScreen === 'DOPAMINE' ? `${theme.primaryAccent}28` : 'transparent',
              border: activeScreen === 'DOPAMINE' ? `1px solid ${theme.primaryAccent}77` : '1px solid transparent',
              color: activeScreen === 'DOPAMINE' ? '#FFFFFF' : 'rgba(255,255,255,0.58)',
            }}
          >
            <span className="w-1.5 h-1.5 rounded-full bg-[#10B981]" />
            <span>DOPAMINE</span>
          </button>

          <button
            onClick={() => {
              if (activeScreen === 'AGENT') handleOrbClick();
              else setActiveScreen('AGENT');
            }}
            className="h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all"
            style={{
              backgroundColor: activeScreen === 'AGENT' ? `${targetOrbColor}28` : 'transparent',
              border: activeScreen === 'AGENT' ? `1px solid ${targetOrbColor}88` : '1px solid transparent',
              color: activeScreen === 'AGENT' ? '#FFFFFF' : 'rgba(255,255,255,0.68)',
            }}
          >
            <span
              className="w-2.5 h-2.5 rounded-full animate-pulse"
              style={{ backgroundColor: targetOrbColor }}
            />
            <span>{agentState !== 'IDLE' ? agentState : 'AGENT'}</span>
          </button>

          <button
            onClick={() => setActiveScreen('RHYTHM')}
            className="h-full rounded-[26px] flex items-center justify-center gap-1.5 text-[11px] font-bold tracking-wider transition-all"
            style={{
              backgroundColor: activeScreen === 'RHYTHM' ? `${theme.secondaryAccent}28` : 'transparent',
              border: activeScreen === 'RHYTHM' ? `1px solid ${theme.secondaryAccent}77` : '1px solid transparent',
              color: activeScreen === 'RHYTHM' ? '#FFFFFF' : 'rgba(255,255,255,0.58)',
            }}
          >
            <span>RHYTHM</span>
          </button>
        </div>
      </div>
    </div>
  );
}
