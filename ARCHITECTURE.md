# TuneMind — Architecture Document

**Version:** 0.3 (Phase 1 — Toolchain corrected after verified environment inspection)
**Date:** 2026-10-05
**Status:** Awaiting approval before code scaffolding begins

**Changelog from v0.2:**
- Corrected Android Studio version identification: installed version is Quail 1 (2026.1.1), not Ladybug 2024.2.1
- Recalculated entire toolchain from the correct Studio version: AGP 9.2.x, Gradle 9.4.1, compileSdk 36
- Updated build tools to 36.1.0 (installed), KGP 2.2.10+ now required by AGP 9.2
- Noted one missing prerequisite: SDK Build Tools 36.0.0 listed by AGP 9.2; 36.1.0 installed satisfies this

**Changelog from v0.1:**
- Removed premature foreground service assumption; designed around NotificationListenerService-only observation
- Introduced PlaybackObservation layer to separate what is directly observed from what is estimated
- Strengthened telemetry architecture with local-first event buffer and position estimation strategy
- Added Telemetry Validation milestone as a required gate before intelligence work begins
- Tightened Phase 1 and Phase 2 exit criteria

---

## 1. Environment Audit

### Android Studio — actual installed version

The installed version was previously misidentified. The following was determined by reading the installed files directly:

| Field | Value | Source |
|---|---|---|
| `product-info.json` → `name` | Android Studio | `%LOCALAPPDATA%\Programs\Android Studio\product-info.json` |
| `product-info.json` → `version` | `AI-261.23567.138.2611.15646644` | same file |
| `product-info.json` → `buildNumber` | `261.23567.138.2611.15646644` | same file |
| `product-info.json` → `dataDirectoryName` | **`AndroidStudio2026.1.1`** | same file — definitive version indicator |
| `build.txt` | `AI-261.23567.138.2611.15646644` | same as buildNumber |
| Bundled JBR | OpenJDK 21.0.10 (build 21.0.10+-14961533-b1163.108) | `jbr\bin\java.exe -version` |

**The `dataDirectoryName` field is the authoritative Studio version identifier.** `AndroidStudio2026.1.1` maps unambiguously to **Android Studio Quail 1 | 2026.1.1**, released June 2026. The build number prefix `261` and the four-part suffix `2611.15646644` are consistent with the Quail 1 release series.

The previous identification as "Ladybug 2024.2.1" (build prefix `242`) was incorrect.

### Confirmed installed

| Component | Version | Notes |
|---|---|---|
| **Android Studio** | **Quail 1 — 2026.1.1** (build AI-261.23567.138.2611.15646644) | Installed via JetBrains Toolbox |
| Java (system, `JAVA_HOME`) | OpenJDK 21.0.11 (Eclipse Temurin) | `JAVA_HOME` points here |
| Java (Android Studio bundled JBR) | OpenJDK 21.0.10 | Studio uses this for its own IDE processes |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | Present and licensed |
| Android Platforms installed | android-35, android-36, **android-36.1** | See toolchain decision below |
| Android Build Tools | 34.0.0, 35.0.0, **36.1.0**, 37.0.0 | |
| Android System Image | android-37 / google_apis_playstore (PS16k) | Emulator-ready |
| ADB | 1.0.41 (v37.0.0) | Current |
| Gradle (cached wrappers) | 8.13, 8.14.4, 8.14.5 | Present; project will use Gradle wrapper pinned to 9.4.1 |
| Node.js | v24.18.0 | Current LTS |
| npm | 11.16.0 | |
| TypeScript | Not installed globally | Install as project `devDependency` |
| Firebase CLI | 15.22.4 | Authenticated; no TuneMind project yet |
| Git | 2.55.0 | |

---

## 2. Android Gradle Toolchain Decision

### Installed Android Studio version

**Android Studio Quail 1 | 2026.1.1** (build `AI-261.23567.138.2611.15646644`).

According to the official Android Studio / AGP compatibility table:

> Android Studio Quail 1 (2026.1.1) supports AGP versions **7.1 – 9.2**

The ceiling is **AGP 9.2**. The floor is AGP 7.1 (irrelevant — we use the highest compatible version).

### AGP version selection

The correct AGP version for this environment is **AGP 9.2.x** — the maximum supported by the installed Studio.

From the official AGP 9.2.0 release notes:

| Dependency | Minimum version | Default version |
|---|---|---|
| Gradle | **9.4.1** | 9.4.1 |
| SDK Build Tools | **36.0.0** | 36.0.0 |
| JDK (to run Gradle/AGP) | **17** | 17 |
| Kotlin Gradle Plugin (KGP) | **2.2.10** | 2.2.10 |
| Max supported compileSdk | **API 37.0** | — |

### compileSdk selection

