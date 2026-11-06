ALTER TABLE camera_report ALTER COLUMN screenshot_type DROP NOT NULL;
ALTER TABLE camera_report ALTER COLUMN screenshot_data DROP NOT NULL;
ALTER TABLE camera_report ADD CONSTRAINT camera_report_screenshot_pair_check
    CHECK ((screenshot_type IS NULL) = (screenshot_data IS NULL));
