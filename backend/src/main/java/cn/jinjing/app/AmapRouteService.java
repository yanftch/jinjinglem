package cn.jinjing.app;

import com.duomaomao.api.dto.LocationPoint;
import com.duomaomao.api.dto.RoutePlanResult.RoutePoint;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class AmapRouteService {
    private static final Logger log = LoggerFactory.getLogger(AmapRouteService.class);
    private static final int MAX_ATTEMPTS = 4;
    private static final int MAX_AVOID_AREAS = 32;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key;
    private final String routeUrl;
    private final int radiusMeters;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Semaphore permits = new Semaphore(3);

    AmapRouteService(JdbcTemplate db, ObjectMapper json,
                     @Value("${app.amap-web-service-key:}") String key,
                     @Value("${app.amap-route-url:https://restapi.amap.com/v5/direction/driving}") String routeUrl,
                     @Value("${app.route-camera-radius-meters:100}") int radiusMeters) {
        if (radiusMeters < 20 || radiusMeters > 500) throw new IllegalArgumentException("探头避让半径须在 20–500 米之间");
        this.db = db;
        this.json = json;
        this.key = key;
        this.routeUrl = routeUrl;
        this.radiusMeters = radiusMeters;
    }

    AmapRouteResponse plan(AmapRouteRequest request) {
        if (key == null || key.isBlank()) throw new AmapNotConfiguredException("未配置高德 Web 服务 Key");
        if (!permits.tryAcquire()) throw new AmapBusyException("路线规划繁忙，请稍后重试");
        try {
            Set<Point> avoidAreas = new LinkedHashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
            for (int attempt = 0; attempt < MAX_ATTEMPTS && System.nanoTime() < deadline; attempt++) {
                List<Candidate> candidates = fetch(request, avoidAreas);
                Candidate best = null;
                for (Candidate candidate : candidates) {
                    Candidate checked = new Candidate(candidate.points(), candidate.steps(),
                            candidate.distanceMeters(), candidate.durationSeconds(), camerasNear(candidate.points()));
                    if (best == null || compare(checked, best, request.preferenceOrDefault()) < 0) best = checked;
                }
                if (best == null) break;
                if (best.hazards().isEmpty()) return response(best);
                int before = avoidAreas.size();
                for (Point camera : best.hazards()) {
                    if (avoidAreas.size() == MAX_AVOID_AREAS) break;
                    avoidAreas.add(camera);
                }
                if (avoidAreas.size() == before) break;
            }
            throw new AmapNoSafeRouteException("未找到避开已收录探头的路线，请调整起终点或途经点");
        } finally {
            permits.release();
        }
    }

    private int compare(Candidate left, Candidate right, int preference) {
        int hits = Integer.compare(left.hazards().size(), right.hazards().size());
        if (hits != 0) return hits;
        return preference == 2
                ? Integer.compare(left.distanceMeters(), right.distanceMeters())
                : Integer.compare(left.durationSeconds(), right.durationSeconds());
    }

    private List<Candidate> fetch(AmapRouteRequest request, Set<Point> avoidAreas) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("key", key);
        params.put("origin", coordinate(request.startPoint()));
        params.put("destination", coordinate(request.endPoint()));
        if (!request.waypointsOrEmpty().isEmpty()) {
            params.put("waypoints", String.join(";", request.waypointsOrEmpty().stream().map(this::coordinate).toList()));
        }
        params.put("strategy", request.preferenceOrDefault() == 3 ? "35" : "32");
        params.put("alternative_route", "3");
        params.put("show_fields", "cost,polyline,navi");
        if (!avoidAreas.isEmpty()) {
            params.put("avoidpolygons", String.join("|", avoidAreas.stream().map(this::polygon).toList()));
        }
        StringBuilder url = new StringBuilder(routeUrl).append('?');
        params.forEach((name, value) -> {
            if (url.charAt(url.length() - 1) != '?') url.append('&');
            url.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(25)).GET().build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AMap route HTTP status {}", response.statusCode());
                throw new AmapUpstreamException("高德路线规划服务暂不可用");
            }
            JsonNode root = json.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                String code = root.path("infocode").asText();
                log.warn("AMap route error code {}", code);
                if (Set.of("10001", "10002", "10005", "10006", "10007").contains(code)) {
                    throw new AmapNotConfiguredException("高德 Web 服务 Key 无效、无权限或 IP 白名单未配置");
                }
                if (Set.of("10003", "10004").contains(code)) {
                    throw new AmapBusyException("高德路线规划配额或访问频率已达上限，请稍后重试");
                }
                throw new AmapUpstreamException("高德路线规划服务暂不可用");
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray()) throw new AmapUpstreamException("高德未返回有效路线");
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode path : paths) {
                Candidate candidate = parse(path);
                if (candidate != null) candidates.add(candidate);
            }
            if (!paths.isEmpty() && candidates.isEmpty()) {
                throw new AmapUpstreamException("高德未返回可绘制的路线");
            }
            return candidates;
        } catch (IOException e) {
            log.warn("AMap route request failed ({})", e.getClass().getSimpleName());
            throw new AmapUpstreamException("高德路线规划服务暂不可用");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmapUpstreamException("路线规划已中断");
        }
    }

    private Candidate parse(JsonNode path) {
        JsonNode steps = path.path("steps");
        if (!steps.isArray()) return null;
        List<Point> points = new ArrayList<>();
        List<AmapRouteStep> directions = new ArrayList<>();
        for (JsonNode step : steps) {
            String polyline = step.path("polyline").asText("");
            for (String token : polyline.split(";")) {
                String[] pair = token.split(",");
                if (pair.length != 2) continue;
                try {
                    Point point = new Point(Double.parseDouble(pair[0]), Double.parseDouble(pair[1]));
                    if (!Double.isFinite(point.longitude()) || !Double.isFinite(point.latitude())) continue;
                    if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) points.add(point);
                } catch (NumberFormatException ignored) {
                    // Skip malformed coordinates, then reject the path if fewer than two remain.
                }
            }
            String instruction = step.path("instruction").asText("");
            if (!instruction.isBlank()) directions.add(new AmapRouteStep(instruction));
        }
        int distance = number(path.path("distance"));
        if (points.size() < 2 || distance <= 0) return null;
        int duration = number(path.path("cost").path("duration"));
        return new Candidate(points, directions, distance,
                duration > 0 ? duration : Integer.MAX_VALUE, List.of());
    }

    private List<Point> camerasNear(List<Point> points) {
        double west = points.stream().mapToDouble(Point::longitude).min().orElseThrow();
        double east = points.stream().mapToDouble(Point::longitude).max().orElseThrow();
        double south = points.stream().mapToDouble(Point::latitude).min().orElseThrow();
        double north = points.stream().mapToDouble(Point::latitude).max().orElseThrow();
        double latitudePadding = radiusMeters / 110540.0;
        double farthestLatitude = Math.max(Math.abs(south), Math.abs(north));
        double longitudePadding = radiusMeters /
                (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(farthestLatitude))));
        List<Point> cameras = db.query("""
                SELECT longitude,latitude FROM camera
                WHERE deleted_at IS NULL AND camera_type <> 'DISCARD'
                  AND coordinate_system='GCJ02'
                  AND longitude BETWEEN ? AND ? AND latitude BETWEEN ? AND ?
                """, (rs, index) -> new Point(rs.getDouble(1), rs.getDouble(2)),
                west - longitudePadding, east + longitudePadding,
                south - latitudePadding, north + latitudePadding);
        List<Point> near = new ArrayList<>();
        for (Point camera : cameras) {
            for (int i = 1; i < points.size(); i++) {
                if (distanceToSegmentMeters(camera, points.get(i - 1), points.get(i)) <= radiusMeters) {
                    near.add(camera);
                    break;
                }
            }
        }
        return near;
    }

    static double distanceToSegmentMeters(Point camera, Point start, Point end) {
        double longitudeScale = 111320.0 * Math.cos(Math.toRadians(camera.latitude()));
        double x1 = (start.longitude() - camera.longitude()) * longitudeScale;
        double y1 = (start.latitude() - camera.latitude()) * 110540.0;
        double x2 = (end.longitude() - camera.longitude()) * longitudeScale;
        double y2 = (end.latitude() - camera.latitude()) * 110540.0;
        double length = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
        if (length == 0) return Math.hypot(x1, y1);
        double t = Math.max(0, Math.min(1, -(x1 * (x2 - x1) + y1 * (y2 - y1)) / length));
        return Math.hypot(x1 + t * (x2 - x1), y1 + t * (y2 - y1));
    }

    private AmapRouteResponse response(Candidate route) {
        List<RoutePoint> points = route.points().stream()
                .map(point -> new RoutePoint(decimal(point.longitude()), decimal(point.latitude()))).toList();
        String distance = route.distanceMeters() >= 1000
                ? String.format(Locale.ROOT, "%.1f 公里", route.distanceMeters() / 1000.0)
                : route.distanceMeters() + " 米";
        String time = route.durationSeconds() == Integer.MAX_VALUE
                ? "预计时间未知" : Math.max(1, (route.durationSeconds() + 59) / 60) + " 分钟";
        return new AmapRouteResponse("AMAP", true, radiusMeters, List.of(points), route.steps(), distance, time);
    }

    private String polygon(Point point) {
        double lat = radiusMeters * 1.25 / 110540.0;
        double lng = radiusMeters * 1.25 /
                (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(point.latitude()))));
        return String.join(";",
                decimal(point.longitude() - lng) + "," + decimal(point.latitude() - lat),
                decimal(point.longitude() + lng) + "," + decimal(point.latitude() - lat),
                decimal(point.longitude() + lng) + "," + decimal(point.latitude() + lat),
                decimal(point.longitude() - lng) + "," + decimal(point.latitude() + lat));
    }

    private String coordinate(LocationPoint point) {
        if (!Double.isFinite(point.longitude()) || !Double.isFinite(point.latitude())) {
            throw new IllegalArgumentException("起终点和途经点必须是有效坐标");
        }
        return decimal(point.longitude()) + "," + decimal(point.latitude());
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static int number(JsonNode node) {
        try { return (int) Math.round(Double.parseDouble(node.asText("0"))); }
        catch (NumberFormatException e) { return 0; }
    }

    record Point(double longitude, double latitude) {}
    private record Candidate(List<Point> points, List<AmapRouteStep> steps,
                             int distanceMeters, int durationSeconds, List<Point> hazards) {}
}
