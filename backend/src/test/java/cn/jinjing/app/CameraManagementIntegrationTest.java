package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_DB_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth-enabled=false")
class CameraManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;

    @Test
    void editingCameraTypePersistsAndMissingTypeKeepsCurrentValue() throws Exception {
        String name = "管理编辑测试" + UUID.randomUUID() + "【六环外】";
        long id = 0;
        try {
            var created = mvc.perform(post("/api/v1/admin/cameras")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new CameraController.CameraInput(
                                    name, new BigDecimal("116.4"), new BigDecimal("40.1"),
                                    "", null, null))))
                    .andExpect(status().isCreated()).andReturn();
            id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
            assertThat(db.queryForObject("SELECT camera_type FROM camera WHERE id=?", String.class, id))
                    .isEqualTo("SIX_RING_OUT");

            var updated = mvc.perform(patch("/api/v1/admin/cameras/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new CameraController.CameraInput(
                                    name, new BigDecimal("116.4"), new BigDecimal("40.1"),
                                    "", CameraType.TWO_RING_IN, 0L))))
                    .andExpect(status().isOk()).andReturn();
            assertThat(json.readTree(updated.getResponse().getContentAsString()).get("cameraType").asText())
                    .isEqualTo("TWO_RING_IN");

            mvc.perform(patch("/api/v1/admin/cameras/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new CameraController.CameraInput(
                                    name + "已废弃", new BigDecimal("116.4"),
                                    new BigDecimal("40.1"), "", null, 1L))))
                    .andExpect(status().isOk());
            var detail = mvc.perform(get("/api/v1/cameras/" + id))
                    .andExpect(status().isOk()).andReturn();
            assertThat(json.readTree(detail.getResponse().getContentAsString()).get("cameraType").asText())
                    .isEqualTo("TWO_RING_IN");
            assertThat(db.queryForObject("SELECT count(*) FROM camera_audit_log WHERE camera_id=? AND action='UPDATE'",
                    Long.class, id)).isEqualTo(2L);
        } finally {
            if (id != 0) {
                db.update("DELETE FROM camera_audit_log WHERE camera_id=?", id);
                db.update("DELETE FROM camera WHERE id=?", id);
            }
        }
    }
}