AGP 9.2 supports up to compileSdk **API 37.0**. However, the installed Android SDK platforms are:
- android-35 ✅
- android-36 ✅
- **android-36.1** ✅ (highest installed stable platform)
- android-37 platform: **NOT installed** (only the system image for the emulator is present — the SDK headers/platform are absent)

The `compileSdk` must match an installed SDK platform. **API 37 cannot be used as `compileSdk` until the android-37 platform is installed via SDK Manager.**

Per the official minimum-tools-per-API table:
- `compileSdk = 36` requires minimum AGP 8.9.1 — satisfied by AGP 9.2 ✅
- `compileSdk = 36.1` requires minimum AGP 8.13.0 — satisfied by AGP 9.2 ✅

Both API 36 and API 36.1 are fully supported. We use **`compileSdk = 36`** (plain android-36) as the primary target because it is the stable release API level with broad tool support. The android-36.1 platform is installed and available if the QPR2-specific APIs are needed in a future phase.

```
compileSdk = 36        (android-36 — stable, installed, fully supported by AGP 9.2)
minSdk     = 26        (Android 8.0 — see rationale below)
targetSdk  = 36
```

**`minSdk = 26` rationale:** Android 8.0 (API 26) is the correct floor because:
- `MediaSessionManager.getActiveSessions()` via `NotificationListenerService` is reliable from API 26+
- Background execution is well-defined from API 26+
- WorkManager operates correctly from API 26+
- Device coverage is approximately 95%+ of active Android devices as of 2026
- Going lower adds complexity (pre-Oreo background service handling) with no meaningful benefit for a new app

### Build Tools

AGP 9.2 requires SDK Build Tools **36.0.0 minimum**. The installed version is **36.1.0**, which is a superset of 36.0.0 and fully satisfies this requirement.

```
Build Tools = 36.1.0   (installed; satisfies AGP 9.2 minimum of 36.0.0)
```

### Gradle wrapper version

