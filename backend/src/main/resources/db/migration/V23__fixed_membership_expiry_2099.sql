-- The 99-year gift means one shared expiry date, not 99 years from registration.
UPDATE member_entitlement
SET days = 1, reason = '新用户首次进入小程序赠送99年至2099-01-01'
WHERE source = 'WELCOME' AND (reason LIKE '%99年%' OR reason LIKE '%2099-01-01%');

-- Keep a durable entitlement so later order/refund recalculation preserves this date.
INSERT INTO member_entitlement(member_id, source, set_expires_at, reason)
SELECT id, 'ADMIN_SET', TIMESTAMPTZ '2099-01-01 00:00:00+08', '系统统一会员到期日为2099-01-01'
FROM member_account;

UPDATE member_account
SET vip_expires_at = TIMESTAMPTZ '2099-01-01 00:00:00+08', updated_at = now();
