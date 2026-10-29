package cn.jinjing.app;

import com.duomaomao.api.config.CloudApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class SyncService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private final JdbcTemplate db;
    private final SyncRunner runner;
    private final ReviewMailService mail;
    private final CloudApiProperties cloud;
    private final boolean scheduledEnabled;
    private final String cron;
    private final Path archiveDirectory;

    SyncService(JdbcTemplate db, SyncRunner runner, ReviewMailService mail, CloudApiProperties cloud,
                @Value("${app.sync-enabled:false}") boolean scheduledEnabled,
                @Value("${app.sync-cron:0 0 3 * * *}") String cron,
                @Value("${app.snapshot-directory:}") String snapshotDirectory) {
        this.db = db;
        this.runner = runner;
        this.mail = mail;
        this.cloud = cloud;
        this.scheduledEnabled = scheduledEnabled;
        this.cron = cron;
        this.archiveDirectory = SyncPaths.archiveDirectory(snapshotDirectory);
    }

    @Bean
    ApplicationRunner markInterruptedSyncRuns(JdbcTemplate jdbc) {
        return args -> {
            jdbc.update("""
                    UPDATE sync_run SET status='INTERRUPTED',phase_message='应用重启，任务已中断',
                    error_message='Interrupted by application restart',finished_at=now()
                    WHERE status IN ('QUEUED','FETCHING','ARCHIVING','PREPARING_REVIEW','IMPORTING')
                    """);
            jdbc.update("UPDATE sync_run SET mail_status='FAILED',mail_error='应用重启时邮件发送中断' WHERE mail_status='SENDING'");
        };
    }

    @Scheduled(cron = "${app.sync-cron:0 0 3 * * *}", zone = "Asia/Shanghai")
    void daily() {
        if (!scheduledEnabled || !configured()) return;
        try {
            start("SCHEDULED");
        } catch (VersionConflictException e) {
            log.info("duomaomao sync already running");
        } catch (Exception e) {
            log.error("cannot schedule duomaomao sync", e);
        }
    }

    long start(String trigger) {
        if (!configured()) throw new IllegalArgumentException("未配置躲猫猫凭据，请设置 DUOMAOMAO_BEARER_TOKEN 或 DUOMAOMAO_BEARER_TOKEN_FILE");
        if (!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger)) throw new IllegalArgumentException("invalid trigger");
        long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO sync_run(source_code,trigger_type,status,phase_message,mail_status)
                    VALUES ('duomaomao',?,'QUEUED','等待执行','PENDING') RETURNING id
                    """, Long.class, trigger);
        } catch (DataIntegrityViolationException e) {
            throw new VersionConflictException("已有躲猫猫抓取任务正在运行");
        }
        db.update("INSERT INTO sync_event(run_id,event_type,message) VALUES (?,'QUEUED','任务已创建')", id);
        try {
            runner.execute(id);
        } catch (RuntimeException e) {
            db.update("UPDATE sync_run SET status='FAILED',error_message=?,finished_at=now() WHERE id=?", e.getMessage(), id);
            throw e;
        }
        return id;
    }

    void approve(long id) {
        Integer unresolved = db.queryForObject("""
                SELECT count(*) FROM sync_review_item WHERE run_id=?
                  AND predicted_status IN ('NEEDS_REVIEW','INVALID') AND decision='PENDING'
                """, Integer.class, id);
        if (unresolved != null && unresolved > 0) {
            throw new VersionConflictException("还有 " + unresolved + " 条待核对或无效记录，请逐条确认或排除");
        }
        Integer stale = db.queryForObject("""
                SELECT count(*) FROM sync_review_item r JOIN camera c ON c.id=r.camera_id
                WHERE r.run_id=? AND r.decision<>'REJECTED' AND r.camera_version IS NOT NULL
                  AND c.version<>r.camera_version
                  AND NOT EXISTS (SELECT 1 FROM import_record i WHERE i.batch_id=r.batch_id
                                  AND i.row_number=r.row_number AND i.status IN ('INSERTED','UPDATED','UNCHANGED'))
                """, Integer.class, id);
        if (stale != null && stale > 0) {
            throw new VersionConflictException("有 " + stale + " 条探头在抓取后发生修改，请重新抓取并审核");
        }
        Integer changedLinks = db.queryForObject("""
                SELECT count(*) FROM sync_review_item r
                JOIN data_source s ON s.code='duomaomao'
                JOIN camera_source_link l ON l.source_id=s.id AND l.source_record_id=r.source_record_id
                WHERE r.run_id=? AND r.decision<>'REJECTED'
                  AND (r.camera_id IS NULL OR l.camera_id<>r.camera_id)
                  AND NOT EXISTS (SELECT 1 FROM import_record i WHERE i.batch_id=r.batch_id
                                  AND i.row_number=r.row_number AND i.status IN ('INSERTED','UPDATED','UNCHANGED'))
                """, Integer.class, id);
        if (changedLinks != null && changedLinks > 0) {
            throw new VersionConflictException("有 " + changedLinks + " 条来源记录在审核期间已被其他批次关联，请重新抓取并审核");
        }
        Long batchId;
        try {
            batchId = db.query("""
                    UPDATE sync_run SET status='IMPORTING',phase_message='管理员已确认，正在更新统一探头数据库',
                    error_message=NULL,finished_at=NULL
                    WHERE id=? AND status='AWAITING_REVIEW' AND import_batch_id IS NOT NULL
                    RETURNING import_batch_id
                    """, rs -> rs.next() ? rs.getLong(1) : null, id);
        } catch (DataIntegrityViolationException e) {
            throw new VersionConflictException("同一来源已有任务正在运行，请稍后审核入库");
        }
        if (batchId == null) throw new VersionConflictException("该任务当前不能审核入库");
        db.update("INSERT INTO sync_event(run_id,event_type,message) VALUES (?,'REVIEW_APPROVED','管理员确认整批入库')", id);
        runner.applyApproved(id, batchId);
    }

    void retryMail(long id) {
        String status = db.query("SELECT status FROM sync_run WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (status == null) throw new ResourceMissingException("抓取任务不存在");
        if (!"AWAITING_REVIEW".equals(status) && !"FAILED".equals(status)
                && !"SUCCEEDED".equals(status) && !"PARTIAL_SUCCESS".equals(status)) {
            throw new VersionConflictException("抓取尚未结束，不能发送摘要邮件");
        }
        mail.send(id);
    }

    boolean configured() {
        if (cloud.getBearerToken() != null && !cloud.getBearerToken().isBlank()) return true;
        if (cloud.getBearerTokenFile() != null && !cloud.getBearerTokenFile().isBlank()) {
            return readableToken(Path.of(cloud.getBearerTokenFile()));
        }
        return readableToken(Path.of("apikey.txt")) || readableToken(Path.of("../_work/apikey.txt"))
                || readableToken(Path.of("_work/apikey.txt"));
    }

    private boolean readableToken(Path path) {
        try {
            return Files.isReadable(path) && !Files.readString(path).isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        result.put("scheduledEnabled", scheduledEnabled);
        result.put("cron", cron);
        result.put("timeZone", ZONE.toString());
        result.put("nextRunAt", scheduledEnabled ? CronExpression.parse(cron).next(ZonedDateTime.now(ZONE)) : null);
        result.put("archiveDirectory", archiveDirectory.toString());
        result.put("mailConfigured", mail.configured());
        return result;
    }
}
