-- When FCM reported the device's token dead (unix ms). The daily cleanup counts its 30-day grace
-- from here, not from seen_at: a quiet paired phone may not have called the relay for months.
ALTER TABLE devices ADD COLUMN dead_at INTEGER;

-- Tokens already dead before this column existed: their grace period starts now.
UPDATE devices SET dead_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000 WHERE fcm_token IS NULL;
