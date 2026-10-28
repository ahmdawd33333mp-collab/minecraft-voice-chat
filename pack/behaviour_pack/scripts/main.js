// VoiceChat Behaviour Pack — pilot v4
// Bedrock 1.26.x, Beta APIs, BDS only. @minecraft/server-net is
// pre-release and only works on Bedrock Dedicated Server, not on the
// game client and not on Realms.
//
// WHAT THIS PACK DOES — and does NOT do (read this first):
// - DOES track each player's position (x, y, z, dimension) once per
//   second and send it to the voice relay over a WebSocket opened
//   with server-net, one socket per player.
// - DOES auto-link voice by name (relay v4): the pack socket says
//   hello with pack:true and the player's name; the relay links it to
//   the app session with the same name automatically. NO in-game
//   command is needed anymore: open the app, enter your name, press
//   Connect, join the game, and voice is linked.
// - WHY NO CHAT COMMAND: on 1.26.52, Mojang REMOVED the chatSend
//   before-event (world.beforeEvents.chatSend is undefined — proven by
//   the 2026-10-26 console error "cannot read property 'subscribe' of
//   undefined at main.js:169"). Any script that subscribes to it
//   crashes at load and nothing in the pack runs. So v4 subscribes to
//   NOTHING chat-related: playerSpawn, playerLeave and runInterval
//   only. Do NOT re-add a chatSend subscription on 1.26.52.
// - DOES show voice state on nametags: a mic icon above a player
//   while he speaks, a link mark while his app is connected and
//   silent, and a plain name while he is muted (no icon at all).
//   This replaces the old "Speaking: X" action-bar text.
// - Does NOT capture microphone audio. Does NOT play live voice.
//   Bedrock packs play only pre-bundled sounds; live voice capture
//   and playback live in the Android app only. Any claim otherwise
//   would be false, and this pack makes no such claim.
// - Mute: the working mute controls are the app's mic button and its
//   notification actions (Mute / Unmute) in the notification shade
//   while playing. A HUD mic button drawn by a behaviour pack alone
//   is not possible: resource-pack HUD buttons are client UI only
//   and cannot reach the relay or the app.

import { world, system } from "@minecraft/server";
// Namespace import on purpose: if a future server-net build renames
// the `websocket` export, the whole script must NOT die at import time.
// With a namespace import, a missing export only breaks relay connect
// (caught in connectPlayer), while chat commands and the welcome
// message still work — so a relay problem can never again look like
// "the pack does not load at all".
import * as serverNet from "@minecraft/server-net";
const websocket = serverNet.websocket;

// EDIT THIS before uploading the pack: the relay's WebSocket address,
// for example "ws://your-relay-host:3000". No password or token here.
const RELAY_URL = "wss://minecraft-voice-relay.onrender.com";

// One relay socket per in-game player. The relay stores position on
// the socket session, so sharing one socket for all players would
// overwrite positions — hence one socket per player.
const sockets = new Map(); // player name -> WebSocketClient
const lastSent = new Map(); // player name -> tick of last position send
const speakingUntil = new Map(); // player name -> epoch ms of last speaking notice
const linkedNames = new Set(); // player names with a linked voice app
const mutedNames = new Set(); // player names currently muted (relay told us)
const SPEAKING_HOLD_MS = 1200; // icon stays this long after the last voice frame
const MIC_ICON = "🎤";
const LINK_ICON = "🔗";

function findPlayer(name) {
    for (const p of world.getAllPlayers()) {
        if (p.name === name) return p;
    }
    return undefined;
}

// Nametag scheme (replaces the old "Speaking: X" action-bar text):
// - speaking -> mic icon above the head: "🎤 name"
// - linked, silent -> connection mark: "🔗 name"
// - muted -> plain name, nothing at all
// (Bedrock shows the same nametag in chat and the player list too;
// there is no separate above-head-only name on BDS.)
function applyNameTag(player) {
    const base = player.name;
    let tag = base;
    if (!mutedNames.has(base)) {
        if ((speakingUntil.get(base) || 0) > Date.now()) tag = `${MIC_ICON} ${base}`;
        else if (linkedNames.has(base)) tag = `${LINK_ICON} ${base}`;
    }
    try {
        if (player.nameTag !== tag) player.nameTag = tag;
    } catch { /* nameTag read-only on some builds; icons just stay off */ }
}

function dimensionName(id) {
    if (id === "minecraft:nether") return "nether";
    if (id === "minecraft:the_end") return "the_end";
    return "overworld";
}

function sendJson(playerName, obj) {
    const client = sockets.get(playerName);
    if (!client) return false;
    try {
        client.send(JSON.stringify(obj));
        return true;
    } catch (e) {
        console.warn(`VoiceChat: send failed for ${playerName}: ${e}`);
        return false;
    }
}

