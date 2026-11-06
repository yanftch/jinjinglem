package cn.jinjing.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
class AndroidUserService {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    AndroidUserService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    @Transactional
    Map<String, Object> register(String deviceId, String deviceSecret) {
        if (deviceId == null || !deviceId.matches("[0-9a-f]{64}") ||
                deviceSecret == null || !deviceSecret.matches("[0-9a-f]{64}")) {
            throw new MemberBadRequest("设备标识无效");
        }
        String secretHash = MemberService.hash(deviceSecret);
        List<Long> inserted = db.query("""
                INSERT INTO android_user(device_id,device_secret_hash) VALUES (?,?)
                ON CONFLICT (device_id) DO NOTHING RETURNING id
                """, (rs, i) -> rs.getLong(1), deviceId, secretHash);
        long userId;
        if (inserted.isEmpty()) {
            var rows = db.query("SELECT id,device_secret_hash FROM android_user WHERE device_id=? FOR UPDATE",
                    (rs, i) -> Map.entry(rs.getLong(1), rs.getString(2).trim()), deviceId);
            if (rows.isEmpty() || !MessageDigest.isEqual(rows.get(0).getValue().getBytes(StandardCharsets.US_ASCII),
                    secretHash.getBytes(StandardCharsets.US_ASCII))) throw new MemberUnauthorized("设备凭据不匹配，请联系管理员处理");
            userId = rows.get(0).getKey();
            db.update("UPDATE android_user SET last_seen_at=now() WHERE id=?", userId);
        } else userId = inserted.get(0);
        String token = "a_" + MemberService.randomToken();
        db.update("INSERT INTO android_user_session(token_hash,user_id,expires_at) VALUES (?,?,?)",
                MemberService.hash(token), userId, Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
        return Map.of("token", token, "user", status(userId));
    }

    long requireUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer a_")) throw new MemberUnauthorized("设备会话无效");
        String token = authorization.substring(7).trim();
        if (!token.matches("a_[0-9a-f]{64}")) throw new MemberUnauthorized("设备会话无效");
        List<Long> ids = db.query("SELECT user_id FROM android_user_session WHERE token_hash=? AND expires_at>now()",
                (rs, i) -> rs.getLong(1), MemberService.hash(token));
        if (ids.isEmpty()) throw new MemberUnauthorized("设备会话已过期，请重新打开 App");
        return ids.get(0);
    }

    long requireVip(HttpServletRequest request) {
        long userId = requireUser(request);
        Boolean active = db.queryForObject("SELECT member_expires_at>now() FROM android_user WHERE id=?", Boolean.class, userId);
        if (!Boolean.TRUE.equals(active)) throw new MemberRequired("Android 会员未开通或已到期");
        return userId;
    }

    Map<String, Object> status(long id) {
        return db.query("""
                SELECT id,device_id,member_expires_at,created_at,last_seen_at,
                       COALESCE(member_expires_at>now(),false) AS active
                FROM android_user WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new ResourceMissingException("Android 用户不存在");
            return Map.of("id", rs.getLong("id"), "deviceId", rs.getString("device_id").trim(),
                    "memberExpiresAt", rs.getTimestamp("member_expires_at") == null ? "" : rs.getTimestamp("member_expires_at").toInstant(),
                    "createdAt", rs.getTimestamp("created_at").toInstant(),
                    "lastSeenAt", rs.getTimestamp("last_seen_at").toInstant(),
                    "active", rs.getBoolean("active"));
        }, id);
    }

    String createRouteRequest(long userId) {
        String id = "r_" + MemberService.randomToken().substring(0, 32);
        db.update("INSERT INTO android_route_request(request_id,user_id,expires_at) VALUES (?,?,?)",
                id, userId, Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))));
        return id;
    }

    void requireRouteOwner(long userId, String requestId) {
        Integer count = db.queryForObject("""
                SELECT count(*) FROM android_route_request WHERE request_id=? AND user_id=? AND expires_at>now()
                """, Integer.class, requestId, userId);
        if (count == null || count == 0) throw new MemberForbidden("路线任务无效或不属于当前设备");
    }

    @Transactional
    Map<String, Object> adminChange(long id, String plan, String reason, String actor, String ip, UUID requestId) {
        if (plan == null || !List.of("MONTH", "YEAR", "NONE").contains(plan)) throw new MemberBadRequest("请选择月会员、年会员或取消会员");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new MemberBadRequest("请填写不超过 500 字的操作原因");
        var rows = db.query("SELECT member_expires_at FROM android_user WHERE id=? FOR UPDATE",
                (rs, i) -> rs.getTimestamp(1), id);
        if (rows.isEmpty()) throw new ResourceMissingException("Android 用户不存在");
        Instant before = rows.get(0) == null ? null : rows.get(0).toInstant();
        Instant after = switch (plan) {
            case "MONTH" -> Instant.now().plus(Duration.ofDays(30));
            case "YEAR" -> Instant.now().plus(Duration.ofDays(365));
            default -> null;
        };
        db.update("UPDATE android_user SET member_expires_at=?,updated_at=now() WHERE id=?",
                after == null ? null : Timestamp.from(after), id);
        db.update("""
                INSERT INTO android_user_admin_audit(user_id,actor,action,plan_code,reason,outcome,
                  before_value,after_value,request_id,source_ip)
                VALUES (?,?, 'SET_MEMBERSHIP',?,?,'SUCCESS',?::jsonb,?::jsonb,?,?)
                """, id, actor, plan, reason.trim(), snapshot(before), snapshot(after), requestId, ip);
        return status(id);
    }

    String snapshot(Instant expiry) {
        try { return json.writeValueAsString(Map.of("memberExpiresAt", expiry == null ? "" : expiry)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    @Scheduled(cron="0 20 4 * * *", zone="Asia/Shanghai")
    void pruneExpiredSessions() {
        db.update("DELETE FROM android_route_request WHERE expires_at<now()");
        db.update("DELETE FROM android_user_session WHERE expires_at<now()");
    }
}
