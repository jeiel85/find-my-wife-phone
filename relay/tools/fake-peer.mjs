// A fake paired phone for end-to-end tests against a LOCAL relay (hardening #13).
//
//   node tools/fake-peer.mjs invite [name]        register (if needed) and print a fmwp://pair link
//   node tools/fake-peer.mjs serve [seconds] [--ring countdown,ringing]
//                                                 answer pushes: pairing, ring requests; one JSON line per event
//   node tools/fake-peer.mjs send <type> [json]   send one message (e.g. ring, locate, status) to the paired app
//   node tools/fake-peer.mjs cleanup              delete this fake phone from the relay
//
// It registers with a "test-outbox:" push token, so it only works with a relay started as
//   npx wrangler dev --var TEST_OUTBOX:1
// which keeps its pushes for GET /v1/test/outbox instead of sending them to FCM.
// RELAY_URL (default http://127.0.0.1:8787) and FAKE_PEER_STATE (default tools/.fake-peer.json) override paths.
// Message formats: design/PROTOCOL.md; crypto mirrors android …/crypto/Crypto.kt.
import crypto from "node:crypto";
import fs from "node:fs";

const RELAY = process.env.RELAY_URL ?? "http://127.0.0.1:8787";
const STATE = process.env.FAKE_PEER_STATE ?? new URL("./.fake-peer.json", import.meta.url);
const b64 = (b) => Buffer.from(b).toString("base64url");
const unb64 = (s) => Buffer.from(s, "base64url");
const load = () => (fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {});
const save = (s) => fs.writeFileSync(STATE, JSON.stringify(s, null, 2));
const log = (...a) => console.error(...a);
const event = (o) => console.log(JSON.stringify({ at: Date.now(), ...o }));

async function call(method, path, token, body) {
  const res = await fetch(RELAY + path, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (res.status >= 400) log(method, path, res.status, text.slice(0, 200));
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

const aad = (from, to) => Buffer.from(`fmwp1|${from}|${to}`);

function seal(key, obj, from, to) {
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", key, nonce);
  c.setAAD(aad(from, to));
  const ct = Buffer.concat([c.update(Buffer.from(JSON.stringify(obj))), c.final(), c.getAuthTag()]);
  return b64(Buffer.concat([Buffer.from([1]), nonce, ct]));
}

function open(key, env, from, to) {
  try {
    const raw = unb64(env);
    if (raw[0] !== 1) return null;
    const d = crypto.createDecipheriv("aes-256-gcm", key, raw.subarray(1, 13));
    d.setAAD(aad(from, to));
    d.setAuthTag(raw.subarray(raw.length - 16));
    return JSON.parse(Buffer.concat([d.update(raw.subarray(13, raw.length - 16)), d.final()]).toString("utf8"));
  } catch {
    return null;
  }
}

const status = () => ({ snoozeUntil: 0, loc: "auto", battery: 81, charging: false });
const msg = (type, extra = {}) => ({ v: 1, type, id: crypto.randomUUID(), ts: Date.now(), st: status(), ...extra });

async function sendTo(s, obj) {
  const r = await call("POST", "/v1/messages", s.token, { to: s.peer, env: seal(unb64(s.key), obj, s.deviceId, s.peer), ttlSec: 60 });
  event({ event: "sent", type: obj.type, reqId: obj.reqId, state: obj.state, status: r.status });
  return r.status;
}

async function ensureRegistered(s) {
  if (s.token) return s;
  const ecdh = crypto.createECDH("prime256v1");
  ecdh.generateKeys();
  const r = await call("POST", "/v1/devices", null, { fcmToken: "test-outbox:" + b64(crypto.randomBytes(24)) });
  if (r.status !== 201) throw new Error(`register failed: ${r.status}`);
  const next = { deviceId: r.json.deviceId, token: r.json.token, priv: b64(ecdh.getPrivateKey()), pub: b64(ecdh.getPublicKey()) };
  save(next);
  return next;
}

const [cmd, ...args] = process.argv.slice(2);
let s = load();

if (cmd === "invite") {
  s = await ensureRegistered(s);
  const secret = crypto.randomBytes(16);
  const inviteId = b64(crypto.createHash("sha256").update(secret).digest());
  const r = await call("POST", "/v1/invites", s.token, { inviteId, ttlSec: 600 });
  if (r.status !== 201) process.exit(1);
  save({ ...s, secret: b64(secret) });
  const offer = { v: 1, id: s.deviceId, pub: s.pub, s: b64(secret), n: args[0] ?? "시험폰", exp: r.json.expiresAt };
  console.log("fmwp://pair/#" + b64(JSON.stringify(offer)));
} else if (cmd === "serve") {
  const seconds = Number(args.find((a) => /^\d+$/.test(a)) ?? 600);
  const ringIdx = args.indexOf("--ring");
  const ringStates = ringIdx >= 0 ? args[ringIdx + 1].split(",").filter(Boolean) : ["countdown", "ringing"];
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    const r = await call("GET", "/v1/test/outbox", s.token);
    if (r.status !== 200) { log("outbox unavailable — is the relay running with --var TEST_OUTBOX:1?"); process.exit(1); }
    for (const data of r.json.messages) {
      if (data.k === "pair") {
        const ecdh = crypto.createECDH("prime256v1");
        ecdh.setPrivateKey(unb64(s.priv));
        const shared = ecdh.computeSecret(unb64(data.pub));
        const key = Buffer.from(crypto.hkdfSync("sha256", shared, unb64(s.secret), Buffer.from("fmwp pair v1"), 32));
        const hello = open(key, data.env, data.src, s.deviceId);
        if (!hello) { event({ event: "pair-undecryptable", src: data.src }); continue; }
        s = { ...s, peer: data.src, key: b64(key) };
        save(s);
        event({ event: "paired", peer: data.src, name: hello.name, model: hello.model });
        await sendTo(s, msg("hello-ack", { name: "시험폰", model: "Fake Peer" }));
      } else if (data.k === "msg") {
        if (!s.key || data.src !== s.peer) { event({ event: "msg-from-unknown", src: data.src }); continue; }
        const m = open(unb64(s.key), data.env, data.src, s.deviceId);
        if (!m) { event({ event: "msg-undecryptable" }); continue; }
        event({ event: "received", type: m.type, reqId: m.reqId, state: m.state });
        if (m.type === "ring") {
          for (const st of ringStates) {
            await sendTo(s, msg("ring-state", { reqId: m.reqId, state: st }));
            await new Promise((ok) => setTimeout(ok, 1000));
          }
        }
      }
    }
    await new Promise((ok) => setTimeout(ok, 700));
  }
} else if (cmd === "send") {
  if (!s.key) { log("not paired"); process.exit(1); }
  const extra = args[1] ? JSON.parse(args[1]) : {};
  const type = args[0];
  const needsReq = ["ring", "ring-cancel", "locate"].includes(type);
  process.exit((await sendTo(s, msg(type, needsReq ? { reqId: crypto.randomUUID(), ...extra } : extra))) < 300 ? 0 : 1);
} else if (cmd === "cleanup") {
  if (s.token) await call("DELETE", "/v1/devices/me", s.token);
  fs.rmSync(STATE, { force: true });
  log("fake peer removed");
} else {
  log("usage: node tools/fake-peer.mjs invite [name] | serve [seconds] [--ring s1,s2] | send <type> [json] | cleanup");
  process.exit(2);
}
