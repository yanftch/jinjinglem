package com.duomaomao.api.web;

import java.util.List;

import com.duomaomao.api.dto.CameraDetail;
import com.duomaomao.api.dto.ExportPreview;
import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.service.ExportInProgressException;
import com.duomaomao.api.service.ExportService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExportController.class)
class ExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExportService exportService;

    private static ExportStatus running() {
        return new ExportStatus("RUNNING", 120, 7324, 0, 1, "2026-10-29T00:00:00+08:00",
                null, null, "/tmp/cameras.json", null, "正在抓取留言详情", null);
    }

    @Test
    void startReturns202WithStatus() throws Exception {
        given(exportService.start(null, true)).willReturn(running());

        mockMvc.perform(post("/api/v1/export/start"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.total").value(7324));
    }

    @Test
    void startPassesLimitAndDetailFlags() throws Exception {
        given(exportService.start(50, false)).willReturn(running());

        mockMvc.perform(post("/api/v1/export/start").param("limit", "50").param("detail", "false"))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(exportService).start(50, false);
    }

    @Test
    void startReturns409WhenAlreadyRunning() throws Exception {
        given(exportService.start(any(), eq(true))).willThrow(new ExportInProgressException());

        mockMvc.perform(post("/api/v1/export/start"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.title").value("Export already running"));
    }

    @Test
    void previewReturnsPagedSummaries() throws Exception {
        given(exportService.preview(2, 20, "西单")).willReturn(new ExportPreview(2, 20, 1,
                List.of(new CameraDetail(752, "西单路口", List.of(116.39, 39.95), "2", "2020-07-28", "",
                        "/content/?712.html", "cam-752", "2020-07-28", 3, null, null)),
                "2026-10-29T00:00:00+08:00", "/tmp/cameras.json"));

        mockMvc.perform(get("/api/v1/export/preview")
                        .param("page", "2").param("size", "20").param("q", "西单"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.items[0].id").value(752))
                .andExpect(jsonPath("$.items[0].commentCount").value(3));
    }

    @Test
    void cameraDetailIncludesComments() throws Exception {
        given(exportService.camera(752)).willReturn(Optional.of(new CameraDetail(
                752, "西单路口", List.of(116.39, 39.95), "2", "2020-07-28", "",
                "/content/?712.html", "cam-752", "2020-07-28", 1,
                List.of(new com.duomaomao.api.dto.CameraComments.Comment("古槐听讼", "2021/3/12", "被拍过")),
                null)));

        mockMvc.perform(get("/api/v1/export/cameras/752"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cameraName").value("cam-752"))
                .andExpect(jsonPath("$.comments[0].nickname").value("古槐听讼"));
    }

    @Test
    void cameraDetailReturns404WhenMissing() throws Exception {
        given(exportService.camera(anyInt())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/export/cameras/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Camera not found"));
    }

    @Test
    void downloadReturns404BeforeFirstExport() throws Exception {
        given(exportService.exportFile()).willReturn(Optional.empty());
        given(exportService.exportFilePath()).willReturn(Path.of("/tmp/cameras.json"));

        mockMvc.perform(get("/api/v1/export/file"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Export file not found"));
    }
}
