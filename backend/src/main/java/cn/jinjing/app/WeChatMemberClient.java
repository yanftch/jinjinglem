package cn.jinjing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WeChatMemberClient {
    private final String appId;
    private final String appSecret;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    WeChatMemberClient(@Value("${app.wechat-app-id:}") String appId,
                       @Value("${app.wechat-app-secret:}") String appSecret, ObjectMapper json) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.json = json;
    }

    boolean identityConfigured() { return !appId.isBlank() && !appSecret.isBlank(); }
    String appId() { return appId; }

    CodeSession code2session(String code) {
        if (!identityConfigured()) throw new MemberUnavailable("微信登录尚未配置");
        if (code == null || code.isBlank() || code.length() > 256) throw new MemberBadRequest("微信登录凭证无效");
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appId)
                + "&secret=" + encode(appSecret) + "&js_code=" + encode(code) + "&grant_type=authorization_code";
        JsonNode data = get(url);
        if (data.path("errcode").asInt(0) != 0 || data.path("openid").asText().isBlank()
                || data.path("session_key").asText().isBlank()) throw new MemberUnauthorized("微信登录失败，请重试");
        return new CodeSession(data.path("openid").asText(), data.path("session_key").asText());
    }

    synchronized String accessToken() {
        if (!identityConfigured()) throw new MemberUnavailable("微信登录尚未配置");
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt) return accessToken;
        JsonNode data = get("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + encode(appId) + "&secret=" + encode(appSecret));
        if (data.path("access_token").asText().isBlank()) throw new MemberUnavailable("微信接口凭据暂不可用");
        accessToken = data.path("access_token").asText();
        accessTokenExpiresAt = System.currentTimeMillis() + Math.max(60, data.path("expires_in").asLong(7200) - 300) * 1000;
        return accessToken;
    }

    JsonNode post(String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new MemberUnavailable("微信支付查询暂不可用");
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new MemberUnavailable("微信服务暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemberUnavailable("微信服务请求中断");
        }
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new MemberUnavailable("微信服务暂不可用");
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new MemberUnavailable("微信服务暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemberUnavailable("微信服务请求中断");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    record CodeSession(String openid, String sessionKey) {}
}

class MemberUnavailable extends RuntimeException {
    MemberUnavailable(String message) { super(message); }
}
