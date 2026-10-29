package cn.jinjing.app;

import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.service.ExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
class SyncRunner {
    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);
    private final JdbcTemplate db;
    private final ImportService imports;
    private final ObjectProvider<ExportService> exporter;
    private final Path archiveDirectory;

    SyncRunner(JdbcTemplate db, ImportService imports, ObjectProvider<ExportService> exporter,
               @Value("${app.snapshot-directory:}") String snapshotDirectory) {
        this.db = db;
        this.imports = imports;
        this.exporter = exporter;
        this.archiveDirectory = SyncPaths.archiveDirectory(snapshotDirectory);
    }

    @Async("syncExecutor")
    public void execute(long id) {
        try {
            phase(id, "FETCHING", "正在拉取探头列表");
            ExportService service = exporter.getObject();
            service.start(null, true);
            ExportStatus status;
            do {
                Thread.sleep(1000);
                status = service.status();
                db.update("""
                        UPDATE sync_run SET fetch_done=?,fetch_total=?,detail_failures=?,phase_message=? WHERE id=?
                        """, status.done(), status.total(), status.failed(), status.message(), id);
            } while ("RUNNING".equals(status.state()));
            if (!"DONE".equals(status.state())) throw new IllegalStateException(status.error());
            if (status.total() == 0) throw new IllegalStateException("来源返回了空探头列表，本次不导入");
            phase(id, "ARCHIVING", "正在归档抓取文件");
            Path archived = archive(service.exportFilePath());
            String hash = sha256(Files.readAllBytes(archived));
            db.update("UPDATE sync_run SET archive_path=?,archive_sha256=? WHERE id=?", archived.toString(), hash, id);
            event(id, "ARCHIVED", "JSON 已保存：" + archived.getFileName());
            long batchId = imports.submitPath(archived);
            db.update("UPDATE sync_run SET import_batch_id=? WHERE id=?", batchId, id);
            phase(id, "IMPORTING", "正在清洗、去重并更新探头");
            imports.processBlocking(batchId, id);
            String importStatus = db.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId);
            String outcome = "SUCCEEDED".equals(importStatus) && status.failed() > 0 ? "PARTIAL_SUCCESS"
                    : "SUCCEEDED".equals(importStatus) ? "SUCCEEDED"
                    : "PARTIAL_SUCCESS".equals(importStatus) ? "PARTIAL_SUCCESS" : "FAILED";
            db.update("UPDATE sync_run SET status=?,phase_message=?,finished_at=now() WHERE id=?",
                    outcome, "SUCCEEDED".equals(outcome) ? "同步完成"
                            : "PARTIAL_SUCCESS".equals(outcome) ? "同步完成，部分记录失败" : "导入失败", id);
            event(id, outcome, "抓取与导入结束");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(id, "INTERRUPTED", "抓取任务被中断");
        } catch (Exception e) {
            log.error("duomaomao sync {} failed", id, e);
            fail(id, "FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Path archive(Path source) throws IOException {
        Files.createDirectories(archiveDirectory);
        String date = LocalDate.now(SyncService.ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        Path target = archiveDirectory.resolve("duomaomao_" + date + ".json");
        Path temp = Files.createTempFile(archiveDirectory, "duomaomao-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private void phase(long id, String phase, String message) {
        db.update("UPDATE sync_run SET status=?,phase_message=?,started_at=COALESCE(started_at,now()) WHERE id=?",
                phase, message, id);
        event(id, phase, message);
    }

    private void event(long id, String type, String message) {
        db.update("INSERT INTO sync_event(run_id,event_type,message) VALUES (?,?,?)", id, type, message);
    }

    private void fail(long id, String status, String message) {
        String safe = message == null ? "未知错误" : message.substring(0, Math.min(500, message.length()));
        db.update("UPDATE sync_run SET status=?,phase_message='任务失败',error_message=?,finished_at=now() WHERE id=?",
                status, safe, id);
        event(id, status, safe);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
