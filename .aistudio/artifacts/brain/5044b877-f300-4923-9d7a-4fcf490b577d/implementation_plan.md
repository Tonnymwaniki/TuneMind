# TuneMind Autonomous Agent & Dopamine Intelligence Architecture Plan

## 1. Executive Summary & Core Vision
Transform TuneMind's voice agent from a rigid single-track recommender into a **true autonomous psychoacoustic companion and app-wide controller**. By removing the hardcoded post-processing overrides in `ClaudeMusicReasoningProvider.ts`, streaming the user's complete neural/hearing telemetry from Android, and introducing an **End-to-End Agent Tool-Calling Protocol**, the agent will be able to:
1. **Converse & Advise Openly**: Discuss your music listening journey, hidden skip/replay micro-patterns, ear fatigue & decibel health, and circadian mood cycles without forcing a scripted song pitch when you just want insight or conversation.
2. **Execute Real App & OS Tools**: Reliably stop/pause, resume, skip next/previous, adjust safe volume levels, lock/restore tracks in the Cooldown Vault, switch between the 3 screens (`DOPAMINE`, `AGENT`, `RHYTHM`), and shift the live Adaptive Atmosphere theme.
3. **Smart Direct-Play vs. Consultative Recommendation**:
   - **Direct Request (`PLAY_TRACK_NOW`)**: When you explicitly name a song or artist (e.g., *"Play Wakadinali"* or *"Play Anaweza Yote"*), or confirm a pending proposal (*"Yes, play it"*), the agent plays it immediately.
   - **Consultative Suggestion (`PROPOSE_TRACK`)**: When you ask for advice, a mood boost, or a recommendation, the agent explains *why* a track fits your current dopamine/circadian profile and waits for your approval before playing.
4. **Proactive Dopamine Capsule Engine**: Automatically compiles a circadian **Dopamine Capsule** (3 compacted tracks combining newly recovered Cooldown Vault gems + peak-dopamine fresh cuts) and surfaces a glowing interactive **Capsule Pill** on the Studio Mascot Agent screen that briefs you and launches your session when tapped or asked by voice.

---

## 2. Backend Intelligence Upgrade (`backend/src/domain/reasoning/ClaudeMusicReasoningProvider.ts` & `streamingGateway.ts`)

### A. Remove Hardcoded Template Overrides
- Remove the forced post-processing block in `ClaudeMusicReasoningProvider.ts` (lines 527–557) that previously overwrote conversational responses lacking a `?` with `"How about [Song] from your library? Should I play it for you?"`.
- Maintain a multi-turn rolling conversation memory per session (`conversationHistory: Array<{ role: 'user' | 'assistant', content: string }>`) so the agent remembers what you just discussed across turns.

### B. Deep Telemetry Context Injection
Expand `StreamingAudioSessionContext` in `streamingGateway.ts` and `ClaudeMusicReasoningProvider.ts` to receive and reason over:
- **Hearing & Ear Fatigue Telemetry**: `averageDb`, `safeHeadroomDb`, `dailyExposureMinutes`, `fatigueWarning`, and `outputDeviceLabel` (e.g., Bluetooth earbuds vs. phone speaker).
- **Cooldown Vault Telemetry**: `vaultTracks` (songs currently quarantined, days locked, and recovery percentage) plus `readyToRestoreCount`.
- **Micro-Pattern & Rhythm DNA Telemetry**: Skip velocity, repeat velocity, peak musical moments (`confirmedMomentsCount`), and current `circadianDaypart` (*Morning Ignition*, *Afternoon Flow*, *Evening Decompression*, *Midnight Drift*).
- **Pre-Compacted Dopamine Capsule**: The top 3 curated tracks for the user's current circadian slot and why they were compacted.

### C. Structured Agent Tool-Calling Protocol (`agent_tool_calls`)
Upgrade Claude's structured JSON schema (while maintaining backward compatibility with `os_execution_command`) so every response returns natural spoken `tts_response_text`, a UI reasoning badge (`reasoning_mode`: `JOURNEY_ADVISOR` | `HEARING_GUARDIAN` | `DOPAMINE_CAPSULE` | `DIRECT_EXECUTION` | `SONIC_CURATOR`), and an executable `tool_call` payload:

