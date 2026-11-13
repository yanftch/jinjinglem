package cn.jinjing.app;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;

@WebMvcTest(controllers = {AdminCsrfController.class, SecurityTestController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.auth-enabled=true",
        "app.admin-user=adsmaxa",
        "app.admin-password=correct-horse-battery-staple"
})
class SecurityAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publicHomepageStaysOpenWhileEveryManagementPageRequiresLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/usage-guide.html")).andExpect(status().isOk());
        for (String path : new String[]{"/index.html", "/manage.html", "/manage.css", "/manage.js",
                "/admin.html", "/sync.html", "/review.html",
                "/reports.html", "/reports.css", "/reports.js", "/ring-location.js", "/ring-boundaries.json", "/road-search.js",
                "/daily-comments.html", "/daily-comments.css", "/daily-comments.js", "/app.js", "/sync.js", "/review.js",
                "/admin-security.js", "/admin-embed.css", "/beijing-time.js", "/members.html", "/members.js",
                 "/member-history.html", "/member-history.js", "/member-admin.css",
                 "/android-users.html", "/android-users.js", "/android-user-history.html", "/android-user-history.js",
                 "/system-config.html", "/system-config.js"}) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login.html"));
        }
        for (String path : new String[]{"/api/v1/admin/csrf", "/api/v1/admin/camera-reports",
                 "/api/v1/admin/members", "/api/v1/admin/members/history",
                 "/api/v1/admin/system-config", "/api/v1/admin/system-config/history",
                "/api/v1/admin/android-users", "/api/v1/admin/android-users/history"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/login.css")).andExpect(status().isOk());
        mvc.perform(get("/login.js")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/contact/wechat-qr")).andExpect(status().isNotFound());
        mvc.perform(get("/portal.js")).andExpect(status().isOk());
        mvc.perform(get("/brand/logo-mark.svg")).andExpect(status().isOk());
        mvc.perform(get("/brand/favicon.svg")).andExpect(status().isOk());
        mvc.perform(get("/index.html").with(request -> {
                    request.setDispatcherType(DispatcherType.FORWARD);
                    request.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, "/");
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void oneAdminSessionProtectsPagesAndWritesAndLogoutEndsIt() throws Exception {
        var loginTokenResponse = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String loginToken = mapper.readTree(loginTokenResponse.getContentAsString()).get("token").asText();
        Cookie loginCookie = loginTokenResponse.getCookie("XSRF-TOKEN");
        mvc.perform(post("/login").param("username", "adsmaxa").param("password", "correct-horse-battery-staple"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").param("username", "admin").param("password", "correct-horse-battery-staple")
                        .param("_csrf", loginToken).cookie(loginCookie))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login.html?error"));
        mvc.perform(post("/login").param("username", "adsmaxa").param("password", "wrong-password")
                        .param("_csrf", loginToken).cookie(loginCookie))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login.html?error"));
        var loginResult = mvc.perform(post("/login").param("username", "adsmaxa")
                        .param("password", "correct-horse-battery-staple")
                        .param("_csrf", loginToken).cookie(loginCookie))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/manage.html"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        mvc.perform(get("/manage.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/beijing-time.js").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/members.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/member-history.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/android-users.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/android-user-history.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/camera-reports").session(session)).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/admin/security-test").session(session))
                .andExpect(status().isForbidden());
        var tokenResponse = mvc.perform(get("/api/v1/admin/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode token = mapper.readTree(tokenResponse.getContentAsString());
        Cookie cookie = tokenResponse.getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/v1/admin/security-test")
                        .session(session)
                        .header("X-XSRF-TOKEN", token.get("token").asText())
                        .cookie(cookie))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/cameras/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/camera-reports").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/android/users/register").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/android-users/1/membership").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/contact/wechat-qr").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/camera-reports/1/approve")
                        .session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session)
                        .header("X-XSRF-TOKEN", token.get("token").asText()).cookie(cookie))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login.html?logout"));
        mvc.perform(get("/manage.html").session(session)).andExpect(status().isFound());
    }

    @Test
    void loginReturnsToRequestedManagementPage() throws Exception {
        var guarded = mvc.perform(get("/manage.html?page=reports"))
                .andExpect(status().isFound()).andReturn();
        MockHttpSession session = (MockHttpSession) guarded.getRequest().getSession(false);
        var tokenResponse = mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(tokenResponse.getContentAsString()).get("token").asText();
        mvc.perform(post("/login").session(session).cookie(tokenResponse.getCookie("XSRF-TOKEN"))
                        .param("username", "adsmaxa").param("password", "correct-horse-battery-staple")
                        .param("_csrf", token))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("**/manage.html?page=reports*"));
    }

}

@RestController
class SecurityTestController {
    @PostMapping("/api/v1/admin/security-test")
    String adminWrite() { return "ok"; }

    @PostMapping("/api/v1/cameras/1/comments")
    String publicComment() { return "ok"; }

    @PostMapping(value = "/api/v1/camera-reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    String publicReport() { return "ok"; }

    @PostMapping("/api/v1/admin/camera-reports/1/approve")
    String adminApproval() { return "ok"; }

    @PostMapping("/api/v1/android/users/register")
    String androidRegister() { return "ok"; }

}
