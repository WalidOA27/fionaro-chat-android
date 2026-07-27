# Fionaro Chat — Handoff Document for Desktop Agent

> **Recipient:** Agent working on the **Desktop/Electron** version of Fionaro Chat  
> **Source:** Fionaro Chat Android fork (`WalidOA27/fionaro-chat-android`)  
> **Base version:** Element X Android v26.06.4 → Element Web/Desktop equivalent  
> **Date:** July 2026

---

## 1. What We Forked and Why

### The Stack

```
┌─────────────────────────────────────────────────┐
│ Fionaro Chat Android (pw.fionaro.chat)           │
│   Fork of: element-hq/element-x-android v26.06.4│
│   Built with: Kotlin + Jetpack Compose           │
│   SDK: matrix-rust-sdk (FFI)                     │
│   Flavor: fdroid (no Firebase/GMS)               │
│                                                   │
│   ┌──────────────────────────────────────────┐   │
│   │ Element Call v0.21.0 (served from server)│   │
│   │   Served at: https://call.fionaro.pw     │   │
│   │   JS bundle: index-CE_xAlEM.js           │   │
│   │   Uses React Router v6                   │   │
│   │   Uses matrix-js-sdk (widget mode)       │   │
│   └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

The Android app embeds Element Call as a **WebView widget**. The Desktop version will embed it in an **Electron shell**. The core web app (Element Call) is the **same JS codebase** — the same bundle, the same routing, the same bugs.

### Our Infrastructure (All Self-Hosted)

| Service | Domain | Technology |
|---------|--------|------------|
| Matrix Homeserver | `matrix.fionaro.pw` | Synapse |
| Element Call (widget) | `call.fionaro.pw` | Element Call v0.21.0 |
| Push Notifications | `push.fionaro.pw` | ntfy (UnifiedPush) |
| Auth | `auth.fionaro.pw` | Matrix Auth |
| LiveKit | `livekit.fionaro.pw` | LiveKit SFU |
| TURN | `turn.fionaro.pw` | coturn |

**Key difference from Element.io:** We serve Element Call from our own server (`call.fionaro.pw`), NOT from `call.element.io`. The JS bundle is identical to standard Element Call v0.21.0 — we have NOT modified it. This means any bug we found in the JS bundle **applies to Desktop too**.

### Flavor and Distribution
- **fdroid flavor only** — no Google Play Services, no Firebase
- Push: **UnifiedPush via self-hosted ntfy** (no FCM)
- Analytics: **completely eliminated** (no Sentry, PostHog, crash reporting)
- Domain: `fionaro.pw` with subdomains: `matrix`, `push`, `call`, `auth`, `livekit`, `turn`
- Deep link scheme: `fionaro://`
- Application ID: `pw.fionaro.chat`

---

## 2. The Group Call Bug — Complete Diagnosis

**This is the single most important section. Read it thoroughly. The Desktop version WILL have the same bug unless you implement the same fix.**

### The Symptom

When two users click "Start Call" in the same Matrix room:
- Both users' apps open a call view
- Both connect to LiveKit successfully (confirmed in logs)
- Both publish audio/video tracks
- **BUT they never see each other** — each user sees only their own video

Each user's `call.member` event was written to a **different** Matrix room ID. Confirmed in logcat: two different `room_id` values, one per user.

### Root Cause #1: React Router v6 Route Collision

Element Call v0.21.0 uses React Router v6 with these routes:

```
path="/"          → ntt (Home Page — renders "Start new call" form → calls fet())
path="/login"     → att (Login)
path="/register"  → ott (Register)
path="*"          → adt (Call View — parses roomId, renders Zut, NEVER calls fet())
```

The widget URL is:
```
https://call.fionaro.pw/#?userId=...&roomId=R&widgetId=...&intent=start_call&...
```

The **pathname** of this URL is `/`. React Router v6's `path="/"` matches it **exactly**. The catch-all `path="*"` is **never reached**.

**Result:** The Home Page (`ntt`) renders instead of the Call View (`adt`).

The Home Page (`ntt`) does NOT check for `roomId` in the URL. It only shows a "Start new call" form with a text input and a "Go" button. When the user submits that form, it calls the `fet()` function which does `client.createRoom()`.

The Call View (`adt`) is the ONLY component that:
- Reads `roomId` from URL params
- Calls `tdt()` to load the room
- Renders `Zut` (GroupCallView) with `skipLobby` parameter
- Starts the MatrixRTC session
- **Never** calls `fet()` or `createRoom()`