AGP 9.2 requires minimum **Gradle 9.4.1**. The previously cached Gradle versions (8.13, 8.14.4, 8.14.5) are all below this floor and cannot be used with AGP 9.2. The Gradle wrapper will download Gradle 9.4.1 on first build.

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
```

### JDK for running Gradle

AGP 9.2 requires **JDK 17 minimum** to run Gradle. The Gradle 9.x line additionally enforces that the JVM version is between 17 and 27 — JVM 28 and later are not yet supported by Gradle 9.x itself.

The system `JAVA_HOME` points to **OpenJDK 21.0.11 (Eclipse Temurin)**, which satisfies both requirements (≥17, ≤27). The Android Studio bundled JBR is OpenJDK 21.0.10. Either is acceptable.

Pin the Gradle JDK explicitly to avoid toolchain resolution issues:

```properties
# gradle.properties
org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot
```

### Kotlin Gradle Plugin

AGP 9.2 requires KGP **2.2.10 minimum**. This is a new requirement not present in the AGP 8.x series. The KGP version must be declared in the project-level `build.gradle.kts`:

```kotlin
// settings.gradle.kts or build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
```

### JVM bytecode target for Kotlin/Java sources

```kotlin
// app/build.gradle.kts
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
```

Java 17 is the correct bytecode target. AGP 9.2 documentation specifies JDK 17 as the default. With `minSdk = 26` and core library desugaring, Java 11 APIs are accessible on older devices.

### Summary

| Component | Selected version | Source / Reason |
|---|---|---|
| Android Studio | **Quail 1 — 2026.1.1** | Verified from `product-info.json` → `dataDirectoryName` |
| AGP | **9.2.x** (latest patch) | Maximum supported by Android Studio Quail 1 (2026.1.1) |
| Gradle wrapper | **9.4.1** | Required minimum and default for AGP 9.2 |
| Kotlin Gradle Plugin | **2.2.10** | Required minimum for AGP 9.2 |
| compileSdk | **36** | android-36 installed; fully supported by AGP 9.2; android-37 platform not installed |
| minSdk | **26** | Android 8.0; MediaSession APIs reliable; ~95%+ device coverage |
| targetSdk | **36** | Matches compileSdk |
| Build Tools | **36.1.0** | Installed; satisfies AGP 9.2 minimum of 36.0.0 |
| JDK (Gradle runtime) | **21** (Eclipse Temurin, pinned in gradle.properties) | Satisfies AGP 9.2/Gradle 9.4.1 floor of JDK 17; within Gradle 9.x ceiling of JDK ≤27 |
| Kotlin JVM target | **17** | Aligned with AGP 9.2 default and compileOptions |

### Missing prerequisite

| Item | Impact | Resolution |
|---|---|---|
| **android-37 platform not installed** | Cannot compile against API 37 | Not needed; targeting API 36 for Phase 1 and 2 |
| **Gradle 9.4.1 not in local cache** | Gradle wrapper will download on first sync | Normal; wrapper auto-downloads. No manual action needed |
| **KGP 2.2.10 not yet configured** | Will be declared in project `build.gradle.kts` during Phase 1 | No blocker |

### Upgrade path

| Studio | Max AGP | Max compileSdk |
|---|---|---|
| Quail 1 — 2026.1.1 (current) | **9.2** | 37 (once android-37 platform is installed) / 36.1 (installed) |
| Quail 2 — 2026.1.2 | 9.3 | 37 |
| Quail 3 — 2026.1.3 | 9.3 | 37 |
| Quail 4 — 2026.1.4 | 9.4 | 37 |

---

## 3. Repository Strategy

**Monorepo.** (Unchanged from v0.1 — approved.)

Single Git repository with three top-level directories:

```
tunemind/
├── android/       ← Kotlin Android application
├── backend/       ← Node.js TypeScript backend
└── schema/        ← Shared event schema (source of truth)
```

---

## 4. Proposed Directory Structure

```
tunemind/
│
├── android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/tunemind/app/
│   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── local/          ← Room database, EventDao, local buffer
│   │   │   │   │   │   └── remote/         ← API client (Retrofit or Ktor, chosen in Phase 2)
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── model/          ← Pure Kotlin domain models
│   │   │   │   │   │   └── event/          ← RawListeningEvent, PlaybackObservation types
│   │   │   │   │   ├── media/
│   │   │   │   │   │   ├── MediaSourceAdapter.kt        ← interface
│   │   │   │   │   │   ├── MediaSessionObserver.kt      ← MediaSessionManager wrapper
│   │   │   │   │   │   ├── PlaybackStateTracker.kt      ← position estimation logic
│   │   │   │   │   │   └── adapters/
│   │   │   │   │   │       └── GenericMediaAdapter.kt
│   │   │   │   │   ├── service/
│   │   │   │   │   │   └── TuneMindListenerService.kt   ← NotificationListenerService
│   │   │   │   │   ├── sync/
│   │   │   │   │   │   └── EventSyncWorker.kt           ← WorkManager periodic sync
│   │   │   │   │   └── ui/
│   │   │   │   │       └── debug/                       ← Debug event viewer (Phase 2)
│   │   │   │   ├── res/
│   │   │   │   └── AndroidManifest.xml
│   │   │   └── test/ / androidTest/
│   │   ├── build.gradle.kts
│   │   └── proguard-rules.pro
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── gradle/wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties      ← distributionUrl → gradle-8.9-bin.zip
│
├── backend/
│   ├── src/
│   │   ├── api/v1/
│   │   │   ├── events.ts                  ← POST /api/v1/events
│   │   │   └── profile.ts                 ← GET /api/v1/profile (Phase 3+)
│   │   ├── domain/
│   │   │   ├── events/
│   │   │   │   ├── EventValidator.ts
│   │   │   │   └── EventIngestionService.ts
│   │   │   ├── analytics/                 ← Deterministic metrics (Phase 2)
│   │   │   └── reasoning/
│   │   │       └── MusicReasoningProvider.ts  ← interface only
│   │   ├── firebase/
│   │   │   ├── FirebaseAdmin.ts
│   │   │   └── collections.ts
│   │   ├── middleware/
│   │   └── index.ts
│   ├── package.json
│   ├── tsconfig.json
│   └── .env.example
│
├── schema/
│   ├── listening-event.schema.json        ← Raw event schema (source of truth)
│   └── README.md
│
├── docs/decisions/                        ← Architecture Decision Records
├── .gitignore
├── README.md
└── ARCHITECTURE.md
```

---

## 5. Observation Architecture: NotificationListenerService Strategy

### The correct observation pattern

TuneMind is a **passive observer**, not a media playback application. It does not play audio. This distinction is critical.

The correct observation chain is:

```
User grants Notification Access in Android Settings
          │
          ▼
TuneMindListenerService  (extends NotificationListenerService)
  - Bound by Android system when notification access is granted
  - onListenerConnected() fires → safe to begin observation
  - Acts as the permission gateway for MediaSessionManager
          │
          ▼
MediaSessionManager.getActiveSessions(componentName)
  - componentName = ComponentName(context, TuneMindListenerService::class.java)
  - Returns List<MediaController> for all active media sessions
  - No MEDIA_CONTENT_CONTROL system permission required
  - Works because our NotificationListenerService is enabled
          │
          ▼
MediaSessionManager.addOnActiveSessionsChangedListener(listener, componentName)
  - Notifies us when sessions appear or disappear
  - On change: re-enumerate sessions, register/unregister MediaController.Callback
          │
          ▼
MediaController.Callback (registered per active session)
  - onMetadataChanged(metadata)   → track title, artist, album, duration
  - onPlaybackStateChanged(state) → isPlaying, position, playbackSpeed, actions
  - onSessionDestroyed()          → session ended
          │
          ▼
