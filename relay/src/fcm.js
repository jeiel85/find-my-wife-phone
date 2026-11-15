// Minimal FCM HTTP v1 sender for Workers: service-account JWT -> OAuth2 token -> messages:send.

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URL = "https://oauth2.googleapis.com/token";

let cached = null; // { key, accessToken, exp } — per isolate

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
const enc = new TextEncoder();

function pemToDer(pem) {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function parseServiceAccount(json) {
  if (!json) throw new Error("FCM_SERVICE_ACCOUNT is not configured");
  const sa = typeof json === "string" ? JSON.parse(json) : json;
  if (!sa.client_email || !sa.private_key || !sa.project_id) {
    throw new Error("FCM_SERVICE_ACCOUNT is missing client_email/private_key/project_id");
  }
  return sa;
}

async function accessToken(sa, fetchFn, now) {
  if (cached && cached.key === sa.client_email && cached.exp - 60_000 > now) return cached.accessToken;
  const iat = Math.floor(now / 1000);
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600,
  })));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`)));
  const res = await fetchFn(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth token request failed: ${res.status}`);
  const body = await res.json();
  cached = { key: sa.client_email, accessToken: body.access_token, exp: now + (body.expires_in ?? 3600) * 1000 };
  return cached.accessToken;
}

/**
 * Sends a data-only, high-priority message.
 * @returns {"ok" | "unregistered" | "error"} — "unregistered" means the token is dead for good.
 */
// FCM rejects the whole message (400 INVALID_ARGUMENT) when a data key is one of these.
const RESERVED = /^(from|notification|message_type|collapse_key)$|^(google|gcm)\./;

export async function sendData(sa, fcmToken, data, ttlSec, fetchFn = fetch, now = Date.now()) {
  const bad = Object.keys(data).find((k) => RESERVED.test(k));
  if (bad) throw new Error(`FCM data key "${bad}" is reserved`);
  const token = await accessToken(sa, fetchFn, now);
  const res = await fetchFn(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: { token: fcmToken, data, android: { priority: "HIGH", ttl: `${ttlSec}s` } },
    }),
  });
  if (res.ok) return "ok";
  let status = "";
  try { status = (await res.json())?.error?.details?.find((d) => d.errorCode)?.errorCode ?? ""; } catch { /* body not JSON */ }
  if (res.status === 404 || status === "UNREGISTERED") return "unregistered";
  if (res.status === 401) cached = null; // force a fresh OAuth token next time
  console.warn(`fcm send failed: ${res.status} ${status}`);
  return "error";
}

export function _resetFcmCacheForTests() { cached = null; }
