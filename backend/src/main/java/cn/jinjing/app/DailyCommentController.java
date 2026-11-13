package cn.jinjing.app;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/comments/daily")
class DailyCommentController {
    private final DailyCommentService service;

    DailyCommentController(DailyCommentService service) { this.service = service; }

    @GetMapping("/summary")
    DailyCommentService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.summary(day(date));
    }

    @GetMapping("/summary/range")
    DailyCommentService.RangeSummary rangeSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        validateRange(startDate, endDate);
        return service.rangeSummary(startDate, endDate);
    }

    @GetMapping
    List<Map<String, Object>> comments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        if (startDate != null || endDate != null) {
            if (date != null || startDate == null || endDate == null) {
                throw new IllegalArgumentException("开始日期和结束日期必须同时填写，且不能与统计日期同时使用");
            }
            validateRange(startDate, endDate);
            return service.comments(startDate, endDate, Math.max(0, offset), Math.max(1, Math.min(500, limit)));
        }
        return service.comments(day(date), Math.max(0, offset), Math.max(1, Math.min(500, limit)));
    }

    @PostMapping("/retry")
    DailyCommentService.Summary retry(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = day(date);
        service.send(target);
        return service.summary(target);
    }

    private LocalDate day(LocalDate requested) {
        return requested == null ? LocalDate.now(DailyCommentService.ZONE).minusDays(1) : requested;
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    }
}
