package cn.jinjing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class ReviewService {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    ReviewService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    void prepare(long runId, long batchId) throws IOException {
        String path = db.queryForObject("SELECT file_path FROM import_batch WHERE id=?", String.class, batchId);
        JsonNode cameras = json.readTree(Files.readAllBytes(Path.of(path))).path("cameras");
        for (int index = 0; index < cameras.size(); index++) {
            JsonNode row = cameras.get(index);
            Preview preview = preview(row);
            db.update("""
                    INSERT INTO sync_review_item
                      (run_id,batch_id,row_number,source_record_id,name,predicted_status,camera_id,
                       camera_version,current_type,proposed_type,new_comment_count,new_image_count,
                       current_snapshot,source_payload,review_note)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                    """, runId, batchId, index + 1, row.path("id").asText(), preview.name(),
                    preview.status(), preview.cameraId(), preview.cameraVersion(), preview.currentType(),
                    preview.proposedType(), preview.newComments(), preview.newImages(), preview.currentJson(),
                    row.toString(), preview.note());
            if ((index + 1) % 100 == 0 || index + 1 == cameras.size()) {
                db.update("UPDATE sync_run SET review_done=? WHERE id=?", index + 1, runId);
            }
        }
        db.update("UPDATE import_batch SET status='AWAITING_REVIEW' WHERE id=?", batchId);
    }

    private Preview preview(JsonNode row) {
        String name = row.path("name").asText("");
        Long cameraId = null;
        Long version = null;
        String currentType = null;
        String currentJson = null;
        String proposedType = null;
        try {
            String sourceId = row.path("id").asText();
            if (name.isBlank() || !row.path("aa").isTextual() || row.path("aa").asText().isBlank()) {
                throw new IllegalArgumentException("名称或原始类型缺失");
            }
            JsonNode position = row.path("position");
            if (!position.isArray() || position.size() != 2 || !position.get(0).isNumber()
                    || !position.get(1).isNumber()) throw new IllegalArgumentException("坐标格式错误");
            BigDecimal lng = position.get(0).decimalValue();
            BigDecimal lat = position.get(1).decimalValue();
            if (lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0
                    || lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new IllegalArgumentException("坐标超出范围");
            }
            JsonNode comments = row.path("comments");
            if (!comments.isArray()) throw new IllegalArgumentException("评论格式错误");
            proposedType = CameraType.fromName(name).name();
            cameraId = db.query("""
                    SELECT l.camera_id FROM camera_source_link l JOIN data_source s ON s.id=l.source_id
                    WHERE s.code='duomaomao' AND l.source_record_id=?
                    """, rs -> rs.next() ? rs.getLong(1) : null, sourceId);
            Map<String, Object> current = null;
            if (cameraId != null) {
                List<Map<String, Object>> rows = db.queryForList("""
                        SELECT id,name,longitude,latitude,type_code_raw,camera_type,manual_protected,
                               deleted_at,version FROM camera WHERE id=?
                        """, cameraId);
                if (!rows.isEmpty()) {
                    current = rows.get(0);
                    version = ((Number) current.get("version")).longValue();
                    currentType = (String) current.get("camera_type");
                    currentJson = json.writeValueAsString(current);
                    if (current.get("deleted_at") != null) {
                        return new Preview(name, "SKIP_DELETED", cameraId, version, currentType,
                                proposedType, 0, 0, currentJson, "人工删除的探头不会自动恢复");
                    }
                }
            }
            int newComments = row.hasNonNull("detailError") ? 0 : newComments(cameraId, comments);
            int newImages = newImages(cameraId, row.path("images"));
            if (cameraId != null) {
                String applied = db.query("SELECT last_applied_hash FROM raw_duomaomao_camera WHERE source_record_id=?",
                        rs -> rs.next() ? rs.getString(1) : null, sourceId);
                String proposed = ImportService.semanticHash(name, row.path("aa").asText(), position,
                        comments, row.path("images"), row.hasNonNull("detailError"));
                if (proposed.equals(applied)) {
                    return new Preview(name, "UNCHANGED", cameraId, version, currentType, proposedType,
                            0, 0, currentJson, "与上次已入库内容相同");
                }
                String note = "来源内容有变化";
                if (!proposedType.equals(currentType)) note += "；类型变化";
                if (newComments > 0) note += "；新增评论 " + newComments + " 条";
                if (newImages > 0) note += "；新增图片 " + newImages + " 张";
                if (row.hasNonNull("detailError")) note += "；详情抓取失败，旧评论会保留";
                return new Preview(name, "UPDATE", cameraId, version, currentType, proposedType,
                        newComments, newImages, currentJson, note);
            }
            List<Map<String, Object>> matches = db.queryForList("""
                    SELECT id,name,longitude,latitude,type_code_raw,camera_type,manual_protected,
                           deleted_at,version FROM camera
                    WHERE name_match=? AND deleted_at IS NULL ORDER BY id
                    """, ImportService.normalizeName(name));
            long near = matches.stream().filter(candidate -> ImportService.distanceMeters(lng, lat,
                    (BigDecimal) candidate.get("longitude"), (BigDecimal) candidate.get("latitude")) <= 100).count();
            boolean directionKnown = name.matches(".*(南向北|北向南|东向西|西向东).*");
            String status = matches.isEmpty() ? "NEW" : near == 1 && directionKnown ? "LINK" : "NEEDS_REVIEW";
            String note = matches.isEmpty() ? "新来源探头" : near == 1 && directionKnown
                    ? "可能与现有同名近距探头合并" : "同名探头距离或方向存在疑问，请逐条确认";
            if (near == 1 && directionKnown) {
                Map<String, Object> target = matches.stream().filter(candidate -> ImportService.distanceMeters(lng, lat,
                        (BigDecimal) candidate.get("longitude"), (BigDecimal) candidate.get("latitude")) <= 100)
                        .findFirst().orElseThrow();
                cameraId = ((Number) target.get("id")).longValue();
                currentType = (String) target.get("camera_type");
                version = ((Number) target.get("version")).longValue();
                currentJson = json.writeValueAsString(target);
            }
            return new Preview(name, status, cameraId, version, currentType, proposedType,
                    newComments, newImages, currentJson, note);
        } catch (Exception e) {
            return new Preview(name, "INVALID", cameraId, version, currentType, proposedType,
                    0, 0, currentJson, e.getMessage() == null ? "记录格式错误" : e.getMessage());
        }
    }

    private int newComments(Long cameraId, JsonNode comments) {
        if (comments.isEmpty()) return 0;
        Set<String> existing = cameraId == null ? Set.of() : new HashSet<>(db.queryForList(
                "SELECT fingerprint FROM camera_comment WHERE camera_id=?", String.class, cameraId));
        Set<String> proposed = new HashSet<>();
        for (JsonNode comment : comments) {
            if (!comment.path("nickname").isTextual() || !comment.path("content").isTextual()
                    || !comment.path("time").isTextual() || comment.path("nickname").asText().isBlank()
                    || comment.path("content").asText().isBlank() || comment.path("time").asText().isBlank()) {
                throw new IllegalArgumentException("评论字段缺失");
            }
            String value = comment.path("nickname").asText() + "\u0000" + comment.path("time").asText()
                    + "\u0000" + comment.path("content").asText();
            proposed.add(sha256(value));
        }
        proposed.removeAll(existing);
        return proposed.size();
    }

    private int newImages(Long cameraId, JsonNode images) {
        if (!images.isArray()) return 0;
        Set<String> existing = cameraId == null ? Set.of() : new HashSet<>(db.queryForList(
                "SELECT url_hash FROM camera_image WHERE camera_id=?", String.class, cameraId));
        Set<String> proposed = new HashSet<>();
        for (JsonNode image : images) {
            String url = image.asText("").trim();
            if (url.startsWith("https://") || url.startsWith("http://")) proposed.add(sha256(url));
        }
        proposed.removeAll(existing);
        return proposed.size();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    void decide(long runId, int rowNumber, String decision, String reason) {
        String status = db.query("SELECT status FROM sync_run WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        if (status == null) throw new ResourceMissingException("抓取任务不存在");
        if (!"AWAITING_REVIEW".equals(status)) throw new VersionConflictException("该批次当前不能修改审核决定");
        Map<String, Object> item = db.query("""
                SELECT source_record_id,predicted_status,decision FROM sync_review_item
                WHERE run_id=? AND row_number=? FOR UPDATE
                """, rs -> rs.next() ? Map.of("sourceId", rs.getString(1), "prediction", rs.getString(2),
                "decision", rs.getString(3)) : null, runId, rowNumber);
        if (item == null) throw new ResourceMissingException("审核记录不存在");
        String previous = (String) item.get("decision");
        if ("CONFIRMED".equals(decision) && !"NEEDS_REVIEW".equals(item.get("prediction"))) {
            throw new IllegalArgumentException("只有待核对记录需要单独确认");
        }
        if ("PENDING".equals(decision) && "PENDING".equals(previous)) return;
        if (!"PENDING".equals(previous) && !"PENDING".equals(decision)) {
            throw new VersionConflictException("请先撤销原审核决定");
        }
        db.update("""
                UPDATE sync_review_item SET decision=?,decision_reason=?,decided_at=CASE WHEN ?='PENDING'
                THEN NULL ELSE now() END WHERE run_id=? AND row_number=?
                """, decision, reason, decision, runId, rowNumber);
        db.update("""
                INSERT INTO sync_event(run_id,event_type,message,details)
                VALUES (?,?,?,jsonb_build_object('rowNumber',?,'sourceId',?,'previous',?,'decision',?,'reason',?))
                """, runId, "REVIEW_" + decision, "管理员审核来源记录 " + item.get("sourceId"),
                rowNumber, item.get("sourceId"), previous, decision, reason);
    }

    private record Preview(String name, String status, Long cameraId, Long cameraVersion,
                           String currentType, String proposedType, int newComments, int newImages,
                           String currentJson, String note) {}
}
