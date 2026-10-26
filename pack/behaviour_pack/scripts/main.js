// VoiceChat Behaviour Pack — pilot v3
// Bedrock 1.26.x, Beta APIs, BDS only. @minecraft/server-net is
// pre-release and only works on Bedrock Dedicated Server, not on the
// game client and not on Realms.
//
// WHAT THIS PACK DOES — and does NOT do (read this first):
// - DOES track each player's position (x, y, z, dimension) once per
//   second and send it to the voice relay over a WebSocket opened
//   with server-net, one socket per player.
// - DOES handle the single command /<own name> (e.g. /ahmdawd3): it
//   sends {type:"link_by_name", player} to relay v3, which links the
//   app session with the same name to this pack socket — no code.
//   /voice with no argument does the same thing. The old
//   /voice <6-digit code> verify path is kept as a fallback.
// - DOES surface relay messages it receives on a player's socket
//   (for example a "speaking" notice) as an in-game action bar line.
// - Does NOT capture microphone audio. Does NOT play live voice.
//   Bedrock packs play only pre-bundled sounds; live voice capture
//   and playback live in the Android app only. Any claim otherwise
//   would be false, and this pack makes no such claim.
// - Mute: /voice mute and /voice unmute in game chat send a mute
//   message on this player's pack socket; the relay applies it to the
//   LINKED app session (the one sending audio) and tells the app via
//   mute_ok, so the app's mic button and notification stay in sync.
//   A HUD mic button drawn by a behaviour pack alone is not possible:
//   resource-pack HUD buttons are client UI only and cannot reach the
//   relay or the app; the working in-game controls are these chat
//   commands, and the working while-playing button is the app's
//   notification action (Mute / Unmute) in the notification shade.

import { world, system } from "@minecraft/server";
import { websocket } from "@minecraft/server-net";

// EDIT THIS before uploading the pack: the relay's WebSocket address,
// for example "ws://your-relay-host:3000". No password or token here.
const RELAY_URL = "wss://minecraft-voice-relay.onrender.com";

// One relay socket per in-game player. The relay stores position on
// the socket session, so sharing one socket for all players would
// overwrite positions — hence one socket per player.
const sockets = new Map(); // player name -> WebSocketClient
const lastSent = new Map(); // player name -> tick of last position send

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
    try {
        const client = await websocket.connect(RELAY_URL);
        sockets.set(player.name, client);
        // Identify this socket to the relay as this in-game player.
        // Position messages on this socket are then that player's.
        client.send(JSON.stringify({ type: "hello", name: player.name }));
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
                            player.onScreenDisplay.setActionBar(`Speaking: ${msg.from}`);
                        }
                        if (msg.type === "mute_ok") {
                            player.sendMessage(`VoiceChat: mic ${msg.muted ? "muted" : "unmuted"} (relay confirmed).`);
                        }
                        if (msg.type === "link_ok") {
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

world.afterEvents.playerSpawn.subscribe((event) => {
    connectPlayer(event.player);
});

world.afterEvents.playerLeave.subscribe((event) => {
    disconnectPlayer(event.playerName);
});

// Single command: /<own name> links voice by name, no code.
// /voice with no argument does the same. /voice <code> is the fallback.
// Cancelled from chat so the command is not broadcast to other players.
world.beforeEvents.chatSend.subscribe((event) => {
    const message = event.message.trim();
    const player = event.sender;
    const isOwnNameCommand = message.toLowerCase() === "/" + player.name.toLowerCase();
    if (isOwnNameCommand || message === "/voice") {
        event.cancel = true;
        const sendLink = () => {
            const ok = sendJson(player.name, { type: "link_by_name", player: player.name });
            player.sendMessage(
                ok
                    ? "VoiceChat: linking voice by name..."
                    : "VoiceChat: relay not connected yet, try again in a few seconds."
            );
        };
        if (sockets.has(player.name)) sendLink();
        else connectPlayer(player).then(sendLink);
        return;
    }
    if (!message.startsWith("/voice")) return;
    event.cancel = true;
    const parts = message.split(/\s+/);
    if (parts.length === 2 && (parts[1] === "mute" || parts[1] === "unmute")) {
        const muted = parts[1] === "mute";
        const doMute = () => {
            const ok = sendJson(player.name, { type: "mute", muted: muted });
            player.sendMessage(
                ok
                    ? `VoiceChat: ${muted ? "mute" : "unmute"} command sent to the relay.`
                    : "VoiceChat: relay not connected yet, try again in a few seconds."
            );
        };
        if (sockets.has(player.name)) doMute();
        else connectPlayer(player).then(doMute);
        return;
    }
    if (parts.length === 2 && parts[1] === "status") {
        player.sendMessage(
            `VoiceChat: relay socket ${sockets.has(player.name) ? "connected" : "NOT connected"} for ${player.name}.`
        );
        return;
    }
    if (parts.length !== 2 || !/^\d{6}$/.test(parts[1])) {
        player.sendMessage("VoiceChat usage: /<your name> to link voice | /voice mute | /voice unmute | /voice status | /voice <6-digit code> (fallback)");
        return;
    }
    const code = parts[1];
    const sendVerify = () => {
        const ok = sendJson(player.name, { type: "verify", code: code, player: player.name });
        player.sendMessage(
            ok
                ? "VoiceChat: verify code sent to the relay."
                : "VoiceChat: relay not connected yet, try again in a few seconds."
        );
    };
    if (sockets.has(player.name)) {
        sendVerify();
    } else {
        connectPlayer(player).then(sendVerify);
    }
});

console.warn("VoiceChat behaviour pack loaded. Relay: " + RELAY_URL);
