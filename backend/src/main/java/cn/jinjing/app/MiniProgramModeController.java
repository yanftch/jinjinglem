package cn.jinjing.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MiniProgramModeController {
    private final JdbcTemplate db;
    private final MemberService members;

    MiniProgramModeController(JdbcTemplate db, MemberService members) {
        this.db = db;
        this.members = members;
    }

    record ModeChange(String mode) {}
    record ArrivalInput(@NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                        @DecimalMin("0") @DecimalMax("5000") BigDecimal accuracy) {}

    @GetMapping("/api/v1/site/miniprogram-mode")
    Map<String, String> publicMode() {
        return Map.of("mode", mode());
    }

    @GetMapping("/api/v1/admin/miniprogram-mode")
    Map<String, Object> adminMode() {
        return db.queryForMap("SELECT mode, updated_at, updated_by FROM miniprogram_mode WHERE id=1");
    }

    @PostMapping("/api/v1/admin/miniprogram-mode")
    @Transactional
    Map<String, Object> changeMode(@RequestBody ModeChange input, Authentication authentication,
                                   HttpServletRequest request) {
        if (input == null || input.mode() == null || !List.of("LIVE", "REVIEW").contains(input.mode())) {
            throw new IllegalArgumentException("模式只能是实际模式或提审模式");
        }
        String before = db.queryForObject("SELECT mode FROM miniprogram_mode WHERE id=1 FOR UPDATE", String.class);
        String actor = authentication == null ? "local-dev" : authentication.getName();
        db.update("UPDATE miniprogram_mode SET mode=?,updated_at=now(),updated_by=? WHERE id=1",
                input.mode(), actor);
        db.update("""
                INSERT INTO miniprogram_mode_audit(mode_before,mode_after,actor,source_ip)
                VALUES (?,?,?,?)
                """, before, input.mode(), actor, request.getRemoteAddr());
        return adminMode();
    }

    @GetMapping("/api/v1/admin/miniprogram-mode/history")
    List<Map<String, Object>> modeHistory() {
        return db.queryForList("""
                SELECT id,mode_before,mode_after,actor,source_ip,created_at
                FROM miniprogram_mode_audit ORDER BY id DESC LIMIT 50
                """);
    }

    @PostMapping("/api/v1/review/arrivals")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> recordArrival(@Valid @RequestBody ArrivalInput input, HttpServletRequest request) {
        long memberId = members.requireMiniProgram(request);
        if (!"REVIEW".equals(mode())) throw new VersionConflictException("当前模式不接受到达记录");
        long id = db.queryForObject("""
                INSERT INTO review_arrival(member_id,longitude,latitude,accuracy_m)
                VALUES (?,?,?,?) RETURNING id
                """, Long.class, memberId, input.longitude(), input.latitude(), input.accuracy());
        return db.queryForMap("""
                SELECT id,longitude,latitude,accuracy_m,coordinate_system,created_at
                FROM review_arrival WHERE id=?
                """, id);
    }

    @GetMapping("/api/v1/review/arrivals")
    List<Map<String, Object>> myArrivals(HttpServletRequest request) {
        long memberId = members.requireMiniProgram(request);
        return db.queryForList("""
                SELECT id,longitude,latitude,accuracy_m,coordinate_system,created_at
                FROM review_arrival WHERE member_id=? ORDER BY id DESC LIMIT 20
                """, memberId);
    }

    @GetMapping("/api/v1/admin/review-arrivals")
    Map<String, Object> allArrivals(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数无效");
        }
        long total = db.queryForObject("SELECT count(*) FROM review_arrival", Long.class);
        var rows = db.queryForList("""
                SELECT id,member_id,longitude,latitude,accuracy_m,coordinate_system,created_at
                FROM review_arrival ORDER BY id DESC LIMIT ? OFFSET ?
                """, size, page * size);
        return Map.of("total", total, "items", rows);
    }

    private String mode() {
        return db.queryForObject("SELECT mode FROM miniprogram_mode WHERE id=1", String.class);
    }
}
