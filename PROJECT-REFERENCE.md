# Fionaro Chat — Project Reference

> Auto-generated technical reference. Last updated: July 27, 2026.

---

## 1. Infrastructure

### 1.1 Server Topology

```
                           INTERNET
                               │
                      ┌────────┴────────┐
                      │   VPS (Caddy)    │  78.17.213.94
                      │   FRP Server     │  root / m7Y46218EnQlsiXGbt53SF90
                      └────────┬────────┘
                               │ FRP tunnels
                      ┌────────┴────────┐
                      │  Homelab (LAN)   │  192.168.1.92
                      │  Docker + Traefik│  walid / waldo
                      └─────────────────┘
```

### 1.2 VPS — Caddy Reverse Proxy (`/opt/caddy/Caddyfile`)

Caddy runs in Docker on the VPS, handling TLS termination and routing:

| Domain | Target | Notes |
|---|---|---|
| `fionaro.pw` | static response | `.well-known` endpoints for Matrix discovery |
| `matrix.fionaro.pw` | FRP (18080) | Matrix homeserver + `/register` static site |
| `chat.fionaro.pw` | `/var/www/fionaro-chat` | Element Web static files |
| `call.fionaro.pw` | FRP (18080) | Element Call (through Traefik) |
| `livekit.fionaro.pw` | FRP (8085/7880) | `/sfu/get`→8085 (JWT), rest→7880 (LiveKit) |
| `push.fionaro.pw` | FRP (18080) | ntfy push gateway (through Traefik) |
| `auth.fionaro.pw` | FRP (18080) | Auth service (through Traefik) |

`.well-known/matrix/client` at `fionaro.pw` and `matrix.fionaro.pw`:
```json
{
  "m.homeserver": {"base_url": "https://matrix.fionaro.pw/"},
  "org.matrix.msc4143.rtc_foci": [{
    "type": "livekit",
    "livekit_service_url": "https://livekit.fionaro.pw"
  }]
}
```

### 1.3 FRP Tunnels (VPS → Homelab)

FRP server on VPS (`frps`, port 7000, auth token: `36asd8344Mo9xKp2mN`).

FRP client on homelab (`/etc/frp/frpc.toml`) tunnels:

| Tunnel | VPS Port → Homelab Target | Purpose |
|---|---|---|
| `matrix-http` | 18080 → 127.0.0.1:443 | All HTTP/WS traffic → Traefik |
| `livekit-tcp-7880` | 7880 → 127.0.0.1:7880 | LiveKit WebSocket (direct) |
| `livekit-tcp-7881` | 7881 → 127.0.0.1:7881 | LiveKit TCP fallback |
| `livekit-tcp-7882` | 7882 → 127.0.0.1:7882 | LiveKit TURN/TCP |
| `livekit-udp-7882` | 7882 → 127.0.0.1:7882 | LiveKit UDP media |
| `jwt-tcp-8085` | 8085 → 127.0.0.1:8085 | JWT service (Caddy direct route for `/sfu/get`) |
| `coturn-tcp-3478` | 3478 → 127.0.0.1:3478 | TURN server |
| `coturn-udp-3478` | 3478 → 127.0.0.1:3478 | TURN server UDP |
| `coturn-tcp-5349` | 5349 → 127.0.0.1:5349 | TURN TLS |

### 1.4 Homelab — Docker Containers

| Container | Image | Ports (host) | Health | Purpose |
|---|---|---|---|---|
| `traefik` | traefik:v3.6 | 80, 443, 8082, 8090 | up | Reverse proxy, TLS |
| `matrix_synapse` | matrixdotorg/synapse:latest | 8008, 8448 (internal) | healthy | Matrix homeserver |
| `livekit` | livekit/livekit-server:latest | 7880, 7882 | healthy | WebRTC SFU |
| `livekit_jwt` | fionaro-jwt-service:latest | 8085 | up | JWT token generator |
| `element_call` | ghcr.io/element-hq/element-call:latest | 8080 (internal) | healthy | Element Call webapp (v0.21.0) |
| `ntfy` | binwiederhier/ntfy:latest | 80 (internal) | healthy | UnifiedPush server |

Synapse mounts: config at `/home/walid/comunicacion/matrix/synapse/config`, media at `/mnt/storage/matrix/synapse/media`.

### 1.5 Traefik Routing

Dynamic config at `/home/walid/traefik/dynamic/` (hot-reloaded on change):

