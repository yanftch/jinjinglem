package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
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
class MemberFailedAudit {
    private final JdbcTemplate db;
    private final MemberService members;
    MemberFailedAudit(JdbcTemplate db, MemberService members) { this.db=db; this.members=members; }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void record(Long memberId, Long orderId, String actor, String action, String reason, String error, UUID requestId, String ip) {
        Long validId = memberId == null ? null : db.query("SELECT id FROM member_account WHERE id=?", (rs,i)->rs.getLong(1), memberId)
                .stream().findFirst().orElse(null);
        Long validOrder=validId==null || orderId==null?null:db.query("SELECT id FROM member_order WHERE id=? AND member_id=?",
                (rs,i)->rs.getLong(1),orderId,validId).stream().findFirst().orElse(null);
        String snapshot=validId==null?null:members.toJson(Map.of("member",members.status(validId),
                "entitlements",members.entitlements(validId,true)));
        db.update("INSERT INTO member_admin_audit(member_id,order_id,actor,action,reason,outcome,before_value,after_value,error_summary,request_id,source_ip) "
                + "VALUES (?,?,?,?,?,'FAILED',?::jsonb,?::jsonb,?,?,?)", validId, validOrder, actor, action, reason,
                snapshot,snapshot,
                error == null ? "操作失败" : error.substring(0,Math.min(300,error.length())), requestId, ip);
    }
}

@RestController
@RequestMapping("/api/v1/admin/members")
class MemberAdminController {
    private final JdbcTemplate db;
    private final MemberService members;
    private final MemberFailedAudit failedAudit;
    private final ObjectMapper json;

    MemberAdminController(JdbcTemplate db, MemberService members, MemberFailedAudit failedAudit, ObjectMapper json) {
        this.db=db; this.members=members; this.failedAudit=failedAudit; this.json=json;
    }

    record ChangeRequest(String action, Integer days, Instant expiresAt, Long entitlementId, Long orderId, String reason) {}

    @GetMapping
    Map<String,Object> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String query) {
        int offset=pageOffset(page,size);
        String like="%"+(query==null?"":query.trim())+"%";
        long total=db.queryForObject("SELECT count(*) FROM member_account WHERE CAST(id AS TEXT) LIKE ? OR member_no LIKE ? OR wechat_openid LIKE ?", Long.class,like,like,like);
        var rows=db.queryForList("SELECT id,member_no,wechat_openid,trial_started_at,trial_expires_at,vip_expires_at,created_at "
                + "FROM member_account WHERE CAST(id AS TEXT) LIKE ? OR member_no LIKE ? OR wechat_openid LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",
                like,like,like,size,offset);
        return Map.of("total",total,"items",rows);
    }

    @GetMapping("/{id}")
    Map<String,Object> detail(@PathVariable long id) {
        return Map.of("member",members.status(id),"openid",members.openid(id),
                "entitlements",members.entitlements(id,true),
                "orders",db.queryForList("SELECT id,out_trade_no,plan_code,amount_fen,status,created_at,paid_at,refunded_at FROM member_order WHERE member_id=? ORDER BY id DESC LIMIT 100",id));
    }

    @GetMapping("/orders")
    Map<String,Object> orders(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) Long memberId,@RequestParam(required=false) String orderNo) {
        int offset=pageOffset(page,size);
        String filter=" WHERE (CAST(? AS BIGINT) IS NULL OR member_id=?) AND (CAST(? AS TEXT) IS NULL OR out_trade_no=?)";
        long total=db.queryForObject("SELECT count(*) FROM member_order"+filter,Long.class,memberId,memberId,orderNo,orderNo);
        var rows=db.queryForList("SELECT id,out_trade_no,member_id,plan_code,amount_fen,status,created_at,paid_at,refunded_at FROM member_order"
                +filter+" ORDER BY id DESC LIMIT ? OFFSET ?",memberId,memberId,orderNo,orderNo,size,offset);
        return Map.of("total",total,"items",rows);
    }

    @PostMapping("/{id}/change")
    Map<String,Object> change(@PathVariable long id,@RequestBody(required=false) String rawBody,
            Authentication authentication,HttpServletRequest request) {
        UUID requestId=UUID.randomUUID();
        String actor=authentication==null?"local-dev":authentication.getName();
        String ip=request.getRemoteAddr();
        ChangeRequest change=null;
        try {
            if (rawBody==null || rawBody.length()>4096) throw new MemberBadRequest("操作内容无效");
            try { change=json.readValue(rawBody,ChangeRequest.class); }
            catch (Exception e) { throw new MemberBadRequest("操作内容格式无效"); }
            return Map.of("requestId",requestId,"member",members.adminChange(id,change.action(),change.days(),
                    change.expiresAt(),change.entitlementId(),change.orderId(),change.reason(),actor,ip,requestId));
        } catch (RuntimeException e) {
            failedAudit.record(id,change==null?null:change.orderId(),actor,change==null || change.action()==null?"UNKNOWN":change.action(),
                    change==null?null:change.reason(),e.getMessage(),requestId,ip);
            throw e;
        }
    }

    @GetMapping("/history")
    Map<String,Object> history(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) Long memberId,@RequestParam(required=false) String memberNo,
            @RequestParam(required=false) Long orderId,
            @RequestParam(required=false) String action,@RequestParam(required=false) String outcome,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        int offset=pageOffset(page,size);
        if (action!=null && !List.of("GRANT","SET_EXPIRY","REVOKE","UNKNOWN").contains(action)) throw new MemberBadRequest("操作类型无效");
        if (outcome!=null && !List.of("SUCCESS","FAILED").contains(outcome)) throw new MemberBadRequest("结果无效");
        String fromTables=" FROM member_admin_audit h LEFT JOIN member_account a ON a.id=h.member_id";
        String where=" WHERE (CAST(? AS BIGINT) IS NULL OR h.member_id=?) AND (CAST(? AS TEXT) IS NULL OR a.member_no=?) "
                +"AND (CAST(? AS BIGINT) IS NULL OR h.order_id=?) AND (CAST(? AS TEXT) IS NULL OR h.action=?) "
                +"AND (CAST(? AS TEXT) IS NULL OR h.outcome=?) AND (CAST(? AS TIMESTAMPTZ) IS NULL OR h.created_at>=?) "
                +"AND (CAST(? AS TIMESTAMPTZ) IS NULL OR h.created_at<?)";
        Object[] args={memberId,memberId,memberNo,memberNo,orderId,orderId,action,action,outcome,outcome,
                from==null?null:Timestamp.from(from),from==null?null:Timestamp.from(from),
                to==null?null:Timestamp.from(to),to==null?null:Timestamp.from(to)};
        long total=db.queryForObject("SELECT count(*)"+fromTables+where,Long.class,args);
        Object[] paged=java.util.Arrays.copyOf(args,args.length+2);
        paged[args.length]=size; paged[args.length+1]=offset;
        var rows=db.queryForList("SELECT h.id,h.member_id,a.member_no,h.order_id,h.actor,h.action,h.reason,h.outcome,"
                +"h.before_value::text AS before_value,h.after_value::text AS after_value,h.error_summary,"
                +"h.request_id,h.source_ip,h.created_at"+fromTables+where+" ORDER BY h.created_at DESC,h.id DESC LIMIT ? OFFSET ?",paged);
        return Map.of("total",total,"items",rows);
    }

    private static int pageOffset(int page,int size) {
        if (page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE) throw new MemberBadRequest("分页参数无效");
        return page*size;
    }
}
