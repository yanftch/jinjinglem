package cn.jinjing.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_DB_URL", matches = ".+")
@SpringBootTest
@TestPropertySource(properties = {"app.auth-enabled=false", "app.comment-report-enabled=false",
        "app.mail-credentials-file=", "app.mail-from=", "app.mail-auth-code="})
class DailyCommentIntegrationTest {
    @Autowired DailyCommentService service;
    @Autowired JdbcTemplate db;

    @Test
    void selectsOnlyUserCommentsInsideBeijingCalendarDay() {
        LocalDate day = LocalDate.of(2030, 1, 2);
        String name = "日报测试" + UUID.randomUUID();
        long cameraId = db.queryForObject("""
                INSERT INTO camera(name,name_match,longitude,latitude,coordinate_system,type_code_raw,camera_type,manual_protected)
                VALUES (?, ?, ?, ?, 'GCJ02', '', 'SIX_RING_IN', true) RETURNING id
                """, Long.class, name, name, new BigDecimal("116.4"), new BigDecimal("39.9"));
        try {
            var start = day.atStartOfDay(DailyCommentService.ZONE).toInstant();
            add(cameraId, "USER", start.minusSeconds(1));
            long first = add(cameraId, "USER", start);
            long last = add(cameraId, "USER", start.plusSeconds(86399));
            add(cameraId, "USER", start.plusSeconds(86400));
            add(cameraId, "SOURCE", start.plusSeconds(10));
            var rows = service.comments(day, 0, 50);
            assertThat(rows).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(last, first);
            assertThat(service.summary(day).count()).isEqualTo(2);
        } finally {
            db.update("DELETE FROM camera_comment WHERE camera_id=?", cameraId);
            db.update("DELETE FROM camera WHERE id=?", cameraId);
        }
    }

    @Test
    void failedMailIsPersistedAndCanBeRetried() {
        LocalDate day = LocalDate.now(DailyCommentService.ZONE).minusDays(1);
        try {
            service.send(day);
            assertThat(service.summary(day).mailStatus()).isEqualTo("FAILED");
            service.send(day);
            assertThat(db.queryForObject("SELECT attempts FROM daily_comment_report WHERE report_date=?",
                    Integer.class, day)).isEqualTo(2);
        } finally {
            db.update("DELETE FROM daily_comment_report WHERE report_date=?", day);
        }
    }

    private long add(long cameraId, String origin, java.time.Instant instant) {
        return db.queryForObject("""
                INSERT INTO camera_comment(camera_id,nickname,content,raw_time,parsed_time,fingerprint,origin,created_at)
                VALUES (?, '测试', '测试评论', '', ?, ?, ?, ?) RETURNING id
                """, Long.class, cameraId, Timestamp.from(instant), UUID.randomUUID().toString(), origin,
                Timestamp.from(instant));
    }
}
