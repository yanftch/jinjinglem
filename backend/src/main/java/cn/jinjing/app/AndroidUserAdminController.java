package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
class AndroidUserFailedAudit {
    private final JdbcTemplate db;
    private final AndroidUserService users;
    AndroidUserFailedAudit(JdbcTemplate db, AndroidUserService users) { this.db=db; this.users=users; }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void record(long id, String plan, String reason, String actor, String ip, UUID requestId, String error) {
        List<Long> existing = db.query("SELECT id FROM android_user WHERE id=?", (rs,i)->rs.getLong(1), id);
        Long validId = existing.isEmpty() ? null : id;
        String snapshot = validId == null ? null : users.snapshot(
                db.queryForObject("SELECT member_expires_at FROM android_user WHERE id=?",
                        (rs,i) -> MemberService.instant(rs.getTimestamp(1)), id));
        db.update("""
                INSERT INTO android_user_admin_audit(user_id,actor,action,plan_code,reason,outcome,
                  before_value,after_value,error_summary,request_id,source_ip)
                VALUES (?,?,'SET_MEMBERSHIP',?,?,'FAILED',?::jsonb,?::jsonb,?,?,?)
                """, validId, actor, plan, reason, snapshot, snapshot,
                error == null ? "操作失败" : error.substring(0, Math.min(300, error.length())), requestId, ip);
    }
}

@RestController
@RequestMapping("/api/v1/admin/android-users")
class AndroidUserAdminController {
    private final JdbcTemplate db;
    private final AndroidUserService users;
    private final AndroidUserFailedAudit failed;
    private final ObjectMapper json;

    AndroidUserAdminController(JdbcTemplate db, AndroidUserService users, AndroidUserFailedAudit failed, ObjectMapper json) {
        this.db=db; this.users=users; this.failed=failed; this.json=json;
    }

    record ChangeRequest(String plan, Instant expiresAt, String reason) {}

    @GetMapping
    Map<String,Object> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String query) {
        int offset=pageOffset(page,size);
        String like="%"+(query==null?"":query.trim())+"%";
        String where=" WHERE CAST(id AS TEXT) LIKE ? OR device_id LIKE ? OR manufacturer ILIKE ? OR brand ILIKE ? OR model ILIKE ?";
        long total=db.queryForObject("SELECT count(*) FROM android_user"+where, Long.class, like,like,like,like,like);
        var rows=db.queryForList("""
                SELECT id,device_id,manufacturer,brand,model,device_name,android_version,sdk_int,
                       app_version,member_expires_at,COALESCE(member_expires_at>now(),false) AS active,
                       created_at,last_seen_at,updated_at
                FROM android_user
                """+where+"""
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, like,like,like,like,like,size,offset);
        var beijing = ZoneId.of("Asia/Shanghai");
        var todayStart = Timestamp.from(LocalDate.now(beijing).atStartOfDay(beijing).toInstant());
        long allUsers = db.queryForObject("SELECT count(*) FROM android_user", Long.class);
        long newToday = db.queryForObject("SELECT count(*) FROM android_user WHERE created_at>=?", Long.class, todayStart);
        long activeVip = db.queryForObject("SELECT count(*) FROM android_user WHERE member_expires_at>now()", Long.class);
        return Map.of("total",total,"items",rows,
                "summary",Map.of("allUsers",allUsers,"newToday",newToday,"activeVip",activeVip));
    }

    @GetMapping("/{id}")
    Map<String,Object> detail(@PathVariable long id) { return Map.of("user", users.status(id)); }

    @PostMapping("/{id}/membership")
    Map<String,Object> change(@PathVariable long id, @RequestBody(required=false) String rawBody,
            Authentication authentication, HttpServletRequest request) {
        UUID requestId=UUID.randomUUID();
        String actor=authentication==null?"local-dev":authentication.getName();
        String ip=request.getRemoteAddr();
        ChangeRequest change=null;
        try {
            if (rawBody==null || rawBody.length()>4096) throw new MemberBadRequest("操作内容无效");
            try { change=json.readValue(rawBody,ChangeRequest.class); }
            catch (Exception e) { throw new MemberBadRequest("操作内容格式无效"); }
            return Map.of("requestId",requestId,"user",users.adminChange(id,change.plan(),change.expiresAt(),change.reason(),actor,ip,requestId));
        } catch (RuntimeException e) {
            failed.record(id,change==null?null:change.plan(),change==null?null:change.reason(),actor,ip,requestId,e.getMessage());
            throw e;
        }
    }

    @GetMapping("/history")
    Map<String,Object> history(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String outcome,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        int offset=pageOffset(page,size);
        if (outcome!=null && !List.of("SUCCESS","FAILED").contains(outcome)) throw new MemberBadRequest("结果无效");
        String where=" WHERE (CAST(? AS BIGINT) IS NULL OR user_id=?) AND (CAST(? AS TEXT) IS NULL OR outcome=?) "
                +"AND (CAST(? AS TIMESTAMPTZ) IS NULL OR created_at>=?) AND (CAST(? AS TIMESTAMPTZ) IS NULL OR created_at<?)";
        Object[] args={userId,userId,outcome,outcome,
                from==null?null:Timestamp.from(from),from==null?null:Timestamp.from(from),
                to==null?null:Timestamp.from(to),to==null?null:Timestamp.from(to)};
        long total=db.queryForObject("SELECT count(*) FROM android_user_admin_audit"+where,Long.class,args);
        Object[] paged=Arrays.copyOf(args,args.length+2); paged[args.length]=size; paged[args.length+1]=offset;
        var rows=db.queryForList("""
                SELECT id,user_id,actor,action,plan_code,reason,outcome,before_value::text AS before_value,
                       after_value::text AS after_value,error_summary,request_id,source_ip,created_at
                FROM android_user_admin_audit
                """+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",paged);
        return Map.of("total",total,"items",rows);
    }

    private static int pageOffset(int page,int size) {
        if (page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE) throw new MemberBadRequest("分页参数无效");
        return page*size;
    }
}
