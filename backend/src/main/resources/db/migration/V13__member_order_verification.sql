ALTER TABLE member_order ADD COLUMN last_verified_at TIMESTAMPTZ;
CREATE INDEX member_order_reconcile_idx ON member_order(status, last_verified_at, id);
