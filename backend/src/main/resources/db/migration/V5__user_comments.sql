ALTER TABLE camera_comment
    ADD COLUMN origin TEXT NOT NULL DEFAULT 'SOURCE';

ALTER TABLE camera_comment
    ADD CONSTRAINT camera_comment_origin_check CHECK (origin IN ('SOURCE', 'USER'));