PlaybackStateTracker  (one per active session)
  - Records state transitions with monotonic timestamps
  - Estimates current position (see Section 6)
  - Emits PlaybackObservation records
          │
          ▼
Local Room database  (EventQueue table)
  - Stores PlaybackObservation records locally first
  - No network required for capture
          │
          ▼
EventSyncWorker  (WorkManager, periodic)
  - Reads unsynchronised records from Room
  - Batches and POSTs to backend
  - Marks records as synced on success
```

### NotificationListenerService lifecycle

`NotificationListenerService` is a system-bound service. Android binds it when:
1. The user grants notification access for the app.
2. The device boots (if already granted).

It is **not started by our code** — it is bound by the Android system. This is a key distinction from a regular service started with `startService()`.

**Background execution implications:**

- Because `NotificationListenerService` is system-bound (not app-started), the standard Android 8.0+ background service execution limits that apply to app-started services do not apply in the same way. The system itself maintains the binding.
- The service runs in the app's process but its lifecycle is controlled by the notification listener framework, not by the app.
- On Android 8.0+ (our `minSdk`), system-bound services like `NotificationListenerService` are exempt from the "app cannot start background services" restriction because it is the system that initiates the bind.
- The service's process priority is elevated by the system binding, making it less likely to be killed under memory pressure compared to an app-started background service.

**We do NOT declare a foreground service at this stage.** `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is intended for apps that themselves play media — it is not semantically appropriate for a passive observer. Whether a foreground service is needed for long-term process survival on specific OEM devices (e.g., aggressive battery optimisers on Samsung, Xiaomi, OPPO) is a question that must be answered by testing on real hardware, not by assumption.

**If testing on real devices reveals that the system kills the process** under memory pressure before events are captured reliably, we will evaluate whether a minimal foreground service with a generic `dataSync` type is appropriate. This decision will be explicitly documented as an Architecture Decision Record when that testing is complete.

### Known risk: OEM battery optimisation

Many OEM Android builds (Samsung One UI, Xiaomi MIUI, OPPO ColorOS, Huawei EMUI) implement aggressive app-kill policies that can terminate even system-bound services. This is a **real-world constraint** that cannot be fully mitigated purely at the code level — users may need to exempt TuneMind from battery optimisation. This will be addressed in the UX onboarding design (Phase 3).

---

## 6. Telemetry Architecture: PlaybackObservation Strategy

> **Current implementation note (Raw Listening Observation v1):** The local Android contract is defined by `schema/raw-listening-observation-v1.schema.json` and `PlaybackObservationCodec`. It stores a random event ID, observer connection ID, raw callback event type, wall-clock event/save times, nullable media metadata, the raw Android playback-state code, and source-reported position/speed values. Room assigns a database-local auto-increment sequence. The old examples below are architectural proposals and are superseded wherever they conflict with this implemented local contract. No backend synchronization is implemented.

### Two distinct layers

```
Layer 1: DIRECT OBSERVATION (PlaybackObservation)
  What Android APIs directly report.
  Recorded immediately.

Layer 2: RAW LISTENING EVENT (RawListeningEvent)
  Structured, timestamped, validated record.
  Derived from one or more PlaybackObservations.
  Stored locally, then synced to backend.
```

This separation is necessary because `PlaybackState.getPosition()` is a **last-reported position**, not a live continuously-updated value. Accurate computation of track position requires knowing both the last reported position and the elapsed time since it was reported, adjusted for playback speed.

### Position estimation

When `onPlaybackStateChanged()` fires, we record:

```
observation.positionMs       = playbackState.position          // last reported position
observation.elapsedRealtimeMs = SystemClock.elapsedRealtime()  // monotonic clock at observation time
observation.playbackSpeed    = playbackState.playbackSpeed     // 1.0 = normal
observation.isPlaying        = (playbackState.state == STATE_PLAYING)
```

Estimated current position at any time `T`:

```
if (isPlaying) {
    estimatedPositionMs = positionMs + ((SystemClock.elapsedRealtime() - elapsedRealtimeMs) * playbackSpeed)
} else {
    estimatedPositionMs = positionMs   // paused: position does not advance
}
```

This estimation is used only for computing derived metrics (e.g., completion percentage) in the backend `AnalyticsService`. The raw `positionMs` and `elapsedRealtimeMs` are stored as observed — we do not store only the estimated position.

### Event type inference strategy

The following event semantics are derived from state transitions, not observed directly. All derivations happen **in the backend `AnalyticsService`**, not on the Android device. The device records only what it observed.

