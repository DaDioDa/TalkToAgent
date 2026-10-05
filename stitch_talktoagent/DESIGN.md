---
name: Sonic Interface
colors:
  surface: '#0c1324'
  surface-dim: '#0c1324'
  surface-bright: '#33394c'
  surface-container-lowest: '#070d1f'
  surface-container-low: '#151b2d'
  surface-container: '#191f31'
  surface-container-high: '#23293c'
  surface-container-highest: '#2e3447'
  on-surface: '#dce1fb'
  on-surface-variant: '#b9cbbd'
  inverse-surface: '#dce1fb'
  inverse-on-surface: '#2a3043'
  outline: '#849588'
  outline-variant: '#3b4a40'
  surface-tint: '#00e293'
  primary: '#cdffde'
  on-primary: '#003921'
  primary-container: '#00f5a0'
  on-primary-container: '#006b43'
  inverse-primary: '#006c44'
  secondary: '#bdf4ff'
  on-secondary: '#00363d'
  secondary-container: '#00e3fd'
  on-secondary-container: '#00616d'
  tertiary: '#fff2e2'
  on-tertiary: '#432c00'
  tertiary-container: '#ffd085'
  on-tertiary-container: '#7d5600'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#50ffaf'
  primary-fixed-dim: '#00e293'
  on-primary-fixed: '#002111'
  on-primary-fixed-variant: '#005232'
  secondary-fixed: '#9cf0ff'
  secondary-fixed-dim: '#00daf3'
  on-secondary-fixed: '#001f24'
  on-secondary-fixed-variant: '#004f58'
  tertiary-fixed: '#ffdeac'
  tertiary-fixed-dim: '#ffba38'
  on-tertiary-fixed: '#281900'
  on-tertiary-fixed-variant: '#604100'
  background: '#0c1324'
  on-background: '#dce1fb'
  surface-variant: '#2e3447'
typography:
  display-lg:
    fontFamily: Space Grotesk
    fontSize: 44px
    fontWeight: '700'
    lineHeight: 52px
  display-lg-mobile:
    fontFamily: Space Grotesk
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
  headline-lg:
    fontFamily: Space Grotesk
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  headline-sm:
    fontFamily: Space Grotesk
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Geist
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Geist
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 18px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '600'
    lineHeight: 12px
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.5rem
  gutter-desktop: 2rem
  margin: 1rem
  margin-tablet: 2rem
  margin-desktop: 3rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style
The design system powers an ultra-responsive, cross-device voice dictation and audio control bridge tailored for Android. The design language fuses modern Material 3 fluid physics with a high-contrast tactile instrumentation aesthetic. 

Targeting professional multi-device power users, developers, and mobile operators, the interface evokes hyper-clarity, kinetic precision, and zero-latency confidence. It leverages dark neutral deep-slate layers accented by dynamic neon bioluminescence to render speech status instantaneously readable from peripheral vision and glanceable distances. Every interactive affordance prioritizes single-handed bottom-weighted ergonomics, ensuring thumb-driven actuation during high-mobility use.

## Colors
The color architecture is built specifically for low-light, high-contrast visual ergonomics. 

- **Primary (`#00F5A0` - Neon Emerald):** Reserved for live voice capture, successful streaming transitions, and immediate actionable mic readiness.
- **Secondary (`#00E5FF` - Cyan Surge):** Designates paired cross-device channels, peer sync state, and outbound WebSocket stream signals.
- **Tertiary (`#FFB300` - Signal Amber):** Indicates latency buffer states, pending reconnection, speech-to-text token processing, and audio threshold warnings.
- **Error (`#FF3B5C` - Laser Rose):** Flags dropped packet streams, mic permission rejections, and offline target nodes.
- **Surfaces & Neutrals (`#020617` through `#1E293B`):** Deep Slate-950 basecanvas with Zinc-900 surface tiers, ensuring pure separation without pixel blooming on OLED panels.

## Typography
A dual-character typographic discipline balances human-readable transcription flow with technical instrumentation:

