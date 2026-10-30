import React, { useState } from 'react';
import {
  X,
  Play,
  Snowflake,
  Activity,
  Flame,
  Clock,
  Sparkles,
  TrendingUp,
  History,
  CheckCircle2,
  ShieldAlert,
  Brain,
  Volume2
} from 'lucide-react';
import { TrackItem } from '../types';

interface SongProfileModalProps {
  track: TrackItem;
  isOpen: boolean;
  onClose: () => void;
  onQuarantine?: (track: TrackItem, days: number) => void;
  onPlay?: (track: TrackItem) => void;
}

export const getTrackEmoji = (title: string, artist: string): string => {
  const lower = `${title} ${artist}`.toLowerCase();
  if (lower.includes('espresso') || lower.includes('coffee') || lower.includes('tea')) return '☕';
  if (lower.includes('electric') || lower.includes('synth') || lower.includes('dance') || lower.includes('club')) return '⚡';
  if (lower.includes('dream') || lower.includes('night') || lower.includes('star') || lower.includes('space')) return '🌌';
  if (lower.includes('water') || lower.includes('wave') || lower.includes('ocean') || lower.includes('rain')) return '🌊';
  if (lower.includes('fire') || lower.includes('heat') || lower.includes('burn') || lower.includes('wild')) return '🔥';
  if (lower.includes('calm') || lower.includes('peace') || lower.includes('breathe')) return '🧘';
  if (lower.includes('sun') || lower.includes('summer') || lower.includes('light')) return '☀️';
  if (lower.includes('moon') || lower.includes('midnight') || lower.includes('dark')) return '🌙';
  if (lower.includes('love') || lower.includes('heart')) return '💖';
  if (lower.includes('jazz') || lower.includes('blues') || lower.includes('sax')) return '🎷';
  if (lower.includes('rock') || lower.includes('guitar')) return '🎸';

  const emojis = ['🎧', '⚡', '🌌', '🌊', '🔥', '☕', '🌙', '🎵', '🎷', '🔮', '✨', '💿'];
  const hash = Math.abs((title + artist).split('').reduce((acc, char) => acc + char.charCodeAt(0), 0));
  return emojis[hash % emojis.length];
};

export const getTrackVibe = (title: string, artist: string, satiety: number): string => {
  if (satiety >= 75) return 'Acoustic Burnout Risk • High Replay';
  if (satiety >= 55) return 'Midday High-Focus Drive';
  const lower = `${title} ${artist}`.toLowerCase();
  if (lower.includes('acoustic') || lower.includes('piano')) return 'Acoustic Palate Cleanser';
  if (lower.includes('ambient') || lower.includes('chill')) return 'Circadian Down-Regulation';
  return 'Harmonic Resonance Window';
};

