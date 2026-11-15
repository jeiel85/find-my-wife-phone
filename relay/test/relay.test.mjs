// Runs the Worker handler against a D1 stand-in built on node:sqlite and a fake FCM.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { generateKeyPairSync } from "node:crypto";
import { handle, cleanup } from "../src/index.js";
import { _resetFcmCacheForTests, sendData, parseServiceAccount } from "../src/fcm.js";

class Stmt {
  constructor(db, sql) { this.db = db; this.sql = sql; this.args = []; }
  bind(...a) { this.args = a; return this; }
  async first() { return this.db.prepare(this.sql).get(...this.args) ?? null; }
  async run() { this.db.prepare(this.sql).run(...this.args); return { success: true }; }
  async all() { return { results: this.db.prepare(this.sql).all(...this.args) }; }
}
function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
  return {
    raw: db,
    prepare: (sql) => new Stmt(db, sql),
    batch: async (stmts) => {
      db.exec("BEGIN");
      try { for (const s of stmts) await s.run(); db.exec("COMMIT"); } catch (e) { db.exec("ROLLBACK"); throw e; }
    },
  };
}

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SA = JSON.stringify({
  project_id: "demo", client_email: "relay@demo.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
});

let env, sent, fcmMode, clock;
const deps = {
  now: () => clock,
  fetch: async (url, init) => {
    if (url.startsWith("https://oauth2.googleapis.com/")) {
      return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
    }
    const msg = JSON.parse(init.body).message;
    if (fcmMode === "dead") {
      return new Response(JSON.stringify({ error: { details: [{ errorCode: "UNREGISTERED" }] } }), { status: 404 });
    }
    if (fcmMode === "down") return new Response("{}", { status: 503 });
    sent.push(msg);
    return new Response("{}", { status: 200 });
  },
};

beforeEach(() => {
  env = { DB: d1(), FCM_SERVICE_ACCOUNT: SA };
  sent = []; fcmMode = "ok"; clock = 1_800_000_000_000;
  _resetFcmCacheForTests();
});