**`livekit.yml`:**
```yaml
http:
  routers:
    livekit-sfu:
      rule: "Host(`livekit.fionaro.pw`) && PathPrefix(`/sfu`)"
      service: livekit-jwt
      entrypoints: [websecure]
      priority: 100
    livekit:
      rule: Host(`livekit.fionaro.pw`)
      service: livekit
      entrypoints: [websecure]
  services:
    livekit-jwt:
      loadBalancer:
        servers:
          - url: "http://livekit_jwt:5000"    # Docker DNS
    livekit:
      loadBalancer:
        servers:
          - url: "http://172.20.0.13:7880"    # LiveKit container IP
```

⚠️ The LiveKit server IP (`172.20.0.13`) is HARDCODED. If the container restarts and gets a new IP, this must be updated. Future improvement: use Docker DNS name.

**`elementcall.yml`:**
```yaml
http:
  routers:
    elementcall:
      rule: "Host(`call.fionaro.pw`)"
      service: elementcall
      entrypoints: [websecure]
  services:
    elementcall:
      loadBalancer:
        servers:
          - url: http://192.168.48.2:80       # element_call container IP:80
```

⚠️ Same hardcoded IP issue. The container EXPOSES 8080 but nginx serves on 80 internally. Port label mismatch.

### 1.6 JWT Service

Python Flask app in `livekit_jwt` container (port 5000 internal, 8085 external):
- Endpoint: `POST /sfu/get`
- Receives: `{room, openid_token, device_id}`
- Resolves OpenID via Federation API: `GET /_matrix/federation/v1/openid/userinfo?access_token=...`
- Gets `sub` = Matrix user ID
- Creates LiveKit room via `POST /twirp/livekit.RoomService/CreateRoom`
- Generates LiveKit JWT with `sub` = Matrix user ID

Two paths to JWT service:
1. **Caddy path:** Caddy `/sfu/get` → FRP 8085 → homelab 8085 → JWT container
2. **Traefik path:** Caddy `livekit.fionaro.pw/*` → FRP 18080 → Traefik → livekit_jwt:5000 (Docker DNS)

### 1.7 LiveKit Server (`livekit.fionaro.pw`)

```yaml
port: 7880
keys:
  fionaro: sST4xicFocRczUkKnrY5OVbs8OpTTSJI
rtc:
  stun_servers: [turn.fionaro.pw:3478]
  turn_servers: [{host: turn.fionaro.pw, port: 3478, protocol: tcp, ...}]
  node_ip: 78.17.213.94
turn:
  enabled: false
```

### 1.8 ntfy Push Server

Config: `/home/walid/comunicacion/notificaciones/ntfy/config/server.yml`
```yaml
listen-http: :80
base-url: "https://push.fionaro.pw"
auth-default-access: read-write       # was write-only (broke WS subscriptions)
enable-login: true
matrix-gateway: "https://push.fionaro.pw"
```

### 1.9 Synapse

- Container: `matrix_synapse`, port 8008 (internal), shared Docker network `matrix_matrix_net`
- Experimental features: `msc3401: enabled` (native Element Call support)
- TURN URIs: `turn:turn.fionaro.pw:3478?transport=udp`, `turn:turn.fionaro.pw:3478?transport=tcp`
- DB: SQLite at `/data/homeserver.db`

---

## 2. Android App

### 2.1 Repository

- **GitHub:** `WalidOA27/fionaro-chat-android`
- **Remote `fionaro`:** `https://github.com/WalidOA27/fionaro-chat-android.git`
- **Remote `origin`:** `https://github.com/element-hq/element-x-android.git` (upstream)
- **Base version:** Element X Android v26.06.4
- **Branch:** `main`
- **Current tag:** v1.0.8

### 2.2 Build

```bash
FIONARO_KEYSTORE_PATH=/home/walid/fionaro-chat-release.jks \
FIONARO_KEYSTORE_PASSWORD=HxI8RI4mYlj1WQ7rZ6MEUGjAKLm7Fd2G \
FIONARO_KEY_PASSWORD=HxI8RI4mYlj1WQ7rZ6MEUGjAKLm7Fd2G \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
./gradlew assembleFdroidRelease
```

- **⚠️ NEVER run `./gradlew clean`** — breaks KSP stubs causing `Unresolved reference` errors (upstream bug)
- **Keystore:** `~/fionaro-chat-release.jks`, alias `fionaro-chat`
- **APK:** `app/build/outputs/apk/fdroid/release/app-fdroid-universal-release.apk`
- **Signing:** `enableV1Signing = true`, `enableV2Signing = true` (v1 required for Obtanium/F-Droid)
- **Flavor:** `fdroid` only (no Firebase/Google Play Services)

