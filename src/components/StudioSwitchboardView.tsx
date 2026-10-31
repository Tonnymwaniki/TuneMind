import React from 'react';
import { ThemePreferences, PALETTES } from '../theme/adaptiveTheme';

interface StudioSwitchboardViewProps {
  preferences: ThemePreferences;
  onUpdatePreferences: (update: Partial<ThemePreferences>) => void;
}

export const StudioSwitchboardView: React.FC<StudioSwitchboardViewProps> = ({
  preferences,
  onUpdatePreferences
}) => {
  const channels = [
    {
      id: 'morning' as const,
      label: 'CH 01 // DAWN',
      name: 'First Light',
      desc: '#E5B879 • Dawn Amber',
      hours: '05:00 - 11:59',
      gain: '+0.0 dB',
      gradient: 'from-[#111C2C] via-[#1A2E47] to-[#E5B879]',
      accent: '#E5B879'
    },
    {
      id: 'afternoon' as const,
      label: 'CH 02 // NOON',
      name: 'Clarity',
      desc: '#83B9BD • Studio Cyan',
      hours: '12:00 - 16:59',
      gain: '+1.2 dB',
      gradient: 'from-[#142235] via-[#1A3350] to-[#83B9BD]',
      accent: '#83B9BD'
    },
    {
      id: 'evening' as const,
      label: 'CH 03 // DUSK',
      name: 'Transition',
      desc: '#D99A69 • Terracotta',
      hours: '17:00 - 20:59',
      gain: '-0.5 dB',
      gradient: 'from-[#151A2A] via-[#2A2333] to-[#D99A69]',
      accent: '#D99A69'
    },
    {
      id: 'night' as const,
      label: 'CH 04 // NIGHT',
      name: 'Deep Focus',
      desc: '#A997D8 • Laser Lavender',
      hours: '21:00 - 04:59',
      gain: '-1.8 dB',
      gradient: 'from-[#0C1220] via-[#161F33] to-[#A997D8]',
      accent: '#A997D8'
    }
  ];

  return (
    <div className="flex flex-col w-full text-[#E9EDF5] select-none pb-20">
      {/* Hardware Rack Header */}
      <div className="py-2 flex items-center justify-between border-b border-[#1C2940]">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-[#10B981] animate-pulse"></span>
          <span className="font-mono text-[10px] uppercase tracking-widest text-[#A1AEC2]">
            HARDWARE BUS // CONSOLE CONFIG
          </span>
        </div>
        <span className="font-mono text-[10px] text-[#83B9BD] bg-[#131D30] px-2 py-0.5 rounded border border-[#25344D]/50">
          DSP LINK: SYNCED
        </span>
      </div>

      <div className="mt-3">
        <h1 className="text-base font-bold text-[#E9EDF5] tracking-tight uppercase">
          STUDIO SWITCHBOARD // ADAPTIVE ATMOSPHERE
        </h1>
        <p className="text-xs text-[#A1AEC2] mt-0.5">
          Physical channel strip routing and acoustic luminance calibration.
        </p>
      </div>

      {/* Chroma Engine 4-Channel Matrix */}
      <div className="mt-4 flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] text-[#A1AEC2] tracking-wider uppercase font-semibold">
            CHROMA ENGINE // HARDWARE PALETTE SELECTORS
          </span>
          <span className="font-mono text-[10px] text-[#A997D8]">
            {preferences.forcedPaletteId ? `${preferences.forcedPaletteId.toUpperCase()} LOCK` : 'AUTO CIRCADIAN'}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-2 mt-1">
          {channels.map((ch) => {
            const isSelected = preferences.forcedPaletteId === ch.id;
            return (
              <button
                key={ch.id}
                onClick={() =>
                  onUpdatePreferences({
                    forcedPaletteId: isSelected ? null : ch.id
                  })
                }
                className={`relative flex flex-col text-left p-3 rounded-xl transition-all ${
                  isSelected
                    ? 'bg-[#1C2940] border border-[#A997D8] shadow-[0_0_12px_rgba(169,151,216,0.25)]'
                    : 'bg-[#131D30] border border-[#25344D]/50 hover:bg-[#1C2940]/50'
                }`}
              >
                <div className="flex items-center justify-between w-full mb-1.5">
                  <span className="font-mono text-[9px] text-[#A1AEC2] uppercase font-bold">{ch.label}</span>
                  <span
                    className={`w-2 h-2 rounded-full ${
                      isSelected ? 'bg-[#A997D8] shadow-[0_0_6px_#A997D8]' : 'bg-[#25344D]'
                    }`}
                  ></span>
                </div>

                {/* Tactile Swatch Bar */}
                <div className="h-7 w-full rounded bg-[#080e1c] p-0.5 shadow-inner mb-2 flex items-center">
                  <div className={`w-full h-full rounded-sm bg-gradient-to-r ${ch.gradient}`}></div>
                </div>

                <span className="font-bold text-xs text-[#E9EDF5]">{ch.name}</span>
                <span className="font-mono text-[9px] text-[#A1AEC2] mt-0.5">{ch.hours}</span>

                <div className="mt-2 flex items-center justify-between pt-1 border-t border-[#25344D]/40 text-[9px] font-mono text-[#A1AEC2]">
                  <span>BUS GAIN</span>
                  <span className="text-[#E9EDF5] font-bold">{ch.gain}</span>
                </div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Acoustic Luminance & Sliders */}
      <div className="mt-4 p-3 bg-[#131D30] border border-[#25344D]/50 rounded-xl flex flex-col gap-3">
        <span className="font-mono text-[10px] text-[#A1AEC2] uppercase tracking-wider font-semibold">
          ACOUSTIC LUMINANCE & MODULATION
        </span>

        {/* Musical Mood Influence Slider */}
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-[#E9EDF5]">Musical Mood Influence</span>
            <span className="font-mono text-[#A997D8] font-bold">
              {Math.round(preferences.musicalInfluenceWeight * 100)}%
            </span>
          </div>
          <input
            type="range"
            min="0"
            max="1"
            step="0.05"
            value={preferences.musicalInfluenceWeight}
            onChange={(e) =>
              onUpdatePreferences({ musicalInfluenceWeight: parseFloat(e.target.value) })
            }
            className="w-full accent-[#A997D8] bg-[#0C1220] rounded h-1.5"
          />
        </div>

        {/* Energy Sensitivity Slider */}
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-[#E9EDF5]">Energy Sensitivity Depth</span>
            <span className="font-mono text-[#D5A66F] font-bold">
              {Math.round(preferences.energySensitivity * 100)}%
            </span>
          </div>
          <input
            type="range"
            min="0"
            max="1"
            step="0.05"
            value={preferences.energySensitivity}
            onChange={(e) =>
              onUpdatePreferences({ energySensitivity: parseFloat(e.target.value) })
            }
            className="w-full accent-[#D5A66F] bg-[#0C1220] rounded h-1.5"
          />
        </div>
      </div>
    </div>
  );
};
