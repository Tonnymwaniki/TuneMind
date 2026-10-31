/**
 * TuneMind — Adaptive Personal Atmosphere Design System (Web)
 * Implements the exact 4 time-based palettes and 6 musical mood profiles.
 */

export interface PaletteColors {
  id: 'morning' | 'afternoon' | 'evening' | 'night';
  name: string;
  description: string;
  primaryBackground: string;
  secondaryBackground: string;
  cardSurface: string;
  elevatedSurface: string;
  primaryText: string;
  secondaryText: string;
  primaryAccent: string;
  secondaryAccent: string;
  highlight: string;
  border: string;
}

export type MusicalMood = 'CALM' | 'ENERGETIC' | 'REFLECTIVE' | 'UPLIFTING' | 'INTENSE' | 'UNKNOWN';

export interface MoodProfile {
  mood: MusicalMood;
  label: string;
  moodAccent: string;
  secondaryAccent: string;
  visualMovement: string;
  gradientIntensity: number;
  animationSpeedFactor: number;
}

export const PALETTES: Record<'morning' | 'afternoon' | 'evening' | 'night', PaletteColors> = {
  // A. MORNING — FIRST LIGHT (05:00 - 11:59)
  morning: {
    id: 'morning',
    name: 'First Light',
    description: 'Freshness, clarity, a new beginning and gentle energy.',
    primaryBackground: '#111C2C',
    secondaryBackground: '#1C2B40',
    cardSurface: '#263951',
    elevatedSurface: '#30445D',
    primaryText: '#F2F4F8',
    secondaryText: '#B5C1D0',
    primaryAccent: '#E5B879',
    secondaryAccent: '#9CB9D5',
    highlight: '#F3D9A6',
    border: '#3B5069',
  },

  // B. AFTERNOON — CLARITY (12:00 - 16:59)
  afternoon: {
    id: 'afternoon',
    name: 'Clarity',
    description: 'Clarity, balance, focus and visual openness.',
    primaryBackground: '#142235',
    secondaryBackground: '#1E3047',
    cardSurface: '#293D55',
    elevatedSurface: '#354B64',
    primaryText: '#F3F5F8',
    secondaryText: '#BAC6D3',
    primaryAccent: '#83B9BD',
    secondaryAccent: '#A9C7D8',
    highlight: '#D5E7E5',
    border: '#40556C',
  },

  // C. EVENING — TRANSITION (17:00 - 20:59)
  evening: {
    id: 'evening',
    name: 'Transition',
    description: 'Warmth, depth, transition and a more intimate atmosphere.',
    primaryBackground: '#151A2A',
    secondaryBackground: '#24283B',
    cardSurface: '#303348',
    elevatedSurface: '#3C4054',
    primaryText: '#F3EFF1',
    secondaryText: '#C0B9C4',
    primaryAccent: '#D99A69',
    secondaryAccent: '#B77E82',
    highlight: '#F0C49A',
    border: '#4A485A',
  },

  // D. NIGHT — DEEP FOCUS (21:00 - 04:59)
  night: {
    id: 'night',
    name: 'Deep Focus',
    description: 'Privacy, calmness, immersion and reduced visual intensity.',
    primaryBackground: '#0C1220',
    secondaryBackground: '#131D30',
    cardSurface: '#1C2940',
    elevatedSurface: '#25344D',
    primaryText: '#E9EDF5',
    secondaryText: '#A1AEC2',
    primaryAccent: '#A997D8',
    secondaryAccent: '#D5A66F',
    highlight: '#C3B6E8',
    border: '#2D3B53',
  },
};

