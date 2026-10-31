import React, { useState } from 'react';
import { TrackItem } from '../types';

interface SatietyLabVaultViewProps {
  cooldownTracks: TrackItem[];
  onReleaseTrack?: (id: string) => void;
  onAddTrack?: () => void;
}

export const SatietyLabVaultView: React.FC<SatietyLabVaultViewProps> = ({
  cooldownTracks,
  onReleaseTrack,
  onAddTrack
}) => {
  const [autoRevive, setAutoRevive] = useState(true);

  return (
    <div className="flex flex-col w-full text-[#E9EDF5] select-none pb-20">
      {/* Top Telemetry Status Rail */}
      <div className="pt-2 pb-2 flex flex-col gap-1">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#83B9BD] animate-pulse"></span>
            <span className="font-mono text-[10px] text-[#83B9BD] tracking-wider uppercase">
              CRYOGENIC COOLDOWN PROTOCOL
            </span>
          </div>
          <span className="font-mono text-[10px] text-[#A1AEC2] uppercase tracking-widest opacity-80">
            VAULT // 48kHz ISO
          </span>
        </div>
        <div className="mt-1">
          <h2 className="text-lg font-bold text-[#E9EDF5] uppercase tracking-wider">
            SATIETY LAB // AUDITORY REST
          </h2>
          <p className="text-xs text-[#A1AEC2] mt-0.5 opacity-90">
            Preventing melodic burnout by quarantining overplayed tracks.
          </p>
        </div>
      </div>

      {/* Centerpiece: Tachometer Gauge Module (Integrated Recessed Well) */}
      <div className="my-3">
        <div className="relative bg-[#1C2940]/60 border border-[#25344D]/60 rounded-xl p-4 shadow-[inset_0_2px_8px_rgba(0,0,0,0.6)] overflow-hidden">
          <div className="relative flex flex-col items-center">
            {/* Gauge SVG */}
            <div className="w-full max-w-[280px] h-[130px] relative flex items-end justify-center">
              <svg className="w-full h-full overflow-visible" viewBox="0 0 240 130">
                <defs>
                  <linearGradient id="gaugeSweep" x1="0%" y1="100%" x2="100%" y2="100%">
                    <stop offset="0%" stopColor="#83B9BD" />
                    <stop offset="55%" stopColor="#83B9BD" />
                    <stop offset="85%" stopColor="#A997D8" />
                    <stop offset="100%" stopColor="#CF7778" />
                  </linearGradient>
                </defs>
                <path d="M 25 120 A 95 95 0 0 1 215 120" fill="none" stroke="#131D30" strokeLinecap="round" strokeWidth="12" />
                <path d="M 25 120 A 95 95 0 0 1 78 46" fill="none" stroke="url(#gaugeSweep)" strokeLinecap="round" strokeWidth="12" />
                <circle cx="120" cy="120" r="14" fill="#0C1220" />
                <circle cx="120" cy="120" r="7" fill="#83B9BD" />
                <g transform="rotate(-52 120 120)">
                  <polygon points="118.5,120 121.5,120 120.5,38 119.5,38" fill="#83B9BD" />
                  <line x1="120" y1="36" x2="120" y2="28" stroke="#E9EDF5" strokeWidth="2" strokeLinecap="round" />
                </g>
              </svg>

              <div className="absolute bottom-0 text-center pb-1">
                <div className="flex items-center justify-center gap-1.5 mb-0.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-[#83B9BD] shadow-[0_0_6px_rgba(131,185,189,0.9)]"></span>
                  <span className="font-mono text-[10px] text-[#E9EDF5] uppercase tracking-wider font-bold">
                    BURNOUT VELOCITY: LOW
                  </span>
                </div>
                <span className="font-mono text-[9px] text-[#83B9BD] opacity-80">STABLE EQUILIBRIUM</span>
              </div>
            </div>

            {/* Telemetry Data Strip */}
            <div className="w-full mt-3 pt-3 flex items-center justify-between bg-[#131D30]/80 border border-[#25344D]/50 px-3 py-2 rounded-lg shadow-inner">
              <div className="flex flex-col">
                <span className="font-mono text-[10px] text-[#A1AEC2] uppercase">RECOVERY QUEUE</span>
                <span className="font-mono text-sm text-[#E9EDF5] font-medium">{cooldownTracks.length} Tracks • 4.2d Avg</span>
              </div>
              <div className="h-6 w-px bg-[#25344D]"></div>
              <div className="flex flex-col items-end">
                <span className="font-mono text-[10px] text-[#A1AEC2] uppercase">DELTA FATIGUE</span>
                <span className="font-mono text-sm text-[#83B9BD] font-bold tracking-tight">-14.2% [1.1x]</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Vault Carousel Section Header */}
      <div className="mt-3 mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="font-mono text-[11px] text-[#83B9BD] tracking-wider font-semibold">
            QUARANTINED CARTRIDGES
          </span>
          <span className="px-1.5 py-0.5 rounded bg-[#25344D] font-mono text-[10px] text-[#E9EDF5]">
            {cooldownTracks.length} ACTIVE
          </span>
        </div>
        <button
          onClick={() => setAutoRevive(!autoRevive)}
          className="flex items-center gap-2 text-[#A1AEC2] hover:text-[#E9EDF5]"
        >
          <span className="font-mono text-[10px] tracking-widest uppercase opacity-70">AUTO-REVIVE</span>
          <span className={`w-2 h-2 rounded-full ${autoRevive ? 'bg-[#10B981]' : 'bg-[#49454f]'}`}></span>
        </button>
      </div>

      {/* Quarantined Vault Cartridges (Tactile List) */}
      <div className="flex flex-col gap-2.5">
        {cooldownTracks.map((track) => (
          <div
            key={track.id}
            className="w-full bg-[#1C2940]/75 border border-[#25344D]/60 rounded-xl p-3 flex items-center justify-between shadow-[inset_0_1px_1px_rgba(131,185,189,0.3)] backdrop-blur-md"
          >
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-11 h-11 rounded-lg bg-[#0C1220] border border-[#25344D]/50 flex items-center justify-center shrink-0">
                <span className="font-mono text-[10px] text-[#83B9BD] font-bold">CRYO</span>
              </div>
              <div className="flex flex-col min-w-0">
                <span className="font-bold text-sm text-[#E9EDF5] truncate">{track.title}</span>
                <span className="text-xs text-[#A1AEC2] truncate">{track.artist}</span>
                <div className="flex items-center gap-2 mt-1">
                  <span className="px-1.5 py-0.2 rounded bg-[#D5A66F]/15 font-mono text-[9px] text-[#D5A66F] font-bold">
                    {track.cooldownDaysRemaining ?? 5}d COOLDOWN
                  </span>
                  <span className="font-mono text-[9px] text-[#A1AEC2]">FATIGUE 88%</span>
                </div>
              </div>
            </div>

            {onReleaseTrack && (
              <button
                onClick={() => onReleaseTrack(track.id)}
                className="w-8 h-8 rounded-lg bg-[#131D30] border border-[#25344D]/60 flex items-center justify-center text-[#A1AEC2] hover:text-[#CF7778] active:scale-95 transition-all ml-2"
                title="Release Track"
              >
                <span className="font-mono text-xs font-bold">✕</span>
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};
