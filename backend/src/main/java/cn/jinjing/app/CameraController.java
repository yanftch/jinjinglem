package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
public class CameraController {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public CameraController(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record CameraInput(@NotBlank String name,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                              @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                              @NotNull String typeCodeRaw,
                              Long version) {}

    @GetMapping("/api/v1/cameras")
    public List<Map<String, Object>> list(@RequestParam(required = false) String name,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String cameraType,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "100") int limit) {
        return db.queryForList("""
                SELECT id,name,longitude,latitude,coordinate_system,type_code_raw,camera_type,manual_protected,version
                FROM camera WHERE deleted_at IS NULL
                  AND (CAST(? AS text) IS NULL OR name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR type_code_raw = ?)
                  AND (CAST(? AS text) IS NULL OR camera_type = ?)
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, name, name, type, type, cameraType, cameraType,
                Math.max(1, Math.min(limit, 500)), Math.max(offset, 0));
    }

    @GetMapping("/api/v1/cameras/count")
    public Map<String, Long> count(@RequestParam(required = false) String name,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String cameraType) {
        Long total = db.queryForObject("""
                SELECT count(*) FROM camera WHERE deleted_at IS NULL
                  AND (CAST(? AS text) IS NULL OR name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR type_code_raw = ?)
                  AND (CAST(? AS text) IS NULL OR camera_type = ?)
                """, Long.class, name, name, type, type, cameraType, cameraType);
        return Map.of("total", total == null ? 0L : total);
    }

    @GetMapping("/api/v1/cameras/map")
    public Map<String, Object> map(@RequestParam BigDecimal west, @RequestParam BigDecimal south,
                                          @RequestParam BigDecimal east, @RequestParam BigDecimal north,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) String cameraType,
                                          @RequestParam(defaultValue = "5000") int limit) {
        if (west.compareTo(east) > 0 || south.compareTo(north) > 0) throw new IllegalArgumentException("invalid bounds");
        int pageSize = Math.max(1, Math.min(limit, 10000));
        List<Map<String, Object>> items = db.queryForList("""
                SELECT id,name,longitude,latitude,type_code_raw,camera_type FROM camera
                WHERE deleted_at IS NULL AND coordinate_system='GCJ02'
                  AND longitude BETWEEN ? AND ? AND latitude BETWEEN ? AND ?
                  AND (CAST(? AS text) IS NULL OR name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR camera_type = ?)
                ORDER BY id LIMIT ?
                """, west, east, south, north, name, name, cameraType, cameraType, pageSize + 1);
        boolean truncated = items.size() > pageSize;
        if (truncated) items = items.subList(0, pageSize);
        return Map.of("items", items, "truncated", truncated);
    }

    @GetMapping("/api/v1/cameras/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return db.query("""
                SELECT id,name,longitude,latitude,coordinate_system,type_code_raw,camera_type,manual_protected,version,created_at,updated_at
                FROM camera WHERE id=? AND deleted_at IS NULL
                """, rs -> {
            if (!rs.next()) throw new ResourceMissingException("camera not found");
            return Map.of("id", rs.getLong("id"), "name", rs.getString("name"),
                    "longitude", rs.getBigDecimal("longitude"), "latitude", rs.getBigDecimal("latitude"),
                    "coordinateSystem", rs.getString("coordinate_system"), "typeCodeRaw", rs.getString("type_code_raw"),
                    "cameraType", rs.getString("camera_type"),
                    "manualProtected", rs.getBoolean("manual_protected"), "version", rs.getLong("version"));
        }, id);
    }

    @GetMapping("/api/v1/cameras/{id}/comments")
    public List<Map<String, Object>> comments(@PathVariable long id,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "100") int limit) {
        return db.queryForList("""
                SELECT cc.id,cc.nickname,cc.content,cc.raw_time,cc.parsed_time
                FROM camera_comment cc JOIN camera c ON c.id=cc.camera_id
                WHERE cc.camera_id=? AND c.deleted_at IS NULL
                ORDER BY cc.parsed_time DESC NULLS LAST,cc.id DESC LIMIT ? OFFSET ?
                """, id, Math.max(1, Math.min(500, limit)), Math.max(0, offset));
    }

    @GetMapping("/api/v1/cameras/{id}/images")
    public List<Map<String, Object>> images(@PathVariable long id) {
        return db.queryForList("""
                SELECT ci.id,ci.url FROM camera_image ci JOIN camera c ON c.id=ci.camera_id
                WHERE ci.camera_id=? AND c.deleted_at IS NULL ORDER BY ci.id
                """, id);
    }

    @PostMapping("/api/v1/admin/cameras")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CameraInput input) {
        long id = db.queryForObject("""
                INSERT INTO camera(name,name_match,longitude,latitude,type_code_raw,camera_type,manual_protected)
                VALUES (?,?,?,?,?,?,true) RETURNING id
                """, Long.class, input.name(), ImportService.normalizeName(input.name()),
                input.longitude(), input.latitude(), input.typeCodeRaw(), CameraType.fromName(input.name()).name());
        audit(id, "CREATE", null, detail(id));
        return Map.of("id", id);
    }

    @PatchMapping("/api/v1/admin/cameras/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody CameraInput input) {
        if (input.version() == null) throw new IllegalArgumentException("version is required");
        Map<String, Object> before = detail(id);
        int changed = db.update("""
                UPDATE camera SET name=?,name_match=?,longitude=?,latitude=?,type_code_raw=?,camera_type=?,
                manual_protected=true,version=version+1,updated_at=now()
                WHERE id=? AND version=? AND deleted_at IS NULL
                """, input.name(), ImportService.normalizeName(input.name()), input.longitude(),
                input.latitude(), input.typeCodeRaw(), CameraType.fromName(input.name()).name(), id, input.version());
        if (changed == 0) throw new VersionConflictException("camera changed; reload and retry");
        audit(id, "UPDATE", before, detail(id));
        return detail(id);
    }

    @DeleteMapping("/api/v1/admin/cameras/{id}")
    @Transactional
    public void delete(@PathVariable long id, @RequestParam long version) {
        Map<String, Object> before = snapshot(id);
        int changed = db.update("""
                UPDATE camera SET deleted_at=now(),manual_protected=true,version=version+1,updated_at=now()
                WHERE id=? AND version=? AND deleted_at IS NULL
                """, id, version);
        if (changed == 0) throw new VersionConflictException("camera changed or missing");
        audit(id, "DELETE", before, snapshot(id));
    }

    @PostMapping("/api/v1/admin/cameras/{id}/restore")
    @Transactional
    public void restore(@PathVariable long id) {
        Map<String, Object> before = snapshot(id);
        int changed = db.update("""
                UPDATE camera SET deleted_at=NULL,manual_protected=true,version=version+1,updated_at=now()
                WHERE id=? AND deleted_at IS NOT NULL
                """, id);
        if (changed == 0) throw new ResourceMissingException("deleted camera not found");
        audit(id, "RESTORE", before, snapshot(id));
    }

    private Map<String, Object> snapshot(long id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id,name,longitude,latitude,coordinate_system,type_code_raw,camera_type,
                       manual_protected,deleted_at,version FROM camera WHERE id=?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void audit(long id, String action, Object before, Object after) {
        try {
            db.update("""
                    INSERT INTO camera_audit_log(camera_id,actor,action,before_value,after_value)
                    VALUES (?,'admin',?,?::jsonb,?::jsonb)
                    """, id, action, before == null ? null : json.writeValueAsString(before),
                    after == null ? null : json.writeValueAsString(after));
        } catch (Exception e) {
            throw new IllegalStateException("audit write failed", e);
        }
    }
}
