package cn.jinjing.app;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/v1/admin/imports")
public class ImportController {
    private final ImportService imports;
    private final JdbcTemplate db;

    public ImportController(ImportService imports, JdbcTemplate db) {
        this.imports = imports;
        this.db = db;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        long id = imports.submit(file);
        imports.process(id);
        return Map.of("batchId", id, "status", "QUEUED");
    }

    @GetMapping("/{id}")
    public Map<String, Object> batch(@PathVariable long id) {
        return db.query("""
                SELECT id,status,total_count,inserted_count,updated_count,unchanged_count,
                       failed_count,detail_warning_count,started_at,finished_at,error_message
                FROM import_batch WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new ResourceMissingException("batch not found");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getLong("id"));
            result.put("status", rs.getString("status"));
            result.put("total", rs.getInt("total_count"));
            result.put("inserted", rs.getInt("inserted_count"));
            result.put("updated", rs.getInt("updated_count"));
            result.put("unchanged", rs.getInt("unchanged_count"));
            result.put("failed", rs.getInt("failed_count"));
            result.put("detailWarnings", rs.getInt("detail_warning_count"));
            result.put("startedAt", rs.getObject("started_at"));
            result.put("finishedAt", rs.getObject("finished_at"));
            result.put("error", rs.getString("error_message"));
            return result;
        }, id);
    }

    @GetMapping("/{id}/records")
    public List<Map<String, Object>> records(@PathVariable long id,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "100") int limit,
                                              @RequestParam(required = false) String status) {
        int size = Math.max(1, Math.min(500, limit));
        if (status == null || status.isBlank()) {
            return db.queryForList("""
                    SELECT row_number,source_record_id,status,error_message FROM import_record
                    WHERE batch_id=? ORDER BY row_number LIMIT ? OFFSET ?
                    """, id, size, Math.max(0, offset));
        }
        return db.queryForList("""
                SELECT row_number,source_record_id,status,error_message FROM import_record
                WHERE batch_id=? AND status=? ORDER BY row_number LIMIT ? OFFSET ?
                """, id, status, size, Math.max(0, offset));
    }
}
