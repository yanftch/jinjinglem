package com.duomaomao.api.web;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.duomaomao.api.service.ExportInProgressException;
import com.duomaomao.api.service.QuotaExceededException;
import com.duomaomao.api.service.UpstreamException;
import com.duomaomao.api.service.UpstreamStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误出口。对外只用 RFC 7807 ProblemDetail，不把 Bearer / 完整上游 body 透出去。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}：Spring Boot 开启
 * {@code spring.mvc.problemdetails.enabled} 后会注册内置的
 * {@code ProblemDetailsExceptionHandler}，它同样处理
 * {@code MethodArgumentNotValidException} 且 order 更靠前，会把我们的字段级
 * {@code errors} 覆盖成一句 "Invalid request content."。这里必须抢在它前面。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final int BODY_PREVIEW_LIMIT = 2000;

    /** 上游业务网关返回非 2xx（例如 400 FUNCTIONS_INVOCATION_FAILED）。 */
    @ExceptionHandler(UpstreamStatusException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamStatus(UpstreamStatusException e) {
        log.warn("upstream rejected the request: HTTP {}", e.statusCode());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        detail.setTitle("Upstream rejected the request");
        detail.setDetail("upstream HTTP " + e.statusCode());
        detail.setProperty("upstreamStatus", e.statusCode());
        detail.setProperty("upstreamBody", preview(e.responseBody()));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(detail);
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ProblemDetail> handleUpstream(UpstreamException e) {
        log.warn("upstream failure: {}", e.getMessage(), e.getCause());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        detail.setTitle("Upstream call failed");
        detail.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(detail);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ProblemDetail> handleQuota(QuotaExceededException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Too many route planning requests");
        detail.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(detail);
    }

    @ExceptionHandler(CameraNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(CameraNotFoundException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Camera not found");
        detail.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(detail);
    }

    @ExceptionHandler(ExportFileNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleExportFileNotFound(ExportFileNotFoundException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Export file not found");
        detail.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(detail);
    }

    @ExceptionHandler(ExportInProgressException.class)
    public ResponseEntity<ProblemDetail> handleExportBusy(ExportInProgressException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Export already running");
        detail.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid request");
        detail.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage() == null
                        ? "invalid" : error.getDefaultMessage()));

        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Validation failed");
        detail.setDetail("请求参数不合法");
        detail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(detail);
    }

    private String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= BODY_PREVIEW_LIMIT ? text : text.substring(0, BODY_PREVIEW_LIMIT) + "…";
    }
}