| Tool Action (`action`) | When Triggered | Parameters |
| :--- | :--- | :--- |
| `PLAY_TRACK_NOW` | User explicitly names a song/artist to play OR approves a proposed track | `track_id`, `track_title`, `track_artist`, `deep_link_uri` |
| `PROPOSE_TRACK` | Agent suggests a song or capsule track and asks for user confirmation first | `track_id`, `track_title`, `track_artist`, `deep_link_uri` |
| `PAUSE_PLAYBACK` | User says *"stop"*, *"pause"*, *"hold on"*, *"quiet"*, *"stop the music"* | none |
| `RESUME_PLAYBACK` | User says *"resume"*, *"continue playing"*, *"unpause"* | none |
| `SKIP_NEXT` / `SKIP_PREVIOUS` | User asks for next song or previous song | none |
| `SET_VOLUME_PERCENT` | User asks to lower/raise volume or asks for safe hearing dB protection | `volume_percent` (10–100) |
| `QUARANTINE_TO_VAULT` | User asks to rest/quarantine an overplayed song (or the current track) into the Cooldown Vault | `track_id`, `track_title` |
| `RESTORE_FROM_VAULT` | User asks to unlock/restore a rested song from the Cooldown Vault | `track_id`, `track_title` |
| `SHIFT_ATMOSPHERE` | User asks to change the studio vibe/theme | `palette_id` (`GLACIER_DAWN`, `EMERALD_VELOCITY`, `SOLAR_EMBER`, `SYNAPTIC_VIOLET`, or `AUTO`) |
| `NAVIGATE_SCREEN` | User asks to open the Dopamine Vault, Rhythm DNA, or Agent screen | `screen` (`DOPAMINE`, `AGENT`, `RHYTHM`) |
| `LAUNCH_DOPAMINE_CAPSULE` | User taps the glowing Capsule card or asks *"What do you have stored for me today?"* | `capsule_tracks` (3-track array), briefs user and queues/proposes the lead capsule track |
| `ADVISE_ONLY` | Pure conversational advice on listening habits, ear fatigue, or music journey | none (speaks response with zero unwanted playback interruption) |

---

## 3. Android Execution & Media Control Fixes

### A. Rock-Solid OS Media Stop / Pause / Resume / Skip (`TuneMindListenerService.kt` & `TuneMindExecutionEngine.kt`)
- Fix `TuneMindExecutionEngine.pauseCurrentMedia()` so it executes a 3-layer media command:
  1. Calls `TuneMindListenerService.instance?.pause()` (which invokes `activeMediaController?.transportControls?.pause()` directly on the active Spotify / Apple Music / system media session).
  2. Dispatches both `KeyEvent.ACTION_DOWN` and `KeyEvent.ACTION_UP` for `KeyEvent.KEYCODE_MEDIA_PAUSE` via Android `AudioManager.dispatchMediaKeyEvent(...)` as a universal OS fallback.
  3. Immediately updates `TuneMindViewModel.nowPlaying.isPlaying = false` so the UI and Studio Mascot transition smoothly from dancing equalizer bars to calm listening/idle state.
- Add `resumeCurrentMedia()`, `skipToNextMedia()`, `skipToPreviousMedia()`, and `setSystemMediaVolume(percent: Int)` in `TuneMindExecutionEngine.kt` using `TuneMindListenerService` and `AudioManager.STREAM_MUSIC`.

### B. Full Telemetry & Tool Dispatcher in `AgentVoiceManager.kt` & `TuneMindViewModel.kt`
- Update `AgentVoiceManager.kt` WebSocket `onOpen` payload to serialize the user's real `hearingInsightsState`, `vaultState`, `musicalMomentsState`, and pre-compacted `dopamineCapsuleTracks` alongside `current_track` and `recent_tracks`.
- Add a centralized `executeAgentToolCall(...)` handler in `TuneMindViewModel.kt` that connects the WebSocket response directly to:
  - Media playback (`playLibraryTrack`, `pause`, `resume`, `next`, `previous`, `setVolume`)
  - Cooldown Vault mutations (`quarantineTrackToVault`, `restoreTrackFromVault`)
  - Adaptive Atmosphere theme overrides (`setManualMoodOverride`)
  - Pager screen navigation (`requestScreenNavigation` flow observed by `MainActivity.kt`)

---

## 4. Proactive Dopamine Capsule Card on the Studio Mascot Screen (`PulseAgentScreen.kt`)
- Build a **Dopamine Capsule Compiler** in `TuneMindViewModel.kt` that automatically compacts 3 high-impact songs for the current time of day (prioritizing recovered Cooldown Vault tracks + high-dopamine fresh tracks with low satiety).
- Render a sleek, non-intrusive glowing **Morning / Session Dopamine Capsule Pill** on `PulseAgentScreen.kt` (above the bottom dialogue console).
- When tapped, it triggers the agent to deliver a personalized spoken & visual briefing on the 3 compacted tracks and proposes/plays the lead track from the capsule.