export default function SongProfileModal({
  track,
  isOpen,
  onClose,
  onQuarantine,
  onPlay
}: SongProfileModalProps) {
  const [selectedDays, setSelectedDays] = useState(5);
  const [quarantinedSuccess, setQuarantinedSuccess] = useState(false);

  if (!isOpen) return null;

  const emoji = getTrackEmoji(track.title, track.artist);
  const vibe = getTrackVibe(track.title, track.artist, track.satietyScore);
  const isFatigued = track.satietyScore >= 70;
  const isModerate = track.satietyScore >= 40 && track.satietyScore < 70;

  // Deterministic Auditory Anchor Weights
  const seed = Math.abs((track.title + track.artist).split('').reduce((a, b) => a + b.charCodeAt(0), 0));
  const harmonicFamiliarity = Math.min(96, Math.max(45, (seed % 28) + 68));
  const rhythmicDrive = Math.min(94, Math.max(40, ((seed * 3) % 32) + 58));
  const melodicNovelty = Math.max(12, 100 - track.satietyScore - (seed % 14));
  const acousticDensity = Math.min(92, Math.max(50, ((seed * 7) % 25) + 65));

  // Multi-day Satiety Trend
  const c = track.satietyScore;
  const trendPoints = [
    { label: 'Day -4', score: Math.round(c * 0.35), projected: false },
    { label: 'Day -2', score: Math.round(c * 0.6), projected: false },
    { label: 'Yesterday', score: Math.round(c * 0.85), projected: false },
    { label: 'Today', score: c, projected: false },
    { label: '+2d Active', score: Math.min(99, c + 16), projected: true },
    { label: '+5d Vault', score: Math.max(18, Math.round(c * 0.38)), projected: true }
  ];

  // Fatigue History
  const fatigueHistory = [
    { session: 'Active Session', time: '14 mins ago', duration: '3m 12s', contribution: isFatigued ? '+0.84 Satiety (Loop)' : '+0.42 Satiety', isLoop: isFatigued },
    { session: 'Prior Playback', time: 'Yesterday 18:22', duration: '2m 58s', contribution: '+0.42 Satiety', isLoop: false },
    { session: 'Initial Discovery', time: '4 days ago', duration: '3m 20s', contribution: '+0.25 Satiety (Novel)', isLoop: false }
  ];

  const handleQuarantine = () => {
    if (onQuarantine) {
      onQuarantine(track, selectedDays);
    }
    setQuarantinedSuccess(true);
    setTimeout(() => {
      setQuarantinedSuccess(false);
      onClose();
    }, 1200);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-md animate-fade-in">
      <div className="bg-slate-900 border border-slate-800 rounded-3xl w-full max-w-xl max-h-[92vh] overflow-y-auto shadow-2xl text-slate-100 flex flex-col">
        {/* Header Bar */}
        <div className="sticky top-0 bg-slate-900/95 backdrop-blur border-b border-slate-800/80 p-5 flex items-center justify-between z-10">
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${track.cooldownActive ? 'bg-amber-400' : isFatigued ? 'bg-rose-500' : 'bg-emerald-400'}`} />
            <span className="text-xs font-mono font-bold tracking-wider text-cyan-400">
              {track.cooldownActive ? 'COOLDOWN VAULT' : 'SONIC PROFILE DOSSIER'}
            </span>
            <span className="text-xs text-slate-500 font-mono">• {track.sourceApp.replace('com.', '')}</span>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-slate-800 text-slate-400 hover:text-white transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-6 space-y-6">
          {/* Hero Profile Photo & Identity */}
          <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-slate-950 via-slate-900 to-slate-950 border border-slate-800 p-5">
            <div className="flex items-center gap-5">
              {/* Stylized Vinyl Disc Avatar Photo with Emoji */}
              <div className="relative group shrink-0">
                <div className={`w-24 h-24 rounded-2xl flex items-center justify-center text-4xl shadow-lg border-2 border-cyan-500/30 ${
                  track.cooldownActive
                    ? 'bg-gradient-to-tr from-amber-900 via-amber-700 to-amber-950'
                    : isFatigued
                    ? 'bg-gradient-to-tr from-rose-950 via-rose-800 to-rose-950'
                    : 'bg-gradient-to-tr from-cyan-950 via-blue-900 to-indigo-950'
                }`}>
                  <span className="transform group-hover:scale-110 transition duration-300 drop-shadow-md select-none">
                    {emoji}
                  </span>
                </div>
                <div className="absolute -bottom-1 -right-1 bg-slate-950/90 border border-slate-700 text-[10px] font-mono px-1.5 py-0.5 rounded text-cyan-300">
                  {track.bpm} BPM
                </div>
              </div>

              {/* Title, Artist, Vibe */}
              <div className="flex-1 min-w-0">
                <h3 className="text-xl font-bold text-white truncate tracking-tight">{track.title}</h3>
                <p className="text-sm font-medium text-slate-300 truncate">{track.artist}</p>
                <div className="mt-2.5 flex flex-wrap gap-2 items-center">
                  <span className="text-[11px] font-mono bg-cyan-950/70 text-cyan-300 border border-cyan-800/60 px-2.5 py-0.5 rounded-md">
                    {vibe}
                  </span>
                  <span className="text-[11px] font-mono bg-slate-800 text-slate-400 px-2 py-0.5 rounded">
                    {track.genre || 'Electronic / Pop'}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Satiety Gauge & Receptor State */}
          <div className="bg-slate-950/70 border border-slate-800/80 rounded-2xl p-4.5 space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-[10px] font-mono uppercase tracking-wider text-cyan-400 font-semibold">
                  Acoustic Satiety Saturation
                </span>
                <p className="text-sm font-bold text-white">
                  {track.cooldownActive
                    ? 'Resting in Cooldown Vault'
                    : isFatigued
                    ? 'Acoustic Burnout Risk'
                    : isModerate
                    ? 'Optimal Appreciation Window'
                    : 'Maximum Dopamine Potency'}
                </p>
              </div>
              <div className={`text-sm font-mono font-bold px-3 py-1 rounded-lg border ${
                isFatigued
                  ? 'bg-rose-950/60 text-rose-400 border-rose-800/60'
                  : 'bg-cyan-950/60 text-cyan-300 border-cyan-800/60'
              }`}>
                {track.satietyScore}% Satiety
              </div>
            </div>

            <div className="w-full bg-slate-800 h-2.5 rounded-full overflow-hidden">
              <div
                className={`h-full transition-all duration-500 rounded-full ${
                  track.cooldownActive
                    ? 'bg-amber-400'
                    : isFatigued
                    ? 'bg-gradient-to-r from-amber-500 to-rose-500'
                    : 'bg-gradient-to-r from-emerald-500 to-cyan-400'
                }`}
                style={{ width: `${Math.min(100, Math.max(10, track.satietyScore))}%` }}
              />
            </div>

            <p className="text-xs text-slate-400 leading-relaxed">
              {track.cooldownActive
                ? `Receptors recovering via exponential decay (e^-0.18t). ${Math.round((track.recoveryProgress || 0.4) * 100)}% restored.`
                : isFatigued
                ? 'Repeated replay loops have saturated auditory receptors. Recommend 3-5 days in Cooldown Vault to reset sensitivity.'
                : 'Auditory enjoyment is in prime balance. Receptive capacity is healthy (+0.42/play).'}
            </p>
          </div>

          {/* Auditory Anchor Weights Breakdown */}
          <div className="bg-slate-950/50 border border-slate-800 rounded-2xl p-4.5 space-y-3.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Brain className="w-4 h-4 text-purple-400" />
                <span className="text-xs font-mono font-bold uppercase tracking-wider text-purple-400">
                  Auditory Anchor Weights
                </span>
              </div>
              <span className="text-[11px] text-slate-500 font-mono">Perceptual Blueprint</span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="bg-slate-900/80 border border-slate-800/80 p-3 rounded-xl space-y-1.5">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-300 font-medium">Harmonic Familiarity</span>
                  <span className="font-mono text-cyan-400 font-bold">{harmonicFamiliarity}%</span>
                </div>
                <div className="w-full bg-slate-800 h-1.5 rounded-full overflow-hidden">
                  <div className="bg-cyan-400 h-full rounded-full" style={{ width: `${harmonicFamiliarity}%` }} />
                </div>
                <p className="text-[10px] text-slate-500">Chord predictability and melodic cadence</p>
              </div>

              <div className="bg-slate-900/80 border border-slate-800/80 p-3 rounded-xl space-y-1.5">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-300 font-medium">Rhythmic Entrainment</span>
                  <span className="font-mono text-purple-400 font-bold">{rhythmicDrive}%</span>
                </div>
                <div className="w-full bg-slate-800 h-1.5 rounded-full overflow-hidden">
                  <div className="bg-purple-400 h-full rounded-full" style={{ width: `${rhythmicDrive}%` }} />
                </div>
                <p className="text-[10px] text-slate-500">Tempo drive and transient impulse density</p>
              </div>

              <div className="bg-slate-900/80 border border-slate-800/80 p-3 rounded-xl space-y-1.5">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-300 font-medium">Melodic Novelty</span>
                  <span className={`font-mono font-bold ${melodicNovelty > 50 ? 'text-emerald-400' : 'text-rose-400'}`}>
                    {melodicNovelty}%
                  </span>
                </div>
                <div className="w-full bg-slate-800 h-1.5 rounded-full overflow-hidden">
                  <div className={`h-full rounded-full ${melodicNovelty > 50 ? 'bg-emerald-400' : 'bg-rose-400'}`} style={{ width: `${melodicNovelty}%` }} />
                </div>
                <p className="text-[10px] text-slate-500">Remaining cognitive surprise potential</p>
              </div>

              <div className="bg-slate-900/80 border border-slate-800/80 p-3 rounded-xl space-y-1.5">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-300 font-medium">Acoustic Density</span>
                  <span className="font-mono text-amber-400 font-bold">{acousticDensity}%</span>
                </div>
                <div className="w-full bg-slate-800 h-1.5 rounded-full overflow-hidden">
                  <div className="bg-amber-400 h-full rounded-full" style={{ width: `${acousticDensity}%` }} />
                </div>
                <p className="text-[10px] text-slate-500">Multiband spectral saturation energy</p>
              </div>
            </div>
          </div>

          {/* Satiety Trend Over Time */}
          <div className="bg-slate-950/50 border border-slate-800 rounded-2xl p-4.5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <TrendingUp className="w-4 h-4 text-cyan-400" />
                <span className="text-xs font-mono font-bold uppercase tracking-wider text-cyan-400">
                  Satiety Trend Over Time
                </span>
              </div>
              <span className="text-[11px] text-slate-500 font-mono">Historical & Projected</span>
            </div>

            <div className="flex items-end justify-between bg-slate-950 border border-slate-800/60 rounded-xl p-3 h-28 gap-2">
              {trendPoints.map((pt, i) => (
                <div key={i} className="flex-1 flex flex-col items-center justify-end h-full gap-1">
                  <span className={`text-[10px] font-mono font-bold ${
                    pt.projected ? 'text-slate-500' : pt.score >= 70 ? 'text-rose-400' : 'text-cyan-400'
                  }`}>
                    {pt.score}%
                  </span>
                  <div
                    className={`w-full rounded-t-md transition-all ${
                      pt.projected
                        ? 'bg-slate-700/60 border border-dashed border-slate-600'
                        : pt.score >= 70
                        ? 'bg-gradient-to-t from-rose-900 to-rose-500'
                        : 'bg-gradient-to-t from-cyan-900 to-cyan-400'
                    }`}
                    style={{ height: `${Math.max(12, pt.score * 0.7)}%` }}
                  />
                  <span className="text-[9px] font-mono text-slate-400 truncate w-full text-center">
                    {pt.label}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* Fatigue Exposure History */}
          <div className="bg-slate-950/50 border border-slate-800 rounded-2xl p-4.5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <History className="w-4 h-4 text-amber-400" />
                <span className="text-xs font-mono font-bold uppercase tracking-wider text-amber-400">
                  Fatigue History
                </span>
              </div>
              <span className="text-[11px] text-slate-500 font-mono">{track.playCount} Total Exposures</span>
            </div>

            <div className="space-y-2">
              {fatigueHistory.map((item, idx) => (
                <div key={idx} className="flex items-center justify-between bg-slate-900/70 border border-slate-800/60 p-2.5 rounded-lg text-xs">
                  <div>
                    <span className="font-semibold text-slate-200">{item.session}</span>
                    <span className="text-[10px] text-slate-500 ml-2 font-mono">{item.time}</span>
                  </div>
                  <div className="text-right">
                    <span className="font-mono text-cyan-300">{item.duration}</span>
                    <span className={`block text-[10px] font-mono ${item.isLoop ? 'text-rose-400 font-bold' : 'text-slate-400'}`}>
                      {item.contribution}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Quarantine Protocol Action */}
          {!track.cooldownActive ? (
            <div className="bg-gradient-to-br from-amber-950/30 to-slate-900 border border-amber-900/40 rounded-2xl p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <h4 className="text-xs font-bold font-mono text-amber-400 uppercase tracking-wide">
                    Cooldown Vault Quarantine
                  </h4>
                  <p className="text-xs text-slate-400">Select rest duration to restore receptor sensitivity.</p>
                </div>
                <Snowflake className="w-5 h-5 text-amber-400" />
              </div>

              <div className="flex gap-2">
                {[3, 5, 7].map((d) => (
                  <button
                    key={d}
                    onClick={() => setSelectedDays(d)}
                    className={`flex-1 py-2 text-xs font-mono rounded-lg border transition ${
                      selectedDays === d
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500 font-bold'
                        : 'bg-slate-900 text-slate-400 border-slate-800 hover:border-slate-700'
                    }`}
                  >
                    {d} Days Rest
                  </button>
                ))}
              </div>

              <button
                onClick={handleQuarantine}
                disabled={quarantinedSuccess}
                className="w-full py-2.5 bg-amber-600 hover:bg-amber-500 active:scale-[0.99] text-slate-950 font-bold rounded-xl text-xs flex items-center justify-center gap-2 transition"
              >
                {quarantinedSuccess ? (
                  <>
                    <CheckCircle2 className="w-4 h-4 text-slate-950" />
                    Quarantined to Vault ({selectedDays} Days)
                  </>
                ) : (
                  <>
                    <Snowflake className="w-4 h-4" />
                    Quarantine Song for {selectedDays} Days
                  </>
                )}
              </button>
            </div>
          ) : (
            <div className="bg-amber-950/40 border border-amber-700/50 rounded-2xl p-4 flex items-center justify-between">
              <div>
                <span className="text-xs font-bold font-mono text-amber-400 uppercase">Resting in Cooldown</span>
                <p className="text-xs text-slate-300">{track.cooldownDaysRemaining || 4} days remaining for receptor restoration</p>
              </div>
              <button
                onClick={() => {
                  if (onQuarantine) onQuarantine(track, 0);
                  onClose();
                }}
                className="px-3 py-1.5 bg-amber-700 hover:bg-amber-600 text-white rounded-lg text-xs font-semibold"
              >
                Release Early
              </button>
            </div>
          )}

          {/* Playback Launcher */}
          {onPlay && (
            <button
              onClick={() => {
                onPlay(track);
                onClose();
              }}
              className="w-full py-3 bg-cyan-500 hover:bg-cyan-400 active:scale-[0.99] text-slate-950 font-bold rounded-xl text-xs flex items-center justify-center gap-2 transition"
            >
              <Play className="w-4 h-4 fill-slate-950" />
              Play Track in {track.sourceApp.replace('com.', '')}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
