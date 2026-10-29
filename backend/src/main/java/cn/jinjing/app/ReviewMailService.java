package cn.jinjing.app;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
class ReviewMailService {
    private static final Logger log = LoggerFactory.getLogger(ReviewMailService.class);
    private final JdbcTemplate db;
    private final String credentialsFile;
    private final String configuredFrom;
    private final String configuredCode;
    private final String configuredTo;
    private final String publicBaseUrl;

    ReviewMailService(JdbcTemplate db,
                      @Value("${app.mail-credentials-file:}") String credentialsFile,
                      @Value("${app.mail-from:}") String configuredFrom,
                      @Value("${app.mail-auth-code:}") String configuredCode,
                      @Value("${app.mail-to:}") String configuredTo,
                      @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.db = db;
        this.credentialsFile = credentialsFile;
        this.configuredFrom = configuredFrom;
        this.configuredCode = configuredCode;
        this.configuredTo = configuredTo;
        this.publicBaseUrl = publicBaseUrl;
    }

    boolean configured() {
        try {
            MailConfig config = config();
            return !config.from().isBlank() && !config.authCode().isBlank() && !config.to().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    void send(long runId) {
        int claimed = db.update("""
                UPDATE sync_run SET mail_status='SENDING',mail_error=NULL
                WHERE id=? AND mail_status IN ('PENDING','FAILED','UNCONFIGURED')
                """, runId);
        if (claimed == 0) return;
        MailConfig config;
        try {
            config = config();
            if (config.from().isBlank() || config.authCode().isBlank() || config.to().isBlank()) {
                db.update("UPDATE sync_run SET mail_status='UNCONFIGURED',mail_error='未配置邮件凭据' WHERE id=?", runId);
                return;
            }
        } catch (Exception e) {
            db.update("UPDATE sync_run SET mail_status='UNCONFIGURED',mail_error=? WHERE id=?", safe(e), runId);
            return;
        }
        try {
            Map<String, Object> run = db.queryForMap("""
                    SELECT id,status,fetch_total,detail_failures,error_message,
                           to_char(created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI') AS created_time
                    FROM sync_run WHERE id=?
                    """, runId);
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("smtp.163.com");
            sender.setPort(465);
            sender.setUsername(config.from());
            sender.setPassword(config.authCode());
            sender.setDefaultEncoding("UTF-8");
            Properties properties = sender.getJavaMailProperties();
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.connectiontimeout", "10000");
            properties.put("mail.smtp.timeout", "30000");
            properties.put("mail.smtp.writetimeout", "30000");
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(config.from());
            helper.setTo(config.to());
            helper.setSubject("进京了么 · 探头抓取审核摘要 · 第 " + runId + " 次");
            helper.setText(report(run), true);
            sender.send(message);
            db.update("UPDATE sync_run SET mail_status='SENT',mail_error=NULL,mail_sent_at=now() WHERE id=?", runId);
            db.update("INSERT INTO sync_event(run_id,event_type,message) VALUES (?,'MAIL_SENT','审核摘要邮件已发送')", runId);
        } catch (Exception e) {
            log.warn("sync review email {} failed: {}", runId, e.toString());
            db.update("UPDATE sync_run SET mail_status='FAILED',mail_error=? WHERE id=?", safe(e), runId);
            db.update("INSERT INTO sync_event(run_id,event_type,message) VALUES (?,'MAIL_FAILED',?)", runId, safe(e));
        }
    }

    private String report(Map<String, Object> run) {
        long runId = ((Number) run.get("id")).longValue();
        List<Map<String, Object>> counts = db.queryForList("""
                SELECT predicted_status,count(*) AS total FROM sync_review_item
                WHERE run_id=? GROUP BY predicted_status ORDER BY predicted_status
                """, runId);
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT row_number,name,predicted_status,current_type,proposed_type,
                       new_comment_count,new_image_count,review_note
                FROM sync_review_item WHERE run_id=? AND predicted_status NOT IN ('UNCHANGED','SKIP_DELETED')
                ORDER BY row_number LIMIT 100
                """, runId);
        StringBuilder html = new StringBuilder("<html><body style='font-family:Arial,sans-serif;color:#25343a'>");
        html.append("<h2>进京了么 · 探头抓取审核摘要</h2><p>抓取时间：")
                .append(escape(run.get("created_time"))).append("（北京时间）</p>")
                .append("<p>任务编号：").append(runId).append("；状态：")
                .append(switch (String.valueOf(run.get("status"))) {
                    case "FAILED" -> "抓取失败";
                    case "SUCCEEDED" -> "已审核入库";
                    case "PARTIAL_SUCCESS" -> "已审核入库，部分成功";
                    default -> "等待人工审核";
                })
                .append("；抓取总数：").append(run.get("fetch_total"))
                .append("；详情失败：").append(run.get("detail_failures")).append("</p>");
        if (run.get("error_message") != null) html.append("<p>错误：").append(escape(run.get("error_message"))).append("</p>");
        html.append("<h3>分类汇总</h3><table border='1' cellpadding='7' cellspacing='0' style='border-collapse:collapse'>")
                .append("<tr><th>类别</th><th>数量</th></tr>");
        for (Map<String, Object> count : counts) {
            html.append("<tr><td>").append(escape(statusName(count.get("predicted_status"))))
                    .append("</td><td>").append(count.get("total")).append("</td></tr>");
        }
        html.append("</table><h3>需要关注的记录（最多展示 100 条）</h3>")
                .append("<table border='1' cellpadding='7' cellspacing='0' style='border-collapse:collapse'>")
                .append("<tr><th>序号</th><th>探头名称</th><th>预计变化</th><th>类型变化</th><th>新增评论</th><th>新增图片</th><th>说明</th></tr>");
        for (Map<String, Object> row : rows) {
            html.append("<tr><td>").append(row.get("row_number")).append("</td><td>")
                    .append(escape(row.get("name"))).append("</td><td>")
                    .append(escape(statusName(row.get("predicted_status")))).append("</td><td>")
                    .append(escape(typeName(row.get("current_type")))).append(" → ")
                    .append(escape(typeName(row.get("proposed_type")))).append("</td><td>")
                    .append(row.get("new_comment_count")).append("</td><td>")
                    .append(row.get("new_image_count")).append("</td><td>")
                    .append(escape(row.get("review_note"))).append("</td></tr>");
        }
        html.append("</table><p>本邮件仅为抓取预览，未更新统一探头数据。请打开审核页面确认后入库。</p>");
        if (!publicBaseUrl.isBlank()) {
            html.append("<p><a href='").append(escape(publicBaseUrl.replaceAll("/+$", "")))
                    .append("/review.html?runId=").append(runId).append("'>打开审核页面</a></p>");
        }
        return html.append("</body></html>").toString();
    }

    private MailConfig config() throws Exception {
        Map<String, String> values = new HashMap<>();
        if (!credentialsFile.isBlank()) {
            for (String line : Files.readAllLines(Path.of(credentialsFile))) {
                if (line.isBlank() || line.stripLeading().startsWith("#") || !line.contains("=")) continue;
                String[] pair = line.split("=", 2);
                values.put(pair[0].trim(), pair[1].trim());
            }
        }
        String from = configuredFrom.isBlank() ? values.getOrDefault("SMTP_EMAIL", "") : configuredFrom;
        String code = configuredCode.isBlank() ? values.getOrDefault("SMTP_AUTH_CODE", "") : configuredCode;
        String to = configuredTo.isBlank() ? from : configuredTo;
        return new MailConfig(from, code, to);
    }

    private String statusName(Object value) {
        return switch (String.valueOf(value)) {
            case "NEW" -> "新增";
            case "UPDATE" -> "更新";
            case "LINK" -> "关联现有探头";
            case "NEEDS_REVIEW" -> "待人工核对";
            case "INVALID" -> "数据无效";
            case "UNCHANGED" -> "无变化";
            case "SKIP_DELETED" -> "人工删除，跳过";
            default -> String.valueOf(value);
        };
    }

    private String typeName(Object value) {
        if (value == null) return "—";
        return switch (String.valueOf(value)) {
            case "SIX_RING_IN" -> "六环内";
            case "SIX_RING_OUT" -> "六环外";
            case "TWO_RING_IN" -> "二环内";
            case "DISCARD" -> "已废弃";
            default -> "—";
        };
    }

    private String escape(Object value) {
        return String.valueOf(value == null ? "—" : value).replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String safe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.substring(0, Math.min(500, message.length()));
    }

    private record MailConfig(String from, String authCode, String to) {}
}
