// find-my-wife-phone relay.
//
// The relay only wakes a paired phone through FCM and hands it an opaque, end-to-end
// encrypted envelope. It keeps: device id + hashed bearer token + FCM token, who may wake
// whom (links), one-time pairing invites and rate-limit counters. See design/PROTOCOL.md.

import { parseServiceAccount, sendData } from "./fcm.js";

const B64URL = /^[A-Za-z0-9_-]+$/;
const DEVICE_ID = /^[A-Za-z0-9_-]{22}$/;
const INVITE_ID = /^[A-Za-z0-9_-]{43}$/;
const PUBKEY = /^[A-Za-z0-9_-]{87}$/; // uncompressed P-256 point, 65 bytes
const MAX_ENV = 3000;
const INVITE_MAX_TTL_MS = 10 * 60_000;
const MAX_OPEN_INVITES = 3;

const LIMITS = {
  register: { max: 20, windowMs: 3600_000 },   // per client IP
  invite: { max: 20, windowMs: 3600_000 },     // per device
  join: { max: 20, windowMs: 3600_000 },       // per device
  message: { max: 60, windowMs: 600_000 },     // per (from, to)
  messageDevice: { max: 120, windowMs: 600_000 }, // per sender, across all its pairs
};

// Cleanup (daily cron). A device's seen_at only moves when it calls the relay, so a quiet phone
// that is still paired must never be dropped for being quiet alone.
const DEAD_TOKEN_MS = 30 * 86_400_000;   // since FCM said its token is gone (dead_at), with no new token since
const IDLE_UNLINKED_MS = 90 * 86_400_000; // paired with nobody and silent this long

class HttpError extends Error {
  constructor(status, code, message) { super(message ?? code); this.status = status; this.code = code; }
}

function json(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function randomB64url(n) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256Hex(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...d].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function readJson(req) {
  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > 16_384) throw new HttpError(413, "too_large");
  const text = await req.text();
  if (text.length > 16_384) throw new HttpError(413, "too_large");
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    if (v === null || typeof v !== "object" || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new HttpError(400, "invalid_json");
  }
}

function requireString(v, re, code) {
  if (typeof v !== "string" || !re.test(v)) throw new HttpError(400, code);
  return v;
}

function requireEnvelope(v) {
  if (typeof v !== "string" || v.length < 16 || v.length > MAX_ENV || !B64URL.test(v)) {
    throw new HttpError(400, "invalid_envelope");
  }
  return v;
}

function requireFcmToken(v) {
  if (typeof v !== "string" || v.length < 20 || v.length > 4096 || !/^[\x21-\x7e]+$/.test(v)) {
    throw new HttpError(400, "invalid_fcm_token");
  }
  return v;
}

async function hit(db, key, { max, windowMs }, now) {
  const win = Math.floor(now / windowMs) * windowMs;
  const row = await db.prepare(
    `INSERT INTO rate (k, win, n) VALUES (?1, ?2, 1)
     ON CONFLICT (k) DO UPDATE SET n = CASE WHEN rate.win = excluded.win THEN rate.n + 1 ELSE 1 END,
                                   win = excluded.win
     RETURNING n`).bind(key, win).first();
  if (row.n > max) throw new HttpError(429, "rate_limited");
}

async function authenticate(req, db, now) {
  const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.get("authorization") ?? "");
  if (!m) throw new HttpError(401, "unauthorized");
  const dev = await db.prepare("SELECT id, fcm_token, seen_at FROM devices WHERE token_hash = ?1")
    .bind(await sha256Hex(m[1])).first();
  if (!dev) throw new HttpError(401, "unauthorized");
  if (now - dev.seen_at > 3600_000) {
    await db.prepare("UPDATE devices SET seen_at = ?1 WHERE id = ?2").bind(now, dev.id).run();
  }
  return dev;
}

// Local end-to-end tests only (hardening #13). A fake phone cannot receive FCM, so under
// `wrangler dev --var TEST_OUTBOX:1` a device registered with a "test-outbox:" token gets its pushes
// here instead and fetches them with GET /v1/test/outbox. Never set TEST_OUTBOX in production: there
// such a token is just an invalid FCM token, and the outbox route does not exist.
const TEST_OUTBOX_PREFIX = "test-outbox:";
const TEST_OUTBOX_MAX = 100;
const outbox = new Map(); // deviceId → [data]; one wrangler dev isolate holds the whole test run
const testOutbox = (env) => env.TEST_OUTBOX === "1";

