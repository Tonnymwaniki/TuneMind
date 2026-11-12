# TuneMind — 3-Screen Personal Music & Dopamine Intelligence UI Plan

## 1. Design Philosophy & Confirmed Architecture

TuneMind is an **Autonomous Personal Voice & Music Intelligence Agent**, not a cluttered dashboard. Every screen prioritizes **visual dopamine signals, tactile one-tap playback, and zero unnecessary reading**.

### Confirmed User Decisions
1. **Navigation Pattern**: **Centered Floating Glass Pill Dock + Horizontal Swipe (`HorizontalPager`)**
   - The app opens directly onto the **Center Screen (`page = 1`: `AGENT`)**, keeping the Voice Orb immediately ready.
   - Swiping left reveals **`DOPAMINE` (`page = 0`)**; swiping right reveals **`RHYTHM` (`page = 2`)**.
   - A compact, floating translucent obsidian pill dock anchored near the bottom thumb zone (`DOPAMINE` • `AGENT` • `RHYTHM`) highlights the active screen and supports 1-tap switching.
2. **Screen 1 (Left, `page = 0`) — `DOPAMINE` (Dopamine Vault: Fresh vs. Overplayed)**:
   - Visually separates your real listening history into **Peak Dopamine / Fresh Reloads** (high reward, low ear fatigue) and **Cooling Down / Overplayed** (high satiety tracks resting until they feel fresh again).
3. **Screen 2 (Center Default, `page = 1`) — `AGENT` (The Living Voice Orb)**:
   - **100% dedicated to the breathing Chrono-Orb** for natural voice dialogue. No clutter, no multi-card feeds, no settings forms—only the top "Now Syncing" glass pill, the 300dp reactive Voice Orb + Satiety Arc, and the live conversational transcript pill above the floating dock.
4. **Screen 3 (Right, `page = 2`) — `RHYTHM` (Personal Sonic DNA & Circadian Flow)**:
   - A visual **24-Hour Circadian Sonic Ring** (*Morning Focus*, *Midday Drive*, *Evening Unwind*, *Late-Night Flow*) paired with your top power artists/songs for the active time window, active audio output & safe dB headroom gauge, and a clean 1-tap default player selector.
5. **Interactive 1-Tap Song Cards**:
   - Tapping any song card on **`DOPAMINE`** or **`RHYTHM`** immediately triggers playback (or lets you tap the mic icon on the card to ask the Voice Agent about that track and switch straight to the Orb).

---

## 2. Spatial Layout & Thumb-Zone Architecture