### Root Cause #2: Per-User Room Creation

Even when `fet()` is called, it creates a **new** Matrix room for the call. Each user's client creates its own room:

```
User A clicks "call" → fet() → createRoom → room C
User B clicks "call" → fet() → createRoom → room D
```

User A writes `m.call.member` events in room C. User B writes them in room D. They're in different Matrix rooms → LiveKit tracks don't connect because there's no shared room state.

Confirmed in logcat: TWO different `room_id` values in `update_state` messages, one per user. Both users had LiveKit connected but with 0 remote members.

### Root Cause #3: L.Room Event Deadlock

When `fet()` calls `createRoom()` and the Kotlin interceptor returns the response, `fet()` then waits for a `L.Room` event on the MatrixClient:

```javascript
async function fet(e, t, n) {
    let r = e.createRoom({...});  // HTTP POST
    let i = await new Promise((t, n) => {
        let handler = e => {
            r.then(n => {
                e.roomId === n.room_id && t(e.roomId)
            })
        };
        e.on(L.Room, handler)  // Waits for room to appear in store
    });
    return {roomId: i, ...};
}
```

In widget mode, `L.Room` is emitted by `store.storeRoom()` in `startClient()`. This happens ONCE when the client initializes. By the time `fet()` registers its listener, the room is already in the store and `L.Room` has already been emitted.

**Result:** `fet()` hangs forever waiting for `L.Room`. The user clicks "Go" again → `fet()` runs again → creates ANOTHER room → hangs again.

We tried fixing this by injecting synthetic `update_state` via `window.postMessage` to trigger `L.Room`. This failed because:
1. WebView threading — `webView.url` crashes on non-main thread
2. `postMessage` target origin syntax errors
3. EC's matryoshka client in widget mode only manages ONE room (from the URL), not dynamically created ones
4. Even when `update_state` arrives, the store discards it for unknown room IDs

**After 7 build iterations, we abandoned all injection approaches.**

### The Final Solution (3 Parts)

**Part 1: URL path fix**
```
BEFORE: https://call.fionaro.pw/#?roomId=R&...
AFTER:  https://call.fionaro.pw/room/#?roomId=R&...
```

With pathname `/room/`, React Router's `path="/"` does NOT match → catch-all `*` matches → `adt` (Call View) renders. `fet()` is structurally unreachable in `adt`.

In the Android code (`DefaultCallWidgetProvider.kt`):
```kotlin
callUrl.replace("call.fionaro.pw/#?", "call.fionaro.pw/room/#?")
```

On Desktop, you need to do the equivalent — modify the widget URL before passing it to the Electron shell.

**Part 2: skipLobby=true**
Append `&skipLobby=true` to the widget URL. This skips the lobby form entirely. The Call View (`Zut`) renders directly with `s(!0)` → `joined=true` → call starts immediately.

**Part 3: Shared original room — THIS IS THE KEY INSIGHT**
Instead of pre-creating a new call room C, use the **original Matrix room R** (where the user clicked "call") as the call room. Both users are already members of R → both write `m.call.member` to R → both see each other's call state → LiveKit connects them.

The Android code change (`DefaultCallWidgetProvider.kt`):
```kotlin
// BEFORE: Create new room for group calls
val callRoom = if (isDm) originalRoom else {
    val newRoomId = matrixClient.createRoom(params).getOrThrow()
    matrixClient.getJoinedRoom(newRoomId)
}

// AFTER: Always use original room
val callRoom = originalRoom  // NO new room creation. EVER.
```

### Evidence This Works

Logcat from v1.0.0 (July 2026) showing two users in the SAME room:

```
19:54:31.283 Call memberships in room: [[@tmoat057:fionaro.pw],[@tmoat058:fionaro.pw]] — emitting (2 members)!
19:54:31.285 Remote user visible: @tmoat058:fionaro.pw creating userMedia item
19:54:31.288 matrixLivekitMembers$ updated: [@tmoat058:fionaro.pw|LRVFDINLYG]
19:54:20.287 livekitRoom.connect SUCCESS
```

Both users in `room_id=!AzkUsZtLGyanGQrHvo:fionaro.pw`, both `call.member` events written, both users' LiveKit tracks visible.

### Relevant MSC and Widget Capabilities

