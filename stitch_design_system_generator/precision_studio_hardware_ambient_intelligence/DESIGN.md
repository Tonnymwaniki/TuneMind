---
name: Precision Studio Hardware Ambient Intelligence
colors:
  surface: '#0d1321'
  surface-dim: '#0d1321'
  surface-bright: '#333949'
  surface-container-lowest: '#080e1c'
  surface-container-low: '#151b2a'
  surface-container: '#1a1f2e'
  surface-container-high: '#242a39'
  surface-container-highest: '#2f3544'
  on-surface: '#dde2f6'
  on-surface-variant: '#cac4d0'
  inverse-surface: '#dde2f6'
  inverse-on-surface: '#2a303f'
  outline: '#948f9a'
  outline-variant: '#49454f'
  surface-tint: '#cfbcff'
  primary: '#cfbcff'
  on-primary: '#36265f'
  primary-container: '#a997d8'
  on-primary-container: '#3d2d67'
  inverse-primary: '#655591'
  secondary: '#efbe85'
  on-secondary: '#472a00'
  secondary-container: '#644214'
  on-secondary-container: '#e0b078'
  tertiary: '#99d0d4'
  on-tertiary: '#00373a'
  tertiary-container: '#74aaae'
  on-tertiary-container: '#003e42'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#cfbcff'
  on-primary-fixed: '#200f49'
  on-primary-fixed-variant: '#4d3d77'
  secondary-fixed: '#ffddb8'
  secondary-fixed-dim: '#efbe85'
  on-secondary-fixed: '#2b1700'
  on-secondary-fixed-variant: '#614012'
  tertiary-fixed: '#b5ecf0'
  tertiary-fixed-dim: '#99d0d4'
  on-tertiary-fixed: '#002022'
  on-tertiary-fixed-variant: '#124e52'
  background: '#0d1321'
  on-background: '#dde2f6'
  surface-variant: '#2f3544'
  studio-body-dark: '#0C1220'
  recessed-console-surface: '#131D30'
  hardware-module-faceplate: '#1C2940'
  elevated-control-bezel: '#25344D'
  primary-typography: '#E9EDF5'
  secondary-labels: '#A1AEC2'
  laser-lavender: '#A997D8'
  warm-amber: '#D5A66F'
  studio-cyan: '#83B9BD'
  safety-green: '#10B981'
  warning-amber: '#F59E0B'
  clip-red: '#CF7778'
typography:
  display-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 36px
    fontWeight: '600'
    lineHeight: 44px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '500'
    lineHeight: 24px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-mono-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: 0.05em
  label-mono-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.08em
  readout-numeric:
    fontFamily: JetBrains Mono
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: 0.02em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  gutter: 1rem
  margin: 1.25rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style
This design system merges the tactile, unyielding precision of high-end analog mixing consoles and acoustic studio hardware with the fluid, context-aware responsiveness of ambient intelligence. The brand personality is authoritative, obsessively crafted, calm, and immersive. It is engineered for creators, audiophiles, and deep-focus listeners who value instrumental feedback over superficial decoration.

The visual style is a bespoke blend of **Tactile Skeuomorphism** and **Minimalist Industrialism**. It avoids generic software UI conventions—eliminating floating cards, drop shadows, and high-contrast borders—in favor of continuous dark-mode surfaces, recessed acoustic cavities, knurled metallic ridges, and razor-sharp monospaced readouts. Depth is communicated strictly through recessed planes and subtle ambient backlighting.

## Colors
The palette is rooted in dark, light-absorbing studio environments, built upon a foundation of deep anodized aluminum tones. Chromatic accents are used sparingly as operational indicators—mimicking vacuum tubes, laser alignments, and digital frequency readouts.

