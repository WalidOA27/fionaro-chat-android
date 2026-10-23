# Project Reference — Fionaro Chat Android

This document is the maintainer reference for the Fionaro Chat Android fork: what the project is,
how it is laid out, how to build and test it, what the fork changes, and how to keep it in sync
with upstream. It contains **no secrets** — only the names of environment variables that carry
signing material at build time.

## 1. Overview

| Item | Value |
| :--- | :--- |
| Product | Fionaro Chat (`pw.fionaro.chat`) |
| Upstream | [element-hq/element-x-android](https://github.com/element-hq/element-x-android), base tag `v26.06.4` |
| Fork version | `v1.1.0` (`versionName` `1.1.0`) |
| License | Dual: AGPL-3.0-only or Element Commercial. See `LICENSE` and `LICENSE-COMMERCIAL`. |
| Distribution | Self-hosted builds; `fdroid` is the default flavor |

The fork is a full rebrand of Element X Android pointed at Fionaro infrastructure. It keeps the
upstream architecture (Compose + Appyx + Molecule + Metro + `matrix-rust-sdk`) and layers a small
set of product, endpoint, and build changes on top (section 6).

## 2. Toolchain

| Tool | Version |
| :--- | :--- |
| Kotlin | 2.4.0 |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.5.1 (wrapper) |
| JDK | 21 (configured in `plugins/src/main/kotlin/Versions.kt`) |
| Compile / target SDK | 37 / 37 (`Versions.COMPILE_SDK`, `Versions.TARGET_SDK`) |
| Build tools | 37.0.0 |
| Min SDK | 24 (FOSS) / 33 (enterprise) |
| Compose BOM | 2026.05.01 |
| Appyx / Molecule / Metro | 1.7.1 / 2.2.0 / 1.2.0 |
| matrix-rust-sdk (Kotlin bindings) | pinned in `gradle/libs.versions.toml` |

Versions that affect the Android toolchain live in `plugins/src/main/kotlin/Versions.kt`; library
versions live in `gradle/libs.versions.toml`.

## 3. Module layout

Gradle is configured by `settings.gradle.kts` with an included build at `plugins/` (convention
plugins such as `io.element.android-compose-application` and `io.element.android-root`).

```
app/                     Application module: app shell, flavor/build-type config, signing, R8 rules
appnav/                  Top-level Appyx navigation and node wiring
appconfig/               Fork product configuration (app name, endpoints, push switches, feature configs)
appicon/element/         FOSS launcher icons
appicon/enterprise/      Enterprise launcher icons
annotations/             Shared annotations
codegen/                 Annotation processing / code generation
enterprise/              Enterprise-only modules (included when building enterprise flavors)
features/<name>/         User-facing features, normally split into api / impl / test
libraries/               Reusable infrastructure and UI libraries
services/                Cross-cutting services (analytics, app error, navigation state, toolbox)
tests/detekt-rules/      Custom Detekt rules
tests/konsist/           Konsist architecture/consistency tests
tests/testutils/         Shared test helpers and fakes
tests/uitests/           Instrumented UI test suite
plugins/                 Included build with Gradle convention plugins and Versions.kt
tools/                   Developer scripts, lint/detekt config, release helpers
fastlane/, .maestro/     Store metadata and Maestro end-to-end flows
docs/                    Upstream developer documentation
```

### Feature module pattern

A feature normally has three modules:

| Module | Contents |
| :--- | :--- |
| `features/foo/api` | Public interfaces, data classes, `FooNode` entry points |
| `features/foo/impl` | Presenter, View, state/event types, internal wiring |
| `features/foo/test` | Fakes and test fixtures published for other modules |

Other modules follow the `api`/`impl` split less strictly (libraries generally expose a single
implementation module).

## 4. Architecture

### UI and navigation

- **Jetpack Compose** for all UI; **Appyx** nodes own navigation and back-stack behaviour.
- A screen is a `FooNode` (Appyx), a `FooPresenter` (a `@Composable` function that turns
  `FooEvent`s into a `FooState`), a stateless `FooView`, plus `FooState`, `FooEvent`, and a
  `FooStateProvider` for previews/screenshot tests.
- Previews wrap content in `ElementPreview { ... }` and use `@PreviewsDayNight`.

### Dependency injection

- **Metro** (`dev.zacsweers.metro`) with compile-time graph validation.
- Constructor injection via `@Inject`; `@AssistedInject` / `@AssistedFactory` for runtime
  arguments; `@ContributesBinding(AppScope::class)` for scoped bindings; `@ContributesNode(...)`
  for Appyx nodes.

### Matrix layer

- `libraries/matrix` wraps `matrix-rust-sdk`; UI code must not see `MatrixRustSDK` types.
- Rust types are mapped to Kotlin data classes in the `api` modules (`JoinedRoom`, `RoomInfo`,
  etc.).
- Rust-backed services (`libraries/rustsdk`, `libraries/matrix`) are initialised from the app
  module; widget and call integration lives in `RustWidgetDriver` / `JoinedRustRoom`.

### Design system and strings

- Compound design system (`libraries/compound`): use `ElementTheme` colours/typography and
  `CompoundIcons`.
- English strings live in `libraries/ui-strings`. `localazy.xml` is generated by Localazy and must
  never be edited by hand; new English strings go into `temporary.xml`.

### Logging

- Timber only (`android.util.Log` is forbidden). Never log secrets or user content; Matrix IDs are
  acceptable.

## 5. Build, run, and test

All commands run from the repository root and use the Gradle wrapper.

### Environment

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # any JDK 21 installation
```

Signing material for **release** builds is supplied only through environment variables:

```bash
export FIONARO_KEYSTORE_PATH=...   # path to the release keystore on the build machine
export FIONARO_KEYSTORE_PASSWORD=...
export FIONARO_KEY_PASSWORD=...
```

Never commit the keystore or the passwords. Release tasks fail with an explicit
"Fionaro release signing is not configured" error when any variable is missing; debug and nightly
builds do not need these variables.

### Flavors and build types

- Flavor dimension `store` with **`fdroid` (default)** and `gplay` (kept from upstream; not built
  or distributed by this fork).
- Build types: `debug`, `release`, `nightly`.
- `release` signs with the Fionaro release keystore and enables V1 + V2 signing (V1 is required by
  Obtanium-style updaters).
- `nightly` uses the separate nightly keystore and Firebase App Distribution config from upstream.
- APKs are split per ABI (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a universal APK; AAB
  builds disable splits.

### Common commands

```bash
# Configure/verify the build
./gradlew help

# Debug app (fdroid is the default flavor)
./gradlew :app:assembleFdroidDebug
./gradlew :app:installFdroidDebug

# Release app
./gradlew :app:assembleFdroidRelease
./gradlew :app:bundleFdroidRelease

# Unit tests for one module (preferred in multi-worker environments)
./gradlew :features:messages:impl:testDebugUnitTest
./gradlew :libraries:matrix:testDebugUnitTest

# Whole-app unit tests (slow; avoid when several builds share the machine)
./gradlew :app:testFdroidDebugUnitTest

# Static analysis and formatting
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew detekt
./gradlew :app:lintFdroidDebug

# Architecture tests
./gradlew :tests:konsist:test
```

APK outputs: `app/build/outputs/apk/fdroid/<buildType>/`, for example
`app-fdroid-arm64-v8a-debug.apk` and `app-fdroid-universal-release.apk`.

Instrumented UI tests (`tests/uitests`) require a device/emulator, and Maestro flows live in
`.maestro/`. Roborazzi screenshot verification is enabled for unit tests
(`roborazzi.test.verify=true`).

### Gradle properties that matter

- `org.gradle.jvmargs=-Xmx12g -XX:MaxMetaspaceSize=3g -XX:+HeapDumpOnOutOfMemoryError` — the
  effective daemon memory setting (declared once).
- `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`,
  `org.gradle.configureondemand=true`, `org.gradle.caching=true`.
- `android.r8.gradual.support=true` — required after enabling R8 with AGP 8.13+.

## 6. Fork-specific changes

### Branding and identity

- Application ID `pw.fionaro.chat`; app name "Fionaro Chat" (`appconfig` + `BuildTimeConfig`).
- Element branding, icons, and URLs replaced under `appicon/`, `libraries/ui-strings`, and
  `appconfig`.
- Fork version code/name are maintained in `plugins/src/main/kotlin/Versions.kt`; the fork override
  keeps builds newer than the upstream Element X release they are based on.

### Infrastructure endpoints

`plugins/src/main/kotlin/config/BuildTimeConfig.kt` and `appconfig/` point the client at:

- Homeserver / auth: `matrix.fionaro.pw`
- Element Call: `call.fionaro.pw` (LiveKit at `livekit.fionaro.pw`, TURN `turn.fionaro.pw`)
- Push: UnifiedPush via self-hosted ntfy at `push.fionaro.pw`; Firebase push is disabled
  (`PUSH_CONFIG_INCLUDE_FIREBASE = false`, `PUSH_CONFIG_INCLUDE_UNIFIED_PUSH = true`).

### Element Call integration

The fork carries patches to make Element Call v0.21.0 group calls work in a shared Matrix room:

- `DefaultCallWidgetProvider` — widget URL uses `/room/` and `skipLobby=true`, targeting the
  original room.
- `DefaultCallWidgetSettingsProvider` — call widget settings for the fork deployment.
- `WebViewWidgetMessageInterceptor` — CORS/createRoom interception and `update_state` injection for
  the Element Call widget.
- `CallScreenPresenter` / `CallScreenView` — Element Call action handling and WebView settings.
- `libraries/matrix` `RustWidgetDriver` / `JoinedRustRoom` — widget message transport and
  send-state-event support.

The full background, symptoms, and fix rationale are in `FIONARO-DESKTOP-HANDOFF.md`. Do not
"clean up" these patches without re-testing real group calls.

### Build and release hardening

- Release signing no longer falls back to the debug keystore. Missing signing environment
  variables fail the release build early with an actionable message
  (`checkFionaroReleaseSigning` in `app/build.gradle.kts`).
- `fdroid` is marked as the default flavor (`isDefault = true`), so IDE variant selection and
  fork tooling target `fdroid` builds; use explicit `...Fdroid...` task names in scripts
  (`assembleDebug`/`installDebug` remain ambiguous because both flavors still exist).
- `README.md` documents the required SDK/JDK and signing environment for fork builds.
- Advertising/analytics/privacy policy statements in `README.md` are maintained separately from
  this reference.

### Distribution

- Builds are self-hosted. The `gplay` flavor remains in the Gradle model only because it comes
  from upstream; this fork neither builds nor ships it.
- F-Droid-compatible behavior: no Firebase push dependency in `fdroid` builds, UnifiedPush/ntfy
  used instead.

## 7. Upstream sync strategy

The fork tracks upstream Element X Android via the `upstream` remote
(`https://github.com/element-hq/element-x-android.git`); `origin` is the public fork
(`https://github.com/WalidOA27/fionaro-chat-android.git`).

### Recommended flow

```bash
# 1. Fetch the upstream history and tags (do this on a clean tree)
git fetch upstream --tags

# 2. Create a sync branch from the current fork branch
git switch -c sync/upstream-<tag> <fork-branch>

# 3. Merge the upstream release tag or main; prefer merge over history rewriting
git merge --no-ff <upstream-tag>

# 4. Resolve conflicts, keeping fork behaviour (see hotspots below)

# 5. Verify the fork still builds and the test suite for touched modules passes
./gradlew help
./gradlew :app:assembleFdroidDebug
./gradlew ktlintCheck :app:detekt

# 6. Record the new base in README/FIONARO-DESKTOP-HANDOFF.md, then merge the sync branch
```

Use `git merge` (not `rebase`) for published fork branches: upstream history must stay intact and
the fork must not rewrite shared history. One upstream release per sync branch keeps conflicts
manageable.

### Conflict hotspots

Expect conflicts in files the fork deliberately changed:

- `app/build.gradle.kts` — signing config, release check task, flavor defaults.
- `gradle.properties` — JVM args and R8/configuration-cache flags.
- `plugins/src/main/kotlin/config/BuildTimeConfig.kt`, `appconfig/**` — endpoints, app identity,
  push switches.
- `plugins/src/main/kotlin/Versions.kt` — version code/name overrides and SDK/JDK bumps.
- `features/call/**`, `libraries/matrix/**` — Element Call widget patches.
- `README.md`, `FIONARO-DESKTOP-HANDOFF.md` — fork documentation.
- Branding resources under `appicon/**` and `libraries/ui-strings/**` (never hand-edit
  `localazy.xml`).

### Post-sync checklist

1. `./gradlew help` and `./gradlew :app:assembleFdroidDebug` succeed.
2. Release signing still fails cleanly without the `FIONARO_*` variables and succeeds with them.
3. `fdroid` is still the default flavor and `pw.fionaro.chat` is still the application ID.
4. Group calls, UnifiedPush notifications, and login against `matrix.fionaro.pw` were re-tested on
   a device (state any unverified item explicitly in the release notes).
5. `localazy.xml` changes were imported from Localazy rather than merged by hand.
6. No secrets, keystores, tokens, or password values were added to the repository.

## 8. Secrets and repository hygiene

Allowed in the repository: environment-variable **names**, documentation of how to supply values,
non-sensitive endpoint URLs.

Not allowed: keystore files, keystore passwords, key passwords, access tokens, service credentials,
`.env` files, or build logs containing them. Signing material is injected at build time through
`FIONARO_KEYSTORE_PATH`, `FIONARO_KEYSTORE_PASSWORD`, and `FIONARO_KEY_PASSWORD`; nightly signing
uses its own `ELEMENT_ANDROID_NIGHTLY_*` variables.
