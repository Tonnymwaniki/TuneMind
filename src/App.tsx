import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  MicOff,
  Volume2,
  VolumeX,
  Play,
  Pause,
  SkipForward,
  Database,
  Sparkles,
  Activity,
  Radio,
  Clock,
  Layers,
  ChevronRight,
  RefreshCw,
  Send,
  Zap,
  Music,
  ExternalLink,
  ShieldCheck,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';

type AgentState = 'IDLE' | 'LISTENING' | 'THINKING' | 'SPEAKING' | 'ERROR';

interface TrackTelemetry {
  title: string;
  artist: string;
  source_player: string;
  bpm: number;
  energy: number;
  key: string;
}

interface AgentIntervention {
  id?: string;
  trigger_transcript: string;
  computed_satiety: number;
  agent_tts_response: string;
  os_intent: string;
  target_search_query: string;
  created_at: string;
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
  };
  database_tags: string[];
  voice_agent_audio_base64?: string | null;
}

export default function App() {
  // Circadian Chrono-Canvas
  const [circadianOverride, setCircadianOverride] = useState<'AUTO' | 'MORNING' | 'MIDDAY' | 'EVENING' | 'NIGHT'>('AUTO');
  const [hour, setHour] = useState(new Date().getHours());

  useEffect(() => {
    const timer = setInterval(() => setHour(new Date().getHours()), 60000);
    return () => clearInterval(timer);
  }, []);

  const effectiveCircadian = circadianOverride === 'AUTO'
    ? (hour >= 6 && hour < 12 ? 'MORNING' : hour >= 12 && hour < 17 ? 'MIDDAY' : hour >= 17 && hour < 22 ? 'EVENING' : 'NIGHT')
    : circadianOverride;

  const bgStyle = {
    MORNING: 'from-[#0F172A] via-[#09101F] to-[#040812]', // Morning Mist
    MIDDAY: 'from-[#000000] via-[#060608] to-[#0A0A0E]', // Midday Obsidian
    EVENING: 'from-[#1E1014] via-[#12080B] to-[#080305]', // Evening Ember
    NIGHT: 'from-[#06050A] via-[#040307] to-[#020104]',  // Late Night Abyss
  }[effectiveCircadian];

  // Agent State & Living Orb
  const [agentState, setAgentState] = useState<AgentState>('IDLE');
  const [satietyPercentage, setSatietyPercentage] = useState(58);
  const [liveTranscript, setLiveTranscript] = useState('');
  const [userCommandInput, setUserCommandInput] = useState('');
  const [isMicActive, setIsMicActive] = useState(false);
  const [isPlaying, setIsPlaying] = useState(true);

  // Active Track Telemetry
  const [currentTrack, setCurrentTrack] = useState<TrackTelemetry>({
    title: 'Starboy',
    artist: 'The Weeknd',
    source_player: 'com.spotify.music',
    bpm: 186,
    energy: 0.82,
    key: 'G Minor'
  });

  // AI Reasoning & Audio Output
  const [lastReasoning, setLastReasoning] = useState<ReasoningApiResponse | null>({
    status: 'ok',
    calculated_satiety_percentage: 58,
    flow_state_analysis: 'Receptor receptivity stable. Harmonic pace maintaining steady cognitive focus.',
    voice_agent_tts: 'Soundstage is clean and focus velocity is locked in.',
    os_execution_command: {
      intent: 'NONE',
      target_player: 'com.spotify.music',
      search_query: '',
      suggested_bpm_target: 124
    },
    database_tags: ['claude-haiku-neuro-engine', 'flow-state', 'spotify-verified']
  });

  const [isPlayingAudio, setIsPlayingAudio] = useState(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // Taste Genome / Supabase Memory
  const [interventions, setInterventions] = useState<AgentIntervention[]>([
    {
      trigger_transcript: 'Give me something faster for running',
      computed_satiety: 65,
      agent_tts_response: 'Pivoting cadence to 132 BPM to match aerobic elevation.',
      os_intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
      target_search_query: 'uptempo afro house 130 bpm',
      created_at: new Date(Date.now() - 1000 * 60 * 18).toISOString()
    },
    {
      trigger_transcript: 'Feeling tired of this loop',
      computed_satiety: 82,
      agent_tts_response: "We're hitting acoustic satiety. Introducing an organic palate cleanser.",
      os_intent: 'OS_MEDIA_NEXT_AND_DEEP_LINK',
      target_search_query: 'chillout acoustic reset',
      created_at: new Date(Date.now() - 1000 * 60 * 45).toISOString()
    }
  ]);

  const [activeTab, setActiveTab] = useState<'CORE' | 'GENOME' | 'SIMULATOR'>('CORE');

  // Orb Colors
  const targetOrbColor = {
    LISTENING: '#00E5FF', // Acoustic Cyan
    THINKING: '#7000FF',  // Neural Violet
    SPEAKING: '#FFB000',  // Liquid Amber
    ERROR: '#FF2A55',     // Crimson
    IDLE: currentTrack.energy > 0.7 ? '#FF4500' : '#10B981' // High Energy vs Chill
  }[agentState];

  const satietyColor = satietyPercentage > 75 ? '#FF2A55' : '#00F0FF';

  // Trigger Reasoning Pipeline
  const executeReasoning = async (command: string) => {
    setAgentState('THINKING');
    setLiveTranscript(command);

    try {
      const response = await fetch('/api/v1/reasoning/evaluate-satiety', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          timestamp_utc: new Date().toISOString(),
          circadian_daypart: effectiveCircadian === 'MORNING' ? 'Morning' : effectiveCircadian === 'MIDDAY' ? 'Midday' : effectiveCircadian === 'EVENING' ? 'Evening' : 'Late Night',
          current_track: {
            title: currentTrack.title,
            artist: currentTrack.artist,
            source_player: currentTrack.source_player
          },
          user_command: command,
          rolling_history: [
            { title: 'Blinding Lights', artist: 'The Weeknd', bpm: 171 },
            { title: 'Die For You', artist: 'The Weeknd', bpm: 134 }
          ],
          current_satiety_percentage: satietyPercentage
        })
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data: ReasoningApiResponse = await response.json();

      setLastReasoning(data);
      setSatietyPercentage(data.calculated_satiety_percentage || 50);

      // Log to local Taste Genome list
      setInterventions(prev => [
        {
          trigger_transcript: command,
          computed_satiety: data.calculated_satiety_percentage,
          agent_tts_response: data.voice_agent_tts,
          os_intent: data.os_execution_command.intent,
          target_search_query: data.os_execution_command.search_query,
          created_at: new Date().toISOString()
        },
        ...prev
      ]);

      setAgentState('SPEAKING');

      // Play audio response if Base64 MP3 exists, else browser speech synthesis
      if (data.voice_agent_audio_base64) {
        playBase64Audio(data.voice_agent_audio_base64);
      } else if ('speechSynthesis' in window && data.voice_agent_tts) {
        speakNative(data.voice_agent_tts);
      } else {
        setTimeout(() => setAgentState('IDLE'), 3500);
      }
    } catch (err) {
      console.error('[TuneMind Agent] Error:', err);
      setAgentState('ERROR');
      setTimeout(() => setAgentState('IDLE'), 3000);
    }
  };

  const playBase64Audio = (base64Str: string) => {
    try {
      const audioUrl = `data:audio/mp3;base64,${base64Str}`;
      if (audioRef.current) {
        audioRef.current.src = audioUrl;
        audioRef.current.play();
        setIsPlayingAudio(true);
        audioRef.current.onended = () => {
          setIsPlayingAudio(false);
          setAgentState('IDLE');
        };
      }
    } catch {
      setAgentState('IDLE');
    }
  };

  const speakNative = (text: string) => {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 1.05;
    utterance.pitch = 1.0;
    utterance.onend = () => setAgentState('IDLE');
    utterance.onerror = () => setAgentState('IDLE');
    window.speechSynthesis.speak(utterance);
  };

  // Phantom Trigger (Orb Tap)
  const handleOrbClick = () => {
    if (agentState === 'IDLE') {
      setAgentState('LISTENING');
      setIsMicActive(true);
      setLiveTranscript('Listening for vocal command...');

      // If Web Speech API is supported, listen for user speech
      if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
        const SpeechRec = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
        try {
          const recognition = new SpeechRec();
          recognition.continuous = false;
          recognition.interimResults = true;
          recognition.lang = 'en-US';

          recognition.onresult = (event: any) => {
            const transcript = Array.from(event.results)
              .map((res: any) => res[0].transcript)
              .join('');
            setLiveTranscript(transcript);
          };

          recognition.onend = () => {
            setIsMicActive(false);
            if (liveTranscript && liveTranscript !== 'Listening for vocal command...') {
              executeReasoning(liveTranscript);
            } else {
              executeReasoning('Switch up the cadence with an acoustic palate cleanser');
            }
          };

          recognition.onerror = () => {
            setIsMicActive(false);
            executeReasoning('Reset acoustic satiety with harmonic variety');
          };

          recognition.start();
          return;
        } catch {
          // Fallback to simulated command
        }
      }

      // Simulated voice input fallback
      setTimeout(() => {
        setIsMicActive(false);
        const sampleCommands = [
          'Give me something faster for running',
          'Tired of this loop, give me a palate cleanser',
          'Play some chill East African Afro-fusion',
          'Optimize my soundstage for deep focus'
        ];
        const randomCmd = sampleCommands[Math.floor(Math.random() * sampleCommands.length)];
        executeReasoning(randomCmd);
      }, 2500);
    } else if (agentState === 'SPEAKING') {
      window.speechSynthesis?.cancel();
      if (audioRef.current) audioRef.current.pause();
      setIsPlayingAudio(false);
      setAgentState('IDLE');
    }
  };

  return (
    <div className={`min-h-screen bg-gradient-to-b ${bgStyle} text-white transition-colors duration-1000 flex flex-col font-sans select-none overflow-x-hidden`}>
      <audio ref={audioRef} className="hidden" />

      {/* TOP STATUS BAR & BRAND HEADER */}
      <header className="px-6 py-4 flex items-center justify-between border-b border-white/[0.06] backdrop-blur-md sticky top-0 z-50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-2xl bg-gradient-to-br from-[#00E5FF] to-[#7000FF] p-[2px] shadow-lg shadow-[#00E5FF]/20">
            <div className="w-full h-full bg-[#06050A] rounded-[14px] flex items-center justify-center">
              <Radio className="w-4 h-4 text-[#00E5FF]" />
            </div>
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-sm font-black tracking-[0.25em] text-white">TUNE MIND</h1>
              <span className="text-[10px] px-2 py-0.5 rounded-full font-semibold bg-[#7000FF]/20 text-[#A78BFA] border border-[#7000FF]/30">
                CLAUDE 3.5 HAIKU
              </span>
            </div>
            <p className="text-[11px] text-white/50 tracking-wide font-medium">Autonomous Neuro-Acoustic Entity</p>
          </div>
        </div>

        {/* Tab switchers */}
        <div className="flex items-center bg-white/[0.04] p-1 rounded-2xl border border-white/[0.08]">
          <button
            onClick={() => setActiveTab('CORE')}
            className={`px-4 py-1.5 rounded-xl text-xs font-semibold transition-all ${
              activeTab === 'CORE'
                ? 'bg-gradient-to-r from-[#00E5FF]/20 to-[#7000FF]/20 text-white border border-[#00E5FF]/40 shadow-sm'
                : 'text-white/60 hover:text-white'
            }`}
          >
            Agent Core
          </button>
          <button
            onClick={() => setActiveTab('GENOME')}
            className={`px-4 py-1.5 rounded-xl text-xs font-semibold transition-all ${
              activeTab === 'GENOME'
                ? 'bg-gradient-to-r from-[#00E5FF]/20 to-[#7000FF]/20 text-white border border-[#00E5FF]/40 shadow-sm'
                : 'text-white/60 hover:text-white'
            }`}
          >
            Taste Genome
          </button>
          <button
            onClick={() => setActiveTab('SIMULATOR')}
            className={`px-4 py-1.5 rounded-xl text-xs font-semibold transition-all ${
              activeTab === 'SIMULATOR'
                ? 'bg-gradient-to-r from-[#00E5FF]/20 to-[#7000FF]/20 text-white border border-[#00E5FF]/40 shadow-sm'
                : 'text-white/60 hover:text-white'
            }`}
          >
            Simulator
          </button>
        </div>

        {/* Circadian daypart indicator */}
        <div className="hidden md:flex items-center gap-2">
          <Clock className="w-3.5 h-3.5 text-[#00E5FF]" />
          <span className="text-xs text-white/70 font-medium capitalize">
            {effectiveCircadian.toLowerCase()} Canvas
          </span>
          <select
            value={circadianOverride}
            onChange={(e) => setCircadianOverride(e.target.value as any)}
            className="text-[11px] bg-white/[0.06] border border-white/[0.1] rounded-lg px-2 py-1 text-white/80 focus:outline-none focus:border-[#00E5FF]"
          >
            <option value="AUTO" className="bg-[#06050A]">Auto (Time-of-day)</option>
            <option value="MORNING" className="bg-[#06050A]">Morning Mist</option>
            <option value="MIDDAY" className="bg-[#06050A]">Midday Obsidian</option>
            <option value="EVENING" className="bg-[#06050A]">Evening Ember</option>
            <option value="NIGHT" className="bg-[#06050A]">Late Night Abyss</option>
          </select>
        </div>
      </header>

      {/* MAIN VIEWPORT */}
      <main className="flex-1 max-w-5xl w-full mx-auto p-4 md:p-8 flex flex-col justify-between">
        {activeTab === 'CORE' && (
          <div className="flex-1 flex flex-col justify-between items-center py-4 space-y-6">
            
            {/* TOP CARD: NOW SYNCING (LIVE SPOTIFY ACOUSTICS) */}
            <div className="w-full max-w-lg soft-glass-card p-5 text-center bouncy-btn cursor-pointer">
              <div className="flex items-center justify-center gap-2 text-[10px] tracking-[0.25em] font-bold text-[#00E5FF] uppercase">
                <Activity className="w-3.5 h-3.5 animate-pulse" />
                <span>Now Syncing Telemetry</span>
              </div>
              <h2 className="text-xl md:text-2xl font-black text-white mt-2 tracking-tight">
                {currentTrack.title}
              </h2>
              <p className="text-sm text-white/60 font-medium">
                {currentTrack.artist}
              </p>
              
              {/* Spotify Acoustic Metrics */}
              <div className="mt-4 pt-3 border-t border-white/[0.06] grid grid-cols-3 gap-2 text-center">
                <div className="bg-white/[0.03] p-2 rounded-2xl border border-white/[0.04]">
                  <span className="text-[10px] text-white/40 font-semibold block uppercase">Tempo</span>
                  <span className="text-xs font-bold text-white">{currentTrack.bpm} BPM</span>
                </div>
                <div className="bg-white/[0.03] p-2 rounded-2xl border border-white/[0.04]">
                  <span className="text-[10px] text-white/40 font-semibold block uppercase">Harmonic Key</span>
                  <span className="text-xs font-bold text-white">{currentTrack.key}</span>
                </div>
                <div className="bg-white/[0.03] p-2 rounded-2xl border border-white/[0.04]">
                  <span className="text-[10px] text-white/40 font-semibold block uppercase">Energy</span>
                  <span className="text-xs font-bold text-[#00E5FF]">{(currentTrack.energy * 100).toFixed(0)}%</span>
                </div>
              </div>
            </div>

            {/* CENTER: THE LIVING AGENT CORE (LIVING ORB & SATIETY RING) */}
            <div className="relative w-72 h-72 md:w-80 md:h-80 flex items-center justify-center">
              
              {/* Ambient Red Glow on High Satiety (>75%) */}
              {satietyPercentage > 75 && (
                <div
                  className="absolute inset-0 rounded-full blur-[64px] bg-[#FF2A55]/30 pointer-events-none transition-all duration-700 animate-pulse"
                />
              )}

              {/* Satiety Gauge Ring (SVG Canvas) */}
              <svg className="absolute inset-0 w-full h-full -rotate-90 pointer-events-none" viewBox="0 0 240 240">
                {/* Background Ring */}
                <circle
                  cx="120"
                  cy="120"
                  r="105"
                  stroke="rgba(255, 255, 255, 0.06)"
                  strokeWidth="8"
                  fill="none"
                  strokeDasharray="495 165"
                  strokeLinecap="round"
                />
                {/* Active Satiety Arc */}
                <circle
                  cx="120"
                  cy="120"
                  r="105"
                  stroke={satietyColor}
                  strokeWidth="8"
                  fill="none"
                  strokeDasharray={`${(satietyPercentage / 100) * 495} 660`}
                  strokeLinecap="round"
                  className="transition-all duration-700 ease-out"
                />
              </svg>

              {/* Satiety Readout Badge */}
              <div className="absolute top-4 left-1/2 -translate-x-1/2 text-[10px] font-bold px-3 py-1 rounded-full bg-[#06050A]/90 border border-white/[0.1] text-white/80 shadow-md">
                SATIETY: <span style={{ color: satietyColor }}>{satietyPercentage}%</span>
              </div>

              {/* Outer Living Orb Ripple */}
              <div
                className={`absolute w-44 h-44 rounded-full transition-all duration-500 pointer-events-none ${
                  agentState !== 'IDLE' ? 'animate-pulse-ring' : ''
                }`}
                style={{
                  background: `radial-gradient(circle, ${targetOrbColor}22 0%, transparent 70%)`
                }}
              />

              {/* Living Orb Core (Interactive Bouncy Squish) */}
              <div
                onClick={handleOrbClick}
                className={`relative w-36 h-36 rounded-full cursor-pointer bouncy-btn shadow-2xl flex items-center justify-center transition-all duration-700 ${
                  agentState === 'IDLE' ? 'animate-orb-breathe' : 'animate-orb-active'
                }`}
                style={{
                  background: `radial-gradient(circle at 35% 35%, #FFFFFF 0%, ${targetOrbColor} 60%, #000000 100%)`,
                  boxShadow: `0 0 40px ${targetOrbColor}66, inset 0 0 20px rgba(255,255,255,0.4)`
                }}
              >
                {/* Inner Depth Shimmer */}
                <div className="w-20 h-20 rounded-full bg-white/20 backdrop-blur-sm flex items-center justify-center">
                  {agentState === 'LISTENING' && <Mic className="w-8 h-8 text-black animate-pulse" />}
                  {agentState === 'THINKING' && <Sparkles className="w-8 h-8 text-white animate-spin" />}
                  {agentState === 'SPEAKING' && <Volume2 className="w-8 h-8 text-black animate-bounce" />}
                  {agentState === 'IDLE' && <Radio className="w-7 h-7 text-white/90" />}
                </div>
              </div>

              {/* Orb Sub-label */}
              <div className="absolute bottom-4 left-1/2 -translate-x-1/2 text-[11px] font-bold tracking-[0.2em] uppercase transition-colors" style={{ color: targetOrbColor }}>
                {agentState === 'IDLE' ? 'Tap Orb to Speak' : agentState}
              </div>
            </div>

            {/* BOTTOM: NEURAL FEED TRANSCRIPT & CLAUDE REASONING */}
            <div className="w-full max-w-lg space-y-3">
              <div className="soft-glass-card p-5">
                <div className="flex items-center justify-between pb-3 border-b border-white/[0.06]">
                  <div className="flex items-center gap-2">
                    <span className="w-2 h-2 rounded-full" style={{ backgroundColor: targetOrbColor }} />
                    <span className="text-[11px] font-bold tracking-wider uppercase text-white/80">
                      {agentState === 'IDLE' ? 'Ready for Command' : `Agent: ${agentState}`}
                    </span>
                  </div>
                  {lastReasoning?.voice_agent_audio_base64 && (
                    <button
                      onClick={() => playBase64Audio(lastReasoning.voice_agent_audio_base64!)}
                      className="flex items-center gap-1.5 text-[11px] text-[#00E5FF] hover:underline font-semibold"
                    >
                      <Volume2 className="w-3.5 h-3.5" />
                      Replay Voice
                    </button>
                  )}
                </div>

                {/* Live Transcript / Speech Input */}
                <div className="py-3">
                  <p className="text-sm text-white/90 font-medium leading-relaxed">
                    "{liveTranscript || lastReasoning?.voice_agent_tts || 'Soundstage is clean and focus velocity is locked in.'}"
                  </p>
                  {lastReasoning?.flow_state_analysis && (
                    <p className="text-xs text-white/50 mt-2 italic">
                      Analysis: {lastReasoning.flow_state_analysis}
                    </p>
                  )}
                </div>

                {/* OS Execution Command Badge */}
                {lastReasoning?.os_execution_command?.intent && lastReasoning.os_execution_command.intent !== 'NONE' && (
                  <div className="mt-2 bg-[#7000FF]/15 border border-[#7000FF]/30 rounded-2xl p-2.5 flex items-center justify-between text-xs">
                    <div>
                      <span className="text-[10px] font-bold uppercase text-[#A78BFA] block">OS Media Execution</span>
                      <span className="font-semibold text-white">
                        {lastReasoning.os_execution_command.intent}: "{lastReasoning.os_execution_command.search_query}"
                      </span>
                    </div>
                    <span className="text-[10px] px-2 py-0.5 rounded-full bg-[#00E5FF]/20 text-[#00E5FF] border border-[#00E5FF]/30 font-bold">
                      {lastReasoning.os_execution_command.target_player.split('.').pop()}
                    </span>
                  </div>
                )}
              </div>

              {/* Quick Prompt Bar */}
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Or type vocal command for Claude..."
                  value={userCommandInput}
                  onChange={(e) => setUserCommandInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && userCommandInput.trim()) {
                      executeReasoning(userCommandInput);
                      setUserCommandInput('');
                    }
                  }}
                  className="flex-1 bg-white/[0.04] border border-white/[0.08] rounded-2xl px-4 py-2.5 text-xs text-white placeholder-white/40 focus:outline-none focus:border-[#00E5FF] transition-all"
                />
                <button
                  onClick={() => {
                    if (userCommandInput.trim()) {
                      executeReasoning(userCommandInput);
                      setUserCommandInput('');
                    }
                  }}
                  className="px-4 py-2.5 bg-gradient-to-r from-[#00E5FF] to-[#7000FF] rounded-2xl text-black font-bold text-xs bouncy-btn flex items-center gap-1.5 shadow-lg shadow-[#00E5FF]/20"
                >
                  <Send className="w-3.5 h-3.5" />
                  Ask
                </button>
              </div>
            </div>

          </div>
        )}

        {/* TASTE GENOME / SUPABASE MEMORY VIEW */}
        {activeTab === 'GENOME' && (
          <div className="w-full max-w-2xl mx-auto space-y-4 py-4">
            <div className="soft-glass-card p-6">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-2xl bg-[#00E5FF]/10 border border-[#00E5FF]/30 flex items-center justify-center">
                    <Database className="w-4 h-4 text-[#00E5FF]" />
                  </div>
                  <div>
                    <h3 className="text-base font-bold text-white">Supabase Taste Genome</h3>
                    <p className="text-xs text-white/50">Historical interventions preventing repetitive suggestions</p>
                  </div>
                </div>
                <span className="text-xs px-3 py-1 rounded-full bg-[#10B981]/20 text-[#10B981] border border-[#10B981]/30 font-semibold flex items-center gap-1.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-ping" />
                  Synced
                </span>
              </div>

              {/* Memory List */}
              <div className="mt-6 space-y-3">
                {interventions.map((item, idx) => (
                  <div key={idx} className="bg-white/[0.03] p-4 rounded-2xl border border-white/[0.05] space-y-2">
                    <div className="flex items-center justify-between text-xs text-white/60">
                      <span className="font-semibold text-white/90">"{item.trigger_transcript}"</span>
                      <span>{new Date(item.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
                    </div>
                    <p className="text-xs text-[#00E5FF] font-medium leading-relaxed">
                      "{item.agent_tts_response}"
                    </p>
                    <div className="flex items-center gap-2 pt-1 text-[10px]">
                      <span className="px-2 py-0.5 rounded-md bg-white/[0.05] text-white/70">
                        Intent: {item.os_intent}
                      </span>
                      {item.target_search_query && (
                        <span className="px-2 py-0.5 rounded-md bg-[#7000FF]/20 text-[#A78BFA] border border-[#7000FF]/30">
                          Search: {item.target_search_query}
                        </span>
                      )}
                      <span className="px-2 py-0.5 rounded-md bg-red-500/10 text-red-400 font-bold ml-auto">
                        Satiety: {item.computed_satiety}%
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* TELEMETRY SIMULATOR */}
        {activeTab === 'SIMULATOR' && (
          <div className="w-full max-w-2xl mx-auto space-y-4 py-4">
            <div className="soft-glass-card p-6 space-y-6">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <Zap className="w-4 h-4 text-[#00E5FF]" />
                  Acoustic Telemetry Simulator
                </h3>
                <p className="text-xs text-white/50">Simulate incoming Spotify playback changes and test fatigue thresholds</p>
              </div>

              {/* Satiety Slider */}
              <div className="space-y-2">
                <div className="flex justify-between text-xs font-semibold">
                  <span className="text-white/70">Acoustic Satiety Index</span>
                  <span style={{ color: satietyColor }}>{satietyPercentage}%</span>
                </div>
                <input
                  type="range"
                  min="10"
                  max="95"
                  value={satietyPercentage}
                  onChange={(e) => setSatietyPercentage(Number(e.target.value))}
                  className="w-full accent-[#00E5FF] bg-white/[0.1] h-1.5 rounded-lg cursor-pointer"
                />
                <span className="text-[11px] text-white/40 block">
                  {satietyPercentage > 75 ? '⚠️ Fatigue Threshold Crossed: Red halo active. Agent recommends acoustic cleanser.' : 'Normal receptivity flow state.'}
                </span>
              </div>

              {/* Quick Preset Songs */}
              <div className="space-y-2">
                <span className="text-xs text-white/70 font-semibold block">Simulate Track Observation</span>
                <div className="grid grid-cols-2 gap-2">
                  {[
                    { title: 'Starboy', artist: 'The Weeknd', bpm: 186, energy: 0.82, key: 'G Minor' },
                    { title: 'Midnight Chill', artist: 'Lofi Fruit', bpm: 82, energy: 0.35, key: 'C Major' },
                    { title: 'Malaika', artist: 'Sauti Sol', bpm: 104, energy: 0.55, key: 'A Minor' },
                    { title: 'Kuna Kuna', artist: 'Vic West', bpm: 112, energy: 0.78, key: 'F# Minor' }
                  ].map((track, i) => (
                    <button
                      key={i}
                      onClick={() => setCurrentTrack({ ...track, source_player: 'com.spotify.music' })}
                      className="bg-white/[0.03] hover:bg-white/[0.08] p-3 rounded-2xl border border-white/[0.06] text-left text-xs bouncy-btn"
                    >
                      <span className="font-bold text-white block">{track.title}</span>
                      <span className="text-white/50 text-[10px]">{track.artist} • {track.bpm} BPM</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Quick Commands */}
              <div className="space-y-2 pt-2 border-t border-white/[0.06]">
                <span className="text-xs text-white/70 font-semibold block">Quick Vocal Trigger Injections</span>
                <div className="flex flex-wrap gap-2">
                  {[
                    'Switch to high tempo for workout',
                    'Ear fatigue is high, give me an acoustic reset',
                    'Play authentic Kikuyu Mugithi songs',
                    'Deep focus mode for programming'
                  ].map((cmd, i) => (
                    <button
                      key={i}
                      onClick={() => executeReasoning(cmd)}
                      className="text-xs px-3 py-1.5 rounded-xl bg-white/[0.04] hover:bg-white/[0.08] text-white/80 border border-white/[0.06] bouncy-btn"
                    >
                      "{cmd}"
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* FOOTER CONTROLS & ARCHITECTURE BADGE */}
      <footer className="px-6 py-3 border-t border-white/[0.06] bg-[#06050A]/80 backdrop-blur-md flex flex-wrap items-center justify-between text-xs text-white/50 gap-2">
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1.5 text-white/70 font-semibold">
            <span className="w-2 h-2 rounded-full bg-[#10B981]" />
            Agent Engine Live
          </span>
          <span>•</span>
          <span>Vosk Wake Word</span>
          <span>•</span>
          <span>AssemblyAI 16kHz</span>
          <span>•</span>
          <span>Claude 3.5 Haiku</span>
          <span>•</span>
          <span>ElevenLabs TTS</span>
        </div>

        <div className="text-[11px] text-white/40">
          TuneMind Synaptic Obsidian v0.3.0
        </div>
      </footer>
    </div>
  );
}
