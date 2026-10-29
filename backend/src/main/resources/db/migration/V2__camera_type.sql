ALTER TABLE camera ADD COLUMN camera_type TEXT;

UPDATE camera SET camera_type = CASE
    WHEN name LIKE '%已废弃%' THEN 'DISCARD'
    WHEN name LIKE '%六环外%' THEN 'SIX_RING_OUT'
    WHEN name LIKE '%二环内%' THEN 'TWO_RING_IN'
    ELSE 'SIX_RING_IN'
END;

ALTER TABLE camera ALTER COLUMN camera_type SET NOT NULL;
ALTER TABLE camera ADD CONSTRAINT camera_type_values
    CHECK (camera_type IN ('DISCARD', 'SIX_RING_OUT', 'TWO_RING_IN', 'SIX_RING_IN'));
