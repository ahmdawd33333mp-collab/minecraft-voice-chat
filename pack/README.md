# VoiceChat pilot — Minecraft pack (Bedrock 1.26.x)

Two packs, installed together:
- `behaviour_pack/` — script: positions + `/voice` verify + relay socket.
- `resource_pack/` — companion pack. In pilot v1 it carries no in-game
  mic audio and no in-game mic button: mic, mute and groups live in
  the Android app. The resource pack is present so the addon installs
  as a pair, as Bedrock expects, and for future HUD work.

## Honest limits (do not skip)

- This pack does NOT capture mic audio and does NOT play live voice.
  Bedrock packs play only pre-bundled sounds. Audio capture/playback
  is Android-app only.
- Mute in relay v1 is stored on the app session and is not pushed to
  pack sockets, so the pack cannot reliably display another player's
  mute state in v1. A muted player's audio is simply not forwarded.
- **The pack-to-relay link is UNTESTED on Falix.** server-net only
  works on Bedrock Dedicated Server, is pre-release, and needs the
  `allowed_modules` permission (see below). If Falix blocks outbound
  server-net, connect fails, positions/verify do not flow, and
  proximity routing does not work. That failure is logged in the
  server console by the pack; it does not crash the world.

## Before uploading — one edit

In `behaviour_pack/scripts/main.js`, replace:

```
const RELAY_URL = "ws://REPLACE-WITH-YOUR-RELAY-URL";
```

with your relay's real WebSocket address, for example
`ws://your-relay-host:3000`. No password or token goes in the pack.

## Install on the ahmd Falix world server

1. In the Falix panel, open the `ahmd` world server (the existing
   Bedrock Vanilla 1.26.52 server, `ahmd.falixsrv.me:33947`).
2. Upload/install both packs:
   - Falix panel: Addons tab, or File Manager: put the behaviour pack
     under the behaviour packs folder and the resource pack under the
     resource packs folder, then enable both for the world.
   - If the panel only accepts `.mcpack` / `.mcaddon` files, zip each
     pack folder's CONTENTS (not the folder itself) and rename the zip
     to `.mcpack`, one file per pack.
3. Turn on the **Beta APIs** experiment for the world. The script
   uses Beta APIs (`@minecraft/server` beta + `@minecraft/server-net`
   beta); without it the script does not run.
4. server-net permission: on a BDS you control, add
   `"@minecraft/server-net"` to `allowed_modules` in
   `config/default/permissions.json` (a copy is in
   `pack/permissions.json.example`). On Falix, if you cannot edit
   that file, that is exactly the Falix fallback case above: test
   and watch the console for "relay connect failed".
5. Restart the server from Console.
6. In game, check `/voice status` — it should say the relay socket
   is connected for your player name. If it says NOT connected, the
   link is blocked or the URL is wrong; fix the URL / permission
   before testing voice.

## Two-phone test (after the app + relay are up)

1. Both phones: install the app, enter a name, enter the relay URL,
   grant mic + usage access, connect.
2. Both phones: join the `ahmd` world.
3. Phone A in the app: "Get verify code", then in game type
   `/voice <code>`. The app should say "Verified in-game".
4. Repeat for phone B.
5. Stand within 15 blocks in the same dimension, talk: B hears A.
   Walk 100 blocks away: B stops hearing A.
6. Long-press mic, create a group, have B join with the password:
   B hears A from anywhere. Long-press again, Exit: proximity rules
   return.
7. Tap mic (mute): nobody hears you until you unmute.

## Files

- `behaviour_pack/manifest.json` — BP header, script module,
  dependencies on `@minecraft/server` beta and `@minecraft/server-net`
  beta, plus the resource pack.
- `behaviour_pack/scripts/main.js` — one relay WebSocket per player,
  position every second, `/voice <code>` and `/voice status`.
- `resource_pack/manifest.json` — RP header, paired to the BP.
- `permissions.json.example` — BDS `allowed_modules` sample.
