[![Latest build](https://github.com/element-hq/element-x-android/actions/workflows/build.yml/badge.svg?query=branch%3Adevelop)](https://github.com/element-hq/element-x-android/actions/workflows/build.yml?query=branch%3Adevelop)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=element-x-android&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=element-x-android)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=element-x-android&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=element-x-android)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=element-x-android&metric=bugs)](https://sonarcloud.io/summary/new_code?id=element-x-android)
[![codecov](https://codecov.io/github/element-hq/element-x-android/branch/develop/graph/badge.svg?token=ecwvia7amV)](https://codecov.io/github/element-hq/element-x-android)
[![Element X Android Matrix room #element-x-android:matrix.org](https://img.shields.io/matrix/element-x-android:matrix.org.svg?label=%23element-x-android:matrix.org&logo=matrix&server_fqdn=matrix.org)](https://matrix.to/#/#element-x-android:matrix.org)
[![Localazy](https://img.shields.io/endpoint?url=https%3A%2F%2Fconnect.localazy.com%2Fstatus%2Felement%2Fdata%3Fcontent%3Dall%26title%3Dlocalazy%26logo%3Dtrue)](https://localazy.com/p/element)

# Fionaro Chat

**Fionaro Chat** is a fully self-hosted, rebranded fork of [Element X Android](https://github.com/element-hq/element-x-android) — a Matrix client built with the [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk), Jetpack Compose, and Appyx.

This fork connects exclusively to the Fionaro infrastructure and removes all dependencies-free, Firebase/Google Play Services-free (fdroid flavor).

## What's Changed

### Infrastructure & Endpoints
All Matrix and related endpoints point to the Fionaro infrastructure:
- Homeserver: `https://matrix.fionaro.pw`
- Element Call: `https://call.fionaro.pw`
- Push gateway: `https://push.fionaro.pw`
- UnifiedPush: `https://push.fionaro.pw` (ntfy self-hosted)
- Auth: `https://auth.fionaro.pw`
- LiveKit: `https://livekit.fionaro.pw`
- TURN: `turn.fionaro.pw`

### Flavor & Distribution
- **fdroid** flavor only (no Google Play / Firebase dependencies)
- UnifiedPush via self-hosted ntfy (no FCM / Google Play Services)
- No analytics, telemetry, or crash reporting to external services

### Build & Signing
- Production keystore: `~/fionaro-chat-release.jks` (passwords in `gradle.properties` or environment)
- Build command:
  ```bash
  FIONARO_KEYSTORE_PATH=... FIONARO_KEYSTORE_PASSWORD=... FIONARO_KEY_PASSWORD=... \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleFdroidRelease
  ```

### Group Call Fix (v0.2.5+)
**Problem:** In Element Call v0.21.0, group calls create a new Matrix room per call. Each participant created their own room, so users never saw each other.

**Root cause:** Element Call widget loads the home page (`/`) instead of the call view (`/room/`). The home page shows a "Start call" form that calls `fet()` → `createRoom()` on submit.

**Fix (3 parts):**
1. **URL path** — widget URL uses `/room/` instead of `/` so React Router matches the call view (`*`), not the home page (`/`).
2. **skipLobby=true** — bypasses the lobby form entirely, jumping straight to the call view.
3. **Shared original room** — both participants use the *original Matrix room* as the call room. No pre-creation, no duplicate rooms, no invite needed — `m.call.member` events are written to the same room everyone already shares.

### Element Call Widget Flow (v0.21.0)
| Step | Before (broken) | After (fixed) |
|------|-----------------|---------------|
| URL path | `/` (home page) | `/room/` (call view) |
| Entry point | Home page → form → `fet()` | Direct → call view |
| Call room | Pre-created per user | Original room (shared) |
| `skipLobby` | `false` | `true` |
| `fet()` call | Always triggered | Never reached |
| `m.call.member` | Written to private rooms | Written to shared room |

### Push & Notifications
- UnifiedPush via self-hosted ntfy (`push.fionaro.pw`)
- No FCM, no Google Play Services
- Works on F-Droid builds and GrapheneOS

### Registration
- Web-based registration at `https://matrix.fionaro.pw/register`
- Uses `m.login.dummy` (no email/password flow required)
- Served from `register.html` on the homeserver

### Security & Privacy
- No telemetry, analytics, or crash reporting to third parties
- No Firebase Crashlytics, no Google Analytics
- No Sentry, no PostHog
- All traffic routed through Fionaro infrastructure only

### Removed / Disabled
- Firebase / Google Play Services dependencies
- Play Store flavor
- Google Play Services flavor
- Firebase Crashlytics
- Google Analytics / Firebase Analytics
- Sentry / PostHog integration
- Element branding strings and resources
- All Element-specific URLs and endpoints

## Build Instructions

### Prerequisites
- Android SDK (API 34)
- Java 21 (OpenJDK)
- `fionaro-chat-release.jks` keystore file
- `gradle.properties` with:
  ```
  FIONARO_KEYSTORE_PATH=/path/to/fionaro-chat-release.jks
  FIONARO_KEYSTORE_PASSWORD=...
  FIONARO_KEY_PASSWORD=...
  ```

### Build
```bash
# On build machine (archD)
cd element-x-android
FIONARO_KEYSTORE_PATH=~/fionaro-chat-release.jks \
FIONARO_KEYSTORE_PASSWORD=... \
FIONARO_KEY_PASSWORD=... \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
./gradlew assembleFdroidRelease
```

### Output
- `app/build/outputs/apk/fdroid/release/app-fdroid-arm64-v8a-release.apk`
- `app/build/outputs/apk/fdroid/release/app-fdroid-universal-release.apk`

## Security Notes

> **This fork is NOT affiliated with Element or the Element X project.**  
> It is a completely independent, self-hosted deployment of the Element X codebase.

### What is NOT in this fork
- No upstream Element keypairs, certificates, or credentials
- No connection to Element's infrastructure
- No Element branding or trademarked assets

### Operational Security
- Keystore passwords stored in `gradle.properties` (gitignored) or injected via CI secrets
- Build machine (archD) is air-gapped from internet
- Production keystore backed up offline only
- No CI/CD pipeline publishes to external stores

### Audit Surface
- All Matrix traffic goes through `matrix.fionaro.pw` (Synapse)
- All calls go through `call.fionaro.pw` (Element Call) + `livekit.fionaro.pw` (LiveKit)
- Push notifications via `push.fionaro.pw` (ntfy)
- TURN via `turn.fionaro.pw` (coturn)

## License

This fork inherits the dual license of Element X Android:
- GNU AGPL v3 (or later) — see [LICENSE](LICENSE)
- Element Commercial License (available from Element)

> **This fork does NOT grant any rights to Element branding, trademarks, or infrastructure.**  
> All Element branding has been removed and replaced with Fionaro branding.

---

*Forked from [element-hq/element-x-android](https://github.com/element-hq/element-x-android) at tag `v26.06.4`.*