async function call(method, path, { token, body, ip } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (ip) headers["cf-connecting-ip"] = ip;
  const res = await handle(new Request(`https://relay.test${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  }), env, deps);
  return { status: res.status, body: await res.json() };
}

const FCM = "f".repeat(30);
const INVITE = "i".repeat(43);
const PUB = "p".repeat(87);
const ENV = "e".repeat(40);

async function register(tag = "x") {
  const r = await call("POST", "/v1/devices", { body: { fcmToken: FCM + tag } });
  assert.equal(r.status, 201);
  return r.body;
}
async function pair() {
  const a = await register("a");
  const b = await register("b");
  assert.equal((await call("POST", "/v1/invites", { token: a.token, body: { inviteId: INVITE } })).status, 201);
  const j = await call("POST", `/v1/invites/${INVITE}/join`, { token: b.token, body: { pub: PUB, env: ENV } });
  assert.equal(j.status, 200);
  assert.equal(j.body.peerId, a.deviceId);
  return { a, b };
}

test("health needs no auth", async () => {
  assert.deepEqual((await call("GET", "/v1/health")).body, { ok: true, schema: 1 });
});

test("unknown token is rejected", async () => {
  assert.equal((await call("PUT", "/v1/devices/me", { token: "t".repeat(43), body: { fcmToken: FCM } })).status, 401);
  assert.equal((await call("POST", "/v1/messages", { body: {} })).status, 401);
});

test("register stores only the token hash", async () => {
  const d = await register();
  assert.match(d.deviceId, /^[A-Za-z0-9_-]{22}$/);
  const row = env.DB.raw.prepare("SELECT token_hash FROM devices").get();
  assert.notEqual(row.token_hash, d.token);
  assert.equal(row.token_hash.length, 64);
});

test("invalid inputs are 400", async () => {
  assert.equal((await call("POST", "/v1/devices", { body: { fcmToken: "short" } })).status, 400);
  const d = await register();
  assert.equal((await call("POST", "/v1/invites", { token: d.token, body: { inviteId: "nope" } })).status, 400);
  assert.equal((await call("POST", "/v1/messages", { token: d.token, body: { to: "x", env: ENV } })).status, 400);
});

test("pairing wakes the inviter and links both ways", async () => {
  const { a, b } = await pair();
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].data, { k: "pair", src: b.deviceId, pub: PUB, env: ENV });
  assert.equal(sent[0].token, FCM + "a");
  assert.equal(sent[0].android.priority, "HIGH");
  // invite is single-use
  const again = await call("POST", `/v1/invites/${INVITE}/join`, { token: b.token, body: { pub: PUB, env: ENV } });
  assert.equal(again.status, 404);
  // both directions may now send
  assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 202);
  assert.equal((await call("POST", "/v1/messages", { token: b.token, body: { to: a.deviceId, env: ENV, ttlSec: 5 } })).status, 202);
  assert.deepEqual(sent.at(-1).data, { k: "msg", src: b.deviceId, env: ENV });
  assert.equal(sent.at(-1).android.ttl, "10s"); // clamped
});

test("expired invite is 410 and self-pairing is refused", async () => {
  const a = await register("a");
  const b = await register("b");
  await call("POST", "/v1/invites", { token: a.token, body: { inviteId: INVITE, ttlSec: 30 } });
  assert.equal((await call("POST", `/v1/invites/${INVITE}/join`, { token: a.token, body: { pub: PUB, env: ENV } })).body.error, "self_pairing");
  clock += 31_000;
  assert.equal((await call("POST", `/v1/invites/${INVITE}/join`, { token: b.token, body: { pub: PUB, env: ENV } })).status, 410);
});

test("unreachable inviter keeps the invite and leaves no links", async () => {
  const a = await register("a");
  const b = await register("b");
  await call("POST", "/v1/invites", { token: a.token, body: { inviteId: INVITE } });
  fcmMode = "down";
  assert.equal((await call("POST", `/v1/invites/${INVITE}/join`, { token: b.token, body: { pub: PUB, env: ENV } })).status, 502);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM links").get().c, 0);
  fcmMode = "ok";
  assert.equal((await call("POST", `/v1/invites/${INVITE}/join`, { token: b.token, body: { pub: PUB, env: ENV } })).status, 200);
});

test("unpaired devices cannot message each other", async () => {
  const a = await register("a");
  const b = await register("b");
  assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 403);
  assert.equal(sent.length, 0);
});

test("unlink removes both directions", async () => {
  const { a, b } = await pair();
  assert.equal((await call("DELETE", `/v1/links/${b.deviceId}`, { token: a.token })).status, 200);
  assert.equal((await call("POST", "/v1/messages", { token: b.token, body: { to: a.deviceId, env: ENV } })).status, 403);
});

test("dead FCM token is cleared and reported as peer_unreachable", async () => {
  const { a, b } = await pair();
  fcmMode = "dead";
  const r = await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } });
  assert.equal(r.status, 410);
  assert.equal(r.body.error, "peer_unreachable");
  assert.equal(env.DB.raw.prepare("SELECT fcm_token FROM devices WHERE id = ?").get(b.deviceId).fcm_token, null);
  fcmMode = "ok";
  // peer re-registers its token and becomes reachable again
  assert.equal((await call("PUT", "/v1/devices/me", { token: b.token, body: { fcmToken: FCM + "new" } })).status, 200);
  assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 202);
});

test("message rate limit per pair", async () => {
  const { a, b } = await pair();
  for (let i = 0; i < 60; i++) {
    assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 202);
  }
  assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 429);
  clock += 600_000;
  assert.equal((await call("POST", "/v1/messages", { token: a.token, body: { to: b.deviceId, env: ENV } })).status, 202);
});

test("registration rate limit per IP", async () => {
  for (let i = 0; i < 20; i++) {
    assert.equal((await call("POST", "/v1/devices", { ip: "1.2.3.4", body: { fcmToken: FCM } })).status, 201);
  }
  assert.equal((await call("POST", "/v1/devices", { ip: "1.2.3.4", body: { fcmToken: FCM } })).status, 429);
  assert.equal((await call("POST", "/v1/devices", { ip: "5.6.7.8", body: { fcmToken: FCM } })).status, 201);
});

test("open invites are capped by dropping the oldest", async () => {
  const a = await register("a");
  for (const ch of "abcd") {
    clock += 1000;
    await call("POST", "/v1/invites", { token: a.token, body: { inviteId: ch.repeat(43) } });
  }
  const ids = env.DB.raw.prepare("SELECT id FROM invites ORDER BY expires_at").all().map((r) => r.id[0]);
  assert.deepEqual(ids, ["b", "c", "d"]);
});

test("deleting a device removes its links and invites", async () => {
  const { a, b } = await pair();
  await call("POST", "/v1/invites", { token: a.token, body: { inviteId: "z".repeat(43) } });
  assert.equal((await call("DELETE", "/v1/devices/me", { token: a.token })).status, 200);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM links").get().c, 0);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM invites").get().c, 0);
  assert.equal((await call("POST", "/v1/messages", { token: b.token, body: { to: a.deviceId, env: ENV } })).status, 403);
});

test("cleanup drops expired invites and old counters", async () => {
  const a = await register("a");
  await call("POST", "/v1/invites", { token: a.token, body: { inviteId: INVITE, ttlSec: 30 } });
  await cleanup(env, clock + 2 * 86_400_000);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM invites").get().c, 0);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM rate").get().c, 0);
});

test("FCM-reserved data keys are refused before sending", async () => {
  let called = false;
  const fetchFn = async () => { called = true; return new Response("{}"); };
  for (const key of ["from", "notification", "message_type", "collapse_key", "google.x", "gcm.n"]) {
    await assert.rejects(sendData(parseServiceAccount(SA), "tok", { [key]: "v" }, 60, fetchFn), /reserved/);
  }
  assert.equal(called, false);
});
