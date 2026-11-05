package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
class CameraReportService {
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    CameraReportService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    Map<String, Object> submit(String nickname, String content, MultipartFile screenshot) throws IOException {
        String cleanNickname = nickname == null ? "" : nickname.trim();
        String cleanContent = content == null ? "" : content.trim();
        if (cleanNickname.isEmpty() || cleanNickname.length() > 30
                || cleanContent.isEmpty() || cleanContent.length() > 2000) {
            throw new IllegalArgumentException("昵称或上报内容为空，或超过长度限制");
        }
        if (screenshot == null || screenshot.isEmpty() || screenshot.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("请上传不超过 5MB 的截图");
        }
        byte[] bytes = screenshot.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("请上传不超过 5MB 的截图");
        }
        String type = imageType(bytes);
        long id = db.queryForObject("""
                INSERT INTO camera_report(nickname,content,screenshot_type,screenshot_data)
                VALUES (?,?,?,?) RETURNING id
                """, Long.class, cleanNickname, cleanContent, type, bytes);
        return Map.of("id", id, "status", "PENDING");
    }

    List<Map<String, Object>> list(String status, int offset, int limit) {
        String filter = status == null || status.isBlank() ? null : status.trim();
        if (filter != null && !List.of("PENDING", "APPROVED", "REJECTED").contains(filter)) {
            throw new IllegalArgumentException("无效的审核状态");
        }
        return db.queryForList("""
                SELECT id,nickname,content,status,created_at,reviewed_at,reviewed_by,
                       decision_reason,camera_id,publish_screenshot,
                       octet_length(screenshot_data) AS screenshot_bytes
                FROM camera_report WHERE (CAST(? AS text) IS NULL OR status=?)
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, filter, filter, Math.max(1, Math.min(limit, 100)), Math.max(offset, 0));
    }

    Map<String, Object> detail(long id) {
        return db.query("""
                SELECT id,nickname,content,status,created_at,reviewed_at,reviewed_by,
                       decision_reason,camera_id,screenshot_type,publish_screenshot,
                       octet_length(screenshot_data) AS screenshot_bytes
                FROM camera_report WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new ResourceMissingException("上报记录不存在");
            return Map.ofEntries(
                    Map.entry("id", rs.getLong("id")),
                    Map.entry("nickname", rs.getString("nickname")),
                    Map.entry("content", rs.getString("content")),
                    Map.entry("status", rs.getString("status")),
                    Map.entry("createdAt", rs.getObject("created_at")),
                    Map.entry("screenshotType", rs.getString("screenshot_type")),
                    Map.entry("screenshotBytes", rs.getInt("screenshot_bytes")),
                    Map.entry("publishScreenshot", rs.getBoolean("publish_screenshot")),
                    Map.entry("review", Map.of(
                            "reviewedAt", nullable(rs.getObject("reviewed_at")),
                            "reviewedBy", nullable(rs.getString("reviewed_by")),
                            "reason", nullable(rs.getString("decision_reason")),
                            "cameraId", nullable(rs.getObject("camera_id")))));
        }, id);
    }

    private static Object nullable(Object value) { return value == null ? "" : value; }

    ImageData screenshot(long id) {
        return db.query("SELECT screenshot_type,screenshot_data FROM camera_report WHERE id=?",
                rs -> {
                    if (!rs.next()) throw new ResourceMissingException("截图不存在");
                    return new ImageData(rs.getString(1), rs.getBytes(2));
                }, id);
    }

    ImageData approvedScreenshot(long cameraId) {
        return db.query("""
                SELECT r.screenshot_type,r.screenshot_data FROM camera_report r
                JOIN camera c ON c.id=r.camera_id
                WHERE r.camera_id=? AND r.status='APPROVED' AND r.publish_screenshot=true
                  AND c.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM camera_image i WHERE i.camera_id=r.camera_id
                              AND i.url=('/api/v1/cameras/' || r.camera_id || '/reported-image'))
                """, rs -> {
            if (!rs.next()) throw new ResourceMissingException("探头图片不存在");
            return new ImageData(rs.getString(1), rs.getBytes(2));
        }, cameraId);
    }

    @Transactional
    Map<String, Object> approve(long reportId, CameraReportController.ApprovalInput input, String reviewer) {
        ensurePending(reportId);
        if (!input.coordinateConfirmed()) {
            throw new IllegalArgumentException("请先确认坐标采用 GCJ-02 坐标系");
        }
        String name = input.name().trim();
        String normalized = ImportService.normalizeName(name);
        String typeRaw = input.typeCodeRaw() == null ? "" : input.typeCodeRaw().trim();
        db.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, normalized);
        List<Map<String, Object>> matches = db.queryForList("""
                SELECT id,longitude,latitude FROM camera
                WHERE deleted_at IS NULL AND coordinate_system='GCJ02' AND name_match=?
                """, normalized);
        for (Map<String, Object> existing : matches) {
            if (ImportService.distanceMeters(input.longitude(), input.latitude(),
                    (BigDecimal) existing.get("longitude"), (BigDecimal) existing.get("latitude")) <= 100) {
                throw new VersionConflictException("附近已有同名探头 #" + existing.get("id") + "，请先核对");
            }
        }
        long cameraId = db.queryForObject("""
                INSERT INTO camera(name,name_match,longitude,latitude,coordinate_system,
                                   type_code_raw,camera_type,manual_protected)
                VALUES (?,?,?,?,'GCJ02',?,?,true) RETURNING id
                """, Long.class, name, normalized, input.longitude(), input.latitude(),
                typeRaw, CameraType.fromName(name).name());
        int changed = db.update("""
                UPDATE camera_report SET status='APPROVED',reviewed_at=now(),reviewed_by=?,camera_id=?,
                                         publish_screenshot=?
                WHERE id=? AND status='PENDING'
                """, reviewer, cameraId, input.publishScreenshot(), reportId);
        if (changed != 1) throw new VersionConflictException("上报记录已被审核，请刷新");
        if (input.publishScreenshot()) {
            String imageUrl = "/api/v1/cameras/" + cameraId + "/reported-image";
            db.update("INSERT INTO camera_image(camera_id,url,url_hash) VALUES (?,?,?)",
                    cameraId, imageUrl, sha256(imageUrl));
        }
        try {
            Map<String, Object> created = db.queryForMap("""
                    SELECT id,name,longitude,latitude,coordinate_system,type_code_raw,camera_type,
                           manual_protected,version,created_at,updated_at FROM camera WHERE id=?
                    """, cameraId);
            db.update("""
                    INSERT INTO camera_audit_log(camera_id,actor,action,before_value,after_value)
                    VALUES (?,'admin','CREATE_FROM_REPORT',NULL,?::jsonb)
                    """, cameraId, json.writeValueAsString(Map.of("camera", created, "reportId", reportId,
                            "publishScreenshot", input.publishScreenshot())));
        } catch (Exception e) {
            throw new IllegalStateException("审核留痕写入失败", e);
        }
        return Map.of("reportId", reportId, "cameraId", cameraId, "status", "APPROVED",
                "publishScreenshot", input.publishScreenshot());
    }

    @Transactional
    Map<String, Object> reject(long reportId, String reason, String reviewer) {
        if (reason == null || reason.trim().isEmpty() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("请填写不超过 500 字的驳回原因");
        }
        ensurePending(reportId);
        db.update("""
                UPDATE camera_report SET status='REJECTED',reviewed_at=now(),reviewed_by=?,decision_reason=?
                WHERE id=? AND status='PENDING'
                """, reviewer, reason.trim(), reportId);
        return Map.of("reportId", reportId, "status", "REJECTED");
    }

    private void ensurePending(long id) {
        String status = db.query("SELECT status FROM camera_report WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (status == null) throw new ResourceMissingException("上报记录不存在");
        if (!"PENDING".equals(status)) throw new VersionConflictException("上报记录已被审核，请刷新");
    }

    private static String imageType(byte[] data) {
        if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P'
                && data[2] == 'N' && data[3] == 'G' && data[4] == 13
                && data[5] == 10 && data[6] == 26 && data[7] == 10) return MediaType.IMAGE_PNG_VALUE;
        if (data.length >= 3 && (data[0] & 0xff) == 0xff
                && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) return MediaType.IMAGE_JPEG_VALUE;
        if (data.length >= 12 && new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new IllegalArgumentException("截图仅支持 PNG、JPEG 或 WebP 图片");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record ImageData(String type, byte[] bytes) {}
}
