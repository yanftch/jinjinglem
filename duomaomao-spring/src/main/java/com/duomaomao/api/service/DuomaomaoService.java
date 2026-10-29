package com.duomaomao.api.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.duomaomao.api.config.CloudApiProperties;
import com.duomaomao.api.dto.AppUpdate;
import com.duomaomao.api.dto.CameraComments;
import com.duomaomao.api.dto.CameraPoint;
import com.duomaomao.api.dto.GaodeKeys;
import com.duomaomao.api.dto.LabelsResult;
import com.duomaomao.api.dto.LocationPoint;
import com.duomaomao.api.dto.PlanResult;
import com.duomaomao.api.dto.RoutePlanRequest;
import com.duomaomao.api.dto.RoutePlanResult;
import com.duomaomao.api.dto.TokenValidation;
import com.duomaomao.api.dto.UserInfo;
import com.example.cloudapi.CloudApiException;
import com.example.cloudapi.EncryptedCloudApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对 duomaomao 云端接口的薄封装：路径解析、TTL 缓存、并发闸门、异常归一。
 */
@Service
public class DuomaomaoService {

    private static final Logger log = LoggerFactory.getLogger(DuomaomaoService.class);

    /** 服务端下发的路径，作为 fallback；实际路径以 /androidCheckUpdate202609 为准。 */
    public static final String P_UPDATE = "/androidCheckUpdate202609";
    public static final String P_LABELS = "/qwvsxdvglkj";
    public static final String P_GAODE = "/getgaodeSDK4Android202609";
    public static final String P_PLAN = "/planRoute202503an202609";
    public static final String P_PLAN_RESULT = "/getPlanResultios202609";
    public static final String P_COMMENT = "/getCommentNew202609";
    public static final String P_USER = "/getUserInfo202609";
    public static final String P_VALIDATE = "/validateToken202609";

    private static final Map<String, String> FALLBACK_PATHS = Map.of(
            "labels", P_LABELS,
            "gaode", P_GAODE,
            "planRoute", P_PLAN,
            "planResult", P_PLAN_RESULT,
            "comment", P_COMMENT,
            "userInfo", P_USER,
            "validateToken", P_VALIDATE);

    /** 规划失败的结果也缓存，但只留 5 分钟，避免一次网络抖动把失败固化半小时。 */
    private static final Duration FAILED_ROUTE_TTL = Duration.ofMinutes(5);

    private final EncryptedCloudApiClient client;
    private final CloudApiProperties properties;

    private final TtlCache<AppUpdate> updateCache = new TtlCache<>(2);
    private final TtlCache<LabelsResult> labelsCache = new TtlCache<>(8);
    private final TtlCache<GaodeKeys> gaodeCache = new TtlCache<>(2);
    private final TtlCache<CameraComments> commentsCache = new TtlCache<>(256);
    private final TtlCache<RoutePlanResult> routeCache = new TtlCache<>(256);

    private final Semaphore routePermits;

    public DuomaomaoService(EncryptedCloudApiClient client, CloudApiProperties properties) {
        this.client = client;
        this.properties = properties;
        this.routePermits = new Semaphore(Math.max(1, properties.getMaxConcurrentRoutePlans()), true);
    }

    // ---------------------------------------------------------------- update / paths

    public AppUpdate update() {
        AppUpdate cached = updateCache.get("update");
        if (cached != null) {
            return cached;
        }
        AppUpdate fresh = callDirect(P_UPDATE, Map.of(), AppUpdate.class);
        updateCache.put("update", fresh, properties.getUpdateCacheTtl());
        return fresh;
    }

    /** 当前生效的端点路径。拿不到下发表时退回内置常量。 */
    public Map<String, String> paths() {
        AppUpdate update = update();
        if (update == null || update.paths() == null || update.paths().isEmpty()) {
            return FALLBACK_PATHS;
        }
        return Map.copyOf(update.paths());
    }

    // ---------------------------------------------------------------- labels / cameras

