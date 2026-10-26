// Minecraft proximity voice relay - pilot v4
// Runtime: Bun. Protocol: JSON text messages, plus binary audio frames.
// Audio is opaque: the relay never decodes it. It forwards a frame only to
// recipients allowed by the rules below, with a `from` name attached.
// Rules, in order:
//  1. Sender muted -> nobody receives.
//  2. Sender in a group -> only group members receive, any distance, any dimension.
//  3. Otherwise -> only players in the same dimension within the SENDER's
//     chosen range receive (range is per app session, default 15, the app
//     lets the player pick 20/30/50/100/200/500/1000/2000).
// Identity v4 (automatic, no command): the game pack says hello with
// pack:true and the in-game player name; the app says hello with the
// player's entered name. When a pack socket and an app session share
// the same name (case-insensitive), the relay links them automatically
// — no chat command, no code. This replaces v3's /<name> command,
// because Mojang REMOVED world.beforeEvents.chatSend on 1.26.52, so
// no script can intercept chat commands there at all (proven by the
// 2026-10-26 console error at old main.js:169). The old link_by_name
// and /voice <code> verify paths are kept as fallbacks for any pack
// that still sends them.
// Positions come from the pack (authoritative). The app never invents a position.

import { createHash } from "node:crypto";

const PORT = Number(process.env.PORT || 7860);
const DEFAULT_PROXIMITY = 15; // blocks, like the Java mod default

const clients = new Map(); // ws -> session
const groups = new Map(); // groupName -> { passwordHash, members:Set<ws> }
const codes = new Map(); // code -> ws

function hashPassword(pw) {
  return createHash("sha256").update(String(pw)).digest("hex");
}
function send(ws, obj) {
  try { ws.send(JSON.stringify(obj)); } catch {}
}
function clampRange(v) {
  const n = Math.floor(Number(v));
  if (!Number.isFinite(n)) return DEFAULT_PROXIMITY;
  return Math.min(2000, Math.max(1, n));
}
// Auto-link: a pack socket and an app session with the same name belong
// to the same player. Link them in either order of arrival, once.
function tryAutoLink(s) {
  if (!s.name || s.appWs || s.packWs) return;
  for (const [otherWs, other] of clients) {
    if (otherWs === s.ws || !other.name) continue;
    if (other.name.toLowerCase() !== s.name.toLowerCase()) continue;
    if (other.isPack === s.isPack) continue;
    const pack = s.isPack ? s : other;
    const app = s.isPack ? other : s;
    app.player = pack.name;
    app.packWs = pack.ws;
    pack.appWs = app.ws;
    send(pack.ws, { type: "link_ok", ok: true, player: pack.name });
    send(app.ws, { type: "linked", player: pack.name });
    return;
  }
}
function distance(a, b) {
  const dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}
// A session's position: its own, or (for an app session) its linked pack
// socket's position, found by the verified in-game player name.
function effectivePos(s) {
  if (s.pos) return s.pos;
  if (s.player) {
    for (const [, other] of clients) {
      if (other.name === s.player && other.pos) return other.pos;
    }
  }
  return null;
}
function recipientsFor(sender) {
  const out = [];
  const senderPos = effectivePos(sender);
  const range = sender.range || DEFAULT_PROXIMITY;
  for (const [ws, s] of clients) {
    if (ws === sender.ws) continue;
    if (!s.name) continue;
    if (sender.muted) continue;
    if (sender.group) {
      if (s.group === sender.group) out.push(ws);
      continue;
    }
    const targetPos = effectivePos(s);
    if (!senderPos || !targetPos) continue;
    if (senderPos.dimension !== targetPos.dimension) continue;
    if (distance(senderPos, targetPos) <= range) out.push(ws);
  }
  return out;
}

