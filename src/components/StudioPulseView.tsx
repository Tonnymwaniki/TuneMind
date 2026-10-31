import React from 'react';
import { TrackItem } from '../types';

interface StudioPulseViewProps {
  currentTrack: TrackItem;
  isPlaying: boolean;
  onTogglePlay: () => void;
  onOpenMoments: () => void;
  onOpenHearing: () => void;
  onOpenFatigue: () => void;
  onOpenSwitchboard: () => void;
  isDaemonLive: boolean;
}

export const StudioPulseView: React.FC<StudioPulseViewProps> = ({
  currentTrack,
  isPlaying,
  onTogglePlay,
  onOpenMoments,
  onOpenHearing,
  onOpenFatigue,
  onOpenSwitchboard,
  isDaemonLive
}) => {
  const [isRestActive, setIsRestActive] = React.useState(false);
  const [isLoopActive, setIsLoopActive] = React.useState(false);

  // Dynamic Satiety
  const satiety = currentTrack?.satietyScore ?? 64;

  return (
    <div className="flex flex-col w-full gap-4 select-none pb-20">
      {/* Top Hardware Telemetry Bar */}
      <div className="flex items-center justify-between pt-1">
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs font-bold tracking-tight text-[#E9EDF5] uppercase">TuneMind</span>
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-[#131D30] border border-[#25344D]/60 shadow-[inset_0_1px_2px_rgba(0,0,0,0.6)]">
            <span className="relative flex h-2 w-2">
              <span className={`animate-ping absolute inline-flex h-full w-full rounded-full ${isDaemonLive ? 'bg-[#10B981]' : 'bg-[#F59E0B]'} opacity-75`}></span>
              <span className={`relative inline-flex rounded-full h-2 w-2 ${isDaemonLive ? 'bg-[#10B981]' : 'bg-[#F59E0B]'}`}></span>
            </span>
            <span className="font-mono text-[9px] leading-none tracking-widest text-[#A1AEC2] uppercase">
              {isDaemonLive ? 'DAEMON // LIVE' : 'DAEMON // STANDBY'}
            </span>
          </div>
        </div>

        {/* Tactile Rotary Utility Keys */}
        <div className="flex items-center gap-2">
          <button
            onClick={onOpenSwitchboard}
            aria-label="Atmosphere Switchboard"
            className="w-8 h-8 rounded-full bg-[#131D30] border border-[#25344D]/50 flex items-center justify-center text-[#A1AEC2] hover:text-[#A997D8] shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.4)] active:translate-y-px transition-all"
          >
            <span className="font-mono text-[11px] font-bold">DSP</span>
          </button>
          <button
            onClick={onOpenHearing}
            aria-label="Acoustic Biometrics"
            className="w-8 h-8 rounded-full bg-[#131D30] border border-[#25344D]/50 flex items-center justify-center text-[#A1AEC2] hover:text-[#83B9BD] shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.4)] active:translate-y-px transition-all"
          >
            <span className="font-mono text-[11px] font-bold">dBA</span>
          </button>
        </div>
      </div>

      {/* Hero Section: Now Playing Deck */}
      <div className="flex flex-col items-center w-full">
        {/* Floating Album Faceplate with Laser Lavender Radial Glow */}
        <div className="relative flex items-center justify-center my-2">
          <div className="absolute w-52 h-52 rounded-full bg-[#A997D8]/20 blur-3xl -z-10 pointer-events-none transform-gpu animate-pulse"></div>

          {/* Outer Machined Rim */}
          <div className="relative w-[210px] h-[210px] rounded-full p-1 bg-gradient-to-b from-[#25344D] to-[#0C1220] shadow-[0_12px_32px_rgba(0,0,0,0.7),inset_0_1px_1px_rgba(255,255,255,0.08)] flex items-center justify-center">
            {/* Vinyl Bezel Ring */}
            <div className="relative w-[200px] h-[200px] rounded-[16px] overflow-hidden bg-[#131D30] shadow-[inset_0_0_12px_rgba(0,0,0,0.9)] flex items-center justify-center">
              <img
                className="w-full h-full object-cover transition-transform duration-700 ease-out hover:scale-105"
                src="https://lh3.googleusercontent.com/aida-public/AB6AXuDw87ZCxUpLOaiazog3TSAgKf4TQGgSUT-mzJX-a1kJrga_nClyr1ufP5RFqGMMWZO2vrUOusEfzQyG1zl5tphz_YRa8yzMvawAYGfywhQo0lRuuRCukZim9iEp-J2uNFtlNNmfjtEt1qjxpODSviD3WpkzCGFevSB2Bp-jljiOD6uexKMR62sbWF0c5cjCUptXtGqF05s0_EO5MH4uYGkReI1C65WYo8E7T6oTwq__"
                alt="Vinyl Record Sleeve"
              />
              <div className="absolute inset-0 bg-gradient-to-tr from-[#0C1220]/40 via-transparent to-white/10 pointer-events-none"></div>
              {/* Micro Monospace Hardware ID Badge */}
              <div className="absolute bottom-2 right-2 px-1.5 py-0.5 rounded bg-[#0C1220]/80 backdrop-blur-md shadow-sm border border-[#25344D]/40">
                <span className="font-mono text-[9px] text-[#A1AEC2] tracking-widest uppercase">VINYL-EMU // 48K</span>
              </div>
            </div>
          </div>
        </div>

        {/* Acoustic Metadata Strip */}
        <div className="flex flex-col items-center text-center mt-3 w-full">
          <h2 className="text-xl font-bold text-[#E9EDF5] tracking-tight">{currentTrack?.title || 'Starboy'}</h2>
          <div className="flex items-center gap-1.5 mt-0.5">
            <span className="text-sm text-[#A1AEC2]">{currentTrack?.artist || 'The Weeknd'}</span>
            <span className="w-1 h-1 rounded-full bg-[#49454f]"></span>
            <span className="text-sm text-[#A1AEC2]">{currentTrack?.sourceApp || 'Spotify'} Stream</span>
          </div>

          {/* Vibe & Harmonic Frequency Chip */}
          <div className="mt-2 flex items-center gap-2 px-2.5 py-1 rounded-full bg-[#1C2940] border border-[#25344D]/50 shadow-[inset_0_1px_2px_rgba(0,0,0,0.5)]">
            <span className="w-1.5 h-1.5 rounded-full bg-[#83B9BD] shadow-[0_0_6px_#83B9BD]"></span>
            <span className="font-mono text-[10px] text-[#83B9BD] tracking-wider uppercase font-semibold">
              REFLECTIVE // 118 BPM
            </span>
          </div>
        </div>

        {/* Satiety Meter (Hardware Segmented Acoustic Freshness) */}
        <div className="w-full mt-4 px-1 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-[#A1AEC2]">
            <span className="font-mono text-[11px] tracking-widest uppercase flex items-center gap-1">
              ACOUSTIC SATIETY: <span className="text-[#E9EDF5] font-bold">{satiety}%</span>
            </span>
            <span className="font-mono text-[10px] text-[#83B9BD] uppercase tracking-wider">[Optimal Freshness]</span>
          </div>

          {/* Segmented LED Bar (24 micro-cells) */}
          <div className="w-full h-3 rounded-sm bg-[#131D30] p-0.5 border border-[#25344D]/60 shadow-[inset_0_1px_3px_rgba(0,0,0,0.9)] flex items-center gap-[2px]">
            {Array.from({ length: 24 }).map((_, i) => {
              const isActive = i < Math.round((satiety / 100) * 24);
              let color = 'bg-[#1C2940]/40';
              if (isActive) {
                if (i < 8) color = 'bg-[#D5A66F] shadow-[0_0_4px_#D5A66F]';
                else if (i < 16) color = 'bg-[#A997D8] shadow-[0_0_4px_#A997D8]';
                else color = 'bg-[#83B9BD] shadow-[0_0_5px_#83B9BD]';
              }
              return <div key={i} className={`flex-1 h-full rounded-[1px] ${color}`} />;
            })}
          </div>

          <div className="flex justify-between items-center px-0.5">
            <span className="font-mono text-[9px] text-[#A1AEC2]/70">DEGRADATION RISK: LOW</span>
            <span className="font-mono text-[9px] text-[#A1AEC2]/70">FATIGUE THRESHOLD: 85%</span>
          </div>
        </div>

        {/* Machined Transport Controls Deck */}
        <div className="w-full mt-4 py-2 px-4 rounded-xl bg-[#131D30]/80 border border-[#25344D]/50 shadow-[inset_0_2px_4px_rgba(0,0,0,0.7),inset_0_-1px_1px_rgba(255,255,255,0.04)] flex items-center justify-between">
          {/* Rest Track Toggle */}
          <button
            onClick={() => setIsRestActive(!isRestActive)}
            aria-label="Rest Track / Anti-Burnout"
            className={`w-10 h-10 rounded-lg bg-[#1C2940] border border-[#25344D]/60 flex items-center justify-center ${
              isRestActive ? 'text-[#D5A66F]' : 'text-[#A1AEC2]'
            } active:bg-[#25344D] shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.5)] active:translate-y-px transition-all`}
          >
            <span className="font-mono text-xs font-bold">REST</span>
          </button>

          {/* Stepper: Previous */}
          <button
            aria-label="Step Previous Track"
            className="w-10 h-10 rounded-lg bg-[#1C2940] border border-[#25344D]/60 flex items-center justify-center text-[#E9EDF5] active:bg-[#25344D] shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.5)] active:translate-y-px transition-all"
          >
            <span className="font-mono text-xs font-bold">PREV</span>
          </button>

          {/* Centerpiece: Laser Lavender Rotary Console Dial */}
          <div className="relative flex items-center justify-center">
            <div className="absolute -inset-1 rounded-full bg-[#A997D8]/30 blur-md pointer-events-none"></div>
            <button
              onClick={onTogglePlay}
              aria-label="Toggle Playback"
              className="relative w-14 h-14 rounded-full bg-[#A997D8] text-[#0C1220] flex items-center justify-center shadow-[inset_0_2px_2px_rgba(255,255,255,0.4),0_6px_16px_rgba(0,0,0,0.7)] active:scale-95 transition-transform"
            >
              <span className="font-mono text-sm font-extrabold">{isPlaying ? 'PAUSE' : 'PLAY'}</span>
            </button>
          </div>

          {/* Stepper: Next */}
          <button
            aria-label="Step Next Track"
            className="w-10 h-10 rounded-lg bg-[#1C2940] border border-[#25344D]/60 flex items-center justify-center text-[#E9EDF5] active:bg-[#25344D] shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.5)] active:translate-y-px transition-all"
          >
            <span className="font-mono text-xs font-bold">NEXT</span>
          </button>

          {/* Dynamic Cycle / Loop Switch */}
          <button
            onClick={() => setIsLoopActive(!isLoopActive)}
            aria-label="Adaptive Acoustic Rotation"
            className={`w-10 h-10 rounded-lg bg-[#1C2940] border border-[#25344D]/60 flex items-center justify-center ${
              isLoopActive ? 'text-[#A997D8]' : 'text-[#83B9BD]'
            } shadow-[inset_0_1px_1px_rgba(255,255,255,0.06),0_2px_4px_rgba(0,0,0,0.5)] active:translate-y-px transition-all`}
          >
            <span className="font-mono text-xs font-bold">LOOP</span>
          </button>
        </div>
      </div>

      {/* Mid Section: Contextual Intelligence & Anti-Fatigue Modular Ribbon */}
      <div className="flex flex-col w-full mt-2 gap-2">
        <div className="flex items-center gap-2">
          <span className="font-mono text-[10px] text-[#A1AEC2] tracking-widest uppercase font-semibold">
            CONTEXTUAL INTELLIGENCE // ANTI-FATIGUE QUEUE
          </span>
          <div className="flex-1 h-[1px] bg-[#1C2940]"></div>
          <span className="font-mono text-[10px] text-[#A997D8] uppercase font-bold tracking-wider">PREDICTIVE</span>
        </div>

        {/* Cartridge 1: Dopamine Re-engage */}
        <div
          onClick={onOpenFatigue}
          className="group relative w-full p-3 rounded-lg bg-[#131D30] border border-[#25344D]/40 shadow-[inset_0_1px_1px_rgba(255,255,255,0.04),0_4px_12px_rgba(0,0,0,0.3)] hover:bg-[#1C2940]/60 transition-colors flex items-center justify-between cursor-pointer"
        >
          <div className="flex items-center gap-3 min-w-0">
            <div className="relative w-11 h-11 rounded bg-[#0C1220] border border-[#25344D]/50 shadow-[inset_0_1px_2px_rgba(0,0,0,0.9)] overflow-hidden shrink-0 flex items-center justify-center p-0.5">
              <span className="font-mono text-[10px] text-[#A997D8] font-bold">M83</span>
              <div className="absolute bottom-0 inset-x-0 h-1 bg-[#A997D8]"></div>
            </div>
            <div className="flex flex-col min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="font-semibold text-sm text-[#E9EDF5] truncate">Midnight City</span>
                <span className="text-[#A1AEC2] text-xs">•</span>
                <span className="text-xs text-[#A1AEC2] truncate">M83</span>
              </div>
              <p className="text-[11px] text-[#A1AEC2]/90 mt-0.5 truncate">Warm vintage synth palette • 94% harmonic match</p>
              <div className="flex items-center gap-1.5 mt-1">
                <span className="px-1.5 py-0.2 rounded bg-[#A997D8]/15 font-mono text-[9px] text-[#A997D8] tracking-wider font-semibold uppercase">
                  DOPAMINE RE-ENGAGE
                </span>
                <span className="font-mono text-[9px] text-[#83B9BD] tracking-wider">Δ -12dB FATIGUE</span>
              </div>
            </div>
          </div>
        </div>

        {/* Cartridge 2: Calm Spectral Recovery */}
        <div
          onClick={onOpenMoments}
          className="group relative w-full p-3 rounded-lg bg-[#131D30] border border-[#25344D]/40 shadow-[inset_0_1px_1px_rgba(255,255,255,0.04),0_4px_12px_rgba(0,0,0,0.3)] hover:bg-[#1C2940]/60 transition-colors flex items-center justify-between cursor-pointer"
        >
          <div className="flex items-center gap-3 min-w-0">
            <div className="relative w-11 h-11 rounded bg-[#0C1220] border border-[#25344D]/50 shadow-[inset_0_1px_2px_rgba(0,0,0,0.9)] overflow-hidden shrink-0 flex items-center justify-center p-0.5">
              <span className="font-mono text-[10px] text-[#D5A66F] font-bold">HOME</span>
              <div className="absolute bottom-0 inset-x-0 h-1 bg-[#D5A66F]"></div>
            </div>
            <div className="flex flex-col min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="font-semibold text-sm text-[#E9EDF5] truncate">Resonance</span>
                <span className="text-[#A1AEC2] text-xs">•</span>
                <span className="text-xs text-[#A1AEC2] truncate">HOME</span>
              </div>
              <p className="text-[11px] text-[#A1AEC2]/90 mt-0.5 truncate">Chilled spectral rest • 88% acoustic parity</p>
              <div className="flex items-center gap-1.5 mt-1">
                <span className="px-1.5 py-0.2 rounded bg-[#D5A66F]/15 font-mono text-[9px] text-[#D5A66F] tracking-wider font-semibold uppercase">
                  CALM RECOVERY
                </span>
                <span className="font-mono text-[9px] text-[#A1AEC2] tracking-wider">NEURO-DRIFT: 0.12</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Hardware Master Telemetry Micro-readout Footer */}
      <div className="w-full flex items-center justify-between pt-2 text-[#A1AEC2]/60 font-mono text-[9px] tracking-widest uppercase">
        <span>DSP MOTOR // D-887</span>
        <span>PEAK HEADROOM: -2.4 dB</span>
        <span>BIT-PERFECT 24b/96k</span>
      </div>
    </div>
  );
};
