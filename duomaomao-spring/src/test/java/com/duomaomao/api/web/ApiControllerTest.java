package com.duomaomao.api.web;

import java.util.List;
import java.util.Optional;

import com.duomaomao.api.dto.CameraComments;
import com.duomaomao.api.dto.RoutePlanResult;
import com.duomaomao.api.dto.UserInfo;
import com.duomaomao.api.service.DuomaomaoService;
import com.duomaomao.api.service.QuotaExceededException;
import com.duomaomao.api.service.UpstreamStatusException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ApiController.class, RouteController.class})
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DuomaomaoService service;

    @Test
    void exposesUserInfoWithUpstreamFieldNames() throws Exception {
        given(service.userInfo("u1"))
                .willReturn(new UserInfo("internal-1", false, "2026-02-05T15:18:58.360Z", false, 0, null));

        mockMvc.perform(get("/api/v1/users").param("openid", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("internal-1"))
                .andExpect(jsonPath("$.isVIP").value(false))
                .andExpect(jsonPath("$.VIPDeadline").value("2026-02-05T15:18:58.360Z"))
                .andExpect(jsonPath("$.buy_times").value(0));
    }

    @Test
    void returns404WhenCameraDoesNotExist() throws Exception {
        given(service.findCamera(999999)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/cameras/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Camera not found"));
    }

    @Test
    void returns400ForInvalidPage() throws Exception {
        given(service.labels(0, false)).willThrow(new IllegalArgumentException("page 必须 >= 1"));

        mockMvc.perform(get("/api/v1/labels").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void commentsKeepUpstreamShape() throws Exception {
        given(service.comments(752)).willReturn(new CameraComments(
                "西城区 安德路鼓楼外大街路口北 北向南",
                "2020-07-28",
                List.of(116.395347, 39.951861),
                List.of(new CameraComments.Comment("古槐听讼", "2021/3/12 17:14:16", "被拍过"))));

        mockMvc.perform(get("/api/v1/comments/752"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cameraName").value("西城区 安德路鼓楼外大街路口北 北向南"))
                .andExpect(jsonPath("$.creatTime").value("2020-07-28"))
                .andExpect(jsonPath("$.comments[0].nickname").value("古槐听讼"));
    }

    @Test
    void routePlanReturnsNormalizedSegments() throws Exception {
        given(service.planRoute(any())).willReturn(new RoutePlanResult(
                true,
                List.of(List.of(new RoutePlanResult.RoutePoint("116.1", "39.1"))),
                List.of(),
                "1.0km",
                "1分钟",
                null,
                "",
                "",
                null));

        mockMvc.perform(post("/api/v1/routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startPoint": {"latitude": 39.9, "longitude": 116.4},
                                  "endPoint":   {"latitude": 40.0, "longitude": 116.5}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccessOnPlanRoute").value(true))
                .andExpect(jsonPath("$.routePlanResponse[0][0].longitude").value("116.1"))
                .andExpect(jsonPath("$.distance").value("1.0km"));
    }

    @Test
    void mapsUpstreamRejectionTo502WithPreview() throws Exception {
        given(service.labels(1, false)).willThrow(new UpstreamStatusException(400,
                "{\"code\":\"FUNCTIONS_INVOCATION_FAILED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/v1/labels"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Upstream rejected the request"))
                .andExpect(jsonPath("$.upstreamStatus").value(400))
                .andExpect(jsonPath("$.upstreamBody").value("{\"code\":\"FUNCTIONS_INVOCATION_FAILED\"}"));
    }

    @Test
    void mapsRouteQueueOverflowTo429WithRetryAfter() throws Exception {
        given(service.planRoute(any())).willThrow(new QuotaExceededException("规划请求过多，请稍后重试（并发上限 3）"));

        mockMvc.perform(post("/api/v1/routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startPoint": {"latitude": 39.9, "longitude": 116.4},
                                  "endPoint":   {"latitude": 40.0, "longitude": 116.5}
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Too many route planning requests"));
    }

    @Test
    void routePlanRejectsMissingEndPoint() throws Exception {
        mockMvc.perform(post("/api/v1/routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startPoint": {"latitude": 39.9, "longitude": 116.4}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.endPoint").exists());
    }

    @Test
    void routePlanRejectsOutOfRangeCoordinates() throws Exception {
        mockMvc.perform(post("/api/v1/routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startPoint": {"latitude": 999, "longitude": 116.4},
                                  "endPoint":   {"latitude": 40.0, "longitude": 116.5}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['startPoint.latitude']").exists());
    }
}