- **Headlines & Display (Space Grotesk):** Provides structured geometric authority for mode switching, device target titles, and glanceable dashboard metrics.
- **Body & Transcription (Geist):** Unobtrusive, neutral, high-legibility grotesque engineered for dynamic text streams and speech-to-text preview buffers.
- **Labels & Metrology (JetBrains Mono):** Reserved for technical status parameters, latency indicators (e.g., `42ms`), port numbers, input gain (dBFS), and responsive status pills.

## Layout & Spacing
The layout follows a bottom-weighted fluid grid strictly optimized for mobile thumb sweeps and multi-device responsive scaling:

- **Mobile (<600dp):** Single-column layout with fixed bottom 96dp safe interaction zone hosting the master voice trigger, mode dock, and audio target router. Main viewports host dynamic transcription cascades.
- **Tablet & Foldables (600dp - 1024dp):** Two-pane asymmetrical layout (40/60 split). Left column anchors persistent device nodes and input telemetry; right pane exposes live transcription, log inspector, and variable waveform canvases.
- **Desktop / Ambient Hub (>1024dp):** Standardized 12-column grid framing pinned diagnostic bars, session histories, and persistent hot-mic relays.

## Elevation & Depth
Depth avoids heavy diffuse drop shadows that wash out dark OLED screens, relying instead on tonal layering, luminescence, and micro-borders:

- **Base Layer (Level 0):** Pure Slate-950 (`#020617`), baseline noise floor.
- **Surface Containers (Level 1 - Level 2):** Elevated Zinc-900 panels (`#0F172A` to `#1E293B`) paired with a fine 1px low-contrast rim border (`rgba(255, 255, 255, 0.08)`).
- **Floating Overlays & Sheets (Level 3):** Frosted ambient backdrop blur (`backdrop-filter: blur(16px)`) with surface opacity at 85% (`rgba(15, 23, 42, 0.85)`).
- **Active State Bioluminescence:** Active voice buttons and connected audio target nodes omit conventional drop shadows in favor of localized neon directional glows (`box-shadow: 0 0 24px rgba(0, 245, 160, 0.35)`).

## Shapes
A pill-first, rounded structural envelope reinforces tactile finger landing targets. Primary buttons, contextual mode filters, and status chips strictly adhere to full capsule geometry (`border-radius: 9999px`). 

Secondary surface panels, transcription sheets, and target card surfaces leverage soft pill adaptations (`rounded-xl: 1.5rem` on mobile, scaling to `2rem` on expanded screens) to match native Android 14+ Material You contours.

## Components

### Master Dictation Trigger (Bottom Pinned)
- Floating capsule container hosting a circular 72dp high-tactile action button.
- **Idle:** `#1E293B` background, `#00F5A0` outline, subtle mic icon.
- **Active/Recording:** Fully saturated `#00F5A0` core with expanding concentric pulse animation and `#020617` inverted glyph.
- **Locked/Hold Mode:** Distinct cyan ring border (`#00E5FF`) with mechanical haptic tick feedback.

### Responsive Status Pills & Badges
- Constructed with `label-sm` JetBrains Mono, uppercase, letter-spacing +0.05em.
- Fixed 24dp height, 12dp horizontal padding.
- **Connected / Ready:** Semi-translucent `#00F5A0` background at 12% opacity, `#00F5A0` solid border, solid neon indicator dot (6dp).
- **Transmitting / Buffer:** Amber accent (`#FFB300`) with blinking dot cadence.
- **Offline / Error:** Rose outline (`#FF3B5C`) with solid background tint.

### Audio Waveform Visualizer
- Real-time 32-bar visualizer embedded within the bottom dock or active card.
- Normalized 3dp bar width, 2dp separation, full round corners.
- Amplitude-driven gradient shift moving dynamically from `#00E5FF` (ambient/low amplitude) to `#00F5A0` (peak speech levels).

### Device Target Selection Cards
- Compact horizontal cards with rapid tap targets for destination devices (e.g., "MacBook Pro - Chrome", "Windows Rig - VS Code").
- Inactive state: Deep slate surface with 1px border (`rgba(255, 255, 255, 0.06)`).
- Selected state: Cyan accent highlight with active ping latency pill badge aligned right.

### Input Fields & Transcription Surface
- Flat background (`#0B1120`), no bottom underline, encased in rounded-xl container.
- Real-time partial speech tokens display at 60% white opacity; finalized processed text snaps instantly to 100% white (`#F8FAFC`).