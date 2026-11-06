package cn.jinjing.app;

import com.duomaomao.api.dto.RoutePlanResult.RoutePoint;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AmapRouteController.class)
@Import({SecurityConfig.class, ApiErrors.class})
@TestPropertySource(properties = {"app.auth-enabled=true", "app.admin-user=adsmaxa",
        "app.admin-password=correct-horse-battery-staple"})
class AmapRouteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AmapRouteService service;
    @MockitoBean RouteAccessService access;

    private static final String REQUEST = """
            {"preference":1,
             "startPoint":{"latitude":39.9,"longitude":116.4},
             "endPoint":{"latitude":40.0,"longitude":116.5},"waypoints":[]}
            """;

    @Test
    void publicMiniProgramCanPlanWithoutAdminOrCsrf() throws Exception {
        given(service.plan(any())).willReturn(new AmapRouteResponse("AMAP", true, 100,
                List.of(List.of(new RoutePoint("116.4", "39.9"))),
                List.of(), "1 公里", "2 分钟"));
        mvc.perform(post("/api/v1/routes/amap/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("AMAP"))
                .andExpect(jsonPath("$.avoidsKnownCameras").value(true));
    }

    @Test
    void noCameraFreeRouteIsExplicit() throws Exception {
        given(service.plan(any())).willThrow(new AmapNoSafeRouteException("未找到避开已收录探头的路线"));
        mvc.perform(post("/api/v1/routes/amap/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_CAMERA_FREE_ROUTE"));
    }

    @Test
    void invalidCoordinateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/routes/amap/plan").contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("39.9", "999.0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredMemberCannotCallAmap() throws Exception {
        given(access.requireVip(any())).willThrow(new MemberRequired("会员已到期"));
        mvc.perform(post("/api/v1/routes/amap/plan").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_REQUIRED"));
        verifyNoInteractions(service);
    }
}
