# VoiceChat pilot — Android app (one app: voice + microphone)

Package: `com.ahm.voicechat`. Native Kotlin, Views (no Compose), OkHttp WebSocket.

## What it does

- Player enters ONLY a name. No game login, no password for the player himself.
- Relay URL field, default `ws://` placeholder, editable. Put your relay's
  WebSocket address here, for example `ws://your-relay-host:3000`.
- Big mic button, bottom-centre:
  - Tap = mute / unmute. Button text and the status line show the state.
  - Long-press = groups:
    - Not in a group: Create group (name + password) or Join a group
      (list from the relay, tap one, enter its password).
    - In a group: the dialog shows your name and the group, with an
      Exit button next to your name.
  - Group members hear each other from anywhere, any distance.
    Outside a group, only players within 15 blocks in the same dimension
    hear you (relay rule, not app rule).
- Verify code: press "Get verify code", then in Minecraft type
  `/voice <code>`. The pack links that code to your in-game player.
- Audio, pilot v1: raw PCM 16 kHz mono, 16-bit, via AudioRecord /
  AudioTrack. **Opus is NOT used in this pilot.** Opus would be a later
  optimisation; claiming Opus here would be false.

## Auto-sense game (the key feature)

- The service polls UsageStatsManager every 5 seconds for the foreground app.
- When `com.mojang.minecraftpe` is foreground, and a name + URL are saved,
  and mic permission is granted, and auto-sense is on: it auto-connects
  to the relay and auto-starts the mic (unless you muted manually).
- When the game leaves foreground: mic capture **pauses immediately**,
  but the WebSocket **stays connected** and playback keeps working.
  Returning to the game resumes mic capture automatically.
- When the service is stopped, everything disconnects. A fresh session
  must re-join a group (the relay drops group membership on close).

## Permissions — what, why, and who grants what

1. Microphone (runtime, asked in-app): capture your voice.
2. Usage access: auto-sense. **The app cannot grant this itself.**
   Press "Permissions" in the app, read the explanation, press
   "Open Settings", find VoiceChat, turn Usage access on.
   Without it, auto-sense does nothing; manual Connect still works.
3. Notifications / foreground service: Android requires a visible
   notification while the mic service runs in the background.
4. Battery optimisation: if the phone kills the service while playing,
   open the battery settings from the app and exempt VoiceChat.
   The app does not change this by itself.

## Build

Local build needs Android SDK + JDK 17 (not available on the build VM
used for this pilot, so local compilation was NOT verified — see the
pilot README). The supported route is GitHub Actions:

1. Push this whole pilot folder to a GitHub repo (no credentials in it).
2. The workflow in `../.github/workflows/build-apk.yml` installs
   Gradle 8.7 itself (no wrapper is shipped) and runs
   `gradle assembleDebug`, then uploads `app-debug.apk` as an artifact.
3. Download the artifact, install it on the phone, grant the
   permissions above, enter a name + relay URL, and test.

## Files

- `app/src/main/java/com/ahm/voicechat/MainActivity.kt` — UI, name/URL,
  mic tap/long-press, groups dialogs, permission explanations.
- `app/src/main/java/com/ahm/voicechat/VoiceService.kt` — foreground
  service, mic capture, playback, game watcher, pause behaviour.
- `app/src/main/java/com/ahm/voicechat/RelayClient.kt` — WebSocket
  client, protocol exactly as in `../relay/server.js`.
- `app/src/main/java/com/ahm/voicechat/GameWatcher.kt` — UsageStats
  foreground check for `com.mojang.minecraftpe`.
