// Minecraft proximity voice relay - pilot v2
// Runtime: Bun. Protocol: JSON text messages, plus binary audio frames.
// Audio is opaque: the relay never decodes it. It forwards a frame only to
// recipients allowed by the rules below, with a `from` name attached.
// Rules, in order:
//  1. Sender muted -> nobody receives.
//  2. Sender in a group -> only group members receive, any distance, any dimension.
//  3. Otherwise -> only players in the same dimension within PROXIMITY blocks receive.
// Identity: a player enters only a name in the app. The game pack links that
// name to an in-game player with a one-time verify code:
//   app sends {type:"request_code"} -> server returns {type:"code", code:"123456"}
//   player types /voice <code> in game -> pack sends {type:"verify", code, player}
//   server links the app session to that in-game player name for position routing.
// Positions come from the pack (authoritative). The app never invents a position.
// v2 fixes (2026-10-25): an app session's position is taken from its LINKED pack
// socket (same player name), because the app itself sends no position — without
// this, proximity routing for app audio returned nobody. And a mute sent from a
// linked pack socket (/voice mute in game) now mutes the linked APP session,
// which is the session that actually sends audio; the app is told via mute_ok
// so its mic button stays in sync.

import { createHash } from "node:crypto";

const PORT = Number(process.env.PORT || 7860);
const PROXIMITY = 15; // blocks, like the Java mod default

const clients = new Map(); // ws -> session
const groups = new Map(); // groupName -> { passwordHash, members:Set<ws> }
const codes = new Map(); // code -> ws

function hashPassword(pw) {
  return createHash("sha256").update(String(pw)).digest("hex");
}
function send(ws, obj) {
  try { ws.send(JSON.stringify(obj)); } catch {}
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
    if (distance(senderPos, targetPos) <= PROXIMITY) out.push(ws);
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
      clients.set(ws, { ws, name: null, player: null, pos: null, muted: false, group: null, appWs: null, packWs: null });
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
        send(ws, { type: "hello_ok", name: s.name, proximity: PROXIMITY });
      } else if (msg.type === "request_code") {
        const code = String(Math.floor(100000 + Math.random() * 900000));
        codes.set(code, ws);
        send(ws, { type: "code", code });
      } else if (msg.type === "verify") {
        // Sent by the game pack, not by the app: {code, player}
        const appWs = codes.get(String(msg.code));
        if (!appWs) { send(ws, { type: "verify_ok", ok: false, reason: "bad_code" }); return; }
        const app = clients.get(appWs);
        if (app) { app.player = String(msg.player || ""); app.packWs = ws; s.appWs = appWs; }
        codes.delete(String(msg.code));
        send(ws, { type: "verify_ok", ok: true, player: String(msg.player || "") });
        send(appWs, { type: "verified", player: String(msg.player || "") });
      } else if (msg.type === "position") {
        s.pos = { x: Number(msg.x), y: Number(msg.y), z: Number(msg.z), dimension: String(msg.dimension || "overworld") };
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

console.log(`relay listening on ${PORT}, proximity ${PROXIMITY} blocks`);