### 2.3 Versioning (`plugins/src/main/kotlin/Versions.kt`)

```kotlin
private const val versionYear = 26
private const val versionMonth = 8
private const val versionReleaseNumber = 8   // increment for new releases
const val VERSION_CODE = (2000 + versionYear) * 10_000 + versionMonth * 100 + versionReleaseNumber
val VERSION_NAME = "1.0." + versionReleaseNumber
```

Current: v1.0.8 → VERSION_CODE = 20260808

### 2.4 Key Modified Files

| File | Modifications |
|---|---|
| `DefaultCallWidgetProvider.kt` | `/room/` path, `skipLobby=true`, `encrypted=false`, shared original room, `direct=isDm` |
| `DefaultCallWidgetSettingsProvider.kt` | Sets `CallIntent` based on `isDm` flag |
| `WebViewWidgetMessageInterceptor.kt` | EC action interceptor, `injectUpdateState()`, CORS preflight, `createRoom` proxy |
| `CallScreenPresenter.kt` | EC action bypass before Rust SDK |
| `CallScreenView.kt` | WebView setup with Fionaro domain |
| `WebViewWidgetMessageInterceptor.kt:222-265` | Intercepts EC `createRoom` calls, creates room via MatrixClient |
| `WebViewWidgetMessageInterceptor.kt:344-406` | `injectUpdateState()` — injects synthetic Matrix state into WebView |
| `RustMatrixClientFactory.kt:176` | `DmRoomDefinition.TWO_MEMBERS` — DM detection requires `m.direct` + ≤2 members |
| `app/build.gradle.kts` | Signing config with v1+v2 signing |
| `BuildTimeConfig.kt` | `APPLICATION_ID: pw.fionaro.chat`, all URLs to `*.fionaro.pw` |

### 2.5 Call Flow

```
User taps "call" in room
  → MessagesFlowNode / RoomDetailsFlowNode
  → CallData(sessionId, roomId, isAudioCall)
  → DefaultCallWidgetProvider.getWidget()
    → isDm = originalRoom.isDm()
    → encrypted = false        (avoids MissingKey from lk_e2ee)
    → direct = isDm
    → hasActiveCall = callRoomInfo.hasRoomCall
  → DefaultCallWidgetSettingsProvider.provide()
    → builds VirtualElementCallWidgetConfig with correct CallIntent
      (START_CALL_DM / START_CALL / JOIN_EXISTING_DM / etc.)
  → Rust SDK generates widget URL with userId, roomId, deviceId, etc.
  → URL host replaced: appassets.androidplatform.net → call.fionaro.pw
  → URL path fixed: /#? → /room/#?
  → &skipLobby=true appended
  → WebView loads Element Call JS from call.fionaro.pw
  → EC JS connects to LiveKit SFU
  → EC JS writes call.member state events
  → Kotlin WebViewMessageInterceptor catches EC actions
```

### 2.6 Call Intent Mapping

| Condition | Intent | Notification Type |
|---|---|---|
| `isDm=true` + no active call | `START_CALL_DM` / `START_CALL_DM_VOICE` | `ring` |
| `isDm=false` + no active call | `START_CALL` | `notification` |
| Active call exists | `JOIN_EXISTING` / `JOIN_EXISTING_DM` | N/A |

### 2.7 DM Detection Bug (RESOLVED)

**Root cause:** `DmRoomDefinition::TwoMembers` (Rust SDK) requires BOTH:
1. Room must be in user's `m.direct` account data (via `dm_targets`)
2. Active non-service members ≤ 2

**Not** "any room with 2 members is DM" as initially assumed.

**Two room creation paths:**
- **StartDM route:** `DefaultStartDMAction` → `createDM()` → sets `isDirect=true`, `preset=TRUSTED_PRIVATE_CHAT`, Rust SDK auto-adds to `m.direct`
- **ConfigureRoom route:** `ConfigureRoomPresenter.createRoom()` → sets `isDirect=false`, `preset=PRIVATE_CHAT`, NEVER touches `m.direct`

**If a 1:1 room is created via ConfigureRoom instead of StartDM**, the SDK won't detect it as DM, call intent will be `START_CALL` (not `START_CALL_DM`), and the recipient gets a silent notification instead of a ringing call.

**Symptom check:** Query `m.room.power_levels` → `invite: 50` = PRIVATE_CHAT (ConfigureRoom), `invite: 0` = TRUSTED_PRIVATE_CHAT (StartDM).

