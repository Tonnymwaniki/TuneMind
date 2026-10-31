import React, { useState } from 'react';

interface MusicalMomentsViewProps {
  onBack?: () => void;
}

export const MusicalMomentsView: React.FC<MusicalMomentsViewProps> = ({ onBack }) => {
  const [filter, setFilter] = useState<'ALL' | 'FAVORITES' | 'SYNTHS' | 'VOCAL'>('ALL');
  const [shuffleLoop, setShuffleLoop] = useState(false);

  const moments = [
    {
      id: 'm1',
      name: 'Midnight Synth Breakdown & Vox',
      track: 'Starboy',
      artist: 'The Weeknd',
      bpm: 118,
      start: '01:14',
      end: '01:44',
      loopCount: 42,
      active: true,
      tag: 'SYNTH'
    },
    {
      id: 'm2',
      name: 'Tape Flutter Outro Hook',
      track: 'Resonance',
      artist: 'HOME',
      bpm: 105,
      start: '02:30',
      end: '03:00',
      loopCount: 19,
      active: false,
      tag: 'CHILL'
    },
    {
      id: 'm3',
      name: 'Sub-Bass Drop & Vocal Chants',
      track: 'Midnight City',
      artist: 'M83',
      bpm: 105,
      start: '03:12',
      end: '03:45',
      loopCount: 31,
      active: false,
      tag: 'SYNTH'
    }
  ];

  return (
    <div className="flex flex-col w-full text-[#E9EDF5] select-none pb-20">
      {/* 1. Header / Console Telemetry Status Bar */}
      <div className="py-2 flex items-center justify-between border-b border-[#1C2940]">
        <div className="flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse"></span>
          <span className="font-mono text-[10px] uppercase text-[#A1AEC2] tracking-wider">
            DSP BUFFER // LIVE
          </span>
        </div>
        <div className="px-2 py-0.5 rounded bg-[#131D30] border border-[#25344D]/50 flex items-center gap-1 text-[#A1AEC2]">
          <span className="font-mono text-[10px] text-[#83B9BD] font-bold">48kHz / 24B</span>
        </div>
      </div>

      {/* 2. Track Context Subheader Ribbon */}
      <div className="py-2.5 px-3 bg-[#080e1c]/80 border border-[#1C2940] rounded-lg mt-2 flex items-center justify-between">
        <div className="min-w-0">
          <p className="font-mono text-[9px] text-[#A1AEC2] uppercase leading-none">TRACK SOURCE</p>
          <p className="text-sm text-[#E9EDF5] truncate mt-0.5 font-bold">
            Starboy <span className="text-[#A1AEC2] font-normal">— The Weeknd</span>
          </p>
        </div>
        <div className="shrink-0 flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#131D30] text-[#D5A66F] border border-[#25344D]/40">
          <span className="font-mono text-[10px] font-bold">118 BPM // REFLECTIVE</span>
        </div>
      </div>

      {/* 3. Center Hero: Waveform Scrubber Bay */}
      <div className="mt-3 relative w-full rounded-lg bg-[#080e1c] border border-[#1C2940] p-3 flex flex-col">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="font-mono text-[10px] text-[#A1AEC2] tracking-widest uppercase">
              STEREO SPECTRAL FLUX
            </span>
            <span className="font-mono text-[9px] px-1.5 rounded bg-[#25344D] text-[#A997D8]">CH-A/B</span>
          </div>
          <span className="font-mono text-[10px] text-[#10B981]">PEAK -1.8 dBFS</span>
        </div>

        {/* Waveform Canvas Simulation */}
        <div className="relative w-full h-32 bg-[#131D30]/90 rounded border border-[#25344D]/40 flex items-center justify-between px-2 overflow-hidden">
          {/* Waveform SVGs */}
          <div className="w-full flex items-center justify-between gap-[2px] h-full opacity-80 py-2">
            {Array.from({ length: 36 }).map((_, i) => {
              const inActive = i >= 10 && i <= 24;
              const h = inActive ? 40 + (i % 5) * 12 : 15 + (i % 3) * 8;
              const bg = inActive
                ? i % 2 === 0
                  ? 'bg-[#A997D8]'
                  : 'bg-[#83B9BD]'
                : 'bg-[#25344D]';
              return (
                <div
                  key={i}
                  className={`w-1.5 rounded-full ${bg} transition-all`}
                  style={{ height: `${h}%` }}
                />
              );
            })}
          </div>

          {/* Dual Marker Pins */}
          <div className="absolute top-2 bottom-2 left-[28%] flex flex-col items-center pointer-events-none">
            <span className="px-1.5 py-0.5 rounded bg-[#A997D8] text-[#0C1220] font-mono text-[9px] font-bold">
              01:14
            </span>
            <div className="w-0.5 flex-1 bg-[#A997D8]"></div>
          </div>
          <div className="absolute top-2 bottom-2 right-[32%] flex flex-col items-center pointer-events-none">
            <span className="px-1.5 py-0.5 rounded bg-[#83B9BD] text-[#0C1220] font-mono text-[9px] font-bold">
              01:44
            </span>
            <div className="w-0.5 flex-1 bg-[#83B9BD]"></div>
          </div>
        </div>

        <div className="flex justify-between items-center mt-2.5">
          <span className="font-mono text-[10px] text-[#A1AEC2]">SELECTION: 30.0s [LOOP READY]</span>
          <button className="px-3 py-1 rounded bg-[#A997D8] text-[#0C1220] font-mono text-[10px] font-bold tracking-wider uppercase active:scale-95 transition-transform">
            + LOCK MOMENT
          </button>
        </div>
      </div>

      {/* 4. Ledger Section */}
      <div className="mt-4 flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <h3 className="font-mono text-[11px] font-bold text-[#E9EDF5] tracking-wider uppercase">
            MOMENTS ARCHIVE REEL
          </h3>
          <button
            onClick={() => setShuffleLoop(!shuffleLoop)}
            className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-[#131D30] border border-[#25344D]/50 text-[#83B9BD] text-[10px] font-mono"
          >
            <span className={`w-1.5 h-1.5 rounded-full ${shuffleLoop ? 'bg-[#83B9BD]' : 'bg-[#49454f]'}`}></span>
            SHUFFLE LOOP
          </button>
        </div>

        {/* Filter Pills */}
        <div className="flex items-center gap-1.5 py-1 overflow-x-auto no-scrollbar">
          {(['ALL', 'FAVORITES', 'SYNTHS', 'VOCAL'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-2.5 py-1 rounded font-mono text-[10px] font-semibold tracking-wider transition-colors ${
                filter === f
                  ? 'bg-[#1C2940] text-[#E9EDF5] border border-[#A997D8]/60'
                  : 'bg-[#131D30] text-[#A1AEC2] border border-[#25344D]/40'
              }`}
            >
              {f}
            </button>
          ))}
        </div>

        {/* Ledger Items */}
        <div className="flex flex-col gap-2 mt-1">
          {moments.map((m) => (
            <div
              key={m.id}
              className={`relative rounded-xl p-3 bg-[#1C2940]/80 border ${
                m.active ? 'border-[#A997D8]' : 'border-[#25344D]/50'
              } flex items-center justify-between shadow-sm`}
            >
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-10 h-10 rounded-lg bg-[#0C1220] border border-[#25344D]/60 flex items-center justify-center shrink-0">
                  <span className="font-mono text-xs font-bold text-[#A997D8]">{m.active ? '▶' : '♫'}</span>
                </div>
                <div className="flex flex-col min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="font-bold text-sm text-[#E9EDF5] truncate">{m.name}</span>
                    {m.active && (
                      <span className="px-1 py-0.2 rounded text-[8px] font-mono bg-[#A997D8]/20 text-[#A997D8] uppercase">
                        ACTIVE
                      </span>
                    )}
                  </div>
                  <span className="text-xs text-[#A1AEC2] truncate">{m.track} — {m.artist}</span>
                  <div className="flex items-center gap-2 mt-1">
                    <span className="font-mono text-[9px] text-[#83B9BD]">{m.start} - {m.end}</span>
                    <span className="font-mono text-[9px] text-[#A1AEC2]">• PLAYED {m.loopCount}x</span>
                  </div>
                </div>
              </div>

              <button className="w-8 h-8 rounded-lg bg-[#131D30] border border-[#25344D]/60 flex items-center justify-center text-[#A1AEC2] hover:text-[#E9EDF5] active:scale-95 transition-transform ml-2">
                <span className="font-mono text-xs font-bold">CUE</span>
              </button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