async function wake(env, deps, db, targetId, data, ttlSec) {
  const target = await db.prepare("SELECT fcm_token FROM devices WHERE id = ?1").bind(targetId).first();
  if (!target?.fcm_token) throw new HttpError(410, "peer_unreachable");
  if (testOutbox(env) && target.fcm_token.startsWith(TEST_OUTBOX_PREFIX)) {
    const box = outbox.get(targetId) ?? [];
    box.push(data);
    outbox.set(targetId, box.slice(-TEST_OUTBOX_MAX));
    return;
  }
  const sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
  const result = await sendData(sa, target.fcm_token, data, ttlSec, deps.fetch, deps.now(), deps.sleep);
  if (result === "unregistered") {
    await db.prepare("UPDATE devices SET fcm_token = NULL, dead_at = ?1 WHERE id = ?2").bind(deps.now(), targetId).run();
    throw new HttpError(410, "peer_unreachable");
  }
  if (result !== "ok") throw new HttpError(502, "push_failed");
}

async function route(req, env, deps) {
  const url = new URL(req.url);
  const db = env.DB;
  const now = deps.now();
  const path = url.pathname;

  if (req.method === "GET" && path === "/v1/health") return json(200, { ok: true, schema: 1 });

  if (req.method === "POST" && path === "/v1/devices") {
    const ip = req.headers.get("cf-connecting-ip") ?? "unknown";
    await hit(db, `register:${ip}`, LIMITS.register, now);
    const body = await readJson(req);
    const fcm = requireFcmToken(body.fcmToken);
    const id = randomB64url(16);
    const token = randomB64url(32);
    await db.prepare("INSERT INTO devices (id, token_hash, fcm_token, created_at, seen_at) VALUES (?1, ?2, ?3, ?4, ?4)")
      .bind(id, await sha256Hex(token), fcm, now).run();
    return json(201, { schema: 1, deviceId: id, token });
  }

  const me = await authenticate(req, db, now);

  if (path === "/v1/devices/me") {
    if (req.method === "PUT") {
      const body = await readJson(req);
      const fcm = requireFcmToken(body.fcmToken);
      await db.prepare("UPDATE devices SET fcm_token = ?1, seen_at = ?2, dead_at = NULL WHERE id = ?3").bind(fcm, now, me.id).run();
      return json(200, { schema: 1, ok: true });
    }
    if (req.method === "DELETE") {
      await db.batch([
        db.prepare("DELETE FROM links WHERE a = ?1 OR b = ?1").bind(me.id),
        db.prepare("DELETE FROM invites WHERE owner = ?1").bind(me.id),
        db.prepare("DELETE FROM devices WHERE id = ?1").bind(me.id),
      ]);
      return json(200, { schema: 1, ok: true });
    }
  }

  if (req.method === "POST" && path === "/v1/invites") {
    await hit(db, `invite:${me.id}`, LIMITS.invite, now);
    const body = await readJson(req);
    const inviteId = requireString(body.inviteId, INVITE_ID, "invalid_invite");
    const ttl = Math.min(Math.max(Number(body.ttlSec) || 600, 30), INVITE_MAX_TTL_MS / 1000) * 1000;
    await db.prepare("DELETE FROM invites WHERE owner = ?1 AND expires_at <= ?2").bind(me.id, now).run();
    const open = await db.prepare("SELECT COUNT(*) AS c FROM invites WHERE owner = ?1").bind(me.id).first();
    if (open.c >= MAX_OPEN_INVITES) {
      // Drop the oldest so a user re-opening the QR screen never gets stuck.
      await db.prepare(
        "DELETE FROM invites WHERE id IN (SELECT id FROM invites WHERE owner = ?1 ORDER BY expires_at ASC LIMIT ?2)")
        .bind(me.id, open.c - MAX_OPEN_INVITES + 1).run();
    }
    await db.prepare("INSERT OR REPLACE INTO invites (id, owner, expires_at) VALUES (?1, ?2, ?3)")
      .bind(inviteId, me.id, now + ttl).run();
    return json(201, { schema: 1, inviteId, expiresAt: now + ttl });
  }

  const join = /^\/v1\/invites\/([A-Za-z0-9_-]{43})\/join$/.exec(path);
  if (req.method === "POST" && join) {
    await hit(db, `join:${me.id}`, LIMITS.join, now);
    const body = await readJson(req);
    const pub = requireString(body.pub, PUBKEY, "invalid_pub");
    const envelope = requireEnvelope(body.env);
    const inv = await db.prepare("SELECT owner, expires_at FROM invites WHERE id = ?1").bind(join[1]).first();
    if (!inv) throw new HttpError(404, "invite_not_found");
    if (inv.expires_at <= now) {
      await db.prepare("DELETE FROM invites WHERE id = ?1").bind(join[1]).run();
      throw new HttpError(410, "invite_expired");
    }
    if (inv.owner === me.id) throw new HttpError(400, "self_pairing");
    // Links must exist before the inviter wakes up, because its first act is to answer us.
    const existed = await db.prepare("SELECT 1 AS ok FROM links WHERE a = ?1 AND b = ?2").bind(me.id, inv.owner).first();
    await db.batch([
      db.prepare("INSERT OR IGNORE INTO links (a, b, created_at) VALUES (?1, ?2, ?3)").bind(me.id, inv.owner, now),
      db.prepare("INSERT OR IGNORE INTO links (a, b, created_at) VALUES (?1, ?2, ?3)").bind(inv.owner, me.id, now),
    ]);
    try {
      await wake(env, deps, db, inv.owner, { k: "pair", src: me.id, pub, env: envelope }, 600);
    } catch (e) {
      // Inviter unreachable: undo new links and keep the invite so the scan can be retried.
      if (!existed) {
        await db.prepare("DELETE FROM links WHERE (a = ?1 AND b = ?2) OR (a = ?2 AND b = ?1)").bind(me.id, inv.owner).run();
      }
      throw e;
    }
    await db.prepare("DELETE FROM invites WHERE id = ?1").bind(join[1]).run();
    return json(200, { schema: 1, peerId: inv.owner });
  }

  if (req.method === "POST" && path === "/v1/messages") {
    const body = await readJson(req);
    const to = requireString(body.to, DEVICE_ID, "invalid_peer");
    const envelope = requireEnvelope(body.env);
    const ttl = Math.min(Math.max(Math.floor(Number(body.ttlSec) || 60), 10), 86_400);
    const link = await db.prepare("SELECT 1 AS ok FROM links WHERE a = ?1 AND b = ?2").bind(me.id, to).first();
    if (!link) throw new HttpError(403, "not_paired");
    await hit(db, `msg:${me.id}:${to}`, LIMITS.message, now);
    await hit(db, `msgdev:${me.id}`, LIMITS.messageDevice, now);
    await wake(env, deps, db, to, { k: "msg", src: me.id, env: envelope }, ttl);
    return json(202, { schema: 1, accepted: true });
  }

  if (req.method === "GET" && path === "/v1/test/outbox" && testOutbox(env)) {
    const messages = outbox.get(me.id) ?? [];
    outbox.delete(me.id);
    return json(200, { schema: 1, messages });
  }

  const unlink = /^\/v1\/links\/([A-Za-z0-9_-]{22})$/.exec(path);
  if (req.method === "DELETE" && unlink) {
    await db.prepare("DELETE FROM links WHERE (a = ?1 AND b = ?2) OR (a = ?2 AND b = ?1)").bind(me.id, unlink[1]).run();
    return json(200, { schema: 1, ok: true });
  }

  throw new HttpError(404, "not_found");
}