    public LabelsResult labels(int page, boolean refresh) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1");
        }
        String cacheKey = "labels:" + page;
        if (!refresh) {
            LabelsResult cached = labelsCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        LabelsResult fresh = callEndpoint("labels", P_LABELS, Map.of("page", page), LabelsResult.class);
        labelsCache.put(cacheKey, fresh, properties.getLabelsCacheTtl());
        return fresh;
    }

    /**
     * 在全量列表里按 id 找探头。服务端没有「按 id 查探头」的接口，
     * 所以只能查全量；结果会被 labels 缓存复用。
     */
    public Optional<CameraPoint> findCamera(int id) {
        for (int page = 1; page <= 2; page++) {
            LabelsResult result = labels(page, false);
            if (result == null || result.labelsData() == null) {
                continue;
            }
            for (CameraPoint point : result.labelsData()) {
                if (point.id() == id) {
                    return Optional.of(point);
                }
            }
            if (result.labelsData().size() < 5000) {
                break;
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- gaode / user / comment

    public GaodeKeys gaodeKeys() {
        GaodeKeys cached = gaodeCache.get("gaode");
        if (cached != null) {
            return cached;
        }
        GaodeKeys fresh = callEndpoint("gaode", P_GAODE, Map.of("platform", "android"), GaodeKeys.class);
        gaodeCache.put("gaode", fresh, properties.getGaodeCacheTtl());
        return fresh;
    }

    public UserInfo userInfo(String openid) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (openid != null && !openid.isBlank()) {
            body.put("OPENID", openid.trim());
        }
        body.put("func", "getUserInfo");
        return callEndpoint("userInfo", P_USER, body, UserInfo.class);
    }

    public CameraComments comments(int id) {
        String cacheKey = "comment:" + id;
        CameraComments cached = commentsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        CameraComments fresh = callEndpoint("comment", P_COMMENT,
                Map.of("id", id, "dowhat", "query"), CameraComments.class);
        commentsCache.put(cacheKey, fresh, properties.getCommentsCacheTtl());
        return fresh;
    }

    public TokenValidation validateToken(String token, String deviceId) {
        return callEndpoint("validateToken", P_VALIDATE,
                Map.of("token", token, "device_id", deviceId), TokenValidation.class);
    }

    // ---------------------------------------------------------------- routes

    public RoutePlanResult planRoute(RoutePlanRequest request) {
        String cacheKey = routeCacheKey(request);
        RoutePlanResult cached = routeCache.get(cacheKey);
        if (cached != null) {
            log.debug("route cache hit: {}", cacheKey);
            return cached;
        }

        if (!acquireRoutePermit()) {
            throw new QuotaExceededException("规划请求过多，请稍后重试（并发上限 "
                    + properties.getMaxConcurrentRoutePlans() + "）");
        }
        try {
            RoutePlanResult fresh = callEndpoint("planRoute", P_PLAN, routeBody(request), RoutePlanResult.class);
            if (fresh == null) {
                throw new UpstreamException("上游返回 null；通常是缺少 preferenceValue，请确认上游契约是否变更");
            }
            Duration ttl = fresh.isSuccessOnPlanRoute()
                    ? properties.getRouteCacheTtl()
                    : FAILED_ROUTE_TTL;
            routeCache.put(cacheKey, fresh, ttl);
            return fresh;
        } finally {
            routePermits.release();
        }
    }

    public PlanResult planResult(String openid) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (openid != null && !openid.isBlank()) {
            body.put("OPENID", openid.trim());
        }
        return callEndpoint("planResult", P_PLAN_RESULT, body, PlanResult.class);
    }

    public int cachedRouteCount() {
        return routeCache.size();
    }

    // ---------------------------------------------------------------- request building

    private Map<String, Object> routeBody(RoutePlanRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.openid() != null && !request.openid().isBlank()) {
            body.put("OPENID", request.openid().trim());
        }
        body.put("currentHoldingLicenceValue", request.licenceOrDefault());
        body.put("startPoint", point(request.startPoint(), "起点"));
        body.put("endPoint", point(request.endPoint(), "终点"));

        List<Map<String, Object>> waypoints = new ArrayList<>();
        int index = 1;
        for (LocationPoint waypoint : request.waypointsOrEmpty()) {
            waypoints.add(point(waypoint, "途经点" + index++));
        }
        body.put("waypoints", waypoints);
        body.put("preferenceValue", request.preferenceOrDefault());
        return body;
    }

    private Map<String, Object> point(LocationPoint point, String defaultName) {
        String name = point.name() == null || point.name().isBlank() ? defaultName : point.name().trim();
        String address = point.address() == null || point.address().isBlank() ? name : point.address().trim();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("address", address);
        map.put("latitude", point.latitude());
        map.put("longitude", point.longitude());
        return map;
    }

    private String routeCacheKey(RoutePlanRequest request) {
        StringBuilder key = new StringBuilder();
        key.append(request.openid() == null ? "" : request.openid().trim())
                .append('|').append(request.licenceOrDefault())
                .append('|').append(request.preferenceOrDefault());
        appendPoint(key, request.startPoint());
        appendPoint(key, request.endPoint());
        for (LocationPoint waypoint : request.waypointsOrEmpty()) {
            appendPoint(key, waypoint);
        }
        return key.toString();
    }

    private void appendPoint(StringBuilder key, LocationPoint point) {
        key.append('|').append(Double.toString(point.latitude()))
                .append(',').append(Double.toString(point.longitude()));
    }

    private boolean acquireRoutePermit() {
        try {
            return routePermits.tryAcquire(properties.getRouteQueueTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------- transport

    private <T> T callEndpoint(String endpointKey, String fallbackPath, Map<String, ?> body, Class<T> type) {
        long start = System.nanoTime();
        try {
            T result = client.postToEndpoint(endpointKey, fallbackPath, P_UPDATE, body, type);
            log.debug("upstream {} ok in {} ms", endpointKey, elapsedMillis(start));
            return result;
        } catch (EncryptedCloudApiClient.HttpStatusException e) {
            log.warn("upstream {} HTTP {} in {} ms", endpointKey, e.statusCode(), elapsedMillis(start));
            throw new UpstreamStatusException(e.statusCode(), e.responseBody());
        } catch (CloudApiException e) {
            throw new UpstreamException("上游接口调用失败: " + endpointKey, e);
        } catch (IOException e) {
            throw new UpstreamException("上游网络错误: " + endpointKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("上游请求被中断: " + endpointKey, e);
        }
    }

    private <T> T callDirect(String path, Map<String, ?> body, Class<T> type) {
        long start = System.nanoTime();
        try {
            T result = client.postEncrypted(path, body, type);
            log.debug("upstream {} ok in {} ms", path, elapsedMillis(start));
            return result;
        } catch (EncryptedCloudApiClient.HttpStatusException e) {
            log.warn("upstream {} HTTP {} in {} ms", path, e.statusCode(), elapsedMillis(start));
            throw new UpstreamStatusException(e.statusCode(), e.responseBody());
        } catch (CloudApiException e) {
            throw new UpstreamException("上游接口调用失败: " + path, e);
        } catch (IOException e) {
            throw new UpstreamException("上游网络错误: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("上游请求被中断: " + path, e);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
