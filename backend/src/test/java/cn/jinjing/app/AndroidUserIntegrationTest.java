package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named="REVIEW_TEST_DB_URL", matches=".+")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={"app.auth-enabled=false"})
class AndroidUserIntegrationTest {
    @Autowired AndroidUserService users;
    @Autowired AndroidUserAdminController admin;
    @Autowired RouteAccessService routeAccess;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void deviceRegistrationAdminMembershipAndRouteOwnershipUseIndependentAccount() throws Exception {
        String deviceId=MemberService.hash("android-test-"+UUID.randomUUID());
        String secret=MemberService.randomToken();
        String body=json.writeValueAsString(Map.of("deviceId",deviceId,"deviceSecret",secret));
        var first=mvc.perform(post("/api/v1/android/users/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.active").value(false)).andReturn();
        String token=json.readTree(first.getResponse().getContentAsString()).get("token").asText();
        long id=json.readTree(first.getResponse().getContentAsString()).at("/user/id").asLong();
        mvc.perform(post("/api/v1/android/users/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id));
        assertThat(db.queryForObject("SELECT count(*) FROM android_user WHERE device_id=?",Long.class,deviceId)).isEqualTo(1);
        mvc.perform(post("/api/v1/android/users/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("deviceId",deviceId,"deviceSecret",MemberService.randomToken()))))
                .andExpect(status().isUnauthorized());

        MockHttpServletRequest request=new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer "+token);
        assertThatThrownBy(()->routeAccess.requireVip(request)).isInstanceOf(MemberRequired.class);
        mvc.perform(get("/api/v1/android/users/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.active").value(false));

        var auth=new UsernamePasswordAuthenticationToken("adsmaxa","unused");
        MockHttpServletRequest adminRequest=new MockHttpServletRequest();
        adminRequest.setRemoteAddr("127.0.0.1");
        var month=admin.change(id,"{\"plan\":\"MONTH\",\"reason\":\"联调月卡\"}",auth,adminRequest);
        assertThat(((Map<?,?>)month.get("user")).get("active")).isEqualTo(true);
        Instant monthEnd=(Instant)users.status(id).get("memberExpiresAt");
        assertThat(monthEnd).isBetween(Instant.now().plus(Duration.ofDays(29)),Instant.now().plus(Duration.ofDays(31)));
        var principal=routeAccess.requireVip(request);
        assertThat(principal.android()).isTrue();
        String requestId=routeAccess.createRouteRequest(principal);
        routeAccess.requireRouteOwner(principal,requestId);
        assertThatThrownBy(()->routeAccess.requireRouteOwner(new RouteAccessService.Principal(true,id+999),requestId))
                .isInstanceOf(MemberForbidden.class);

        admin.change(id,"{\"plan\":\"YEAR\",\"reason\":\"联调年卡\"}",auth,adminRequest);
        Instant yearEnd=(Instant)users.status(id).get("memberExpiresAt");
        assertThat(yearEnd).isBetween(Instant.now().plus(Duration.ofDays(364)),Instant.now().plus(Duration.ofDays(366)));
        assertThat(yearEnd).isAfter(monthEnd);
        assertThatThrownBy(()->admin.change(id,"{\"plan\":\"WEEK\",\"reason\":\"无效操作\"}",auth,adminRequest))
                .isInstanceOf(MemberBadRequest.class);
        assertThat(admin.history(0,20,id,"FAILED",null,null).get("total")).isEqualTo(1L);

        admin.change(id,"{\"plan\":\"NONE\",\"reason\":\"联调撤销\"}",auth,adminRequest);
        assertThat(users.status(id).get("active")).isEqualTo(false);
        assertThatThrownBy(()->routeAccess.requireVip(request)).isInstanceOf(MemberRequired.class);
        assertThat(db.queryForObject("SELECT count(*) FROM android_user_admin_audit WHERE user_id=? AND outcome='SUCCESS'",Long.class,id)).isEqualTo(3);
    }
}
