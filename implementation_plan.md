# Implementation Plan - Tripper Dash Alternative Background Application

The goal of this task is to create a robust, background-friendly alternative app to connect to the Royal Enfield Bear 650 Tripper Dash. The official RE app runs slowly, disconnects frequently, requires keeping the phone foregrounded, and lacks customizable telemetry or navigation simulation. 

Our application will run as an **Android Foreground Service**, which keeps the Wi-Fi connection and UDP status stream alive in the background (even when the screen is locked). It will feature a premium Jetpack Compose dashboard UI, automatic media track metadata syncing via a Notification Listener, telemetry control simulations, and a Turn-by-Turn navigation simulator.

---

## User Review Required

> [!IMPORTANT]
> The app requires the following system permissions to work correctly:
> 1. **Location Permissions** (`ACCESS_FINE_LOCATION`): Required on Android 10+ to scan and connect to peer-to-peer Wi-Fi networks (the Tripper Dash).
> 2. **Notification Access Permission** (`NotificationListenerService`): Required to automatically read currently playing track details (Spotify, YouTube, etc.) to sync with the dash. If not granted, the app will fall back to manual media controls on the UI.
> 3. **Foreground Service Permissions** (`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`): Necessary on Android 14+ to keep the connection alive while backgrounded.

---

## Proposed Changes

### 1. Permissions & Services Configuration

#### [MODIFY] [AndroidManifest.xml](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/AndroidManifest.xml)
- Add required permissions:
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` (required for API 34+ / Android 14+)
  - `android.permission.POST_NOTIFICATIONS` (required for Android 13+)
  - `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (for the media session tracking)
- Declare the foreground service `com.opdash.connection.DashConnectionService` with `foregroundServiceType="connectedDevice"`.
- Declare the notification listener service `com.opdash.media.MediaNotificationListener`.

---

### 2. Communication Protocol & Packet Builder

#### [MODIFY] [K1GPacket.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/protocol/K1GPacket.kt)
- Define a serialization helper to construct the "K1G" frame format:
  - Byte 0-1: Total packet length (big-endian short).
  - Byte 2-3: Element count or `00 02` type (big-endian short).
  - Byte 4-7: `00 00 00 00` (4 bytes padding/constants).
  - Byte 8-9: `02 01` (2 bytes constants).
  - Byte 10-11: `00 05` (length of identifier).
  - Byte 12-15: `4B 31 47 20` (ASCII "K1G ").
  - Byte 16: Sequence counter (`0` to `255`), incremented for every transmitted packet.
  - Byte 17+: Payload bytes containing concatenated TLV fields.
- Define helper methods for compiling TLV (Tag-Length-Value) blocks for battery, charging, cellular signal, GPS, weather, and turn-by-turn navigation data.

---

### 3. Foreground Connection Service

#### [NEW] [DashConnectionService.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/connection/DashConnectionService.kt)
- A background-friendly foreground service that:
  - Starts a persistent notification with "Connected to Tripper Dash" and action buttons.
  - Initiates the Wi-Fi connection using `ConnectivityManager.requestNetwork(...)`.
  - Once Wi-Fi is connected, automatically retrieves the Network object, binds the process/sockets using `network.bindSocket(...)` to force traffic through the dash Wi-Fi interface (bypassing mobile data).
  - Launches a UDP Socket Sender (local port 2000, sending to broadcast) and a UDP Socket Receiver (local port 2002, listening to responses).
  - Maintains an auto-incrementing sequence counter.
  - Runs a 1-second interval Timer that compiles and transmits the standard status/telemetry packet.
  - Exposes connection status (`DISCONNECTED`, `CONNECTING_WIFI`, `RUNNING_UDP`, `CONNECTED`, etc.) via standard LiveData/StateFlow or a service binder.

---

### 4. Automatic Media Syncing

#### [NEW] [MediaNotificationListener.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/media/MediaNotificationListener.kt)
- Subclasses `NotificationListenerService`.
- Listens to active media playback changes on the phone.
- Extracts `android.media.metadata.TITLE`, `android.media.metadata.ARTIST`, and `android.media.metadata.ALBUM`.
- Safely formats the media details into the `050D` music metadata TLV:
  - Format: `album + \0 + title + \0 + artist`
  - Maximum size truncated to 19 characters per field (mirroring the official app's truncation constraint).
  - Enqueues the packet to the `DashConnectionService` UDP queue.

---

### 5. Connection Manager Bridge

#### [MODIFY] [ConnectionManager.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/logging/ConnectionManager.kt)
- Shift connection operations from direct Wi-Fi logic to managing the lifecycle of the `DashConnectionService`.
- Add `connect(ssid, password)` to launch the service with connection parameters.
- Add `disconnect()` to stop the foreground service.
- Bind to the service to receive live state updates and logs.

---

### 6. ViewModel & Polished Premium UI

#### [MODIFY] [MainViewModel.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/ui/MainViewModel.kt)
- Expose state properties for connection state, logs, manual telemetry control values, manual media entries, and turn-by-turn simulation inputs.
- Implement simulation triggers:
  - `sendManualTelemetry()` (force send mock battery, charging, cellular, GPS status to the dash).
  - `sendManualMedia(title, artist, album, isPlaying)` (send mock song details).
  - `simulateNavigationStep(destination, maneuver, distance, unit)` (send custom turn-by-turn step).

#### [MODIFY] [MainScreen.kt](file:///home/chitresh/AndroidStudioProjects/OPDashV2/app/src/main/java/com/opdash/ui/MainScreen.kt)
- Replace the basic styling with a premium, motorcycle-dashboard themed visual design:
  - Vibrant dark-mode color scheme (deep carbons, neon amber accents, glowing status indicators).
  - Dashboard card: clean inputs for SSID and Passphrase, with large status gauges.
  - Telemetry Simulator card: controls to mock battery percentage, charging state, GPS state, and cellular signal bars.
  - Media Session card: shows currently playing music (live or manual mock inputs), play/pause triggers, and track info.
  - Turn-by-Turn Simulator card: select maneuver icons, input distance, and send simulated directions to the dash.
  - Logger card: a neat terminal-styled logs console showing transmitted packet hexes and connection events.

---

## Verification Plan

### Automated & Manual Verification
1. **Compilation**: Run gradle build `./gradlew assembleDebug` to ensure all Kotlin and Android files build successfully.
2. **Service Lifecycles**: Verify that launching the application starts the Foreground Service, displays a persistent notification, and successfully establishes connections.
3. **UDP Packet Serialization**: Add unit tests or log output validation verifying that the constructed packet structures (header, element count, TLVs, sequence byte) match the reverse-engineered RE specification.