```
+---------------------------------------------------+  +---------------------------------------------------+  +---------------------------------------------------+
| [0] DOPAMINE VAULT                                |  | [1] AGENT ORB (DEFAULT HOME)                      |  | [2] RHYTHM DNA                                    |
|                                                   |  |                                                   |  |                                                   |
|  DOPAMINE VAULT                     [84% Fresh]   |  |      (•) NOW SYNCING • Essense — Wizkid           |  |  RHYTHM DNA                         [MIDDAY FLOW] |
|                                                   |  |                                                   |  |                                                   |
|  ╭─────────────────────────────────────────────╮  |  |                                                   |  |          ╭───────────────────────────╮            |
|  │  (  18%  )   OVERPLAYED • COOLING DOWN      │  |  |               ╭─────────────────╮                 |  |        ╱   24H CIRCADIAN SONIC RING   ╲           |
|  │   Satiety    2 tracks resting for peak hit  │  |  |             ╱   ░░░░░░░░░░░░░   ╲                 |  |       │    Morning • Midday • Night    │          |
|  ╰─────────────────────────────────────────────╯  |  |            │  ░░  CHRONO-ORB  ░░ │                |  |        ╲       [ 106 BPM • 68 dB ]    ╱           |
|                                                   |  |            │  ░░  (TAP / TALK) ░░ │                |  |          ╰───────────────────────────╯            |
|  ⚡ PEAK DOPAMINE • READY TO PLAY                 |  |             ╲   ░░░░░░░░░░░░░   ╱                 |  |                                                   |
|  ╭─────────────────────────────────────────────╮  |  |               ╰─────────────────╯                 |  |  🔥 YOUR POWER ROTATION (THIS HOUR)               |
|  │ [▶]  Anaweza Yote              [98% Fresh]  │  |  |                 TAP ORB TO TALK                   |  |  ╭─────────────────────────────────────────────╮  |
|  │      The Adventist Police...        [Ask 🎙]│  |  |                                                   |  |  │ [▶]  1. Essence — Wizkid, Tems      [Play]  │  |
|  ╰─────────────────────────────────────────────╯  |  |  ╭─────────────────────────────────────────────╮  |  |  │ [▶]  2. Anaweza Yote — Adventist..  [Play]  │  |
|  ╭─────────────────────────────────────────────╮  |  |  │ "Putting on Essence by Wizkid—want me to    │  |  |  ╰─────────────────────────────────────────────╯  |
|  │ [▶]  Essence                   [94% Fresh]  │  |  |  │  spin it now?"                              │  |  |                                                   |
|  │      Wizkid, Tems                   [Ask 🎙]│  |  |  ╰─────────────────────────────────────────────╯  |  |  🎧 OUTPUT & TARGET PLAYER                        |
|  ╰─────────────────────────────────────────────╯  |  |                                                   |  |  [ Spotify • Active ]  [ YouTube Music ]  [68 dB] |
|                                                   |  |                                                   |  |                                                   |
|       ╭───────────────────────────────────╮       |  |       ╭───────────────────────────────────╮       |  |       ╭───────────────────────────────────╮       |
|       │  [ DOPAMINE ]  ( AGENT )  RHYTHM  │       |  |       │   DOPAMINE  [ ( AGENT ) ]  RHYTHM │       |  |       │   DOPAMINE   ( AGENT ) [ RHYTHM ] │       |
|       ╰───────────────────────────────────╯       |  |       ╰───────────────────────────────────╯       |  |       ╰───────────────────────────────────╯       |
+---------------------------------------------------+  +---------------------------------------------------+  +---------------------------------------------------+
                  ◄── SWIPE LEFT                                        CENTER HOME                                        SWIPE RIGHT ──►
```

---

## 3. Visual & Color Token System (Chrono-Canvas Dark)

| Semantic Token | Hex Value | Role in TuneMind |
| :--- | :--- | :--- |
| **Obsidian Void** | `#050508` | Deep OLED background canvas across all 3 pager screens |
| **Smoked Glass Surface** | `#12141F` (`78%` alpha) | Floating cards, top status pill, and bottom glass navigation dock |
| **Glacier Cyan (Peak Dopamine)** | `#00E5FF` | Fresh/unplayed high-dopamine tracks, active listening state, safe dB |
| **Neural Violet (Rhythm DNA)** | `#7000FF` | Thinking state, circadian rhythm ring, and personal taste affinity |
| **Liquid Amber (Warm Satiety)** | `#FFB000` | Tracks approaching ear fatigue (50%–75% satiety) |
| **Crimson Pulse (Overplayed Vault)** | `#FF2A55` | Overplayed tracks locked in cooldown (>75% satiety) |
| **Pure Titanium / Muted Zinc** | `#F8FAFC` / `#94A3B8` | Crisp song titles and low-word-count visual badges |

---

## 4. Screen-by-Screen Implementation Plan

### A. Shared Navigation Shell (`MainActivity.kt` + `FloatingGlassDock.kt`)
- Wrap the root Compose layout in a `HorizontalPager(pageCount = 3, initialPage = 1)` so the app always launches directly onto the **Center Voice Orb (`page = 1`)**.
- Render a **Centered Floating Glass Pill Dock** (`height = 56.dp`, `width = 280.dp`, `RoundedCornerShape(32.dp)`) suspended `24.dp` above the bottom inset:
  - **Left Tab (`DOPAMINE`)**: Bolt/Spark icon + subtle cyan indicator dot when fresh tracks are ready.
  - **Center Tab (`AGENT`)**: Breathing mini-orb icon + glowing border when the voice agent is active (`LISTENING`, `THINKING`, or `SPEAKING`).
  - **Right Tab (`RHYTHM`)**: Sonic wave / DNA icon.

