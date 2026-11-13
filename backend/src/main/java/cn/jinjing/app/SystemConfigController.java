package cn.jinjing.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system-config")
class SystemConfigController {
    private static final List<String> DURATIONS = List.of("30_DAYS", "99_YEARS");
    private final JdbcTemplate db;

    SystemConfigController(JdbcTemplate db) { this.db = db; }

    record GiftDurationRequest(String giftDuration) {}

    @GetMapping
    Map<String, Object> getConfig() {
        return db.queryForMap("SELECT duration AS \"giftDuration\", updated_at AS \"updatedAt\", updated_by AS \"updatedBy\" FROM system_membership_gift_config WHERE id=1");
    }

    @PostMapping
    @Transactional
    Map<String, Object> updateConfig(@RequestBody GiftDurationRequest input, Authentication authentication,
                                    HttpServletRequest request) {
        if (input == null || !DURATIONS.contains(input.giftDuration()))
            throw new IllegalArgumentException("赠送时长只能选择30天或99年");
        String before = db.queryForObject("SELECT duration FROM system_membership_gift_config WHERE id=1 FOR UPDATE", String.class);
        String actor = authentication == null ? "local-dev" : authentication.getName();
        db.update("UPDATE system_membership_gift_config SET duration=?,updated_at=now(),updated_by=? WHERE id=1",
                input.giftDuration(), actor);
        db.update("INSERT INTO system_membership_gift_config_audit(duration_before,duration_after,actor,source_ip) VALUES (?,?,?,?)",
                before, input.giftDuration(), actor, request.getRemoteAddr());
        return getConfig();
    }

    @GetMapping("/history")
    List<Map<String, Object>> history() {
        return db.queryForList("SELECT id,duration_before,duration_after,actor,source_ip,created_at FROM system_membership_gift_config_audit ORDER BY id DESC LIMIT 100");
    }
}
