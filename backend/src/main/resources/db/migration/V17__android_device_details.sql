ALTER TABLE android_user
    ADD COLUMN manufacturer VARCHAR(100),
    ADD COLUMN brand VARCHAR(100),
    ADD COLUMN model VARCHAR(100),
    ADD COLUMN device_name VARCHAR(100),
    ADD COLUMN android_version VARCHAR(50),
    ADD COLUMN sdk_int INTEGER,
    ADD COLUMN app_version VARCHAR(50);