const server = Bun.serve({
  port: PORT,
  fetch(req, srv) {
    if (srv.upgrade(req)) return undefined;
    return new Response("minecraft-voice-relay v1 ok", { status: 200 });
  },
  websocket: {
    open(ws) {
      clients.set(ws, { ws, name: null, player: null, pos: null, muted: false, group: null, appWs: null, packWs: null, isPack: false, range: DEFAULT_PROXIMITY });
    },
    message(ws, message) {
      const s = clients.get(ws);
      if (!s) return;
      // Binary frame = audio. Forward by the routing rules, with sender name.
      if (message instanceof ArrayBuffer || message instanceof Uint8Array) {
        if (!s.name || s.muted) return;
        const targets = recipientsFor(s);
        for (const t of targets) {
          try { t.send(JSON.stringify({ type: "speaking", from: s.name, speaking: true })); } catch {}
          try { t.send(message); } catch {}
        }
        return;
      }
      let msg;
      try { msg = JSON.parse(String(message)); } catch { return; }
      if (msg.type === "hello") {
        s.name = String(msg.name || "").slice(0, 32);
        if (msg.pack === true) s.isPack = true;
        if (msg.range !== undefined) s.range = clampRange(msg.range);
        send(ws, { type: "hello_ok", name: s.name, proximity: s.range });
        tryAutoLink(s);
      } else if (msg.type === "range") {
        s.range = clampRange(msg.range);
        send(ws, { type: "range_ok", range: s.range });
      } else if (msg.type === "request_code") {
        const code = String(Math.floor(100000 + Math.random() * 900000));
        codes.set(code, ws);
        send(ws, { type: "code", code });
      } else if (msg.type === "link_by_name") {
        // Sent by the game pack when the player types /<own name> in chat.
        // Link the app session with the same name (not a pack socket) to
        // this pack socket, so positions route to the app session.
        const player = String(msg.player || "");
        s.isPack = true;
        let appWs = null;
        for (const [otherWs, other] of clients) {
          if (otherWs === ws) continue;
          if (!other.name) continue;
          if (other.isPack) continue;
          if (other.name.toLowerCase() === player.toLowerCase()) { appWs = otherWs; break; }
        }
        if (!appWs) { send(ws, { type: "link_ok", ok: false, reason: "no_app_with_that_name" }); return; }
        const app = clients.get(appWs);
        if (app) { app.player = player; app.packWs = ws; }
        s.appWs = appWs;
        send(ws, { type: "link_ok", ok: true, player });
        send(appWs, { type: "linked", player });
      } else if (msg.type === "verify") {
        // Fallback, sent by the game pack: {code, player}
        const appWs = codes.get(String(msg.code));
        if (!appWs) { send(ws, { type: "verify_ok", ok: false, reason: "bad_code" }); return; }
        const app = clients.get(appWs);
        s.isPack = true;
        if (app) { app.player = String(msg.player || ""); app.packWs = ws; s.appWs = appWs; }
        codes.delete(String(msg.code));
        send(ws, { type: "verify_ok", ok: true, player: String(msg.player || "") });
        send(appWs, { type: "verified", player: String(msg.player || "") });
      } else if (msg.type === "position") {
        s.isPack = true;
        s.pos = { x: Number(msg.x), y: Number(msg.y), z: Number(msg.z), dimension: String(msg.dimension || "overworld") };
        tryAutoLink(s);
      } else if (msg.type === "mute") {
        // A mute from a linked pack socket (/voice mute in game) mutes the
        // linked APP session, which is the one sending audio. A mute from
        // the app itself mutes its own session. Either way the app is told.
        const target = (s.appWs && clients.get(s.appWs)) || s;
        target.muted = Boolean(msg.muted);
        send(ws, { type: "mute_ok", muted: target.muted });
        if (target.ws !== ws) send(target.ws, { type: "mute_ok", muted: target.muted });
      } else if (msg.type === "group_create") {
        const name = String(msg.name || "").slice(0, 32);
        if (!name || groups.has(name)) { send(ws, { type: "group_ok", ok: false, reason: "exists_or_empty" }); return; }
        groups.set(name, { passwordHash: hashPassword(msg.password || ""), members: new Set([ws]) });
        if (s.group) groups.get(s.group)?.members.delete(ws);
        s.group = name;
        send(ws, { type: "group_ok", ok: true, group: name });
      } else if (msg.type === "group_list") {
        send(ws, { type: "groups", groups: [...groups.keys()] });
      } else if (msg.type === "group_join") {
        const g = groups.get(String(msg.name || ""));
        if (!g || g.passwordHash !== hashPassword(msg.password || "")) {
          send(ws, { type: "group_ok", ok: false, reason: "bad_name_or_password" }); return;
        }
        if (s.group) groups.get(s.group)?.members.delete(ws);
        g.members.add(ws);
        s.group = String(msg.name);
        send(ws, { type: "group_ok", ok: true, group: s.group });
      } else if (msg.type === "group_leave") {
        if (s.group) groups.get(s.group)?.members.delete(ws);
        s.group = null;
        send(ws, { type: "group_ok", ok: true, group: null });
      }
    },
    close(ws) {
      const s = clients.get(ws);
      if (s?.group) groups.get(s.group)?.members.delete(ws);
      if (s?.appWs) { const app = clients.get(s.appWs); if (app) app.packWs = null; }
      if (s?.packWs) { const pack = clients.get(s.packWs); if (pack) pack.appWs = null; }
      for (const [code, w] of codes) if (w === ws) codes.delete(code);
      clients.delete(ws);
    },
  },
});

console.log(`relay listening on ${PORT}, default proximity ${DEFAULT_PROXIMITY} blocks`);
