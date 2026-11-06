package cn.jinjing.app;

import com.duomaomao.api.dto.LocationPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;

class AmapRouteServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void detectsCameraBetweenPolylineVertices() {
        double distance = AmapRouteService.distanceToSegmentMeters(
                new AmapRouteService.Point(116.405, 39.9),
                new AmapRouteService.Point(116.4, 39.9),
                new AmapRouteService.Point(116.41, 39.9));
        assertEquals(0, distance, 0.01);
        assertTrue(AmapRouteService.distanceToSegmentMeters(
                new AmapRouteService.Point(116.405, 39.902),
                new AmapRouteService.Point(116.4, 39.9),
                new AmapRouteService.Point(116.41, 39.9)) > 100);
    }

    @Test
    void selectsCameraFreeAlternative() throws Exception {
        serve(query -> response(path("116.400000,39.900000;116.410000,39.900000", 600),
                path("116.400000,39.910000;116.410000,39.910000", 750)));
        AmapRouteService service = service("test-key",
                List.of(new AmapRouteService.Point(116.405, 39.9)));
        AmapRouteResponse result = service.plan(request());
        assertTrue(result.avoidsKnownCameras());
        assertEquals("AMAP", result.provider());
        assertEquals("39.910000", result.routePlanResponse().get(0).get(0).latitude());
    }

    @Test
    void retriesWithAvoidanceAndChecksReroutedLine() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        serve(query -> {
            calls.incrementAndGet();
            return query.contains("avoidpolygons")
                    ? response(path("116.400000,39.910000;116.410000,39.910000", 750))
                    : response(path("116.400000,39.900000;116.410000,39.900000", 600));
        });
        AmapRouteResponse result = service("test-key",
                List.of(new AmapRouteService.Point(116.405, 39.9))).plan(request());
        assertEquals(2, calls.get());
        assertEquals("39.910000", result.routePlanResponse().get(0).get(0).latitude());
    }

    @Test
    void neverReturnsUnsafeLineAndDoesNotNeedKeyToConstruct() throws Exception {
        serve(query -> response(path("116.400000,39.900000;116.410000,39.900000", 600)));
        AmapRouteService service = service("test-key",
                List.of(new AmapRouteService.Point(116.405, 39.9)));
        assertThrows(AmapNoSafeRouteException.class, () -> service.plan(request()));
        assertThrows(AmapNotConfiguredException.class, () -> service("", List.of()).plan(request()));
    }

    @Test
    void convertsUpstreamFailureWithoutExposingKey() throws Exception {
        serve(query -> "{\"status\":\"0\",\"infocode\":\"10021\"}");
        AmapUpstreamException error = assertThrows(AmapUpstreamException.class,
                () -> service("secret-key", List.of()).plan(request()));
        assertFalse(error.getMessage().contains("secret-key"));
    }

    @Test
    void reportsAmapQuotaAsBusy() throws Exception {
        serve(query -> "{\"status\":\"0\",\"infocode\":\"10003\"}");
        assertThrows(AmapBusyException.class, () -> service("test-key", List.of()).plan(request()));
    }

    private AmapRouteService service(String key, List<AmapRouteService.Point> cameras) {
        JdbcTemplate db = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                assertTrue(sql.contains("deleted_at IS NULL"));
                assertTrue(sql.contains("camera_type <> 'DISCARD'"));
                assertTrue(sql.contains("coordinate_system='GCJ02'"));
                return (List<T>) cameras;
            }
        };
        return new AmapRouteService(db, new ObjectMapper(), key,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/route", 100);
    }

    private AmapRouteRequest request() {
        return new AmapRouteRequest(1,
                new LocationPoint("起点", "", 39.9, 116.4),
                new LocationPoint("终点", "", 39.9, 116.41), List.of());
    }

    private interface Reply { String body(String query); }

    private void serve(Reply reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/route", exchange -> {
            byte[] body = reply.body(exchange.getRequestURI().getRawQuery())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private String path(String polyline, int duration) {
        return "{\"distance\":\"1000\",\"cost\":{\"duration\":\"" + duration
                + "\"},\"steps\":[{\"instruction\":\"前行\",\"polyline\":\""
                + polyline + "\"}]}";
    }

    private String response(String... paths) {
        return "{\"status\":\"1\",\"route\":{\"paths\":[" + String.join(",", paths) + "]}}";
    }
}