| Derived concept | How to infer (backend, not device) |
|---|---|
| Track start | `PLAYBACK_STARTED` with a new `track.trackId` or title |
| Pause | `PLAYBACK_STATE_CHANGED` where `isPlaying` transitions from true → false |
| Resume | `PLAYBACK_STATE_CHANGED` where `isPlaying` transitions from false → true |
| Track change | `TRACK_CHANGED` event; new metadata present |
| Estimated position at change | `previousTrack.positionMs` + elapsed time calculation |
| Probable skip | `TRACK_CHANGED` where estimated completion < threshold (e.g., < 80%); labelled with confidence |
| Probable seek | `PLAYBACK_STATE_CHANGED` where `positionMs` jumps discontinuously relative to estimated position |
| Listening duration | Accumulate `isPlaying` intervals between state changes within a session |

**Important:** `probable_skip` and `probable_seek` are **derived inferences stored in a separate analytics collection** with confidence scores. They are never stored as raw observation facts.

### Local-first event flow

```
MediaController.Callback fires
        │
        ▼
PlaybackStateTracker.recordObservation()
  - Stamps monotonic clock
  - Stores PlaybackObservation in memory
        │
        ▼
EventBufferRepository.enqueue(RawListeningEvent)
  - Converts observation to RawListeningEvent
  - Inserts into Room database (EventQueue table)
  - No network call
        │
        ▼  (periodic, on schedule or connectivity change)
EventSyncWorker (WorkManager)
  - Reads unsynchronised batch from Room (max N records)
  - POST /api/v1/events  (batch array)
  - On 2xx response: mark records as synced
  - On failure: leave unsynchronised; retry on next schedule
```

The device **never sends a network request per event**. All events are batched.

WorkManager schedule: periodic, approximately every 15 minutes (minimum interval enforced by WorkManager on API 26+), or triggered on `NetworkType.CONNECTED` constraint satisfaction.

### What each PlaybackObservation records

```kotlin
data class PlaybackObservation(
    val observationId: String,         // UUID, device-generated
    val sessionId: String,             // groups observations in one listening session
    val deviceId: String,              // anonymised stable device identifier
    val mediaSource: String,           // package name of the media app
    val observedAt: Long,              // System.currentTimeMillis() — wall clock
    val elapsedRealtimeMs: Long,       // SystemClock.elapsedRealtime() — monotonic
    val eventType: ObservationEventType,
    // Track metadata at observation time — nullable; not all apps expose this
    val trackTitle: String?,
    val trackArtist: String?,
    val trackAlbum: String?,
    val trackDurationMs: Long?,        // reported track duration; 0 if unknown
    val trackId: String?,              // source-app-specific ID if available
    // Playback state at observation time
    val positionMs: Long,              // last reported position (NOT live)
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    // For TRACK_CHANGED events: snapshot of previous track state
    val previousTrackTitle: String?,
    val previousTrackPositionMs: Long?,
    val previousTrackDurationMs: Long?
)

enum class ObservationEventType {
    PLAYBACK_STATE_CHANGED,   // isPlaying changed, or position jumped
    METADATA_CHANGED,         // track metadata changed (new track)
    SESSION_CREATED,          // new MediaController observed
    SESSION_DESTROYED,        // MediaController session ended
    OBSERVATION_STARTED,      // TuneMindListenerService connected
    OBSERVATION_STOPPED       // TuneMindListenerService disconnected
}
```

Note `ObservationEventType` does not include inferred concepts like `SKIP` or `PAUSE`. Those are detected by the backend from `PLAYBACK_STATE_CHANGED` transitions.

---

## 7. Listening Event Schema

