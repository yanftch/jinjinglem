package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_DB_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth-enabled=false")
class CameraReportIntegrationTest {
    @Autowired CameraReportService reports;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void publicUploadAndAdminRejectionWorkThroughHttp() throws Exception {
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        var response = mvc.perform(multipart("/api/v1/camera-reports")
                        .file(new MockMultipartFile("screenshot", "proof.jpg", "image/jpeg", image))
                        .param("nickname", "司机").param("content", "北向南有新探头"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id = json.readTree(response.getContentAsString()).get("id").asLong();
        try {
            mvc.perform(get("/api/v1/admin/camera-reports/" + id + "/screenshot"))
                    .andExpect(status().isOk()).andExpect(content().bytes(image));
            mvc.perform(post("/api/v1/admin/camera-reports/" + id + "/reject")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"位置不详\"}"))
                    .andExpect(status().isOk());
            assertThat(reports.detail(id).get("status")).isEqualTo("REJECTED");
        } finally {
            db.update("DELETE FROM camera_report WHERE id=?", id);
        }
    }

    @Test
    void reportRemainsSeparateUntilApprovedAndCannotBeReviewedTwice() throws Exception {
        String name = "测试上报" + UUID.randomUUID().toString().substring(0, 8) + " 南向北【六环外】";
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        long cameraBefore = db.queryForObject("SELECT count(*) FROM camera", Long.class);
        long id = ((Number) reports.submit(" 司机 ", " 路口南向北 ",
                new MockMultipartFile("screenshot", "proof.png", "image/png", image)).get("id")).longValue();
        Long cameraId = null;
        try {
            assertThat(db.queryForObject("SELECT count(*) FROM camera", Long.class)).isEqualTo(cameraBefore);
            assertThat(reports.detail(id).get("status")).isEqualTo("PENDING");
            assertThat(reports.screenshot(id).bytes()).isEqualTo(image);
            assertThat(reports.list("PENDING", 0, 100).stream().map(row -> row.get("id"))).contains(id);
            assertThat(reports.list("", 0, 100).stream().map(row -> row.get("id"))).contains(id);

            var approval = new CameraReportController.ApprovalInput(name,
                    new BigDecimal("116.1234567"), new BigDecimal("39.1234567"), "", true, false,
                    CameraType.SIX_RING_IN);
            var approvedResponse = mvc.perform(post("/api/v1/admin/camera-reports/" + id + "/approve")
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(approval)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            cameraId = json.readTree(approvedResponse.getContentAsString()).get("cameraId").asLong();
            assertThat(reports.detail(id).get("status")).isEqualTo("APPROVED");
            assertThat(reports.screenshot(id).bytes()).isEqualTo(image);
            assertThat(reports.detail(id).get("publishScreenshot")).isEqualTo(false);
            assertThat(db.queryForObject("SELECT camera_type FROM camera WHERE id=?", String.class, cameraId))
                    .isEqualTo("SIX_RING_IN");
            assertThat(db.queryForObject("SELECT coordinate_system FROM camera WHERE id=?", String.class, cameraId))
                    .isEqualTo("GCJ02");
            assertThat(db.queryForObject("SELECT camera_id FROM camera_report WHERE id=?", Long.class, id))
                    .isEqualTo(cameraId);
            assertThat(db.queryForObject("SELECT count(*) FROM camera_image WHERE camera_id=?", Long.class, cameraId))
                    .isZero();
            mvc.perform(get("/api/v1/cameras/" + cameraId + "/reported-image"))
                    .andExpect(status().isNotFound());
            assertThat(db.queryForObject("SELECT count(*) FROM camera_audit_log WHERE camera_id=? AND action='CREATE_FROM_REPORT'", Long.class, cameraId))
                    .isEqualTo(1L);
            assertThatThrownBy(() -> reports.reject(id, "重复审核", "test-admin"))
                    .isInstanceOf(VersionConflictException.class);
            assertThatThrownBy(() -> reports.approve(id, new CameraReportController.ApprovalInput(name,
                    new BigDecimal("116.1234567"), new BigDecimal("39.1234567"), "", true, false,
                    CameraType.SIX_RING_IN), "test-admin"))
                    .isInstanceOf(VersionConflictException.class);
        } finally {
            db.update("DELETE FROM camera_report WHERE id=?", id);
            if (cameraId != null) {
                db.update("DELETE FROM camera_audit_log WHERE camera_id=?", cameraId);
                db.update("DELETE FROM camera WHERE id=?", cameraId);
            }
        }
    }

    @Test
    void selectedScreenshotBecomesPublicCameraImageOnlyAfterApproval() throws Exception {
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        long reportId = ((Number) reports.submit("司机", "路口照片",
                new MockMultipartFile("screenshot", "proof.jpg", "image/jpeg", image)).get("id")).longValue();
        Long cameraId = null;
        try {
            mvc.perform(get("/api/v1/cameras/999999999/reported-image"))
                    .andExpect(status().isNotFound());
            var input = new CameraReportController.ApprovalInput(
                    "测试公开图片" + UUID.randomUUID().toString().substring(0, 8) + " 南向北",
                    new BigDecimal("116.1234567"), new BigDecimal("39.1234567"), "", true, true, null);
            cameraId = ((Number) reports.approve(reportId, input, "test-admin").get("cameraId")).longValue();
            assertThat(reports.detail(reportId).get("publishScreenshot")).isEqualTo(true);
            assertThat(db.queryForObject("SELECT publish_screenshot FROM camera_report WHERE id=?", Boolean.class, reportId))
                    .isTrue();
            String url = "/api/v1/cameras/" + cameraId + "/reported-image";
            assertThat(db.queryForObject("SELECT url FROM camera_image WHERE camera_id=?", String.class, cameraId))
                    .isEqualTo(url);
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(content().bytes(image));
            mvc.perform(get("/api/v1/cameras/" + cameraId + "/images"))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(url)));
        } finally {
            db.update("DELETE FROM camera_report WHERE id=?", reportId);
            if (cameraId != null) {
                db.update("DELETE FROM camera_image WHERE camera_id=?", cameraId);
                db.update("DELETE FROM camera_audit_log WHERE camera_id=?", cameraId);
                db.update("DELETE FROM camera WHERE id=?", cameraId);
            }
        }
    }

