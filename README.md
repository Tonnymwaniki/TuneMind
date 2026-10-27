# TuneMind

A personal music intelligence platform that learns how an individual listener interacts with music over time.

## Repository structure

```
tunemind/
├── android/        Android application (Kotlin)
├── backend/        Node.js + TypeScript backend
├── schema/         Shared event schema (source of truth)
└── ARCHITECTURE.md Full architecture documentation
```

## Current status

**Listening Pattern Intelligence v1 — local observation, reconstruction, memory, and deterministic pattern summaries.** The Android app derives explainable summaries on demand from retained local observations. No backend synchronization or model-based inference is implemented.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full architecture, phase plan, and development decisions.

## Toolchain

| Component | Version |
|---|---|
| Android Studio | Quail 1 — 2026.1.1 |
| AGP | 9.2.x |
| Gradle | 9.4.1 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK (Gradle) | 21 |
| Kotlin JVM target | 17 |
| Node.js | 24.x LTS |
| TypeScript | 5.x |

## Building

### Android

```bash
cd android
./gradlew assembleDebug
```

### Backend

```bash
cd backend
npm install
npm run build
```

## Local playback data and privacy

Playback observations are stored only in the Android app's private Room database. The development build retains at most the 500 most recently inserted observations, and the debug screen can clear them. The local payload is defined by [`raw-listening-observation-v1.schema.json`](./schema/raw-listening-observation-v1.schema.json); the existing `listening-event.schema.json` describes a separate future server-ingestion payload and is not used for Room serialization. Local records include random event/session IDs, event and save times, event type, media package, available track metadata, raw Android playback state, and available position/speed values. No user or device identity is created.

The database is **not encrypted by TuneMind**. Android app sandboxing and device-level encryption are separate protections and do not make this an encrypted or zero-knowledge vault. Android app backup is disabled in the manifest for this milestone. Do not store sensitive production listening history until a deliberate at-rest protection, retention, deletion, and backup policy has been designed.

Media explicitly marked with Android's `android.media.metadata.ADVERTISEMENT` flag is omitted from local observations. TuneMind does not infer advertisements from title or artist text. A media app that does not expose this marker may still have ads recorded as ordinary media; validate this behavior against each target app/version before relying on ad exclusion.

The Room sequence number orders accepted inserts within this local database only. It is not a global timestamp or a guarantee of the order in which Android callbacks occurred. `eventTimeMs` is TuneMind's wall-clock callback capture time; `savedAtMs` is sampled by the local repository before insertion. Android's source-reported position-update time uses the elapsed-realtime clock and is kept as a separate field. Schema version changes require an explicit decoder path; version 1 payloads are preserved, and unknown versions are rejected by the codec.

### Tentative playback reconstruction

The debug screen derives playback episodes and candidate track appearances from the retained raw observations. It does not alter or persist raw records and is not evidence that a person heard or attended to audio. A playback episode is scoped to one source package and one observer-service connection; the observer-session ID does not identify a media controller. Unknown source packages are grouped together because the current raw contract has no controller identity.

The deterministic v1 engine sorts by wall-clock `eventTimeMs`, breaking ties by event ID and event type. This provides repeatable output, not recovery of Android callback order; conflicting duplicate IDs or a regression in source-reported elapsed position time are flagged as ambiguous. The engine counts active duration only across `STATE_PLAYING` spans bounded by another observation with an available state within 2 minutes. It closes an episode at a gap over 5 minutes, marking the gap censored; a shorter over-limit span contributes no duration and is marked as a gap. Pauses do not count as active time. Open spans are never extended to the current time.

Track IDs are the strongest available grouping key. When absent, title and artist (and album when available) form a weaker metadata fingerprint; incomplete metadata remains insufficient identity. A changed identity is a candidate appearance only, not a confirmed track transition or skip. Because only 500 raw records are retained, reconstruction conservatively marks boundaries as retention-truncated when the full retained window is supplied. Session and track boundaries, durations, source attribution, and metadata remain estimates bounded by the callbacks TuneMind captured.

### Listening Memory v1

Listening Memory is derived on demand from decoded Room observations and the existing reconstructed track appearances. It does not persist a second copy or alter the raw records. Provider track IDs are matched only within a known source package; without one, the appearance stays unidentified. The conservative metadata fallback requires a known source package plus title, artist, and album, normalized for case and surrounding whitespace. Same titles alone, different albums, missing fields, and conflicting metadata are not silently merged. Artist summaries group available labels case-insensitively and count distinct identified track entries and distinct reconstructed appearance IDs; artist labels are not verified artist entities.

Recurrence means at least two distinct reconstructed appearance records for one identity. Multiple raw callbacks supporting one appearance count once. These are observed/reconstructed appearances, not complete plays, enjoyment, preferences, skips, or proof the user heard the track. First/last values are appearance observation bounds, not exact track start/end times. Callback gaps, unavailable metadata, wall-clock ordering, unknown source apps, and the 500-observation retention cap can leave memory incomplete. Processing remains on-device and local; no network or model-provider integration is used.

### Listening Pattern Intelligence v1

Pattern analysis runs on demand from retained Room rows through the existing session reconstructor and Listening Memory builder, then a pure Kotlin analyzer. Raw observations remain unchanged; derived results are not persisted. Database reads and analysis run outside the UI thread. Processing is local-only.

The analysis window is the minimum and maximum valid raw `eventTimeMs` in the retained input, displayed in the explicitly supplied device timezone. It describes the observed callback window, not the listener's full history. Each unique raw event ID with a valid timestamp contributes one **observed media-session event** to its local date and hour. Calendar conversion uses `java.time.ZoneId` rules: a skipped daylight-saving hour has no bucket, while a repeated local hour is combined into the same hour-of-day bucket.

Reconstructed episode counts are grouped by each supported episode's first observed timestamp date. They count reconstructed playback episodes, not plays or human listening sessions. Active duration reuses the reconstruction engine's callback-bounded `estimatedActivePlaybackMs`; it is not derived from track duration or wall-clock episode length. The aggregate is shown only for same-local-date, valid episode bounds with no overlapping episode observation bounds. Cross-day estimates are not apportioned, invalid or unbounded estimates are excluded, and partial totals are labeled. This is conservative because the existing episode model does not expose each bounded active subinterval for calendar apportionment.

Track identity and distinct appearance grouping come only from Listening Memory v1. Raw callback counts do not inflate reconstructed appearance counts. Recurrence and inter-appearance gaps are shown only for supported identities with unambiguous reconstruction evidence. Gaps use successive `firstObservedAtMs` values from reconstructed appearances; these are first observed metadata bounds, not exact track starts, and the gaps do not represent intervals between complete plays. Equal timestamps produce an unavailable gap rather than a fabricated interval.

Coverage is always marked unknown or potentially incomplete because media-session callbacks do not provide a reliable observation-coverage denominator. The 500-row retention cap may remove older evidence, missing callbacks or metadata can hide activity, and wall-clock timestamps cannot reconstruct callback arrival order. Comparable-period changes are withheld because differing callback coverage could explain any apparent difference. The app makes no claims about preference, attention, enjoyment, intent, fatigue, mood, complete plays, or biological effects.

Run analyzer, reconstruction, and memory unit tests from `android` with `./gradlew :app:testDebugUnitTest` (Windows PowerShell: `.\gradlew.bat :app:testDebugUnitTest`).

