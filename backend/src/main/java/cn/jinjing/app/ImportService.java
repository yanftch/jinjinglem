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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
@EnableAsync
public class ImportService {
    private static final Pattern DIRECTION = Pattern.compile("南向北|北向南|东向西|西向东");
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
        return submitBytes(file.getBytes(), file.getOriginalFilename());
    }

    public long submitPath(Path file) throws IOException {
        return submitBytes(Files.readAllBytes(file), file.getFileName().toString());
    }

    private long submitBytes(byte[] bytes, String fileName) throws IOException {
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
                """, Long.class, sourceId, fileName == null ? "upload.json" : Path.of(fileName).getFileName().toString(),
                saved.toString(), hash, generatedAt.isBlank() ? null : generatedAt, cameras.size());
    }

    @Async
    public void process(long batchId) {
        processBlocking(batchId, null);
    }

    public void processBlocking(long batchId, Long syncRunId) {
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
                    ApplyResult result = transactions.execute(status -> apply(batchId, syncRunId, camera));
                    switch (result.status()) {
                        case "INSERTED" -> inserted++;
                        case "UPDATED" -> updated++;
                        default -> unchanged++;
                    }
                    if (camera.hasNonNull("detailError")) warnings++;
                    record(batchId, i + 1, id, result.status(), null, result.cameraId(), result.reviewNote());
                } catch (Exception ex) {
                    failed++;
                    record(batchId, i + 1, id, "FAILED", shortError(ex), null, null);
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

    private record ApplyResult(String status, Long cameraId, String reviewNote) {}

    private ApplyResult apply(long batchId, Long syncRunId, JsonNode row) {
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
        String rawHash = sha256(payload.getBytes(StandardCharsets.UTF_8));
        String hash = semanticHash(name, type, position, comments, row.path("images"), detailFailed);
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
                """, sourceRecordId, payload, rawHash, batchId, detailFailed ? "FAILED" : "COMPLETE");

        String reviewNote = null;
        List<Map<String, Object>> matches = db.queryForList("""
                SELECT id,longitude,latitude,manual_protected FROM camera
                WHERE name_match=? AND deleted_at IS NULL ORDER BY id
                """, normalizeName(name));
        List<Map<String, Object>> nearby = new ArrayList<>();
        for (Map<String, Object> candidate : matches) {
            long candidateId = ((Number) candidate.get("id")).longValue();
            if (cameraId != null && candidateId == cameraId) continue;
            double meters = distanceMeters(lng, lat, (BigDecimal) candidate.get("longitude"),
                    (BigDecimal) candidate.get("latitude"));
            if (meters <= 100) nearby.add(candidate);
            else if (reviewNote == null) reviewNote = "同名探头距离 " + Math.round(meters) + " 米，待人工核对：探头 " + candidateId;
        }
        boolean directionKnown = DIRECTION.matcher(normalizeName(name)).find();
        if (!nearby.isEmpty() && !directionKnown) {
            reviewNote = "同名探头缺少明确方向，待人工核对";
        }
        if (cameraId != null && deleted(cameraId)) {
            db.update("UPDATE raw_duomaomao_camera SET last_applied_hash=? WHERE source_record_id=?", hash, sourceRecordId);
            return new ApplyResult("UNCHANGED", cameraId, "人工删除的探头未自动恢复");
        }
        boolean linkedToExisting = false;
        if (directionKnown && !nearby.isEmpty()) {
            Map<String, Object> best = nearby.stream()
                    .sorted((a, b) -> {
                        int manual = Boolean.compare((Boolean) b.get("manual_protected"), (Boolean) a.get("manual_protected"));
                        return manual != 0 ? manual : Long.compare(((Number) a.get("id")).longValue(), ((Number) b.get("id")).longValue());
                    }).findFirst().orElseThrow();
            long candidateId = ((Number) best.get("id")).longValue();
            if (cameraId == null) {
                cameraId = candidateId;
                linkedToExisting = true;
                db.update("""
                        INSERT INTO camera_source_link(camera_id,source_id,source_record_id)
                        SELECT ?,id,? FROM data_source WHERE code='duomaomao'
                        """, cameraId, sourceRecordId);
                audit(cameraId, "SOURCE_LINK", null, Map.of("sourceRecordId", sourceRecordId), syncRunId, batchId);
            } else if (cameraId != candidateId) {
                boolean bothManual = protectedCamera(cameraId) && protectedCamera(candidateId);
                if (bothManual) {
                    reviewNote = "两个人工维护的同名近距探头，待人工核对";
                } else {
                    long winner = protectedCamera(cameraId) ? cameraId
                            : protectedCamera(candidateId) ? candidateId : Math.min(cameraId, candidateId);
                    long loser = winner == cameraId ? candidateId : cameraId;
                    merge(winner, loser, syncRunId, batchId);
                    cameraId = winner;
                    linkedToExisting = true;
                }
            }
        }
        String primaryRecordId = cameraId == null ? null : primarySourceRecordId(cameraId);
        if (cameraId != null && !sourceRecordId.equals(primaryRecordId)) {
            String baselineType = db.queryForObject("SELECT camera_type FROM camera WHERE id=?", String.class, cameraId);
            if (!cameraType.equals(baselineType)) {
                String conflict = "同一探头的来源类型不一致，当前以主来源记录为准";
                reviewNote = reviewNote == null ? conflict : reviewNote + "；" + conflict;
            }
        }
        if (cameraId != null && hash.equals(appliedHash) && !linkedToExisting) {
            return new ApplyResult("UNCHANGED", cameraId, reviewNote);
        }
        boolean inserted = cameraId == null;
        Map<String, Object> before = inserted ? null : cameraSnapshot(cameraId);
        boolean changed = inserted || linkedToExisting;
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
        } else if (sourceRecordId.equals(primaryRecordId)
                && cameraFieldsChanged(before, name, lng, lat, type, cameraType)) {
            db.update("""
                    UPDATE camera SET
                      name=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN name ELSE ? END,
                      name_match=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN name_match ELSE ? END,
                      longitude=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN longitude ELSE ? END,
                      latitude=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN latitude ELSE ? END,
                      coordinate_system=CASE WHEN manual_protected OR deleted_at IS NOT NULL THEN coordinate_system
                        ELSE (SELECT coordinate_system FROM data_source WHERE code='duomaomao') END,
                      type_code_raw=?,
                      camera_type=?,
                      updated_at=now(),version=version+1 WHERE id=?
                    """, name, normalizeName(name), lng, lat, type, cameraType, cameraId);
            changed = true;
        }
        if (!detailFailed) {
            for (JsonNode comment : comments) {
                String nickname = requiredText(comment, "nickname");
                String content = requiredText(comment, "content");
                String time = requiredText(comment, "time");
                String fingerprint = sha256((nickname + "\u0000" + time + "\u0000" + content).getBytes(StandardCharsets.UTF_8));
                Long commentId = db.query("""
                        INSERT INTO camera_comment(camera_id,nickname,content,raw_time,parsed_time,fingerprint)
                        VALUES (?,?,?,?,?::timestamptz,?) ON CONFLICT (camera_id,fingerprint) DO NOTHING RETURNING id
                        """, rs -> rs.next() ? rs.getLong(1) : null,
                        cameraId, nickname, content, time, parseCommentTime(time), fingerprint);
                if (commentId != null) {
                    changed = true;
                    audit(cameraId, "COMMENT_ADD", null,
                            Map.of("commentId", commentId, "nickname", nickname, "content", content, "time", time),
                            syncRunId, batchId);
                }
            }
        }
        JsonNode images = row.path("images");
        if (images.isArray()) {
            for (JsonNode image : images) {
                if (!image.isTextual()) continue;
                String url = image.asText().trim();
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
                String fingerprint = sha256(url.getBytes(StandardCharsets.UTF_8));
                Long imageId = db.query("""
                        INSERT INTO camera_image(camera_id,url,url_hash) VALUES (?,?,?)
                        ON CONFLICT (camera_id,url_hash) DO NOTHING RETURNING id
                        """, rs -> rs.next() ? rs.getLong(1) : null, cameraId, url, fingerprint);
                if (imageId != null) {
                    changed = true;
                    audit(cameraId, "IMAGE_ADD", null,
                            Map.of("imageId", imageId, "url", url), syncRunId, batchId);
                }
            }
        }
        db.update("UPDATE raw_duomaomao_camera SET last_applied_hash=? WHERE source_record_id=?", hash, sourceRecordId);
        Map<String, Object> after = cameraSnapshot(cameraId);
        if (inserted || !before.equals(after)) {
            audit(cameraId, inserted ? "INSERT" : "UPDATE", before, after, syncRunId, batchId);
        }
        return new ApplyResult(inserted ? "INSERTED" : changed ? "UPDATED" : "UNCHANGED", cameraId, reviewNote);
    }

    private void record(long batchId, int row, String id, String status, String error, Long cameraId, String reviewNote) {
        db.update("""
                INSERT INTO import_record(batch_id,row_number,source_record_id,status,error_message,camera_id,review_note)
                VALUES (?,?,?,?,?,?,?)
                """, batchId, row, id, status, error, cameraId, reviewNote);
    }

    private boolean deleted(long cameraId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT deleted_at IS NOT NULL FROM camera WHERE id=?", Boolean.class, cameraId));
    }

    private boolean protectedCamera(long cameraId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT manual_protected FROM camera WHERE id=?", Boolean.class, cameraId));
    }

    private String primarySourceRecordId(long cameraId) {
        return db.query("""
                SELECT l.source_record_id FROM camera_source_link l JOIN data_source s ON s.id=l.source_id
                WHERE l.camera_id=? AND s.code='duomaomao' ORDER BY l.id LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, cameraId);
    }

    private Map<String, Object> cameraSnapshot(long cameraId) {
        return db.queryForMap("""
                SELECT id,name,name_match,longitude,latitude,coordinate_system,type_code_raw,camera_type,
                       manual_protected,deleted_at,version FROM camera WHERE id=?
                """, cameraId);
    }

    private boolean cameraFieldsChanged(Map<String, Object> before, String name, BigDecimal lng, BigDecimal lat,
                                        String type, String cameraType) {
        if (!type.equals(before.get("type_code_raw")) || !cameraType.equals(before.get("camera_type"))) return true;
        if (Boolean.TRUE.equals(before.get("manual_protected"))) return false;
        String sourceCoordinate = db.queryForObject("SELECT coordinate_system FROM data_source WHERE code='duomaomao'", String.class);
        return !name.equals(before.get("name")) || !normalizeName(name).equals(before.get("name_match"))
                || lng.compareTo((BigDecimal) before.get("longitude")) != 0
                || lat.compareTo((BigDecimal) before.get("latitude")) != 0
                || !sourceCoordinate.equals(before.get("coordinate_system"));
    }

    private void merge(long winner, long loser, Long syncRunId, long batchId) {
        Map<String, Object> beforeWinner = cameraSnapshot(winner);
        Map<String, Object> beforeLoser = cameraSnapshot(loser);
        db.update("UPDATE camera_source_link SET camera_id=? WHERE camera_id=?", winner, loser);
        for (Map<String, Object> comment : db.queryForList("""
                SELECT id,nickname,content,raw_time,parsed_time,fingerprint FROM camera_comment WHERE camera_id=?
                """, loser)) {
            Long copiedId = db.query("""
                    INSERT INTO camera_comment(camera_id,nickname,content,raw_time,parsed_time,fingerprint)
                    VALUES (?,?,?,?,?,?) ON CONFLICT (camera_id,fingerprint) DO NOTHING RETURNING id
                    """, rs -> rs.next() ? rs.getLong(1) : null, winner, comment.get("nickname"),
                    comment.get("content"), comment.get("raw_time"), comment.get("parsed_time"), comment.get("fingerprint"));
            if (copiedId != null) audit(winner, "COMMENT_MERGE",
                    Map.of("cameraId", loser, "commentId", comment.get("id"), "nickname", comment.get("nickname"),
                            "content", comment.get("content"), "time", comment.get("raw_time")),
                    Map.of("cameraId", winner, "commentId", copiedId, "nickname", comment.get("nickname"),
                            "content", comment.get("content"), "time", comment.get("raw_time")), syncRunId, batchId);
        }
        for (Map<String, Object> image : db.queryForList("SELECT id,url,url_hash FROM camera_image WHERE camera_id=?", loser)) {
            Long copiedId = db.query("""
                    INSERT INTO camera_image(camera_id,url,url_hash) VALUES (?,?,?)
                    ON CONFLICT (camera_id,url_hash) DO NOTHING RETURNING id
                    """, rs -> rs.next() ? rs.getLong(1) : null, winner, image.get("url"), image.get("url_hash"));
            if (copiedId != null) audit(winner, "IMAGE_MERGE",
                    Map.of("cameraId", loser, "imageId", image.get("id"), "url", image.get("url")),
                    Map.of("cameraId", winner, "imageId", copiedId, "url", image.get("url")), syncRunId, batchId);
        }
        db.update("UPDATE camera SET deleted_at=now(),version=version+1,updated_at=now() WHERE id=?", loser);
        audit(winner, "MERGE_IN", beforeWinner, Map.of("mergedCameraId", loser, "camera", cameraSnapshot(winner)),
                syncRunId, batchId);
        audit(loser, "MERGED_INTO", beforeLoser, Map.of("cameraId", winner, "camera", cameraSnapshot(loser)),
                syncRunId, batchId);
    }

    private void audit(long cameraId, String action, Object before, Object after, Long syncRunId, long batchId) {
        try {
            db.update("""
                    INSERT INTO camera_audit_log(camera_id,actor,action,before_value,after_value,sync_run_id,import_batch_id)
                    VALUES (?,?,?,?::jsonb,?::jsonb,?,?)
                    """, cameraId, syncRunId == null ? "import:duomaomao" : "sync:duomaomao", action,
                    before == null ? null : json.writeValueAsString(before),
                    after == null ? null : json.writeValueAsString(after), syncRunId, batchId);
        } catch (Exception e) {
            throw new IllegalStateException("audit write failed", e);
        }
    }

    static double distanceMeters(BigDecimal lng1, BigDecimal lat1, BigDecimal lng2, BigDecimal lat2) {
        double p1 = Math.toRadians(lat1.doubleValue());
        double p2 = Math.toRadians(lat2.doubleValue());
        double dLat = p2 - p1;
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String semanticHash(String name, String type, JsonNode position,
                                       JsonNode comments, JsonNode images, boolean detailFailed) {
        TreeSet<String> normalizedComments = new TreeSet<>();
        if (!detailFailed) {
            for (JsonNode comment : comments) {
                normalizedComments.add(comment.path("nickname").asText() + "\u0000"
                        + comment.path("time").asText() + "\u0000" + comment.path("content").asText());
            }
        }
        TreeSet<String> normalizedImages = new TreeSet<>();
        if (images.isArray()) for (JsonNode image : images) if (image.isTextual()) normalizedImages.add(image.asText());
        String content = normalizeName(name) + "\u0000" + type + "\u0000" + position.toString()
                + "\u0000" + detailFailed + "\u0000" + String.join("\u0001", normalizedComments)
                + "\u0000" + String.join("\u0001", normalizedImages);
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    static String normalizeName(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\p{Z}\\s]+", "")
                .replaceAll("[【\\[](六环外|六环内|二环内|已废弃)[】\\]]", "")
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