async function connectPlayer(player) {
    if (sockets.has(player.name)) return;
    if (!websocket || typeof websocket.connect !== "function") {
        console.warn("VoiceChat: server-net websocket export is unavailable on this build; relay connect skipped, chat commands still work.");
        return;
    }
    try {
        const client = await websocket.connect(RELAY_URL);
        sockets.set(player.name, client);
        // Identify this socket to the relay as this in-game player.
        // Position messages on this socket are then that player's.
        client.send(JSON.stringify({ type: "hello", name: player.name, pack: true }));
        // Best-effort receive handling: the exact receive-event name
        // differs between server-net pre-release builds. If the build
        // exposes a message event, surface "speaking" notices in game.
        // If it does not, sending (position + verify) still works and
        // speaking indication stays app-side, as documented above.
        try {
            const after = client.afterEvents;
            const signal = after && (after.messageReceived || after.message);
            if (signal && signal.subscribe) {
                signal.subscribe((event) => {
                    try {
                        const text = typeof event === "string" ? event : event.message;
                        const msg = JSON.parse(text);
                        if (msg.type === "speaking" && msg.from) {
                            // Mic icon above the SPEAKER's head. The
                            // notice arrives on the listener's socket,
                            // but this script runs server-wide, so it
                            // can set the speaker's nametag directly.
                            speakingUntil.set(msg.from, Date.now() + SPEAKING_HOLD_MS);
                            const speaker = findPlayer(msg.from);
                            if (speaker) applyNameTag(speaker);
                        }
                        if (msg.type === "mute" && msg.player) {
                            if (msg.muted) mutedNames.add(msg.player);
                            else mutedNames.delete(msg.player);
                            const p = findPlayer(msg.player);
                            if (p) applyNameTag(p);
                        }
                        if (msg.type === "mute_ok") {
                            player.sendMessage(`VoiceChat: mic ${msg.muted ? "muted" : "unmuted"} (relay confirmed).`);
                        }
                        if (msg.type === "link_ok") {
                            if (msg.ok) {
                                linkedNames.add(player.name);
                                applyNameTag(player);
                            }
                            player.sendMessage(
                                msg.ok
                                    ? `VoiceChat: voice linked for ${msg.player}. Speak — nearby players on your chosen range will hear you.`
                                    : "VoiceChat: no app connected with your name yet. Open the app, enter the same name, press Connect, then type this command again."
                            );
                        }
                    } catch {
                        // Binary audio frames and non-JSON frames are ignored:
                        // the pack never plays live voice.
                    }
                });
            }
        } catch {
            // Receive handling unavailable on this build; sending continues.
        }
    } catch (e) {
        console.warn(`VoiceChat: relay connect failed for ${player.name}: ${e}`);
        console.warn("VoiceChat: check RELAY_URL, Beta APIs, and server-net permission (see pack README).");
    }
}

function disconnectPlayer(playerName) {
    const client = sockets.get(playerName);
    if (client) {
        try { client.close(); } catch { /* already closed */ }
    }
    sockets.delete(playerName);
    lastSent.delete(playerName);
    speakingUntil.delete(playerName);
    linkedNames.delete(playerName);
    mutedNames.delete(playerName);
}

// Reconnect players that are online but have no socket, and send
// each player's position once per second (20 ticks).
system.runInterval(() => {
    for (const player of world.getAllPlayers()) {
        if (!sockets.has(player.name)) {
            connectPlayer(player);
            continue;
        }
        const loc = player.location;
        sendJson(player.name, {
            type: "position",
            x: Math.round(loc.x * 100) / 100,
            y: Math.round(loc.y * 100) / 100,
            z: Math.round(loc.z * 100) / 100,
            dimension: dimensionName(player.dimension.id),
        });
        lastSent.set(player.name, system.currentTick);
    }
}, 20);

// Clear stale mic icons twice a second: a muted sender sends no
// frames at all, so its icon drops here even without a mute message.
system.runInterval(() => {
    const now = Date.now();
    for (const player of world.getAllPlayers()) {
        const until = speakingUntil.get(player.name) || 0;
        if (until && until <= now) {
            speakingUntil.delete(player.name);
            applyNameTag(player);
        }
    }
}, 10);

world.afterEvents.playerSpawn.subscribe((event) => {
    connectPlayer(event.player);
    // Proof-of-life: if the script is running at all, the player sees
    // this the moment he joins. If he does NOT see it, the script did
    // not load (Beta APIs / module version / pack activation), and no
    // chat command can work — that is the diagnostic that matters.
    try {
        event.player.sendMessage(`VoiceChat: pack loaded. Voice links automatically — open the app, enter the same name, press Connect.`);
    } catch { /* ignore */ }
});

world.afterEvents.playerLeave.subscribe((event) => {
    disconnectPlayer(event.playerName);
});

// NO CHAT COMMANDS ON 1.26.52 — DO NOT RE-ADD THEM.
// world.beforeEvents.chatSend does not exist on this build (it was
// removed by Mojang); subscribing to it throws at load time and kills
// the whole script (console error at old main.js:169, 2026-10-26).
// Voice linking is automatic by matching name (pack hello pack:true
// + app hello with the same name, linked by relay v4). Mute is via
// the app mic button and its notification actions.

console.warn("VoiceChat behaviour pack loaded. Relay: " + RELAY_URL);
