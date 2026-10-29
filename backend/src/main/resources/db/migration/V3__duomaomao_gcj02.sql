UPDATE data_source SET coordinate_system='GCJ02' WHERE code='duomaomao';

UPDATE camera c SET coordinate_system='GCJ02'
WHERE c.coordinate_system='UNKNOWN'
  AND EXISTS (
    SELECT 1 FROM camera_source_link l
    JOIN data_source s ON s.id=l.source_id
    WHERE l.camera_id=c.id AND s.code='duomaomao'
  );
