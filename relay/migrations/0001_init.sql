-- Relay state. The relay never sees message contents: it only knows which device may
-- wake which other device, and the FCM token needed to do it.

CREATE TABLE devices (
  id          TEXT PRIMARY KEY,          -- 22-char base64url (16 random bytes)
  token_hash  TEXT NOT NULL UNIQUE,      -- hex SHA-256 of the bearer token
  fcm_token   TEXT,                      -- NULL once FCM reports the token dead
  created_at  INTEGER NOT NULL,          -- unix ms
  seen_at     INTEGER NOT NULL           -- unix ms, last authenticated call
);

-- Directed permission: `a` may send to `b`. Pairing always inserts both directions.
CREATE TABLE links (
  a           TEXT NOT NULL,
  b           TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (a, b)
);
CREATE INDEX links_b ON links (b);

-- One-time pairing invites. id = base64url(SHA-256(pairSecret)); the secret itself only
-- lives in the QR code and on the inviting phone.
CREATE TABLE invites (
  id          TEXT PRIMARY KEY,
  owner       TEXT NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX invites_owner ON invites (owner);

-- Fixed-window counters for rate limiting.
CREATE TABLE rate (
  k           TEXT PRIMARY KEY,
  win         INTEGER NOT NULL,          -- window start, unix ms
  n           INTEGER NOT NULL
);
