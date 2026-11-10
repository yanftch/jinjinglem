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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/places", produces = MediaType.APPLICATION_JSON_VALUE)
class PlaceSearchController {
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchController.class);
    private static final Set<String> INVALID_KEY_CODES = Set.of("10001", "10002", "10005", "10006", "10007");
    private static final Set<String> BUSY_CODES = Set.of("10003", "10004");
    private final String key;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Semaphore permits = new Semaphore(6);

    PlaceSearchController(@Value("${app.amap-web-service-key:}") String key, ObjectMapper json) {
        this.key = key;
        this.json = json;
    }

    @GetMapping("/search")
    List<PlaceResult> search(@RequestParam String q) {
        String keyword = q.trim();
        if (keyword.length() < 2 || keyword.length() > 80) {
            throw new IllegalArgumentException("地点关键词长度须为 2 至 80 个字符");
        }
        if (key == null || key.isBlank()) throw new AmapNotConfiguredException("地点搜索暂不可用");
        if (!permits.tryAcquire()) throw new AmapBusyException("地点搜索繁忙，请稍后重试");
        try {
            String url = "https://restapi.amap.com/v5/place/text?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "&keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&region=" + URLEncoder.encode("北京市", StandardCharsets.UTF_8)
                    + "&city_limit=false&page_size=20&page_num=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AMap place search HTTP status {}", response.statusCode());
                throw new AmapUpstreamException("地点搜索暂不可用");
            }
            JsonNode root = json.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                String code = root.path("infocode").asText();
                log.warn("AMap place search error code {}", code);
                if (INVALID_KEY_CODES.contains(code)) throw new AmapNotConfiguredException("地点搜索暂不可用");
                if (BUSY_CODES.contains(code)) throw new AmapBusyException("地点搜索繁忙，请稍后重试");
                throw new AmapUpstreamException("地点搜索暂不可用");
            }
            List<PlaceResult> places = new ArrayList<>();
            for (JsonNode poi : root.path("pois")) {
                String[] coordinate = poi.path("location").asText("").split(",");
                if (coordinate.length != 2) continue;
                try {
                    double longitude = Double.parseDouble(coordinate[0]);
                    double latitude = Double.parseDouble(coordinate[1]);
                    String name = poi.path("name").asText("").trim();
                    if (name.isEmpty() || !Double.isFinite(longitude) || !Double.isFinite(latitude)
                            || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) continue;
                    String address = poi.path("address").isTextual() ? poi.path("address").asText() : "";
                    places.add(new PlaceResult(poi.path("id").asText(""), name, address,
                            poi.path("adname").asText(""), longitude, latitude));
                } catch (NumberFormatException ignored) {
                    // Ignore POIs without a usable coordinate.
                }
            }
            return places;
        } catch (IOException e) {
            log.warn("AMap place search I/O error: {}", e.toString());
            throw new AmapUpstreamException("地点搜索暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmapUpstreamException("地点搜索已中断");
        } finally {
            permits.release();
        }
    }
}

record PlaceResult(String id, String name, String address, String district,
                   double longitude, double latitude) {}
