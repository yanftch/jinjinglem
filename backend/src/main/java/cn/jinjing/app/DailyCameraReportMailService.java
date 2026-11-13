package cn.jinjing.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
class DailyCameraReportMailService {
    private static final Logger log = LoggerFactory.getLogger(DailyCameraReportMailService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final ReviewMailService mail;
    private final boolean enabled;
    private final String recipient;

    DailyCameraReportMailService(JdbcTemplate db, ReviewMailService mail,
                                 @Value("${app.camera-report-mail-enabled:false}") boolean enabled,
                                 @Value("${app.camera-report-mail-to:qiuyanfeng678@163.com}") String recipient) {
        this.db = db;
        this.mail = mail;
        this.enabled = enabled;
        this.recipient = recipient;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    void daily() {
        if (enabled) sendYesterday();
    }

    @EventListener(ApplicationReadyEvent.class)
    void afterStartup() {
        if (!enabled) return;
        db.update("UPDATE daily_camera_report_mail SET status='FAILED',error_message='应用重启时发送中断',updated_at=now() WHERE status='SENDING'");
        sendYesterday();
    }

    private void sendYesterday() {
        try {
            send(LocalDate.now(ZONE).minusDays(1));
        } catch (Exception e) {
            log.error("cannot send daily camera report email ({})", e.getClass().getSimpleName());
        }
    }

    void send(LocalDate date) {
        if (date.isAfter(LocalDate.now(ZONE).minusDays(1))) {
            throw new IllegalArgumentException("只能发送已结束日期的用户上报日报");
        }
        List<LocalDate> claimed = db.query("""
                INSERT INTO daily_camera_report_mail(report_date,status,attempts)
                VALUES (?,'SENDING',1)
                ON CONFLICT (report_date) DO UPDATE SET
                    status='SENDING',attempts=daily_camera_report_mail.attempts+1,
                    error_message=NULL,updated_at=now()
                WHERE daily_camera_report_mail.status='FAILED'
                RETURNING report_date
                """, (rs, row) -> rs.getObject(1, LocalDate.class), date);
        if (claimed.isEmpty()) return;

        try {
            List<Map<String, Object>> reports = reports(date);
            String subject = "进京了 · 用户上报日报 · " + date + " · " + reports.size() + " 条上报";
            mail.sendPlainText(recipient, subject, render(date, reports));
            db.update("UPDATE daily_camera_report_mail SET status='SENT',report_count=?,sent_at=now(),error_message=NULL,updated_at=now() WHERE report_date=?",
                    reports.size(), date);
        } catch (Exception e) {
            String error = ErrorSanitizer.sanitize(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            error = error.substring(0, Math.min(500, error.length()));
            db.update("UPDATE daily_camera_report_mail SET status='FAILED',error_message=?,updated_at=now() WHERE report_date=?", error, date);
            log.warn("daily camera report email {} failed: {}", date, error);
        }
    }

    List<Map<String, Object>> reports(LocalDate date) {
        return db.queryForList("""
                SELECT id,nickname,content,status,
                       to_char(created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') AS report_time
                FROM camera_report WHERE created_at>=? AND created_at<?
                ORDER BY created_at DESC,id DESC
                """, start(date), start(date.plusDays(1)));
    }

    static String render(LocalDate date, List<Map<String, Object>> rows) {
        StringBuilder body = new StringBuilder("进京了 · 用户上报日报\n")
                .append("统计日期：").append(date).append("（北京时间 00:00—次日 00:00）\n")
                .append("上报数量：").append(rows.size()).append(" 条\n\n");
        if (rows.isEmpty()) return body.append("昨日没有新的用户上报。\n").toString();
        for (Map<String, Object> row : rows) {
            body.append("上报编号：#").append(row.get("id"))
                    .append("\n时间：").append(row.get("report_time"))
                    .append("\n昵称：").append(row.get("nickname"))
                    .append("\n状态：").append(statusName(row.get("status")))
                    .append("\n内容：\n").append(row.get("content")).append("\n\n---\n\n");
        }
        return body.toString();
    }

    private static String statusName(Object status) {
        return switch (String.valueOf(status)) {
            case "PENDING" -> "待审核";
            case "APPROVED" -> "已通过";
            case "REJECTED" -> "已驳回";
            default -> String.valueOf(status);
        };
    }

    private static Timestamp start(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(ZONE).toInstant());
    }
}
