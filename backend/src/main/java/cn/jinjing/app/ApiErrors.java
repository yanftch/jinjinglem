package cn.jinjing.app;

import com.duomaomao.api.service.QuotaExceededException;
import com.duomaomao.api.service.UpstreamException;
import com.duomaomao.api.service.UpstreamStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

class ResourceMissingException extends RuntimeException {
    ResourceMissingException(String message) { super(message); }
}

class VersionConflictException extends RuntimeException {
    VersionConflictException(String message) { super(message); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(AmapNotConfiguredException.class)
    ResponseEntity<Map<String, String>> amapNotConfigured(AmapNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "AMAP_NOT_CONFIGURED", "error", e.getMessage()));
    }

    @ExceptionHandler(AmapBusyException.class)
    ResponseEntity<Map<String, String>> amapBusy(AmapBusyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("code", "AMAP_BUSY", "error", e.getMessage()));
    }

    @ExceptionHandler(AmapNoSafeRouteException.class)
    ResponseEntity<Map<String, String>> amapNoSafeRoute(AmapNoSafeRouteException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", "NO_CAMERA_FREE_ROUTE", "error", e.getMessage()));
    }

    @ExceptionHandler(AmapUpstreamException.class)
    ResponseEntity<Map<String, String>> amapUpstream(AmapUpstreamException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", "AMAP_UPSTREAM_ERROR", "error", e.getMessage()));
    }

    @ExceptionHandler(RouteUnavailableException.class)
    ResponseEntity<Map<String, String>> routeUnavailable(RouteUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<Map<String, String>> routeBusy(QuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({UpstreamException.class, UpstreamStatusException.class})
    ResponseEntity<Map<String, String>> routeUpstream(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "上游路线规划服务暂不可用"));
    }

    @ExceptionHandler(ResourceMissingException.class)
    ResponseEntity<Map<String, String>> missing(ResourceMissingException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<Map<String, String>> conflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