### 2.8 Red Exclamation in Calls (NOT A BUG)

The red exclamation `ErrorSolidIcon` in `MediaView.tsx` is the `unencryptedWarning` indicator. It shows because `encrypted=false` is deliberately set (avoids `MissingKey` from `lk_e2ee` hardcoded in EC v0.21.0). This is purely cosmetic — audio and video render independently.

---

## 3. Element Call (EC)

### 3.1 Version

**v0.21.0** — served from `call.fionaro.pw` (nginx in `element_call` Docker container).

Bundle: `assets/index-CE_xAlEM.js` (3.0 MB minified).

v0.22.0 exists (released July 20, 2026) but doesn't mention relevant fixes in changelog.

### 3.2 Identity & JWT Flow

1. EC JS discovers `livekit_service_url` from `.well-known` → `https://livekit.fionaro.pw`
2. Gets OpenID token from Matrix: `POST /_matrix/client/v3/user/{userId}/openid/request_token`
3. Calls `POST https://livekit.fionaro.pw/sfu/get` with `{room, openid_token, device_id}`
4. JWT service resolves OpenID → Matrix user ID via Federation API
5. JWT service creates LiveKit room, returns JWT with `sub` = user's Matrix ID
6. EC connects to LiveKit with JWT

Membership type: `org.matrix.msc3401.call.member` → `$k.Session` → `rtcBackendIdentity = senderUserId` (plain text, not hash).

### 3.3 MatrixAudioRenderer Warning

The `MatrixAudioRenderer` (`nut()` function in bundle) logs:
```
WARNING [MatrixAudioRenderer] Audio track @LOCAL_USER from livekit.fionaro.pw
  has no matching matrix call member current members: @REMOTE_USER
  track will not get rendered
```

This fires because `validIdentities` computation via `ConnectManager` fails to match LiveKit participants to Matrix members. Despite the warning, **audio/video works correctly** — this is a cosmetic logging issue, not a transport failure.

---

## 4. Users & Credentials

### 4.1 Admin Account

```
Username:  @admin:fionaro.pw
Password:  waldo
API Token: syt_YWRtaW4_DlpsUHAAuqHOUBvdHCEo_49Gddv
```

### 4.2 Test Users

| User | Device | Device ID |
|---|---|---|
| `@tmoat057:fionaro.pw` | Pixel 9a (ADB: 63161JEBF06502) | EUPSIBZBBO |
| `@tmoat058:fionaro.pw` | Redmi Note 13 Pro (ADB: cb871252) | IKVSFUSCXQ |
| `@luxo:fionaro.pw` | Honor HNCRT-M1 (no ADB) | DPKGNRVKFS |

### 4.3 SSH Access

```
archD:   walid@192.168.1.75   password=waldo
homelab: walid@192.168.1.92   password=waldo
VPS:     root@78.17.213.94    password=m7Y46218EnQlsiXGbt53SF90
```

---

## 5. Admin Operations

### 5.1 Announcements Room

- **Room:** `#Anuncios Fionaro:fionaro.pw` (`!cBndmQDEDywPmRQzqI`)
- **All existing users** auto-invited

**Send announcement:**
```bash
ssh homelab "docker exec matrix_synapse curl -s \
  -X PUT 'http://localhost:8008/_matrix/client/v3/rooms/!cBndmQDEDywPmRQzqI:fionaro.pw/send/m.room.message/\$(uuidgen)' \
  -H 'Authorization: Bearer syt_YWRtaW4_DlpsUHAAuqHOUBvdHCEo_49Gddv' \
  -H 'Content-Type: application/json' \
  -d '{\"msgtype\":\"m.text\",\"body\":\"tu mensaje\"}'"
```

**Add user to room:**
```bash
ssh homelab "docker exec matrix_synapse curl -s \
  -X POST 'http://localhost:8008/_synapse/admin/v1/join/!cBndmQDEDywPmRQzqI:fionaro.pw' \
  -H 'Authorization: Bearer syt_YWRtaW4_DlpsUHAAuqHOUBvdHCEo_49Gddv' \
  -H 'Content-Type: application/json' \
  -d '{\"user_id\":\"@nuevo:fionaro.pw\"}'"
```

### 5.2 Reset User Password

```bash
ssh homelab "docker exec matrix_synapse curl -s -X POST \
  'http://localhost:8008/_synapse/admin/v1/reset_password/@user:fionaro.pw' \
  -H 'Authorization: Bearer syt_YWRtaW4_DlpsUHAAuqHOUBvdHCEo_49Gddv' \
  -H 'Content-Type: application/json' \
  -d '{\"new_password\": \"nueva_clave\", \"logout_devices\": false}'"
```