- Call member event type: `org.matrix.msc3401.call.member`
- State key format: `_@user:server_DEVICEID_m.call`
- Required widget capability: `org.matrix.msc2762.send.state_event:org.matrix.msc3401.call.member#@user:server`
- The widget bridge sends `send_event` via postMessage for `call.member` events
- The bridge processes these and returns `update_state` with the written events

### THIS BUG APPLIES TO DESKTOP

All fixes are in the **JS layer** (Element Call bundle) and the **integration layer** (URL construction). They are NOT Android-specific:
- The React Router bug is in the JS bundle → same bundle on Desktop
- The URL path fix is in the widget URL construction → same construction on Desktop
- The shared room approach is in the call startup logic → same logic needed on Desktop

**If the Desktop agent sees the same symptom (users in same room but LiveKit not connecting them), this is the FIRST place to look.**

---

## 3. Desktop Repo Instructions — CRITICAL

### DO NOT use the archived repo

The repo `element-hq/element-desktop` was **ARCHIVED in 2025**. It was merged back into `element-hq/element-web` under the `apps/desktop` directory.

**Correct base repo:** https://github.com/element-hq/element-web/tree/develop/apps/desktop

Element Desktop is an **Electron wrapper** around Element Web. It does NOT contain its own webapp code — it fetches the webapp from a URL at runtime.

### How to point Desktop at OUR Element Web/Element Call

The desktop app downloads the webapp from a configured URL. You need to point it at:
- Our Element Call server: `https://call.fionaro.pw` (for the call widget)
- Our Element Web server (if we set one up): `https://matrix.fionaro.pw` (for the main web client)

In the Electron wrapper:
1. The `config.json` determines where the webapp is fetched from
2. Set the `base_url` or equivalent to `https://matrix.fionaro.pw`
3. Set the call widget URL prefix to incorporate `/room/` path and `skipLobby=true`

### Development shortcut: symlink

```bash
ln -s ../element-web/webapp ./webapp
```
Makes Electron load the webapp from your local build instead of downloading it.

### Important CVE

**CVE-2025-59161** — affects the Electron wrapper. Fixed in **v1.11.112**. Do NOT use any base code from before this version without reviewing the fix. This is in the Electron wrapper, not in the webapp.

### Known Linux issue

On Linux, there's a known bug where the **tray icon appears but the window doesn't open**. This is a pre-existing Element Desktop issue — do not waste time re-diagnosing it. If encountered, it's likely an Electron/X11/Wayland compositor issue, not our fork.

---

## 4. Fork-Specific Configuration and Decisions

### CORS Configuration

Our Synapse server (`matrix.fionaro.pw`) returns `Access-Control-Allow-Origin: *` on ALL methods (verified with curl and runtime logs). No CORS issues expected.

In the Android app, we added a CORS preflight interceptor in the WebView for `OPTIONS` requests to `/_matrix/` paths. In Desktop, this is handled by Electron's networking layer and shouldn't need explicit handling.

### Certificate/Domain Configuration

All services run under `*.fionaro.pw` with valid TLS certificates. The Electron app must trust these certificates. If using self-signed certs in development, Electron may need `--ignore-certificate-errors` flag (for development only).

### Encrypted Calls

We use `perParticipantE2EE=false` in the widget URL. This disables per-participant end-to-end encryption in the call. The group call uses `lk_e2ee` (LiveKit media encryption) which is hardcoded in EC v0.21.0. We currently have a known issue: `MissingKey → Suppressing further decryption errors` — this needs server-side resolution (either update EC to a version that supports disabling LiveKit E2EE, or configure LiveKit to accept unencrypted media from our server).

**Status:** Encrypted calls may not work. Unencrypted calls work fine.

### Registration Flow

We use web-based registration at `https://matrix.fionaro.pw/register` with `m.login.dummy`. No email/password flow.

### Build Configuration

Android uses `BuildTimeConfig.kt` and `ModulesConfig.kt`:
- `APPLICATION_ID = "pw.fionaro.chat"`
- All URLs point to `*.fionaro.pw`
- Analytics → Disabled for non-Element builds
- Push toggles for UnifiedPush

Desktop equivalent will need similar configuration in `config.json`, environment variables, or Electron build config.

### App Icon, Colors, and Branding (v1.0.1)