export const MOOD_PROFILES: Record<MusicalMood, MoodProfile> = {
  CALM: {
    mood: 'CALM',
    label: 'Calm / Peaceful',
    moodAccent: '#8FAFC8',
    secondaryAccent: '#A9B8D0',
    visualMovement: 'Slow and fluid',
    gradientIntensity: 0.35,
    animationSpeedFactor: 0.8,
  },
  ENERGETIC: {
    mood: 'ENERGETIC',
    label: 'Energetic',
    moodAccent: '#E39A55',
    secondaryAccent: '#D96F62',
    visualMovement: 'More dynamic',
    gradientIntensity: 0.65,
    animationSpeedFactor: 1.3,
  },
  REFLECTIVE: {
    mood: 'REFLECTIVE',
    label: 'Reflective',
    moodAccent: '#9889C7',
    secondaryAccent: '#7889B8',
    visualMovement: 'Subtle',
    gradientIntensity: 0.35,
    animationSpeedFactor: 0.85,
  },
  UPLIFTING: {
    mood: 'UPLIFTING',
    label: 'Uplifting',
    moodAccent: '#E2BD73',
    secondaryAccent: '#82B9B1',
    visualMovement: 'Light and fluid',
    gradientIntensity: 0.55,
    animationSpeedFactor: 1.1,
  },
  INTENSE: {
    mood: 'INTENSE',
    label: 'Intense',
    moodAccent: '#CF7778',
    secondaryAccent: '#D69A69',
    visualMovement: 'More pronounced but controlled',
    gradientIntensity: 0.70,
    animationSpeedFactor: 1.25,
  },
  UNKNOWN: {
    mood: 'UNKNOWN',
    label: 'Unclassified',
    moodAccent: '#A997D8',
    secondaryAccent: '#D5A66F',
    visualMovement: 'Calm and composed',
    gradientIntensity: 0.30,
    animationSpeedFactor: 1.0,
  },
};

export type ThemeBehavior = 'ADAPTIVE' | 'TIME_BASED' | 'FIXED';
export type VisualIntensity = 'SUBTLE' | 'BALANCED' | 'EXPRESSIVE';

export interface ThemePreferences {
  behavior: ThemeBehavior;
  fixedPaletteId: 'morning' | 'afternoon' | 'evening' | 'night';
  forcedPaletteId?: 'morning' | 'afternoon' | 'evening' | 'night' | null;
  musicalInfluenceWeight: number;
  energySensitivity: number;
  enableMoodAdaptation: boolean;
  enableEnergyAdaptation: boolean;
  visualIntensity: VisualIntensity;
  reducedMotion: boolean;
  highContrast: boolean;
}

export const DEFAULT_THEME_PREFERENCES: ThemePreferences = {
  behavior: 'ADAPTIVE',
  fixedPaletteId: 'night',
  forcedPaletteId: null,
  musicalInfluenceWeight: 0.8,
  energySensitivity: 0.65,
  enableMoodAdaptation: true,
  enableEnergyAdaptation: true,
  visualIntensity: 'BALANCED',
  reducedMotion: false,
  highContrast: false,
};

export function resolveTimePeriod(now: Date = new Date()): 'morning' | 'afternoon' | 'evening' | 'night' {
  const hour = now.getHours();
  const minute = now.getMinutes();
  const total = hour * 60 + minute;

  if (total >= 5 * 60 && total <= 11 * 60 + 59) return 'morning';
  if (total >= 12 * 60 && total <= 16 * 60 + 59) return 'afternoon';
  if (total >= 17 * 60 && total <= 20 * 60 + 59) return 'evening';
  return 'night';
}

export function resolveTrackMood(title?: string, artist?: string, genre?: string): MusicalMood {
  const text = `${title || ''} ${artist || ''} ${genre || ''}`.toLowerCase();
  if (!text.trim()) return 'UNKNOWN';

  if (/calm|peace|ambient|acoustic|sleep|meditat|chill/i.test(text)) return 'CALM';
  if (/energy|dance|electronic|upbeat|workout|party/i.test(text)) return 'ENERGETIC';
  if (/reflect|nostalg|midnight|memory|piano|melancholy/i.test(text)) return 'REFLECTIVE';
  if (/uplift|joy|sunshine|happy|bright|hope|inspire/i.test(text)) return 'UPLIFTING';
  if (/metal|rock|intense|hyper|rage|heavy/i.test(text)) return 'INTENSE';

  return 'UNKNOWN';
}

