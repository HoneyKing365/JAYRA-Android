# JAYRA Deck — Android Companion (v4.5.0 "Cybernetic Deck Edition")

Native Android app (Kotlin · Jetpack Compose · Material 3 · Coroutines/StateFlow · Koin) that turns your phone
into a wireless deck for the JAYRA desktop assistant: dual-WebSocket bridge (`/live` + `/mobile-bridge`),
holographic avatar, ReAct task console, biometric-gated approvals, spatial proximity hand-off, ambient vibe shifting,
universal clipboard, file transfer and remote phone control.

## Build

1. Open the folder in **Android Studio Ladybug (2024.2) or newer** (JDK 17). It creates the Gradle wrapper for you;
   or run `gradle wrapper --gradle-version 8.9` once if you have Gradle installed.
2. Run on a device/emulator with Android 10+ (minSdk 29). `./gradlew assembleDebug` / `./gradlew test`.

## First run

1. Walk through the permission wizard (mic, notifications, camera, accessibility; DND + activity are optional).
   On sideloaded builds Android 13+ needs *App info → ⋮ → Allow restricted settings* before the accessibility
   toggle can be switched on.
2. Settings → **Discover** (UDP multicast) or type the PC address → enter the 8-digit code (or **Scan QR**) → Pair & Connect.
   The bearer token is stored in `EncryptedSharedPreferences` (Keystore-backed).

## Layout

```
model/      BridgeModels.kt, AppPermission.kt         protocol + UI models
net/        JayraBridgeClient (dual WS, backoff), HostApi (REST), LanDiscoveryManager,
            FileTransferManager, FluidTextStreamingBuffer, PairingParser
service/    JayraForegroundService, JayraAccessibilityService, AudioRecordStreamer, PcmAudioPlayer,
            AmbientAudioPlayer, BiometricAuthManager, JayraClipboardListener, AppLaunchManager,
            DeviceControlManager, ProximityTracker (engine + Wi-Fi RSSI), VibeTelemetryManager, NotificationHelper
runtime/    BridgeRuntime — routes every host frame/command, owns all app state
viewmodel/  CompanionViewModel, PermissionViewModel
ui/         MainScreen, tabs (Home/Chat/Tasks/Settings), components (avatar, dock, auth sheet, QR, onboarding…)
di/         Koin module
```

## Protocol details the spec left open (assumptions — adjust on the host or in `BridgeRuntime` if yours differ)

| Area | Assumption |
|---|---|
| WS auth | `Authorization: Bearer <token>` and `X-JAYRA-Token` headers on the upgrade request |
| Command replies | `{"type":"command_result","id":<command id>,"action":…,"success":bool,"result":{…}}` on the channel the command arrived on (no per-chunk ack for `FILE_TRANSFER_CHUNK`) |
| TTS downlink | `{"type":"audio_chunk","audio":"<base64 PCM16 mono>","sampleRate":24000}`; `audio_interrupt` flushes; ignored when routing is `DESKTOP_ONLY` |
| Mic uplink | `{"type":"mic_audio_frame","audio":"<base64 PCM16 16 kHz>","sampleRate":16000}` on `/live` (falls back to `/mobile-bridge`) |
| Chat text | `{"type":"assistant_text","text":…,"delta":bool,"final":bool,"reasoning":…}`; `transcript` with `role` also accepted. Full message when `final` and not `delta` |
| User input | `{"type":"user_input","text":…}`, `{"type":"trigger_task","task":…}`, `{"type":"emergency_stop"}` (sent on both channels), `request_screenshot`, `pc_media_control`, `set_audio_routing {mode}` |
| Authorization | Phone answers `REQUEST_AUTHORIZATION` with `{"type":"authorization_response","authId","approved","method","reason"}` (REST fallback `POST /api/mobile/authorize-response`) |
| Clipboard | `{"type":"clipboard_sync","text","timestamp","source":"phone"}` both ways |
| Screenshot | `screenshot` / `screenshot_result` with base64 in `image` / `imageBase64` / `dataBase64` |
| QR payload | JSON `{host,port,code}`, a `jayra://pair?…` / `http://host:port/?code=…` URL, or a bare 8-digit code |
| Upload | `POST /api/mobile/upload` raw octet-stream, ASCII-sanitized `X-Filename`; `GET /api/mobile/files`, `GET /api/mobile/download/:name` |
| Host toggles | `GET /api/spatial-proximity/status`, `POST …/toggle`, same for `/api/vibe-shifting/*` (reads `enabled`) |

## Android platform limits worth knowing

* **Clipboard**: Android 10+ blocks background clipboard reads. PC→phone is instant; phone→PC syncs while JAYRA has window focus (it re-checks the moment you open the app).
* **Launching apps from the PC while the phone is idle**: works instantly when the accessibility service is enabled; otherwise the phone shows a tap-to-open notification.
* **Biometrics**: API 30+ uses strong biometrics or device PIN; API 29 uses weak-biometrics or PIN (platform limitation). With *Biometric guard* off the sheet falls back to a plain tap-to-approve — it never auto-approves.
* **Cleartext LAN traffic** is allowed in the network security config, but the app itself refuses non-private hosts (`NetUtil.isPrivateLan`) so the token never leaves your network.
* **Ringer finder** plays on the *alarm* stream (max volume, restored afterwards), so it rings even on silent without needing DND access. DND access is only used by Deep Focus (alarms are still allowed through).
* The microphone foreground-service type is only attached while the mic orb is live (Android 14+ forbids starting it from the background).