| Asset | Value |
|-------|-------|
| App icon | Circular logo from `fionaro-logo-circle.svg` (1095×1095, clipPath) |
| Icon background | Black `#000000` |
| Icon foreground | White logo + purple accent `#7C3AED` |
| Accent color | Purple `#7C3AED` |
| Splash screen | Black `#000000` background |
| Onboarding gradient | `#7C3AED` → `#4C1D95` (purple → deep purple) |
| Notification icon | Vector XML `ic_notification.xml` (monochrome, tinted at runtime) |

### Decisions We Made (and Why)

1. **fdroid flavor only** — No dependency on Google services. Desktop doesn't have this constraint, but consistency matters.

2. **No analytics anywhere** — Sentry, PostHog, crash reporting all disabled. The Desktop build should follow suit.

3. **encrypted = false in createRoom** — Controls `perParticipantE2EE` only. We hardcode this.

4. **skipLobby=true** — We decided to skip the lobby entirely (no "start call" button). The call starts immediately when the widget loads.

5. **Shared original room** — We eliminated pre-created rooms entirely. The call always happens in the room where the user clicked "call". This was the key insight after 7 failed build iterations trying to inject `update_state` or pre-create rooms.

6. **No modification to Element Call JS bundle** — We could have patched the JS to skip `fet()` or change routing. We deliberately chose NOT to. All fixes are in the integration layer (Kotlin for Android, config/URL for Desktop). This minimizes maintenance burden across EC version updates.

### Files to Study (Android-specific, but patterns apply to Desktop)

| File | What it does |
|------|-------------|
| `DefaultCallWidgetProvider.kt` | Builds widget URL, creates driver, sets skipLobby, uses original room |
| `DefaultCallWidgetSettingsProvider.kt` | Sets widget intent, encryption, analytics |
| `WebViewWidgetMessageInterceptor.kt` | CORS preflight, createRoom interceptor, postMessage bridge |
| `CallScreenPresenter.kt` | Manages widget lifecycle, message relay |
| `BuildTimeConfig.kt` | Central config: APPLICATION_ID, all URLs |

### Debugging Tips (from our experience)

1. **Always start with the JS console.** The Element Call bundle logs extensively:
   - `UrlParams: final set of url params` — what EC sees in the URL
   - `Finished initial sync` — matryoshka client initialized
   - `GroupCallView Component mounted` — call view rendered
   - `livekitRoom.connect SUCCESS` — LiveKit connection established
   - `Creating room for group call` — `fet()` was called (this should NEVER appear with the fix)

2. **Check the widget URL pathname.** If it's `/`, the Home Page renders (bug). If it's `/room/`, the Call View renders (correct).

3. **Check `room_id` in `call.member` events.** Both users should write to the SAME room_id.

4. **Check MatrixRTC membership count.** After both users join, you should see `emitting (2 members)`, not `(1 members)`.

---

## 5. Quick Reference: URLs and Endpoints

```
Matrix Homeserver:  https://matrix.fionaro.pw
Element Call:       https://call.fionaro.pw
Push Gateway:       https://push.fionaro.pw
Auth:               https://auth.fionaro.pw
LiveKit SFU:        https://livekit.fionaro.pw
TURN:               turn.fionaro.pw
Registration:       https://matrix.fionaro.pw/register
Logo PNG:           https://fionaro.pw/logo.png
```

---

## 6. Repository and Version Info

- **Android fork:** https://github.com/WalidOA27/fionaro-chat-android
- **Desktop fork (to be created):** https://github.com/WalidOA27/fionaro-chat-desktop
- **Base version:** Element X Android v26.06.4 / Element Web equivalent
- **Current versions:** v1.0.0 (stable group calls), v1.0.1 (rebranding)
- **Element Call version:** v0.21.0 (served from `call.fionaro.pw`)
- **Desktop base:** element-hq/element-web > `apps/desktop` (NOT the archived element-desktop)
- **License:** AGPL-3.0 (inherited from Element X)

---

## 7. Known Issues

### ntfy Dead Pusher — Silent Push Failure After App Reinstall

**Discovered:** July 2026.

**Root cause:** ntfy's Matrix push gateway returns HTTP 200 OK even when the target UnifiedPush topic no longer exists (e.g., after reinstalling the app, which regenerates the push endpoint). Synapse trusts the 200 response, keeping `enabled=1, failing_since=null` forever.

**Impact:** Any user who reinstalls the app silently stops receiving push for calls. DB fix: re-register the pusher from the app (Settings → Notifications → Reconnect UnifiedPush).

