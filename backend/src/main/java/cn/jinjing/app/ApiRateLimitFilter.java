package cn.jinjing.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Basic per-client throttling for public endpoints. The production origin is loopback-only behind Cloudflare Tunnel. */
final class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_TRACKED_KEYS = 50_000;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        String key = clientAddress(request) + ':' + rule.name;
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_KEYS) {
            prune(now);
            if (windows.size() >= MAX_TRACKED_KEYS) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":\"请求繁忙，请稍后再试\"}");
                return;
            }
        }
        long bucketStart = now / rule.windowMillis * rule.windowMillis;
        int[] observedCount = new int[1];
        windows.compute(key, (ignored, current) -> {
            Window updated = current == null || current.start != bucketStart
                    ? new Window(bucketStart, 1)
                    : new Window(bucketStart, current.count + 1);
            observedCount[0] = updated.count;
            return updated;
        });
        if (requests.incrementAndGet() % 512 == 0) prune(now);
        if (observedCount[0] > rule.limit) {
            long retrySeconds = Math.max(1, (bucketStart + rule.windowMillis - now + 999) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retrySeconds));
            response.setHeader("Cache-Control", "no-store");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private void prune(long now) {
        if (windows.size() < MAX_TRACKED_KEYS) return;
        windows.entrySet().removeIf(entry -> now - entry.getValue().start > Duration.ofHours(2).toMillis());
    }

    private String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        // Trust Cloudflare's client address only for requests arriving from the local Tunnel origin.
        if (isLoopback(remote)) {
            String cloudflareAddress = request.getHeader("CF-Connecting-IP");
            if (cloudflareAddress != null && cloudflareAddress.length() <= 64
                    && cloudflareAddress.matches("[0-9a-fA-F:.]+")) return cloudflareAddress;
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private boolean isLoopback(String address) {
        try { return address != null && InetAddress.getByName(address).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private Rule ruleFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/login".equals(path)) return new Rule("admin-login", 10, Duration.ofMinutes(15));
        if ("POST".equals(method) && "/api/v1/members/wechat-login".equals(path))
            return new Rule("wechat-login", 20, Duration.ofMinutes(15));
        if ("POST".equals(method) && "/api/v1/android/users/register".equals(path))
            return new Rule("android-register", 20, Duration.ofMinutes(15));
        if ("POST".equals(method) && "/api/v1/camera-reports".equals(path))
            return new Rule("camera-report", 10, Duration.ofHours(1));
        if ("POST".equals(method) && ("/api/v1/routes/plan".equals(path)
                || "/api/v1/routes/amap/plan".equals(path)))
            return new Rule("route-plan", 12, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/routes/")) return new Rule("route-api", 120, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/") && !path.startsWith("/api/v1/admin/"))
            return new Rule("public-api", 300, Duration.ofMinutes(1));
        return null;
    }

    private static final class Window {
        private final long start;
        private int count;
        private Window(long start, int count) { this.start = start; this.count = count; }
    }

    private static final class Rule {
        private final String name;
        private final int limit;
        private final long windowMillis;
        private Rule(String name, int limit, Duration window) {
            this.name = name;
            this.limit = limit;
            this.windowMillis = window.toMillis();
        }
    }
}