This is the backend-facing schema. It corresponds to the batch payload sent to `POST /api/v1/events`. Fields marked `server-set` are populated by the backend, not the client.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema",
  "title": "ListeningEvent",
  "description": "A raw, directly observed playback state change. No inferences stored here.",
  "type": "object",
  "required": ["eventId", "userId", "sessionId", "deviceId", "eventType", "occurredAt", "mediaSource"],
  "properties": {
    "eventId":       { "type": "string", "format": "uuid" },
    "userId":        { "type": "string" },
    "sessionId":     { "type": "string" },
    "deviceId":      { "type": "string" },
    "schemaVersion": { "type": "string", "default": "1.0" },
    "eventType": {
      "type": "string",
      "enum": [
        "PLAYBACK_STATE_CHANGED",
        "METADATA_CHANGED",
        "SESSION_CREATED",
        "SESSION_DESTROYED",
        "OBSERVATION_STARTED",
        "OBSERVATION_STOPPED"
      ]
    },
    "occurredAt":   { "type": "string", "format": "date-time", "description": "ISO 8601 UTC — device wall clock" },
    "receivedAt":   { "type": "string", "format": "date-time", "description": "Server-side receipt time. Set by backend, not client." },
    "mediaSource":  { "type": "string", "description": "Package name of the source media app. Never assumed to be a specific application." },
    "track": {
      "type": ["object", "null"],
      "description": "Track metadata at event time. Null if the media source does not expose it.",
      "properties": {
        "trackId":      { "type": ["string", "null"] },
        "title":        { "type": ["string", "null"] },
        "artist":       { "type": ["string", "null"] },
        "album":        { "type": ["string", "null"] },
        "durationMs":   { "type": ["integer", "null"], "description": "Reported duration. Null or 0 if not exposed." }
      }
    },
    "playback": {
      "type": "object",
      "description": "Playback state at moment of observation.",
      "properties": {
        "positionMs":       { "type": "integer", "description": "Last reported position in ms. NOT a live position." },
        "elapsedRealtimeMs":{ "type": "integer", "description": "Monotonic device clock at observation time. Used to estimate live position." },
        "isPlaying":        { "type": "boolean" },
        "playbackSpeed":    { "type": "number", "description": "1.0 = normal speed." }
      }
    },
    "previousTrack": {
      "type": ["object", "null"],
      "description": "For METADATA_CHANGED events: snapshot of previous track. Null otherwise.",
      "properties": {
        "title":        { "type": ["string", "null"] },
        "artist":       { "type": ["string", "null"] },
        "positionMs":   { "type": "integer", "description": "Position at which previous track was abandoned (last reported)." },
        "elapsedRealtimeMs": { "type": "integer", "description": "Monotonic clock at abandonment. Use with positionMs to estimate completion." },
        "durationMs":   { "type": ["integer", "null"] }
      }
    }
  }
}
```

**Schema design notes:**

- `elapsedRealtimeMs` is stored alongside `positionMs` for both current and previous track. This is the minimum required for the backend to estimate actual playback position using the formula in Section 6.
- No field named `wasSkipped`, `completionPercent`, `fatigueScore`, or `anchorSignal` exists in this schema.
- `receivedAt` is server-set. The backend uses the difference between `occurredAt` and `receivedAt` to detect device clock skew. Events with unreasonable skew are flagged (not silently discarded) during validation.
- `mediaSource` is always a package name string — never an assumed mapping to a human-readable app name in the schema.

---

## 8. Interface Definitions

### 8.1 `MediaSourceAdapter` (Android)

```kotlin
// media/MediaSourceAdapter.kt
interface MediaSourceAdapter {
    val targetPackage: String

    fun canHandle(packageName: String): Boolean

    /**
     * Extract a PlaybackObservation from raw MediaController callback data.
     * Returns null if insufficient data is available from this source.
     * MUST NOT perform any skip/completion inference — raw observation only.
     */
    fun extractObservation(
        eventType: ObservationEventType,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        previousSnapshot: TrackSnapshot?
    ): PlaybackObservation?
}
```

`GenericMediaAdapter` uses only standard `MediaMetadata` keys (`METADATA_KEY_TITLE`, `METADATA_KEY_ARTIST`, `METADATA_KEY_ALBUM`, `METADATA_KEY_DURATION`). App-specific subclasses can override if a source exposes non-standard data.

### 8.2 `MusicReasoningProvider` (Backend — interface only, not implemented)

```typescript
// backend/src/domain/reasoning/MusicReasoningProvider.ts

export interface UserMusicContext {
  userId: string;
  recentListeningStats: ListeningStats;         // deterministic, computed by AnalyticsService
  auditoryAnchorSignals: AuditoryAnchorSignals; // Phase 3
  fatigueSignals: TrackFatigueSignal[];         // Phase 3
}

export interface ReasoningResult {
  requestId: string;
  generatedAt: string;
  probabilisticInferences: ProbabilisticInference[];
  rawProviderResponse?: unknown;  // preserved for debugging
}

export interface ProbabilisticInference {
  type: string;
  description: string;
  confidence: number;        // 0.0 – 1.0
  evidenceSummary: string;
  isFactual: false;          // always false — inferences are never facts
}

/**
 * Plug-in interface for the external AI reasoning provider.
 * No implementation exists. The actual provider is injected at runtime in Phase 3.
 */
export interface MusicReasoningProvider {
  readonly providerId: string;
  isAvailable(): Promise<boolean>;
  reason(context: UserMusicContext): Promise<ReasoningResult>;
}
```

---

## 9. Data Architecture: Raw vs. Derived

### Android local storage (Room)

```
event_queue (Room table)
  - sequence_number: Long (database-local auto-increment ordering)
  - event_id: String (unique random event identifier)
  - payload_json: String (versioned local observation JSON)
  - synced: Boolean (legacy queue field; remains false in this milestone)
  - event_time_ms: Long (callback capture wall clock)
  - saved_at_ms: Long (repository save-attempt wall clock)
