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
    private final CloudApiProperties cloud;
    private final boolean scheduledEnabled;
    private final String cron;
    private final Path archiveDirectory;

    SyncService(JdbcTemplate db, SyncRunner runner, CloudApiProperties cloud,
                @Value("${app.sync-enabled:false}") boolean scheduledEnabled,
                @Value("${app.sync-cron:0 0 3 * * *}") String cron,
                @Value("${app.snapshot-directory:}") String snapshotDirectory) {
        this.db = db;
        this.runner = runner;
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
                    WHERE status IN ('QUEUED','FETCHING','ARCHIVING','IMPORTING')
                    """);
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
                    INSERT INTO sync_run(source_code,trigger_type,status,phase_message)
                    VALUES ('duomaomao',?,'QUEUED','等待执行') RETURNING id
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
        return result;
    }
}
