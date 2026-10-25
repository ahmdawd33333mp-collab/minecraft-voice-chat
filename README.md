# Minecraft proximity voice pilot — v1

Three parts. Only the first was already built and tested; the other
two are built in this folder and are NOT yet tested live.

| Part | Status |
|---|---|
| `relay/server.js` + `relay/test.js` | DONE, tested: 13/13 tests passed. DO NOT MODIFY. |
| `android/` — unified app (voice + mic, one app) | Built here. NOT compiled locally (no Android SDK/JDK on this VM). Build via GitHub Actions. NOT tested on a phone. |
| `pack/` — Behaviour + Resource pack, Bedrock 1.26.x | Built here. Script syntax-checked only. Pack-to-relay link NOT tested on Falix (see below). |

## How it works (honest version)

- The relay (`relay/server.js`, Bun, WebSocket) forwards audio opaquely.
  Rules: muted sender reaches nobody; group members hear each other
  from anywhere; otherwise only same-dimension players within 15
  blocks hear each other. Positions come from the pack only.
- The Android app captures mic and plays received audio. Pilot audio
  is raw PCM 16 kHz mono — **not Opus**. Opus is a later optimisation.
- The pack tracks positions and handles `/voice <code>` verify. It
  does NOT capture mic audio and does NOT play live voice. Bedrock
  packs cannot do either; audio is app-side only.
- The mic button, mute, groups and speaking indicator live in the app.
  In pilot v1 the resource pack does not add a working in-game mic
  button; a true in-game mute button would need a relay change to
  broadcast mute state to pack sessions, which is deliberately NOT
  in v1 (relay untouched).

## What was verified here

- All `manifest.json` files parse as JSON.
- `pack/behaviour_pack/scripts/main.js` passes a syntax check
  (`node --check` on a `.mjs` copy; imports are not executed).
- Kotlin files were checked for balanced braces / obvious syntax
  issues only — they were NOT compiled, because no JDK / Android SDK
  exists on this VM. Do not claim a successful APK build.
- Relay files were not modified.

## What was NOT verified

- No APK was built. GitHub Actions is the build route
  (`.github/workflows/build-apk.yml`, installs Gradle 8.7 itself;
  no Gradle wrapper is shipped, so `./gradlew` will not work).
- No two-phone test, no live Falix pack test, no live relay + app +
  pack test.
- Pack-to-relay server-net link on Falix: UNTESTED. If Falix blocks
  outbound server-net, positions/verify do not flow (see
  `pack/README.md` for the fallback).

## What Ahm must do next, in order

1. Relay hosting: put `relay/server.js` on a host that runs Bun
   (Falix Bun free was refused for apps on the free plan via the API;
   see the Falix notes — do not assume a free Bun slot exists).
   Note the relay's WebSocket URL.
2. Pack: edit `RELAY_URL` in `pack/behaviour_pack/scripts/main.js`,
   install both packs on the `ahmd` world server, turn Beta APIs on,
   restart, and check `/voice status` in game.
3. App: push this folder to GitHub, run the Actions workflow,
   download the debug APK, install it on two phones.
4. Permissions on each phone: microphone, usage access (Settings,
   granted by the user — the app cannot grant it), notifications,
   battery exemption if the service gets killed.
5. Two-phone test exactly as in `pack/README.md`: verify code,
   proximity 15 blocks, far = no audio, group = anywhere,
   mute = nobody hears you.
6. Only after that test passes, decide on Opus, on a real in-game
   mute button (needs a relay change), and on paid vs free hosting.