### B. Screen 1 (`page = 0`): `DopamineVaultScreen.kt`
- **Visual Satiety Header Ring**: Single compact radial ring gauge showing overall **Freshness vs. Ear Fatigue** alongside a 1-line summary (e.g., *"92% Dopamine Readiness"*).
- **Filter Toggle Pills**: Quick toggle between **`⚡ Peak Dopamine (Fresh)`** and **`⏳ Cooling Down (Overplayed)`**.
- **Interactive Song Cards (Low-Text, High-Signal)**:
  - Populated from real phone listening history (`homeState.recentObservations`, `historyState`, and `musicalMomentsState`).
  - Each card displays:
    - **Left**: Play trigger badge (`48x48dp` touch target) with circular freshness ring (`#00E5FF` for fresh, `#FFB000`/`#FF2A55` for overplayed).
    - **Center**: Track Title + Artist Name (truncated cleanly to 1 line each) + micro visual tag (`96% Fresh` or `Cooling • 4 Plays`).
    - **Right**: **`Ask 🎙`** button (`44x44dp` touch target) that scrolls smoothly to the Center Orb and prompts the agent with that track, while tapping the card body plays the song immediately via `MusicPlayerAutoLauncher`.

### C. Screen 2 (`page = 1`, Default): `PulseAgentScreen.kt`
- **Strictly 100% Orb-Centric**:
  - **Top**: Floating "Now Syncing" status pill (shows active playing track or live sync pulse).
  - **Center**: Full 300dp multi-layered **Chrono-Orb + 260° Satiety Arc**, interactive on tap (starts listening, interrupts TTS, or wakes the agent).
  - **Bottom (Above Dock)**: Clean glass transcript pill showing live recognized speech or the agent's spoken proposal during the 2-step approval dialogue.
  - Remove any secondary settings buttons or clutter from the Orb screen (target player & cloud settings move cleanly to `RHYTHM`).

### D. Screen 3 (`page = 2`): `RhythmDnaScreen.kt`
- **24-Hour Circadian Sonic Canvas**:
  - Custom Compose `Canvas` ring divided into 4 living arcs: **Morning Focus (06–12)**, **Midday Drive (12–17)**, **Evening Unwind (17–22)**, and **Late-Night Flow (22–06)**, highlighting the current active time window and target BPM.
- **Current Window Power Rotation**:
  - Top 3–5 tracks & artists you actually play during this time of day—tapping any row launches playback immediately.
- **Compact Output & Player Strip**:
  - Shows active audio route (e.g., Bluetooth / Speaker + safe dB headroom gauge from `hearingInsightsState`) and a 1-tap pill selector for your default music player (**Spotify**, **YouTube Music**, **Apple Music**, **System Default**) plus a subtle gear icon for the Cloud Brain URL dialog.

---

## 5. Verification & Delivery Workflow
1. Implement `DopamineVaultScreen.kt`, `RhythmDnaScreen.kt`, `FloatingGlassDock.kt`, update `PulseAgentScreen.kt` (making room for the floating dock and removing extra chrome), and wire the 3-page `HorizontalPager` + 1-tap playback actions in `MainActivity.kt`.
2. Sync the Web Preview mirror (`src/App.tsx`) to showcase the exact same 3-screen `DOPAMINE` • `AGENT` • `RHYTHM` architecture and floating glass pill dock.
3. Verify compilation (`compile_applet` & `lint_applet`) and push the new Android commit to `https://github.com/Tonnymwaniki/TuneMind.git` (`main`) so you can pull and install immediately.
