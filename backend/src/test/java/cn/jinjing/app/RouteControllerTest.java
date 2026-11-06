package cn.jinjing.app;

import com.duomaomao.api.dto.RoutePlanResult;
import com.duomaomao.api.dto.RoutePlanRequest;
import com.duomaomao.api.service.DuomaomaoService;
import com.duomaomao.api.service.UpstreamStatusException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RouteController.class)
@Import({SecurityConfig.class, ApiErrors.class})
@TestPropertySource(properties = {"app.auth-enabled=true", "app.admin-user=adsmaxa", "app.admin-password=correct-horse-battery-staple"})
class RouteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DuomaomaoService service;
    @MockitoBean RouteAccessService access;

    private static final String REQUEST = """
            {"openid":"jlm_test","licence":3,"preference":1,
             "startPoint":{"latitude":39.9,"longitude":116.4},
             "endPoint":{"latitude":40.0,"longitude":116.5},"waypoints":[]}
            """;

    @Test
    void publicMiniProgramCanPlanWithoutAdminOrCsrf() throws Exception {
        var principal = new RouteAccessService.Principal(false, 1L);
        given(access.requireVip(any())).willReturn(principal);
        given(access.createRouteRequest(principal)).willReturn("r_owned");
        given(service.planRoute(any())).willReturn(new RoutePlanResult(true,
                List.of(List.of(new RoutePlanResult.RoutePoint("116.4", "39.9"))),
                List.of(), "1km", "2分钟", null, null, null, null));
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccessOnPlanRoute").value(true))
                .andExpect(jsonPath("$.routePlanResponse[0][0].longitude").value("116.4"));
        var sent=ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(service).planRoute(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().openid()).isEqualTo("r_owned");
    }

    @Test
    void upstreamTimeLimitReturnsPendingForResultPolling() throws Exception {
        var principal = new RouteAccessService.Principal(false, 1L);
        given(access.requireVip(any())).willReturn(principal);
        given(access.createRouteRequest(principal)).willReturn("r_owned");
        given(service.planRoute(any())).willThrow(new UpstreamStatusException(400,
                "FUNCTION_TIME_LIMIT_EXCEEDED".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value("r_owned"));
    }

    @Test
    void invalidCoordinateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("39.9", "999.0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredMemberCannotCallUpstream() throws Exception {
        given(access.requireVip(any())).willThrow(new MemberRequired("会员已到期"));
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void pollingAnotherMembersTaskIsRejected() throws Exception {
        var principal = new RouteAccessService.Principal(false, 1L);
        given(access.requireVip(any())).willReturn(principal);
        doThrow(new MemberForbidden("路线任务无效")).when(access).requireRouteOwner(principal,"r_other");
        mvc.perform(get("/api/v1/routes/result").param("openid","r_other"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_FORBIDDEN"));
        verifyNoInteractions(service);
    }
}
