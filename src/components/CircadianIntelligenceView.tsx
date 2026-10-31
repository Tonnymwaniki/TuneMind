import React, { useState } from 'react';

export const CircadianIntelligenceView: React.FC = () => {
  const [selectedDay, setSelectedDay] = useState(2); // Wednesday

  const daysData = [
    { label: 'M', hours: '2.4h', fill: 45, zone: 'Optimum Focus Zone' },
    { label: 'T', hours: '4.1h', fill: 80, zone: 'Deep Flow Acoustic Lock' },
    { label: 'W', hours: '3.2h', fill: 65, zone: 'Circadian Equilibrium' },
    { label: 'T', hours: '5.0h', fill: 95, zone: 'High Dopamine Influx' },
    { label: 'F', hours: '4.8h', fill: 90, zone: 'Evening Wind-down' },
    { label: 'S', hours: '6.2h', fill: 100, zone: 'Novelty Exploration' },
    { label: 'S', hours: '3.5h', fill: 70, zone: 'Recovery Horizon' }
  ];

  return (
    <div className="flex flex-col w-full text-[#E9EDF5] select-none pb-20">
      {/* Top Hardware Telemetry Strip */}
      <div className="py-2 flex items-center justify-between border-b border-[#1C2940]">
        <div className="flex items-center gap-1.5">
          <div className="w-1.5 h-1.5 rounded-full bg-[#10B981] shadow-[0_0_6px_#10B981]"></div>
          <span className="font-mono text-[10px] text-[#A1AEC2] tracking-widest uppercase">
            CIRCADIAN // NEURO-ACOUSTIC DRIFT
          </span>
        </div>
        <div className="px-2 py-0.5 rounded bg-[#131D30] border border-[#25344D]/60">
          <span className="font-mono text-[9px] text-[#A997D8] font-bold tracking-widest uppercase">
            BUFFER // 7D SYNC
          </span>
        </div>
      </div>

      <div className="mt-3">
        <h2 className="text-base font-bold text-[#E9EDF5] uppercase tracking-tight">
          CIRCADIAN INTELLIGENCE // 7-DAY BASELINE
        </h2>
        <p className="text-xs text-[#A1AEC2] mt-0.5">
          Calibrating auditory attention drift across circadian cycles.
        </p>
      </div>

      {/* Interactive 7-Day Matrix Module (Pill Vacuum Tubes) */}
      <div className="mt-3 bg-[#131D30] border border-[#1C2940] rounded-xl p-3.5 shadow-[inset_0_2px_8px_rgba(0,0,0,0.65)] flex flex-col">
        <div className="flex items-center justify-between pb-2 border-b border-[#25344D]/40">
          <span className="font-mono text-[10px] text-[#E9EDF5] font-semibold tracking-wider uppercase">
            WEEKLY NEURO-ACOUSTIC CYCLE
          </span>
          <span className="font-mono text-[9px] text-[#D5A66F] uppercase">CURRENT CYCLE: W38</span>
        </div>

        {/* 7 Vacuum Pill Tubes Grid */}
        <div className="grid grid-cols-7 gap-1.5 items-end h-40 pt-4 pb-2">
          {daysData.map((d, i) => {
            const isSelected = i === selectedDay;
            return (
              <div
                key={i}
                onClick={() => setSelectedDay(i)}
                className="flex flex-col items-center h-full justify-end cursor-pointer group"
              >
                <span className={`font-mono text-[10px] mb-1 font-bold ${isSelected ? 'text-[#E9EDF5]' : 'text-[#A1AEC2]'}`}>
                  {d.hours}
                </span>

                {/* Vacuum Tube Glass Chassis */}
                <div
                  className={`w-full max-w-[28px] h-28 rounded-full bg-[#0C1220] p-1 flex flex-col justify-end shadow-inner border ${
                    isSelected ? 'border-[#A997D8] shadow-[0_0_8px_rgba(169,151,216,0.3)]' : 'border-[#25344D]/50'
                  }`}
                >
                  <div
                    className={`w-full rounded-full transition-all duration-300 ${
                      isSelected ? 'bg-[#A997D8]' : 'bg-[#83B9BD]/80'
                    }`}
                    style={{ height: `${d.fill}%` }}
                  />
                </div>

                <span className={`font-mono text-[10px] mt-2 font-bold ${isSelected ? 'text-[#A997D8]' : 'text-[#A1AEC2]'}`}>
                  {d.label}
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Day Detail Card */}
      <div className="mt-3 p-3 bg-[#131D30] border border-[#25344D]/50 rounded-xl flex flex-col gap-1">
        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] text-[#A997D8] font-bold uppercase">
            SELECTED DAY METRICS
          </span>
          <span className="font-mono text-[10px] text-[#E9EDF5]">{daysData[selectedDay].hours} ACTIVE</span>
        </div>
        <p className="text-xs text-[#E9EDF5] font-semibold">{daysData[selectedDay].zone}</p>
        <p className="text-[11px] text-[#A1AEC2]">
          Acoustic equilibrium maintained without fatigue alerts across this cycle.
        </p>
      </div>
    </div>
  );
};
