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
// - DOES offer in-game voice controls WITHOUT leaving the game:
//   ONE command, /s, opens the groups panel (create/browse groups,
//   normal range with a free number, global toggle). A Voice Menu
//   item (echo shard, given on join) opens the same panel on
//   right-click. A resource-pack HUD button like Java mods have
//   cannot do this on Bedrock: client UI cannot reach the script
//   or the relay, so the item + /s panel are the working
//   equivalents. (Earlier /v, /vglobal and /vgroup commands were
//   removed on the player's order; everything they did lives in
//   the /s panel.)
// - Does NOT capture microphone audio. Does NOT play live voice.
//   Bedrock packs play only pre-bundled sounds; live voice capture
//   and playback live in the Android app only. Any claim otherwise
//   would be false, and this pack makes no such claim.
// - Mute: the working mute controls are the app's mic button and its
//   notification actions (Mute / Unmute) in the notification shade
//   while playing. A HUD mic button drawn by a behaviour pack alone
//   is not possible: resource-pack HUD buttons are client UI only
//   and cannot reach the relay or the app.

import { world, system, ItemStack, CustomCommandStatus, CommandPermissionLevel } from "@minecraft/server";
// Namespace imports on purpose: if a future build renames an export,
// the whole script must NOT die at import time. A missing export only
// breaks the feature that needs it, while everything else keeps
// working — so one relay/UI problem can never again look like "the
// pack does not load at all".
import * as serverNet from "@minecraft/server-net";
const websocket = serverNet.websocket;
import * as serverUi from "@minecraft/server-ui";
const ActionFormData = serverUi.ActionFormData;
const ModalFormData = serverUi.ModalFormData;

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

// Per-player voice settings as last confirmed by the relay. These
// only feed the menu display; the relay is the source of truth.
const voiceState = new Map(); // player name -> { range, global, group }
function stateFor(name) {
    let st = voiceState.get(name);
    if (!st) {
        st = { range: null, global: false, group: null };
        voiceState.set(name, st);
    }
    return st;
}

// ---- In-game menus (no leaving the game) ----
// The ONLY menu is /s (groups panel). No /v menu and no /v commands
// remain — /s covers range, global and groups; /c toggles mute,
// /cc opens the panel. Mute also stays on the app mic button and
// its notification actions. (The old Voice Menu item was removed on
// the player's order; MENU_ITEM_* below only identify leftovers to
// clean out of inventories.)
// ActionForm = button menu, ModalForm = input fields. Both come from
// @minecraft/server-ui, a stable module — no Beta-APIs permission
// needed beyond what the pack already requires for server-net.
const MENU_ITEM_ID = "minecraft:echo_shard";
const MENU_ITEM_NAME = "Voice Menu";

// ---- Groups panel (/s): the only menu ----
async function openGroupsMenu(player) {
    if (!ActionFormData) {
        player.sendMessage("VoiceChat: forms are unavailable on this server build.");
        return;
    }
    const form = new ActionFormData();
    form.title("VoiceChat: groups");
    form.body("Create a group, or browse existing groups and join one.");
    form.button("Create group");
    form.button("Browse groups");
    form.button("Normal range (blocks)");
    let res;
    try {
        res = await form.show(player);
    } catch {
        return;
    }
    if (res.canceled) return;
    if (res.selection === 0) return openCreateGroupPanel(player);
    if (res.selection === 1) {
        sendJson(player.name, { type: "group_list" });
        return;
    }
    if (res.selection === 2) return openRangeForm(player);
}

// Groups are ALWAYS unlimited: members hear each other wherever
// they are. No range field here by design — the normal-mode range
// (a free number, not presets) lives in the Range form instead.
async function openCreateGroupPanel(player) {
    if (!ModalFormData) return;
    const form = new ModalFormData();
    form.title("VoiceChat: create group");
    form.textField("Group name", "e.g. friends");
    form.textField("Password", "password others must enter");
    let res;
    try {
        res = await form.show(player);
    } catch {
        return;
    }
    if (res.canceled) return;
    const [name, pw] = res.formValues;
    if (!name || !pw) {
        player.sendMessage("VoiceChat: enter a group name and a password.");
        return;
    }
    sendJson(player.name, { type: "group_create", name, password: pw });
}

