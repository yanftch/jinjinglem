package cn.jinjing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/sync")
class SyncController {
    private final JdbcTemplate db;
    private final SyncService sync;
    private final ObjectMapper mapper;

    SyncController(JdbcTemplate db, SyncService sync, ObjectMapper mapper) {
        this.db = db;
        this.sync = sync;
        this.mapper = mapper;
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        return sync.config();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> runNow() {
        return Map.of("runId", sync.start("MANUAL"), "status", "QUEUED");
    }

    @GetMapping("/runs")
    List<Map<String, Object>> runs(@RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "20") int limit) {
        return db.queryForList("""
                SELECT r.id,r.trigger_type,r.status,r.phase_message,r.fetch_done,r.fetch_total,
                       r.detail_failures,r.archive_sha256,r.import_batch_id,r.error_message,
                       r.created_at,r.started_at,r.finished_at,
                       b.total_count,b.inserted_count,b.updated_count,b.unchanged_count,
                       b.failed_count,b.detail_warning_count
                FROM sync_run r LEFT JOIN import_batch b ON b.id=r.import_batch_id
                ORDER BY r.id DESC LIMIT ? OFFSET ?
                """, size(limit), Math.max(0, offset));
    }

    @GetMapping("/runs/{id}")
    Map<String, Object> run(@PathVariable long id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT r.id,r.trigger_type,r.status,r.phase_message,r.fetch_done,r.fetch_total,
                       r.detail_failures,r.archive_path,r.archive_sha256,r.import_batch_id,r.error_message,
                       r.created_at,r.started_at,r.finished_at,
                       b.status AS import_status,b.total_count,b.inserted_count,b.updated_count,
                       b.unchanged_count,b.failed_count,b.detail_warning_count,b.error_message AS import_error,
                       (SELECT count(*) FROM import_record x WHERE x.batch_id=r.import_batch_id) AS imported_done
                FROM sync_run r LEFT JOIN import_batch b ON b.id=r.import_batch_id WHERE r.id=?
                """, id);
        if (rows.isEmpty()) throw new ResourceMissingException("sync run not found");
        return rows.get(0);
    }

    @GetMapping("/runs/{id}/records")
    List<Map<String, Object>> records(@PathVariable long id,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "false") boolean reviewOnly) {
        assertRun(id);
        return db.queryForList("""
                SELECT x.row_number,x.source_record_id,x.camera_id,x.status,x.error_message,x.review_note
                FROM import_record x JOIN sync_run r ON r.import_batch_id=x.batch_id
                WHERE r.id=? AND (CAST(? AS text) IS NULL OR x.status=?)
                  AND (NOT ? OR x.review_note IS NOT NULL)
                ORDER BY x.row_number LIMIT ? OFFSET ?
                """, id, status, status, reviewOnly, size(limit), Math.max(0, offset));
    }

    @GetMapping("/runs/{id}/records/{sourceId}")
    JsonNode rawRecord(@PathVariable long id, @PathVariable String sourceId) throws IOException {
        Map<String, Object> run = run(id);
        if (run.get("import_batch_id") == null) throw new ResourceMissingException("import file not found");
        String path = db.queryForObject("SELECT file_path FROM import_batch WHERE id=?", String.class,
                run.get("import_batch_id"));
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) throw new ResourceMissingException("import file missing");
        JsonNode cameras = mapper.readTree(file.toFile()).path("cameras");
        for (JsonNode camera : cameras) {
            if (sourceId.equals(camera.path("id").asText())) return camera;
        }
        throw new ResourceMissingException("source record not found");
    }

    @GetMapping("/runs/{id}/events")
    List<Map<String, Object>> events(@PathVariable long id,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "100") int limit) {
        assertRun(id);
        return db.queryForList("""
                SELECT id,event_type,message,details::text AS details,created_at FROM sync_event
                WHERE run_id=? ORDER BY id LIMIT ? OFFSET ?
                """, id, size(limit), Math.max(0, offset));
    }

    @GetMapping("/runs/{id}/audit")
    List<Map<String, Object>> runAudit(@PathVariable long id,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "100") int limit) {
        assertRun(id);
        return db.queryForList("""
                SELECT id,camera_id,actor,action,before_value::text AS before_value,
                       after_value::text AS after_value,created_at
                FROM camera_audit_log WHERE sync_run_id=? ORDER BY id DESC LIMIT ? OFFSET ?
                """, id, size(limit), Math.max(0, offset));
    }

    @GetMapping("/audit")
    List<Map<String, Object>> audit(@RequestParam(required = false) Long cameraId,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "100") int limit) {
        return db.queryForList("""
                SELECT id,camera_id,actor,action,sync_run_id,import_batch_id,
                       before_value::text AS before_value,after_value::text AS after_value,created_at
                FROM camera_audit_log
                WHERE (CAST(? AS bigint) IS NULL OR camera_id=?)
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, cameraId, cameraId, size(limit), Math.max(0, offset));
    }

    @GetMapping("/runs/{id}/file")
    ResponseEntity<FileSystemResource> file(@PathVariable long id) {
        Map<String, Object> run = run(id);
        Object batch = run.get("import_batch_id");
        String path = batch == null ? (String) run.get("archive_path")
                : db.queryForObject("SELECT file_path FROM import_batch WHERE id=?", String.class, batch);
        if (path == null) throw new ResourceMissingException("archive not found");
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) throw new ResourceMissingException("archive file missing");
        if (batch == null) {
            try {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                if (!actual.equals(run.get("archive_sha256"))) throw new ResourceMissingException("archive has been replaced");
            } catch (ResourceMissingException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("cannot verify archive", e);
            }
        }
        String name = run.get("archive_path") == null ? file.getFileName().toString()
                : Path.of(run.get("archive_path").toString()).getFileName().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(file));
    }

    private void assertRun(long id) {
        Integer count = db.queryForObject("SELECT count(*) FROM sync_run WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new ResourceMissingException("sync run not found");
    }

    private int size(int requested) {
        return Math.max(1, Math.min(requested, 500));
    }
}