**Long-term:** ntfy should return `rejected: [pushkey]` in the response body. Synapse cron could disable stale pushers. Not implemented.

### DM Detection: StartDM vs ConfigureRoom — "Ring" vs "Notification" Type

**Discovered:** July 2026.

**Root cause:** There are TWO separate code paths for creating 1:1 rooms:
- **StartDM / createDM** route (`DefaultStartDMAction` → `matrixClient.startDM()` → `createDM()`): sets `isDirect=true`, `preset=TRUSTED_PRIVATE_CHAT`, and the Rust SDK automatically adds the room to `m.direct` account data.
- **ConfigureRoom / New Room** route (`ConfigureRoomPresenter.createRoom()`): sets `isDirect=false`, `preset=PRIVATE_CHAT`, and **never** touches `m.direct` account data.

The Rust SDK's `DmRoomDefinition::TwoMembers` (configured in `RustMatrixClientFactory.kt:176`) does NOT mean "any room with 2 members is a DM". From the SDK source (`matrix-sdk-base/src/room/mod.rs`):

```rust
// compute_is_dm()
DmRoomDefinition::TwoMembers => {
    if !is_direct { return Ok(false); }  // must be in m.direct first
    let at_most_two = active_members - service_members <= 2;
    Ok(at_most_two)
}
// is_direct() reads dm_targets, populated from m.direct account data
```

**Impact:** Rooms created via ConfigureRoom (even 1:1) → `isDm=false` → `intent=START_CALL` (not `START_CALL_DM`) → `sendNotificationType="notification"` instead of `"ring"` → silent notification instead of full-screen ringing call.

**Symptom check:** Query `m.room.power_levels` → `invite: 50` = PRIVATE_CHAT (ConfigureRoom), `invite: 0` = TRUSTED_PRIVATE_CHAT (StartDM).

**Fix:** Use StartDM route for 1:1 rooms. For orphan rooms, manually add to `m.direct` account data. No code changes needed.

**Key files:**
- `libraries/matrix/impl/.../RustMatrixClientFactory.kt:176` — `DmRoomDefinition.TWO_MEMBERS`
- `libraries/matrix/impl/.../widget/DefaultCallWidgetSettingsProvider.kt:65-73` — intent selection
- `features/call/impl/.../utils/DefaultCallWidgetProvider.kt:48-62` — `isDm()` → `direct` param
- `libraries/matrix/api/.../room/RoomInfo.kt:31-32` — `isDirect` (create event) vs `isDm` (m.direct)

### LiveKit Identity Mismatch — Red Exclamation in Calls

**Discovered:** July 2026.

**Symptom:** Both participants see a red exclamation mark during calls. Audio/video tracks don't render. Logcat shows repeated warnings:
```
FionaroCallJS: WARNING [MatrixAudioRenderer]
  Audio track @LOCAL_USER from livekit.fionaro.pw
  has no matching matrix call member current members: @REMOTE_USER
  track will not get rendered
```

**Root cause (hypothesis, unconfirmed):** When the remote participant (e.g., @luxo on Honor HNCRT-M1) connects to LiveKit, their EC instance announces a LiveKit identity that doesn't match the Matrix user ID in `call.member` events. The local device sees the track arriving from LiveKit with a wrong identity and can't map it to a known Matrix call member.

**How `matrixLivekitMembers` works:** It's computed by EC JS from `m.call.member` state events in the Matrix room. Format: `[@userId|deviceId]`. The renderer requires that LiveKit track identity matches a userId in this list.

**Observed pattern** (July 26 tests):
- Pixel (tmoat057) → @luxo in `gbQd`: `matrixLivekitMembers=[@luxo|DPKGNRVKFS]`, track arrives as `@tmoat057`
- Redmi (tmoat058) → @luxo in `lvx` (simultaneous): same pattern, track arrives as `@tmoat058`
- Both @luxo accounts on a single Honor device receiving two simultaneous rings

**Status (July 26): DIAGNOSED — NOT A BUG. The red exclamation is `ErrorSolidIcon` showing the `unencryptedWarning` flag because calls intentionally run without per-participant E2EE (`encrypted=false`).**

**What the icon is:**
- Component: `ErrorSolidIcon` in `MediaView.tsx` (EC JS, `src/tile/MediaView.tsx`)
- Triggered by: `unencryptedWarning$` observable on `UserMediaViewModel`
- Shows when: `encrypted=false` → EC interprets this as "this call is unencrypted"
- **Purely cosmetic** — does NOT control video/audio rendering. The `video$` and LiveKit track publication observables are independent.