    @Test
    void rejectionKeepsReportButNeverCreatesCamera() throws Exception {
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        long before = db.queryForObject("SELECT count(*) FROM camera", Long.class);
        long id = ((Number) reports.submit("司机", "位置不清晰",
                new MockMultipartFile("screenshot", "proof.jpg", "image/jpeg", image)).get("id")).longValue();
        try {
            reports.reject(id, "无法确认位置", "test-admin");
            assertThat(reports.detail(id).get("status")).isEqualTo("REJECTED");
            assertThat(db.queryForObject("SELECT count(*) FROM camera", Long.class)).isEqualTo(before);
        } finally {
            db.update("DELETE FROM camera_report WHERE id=?", id);
        }
    }

    @Test
    void invalidScreenshotIsNotPersisted() {
        assertThatThrownBy(() -> reports.submit("司机", "线索",
                new MockMultipartFile("screenshot", "fake.png", "image/png", "not an image".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportWithoutScreenshotCanBeReviewedButCannotPublishAnImage() throws Exception {
        var response = mvc.perform(post("/api/v1/camera-reports")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("nickname", "司机").param("content", "路口南向北有探头"))
                .andExpect(status().isCreated()).andReturn();
        long reportId = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        Long cameraId = null;
        try {
            assertThat(reports.detail(reportId).get("hasScreenshot")).isEqualTo(false);
            assertThat(reports.detail(reportId).get("screenshotBytes")).isEqualTo(0);
            mvc.perform(get("/api/v1/admin/camera-reports/" + reportId + "/screenshot"))
                    .andExpect(status().isNotFound());
            var input = new CameraReportController.ApprovalInput(
                    "无图上报测试" + UUID.randomUUID() + " 南向北",
                    new BigDecimal("116.1234567"), new BigDecimal("39.1234567"), "", true, true, null);
            assertThatThrownBy(() -> reports.approve(reportId, input, "test-admin"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有截图");
            var withoutImage = new CameraReportController.ApprovalInput(input.name(), input.longitude(),
                    input.latitude(), input.typeCodeRaw(), true, false, null);
            cameraId = ((Number) reports.approve(reportId, withoutImage, "test-admin").get("cameraId")).longValue();
            assertThat(db.queryForObject("SELECT count(*) FROM camera_image WHERE camera_id=?", Long.class, cameraId))
                    .isZero();
        } finally {
            db.update("DELETE FROM camera_report WHERE id=?", reportId);
            if (cameraId != null) {
                db.update("DELETE FROM camera_audit_log WHERE camera_id=?", cameraId);
                db.update("DELETE FROM camera WHERE id=?", cameraId);
            }
        }
    }

    @Test
    void screenshotOverOneMegabyteIsRejectedByApi() throws Exception {
        byte[] image = new byte[CameraReportService.MAX_IMAGE_BYTES + 1];
        image[0] = (byte) 0xff;
        image[1] = (byte) 0xd8;
        image[2] = (byte) 0xff;
        mvc.perform(multipart("/api/v1/camera-reports")
                        .file(new MockMultipartFile("screenshot", "large.jpg", "image/jpeg", image))
                        .param("nickname", "司机").param("content", "过大的图片"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nearbySameNameNeedsManualRecheck() throws Exception {
        String name = "测试重复" + UUID.randomUUID().toString().substring(0, 8) + " 南向北";
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        long first = ((Number) reports.submit("甲", "第一条",
                new MockMultipartFile("screenshot", "a.jpg", "image/jpeg", image)).get("id")).longValue();
        long second = ((Number) reports.submit("乙", "第二条",
                new MockMultipartFile("screenshot", "b.jpg", "image/jpeg", image)).get("id")).longValue();
        Long cameraId = null;
        try {
            var input = new CameraReportController.ApprovalInput(name, new BigDecimal("116.1234567"),
                    new BigDecimal("39.1234567"), "", true, false, null);
            cameraId = ((Number) reports.approve(first, input, "test-admin").get("cameraId")).longValue();
            assertThatThrownBy(() -> reports.approve(second, input, "test-admin"))
                    .isInstanceOf(VersionConflictException.class).hasMessageContaining("同名探头");
            assertThat(reports.detail(second).get("status")).isEqualTo("PENDING");
        } finally {
            db.update("DELETE FROM camera_report WHERE id IN (?,?)", first, second);
            if (cameraId != null) {
                db.update("DELETE FROM camera_audit_log WHERE camera_id=?", cameraId);
                db.update("DELETE FROM camera WHERE id=?", cameraId);
            }
        }
    }
}
