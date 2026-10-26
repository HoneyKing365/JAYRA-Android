# JAYRA Companion (Android)

Native Kotlin + Jetpack Compose companion for the JAYRA desktop host, implementing
`JAYRA_ANDROID_BRIDGE_SPEC.md` v2.0.0.

## Layout
```
app/src/main/java/ai/jayra/companion/
  protocol/       command schema + on-device validation
  security/       Keystore SecretStore, private-LAN policy, Redactor
  data/           Pairing (QR parser + encrypted store), Settings
  net/            ReconnectingSocket (backoff), BridgeController (/mobile-bridge),
                  LiveSession (/live), HostDiscovery (UDP multicast)
  commands/       Notification, AppLauncher, Media, PhoneFinder, Confirmation, Router
  telemetry/      StatusCollector
  accessibility/  optional, narrowly scoped service
  service/        BridgeService (foreground), receivers
  audio/          MicStreamer (16 kHz), PcmPlayer (24 kHz)
  ui/             Compose screens + ViewModel
```

## Build
Requirements: JDK 17, Android Studio (Ladybug or newer) with Android SDK 35.

```bash
cd jayra-android
gradle wrapper --gradle-version 8.9      # one time, if gradlew is missing
./gradlew :app:testDebugUnitTest         # unit tests
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open the folder in Android Studio and press Run.

Release APK: create `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), generate a key with
`keytool -genkeypair -v -keystore jayra.jks -alias jayra -keyalg RSA -keysize 2048 -validity 10000`,
then `./gradlew :app:assembleRelease`. Without it, release builds are debug-signed.

## Pair
1. JAYRA desktop: enable Network Access (host binds 0.0.0.0:3000).
2. Phone on the same Wi-Fi, three ways:
   - scan the pairing QR (token is read from the URL fragment locally),
   - type the PC address + the 8-digit code (`POST /api/mobile/pair` -> `bridgeToken`),
   - or paste the pairing link.
   The token goes only in the `X-JAYRA-Token` header and is stored Keystore-encrypted.
3. The link starts automatically; a foreground notification shows its status.
The host token is persistent, so reboots and host restarts don't require re-pairing.

## UX map
- First launch: 3-step permission wizard (notifications, optional phone control, microphone).
  Steps can be skipped; Settings -> Permissions status lets you fix them later.
- Top bar: logo + live status (shape + text + color), emergency STOP. Drawer: endpoint, pause/resume,
  "Disconnect & forget PC". Tabs: Home, Controls, Chat (chat + terminal log), Settings.
- Hold the glowing mic button to talk (TalkBack: double-tap toggles).

## Notes
- Plain ws:// is refused for non-private hosts. Use wss:// beyond a trusted LAN.
- Accessibility is off until you enable it in Android settings; Pause switch is in-app.
- Background app launches are blocked by Android; JAYRA posts a tap-to-open notification instead.
- Some OEMs kill background apps: exempt the app from battery optimization.
