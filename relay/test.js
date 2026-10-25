// Systematic relay test, no game and no phone needed.
// Simulates: app A (ahmd), app B (near), app C (far), and a pack client.
// Each case prints PASS or FAIL. Exit 0 only if all pass.
const URL = process.env.RELAY_URL || "ws://localhost:3000";
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
function client(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    const c = { ws, name, received: [], json: [] };
    ws.addEventListener("message", (e) => {
      if (typeof e.data === "string") { try { c.json.push(JSON.parse(e.data)); } catch {} }
      else c.received.push(e.data);
    });
    ws.addEventListener("open", () => { ws.send(JSON.stringify({ type: "hello", name })); resolve(c); });
    ws.addEventListener("error", reject);
  });
}
function send(c, o) { c.ws.send(JSON.stringify(o)); }
function audio(c, text) { c.ws.send(new TextEncoder().encode(text)); }
async function main() {
  const results = [];
  const check = (label, cond) => { results.push([label, cond]); console.log(`${cond ? "PASS" : "FAIL"} ${label}`); };
  const A = await client("ahmd");
  const B = await client("friend_near");
  const C = await client("friend_far");
  const pack = await client("pack");
  await wait(200);
  send(A, { x: 0, y: 64, z: 0, dimension: "overworld", type: "position" });
  send(B, { x: 10, y: 64, z: 0, dimension: "overworld", type: "position" });
  send(C, { x: 100, y: 64, z: 0, dimension: "overworld", type: "position" });
  await wait(200);
  // 1 proximity
  B.received.length = 0; C.received.length = 0;
  audio(A, "hello-near"); await wait(300);
  check("proximity: near (10 blocks) receives", B.received.length === 1);
  check("proximity: far (100 blocks) does not receive", C.received.length === 0);
  // 2 different dimension
  send(B, { x: 10, y: 64, z: 0, dimension: "nether", type: "position" }); await wait(200);
  B.received.length = 0; audio(A, "hello-dim"); await wait(300);
  check("dimension: different dimension does not receive", B.received.length === 0);
  send(B, { x: 10, y: 64, z: 0, dimension: "overworld", type: "position" }); await wait(200);
  // 3 mute
  send(A, { type: "mute", muted: true }); await wait(200);
  B.received.length = 0; audio(A, "hello-muted"); await wait(300);
  check("mute: muted sender reaches nobody", B.received.length === 0);
  send(A, { type: "mute", muted: false }); await wait(200);
  // 4 verify code
  send(A, { type: "request_code" }); await wait(200);
  const code = A.json.find((j) => j.type === "code")?.code;
  check("verify: code issued (6 digits)", typeof code === "string" && code.length === 6);
  send(pack, { type: "verify", code, player: "ahmd" }); await wait(200);
  check("verify: app linked to in-game player", A.json.some((j) => j.type === "verified" && j.player === "ahmd"));
  // 5 group: wrong password, then correct, then anywhere
  send(C, { type: "group_create", name: "team1", password: "1234" }); await wait(200);
  check("group: create ok", C.json.some((j) => j.type === "group_ok" && j.ok === true));
  send(A, { type: "group_join", name: "team1", password: "wrong" }); await wait(200);
  check("group: wrong password rejected", A.json.some((j) => j.type === "group_ok" && j.ok === false));
  send(A, { type: "group_join", name: "team1", password: "1234" }); await wait(200);
  check("group: correct password joins", A.json.some((j) => j.type === "group_ok" && j.ok === true && j.group === "team1"));
  C.received.length = 0; B.received.length = 0;
  audio(A, "hello-group"); await wait(300);
  check("group: far member (100 blocks) receives group audio", C.received.length === 1);
  check("group: non-member near player does not receive group audio", B.received.length === 0);
  // 6 leave group -> proximity again
  send(A, { type: "group_leave" }); await wait(200);
  B.received.length = 0; C.received.length = 0;
  audio(A, "hello-back"); await wait(300);
  check("group leave: near receives again by proximity", B.received.length === 1);
  check("group leave: far stops receiving", C.received.length === 0);
  for (const c of [A, B, C, pack]) c.ws.close();
  const failed = results.filter(([, ok]) => !ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length ? 1 : 0);
}
main().catch((e) => { console.error(e); process.exit(1); });
