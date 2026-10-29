package cn.jinjing.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewWorkflowIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ImportService imports;
    @Autowired ReviewService reviews;
    @Autowired SyncService sync;
    @TempDir Path temp;

    @Test
    void fetchPreviewDoesNotUpdateCameraUntilAdminApprovesAndRejectsAreSkipped() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Path file = temp.resolve("review.json");
        Files.writeString(file, """
                {"generatedAt":"2026-10-29T03:00:00+08:00","total":2,"cameras":[
                  {"id":"review-test-%s-1","name":"测试甲%s 南向北","aa":"w","position":[116.1,39.9],"comments":[]},
                  {"id":"review-test-%s-2","name":"测试乙%s 南向北","aa":"w","position":[116.2,39.9],"comments":[]}
                ]}
                """.formatted(suffix, suffix, suffix, suffix));
        long before = db.queryForObject("SELECT count(*) FROM camera", Long.class);
        long batch = imports.submitPath(file);
        long run = db.queryForObject("""
                INSERT INTO sync_run(source_code,trigger_type,status,import_batch_id,mail_status)
                VALUES ('duomaomao','MANUAL','PREPARING_REVIEW',?,'NOT_APPLICABLE') RETURNING id
                """, Long.class, batch);
        reviews.prepare(run, batch);
        db.update("UPDATE sync_run SET status='AWAITING_REVIEW' WHERE id=?", run);
        assertThat(db.queryForObject("SELECT count(*) FROM camera", Long.class)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM sync_review_item WHERE run_id=?", Long.class, run)).isEqualTo(2L);

        reviews.decide(run, 2, "REJECTED", "测试排除");
        sync.approve(run);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String status;
        do {
            status = db.queryForObject("SELECT status FROM sync_run WHERE id=?", String.class, run);
            if ("IMPORTING".equals(status)) Thread.sleep(50);
        } while ("IMPORTING".equals(status) && Instant.now().isBefore(deadline));
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT count(*) FROM camera", Long.class)).isEqualTo(before + 1);
        assertThat(db.queryForObject("SELECT count(*) FROM camera_source_link WHERE source_record_id=?", Long.class,
                "review-test-" + suffix + "-1")).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT count(*) FROM camera_source_link WHERE source_record_id=?", Long.class,
                "review-test-" + suffix + "-2")).isZero();
        assertThat(db.queryForObject("SELECT decision FROM sync_review_item WHERE run_id=? AND row_number=2", String.class, run)).isEqualTo("REJECTED");
    }

    @Test
    void invalidRecordMustBeExcludedBeforeBatchCanBeApproved() throws Exception {
        Path file = temp.resolve("invalid.json");
        Files.writeString(file, """
                {"generatedAt":"2026-10-29T03:00:00+08:00","total":1,"cameras":[
                  {"id":"review-invalid-1","name":"","aa":"w","position":[116.1,39.9],"comments":[]}
                ]}
                """);
        long batch = imports.submitPath(file);
        long run = db.queryForObject("""
                INSERT INTO sync_run(source_code,trigger_type,status,import_batch_id,mail_status)
                VALUES ('duomaomao','MANUAL','PREPARING_REVIEW',?,'NOT_APPLICABLE') RETURNING id
                """, Long.class, batch);
        reviews.prepare(run, batch);
        db.update("UPDATE sync_run SET status='AWAITING_REVIEW' WHERE id=?", run);

        assertThat(db.queryForObject("SELECT predicted_status FROM sync_review_item WHERE run_id=?", String.class, run))
                .isEqualTo("INVALID");
        assertThatThrownBy(() -> sync.approve(run)).isInstanceOf(VersionConflictException.class);
        reviews.decide(run, 1, "REJECTED", "无名称");
        sync.approve(run);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String status;
        do {
            status = db.queryForObject("SELECT status FROM sync_run WHERE id=?", String.class, run);
            if ("IMPORTING".equals(status)) Thread.sleep(50);
        } while ("IMPORTING".equals(status) && Instant.now().isBefore(deadline));
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT status FROM import_record WHERE batch_id=?", String.class, batch))
                .isEqualTo("REJECTED");
    }

    @Test
    void approvalRejectsAStalePreviewAfterAnotherBatchLinksTheSourceRecord() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Path file = temp.resolve("stale.json");
        Files.writeString(file, """
                {"generatedAt":"2026-10-29T03:00:00+08:00","total":1,"cameras":[
                  {"id":"review-stale-%s","name":"测试丙%s 南向北","aa":"w","position":[116.3,39.9],"comments":[]}
                ]}
                """.formatted(suffix, suffix));
        long batch = imports.submitPath(file);
        long run = db.queryForObject("""
                INSERT INTO sync_run(source_code,trigger_type,status,import_batch_id,mail_status)
                VALUES ('duomaomao','MANUAL','PREPARING_REVIEW',?,'NOT_APPLICABLE') RETURNING id
                """, Long.class, batch);
        reviews.prepare(run, batch);
        db.update("UPDATE sync_run SET status='AWAITING_REVIEW' WHERE id=?", run);
        long otherBatch = imports.submitPath(file);
        imports.processBlocking(otherBatch, null);
        assertThatThrownBy(() -> sync.approve(run)).isInstanceOf(VersionConflictException.class);
        assertThat(db.queryForObject("SELECT status FROM sync_run WHERE id=?", String.class, run))
                .isEqualTo("AWAITING_REVIEW");
    }

    @Test
    void nearbySameNamePreviewShowsExistingCameraBeforeApproval() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "测试关联" + suffix + " 南向北";
        Path original = temp.resolve("original.json");
        Files.writeString(original, """
                {"total":1,"cameras":[{"id":"review-link-original-%s","name":"%s",
                "aa":"w","position":[116.4,39.9],"comments":[]}]}
                """.formatted(suffix, name));
        long originalBatch = imports.submitPath(original);
        imports.processBlocking(originalBatch, null);
        long cameraId = db.queryForObject("""
                SELECT camera_id FROM camera_source_link WHERE source_record_id=?
                """, Long.class, "review-link-original-" + suffix);

        Path next = temp.resolve("next.json");
        Files.writeString(next, """
                {"total":1,"cameras":[{"id":"review-link-next-%s","name":"%s",
                "aa":"w","position":[116.4002,39.9],"comments":[]}]}
                """.formatted(suffix, name));
        long batch = imports.submitPath(next);
        long run = db.queryForObject("""
                INSERT INTO sync_run(source_code,trigger_type,status,import_batch_id,mail_status)
                VALUES ('duomaomao','MANUAL','PREPARING_REVIEW',?,'NOT_APPLICABLE') RETURNING id
                """, Long.class, batch);
        reviews.prepare(run, batch);
        db.update("UPDATE sync_run SET status='AWAITING_REVIEW' WHERE id=?", run);
        assertThat(db.queryForMap("""
                SELECT predicted_status,camera_id,camera_version,current_snapshot
                FROM sync_review_item WHERE run_id=?
                """, run)).satisfies(row -> {
                    assertThat(row.get("predicted_status")).isEqualTo("LINK");
                    assertThat(((Number) row.get("camera_id")).longValue()).isEqualTo(cameraId);
                    assertThat(row.get("camera_version")).isNotNull();
                    assertThat(row.get("current_snapshot")).isNotNull();
                });
        assertThat(db.queryForObject("SELECT count(*) FROM camera_source_link WHERE source_record_id=?", Long.class,
                "review-link-next-" + suffix)).isZero();
    }
}