- **Foundations:** Use `studio-body-dark` (`#0C1220`) for the canvas base, stepping up through `recessed-console-surface` and `hardware-module-faceplate` to define depth without borders.
- **Typography & Labels:** High-contrast `primary-typography` (`#E9EDF5`) for active readouts; `secondary-labels` (`#A1AEC2`) for etched faceplate markings and secondary metadata.
- **Accents:** `laser-lavender` serves as the primary system focal point, paired with `warm-amber` for analog warmth (VU/tubes) and `studio-cyan` for digital metrics. Functional status colors (`safety-green`, `warning-amber`, `clip-red`) are strictly reserved for audio clipping, levels, and system states.

## Typography
Typography bridges clean, contemporary interface sans-serifs with high-precision monospace instrumentation. 

- Use **Plus Jakarta Sans** for all human-facing UI elements, section headings, and conversational prompts to maintain an approachable yet refined aesthetic.
- Use **JetBrains Mono** exclusively for data readouts, timestamps, frequency metrics, mixer channels, and hardware-style telemetry. All metadata labels set in monospace must use increased letter-spacing to emulate physical faceplate engravings.

## Layout & Spacing
The layout model is built on a precise, mobile-first fluid grid optimized for the 390x844px viewport. Content is organized into continuous modular zones rather than disjointed cards.

- **Canvas Margins:** Maintain strict outer margins (`1.25rem`) to frame the interface like a physical aluminum chassis.
- **Rhythm:** Spacing tokens are structured around a base-4 grid (`4px`, `8px`, `16px`, `24px`, `40px`) to align with hardware component sizing.
- **Structure:** Avoid card clutter. Group related controls inside recessed background panels (`#131D30`) separated by 1px dark interior shadow cutouts rather than solid border lines.

## Elevation & Depth
Depth is strictly achieved through an **inverted physical model** (recessed planes) inspired by mixing consoles and modular synthesizers. 

- **Surface Tiers:** Backgrounds sit at the base (`#0C1220`). Interactive modules and control wells are *recessed* into the surface using dark inner shadows and top-edge highlights (inset lighting), simulating routed aluminum cavities (`#131D30` to `#1C2940`).
- **Interactive States:** Active controls, knobs, and sliders elevate slightly out of their recesses using subtle ambient backlighting (`laser-lavender` or `warm-amber` glows at low opacity) instead of traditional drop shadows.
- **Outlines:** Zero solid borders. Use low-contrast tonal shifts and micro-grooves to separate interactive touch targets.

## Shapes
The shape language favors tight, mechanical tolerances. 

- **Corner Radius:** Use a low roundedness level (`1` / `0.25rem` base, up to `0.5rem` for major modules). Avoid pill-shaped buttons unless representing specific toggle switches or rotary tracking pills.
- **Geometry:** Rectangular components feature chamfered or tightly clipped corners, mirroring CNC-milled aluminum faceplates and physical button caps.

## Components
All components must reject standard flat mobile patterns in favor of studio-grade tactile hardware metaphors.

- **Buttons:** Rendered as matte PBT tactile switches. Default state is flush with the faceplate; active/pressed states depress downward into the module surface with an inner shadow shift and a subtle LED backlighting pulse.
- **Input Fields & Dials:** Replace standard text inputs with precision rotary scrubbers and linear sliders featuring knurled metallic tracks. Numeric inputs use monospace readouts with direct-touch incrementing.
- **Lists & Track Displays:** Avoid stacked white cards. Use continuous flat horizontal strips with subtle groove separators, displaying time signatures and frequencies in JetBrains Mono.
- **Checkboxes & Toggles:** Styled as physical toggle switches or sliding mechanical tracks with illuminated active indicators (`laser-lavender` or `safety-green`).
- **Cards (Anti-Card Clutter):** Replace cards with continuous surface sections. Use tonal background blocks (`#131D30`) with recessed inner panels to chunk content without drawing visible bounding box borders.
- **Audio Meters (Unique Component):** Real-time frequency analyzers and VU meters built with segmented LED bars utilizing `warm-amber`, `studio-cyan`, and `clip-red` thresholds.