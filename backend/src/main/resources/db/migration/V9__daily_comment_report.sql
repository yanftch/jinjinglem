CREATE INDEX camera_comment_user_created_idx
    ON camera_comment(created_at, id) WHERE origin = 'USER';

CREATE TABLE daily_comment_report (
    report_date DATE PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('SENDING', 'SENT', 'FAILED')),
    comment_count INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