async function openGroupListForm(player, names) {
    if (!names.length) {
        player.sendMessage("VoiceChat: no groups yet — create one first.");
        return;
    }
    const form = new ActionFormData();
    form.title("VoiceChat: groups");
    form.body("Tap a group, then enter its password.");
    for (const n of names) form.button(n);
    let res;
    try {
        res = await form.show(player);
    } catch {
        return;
    }
    if (res.canceled) return;
    const name = names[res.selection];
    const pwForm = new ModalFormData();
    pwForm.title(`Join "${name}"`);
    pwForm.textField("Password", "password set by the group creator");
    let pwRes;
    try {
        pwRes = await pwForm.show(player);
    } catch {
        return;
    }
    if (pwRes.canceled) return;
    const [pw] = pwRes.formValues;
    if (!pw) {
        player.sendMessage("VoiceChat: enter the password.");
        return;
    }
    sendJson(player.name, { type: "group_join", name, password: pw });
}

async function openRangeForm(player) {
    if (!ModalFormData) return;
    const st = stateFor(player.name);
    const form = new ModalFormData();
    form.title("VoiceChat: range");
    form.textField("Range in blocks (1-2000)", "e.g. 50", st.range ? String(st.range) : "");
    form.toggle("Global — heard anywhere in the world", st.global);
    let res;
    try {
        res = await form.show(player);
    } catch {
        return;
    }
    if (res.canceled) return;
    const [rangeText, globalOn] = res.formValues;
    const n = Math.floor(Number(rangeText));
    // Range first, global second: the relay clears global when a
    // range is set, so sending global last makes it win when on.
    if (Number.isFinite(n) && n >= 1) sendJson(player.name, { type: "range", range: Math.min(2000, n) });
    if (globalOn !== st.global) sendJson(player.name, { type: "global", global: globalOn });
}