```

### Firestore collection layout (target for Phase 2)

```
/users/{userId}/
    rawEvents/{eventId}        ← ListeningEvent (immutable after write)
    sessions/{sessionId}       ← session-level aggregates (Phase 2)
    trackStats/{trackKey}      ← per-track deterministic stats (Phase 2)
    auditoryProfile/current    ← derived anchor signals (Phase 3)
    fatigueSignals/{trackKey}  ← fatigue estimates (Phase 3)
    reasoningLog/{requestId}   ← AI outputs (Phase 3)
```

**Principle:** Only directly observed data lives in `rawEvents`. Inferences — including `probable_skip`, `probable_seek`, `completionPercent` — live in their own collections with explicit confidence fields. `completionPercent` is deterministic and belongs in `trackStats`, not in `rawEvents`.

---

## 10. Android Limitations: Verified and Open Questions

### Verified from official documentation

**V1. `getActiveSessions()` permission model**
The `NotificationListenerService` component name is sufficient to call `MediaSessionManager.getActiveSessions()`. The `MEDIA_CONTENT_CONTROL` system permission is **not** required for a regular app using an enabled `NotificationListenerService`. This is confirmed by the Android SDK source and developer documentation.

**V2. `NotificationListenerService` is system-bound**
The service lifecycle is managed by the Android notification framework, not by the app. It is bound by the system when notification access is granted and the device boots. The standard Android 8.0+ restriction that prevents apps from starting background services from the background does not apply to system-bound services.

**V3. `PlaybackState.getPosition()` is last-reported, not live**
Confirmed. Accurate position must be estimated using the formula: `positionMs + (elapsedRealtime - observationElapsedRealtime) * playbackSpeed`. The `elapsedRealtimeMs` field in `PlaybackObservation` is required for this.

**V4. Notification Access cannot be granted via runtime permission dialog**
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` must be used to redirect the user to the system settings screen. The app cannot grant this permission programmatically.

### Open questions — must be answered by real device testing in Phase 2

**O1. What does each target app actually expose?**
The `MediaController.Callback` will be tested with at least two real music applications to determine what metadata fields are populated. Known unknowns:
- Spotify: historically exposes limited metadata to third-party observers in some versions
- YouTube Music: generally well-behaved; metadata typically available
- Boomplay: behaviour unknown; must be tested
- Decision: `GenericMediaAdapter` must handle all fields as nullable and never assume completeness.

**O2. Does the service process survive reliably without a foreground service?**
`NotificationListenerService` is system-bound and in theory has elevated process priority. In practice, OEM battery optimisers on Samsung, Xiaomi, OPPO, and other brands actively kill processes outside the standard Android rules. This will be assessed during Telemetry Validation (Phase 2 milestone). If events are being dropped due to process kills, the response will be documented and the options evaluated — which may include a simple foreground service using `dataSync` type, or battery optimisation exemption guidance to users.

**O3. How frequently does `onPlaybackStateChanged` fire during continuous playback?**
If a media app fires no callbacks during normal uninterrupted playback, we have no position updates. We need to understand the typical callback frequency to know whether we need to schedule our own periodic position snapshots or whether callbacks are sufficient.

**O4. Does `MediaMetadata.METADATA_KEY_DURATION` return a reliable value across all target apps?**
If duration is unreliable, completion percentage estimates will be inaccurate. We need a fallback strategy (e.g., skip confidence lowered when duration is unknown).

**O5. Are `MediaSession` tokens stable across app restarts?**
If a music app is killed and restarted, a new `MediaController` will be created. We need to confirm the session change listener fires reliably so we do not miss the new session.

---

## 11. Telemetry Validation Milestone

This milestone is required before any intelligence, analytics, or AI work begins.

**Definition of done:**

All of the following must be demonstrated on a **real physical Android device** (not emulator), with at least **two different music applications**:

1. Start a song in a supported music app.
2. TuneMind detects the active `MediaSession` and logs the track metadata (title, artist, duration if available).
3. Pause the track. TuneMind records a `PLAYBACK_STATE_CHANGED` observation with `isPlaying = false` and the correct `positionMs`.
4. Resume the track. TuneMind records `isPlaying = true` with correct `positionMs`.
5. Change to the next track. TuneMind records a `METADATA_CHANGED` event, including:
   - Previous track title
   - Previous track `positionMs` and `elapsedRealtimeMs` (for completion estimation)
   - New track title
6. All of the above observations are stored in the local Room database.
7. A debug screen in the app displays the raw captured events from Room in human-readable form — sufficient for a developer to verify correctness without connecting a debugger.
8. The WorkManager sync task runs and the events are sent to the backend.
9. The backend validates and writes the events to Firestore.
10. The same events can be inspected in the Firebase console.
11. The above is repeated with a second music application to confirm `GenericMediaAdapter` does not assume app-specific behaviour.

**This milestone is not passed until all 11 steps succeed on real hardware.**

