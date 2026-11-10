ALTER TABLE member_account ADD COLUMN member_no VARCHAR(8);

DO $$
DECLARE
    account_row RECORD;
    candidate TEXT;
    alphabet CONSTANT TEXT := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
BEGIN
    FOR account_row IN SELECT id FROM member_account WHERE member_no IS NULL ORDER BY id LOOP
        LOOP
            candidate := '';
            FOR char_index IN 1..8 LOOP
                candidate := candidate || substr(alphabet, floor(random() * length(alphabet))::INTEGER + 1, 1);
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM member_account WHERE member_no = candidate);
        END LOOP;

        UPDATE member_account SET member_no = candidate WHERE id = account_row.id;
    END LOOP;
END $$;

ALTER TABLE member_account ALTER COLUMN member_no SET NOT NULL;
ALTER TABLE member_account ADD CONSTRAINT member_account_member_no_format
    CHECK (member_no ~ '^[2-9A-HJ-NP-Z]{8}$');
ALTER TABLE member_account ADD CONSTRAINT member_account_member_no_unique UNIQUE (member_no);