export async function handle(req, env, deps = { fetch, now: Date.now }) {
  try {
    return await route(req, env, deps);
  } catch (e) {
    if (e instanceof HttpError) return json(e.status, { schema: 1, error: e.code });
    console.error("unhandled", e?.stack ?? e);
    return json(500, { schema: 1, error: "internal" });
  }
}

export async function cleanup(env, now = Date.now()) {
  const db = env.DB;
  await db.batch([
    db.prepare("DELETE FROM invites WHERE expires_at <= ?1").bind(now),
    db.prepare("DELETE FROM rate WHERE win < ?1").bind(now - 86_400_000),
    // Reinstalled or removed apps: the old registration can never be woken again.
    db.prepare(
      `DELETE FROM devices WHERE (fcm_token IS NULL AND dead_at < ?1)
          OR (seen_at < ?2 AND NOT EXISTS (SELECT 1 FROM links WHERE links.a = devices.id OR links.b = devices.id))`)
      .bind(now - DEAD_TOKEN_MS, now - IDLE_UNLINKED_MS),
    // Whatever pointed at a removed device (also rows left behind before this cleanup existed).
    db.prepare("DELETE FROM links WHERE a NOT IN (SELECT id FROM devices) OR b NOT IN (SELECT id FROM devices)"),
    db.prepare("DELETE FROM invites WHERE owner NOT IN (SELECT id FROM devices)"),
  ]);
}

export default {
  fetch: (req, env) => handle(req, env),
  scheduled: (_event, env, ctx) => ctx.waitUntil(cleanup(env)),
};
