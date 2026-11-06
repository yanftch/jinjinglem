ALTER TABLE member_entitlement DROP CONSTRAINT member_entitlement_source_check;
ALTER TABLE member_entitlement ADD CONSTRAINT member_entitlement_source_check
    CHECK (source IN ('PAYMENT', 'ADMIN_ADD', 'ADMIN_SET', 'WELCOME'));

CREATE UNIQUE INDEX member_entitlement_welcome_once_idx
    ON member_entitlement(member_id) WHERE source = 'WELCOME';