### 5.3 Query Synapse DB

```bash
ssh homelab "docker exec matrix_synapse python3 -c '...'"
```

Key tables: `users`, `pushers`, `access_tokens`, `account_data`, `state_events`, `event_json`, `events`, `e2e_cross_signing_keys`, `e2e_room_keys`.

### 5.4 Restart Services (homelab)

```bash
ssh homelab "docker restart element_call"
ssh homelab "docker restart livekit_jwt"
ssh homelab "docker restart livekit"
ssh homelab "docker restart ntfy"
ssh homelab "docker restart matrix_synapse"
ssh homelab "docker restart traefik"
```

### 5.5 Traefik Config Hot-Reload

Traefik watches `/home/walid/traefik/dynamic/` — any YAML file change is picked up within seconds. No restart needed.

### 5.6 ADB Commands

```bash
adb devices                                    # list connected devices
adb -s SERIAL logcat -c                        # clear logs
adb -s SERIAL logcat -d | grep FionaroCall     # dump Fionaro logs
adb -s SERIAL shell dumpsys package pw.fionaro.chat  # app info
```

---

## 6. Consolidated Bug Map

| # | Bug | Root Cause | Status | Fix |
|---|---|---|---|---|
| 1 | Group calls: both users in same room but LiveKit not connecting | React Router `path="/"` collision — EC JS's `fet()` routed to Home Page instead of Call View | ✅ FIXED (v1.0.0) | `/room/` URL path + `skipLobby=true` for all calls; shared original room |
| 2 | Silent push failure after app reinstall | ntfy `auth-default-access: write-only` blocked WebSocket subscriptions (403); also stale pushers from reinstalls | ✅ FIXED (server-side) | Changed ntfy config to `read-write`; restarted container |
| 3 | DM calls ring as notification instead of full-screen ringtone | Room not in `m.direct`; `DmRoomDefinition::TwoMembers` requires BOTH `m.direct` AND ≤2 members | ✅ DIAGNOSED | Use StartDM route (not ConfigureRoom) for 1:1 rooms |
| 4 | Red exclamation mark in calls | `ErrorSolidIcon` showing `unencryptedWarning=true` because `encrypted=false` deliberately set | ✅ NOT A BUG | Icon is correct and purely cosmetic |
| 5 | 502 error on `call.fionaro.pw` | Traefik config had stale IP + wrong port (8080→80) for element_call container | ✅ FIXED | Updated `/home/walid/traefik/dynamic/elementcall.yml` |
| 6 | Calls failing — JWT service unreachable | No Traefik route for `/sfu/get` → JWT service at port 5000 | ✅ FIXED | Added `livekit-sfu` router with higher priority in `livekit.yml` |

### Evidence Chain for Call Identity (Bug #3 research)

```
Rust SDK → DmRoomDefinition::TwoMembers
  → compute_is_dm() first checks dm_targets (from m.direct account data)
  → Then checks active non-service members ≤ 2
  → Both conditions must be true for isDm=true

DefaultCallWidgetProvider.kt:48 → val isDm = originalRoom.isDm()
DefaultCallWidgetProvider.kt:62 → direct = isDm
DefaultCallWidgetSettingsProvider.kt:65-73 → if direct → START_CALL_DM, else START_CALL
EC JS (index-CE_xAlEM.js) → if intent=start_call → sendNotificationType="notification"
EC JS (index-CE_xAlEM.js) → if intent=start_call_dm → sendNotificationType="ring"
```

---

## 7. API Keys & Secrets

| Secret | Location | Value |
|---|---|---|
| LiveKit API Key | `/home/walid/comunicacion/livekit/config/livekit.yaml` | `sST4xicFocRczUkKnrY5OVbs8OpTTSJI` |
| FRP Auth Token | `/etc/frp/frpc.toml`, `/etc/frp/frps.toml` | `36asd8344Mo9xKp2mN` |
| Synapse Admin Token | DB `access_tokens` | `syt_YWRtaW4_DlpsUHAAuqHOUBvdHCEo_49Gddv` |
| Android Keystore | `~/fionaro-chat-release.jks` | pass: `HxI8RI4mYlj1WQ7rZ6MEUGjAKLm7Fd2G`, alias: `fionaro-chat` |
| TURN Credential | `livekit.yaml` | `1BbzruJzIse0BmQXOoooWfC3nkE=` |
| ntfy default access | `server.yml` | `read-write` |
