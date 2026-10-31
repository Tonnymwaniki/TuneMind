import React from 'react';

export const HearingHealthView: React.FC = () => {
  const currentDb = 72;
  const isSafe = currentDb < 85;

  return (
    <div className="flex flex-col w-full text-[#E9EDF5] select-none pb-20">
      {/* Top Hardware Telemetry Strip & Device Bridge */}
      <div className="pt-2 pb-3 flex flex-col gap-1">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse"></span>
            <span className="font-mono text-[10px] text-[#A1AEC2] tracking-widest uppercase">
              HEARING HEALTH // EXPOSURE ENGINE
            </span>
          </div>
          <span className="font-mono text-[10px] text-[#83B9BD] bg-[#080e1c] px-1.5 py-0.5 rounded border border-[#1C2940]">
            DSP BUS • 48kHz
          </span>
        </div>

        {/* Active Transducer Recessed Pill */}
        <div className="bg-[#131D30] border border-[#25344D]/60 px-3 py-2 rounded flex items-center justify-between shadow-[inset_0_2px_4px_rgba(0,0,0,0.6)] mt-1">
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-mono text-xs text-[#A997D8] font-bold">🎧</span>
            <span className="text-xs text-[#E9EDF5] font-semibold truncate">Sony WH-1000XM5</span>
            <span className="font-mono text-[10px] text-[#A1AEC2] shrink-0">(A2DP • LDAC)</span>
          </div>
          <div className="flex items-center gap-1 shrink-0">
            <span className="font-mono text-[10px] text-[#10B981] font-bold">SYNCED</span>
          </div>
        </div>
      </div>

      {/* Main Centerpiece: Analog / Digital Precision Arc VU Faceplate */}
      <div className="py-2">
        <div className="bg-[#1C2940] border border-[#25344D]/80 rounded-xl p-4 shadow-[0_12px_28px_rgba(0,0,0,0.7)] flex flex-col items-center relative overflow-hidden">
          {/* Meter Calibration Legend */}
          <div className="w-full flex items-center justify-between font-mono text-[10px] text-[#A1AEC2] mb-1">
            <span>RANGE: 40-120 dBA</span>
            <span className="text-[#83B9BD] font-bold">MONITOR: CONTINUOUS</span>
          </div>

          {/* Precision Arc SVG Visualizer */}
          <div className="relative w-full max-w-[280px] aspect-[280/145] flex items-center justify-center">
            <svg className="w-full h-full select-none" viewBox="0 0 310 165">
              <defs>
                <linearGradient id="arcTrackGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                  <stop offset="0%" stopColor="#83B9BD" />
                  <stop offset="55%" stopColor="#83B9BD" />
                  <stop offset="70%" stopColor="#F59E0B" />
                  <stop offset="100%" stopColor="#CF7778" />
                </linearGradient>
              </defs>
              <path d="M 25 155 A 130 130 0 0 1 285 155" stroke="#131D30" strokeWidth="18" strokeLinecap="round" fill="none" />
              <path d="M 25 155 A 130 130 0 0 1 285 155" stroke="url(#arcTrackGrad)" strokeWidth="6" strokeLinecap="round" opacity="0.85" fill="none" />
              <circle cx="155" cy="155" r="16" fill="#080e1c" />
              <circle cx="155" cy="155" r="10" fill="#25344D" />
              <circle cx="155" cy="155" r="3" fill="#83B9BD" />
              <line x1="155" y1="155" x2="119.5" y2="45.6" stroke="#83B9BD" strokeWidth="2.5" strokeLinecap="round" />
            </svg>

            {/* Readout at Bottom */}
            <div className="absolute bottom-1 text-center">
              <span className="font-mono text-2xl font-bold text-[#83B9BD]">{currentDb} dBA</span>
              <p className="font-mono text-[9px] text-[#10B981] font-bold">SAFE CONTINUOUS EXPOSURE</p>
            </div>
          </div>

          <div className="w-full flex justify-between font-mono text-[9px] text-[#A1AEC2] mt-2 px-2">
            <span>40</span>
            <span className="text-[#83B9BD]">60</span>
            <span>80</span>
            <span className="text-[#F59E0B] font-bold">85 CRIT</span>
            <span className="text-[#CF7778]">120</span>
          </div>
        </div>
      </div>

      {/* Headroom Telemetry Strip */}
      <div className="mt-3 p-3 bg-[#131D30] border border-[#25344D]/50 rounded-xl flex items-center justify-between">
        <div className="flex flex-col">
          <span className="font-mono text-[9px] text-[#A1AEC2] uppercase">HEADROOM (TO 85 dBA)</span>
          <span className="font-mono text-base font-bold text-[#A997D8]">+13.0 dB</span>
        </div>
        <div className="h-6 w-px bg-[#25344D]"></div>
        <div className="flex flex-col items-end">
          <span className="font-mono text-[9px] text-[#A1AEC2] uppercase">SAFE ALLOWANCE</span>
          <span className="font-mono text-base font-bold text-[#10B981]">8.0 HOURS</span>
        </div>
      </div>
    </div>
  );
};