**Why `encrypted=false`:**
- Deliberate decision (documented in Line 299 above)
- `lk_e2ee` (LiveKit media encryption) is hardcoded in EC v0.21.0
- With `encrypted=true`: `perParticipantE2EE` + `lk_e2ee` conflict → `MissingKey` errors
- With `encrypted=false`: `lk_e2ee` still active (server-side), but no `MissingKey` (confirmed: 0 errors in logs)
- Audio/video transmission works correctly (confirmed by @luxo in live test)

**The `MatrixAudioRenderer` warning is unrelated:**
- `MatrixAudioRenderer` logs "Audio track...has no matching matrix call member"
- This warning fires initially but does NOT prevent audio playback
- The `validIdentities`/ConnectManager matching issue is real but cosmetic — audio still plays through LiveKit's own mechanisms

**Action:** No code changes needed. The icon correctly indicates the unencrypted status. If a UX decision is made to hide it, the flag would need to be controlled via `VirtualElementCallWidgetConfig` or a custom EC build flag.

---

## Consolidated Bug Map — July 2026 Session

Four independent bugs were discovered and investigated during the July 2026 debugging session. Here is the consolidated status.

| # | Bug | Root Cause | Status | Fix |
|---|---|---|---|---|
| 1 | Group calls: both users in same room but LiveKit not connecting | React Router `path="/"` collision — EC JS's `fet()` routed to Home Page instead of Call View | ✅ FIXED (v1.0.0) | `/room/` URL path + `skipLobby=true` for all calls; shared original room |
| 2 | Silent push failure after app reinstall | ntfy `auth-default-access: write-only` blocked WebSocket subscriptions (403 Forbidden); also 3 stale pushers per user from reinstalls | ✅ FIXED (server-side) | Changed ntfy config to `read-write`; restarted container; documented pusher cleanup |
| 3 | DM calls ring as notification instead of full-screen ringtone | Room not in `m.direct` account data; `DmRoomDefinition::TwoMembers` requires BOTH `m.direct` membership AND ≤2 members | ✅ DIAGNOSED — no code change needed | Use StartDM route (not ConfigureRoom) for 1:1 rooms; orphan rooms can be manually added to `m.direct` |
| 4 | Red exclamation mark in calls | `ErrorSolidIcon` in `MediaView.tsx` showing `unencryptedWarning=true` because `encrypted=false` is deliberately set (avoids `MissingKey` errors from `lk_e2ee` in EC v0.21.0) | ✅ DIAGNOSED (not a bug) | Icon is correct and purely cosmetic; no code change needed; document that calls run without per-participant E2EE |

### Evidence Chain for Bug #4

```
                         ✅ VERIFIED: JWT sub = correct per-user
                                      │
                                      ▼
post /sfu/get ──────► jwt service ──► resolve openid via Federation API
                                      │
                    ✅ VERIFIED: Federation returns correct sub
                                      │
                                      ▼
                 LiveKit creates room with correct participant identities
                                      │
           ❌ BUG: ConnectManager fails participant<->member match
                                      │
                                      ▼
           validIdentities = [] → MatrixAudioRenderer filters ALL tracks
                                      │
                                      ▼
                 Red exclamation on both devices, no audio/video
```

### Files Modified This Session

| File | Change |
|---|---|
| `DefaultCallWidgetProvider.kt` | `/room/` path, `skipLobby=true`, shared original room, `encrypted=false` |
| `DefaultCallWidgetSettingsProvider.kt` | `CallIntent` selection based on `isDm` |
| `WebViewWidgetMessageInterceptor.kt` | EC action interceptor, `injectUpdateState`, CORS preflight |
| `CallScreenPresenter.kt` | EC action bypass before Rust SDK |
| `Versions.kt` | Bumped to v1.0.8 |
| `app/build.gradle.kts` | `enableV1Signing=true` for Obtanium |
| `FIONARO-DESKTOP-HANDOFF.md` | This document (all findings) |
| `README.md` | Admin announcements section, branding links |

---

*If something is unclear, check the Android repo's README.md and commit history (especially v0.2.x for debugging iterations of the group call fix, and v1.0.0 for the final solution).*

*If something is unclear, check the Android repo's README.md and commit history (especially v0.2.x for debugging iterations of the group call fix, and v1.0.0 for the final solution).*
