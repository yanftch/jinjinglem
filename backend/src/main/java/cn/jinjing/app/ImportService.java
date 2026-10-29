package cn.jinjing.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

@Service
@EnableAsync
public class ImportService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Path importDirectory;

    public ImportService(JdbcTemplate db, TransactionTemplate transactions,
                         ObjectMapper mapper, @Value("${app.import-directory}") String importDirectory) {
        this.db = db;
        this.transactions = transactions;
        this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.importDirectory = Path.of(importDirectory).toAbsolutePath().normalize();
    }

    @Bean
    ApplicationRunner markInterruptedImports(JdbcTemplate jdbc) {
        return args -> jdbc.update("""
                UPDATE import_batch SET status='FAILED', error_message='Interrupted by application restart',
                finished_at=now() WHERE status IN ('QUEUED','RUNNING')
                """);
    }

    public long submit(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("empty file");
        byte[] bytes = file.getBytes();
        JsonNode root = json.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("cameras").isArray()) {
            throw new IllegalArgumentException("expected a JSON object with cameras array");
        }
        JsonNode cameras = root.get("cameras");
        if (!root.path("total").canConvertToInt() || root.path("total").asInt() != cameras.size()) {
            throw new IllegalArgumentException("total does not match cameras length");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode camera : cameras) {
            String id = sourceId(camera);
            if (id == null || !ids.add(id)) throw new IllegalArgumentException("missing or duplicated source id: " + id);
        }
        String generatedAt = root.path("generatedAt").asText("");
        if (!generatedAt.isBlank()) OffsetDateTime.parse(generatedAt);
        Files.createDirectories(importDirectory);
        String hash = sha256(bytes);
        Path saved = importDirectory.resolve(hash + ".json");
        if (!Files.exists(saved)) Files.write(saved, bytes);
        Long sourceId = db.queryForObject("SELECT id FROM data_source WHERE code='duomaomao'", Long.class);
        return db.queryForObject("""
                INSERT INTO import_batch (source_id,file_name,file_path,file_sha256,generated_at,status,total_count)
                VALUES (?,?,?,?,?::timestamptz,'QUEUED',?) RETURNING id
                """, Long.class, sourceId, file.getOriginalFilename() == null ? "upload.json" : Path.of(file.getOriginalFilename()).getFileName().toString(),
                saved.toString(), hash, generatedAt.isBlank() ? null : generatedAt, cameras.size());
    }

    @Async
    public void process(long batchId) {
        // The configured single-thread executor serializes imports within this application instance.
        db.update("UPDATE import_batch SET status='RUNNING', started_at=now() WHERE id=?", batchId);
        try {
            String path = db.queryForObject("SELECT file_path FROM import_batch WHERE id=?", String.class, batchId);
            JsonNode cameras = json.readTree(Files.readAllBytes(Path.of(path))).path("cameras");
            int inserted = 0, updated = 0, unchanged = 0, failed = 0, warnings = 0;
            for (int i = 0; i < cameras.size(); i++) {
                JsonNode camera = cameras.get(i);
                String id = sourceId(camera);
                try {
                    String result = transactions.execute(status -> apply(batchId, camera));
                    switch (result) {
                        case "INSERTED" -> inserted++;
                        case "UPDATED" -> updated++;
                        default -> unchanged++;
                    }
                    if (camera.hasNonNull("detailError")) warnings++;
                    record(batchId, i + 1, id, result, null);
                } catch (Exception ex) {
                    failed++;
                    record(batchId, i + 1, id, "FAILED", shortError(ex));
                }
            }
            db.update("""
                    UPDATE import_batch SET status=?, inserted_count=?, updated_count=?, unchanged_count=?,
                    failed_count=?, detail_warning_count=?, finished_at=now() WHERE id=?
                    """, failed == 0 ? "SUCCEEDED" : "PARTIAL_SUCCESS", inserted, updated, unchanged, failed, warnings, batchId);
        } catch (Exception ex) {
            db.update("UPDATE import_batch SET status='FAILED', error_message=?, finished_at=now() WHERE id=?",
                    shortError(ex), batchId);
        }
    }

    private String apply(long batchId, JsonNode row) {
        String sourceRecordId = sourceId(row);
        String name = requiredText(row, "name");
        String type = requiredText(row, "aa");
        String cameraType = CameraType.fromName(name).name();
        JsonNode position = row.path("position");
        if (!position.isArray() || position.size() != 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw new IllegalArgumentException("position must be [longitude,latitude]");
        }
        BigDecimal lng = position.get(0).decimalValue();
        BigDecimal lat = position.get(1).decimalValue();
        if (lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0
                || lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("coordinate outside valid range");
        }
        JsonNode comments = row.path("comments");
        if (!comments.isArray()) throw new IllegalArgumentException("comments must be an array");
        boolean detailFailed = row.hasNonNull("detailError");
        String payload = row.toString();
        String hash = sha256(payload.getBytes(StandardCharsets.UTF_8));
        String appliedHash = db.query("SELECT last_applied_hash FROM raw_duomaomao_camera WHERE source_record_id=?",
                rs -> rs.next() ? rs.getString(1) : null, sourceRecordId);
        Long cameraId = db.query("""
                SELECT camera_id FROM camera_source_link l JOIN data_source s ON s.id=l.source_id
                WHERE s.code='duomaomao' AND l.source_record_id=?
                """, rs -> rs.next() ? rs.getLong(1) : null, sourceRecordId);

        db.update("""
                INSERT INTO raw_duomaomao_camera
                    (source_record_id,raw_payload,content_hash,last_seen_batch_id,detail_status)
                VALUES (?,?::jsonb,?,?,?)
                ON CONFLICT (source_record_id) DO UPDATE SET raw_payload=EXCLUDED.raw_payload,
                    content_hash=EXCLUDED.content_hash,last_seen_batch_id=EXCLUDED.last_seen_batch_id,
                    detail_status=EXCLUDED.detail_status,last_seen_at=now()
                """, sourceRecordId, payload, hash, batchId, detailFailed ? "FAILED" : "COMPLETE");

        if (cameraId != null && hash.equals(appliedHash)) return "UNCHANGED";
        boolean inserted = cameraId == null;
        if (inserted) {
            cameraId = db.queryForObject("""
                    INSERT INTO camera(name,name_match,longitude,latitude,type_code_raw,camera_type,coordinate_system)
                    SELECT ?,?,?,?,?,?,coordinate_system FROM data_source WHERE code='duomaomao'
                    RETURNING id
                    """, Long.class, name, normalizeName(name), lng, lat, type, cameraType);
            db.update("""
                    INSERT INTO camera_source_link(camera_id,source_id,source_record_id)
                    SELECT ?,id,? FROM data_source WHERE code='duomaomao'
                    """, cameraId, sourceRecordId);
        } else {
            db.update("""
                    UPDATE camera SET
                      name=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN name ELSE ? END,
                      name_match=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN name_match ELSE ? END,
                      longitude=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN longitude ELSE ? END,
                      latitude=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN latitude ELSE ? END,
                      coordinate_system=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN coordinate_system
                        ELSE (SELECT coordinate_system FROM data_source WHERE code='duomaomao') END,
                      type_code_raw=CASE WHEN deleted_at IS NOT NULL THEN type_code_raw ELSE ? END,
                      camera_type=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN camera_type ELSE ? END,
                      updated_at=now(),version=version+1 WHERE id=?
                    """, name, normalizeName(name), lng, lat, type, cameraType, cameraId);
        }
        if (!detailFailed) {
            for (JsonNode comment : comments) {
                String nickname = requiredText(comment, "nickname");
                String content = requiredText(comment, "content");
                String time = requiredText(comment, "time");
                String fingerprint = sha256((nickname + "\u0000" + time + "\u0000" + content).getBytes(StandardCharsets.UTF_8));
                db.update("""
                        INSERT INTO camera_comment(camera_id,nickname,content,raw_time,parsed_time,fingerprint)
                        VALUES (?,?,?,?,?::timestamptz,?) ON CONFLICT (camera_id,fingerprint) DO NOTHING
                        """, cameraId, nickname, content, time, parseCommentTime(time), fingerprint);
            }
        }
        db.update("UPDATE raw_duomaomao_camera SET last_applied_hash=? WHERE source_record_id=?", hash, sourceRecordId);
        return inserted ? "INSERTED" : "UPDATED";
    }

    private void record(long batchId, int row, String id, String status, String error) {
        db.update("INSERT INTO import_record(batch_id,row_number,source_record_id,status,error_message) VALUES (?,?,?,?,?)",
                batchId, row, id, status, error);
    }

    static String normalizeName(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\p{Z}\\s]+", "")
                .replaceAll("[【\\[]六环外[】\\]]", "")
                .replaceAll("[【\\[]顺义高峰(?:期)?[】\\]]", "")
                .replaceAll("[【\\[](南向北|北向南|东向西|西向东)[】\\]]", "$1");
        return normalized;
    }

    private static String sourceId(JsonNode row) {
        JsonNode id = row.path("id");
        return id.isIntegralNumber() || id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("missing " + key);
        return value.asText();
    }

    private static String parseCommentTime(String value) {
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                    .atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String shortError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
