# AGENT.md

## Modification Rules

NEVER modify `.env`, database schemas, Gradle files, or authentication/storage repositories.

Allowed scope:
- **Android Jetpack Compose UI layer**: Composable layouts, spacing, typography, copy/wording, color tokens, and theme/styling files.
- **Voice Agent & AI Reasoning**: `ClaudeMusicReasoningProvider.ts`, `AgentVoiceManager.kt`, `TuneMindExecutionEngine.kt`, and `MainActivity.kt` navigation/player-routing wiring for voice tools.