export function blendHex(color1: string, color2: string, ratio: number): string {
  const clamped = Math.max(0, Math.min(1, ratio));
  const c1 = parseInt(color1.replace('#', ''), 16);
  const c2 = parseInt(color2.replace('#', ''), 16);

  const r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
  const r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;

  const r = Math.round(r1 + (r2 - r1) * clamped);
  const g = Math.round(g1 + (g2 - g1) * clamped);
  const b = Math.round(b1 + (b2 - b1) * clamped);

  return `#${((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1)}`;
}

export interface ComputedThemeState {
  timePeriod: 'morning' | 'afternoon' | 'evening' | 'night';
  palette: PaletteColors;
  mood: MusicalMood;
  primaryBackground: string;
  secondaryBackground: string;
  cardSurface: string;
  elevatedSurface: string;
  primaryText: string;
  secondaryText: string;
  primaryAccent: string;
  secondaryAccent: string;
  highlight: string;
  border: string;
  visualMovement: string;
  gradientIntensity: number;
}

export function computeTheme(
  prefs: ThemePreferences = DEFAULT_THEME_PREFERENCES,
  nowPlaying?: { title?: string; artist?: string; isPlaying?: boolean }
): ComputedThemeState {
  const currentPeriod = resolveTimePeriod();
  const paletteKey = prefs.forcedPaletteId || (prefs.behavior === 'FIXED' ? prefs.fixedPaletteId : currentPeriod);
  const basePalette = PALETTES[paletteKey] || PALETTES.night;

  const mood = (prefs.behavior === 'ADAPTIVE' && prefs.enableMoodAdaptation)
    ? resolveTrackMood(nowPlaying?.title, nowPlaying?.artist)
    : 'UNKNOWN';

  const moodProfile = MOOD_PROFILES[mood];
  const intensityMap = { SUBTLE: 0.35, BALANCED: 0.70, EXPRESSIVE: 1.0 };
  const blendFactor = (mood !== 'UNKNOWN' && prefs.enableMoodAdaptation)
    ? intensityMap[prefs.visualIntensity]
    : 0;

  const primaryAccent = blendFactor > 0
    ? blendHex(basePalette.primaryAccent, moodProfile.moodAccent, blendFactor)
    : basePalette.primaryAccent;

  const secondaryAccent = blendFactor > 0
    ? blendHex(basePalette.secondaryAccent, moodProfile.secondaryAccent, blendFactor)
    : basePalette.secondaryAccent;

  const primaryText = prefs.highContrast ? '#FFFFFF' : basePalette.primaryText;
  const secondaryText = prefs.highContrast ? '#E2E8F0' : basePalette.secondaryText;
  const border = prefs.highContrast
    ? blendHex(basePalette.border, primaryAccent, 0.45)
    : basePalette.border;

  return {
    timePeriod: currentPeriod,
    palette: basePalette,
    mood,
    primaryBackground: basePalette.primaryBackground,
    secondaryBackground: basePalette.secondaryBackground,
    cardSurface: basePalette.cardSurface,
    elevatedSurface: basePalette.elevatedSurface,
    primaryText,
    secondaryText,
    primaryAccent,
    secondaryAccent,
    highlight: basePalette.highlight,
    border,
    visualMovement: moodProfile.visualMovement,
    gradientIntensity: moodProfile.gradientIntensity,
  };
}

export function applyThemeToCssVariables(theme: ComputedThemeState, reducedMotion = false) {
  const root = document.documentElement;
  root.style.setProperty('--tm-bg', theme.primaryBackground);
  root.style.setProperty('--tm-sec-bg', theme.secondaryBackground);
  root.style.setProperty('--tm-card', theme.cardSurface);
  root.style.setProperty('--tm-elevated', theme.elevatedSurface);
  root.style.setProperty('--tm-text', theme.primaryText);
  root.style.setProperty('--tm-text-sec', theme.secondaryText);
  root.style.setProperty('--tm-accent', theme.primaryAccent);
  root.style.setProperty('--tm-accent-sec', theme.secondaryAccent);
  root.style.setProperty('--tm-highlight', theme.highlight);
  root.style.setProperty('--tm-border', theme.border);
  root.style.setProperty(
    '--tm-transition',
    reducedMotion ? 'none' : 'background-color 700ms ease, border-color 700ms ease, color 700ms ease'
  );
}
