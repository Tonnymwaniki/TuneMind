import React, { useState } from 'react';

export const CircadianIntelligenceView: React.FC = () => {
  const [selectedDay, setSelectedDay] = useState(2); // Wednesday

  const daysData = [
    { label: 'MON', hours: '2.4h', fill: 48, zone: 'Harmonic Awakening • Balanced mid-tempo groove' },
    { label: 'TUE', hours: '4.1h', fill: 82, zone: 'Deep Acoustic Flow • High linguistic tolerance' },
    { label: 'WED', hours: '3.2h', fill: 64, zone: 'Midweek Equilibrium • Dynamic structural lock' },
    { label: 'THU', hours: '5.0h', fill: 95, zone: 'Satiety Balance • Safe vocal repetition' },
    { label: 'FRI', hours: '4.8h', fill: 90, zone: 'Evening Novelty • High receptivity window' },
    { label: 'SAT', hours: '6.2h', fill: 100, zone: 'Extended Immersion • Ambient soundscape rest' },
    { label: 'SUN', hours: '3.5h', fill: 70, zone: 'Restorative Cooldown • Low treble ambient recovery' }
  ];

  return (
    <div className="flex flex-col w-full text-[#F5E5D0] select-none pb-6">
      {/* Top Hardware Telemetry Strip */}
      <div className="py-2.5 px-3 rounded-xl bg-[#713019] flex items-center justify-between border border-[#985B3C]/50">
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full bg-[#8FAF83] shadow-[0_0_8px_#8FAF83]"></div>
          <span className="font-mono text-[10px] text-[#CFAE91] tracking-widest uppercase font-bold">
            CONTINUOUS INTELLIGENCE // REAL-TIME
          </span>
        </div>
        <div className="px-2 py-0.5 rounded bg-[#4F2012] border border-[#C2763F]/40">
          <span className="font-mono text-[9px] text-[#C68A46] font-bold tracking-widest uppercase">
            EVOLVING PERMANENTLY
          </span>
        </div>
      </div>

      <div className="mt-3.5 px-1">
        <h2 className="text-sm font-bold text-[#F5E5D0] uppercase tracking-wide font-mono">
          CONTINUOUS ACOUSTIC INTELLIGENCE // ADAPTIVE MATRIX
        </h2>
        <p className="text-xs text-[#CFAE91] mt-1 leading-relaxed">
          Unlocked and actively learning your auditory fingerprint. Intelligence refines continuously across all playback cycles.
        </p>
      </div>

      {/* Interactive Continuous Intelligence Matrix Module (Pill Vacuum Tubes) */}
      <div className="mt-3.5 bg-[#78351E] border border-[#985B3C] rounded-2xl p-4 shadow-xl flex flex-col gap-3">
        <div className="flex items-center justify-between pb-2 border-b border-[#985B3C]/40">
          <span className="font-mono text-[10px] text-[#F5E5D0] font-bold tracking-wider uppercase flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#C68A46]"></span>
            ROLLING AUDITORY CYCLE MATRIX
          </span>
          <span className="font-mono text-[9px] text-[#8FAF83] font-bold uppercase px-2 py-0.5 rounded bg-[#4F2012] border border-[#8FAF83]/30">
            ALL ACTIVE // UNLOCKED
          </span>
        </div>

        {/* 7 Vacuum Pill Tubes Grid inside Recessed Obsidian Well */}
        <div className="bg-[#4F2012] rounded-xl p-3 border border-[#985B3C]/30 grid grid-cols-7 gap-2 items-end h-44">
          {daysData.map((d, i) => {
            const isSelected = i === selectedDay;
            return (
              <div
                key={i}
                onClick={() => setSelectedDay(i)}
                className="flex flex-col items-center h-full justify-end cursor-pointer group"
              >
                <span className={`font-mono text-[9px] mb-1.5 font-bold transition-colors ${isSelected ? 'text-[#E9A84F]' : 'text-[#B99578]'}`}>
                  {d.hours}
                </span>

                {/* Vacuum Tube Glass Chassis */}
                <div
                  className={`w-full max-w-[26px] h-28 rounded-full bg-[#713019] p-1 flex flex-col justify-end border transition-all duration-200 ${
                    isSelected ? 'border-[#C68A46] shadow-[0_0_12px_rgba(198,138,70,0.5)] ring-1 ring-[#C68A46]' : 'border-[#985B3C]/50'
                  }`}
                >
                  <div
                    className={`w-full rounded-full transition-all duration-300 ${
                      isSelected
                        ? 'bg-gradient-to-t from-[#C68A46] via-[#D9A054] to-[#F3C77D]'
                        : 'bg-gradient-to-t from-[#C2763F] to-[#C68A46]'
                    }`}
                    style={{ height: `${d.fill}%` }}
                  />
                </div>

                <span className={`font-mono text-[10px] mt-2 font-bold transition-colors ${isSelected ? 'text-[#C68A46]' : 'text-[#CFAE91]'}`}>
                  {d.label}
                </span>
                <div className={`w-1 h-1 rounded-full mt-1 ${isSelected ? 'bg-[#C68A46]' : 'bg-transparent'}`} />
              </div>
            );
          })}
        </div>
      </div>

      {/* Day Detail Card */}
      <div className="mt-3.5 p-4 bg-[#854126] border border-[#985B3C] rounded-2xl flex flex-col gap-1.5 shadow-md">
        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] text-[#D9A054] font-bold tracking-wider uppercase">
            {daysData[selectedDay].label} ADAPTIVE PROFILE
          </span>
          <span className="font-mono text-[10px] text-[#F5E5D0] font-bold px-2 py-0.5 rounded bg-[#4F2012]">
            {daysData[selectedDay].hours} ACTIVE
          </span>
        </div>
        <p className="text-xs text-[#F5E5D0] font-bold mt-0.5">{daysData[selectedDay].zone}</p>
        <p className="text-[11px] text-[#CFAE91] leading-relaxed">
          Acoustic equilibrium maintained without fatigue alerts. Neural auditory models continually calibrate against your skip dynamics and harmonic preference.
        </p>
      </div>
    </div>
  );
};
