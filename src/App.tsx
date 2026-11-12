import React, { useState, useEffect, useRef } from 'react';
import {
  Play,
  Pause,
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
    cardBg: 'rgba(16, 36, 66, 0.78)',
    primaryAccent: '#00E5FF',
    secondaryAccent: '#38BDF8',
  },
  afternoon: {
    id: 'afternoon',
    name: 'Emerald Velocity',
    moodLabel: 'Electric Groove',
    bgGradient: 'from-[#04130E] via-[#092118] to-[#020B08]',
    cardBg: 'rgba(14, 46, 34, 0.78)',
    primaryAccent: '#10B981',
    secondaryAccent: '#00E5FF',
  },
  evening: {
    id: 'evening',
    name: 'Solar Ember',
    moodLabel: 'Golden Uplift',
    bgGradient: 'from-[#160A07] via-[#24110C] to-[#0E0504]',
    cardBg: 'rgba(50, 24, 17, 0.78)',
    primaryAccent: '#FFB000',
    secondaryAccent: '#FF5E36',
  },
  night: {
    id: 'night',
    name: 'Synaptic Violet',
    moodLabel: 'Midnight Reflective',
    bgGradient: 'from-[#080516] via-[#120B29] to-[#04020C]',
    cardBg: 'rgba(27, 17, 58, 0.78)',
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
  { id: '1', title: 'Essence', artist: 'Wizkid, Tems', freshness: 96, playCount: 1, bpm: 104, energy: 0.78, moodPalette: 'afternoon', moodName: 'Electric Groove', isCoolingDown: false },
  { id: '2', title: 'Malaika', artist: 'Sauti Sol', freshness: 93, playCount: 1, bpm: 102, energy: 0.65, moodPalette: 'morning', moodName: 'Calm Sanctuary', isCoolingDown: false },
  { id: '3', title: 'Last Last', artist: 'Burna Boy', freshness: 89, playCount: 2, bpm: 115, energy: 0.84, moodPalette: 'afternoon', moodName: 'Electric Groove', isCoolingDown: false },
  { id: '4', title: 'Anaweza Yote', artist: 'The Adventist Police Ministers', freshness: 85, playCount: 2, bpm: 98, energy: 0.72, moodPalette: 'evening', moodName: 'Golden Uplift', isCoolingDown: false },
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
  const [activePaletteId, setActivePaletteId] = useState<PaletteId>('afternoon');
  const [isAutoTheme, setIsAutoTheme] = useState<boolean>(true);

  const [songs, setSongs] = useState<DopamineTrack[]>(INITIAL_SONGS);
  const [currentTrack, setCurrentTrack] = useState<DopamineTrack>(INITIAL_SONGS[0]);
  const [isMusicPlaying, setIsMusicPlaying] = useState<boolean>(true);
  const [tickerIndex, setTickerIndex] = useState<number>(0);
  const [activeSyncSong, setActiveSyncSong] = useState<DopamineTrack | null>(null);
  const [agentState, setAgentState] = useState<AgentState>('IDLE');
  const [satietyPercentage, setSatietyPercentage] = useState<number>(14);
  const [liveTranscript, setLiveTranscript] = useState<string>('');
  const [flowAnalysis, setFlowAnalysis] = useState<string>(
    'Shifting into smooth Afrobeats groove at 104 BPM — 96% Dopamine Readiness.'
  );
  const [waveTick, setWaveTick] = useState<number>(0);

  // Live harmonic animation loop for the 36-petal Studio Mascot & Equalizer
  useEffect(() => {
    const id = setInterval(() => {
      setWaveTick(t => (t + 1) % 360);
    }, 65);
    return () => clearInterval(id);
  }, []);

  // Rotate standby queue in header when paused
  useEffect(() => {
    const id = setInterval(() => {
      setTickerIndex(i => (i + 1) % songs.length);
    }, 5000);
    return () => clearInterval(id);
  }, [songs.length]);

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

  const secondaryGlowColor =
    agentState === 'SPEAKING'
      ? '#00E5FF'
      : agentState === 'LISTENING'
      ? '#10B981'
      : theme.secondaryAccent;

  const speakNative = (text: string, onDone?: () => void) => {
    if (!('speechSynthesis' in window)) {
      setTimeout(() => onDone?.(), 2400);
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
      setLiveTranscript('Listening to your voice...');
      return;
    }
    setAgentState('LISTENING');
    setLiveTranscript('Listening to your voice...');
    setTimeout(() => {
      setAgentState('THINKING');
      setFlowAnalysis('Synthesizing your Taste Genome & live dopamine freshness...');
      setTimeout(() => {
        const nextFresh = songs.find(s => !s.isCoolingDown && s.id !== currentTrack.id) || songs[0];
        const proposal = `How about "${nextFresh.title}" by ${nextFresh.artist}? It's sitting at ${nextFresh.freshness}% dopamine freshness with an ${nextFresh.moodName.toLowerCase()} vibe—want me to spin it right now?`;
        setLiveTranscript(proposal);
        setFlowAnalysis(`Mood DNA: ${nextFresh.moodName} • ${nextFresh.freshness}% Dopamine Readiness`);
        setAgentState('SPEAKING');
        speakNative(proposal, () => setAgentState('IDLE'));
      }, 750);
    }, 1300);
  };

  const handlePlaySongButtonOnly = (track: DopamineTrack) => {
    setCurrentTrack(track);
    setIsMusicPlaying(true);
    if (isAutoTheme) {
      setActivePaletteId(track.moodPalette);
    }
    setSatietyPercentage(100 - track.freshness);
    const spoken = `Playing ${track.title} by ${track.artist} now.`;
    setLiveTranscript(spoken);
    setFlowAnalysis(`Direct Studio Launch • ${track.moodName} (${track.bpm} BPM)`);
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
    setFlowAnalysis(`Mood DNA: ${track.moodName} • ${track.freshness}% Dopamine Readiness`);
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
  const rotatingCandidate = songs[tickerIndex % songs.length];

  // Dynamic Header Telemetry for Center Screen
  const headerBadge =
    agentState === 'LISTENING'
      ? '🎙 VOICE CAPTURE LIVE'
      : agentState === 'THINKING'
      ? '🧠 NEURAL REASONING'
      : agentState === 'SPEAKING'
      ? '🔊 AGENT SPEAKING'
      : isMusicPlaying
      ? '● LIVE IN STUDIO'
      : '✦ PEAK DOPAMINE QUEUE';

  const headerTitle =
    agentState === 'LISTENING'
      ? 'Listening to your request...'
      : agentState === 'THINKING'
      ? 'Matching your Taste Genome...'
      : isMusicPlaying
      ? currentTrack.title
      : rotatingCandidate.title;

  const headerSubtitle =
    agentState === 'LISTENING'
      ? 'Studio microphone active • 16kHz voice stream'
      : agentState === 'THINKING'
      ? `Evaluating dopamine freshness & ${theme.moodLabel}`
      : isMusicPlaying
      ? `${currentTrack.artist} • ${currentTrack.moodName}`
      : `${rotatingCandidate.artist} • ${rotatingCandidate.freshness}% Fresh in Vault`;

  return (
    <div
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
      className={`h-screen bg-gradient-to-b ${theme.bgGradient} text-white flex flex-col justify-between relative overflow-hidden select-none font-sans transition-colors duration-700`}
    >
      {/* Studio Spotlights */}
      <div
        className="fixed -top-16 left-1/2 -translate-x-1/2 w-80 h-80 rounded-full blur-[95px] pointer-events-none transition-all duration-700"
        style={{ backgroundColor: `${targetOrbColor}38` }}
      />
      <div
        className="fixed bottom-16 left-1/2 -translate-x-1/2 w-72 h-72 rounded-full blur-[95px] pointer-events-none transition-all duration-700"
        style={{ backgroundColor: `${secondaryGlowColor}28` }}
      />

      {/* MAIN 3-SCREEN CANVAS (pb-24 reserves space so Floating Dock never collides with bottom console) */}
      <main className="flex-1 w-full max-w-md mx-auto px-5 pt-4 pb-24 flex flex-col justify-between relative z-10 overflow-hidden">
        {/* SCREEN 0: DOPAMINE VAULT */}
        {activeScreen === 'DOPAMINE' && (
          <div className="flex-1 flex flex-col space-y-3.5 overflow-hidden">
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
              className="rounded-[26px] border p-3.5 flex items-center gap-4"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}44`,
              }}
            >
              <div className="relative w-14 h-14 flex items-center justify-center shrink-0">
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
                <span className="absolute text-xs font-bold text-white">{avgFreshness}%</span>
              </div>
              <div>
                <h2 className="text-sm font-bold text-white">
                  {vaultFilter === 'FRESH'
                    ? `${freshSongs.length} Peak Dopamine Tracks`
                    : `${coolingSongs.length} Tracks Cooling Down`}
                </h2>
                <p className="text-xs text-white/65 mt-0.5 leading-relaxed">
                  Tap ▶ to play a song, or tap SYNC for neural track actions & mood sync.
                </p>
              </div>
            </div>

            {/* 2-Pill Filter Toggle */}
            <div className="grid grid-cols-2 gap-1.5 p-1 rounded-full bg-white/[0.05] border border-white/[0.08]">
              <button
                onClick={() => setVaultFilter('FRESH')}
                className="py-2 rounded-full text-[11px] font-bold tracking-wider transition-all"
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
                className="py-2 rounded-full text-[11px] font-bold tracking-wider transition-all"
                style={{
                  backgroundColor: vaultFilter === 'COOLING' ? 'rgba(255,176,0,0.22)' : 'transparent',
                  border: vaultFilter === 'COOLING' ? '1px solid rgba(255,176,0,0.6)' : '1px solid transparent',
                  color: vaultFilter === 'COOLING' ? '#FFFFFF' : 'rgba(255,255,255,0.55)',
                }}
              >
                ⏳ COOLING DOWN ({coolingSongs.length})
              </button>
            </div>

            {/* Scroll-Safe Song Cards */}
            <div className="space-y-2.5 flex-1 overflow-y-auto pr-0.5">
              {displayedVaultSongs.map(song => {
                const isPlayingNow = currentTrack.id === song.id && isMusicPlaying;
                const accent = isPlayingNow ? '#10B981' : song.isCoolingDown ? '#FFB000' : theme.primaryAccent;
                return (
                  <div
                    key={song.id}
                    className="rounded-3xl border p-3 flex items-center gap-3.5 transition-all"
                    style={{
                      backgroundColor: isPlayingNow ? `${accent}22` : theme.cardBg,
                      borderColor: isPlayingNow ? `${accent}88` : `${theme.primaryAccent}33`,
                    }}
                  >
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

                    <button
                      onClick={() => setActiveSyncSong(song)}
                      className="min-w-[78px] min-h-[42px] px-3 py-2 rounded-2xl border flex items-center justify-center gap-2 cursor-pointer active:scale-95 transition-all shrink-0"
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

        {/* SCREEN 1: CENTER HOME — FULL-SCREEN STUDIO AI MASCOT & SONIC ENTITY */}
        {activeScreen === 'AGENT' && (
          <div className="flex-1 flex flex-col justify-between items-center h-full">
            {/* 1. TOP: LIVE STUDIO TELEMETRY HEADER (Never Hardcoded) */}
            <div
              className="w-full rounded-[26px] border px-4 py-3 flex items-center gap-3"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${targetOrbColor}66`,
              }}
            >
              {/* Animated 4-bar Equalizer Badge */}
              <button
                onClick={() => setIsMusicPlaying(p => !p)}
                className="w-10 h-10 rounded-full flex items-center justify-center gap-[2.5px] border shrink-0 cursor-pointer"
                style={{
                  backgroundColor: `${targetOrbColor}22`,
                  borderColor: `${targetOrbColor}66`,
                }}
                title="Toggle Live Studio Playback Simulation"
              >
                {[0, 1, 2, 3].map(i => {
                  const active = agentState !== 'IDLE' || isMusicPlaying;
                  const h = active
                    ? 6 + Math.round(9 * ((Math.sin((waveTick * 0.25) + i * 1.2) + 1) / 2))
                    : 6 + (i % 2) * 4;
                  return (
                    <span
                      key={i}
                      className="w-[3px] rounded-full transition-all duration-75"
                      style={{
                        height: `${h}px`,
                        backgroundColor: i % 2 === 0 ? targetOrbColor : secondaryGlowColor,
                      }}
                    />
                  );
                })}
              </button>

              <div className="flex-1 min-w-0">
                <span
                  className="text-[9px] font-extrabold tracking-[0.16em] uppercase block truncate"
                  style={{ color: targetOrbColor }}
                >
                  {headerBadge}
                </span>
                <h2 className="text-sm font-bold text-white truncate">{headerTitle}</h2>
                <p className="text-[11px] text-white/68 truncate">{headerSubtitle}</p>
              </div>

              <button
                onClick={cycleAtmospherePalette}
                className="px-2.5 py-1.5 rounded-2xl text-center border shrink-0 transition-all active:scale-95 cursor-pointer"
                style={{
                  backgroundColor: `${targetOrbColor}22`,
                  borderColor: `${targetOrbColor}66`,
                }}
              >
                <span className="text-[8px] font-extrabold tracking-wider block" style={{ color: targetOrbColor }}>
                  ✦ MOOD
                </span>
                <span className="text-[10px] font-bold text-white block">
                  {theme.name.split(' ')[0].toUpperCase()}
                </span>
              </button>
            </div>

            {/* 2. CENTER: LIVING STUDIO MASCOT & 36-PETAL RADIAL SPECTRUM VISUALIZER */}
            <div className="flex flex-col items-center justify-center my-auto">
              <div
                onClick={handleOrbClick}
                className="relative w-72 h-72 flex items-center justify-center cursor-pointer group"
              >
                {/* Ambient Studio Spotlight */}
                <div
                  className="absolute w-52 h-52 rounded-full blur-[55px] pointer-events-none transition-all duration-500"
                  style={{ backgroundColor: `${targetOrbColor}48` }}
                />

                {/* 36-Petal Radial Spectrum + Gyroscope + Satiety Arc SVG */}
                <svg className="absolute inset-0 w-full h-full" viewBox="0 0 280 280">
                  {/* Outer Satiety & Dopamine Arc */}
                  <circle
                    cx="140"
                    cy="140"
                    r="128"
                    stroke="rgba(255,255,255,0.07)"
                    strokeWidth="4"
                    fill="none"
                    strokeDasharray="600 210"
                    strokeLinecap="round"
                    transform="rotate(135 140 140)"
                  />
                  <circle
                    cx="140"
                    cy="140"
                    r="128"
                    stroke={targetOrbColor}
                    strokeWidth="5"
                    fill="none"
                    strokeDasharray={`${((100 - satietyPercentage) / 100) * 600} 810`}
                    strokeLinecap="round"
                    transform="rotate(135 140 140)"
                  />

                  {/* 36 Radial Vocal & Musical Spectrum Petals */}
                  {Array.from({ length: 36 }).map((_, i) => {
                    const angleDeg = i * 10;
                    const angleRad = (angleDeg * Math.PI) / 180;
                    const amp =
                      agentState === 'SPEAKING'
                        ? 0.35 + 0.65 * ((Math.sin(waveTick * 0.35 + i * 0.55) * Math.cos(waveTick * 0.2 - i * 0.35) + 1) / 2)
                        : agentState === 'LISTENING'
                        ? 0.25 + 0.5 * ((Math.sin(waveTick * 0.28 + i * 0.4) + 1) / 2)
                        : isMusicPlaying
                        ? 0.22 + 0.6 * ((Math.sin(waveTick * 0.25 + i * 0.65) + 1) / 2)
                        : 0.14 + 0.14 * ((Math.sin(waveTick * 0.12 + i * 0.3) + 1) / 2);

                    const rStart = 76;
                    const rEnd = rStart + 30 * amp;
                    const x1 = 140 + rStart * Math.cos(angleRad);
                    const y1 = 140 + rStart * Math.sin(angleRad);
                    const x2 = 140 + rEnd * Math.cos(angleRad);
                    const y2 = 140 + rEnd * Math.sin(angleRad);
                    return (
                      <line
                        key={i}
                        x1={x1}
                        y1={y1}
                        x2={x2}
                        y2={y2}
                        stroke={i % 2 === 0 ? targetOrbColor : secondaryGlowColor}
                        strokeWidth={agentState === 'SPEAKING' ? '4' : '3'}
                        strokeLinecap="round"
                        opacity="0.88"
                      />
                    );
                  })}
                </svg>

                {/* 3D Studio Mascot Core Sphere & Expressive Visor */}
                <div
                  className="relative w-36 h-36 rounded-full flex items-center justify-center transition-transform duration-300 group-active:scale-92"
                  style={{
                    background: `radial-gradient(circle at 30% 30%, ${targetOrbColor} 0%, ${secondaryGlowColor} 65%, #070812 100%)`,
                    boxShadow: `0 0 46px ${targetOrbColor}77`,
                  }}
                >
                  {/* Obsidian Mascot Visor */}
                  <div className="w-24 h-16 rounded-full bg-[#070811]/90 border border-white/20 flex flex-col items-center justify-center px-3 py-2 shadow-inner">
                    {/* Expressive Glowing Mascot Eyes */}
                    <div className="flex items-center justify-between w-14 mb-1.5">
                      <span
                        className="w-3.5 rounded-full bg-white transition-all duration-150"
                        style={{
                          height: agentState === 'LISTENING' ? '15px' : '11px',
                          boxShadow: `0 0 10px ${targetOrbColor}`,
                        }}
                      />
                      <span
                        className="w-3.5 rounded-full bg-white transition-all duration-150"
                        style={{
                          height: agentState === 'LISTENING' ? '15px' : '11px',
                          boxShadow: `0 0 10px ${targetOrbColor}`,
                        }}
                      />
                    </div>

                    {/* Expressive Vocal Mouth / Studio Equalizer inside Visor */}
                    <div className="flex items-center justify-center gap-[3px] h-4">
                      {agentState === 'SPEAKING' ? (
                        [0, 1, 2, 3, 4, 5, 6].map(b => {
                          const h = 4 + Math.round(11 * ((Math.sin(waveTick * 0.45 + b * 0.9) + 1) / 2));
                          return (
                            <span
                              key={b}
                              className="w-[2.5px] rounded-full bg-[#FFB000]"
                              style={{ height: `${h}px` }}
                            />
                          );
                        })
                      ) : isMusicPlaying ? (
                        [0, 1, 2, 3, 4].map(b => {
                          const h = 4 + Math.round(9 * ((Math.sin(waveTick * 0.3 + b * 1.1) + 1) / 2));
                          return (
                            <span
                              key={b}
                              className="w-[3px] rounded-full"
                              style={{ height: `${h}px`, backgroundColor: targetOrbColor }}
                            />
                          );
                        })
                      ) : (
                        <span
                          className="w-6 h-1.5 rounded-full"
                          style={{ backgroundColor: targetOrbColor }}
                        />
                      )}
                    </div>
                  </div>
                </div>
              </div>

              {/* Studio Mode Badge */}
              <div
                className="px-3.5 py-1.5 rounded-2xl border text-[10px] font-extrabold tracking-[0.14em] uppercase mt-1"
                style={{
                  backgroundColor: `${targetOrbColor}22`,
                  borderColor: `${targetOrbColor}66`,
                  color: '#FFFFFF',
                }}
              >
                {agentState === 'LISTENING'
                  ? '🎙 LISTENING TO YOUR VOICE • SPEAK FREELY'
                  : agentState === 'THINKING'
                  ? '🧠 CLAUDE SYNTHESIZING DOPAMINE & MOOD...'
                  : agentState === 'SPEAKING'
                  ? '🔊 TALKING TO YOU • TAP MASCOT TO INTERRUPT'
                  : isMusicPlaying
                  ? '🎵 STUDIO VISUALIZER LIVE • TAP MASCOT TO TALK'
                  : '✦ STUDIO AGENT READY • TAP MASCOT TO TALK'}
              </div>
            </div>

            {/* 3. BOTTOM (ABOVE FLOATING DOCK — ZERO COLLISION): STUDIO DIALOGUE & AI REASONING CONSOLE */}
            <div
              className="w-full rounded-[24px] border px-4 py-3 space-y-1.5"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${targetOrbColor}55`,
              }}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full" style={{ backgroundColor: targetOrbColor }} />
                  <span
                    className="text-[9px] font-extrabold tracking-[0.16em] uppercase"
                    style={{ color: targetOrbColor }}
                  >
                    {agentState === 'IDLE' ? 'STUDIO VOICE CONSOLE' : `LIVE • ${agentState}`}
                  </span>
                </div>
                <span className="text-[10px] font-bold" style={{ color: secondaryGlowColor }}>
                  {currentTrack.moodName} • {100 - satietyPercentage}% Fresh
                </span>
              </div>

              <p className="text-xs text-white/95 font-medium leading-relaxed line-clamp-2">
                "{liveTranscript || 'Tap the Studio Mascot and say "Play me a song" or "Change the vibe".'}"
              </p>

              <div className="rounded-xl bg-white/[0.04] px-2.5 py-1.5 flex items-center gap-1.5">
                <span className="text-[10px] font-extrabold shrink-0" style={{ color: targetOrbColor }}>
                  🧠 AI:
                </span>
                <span className="text-[10px] text-white/75 truncate">{flowAnalysis}</span>
              </div>
            </div>
          </div>
        )}

        {/* SCREEN 2: RHYTHM DNA & ADAPTIVE ATMOSPHERE ENGINE */}
        {activeScreen === 'RHYTHM' && (
          <div className="flex-1 flex flex-col space-y-3.5 overflow-y-auto pr-0.5">
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
              className="rounded-[26px] border p-3.5 space-y-2.5"
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
              className="rounded-[26px] border p-3.5 flex flex-col items-center"
              style={{
                backgroundColor: theme.cardBg,
                borderColor: `${theme.primaryAccent}33`,
              }}
            >
              <div className="relative w-34 h-34 flex items-center justify-center">
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

              <div className="grid grid-cols-4 gap-1.5 w-full mt-2.5">
                {CIRCADIAN_PHASES.map((phase, idx) => {
                  const isSelected = idx === selectedPhaseIdx;
                  return (
                    <button
                      key={phase.id}
                      onClick={() => {
                        setSelectedPhaseIdx(idx);
                        setActivePaletteId(phase.paletteId);
                      }}
                      className="py-1.5 rounded-xl text-[10px] font-bold tracking-wider border transition-all"
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

            {/* Power Rotation Tracks */}
            <div className="space-y-2">
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
                    className="min-w-[74px] min-h-[40px] px-3 py-1.5 rounded-2xl border flex items-center justify-center gap-1.5 cursor-pointer active:scale-95"
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
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40">
        <div
          className="h-[58px] w-[316px] rounded-[32px] backdrop-blur-xl border shadow-2xl p-1.5 grid grid-cols-3 items-center"
          style={{
            backgroundColor: 'rgba(12, 14, 24, 0.94)',
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
