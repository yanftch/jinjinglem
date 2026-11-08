package cn.jinjing.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path="/api/v1/android/users", produces=MediaType.APPLICATION_JSON_VALUE)
class AndroidUserController {
    private final AndroidUserService users;
    AndroidUserController(AndroidUserService users) { this.users = users; }

    record RegisterRequest(String deviceId, String deviceSecret, String manufacturer, String brand,
            String model, String deviceName, String androidVersion, Integer sdkInt, String appVersion) {}

    @PostMapping(path="/register", consumes=MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> register(@RequestBody RegisterRequest body) {
        return users.register(body);
    }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        long id = users.requireUser(request);
        users.touch(id);
        return Map.of("user", users.status(id));
    }
}
