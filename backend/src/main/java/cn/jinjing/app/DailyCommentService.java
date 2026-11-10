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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class DailyCommentService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(DailyCommentService.class);
    private final JdbcTemplate db;
    private final ReviewMailService mail;
    private final boolean enabled;
    private final String recipient;

    DailyCommentService(JdbcTemplate db, ReviewMailService mail,
                        @Value("${app.comment-report-enabled:false}") boolean enabled,
                        @Value("${app.comment-report-to:qiuyanfeng678@163.com}") String recipient) {
        this.db = db;
        this.mail = mail;
        this.enabled = enabled;
        this.recipient = recipient;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    void daily() {
        if (enabled) sendYesterday();
    }

    @EventListener(ApplicationReadyEvent.class)
    void afterStartup() {
        if (!enabled) return;
        db.update("UPDATE daily_comment_report SET status='FAILED',error_message='应用重启时发送中断',updated_at=now() WHERE status='SENDING'");
        sendYesterday();
    }

    private void sendYesterday() {
        try {
            send(LocalDate.now(ZONE).minusDays(1));
        } catch (Exception e) {
            log.error("cannot send daily comment report ({})", e.getClass().getSimpleName());
        }
    }

    void send(LocalDate date) {
        if (date.isAfter(LocalDate.now(ZONE).minusDays(1))) {
            throw new IllegalArgumentException("只能发送已结束日期的评论报表");
        }
        List<LocalDate> claimed = db.query("""
                INSERT INTO daily_comment_report(report_date,status,attempts)
                VALUES (?,'SENDING',1)
                ON CONFLICT (report_date) DO UPDATE SET
                    status='SENDING', attempts=daily_comment_report.attempts+1,
                    error_message=NULL, updated_at=now()
                WHERE daily_comment_report.status='FAILED'
                RETURNING report_date
                """, (rs, row) -> rs.getObject(1, LocalDate.class), date);
        if (claimed.isEmpty()) return;
        try {
            List<Map<String, Object>> comments = comments(date, 0, Integer.MAX_VALUE);
            String subject = "进京了 · " + date + " 新增评论日报（" + comments.size() + " 条）";
            mail.sendPlainText(recipient, subject, renderReport(date, comments));
            db.update("""
                    UPDATE daily_comment_report SET status='SENT',comment_count=?,sent_at=now(),
                    error_message=NULL,updated_at=now() WHERE report_date=?
                    """, comments.size(), date);
        } catch (Exception e) {
            String error = ErrorSanitizer.sanitize(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            db.update("""
                    UPDATE daily_comment_report SET status='FAILED',error_message=?,updated_at=now()
                    WHERE report_date=?
                    """, error.substring(0, Math.min(500, error.length())), date);
            log.warn("daily comment report {} failed: {}", date, error);
        }
    }

    Summary summary(LocalDate date) {
        Map<String, Object> report = db.query("""
                SELECT status,error_message,sent_at FROM daily_comment_report WHERE report_date=?
                """, rs -> rs.next() ? Map.of("status", rs.getString("status"),
                "error", rs.getString("error_message") == null ? "" : rs.getString("error_message"),
                "sent", rs.getTimestamp("sent_at") == null ? "" : rs.getTimestamp("sent_at").toInstant().toString()) : null, date);
        int count = db.queryForObject("""
                SELECT count(*) FROM camera_comment WHERE origin='USER' AND created_at>=? AND created_at<?
                """, Integer.class, start(date), start(date.plusDays(1)));
        return new Summary(date, count, report == null ? "PENDING" : (String) report.get("status"),
                report == null ? "" : (String) report.get("error"),
                report == null ? "" : (String) report.get("sent"), recipient);
    }

    List<Map<String, Object>> comments(LocalDate date, int offset, int limit) {
        return db.queryForList("""
                SELECT cc.id,cc.camera_id,c.name AS camera_name,cc.nickname,cc.content,
                       to_char(cc.created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') AS comment_time
                FROM camera_comment cc JOIN camera c ON c.id=cc.camera_id
                WHERE cc.origin='USER' AND cc.created_at>=? AND cc.created_at<?
                ORDER BY cc.created_at DESC,cc.id DESC LIMIT ? OFFSET ?
                """, start(date), start(date.plusDays(1)), limit, offset);
    }

    static String renderReport(LocalDate date, List<Map<String, Object>> rows) {
        StringBuilder body = new StringBuilder("# 进京了 · 新增评论日报\n\n统计日期：")
                .append(date).append("（北京时间 00:00—次日 00:00）\n")
                .append("新增评论：").append(rows.size()).append(" 条\n\n");
        if (rows.isEmpty()) return body.append("昨日没有新增用户评论。\n").toString();
        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String camera = String.valueOf(row.get("camera_name"));
            groups.computeIfAbsent(camera, unused -> new StringBuilder())
                    .append(row.get("nickname")).append(" · ").append(row.get("comment_time"))
                    .append("\n").append(row.get("content")).append("\n\n");
        }
        groups.forEach((camera, comments) -> body.append("# ").append(camera).append("\n\n").append(comments));
        return body.toString();
    }

    private Timestamp start(LocalDate date) {
        Instant instant = date.atStartOfDay(ZONE).toInstant();
        return Timestamp.from(instant);
    }

    record Summary(LocalDate date, int count, String mailStatus, String mailError, String sentAt,
                   String recipient) {}
}