---

## 12. Phase Plan

### Phase 1 — Foundation (current)
**Scope: Create the project structure. No business logic.**

Deliverables:
- Monorepo initialised with `android/`, `backend/`, `schema/` directories
- Android project created with AGP 8.7.x, Gradle 8.9, compileSdk 35, minSdk 26
- `TuneMindListenerService` stub (extends `NotificationListenerService`, empty)
- `MediaSourceAdapter` interface defined (Kotlin)
- `PlaybackObservation` data class defined (Kotlin)
- `GenericMediaAdapter` stub (implements `MediaSourceAdapter`, unimplemented)
- `EventQueue` Room entity and Dao defined (Kotlin, schema only — no logic)
- Node.js backend: TypeScript project structure, Express skeleton, `index.ts` starts on port 3000
- `MusicReasoningProvider` interface defined (TypeScript)
- `ListeningEvent` JSON Schema written to `schema/listening-event.schema.json`
- Gradle wrapper produces a clean build (`./gradlew assembleDebug` exits 0)
- Backend produces a clean TypeScript compile (`tsc --noEmit` exits 0)

**Exit criteria:**
- `./gradlew assembleDebug` succeeds with zero errors
- `npm run build` (or `tsc --noEmit`) in `backend/` succeeds with zero errors
- No business logic implemented
- No Firebase dependency present
- No network calls present

### Phase 2 — Observation Engine + Telemetry Validation
**Scope: Real telemetry on a real device.**

Deliverables:
- `TuneMindListenerService` fully implemented:
  - `onListenerConnected` → initialise `MediaSessionObserver`
  - Session change listener registered with `MediaSessionManager`
  - `MediaController.Callback` registered per session
  - `PlaybackStateTracker` computing position estimates
  - Events written to Room via `EventBufferRepository`
- `GenericMediaAdapter` implemented using standard `MediaMetadata` keys
- `EventSyncWorker` (WorkManager) implemented
- Debug screen: `EventListActivity` displaying raw Room contents
- Backend:
  - `POST /api/v1/events` accepting batched event arrays
  - `EventValidator` (reject malformed events; flag clock skew)
  - `EventIngestionService` writing to Firestore `rawEvents` collection
  - Firebase project created and configured
- Telemetry Validation milestone passed (all 11 items in Section 11)

**Exit criteria:**
- All 11 Telemetry Validation criteria met on real hardware
- Raw events visible in Firestore for at least two music applications
- No analytics, no AI, no fatigue signals, no anchor signals

### Phase 3 — Analytics + Intelligence Foundation
**Scope: Deterministic metrics and AI interface.**

Deliverables:
- `AnalyticsService` computing: completion percentage, play count, skip detection (with confidence), listening duration per track, replay frequency
- `MusicReasoningProvider` connected to the external reasoning API
- Firestore analytics and derived profile collections populated
- Basic user-facing UI (listening history, track stats)
- Auditory anchor and fatigue signal computation

**Exit criteria:**
- Analytics can be queried via backend API
- External AI provider responds to structured context
- Inferences are stored separately from raw observations with confidence scores

---

## 13. Technology Stack Summary

| Layer | Technology | Version |
|---|---|---|
| Android Studio | **Quail 1 — 2026.1.1** | Verified from installed `product-info.json` |
| Android language | Kotlin | KGP 2.2.10+ (required by AGP 9.2) |
| Android Gradle Plugin | **AGP 9.2.x** | Max supported by Android Studio Quail 1 (2026.1.1) |
| Gradle wrapper | **9.4.1** | Required minimum and default for AGP 9.2 |
| compileSdk | **36** | android-36 installed; fully supported by AGP 9.2 |
| minSdk | 26 | Android 8.0 |
| targetSdk | 36 | Matches compileSdk |
| Build Tools | **36.1.0** | Installed; satisfies AGP 9.2 minimum of 36.0.0 |
| JDK (Gradle runtime) | 21 (Eclipse Temurin, pinned) | Satisfies AGP 9.2 floor of JDK 17; within Gradle 9.x ceiling |
| Kotlin JVM target | 17 | Aligned with AGP 9.2 default and compileOptions |
| Local storage (Android) | Room | Latest stable |
| Background sync | WorkManager | Latest stable |
| HTTP client (Android) | TBD in Phase 2 | (Retrofit or Ktor — decided at implementation) |
| Backend runtime | Node.js | v24.18.0 |
| Backend language | TypeScript | ~5.x |
| Backend framework | Express | ~4.x |
| Database | Firebase Firestore | Admin SDK |
| AI provider | External (pluggable) | Phase 3 |

---

*This document is the single source of architectural truth for TuneMind.*
*All structural decisions must be reflected here before implementation begins.*
*Implementation details live in code; rationale and constraints live here.*
