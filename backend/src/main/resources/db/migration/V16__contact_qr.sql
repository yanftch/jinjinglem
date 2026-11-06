CREATE TABLE contact_qr (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    content_type VARCHAR(16) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    image_data BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT NOT NULL
);
