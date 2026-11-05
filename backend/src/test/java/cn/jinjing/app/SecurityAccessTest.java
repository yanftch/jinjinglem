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

import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminCsrfController.class, SecurityTestController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.auth-enabled=true",
        "app.admin-user=admin",
        "app.admin-password=correct-horse-battery-staple"
})
class SecurityAccessTest {
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("admin:correct-horse-battery-staple".getBytes(UTF_8));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publicHomepageStaysOpenWhileEveryManagementPageRequiresAdmin() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        for (String path : new String[]{"/index.html", "/admin.html", "/sync.html", "/review.html",
                "/app.js", "/sync.js", "/review.js", "/admin-security.js", "/api/v1/admin/csrf"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/admin.html").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk());
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
    void adminWritesNeedCsrfButPublicCommentsDoNot() throws Exception {
        mvc.perform(post("/api/v1/admin/security-test").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());

        var tokenResponse = mvc.perform(get("/api/v1/admin/csrf")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode token = mapper.readTree(tokenResponse.getContentAsString());
        Cookie cookie = tokenResponse.getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/v1/admin/security-test")
                        .header("Authorization", AUTHORIZATION)
                        .header("X-XSRF-TOKEN", token.get("token").asText())
                        .cookie(cookie))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/cameras/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

}

@RestController
class SecurityTestController {
    @PostMapping("/api/v1/admin/security-test")
    String adminWrite() { return "ok"; }

    @PostMapping("/api/v1/cameras/1/comments")
    String publicComment() { return "ok"; }
}
