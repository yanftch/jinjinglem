package cn.jinjing.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

@Service
class MemberService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private final JdbcTemplate db;
    private final ObjectMapper json;

    MemberService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    Map<String, Object> login(String openid) {
        if (openid == null || openid.isBlank() || openid.length() > 128) throw new MemberBadRequest("微信身份无效");
        long memberId = -1;
        boolean newMember = false;
        for (int attempt = 0; attempt < 20; attempt++) {
            List<Long> created = db.query("""
                    INSERT INTO member_account(wechat_openid,member_no,trial_started_at,trial_expires_at)
                    VALUES (?,?,now(),now()) ON CONFLICT DO NOTHING RETURNING id
                    """, (rs, i) -> rs.getLong(1), openid, randomMemberNo());
            if (!created.isEmpty()) {
                memberId = created.get(0);
                newMember = true;
                break;
            }
            List<Long> existing = db.query("SELECT id FROM member_account WHERE wechat_openid=?",
                    (rs, i) -> rs.getLong(1), openid);
            if (!existing.isEmpty()) {
                memberId = existing.get(0);
                break;
            }
        }
        if (memberId < 0) throw new IllegalStateException("无法生成唯一会员编号");
        if (newMember) {
            db.update("""
                    INSERT INTO member_entitlement(member_id,source,days,reason)
                    VALUES (?,'WELCOME',30,'新用户首次进入小程序赠送')
                    ON CONFLICT DO NOTHING
                    """, memberId);
            recalculate(memberId);
        }
        return issueSession(memberId, "MINIPROGRAM", null);
    }

    @Transactional
    Map<String, Object> issueSession(long memberId, String deviceType, String deviceId) {
        String token = randomToken();
        db.update("INSERT INTO member_session(token_hash,member_id,device_type,device_id,expires_at) VALUES (?,?,?,?,?)",
                hash(token), memberId, deviceType, deviceId, Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
        return Map.of("token", token, "member", status(memberId));
    }

    long requireLogin(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new MemberUnauthorized("请先登录");
        String token = authorization.substring(7).trim();
        if (token.length() < 20 || token.length() > 128) throw new MemberUnauthorized("登录已失效");
        List<Long> members = db.query("SELECT member_id FROM member_session WHERE token_hash=? AND expires_at>now()",
                (rs, i) -> rs.getLong(1), hash(token));
        if (members.isEmpty()) throw new MemberUnauthorized("登录已失效");
        return members.get(0);
    }

    long requireVip(HttpServletRequest request) {
        long memberId = requireLogin(request);
        if (!Boolean.TRUE.equals(status(memberId).get("active"))) throw new MemberRequired("会员已到期，请开通会员");
        return memberId;
    }

    long requireMiniProgram(HttpServletRequest request) {
        long memberId=requireLogin(request);
        String token=request.getHeader("Authorization").substring(7).trim();
        Integer count=db.queryForObject("SELECT count(*) FROM member_session WHERE token_hash=? AND member_id=? "
                +"AND device_type='MINIPROGRAM' AND expires_at>now()",Integer.class,hash(token),memberId);
        if (count==null || count==0) throw new MemberForbidden("请在微信小程序中生成动态码");
        return memberId;
    }

    @Transactional
    Map<String, Object> startTrial(long memberId) {
        db.update("""
                UPDATE member_account SET trial_started_at=now(), trial_expires_at=now()+interval '2 days', updated_at=now()
                WHERE id=? AND trial_started_at IS NULL
                """, memberId);
        return status(memberId);
    }

    Map<String, Object> status(long memberId) {
        return db.query("""
                SELECT a.id,a.member_no,a.trial_started_at,a.trial_expires_at,a.vip_expires_at,
                  (SELECT created_at+interval '30 days' FROM member_entitlement
                   WHERE member_id=a.id AND source='WELCOME' LIMIT 1) AS welcome_expires_at
                FROM member_account a WHERE a.id=?
                """,
                rs -> {
                    if (!rs.next()) throw new MemberUnauthorized("用户不存在");
                    Instant trial = instant(rs.getTimestamp("trial_expires_at"));
                    Instant paid = instant(rs.getTimestamp("vip_expires_at"));
                    Instant until = max(trial, paid);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", rs.getLong("id"));
                    result.put("memberNo", rs.getString("member_no"));
                    result.put("active", until != null && until.isAfter(Instant.now()));
                    result.put("trialStarted", rs.getTimestamp("trial_started_at") != null);
                    result.put("trialExpiresAt", trial);
                    result.put("vipExpiresAt", paid);
                    result.put("welcomeExpiresAt", instant(rs.getTimestamp("welcome_expires_at")));
                    result.put("expiresAt", until);
                    return result;
                }, memberId);
    }

    String openid(long memberId) {
        List<String> values = db.query("SELECT wechat_openid FROM member_account WHERE id=?",
                (rs, i) -> rs.getString(1), memberId);
        if (values.isEmpty()) throw new MemberUnauthorized("用户不存在");
        return values.get(0);
    }

    @Transactional
    String createAppCode(long memberId) {
        db.queryForObject("SELECT id FROM member_account WHERE id=? FOR UPDATE",Long.class,memberId);
        db.update("UPDATE member_app_code SET used_at=now() WHERE member_id=? AND used_at IS NULL", memberId);
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(11);
        for (int i = 0; i < bytes.length; i++) {
            if (i == 5) value.append('-');
            value.append(CODE_CHARS[Byte.toUnsignedInt(bytes[i]) % CODE_CHARS.length]);
        }
        String code = value.toString();
        db.update("INSERT INTO member_app_code(code_hash,member_id,expires_at) VALUES (?,?,?)",
                hash(code), memberId, Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        return code;
    }

    @Transactional
    Map<String, Object> redeemAppCode(String code, String deviceId, String deviceName) {
        if (code == null || !code.matches("[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}"))
            throw new MemberBadRequest("动态码格式无效");
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128 ||
                deviceName == null || deviceName.isBlank() || deviceName.length() > 128) throw new MemberBadRequest("设备信息无效");
        List<Long> ids = db.query("""
                UPDATE member_app_code SET used_at=now() WHERE code_hash=? AND used_at IS NULL AND expires_at>now()
                RETURNING member_id
                """, (rs, i) -> rs.getLong(1), hash(code));
        if (ids.isEmpty()) throw new MemberUnauthorized("动态码已失效或已使用");
        return issueSession(ids.get(0), "APP", deviceId);
    }

    @Transactional
    String createRouteRequest(long memberId) {
        String id = "r_" + randomToken().substring(0, 32);
        db.update("INSERT INTO member_route_request(request_id,member_id,expires_at) VALUES (?,?,?)",
                id, memberId, Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))));
        return id;
    }

    void requireRouteOwner(long memberId, String requestId) {
        if (requestId == null || requestId.length() > 64) throw new MemberForbidden("路线任务无效");
        Integer count = db.queryForObject("""
                SELECT count(*) FROM member_route_request WHERE request_id=? AND member_id=? AND expires_at>now()
                """, Integer.class, requestId, memberId);
        if (count == null || count == 0) throw new MemberForbidden("路线任务无效或已过期");
    }

    @Scheduled(cron="0 30 4 * * *", zone="Asia/Shanghai")
    void cleanExpiredCredentials() {
        db.update("DELETE FROM member_route_request WHERE expires_at<now()");
        db.update("DELETE FROM member_app_code WHERE expires_at<now()");
        db.update("DELETE FROM member_session WHERE expires_at<now()");
    }

    @Transactional
    void grantPayment(long orderId, String wxOrderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM member_order WHERE id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) throw new MemberBadRequest("订单不存在");
        Map<String, Object> order = rows.get(0);
        if ("REFUNDED".equals(order.get("status"))) return;
        if ("CLOSED".equals(order.get("status"))) throw new MemberBadRequest("已关闭订单收到支付通知");
        if ("PAID".equals(order.get("status"))) {
            String previous=(String)order.get("wx_order_id");
            if (previous!=null && wxOrderId!=null && !previous.equals(wxOrderId))
                throw new MemberBadRequest("微信平台订单编号冲突");
            if (previous==null && wxOrderId!=null)
                db.update("UPDATE member_order SET wx_order_id=?,updated_at=now() WHERE id=?",wxOrderId,orderId);
            return;
        }
        long memberId = ((Number) order.get("member_id")).longValue();
        db.queryForObject("SELECT id FROM member_account WHERE id=? FOR UPDATE",Long.class,memberId);
        int days = "MONTH".equals(order.get("plan_code")) ? 30 : 365;
        db.update("UPDATE member_order SET status='PAID',wx_order_id=?,paid_at=now(),updated_at=now() WHERE id=?",
                wxOrderId, orderId);
        db.update("INSERT INTO member_entitlement(member_id,source,order_id,days) VALUES (?,'PAYMENT',?,?) ON CONFLICT (order_id) DO NOTHING",
                memberId, orderId, days);
        recalculate(memberId);
    }

    @Transactional
    void refundPayment(long orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM member_order WHERE id=? FOR UPDATE", orderId);
        if (rows.isEmpty() || !"PAID".equals(rows.get(0).get("status"))) return;
        long memberId = ((Number) rows.get(0).get("member_id")).longValue();
        db.queryForObject("SELECT id FROM member_account WHERE id=? FOR UPDATE",Long.class,memberId);
        db.update("UPDATE member_order SET status='REFUNDED',refunded_at=now(),updated_at=now() WHERE id=?", orderId);
        db.update("UPDATE member_entitlement SET revoked_at=now() WHERE order_id=? AND revoked_at IS NULL", orderId);
        recalculate(memberId);
    }

    @Transactional
    Map<String, Object> adminChange(long memberId, String action, Integer days, Instant expiresAt,
                                    Long entitlementId, Long relatedOrderId, String reason, String actor, String ip, UUID requestId) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new MemberBadRequest("必须填写操作原因（最多 500 字）");
        List<Long> locked = db.query("SELECT id FROM member_account WHERE id=? FOR UPDATE", (rs,i) -> rs.getLong(1), memberId);
        if (locked.isEmpty()) throw new MemberBadRequest("会员不存在");
        if (relatedOrderId!=null) {
            Integer belongs=db.queryForObject("SELECT count(*) FROM member_order WHERE id=? AND member_id=?",
                    Integer.class,relatedOrderId,memberId);
            if (belongs==null || belongs==0) throw new MemberBadRequest("关联订单不属于该会员");
        }
        Map<String, Object> before = status(memberId);
        Map<String,Object> beforeFull = Map.of("member", before, "entitlements", entitlements(memberId, true));
        switch (action) {
            case "GRANT" -> {
                if (days == null || days < 1 || days > 3650) throw new MemberBadRequest("补发天数须为 1 至 3650");
                db.update("INSERT INTO member_entitlement(member_id,source,days,reason) VALUES (?,'ADMIN_ADD',?,?)",
                        memberId, days, reason.trim());
            }
            case "SET_EXPIRY" -> {
                if (expiresAt == null) throw new MemberBadRequest("到期时间不能为空");
                db.update("INSERT INTO member_entitlement(member_id,source,set_expires_at,reason) VALUES (?,'ADMIN_SET',?,?)",
                        memberId, Timestamp.from(expiresAt), reason.trim());
            }
            case "REVOKE" -> {
                if (entitlementId == null || db.update("""
                        UPDATE member_entitlement SET revoked_at=now() WHERE id=? AND member_id=?
                        AND source IN ('ADMIN_ADD','ADMIN_SET') AND revoked_at IS NULL
                        """, entitlementId, memberId) != 1) throw new MemberBadRequest("人工权益不存在或已撤销");
            }
            default -> throw new MemberBadRequest("未知操作");
        }
        recalculate(memberId);
        Map<String, Object> after = status(memberId);
        Map<String,Object> afterFull = Map.of("member", after, "entitlements", entitlements(memberId, true));
        db.update("""
                INSERT INTO member_admin_audit(member_id,order_id,actor,action,reason,outcome,before_value,after_value,request_id,source_ip)
                VALUES (?,?,?,?,?,'SUCCESS',?::jsonb,?::jsonb,?,?)
                """, memberId,relatedOrderId, actor, action, reason.trim(), toJson(beforeFull), toJson(afterFull), requestId, ip);
        return after;
    }

    List<Map<String,Object>> entitlements(long memberId, boolean includeRevoked) {
        return db.queryForList("SELECT id,source,order_id,days,set_expires_at,reason,created_at,revoked_at "
                + "FROM member_entitlement WHERE member_id=? " + (includeRevoked ? "" : "AND revoked_at IS NULL ")
                + "ORDER BY id DESC", memberId);
    }

    private void recalculate(long memberId) {
        Instant trial = db.queryForObject("SELECT trial_expires_at FROM member_account WHERE id=?",
                (rs, i) -> instant(rs.getTimestamp(1)), memberId);
        Instant cursor = trial;
        List<Map<String, Object>> items = db.queryForList("""
                SELECT source,days,set_expires_at,created_at FROM member_entitlement
                WHERE member_id=? AND revoked_at IS NULL ORDER BY created_at,id
                """, memberId);
        for (Map<String, Object> item : items) {
            if ("ADMIN_SET".equals(item.get("source"))) cursor = instant((Timestamp) item.get("set_expires_at"));
            else {
                Instant created = instant((Timestamp) item.get("created_at"));
                cursor = max(created, cursor).plus(Duration.ofDays(((Number) item.get("days")).longValue()));
            }
        }
        db.update("UPDATE member_account SET vip_expires_at=?,updated_at=now() WHERE id=?",
                items.isEmpty() || cursor == null ? null : Timestamp.from(cursor), memberId);
    }

    String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String randomMemberNo() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(bytes.length);
        for (byte item : bytes) value.append(CODE_CHARS[Byte.toUnsignedInt(item) % CODE_CHARS.length]);
        return value.toString();
    }

    static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    static Instant max(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}

class MemberUnauthorized extends RuntimeException {
    MemberUnauthorized(String message) { super(message); }
}
class MemberRequired extends RuntimeException {
    MemberRequired(String message) { super(message); }
}
class MemberForbidden extends RuntimeException {
    MemberForbidden(String message) { super(message); }
}
class MemberBadRequest extends RuntimeException {
    MemberBadRequest(String message) { super(message); }
}