// The Voice Menu item was REMOVED on the player's order. This only
// cleans up copies already sitting in inventories (best effort —
// if clearing fails, the leftover item simply does nothing, since
// the itemUse handler below was removed too).
function removeMenuItem(player) {
    try {
        const inv = player.getComponent("inventory")?.container;
        if (!inv) return;
        for (let i = 0; i < inv.size; i++) {
            const it = inv.getItem(i);
            if (it && it.typeId === MENU_ITEM_ID && it.nameTag === MENU_ITEM_NAME) {
                try {
                    inv.setItem(i, undefined);
                } catch {
                    // leave it; without the handler it does nothing
                }
            }
        }
    } catch {
        // ignore
    }
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
                        if (msg.type === "range_ok" && msg.range !== undefined) {
                            const st = stateFor(player.name);
                            st.range = msg.range;
                            st.global = false;
                            player.sendMessage(`VoiceChat: range set to ${msg.range} blocks (proximity).`);
                        }
                        if (msg.type === "global_ok") {
                            const st = stateFor(player.name);
                            st.global = Boolean(msg.global);
                            player.sendMessage(
                                st.global
                                    ? "VoiceChat: global voice ON — you are heard anywhere in the world."
                                    : "VoiceChat: global voice OFF — back to proximity range."
                            );
                        }
                        if (msg.type === "groups" && Array.isArray(msg.groups)) {
                            openGroupListForm(player, msg.groups);
                        }
                        if (msg.type === "group_ok") {
                            const st = stateFor(player.name);
                            if (msg.ok) {
                                st.group = msg.group || null;
                                if (msg.group) {
                                    player.sendMessage(
                                        msg.range
                                            ? `VoiceChat: you are in group "${msg.group}" — members hear each other within ${msg.range} blocks.`
                                            : `VoiceChat: you are in group "${msg.group}" — members hear each other anywhere.`
                                    );
                                } else {
                                    player.sendMessage("VoiceChat: you left the group — back to proximity range.");
                                }
                            } else {
                                player.sendMessage(
                                    msg.reason === "bad_name_or_password"
                                        ? "VoiceChat: wrong owner name or password — group not joined."
                                        : "VoiceChat: could not create that group."
                                );
                            }
                        }
                        if (msg.type === "voice_error" && msg.message) {
                            player.sendMessage(`VoiceChat: ${msg.message}`);
                        }
                        if (msg.type === "mute_ok") {
                            // Pack-originated mutes (e.g. /c) arrive here, not
                            // as a "mute" broadcast — track them too, or the
                            // nametag would show the link mark instead of
                            // the plain name the player asked for when muted.
                            if (msg.muted) mutedNames.add(player.name);
                            else mutedNames.delete(player.name);
                            applyNameTag(player);
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
    voiceState.delete(playerName);
}

// ---- Custom chat command (/s only) ----
// NOTE: this is NOT a chatSend interception (Mojang removed that
// event) — it is a real command registered with the script API's
// custom command registry, so it works from the chat box like any
// command, with cheats NOT required and permission level Any, meaning
// every player can use it, not just operators.
system.beforeEvents.startup.subscribe((event) => {
    const registry = event.customCommandRegistry;
    if (!registry) return;
    const register = (name, def, cb) => {
        try {
            registry.registerCommand(def, cb);
        } catch (e) {
            console.warn(`VoiceChat: command /${name} not registered: ${e}`);
        }
    };
    const playerOf = (origin) => {
        const e = origin.sourceEntity;
        return e && e.typeId === "minecraft:player" ? e : undefined;
    };
    const fail = (message) => ({ status: CustomCommandStatus.Failure, message });
    const noRelay = () => fail("VoiceChat: relay is not connected — try again in a few seconds.");
    const notPlayer = () => fail("VoiceChat: run this in game as a player.");
    // /s is the main command. /v, /vglobal and /vgroup were removed
    // on the player's order — everything they did lives in the /s
    // panel (range + global toggle under Normal range, groups under
    // Create/Browse), so nothing is lost by removing them.
    // /c toggles mute, /cc opens the /s panel — short commands the
    // player asked for, same Any permission, no cheats required.
    const cDef = (name) => ({
        name,
        description: "VoiceChat: mute/unmute your mic",
        cheatsRequired: false,
        permissionLevel: CommandPermissionLevel.Any,
    });
    const cCb = (origin) => {
        const player = playerOf(origin);
        if (!player) return notPlayer();
        const target = !mutedNames.has(player.name);
        if (!sendJson(player.name, { type: "mute", muted: target })) return noRelay();
        return undefined;
    };
    register("c", cDef("c"), cCb);
    register("voice:c", cDef("voice:c"), cCb);
    const ccDef = (name) => ({
        name,
        description: "VoiceChat: open the groups panel",
        cheatsRequired: false,
        permissionLevel: CommandPermissionLevel.Any,
    });
    const ccCb = (origin) => {
        const player = playerOf(origin);
        if (!player) return notPlayer();
        openGroupsMenu(player);
        return undefined;
    };
    register("cc", ccDef("cc"), ccCb);
    register("voice:cc", ccDef("voice:cc"), ccCb);
    const sDef = (name) => ({
        name,
        description: "VoiceChat: open the groups panel (create or browse groups)",
        cheatsRequired: false,
        permissionLevel: CommandPermissionLevel.Any,
    });
    const sCb = (origin) => {
        const player = playerOf(origin);
        if (!player) return notPlayer();
        openGroupsMenu(player);
        return undefined;
    };
    register("s", sDef("s"), sCb);
    register("voice:s", sDef("voice:s"), sCb);
});

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
    removeMenuItem(event.player);
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

// (The Voice Menu item handler was removed with the item itself:
// /s and /cc are now the only ways to open the panel.)

// NO CHAT COMMANDS ON 1.26.52 — DO NOT RE-ADD THEM.
// world.beforeEvents.chatSend does not exist on this build (it was
// removed by Mojang); subscribing to it throws at load time and kills
// the whole script (console error at old main.js:169, 2026-10-26).
// Voice linking is automatic by matching name (pack hello pack:true
// + app hello with the same name, linked by relay v4). Mute is via
// the app mic button and its notification actions.

console.warn("VoiceChat behaviour pack loaded. Relay: " + RELAY_URL);
