package cn.jinjing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
class MemberPaymentService {
    private static final Logger LOG=LoggerFactory.getLogger(MemberPaymentService.class);
    private final JdbcTemplate db;
    private final MemberService members;
    private final WeChatMemberClient wechat;
    private final ObjectMapper json;
    private final String offerId, appKey, monthProduct, yearProduct, messageToken, messageAesKey;

    MemberPaymentService(JdbcTemplate db, MemberService members, WeChatMemberClient wechat, ObjectMapper json,
            @Value("${app.virtual-pay-offer-id:}") String offerId,
            @Value("${app.virtual-pay-app-key:}") String appKey,
            @Value("${app.virtual-pay-month-product-id:}") String monthProduct,
            @Value("${app.virtual-pay-year-product-id:}") String yearProduct,
            @Value("${app.virtual-pay-message-token:}") String messageToken,
            @Value("${app.virtual-pay-message-aes-key:}") String messageAesKey) {
        this.db = db; this.members = members; this.wechat = wechat; this.json = json;
        this.offerId = offerId; this.appKey = appKey;
        this.monthProduct = monthProduct; this.yearProduct = yearProduct;
        this.messageToken=messageToken; this.messageAesKey=messageAesKey;
    }

    boolean available() { return wechat.identityConfigured() && !offerId.isBlank() && !appKey.isBlank()
            && !monthProduct.isBlank() && !yearProduct.isBlank()
            && !messageToken.isBlank() && messageAesKey.length()==43; }

    @Transactional
    Map<String,Object> createOrder(long memberId, String plan, String sessionKey) {
        if (!available()) throw new MemberUnavailable("暂不可购买：微信虚拟支付尚未配置");
        if (!"MONTH".equals(plan) && !"YEAR".equals(plan)) throw new MemberBadRequest("套餐无效");
        String product = "MONTH".equals(plan) ? monthProduct : yearProduct;
        int price = "MONTH".equals(plan) ? 1000 : 10800;
        String outTradeNo = "JL" + MemberService.randomToken().substring(0, 28).toUpperCase();
        db.update("INSERT INTO member_order(out_trade_no,member_id,plan_code,product_id,amount_fen) VALUES (?,?,?,?,?)",
                outTradeNo, memberId, plan, product, price);
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("offerId", offerId); data.put("buyQuantity", 1); data.put("env", 0);
        data.put("currencyType", "CNY"); data.put("productId", product);
        data.put("goodsPrice", price); data.put("outTradeNo", outTradeNo);
        data.put("attach", "member:" + memberId);
        String signData = members.toJson(data);
        return Map.of("orderNo", outTradeNo, "plan", plan, "amountFen", price,
                "payParams", Map.of("signData", signData, "paySig", hmac(appKey, "requestVirtualPayment&" + signData),
                        "signature", hmac(sessionKey, signData), "mode", "short_series_goods"));
    }

    List<Map<String,Object>> orders(long memberId) {
        return db.queryForList("SELECT id,out_trade_no,plan_code,amount_fen,status,created_at,paid_at,refunded_at FROM member_order WHERE member_id=? ORDER BY id DESC LIMIT 50", memberId);
    }

    boolean knownPaidOrder(String outTradeNo, String openid) {
        Integer count=db.queryForObject("SELECT count(*) FROM member_order o JOIN member_account a ON a.id=o.member_id "
                +"WHERE o.out_trade_no=? AND a.wechat_openid=? AND o.status='PAID'",Integer.class,outTradeNo,openid);
        return count!=null && count>0;
    }

    @Transactional
    void applyDelivery(String event, String outTradeNo, String openid, String productId, String wxOrderNo) {
        List<Map<String,Object>> rows=db.queryForList("SELECT o.id,o.product_id,a.wechat_openid FROM member_order o "
                +"JOIN member_account a ON a.id=o.member_id WHERE o.out_trade_no=? FOR UPDATE OF o",outTradeNo);
        if (rows.isEmpty() || !openid.equals(rows.get(0).get("wechat_openid"))
                || !productId.equals(rows.get(0).get("product_id"))) throw new MemberBadRequest("支付消息与订单不一致");
        members.grantPayment(((Number)rows.get(0).get("id")).longValue(),wxOrderNo);
        String state=db.queryForObject("SELECT status FROM member_order WHERE id=?",String.class,rows.get(0).get("id"));
        recordEvent(event,"xpay_goods_deliver_notify",outTradeNo,wxOrderNo,
                "PAID".equals(state)?"DELIVERED":"IGNORED_REFUNDED");
    }

    /** Querying is the fallback when a verified server-side payment push is delayed or lost. */
    int verifyOrder(String outTradeNo) { return verifyOrder(outTradeNo, null, null, null); }

    int verifyOrder(String outTradeNo, String expectedProduct, String expectedOpenid, String expectedWxOrder) {
        if (!available()) throw new MemberUnavailable("微信虚拟支付尚未配置");
        List<Map<String,Object>> rows = db.queryForList("SELECT o.*,a.wechat_openid FROM member_order o JOIN member_account a ON a.id=o.member_id WHERE o.out_trade_no=?", outTradeNo);
        if (rows.isEmpty()) throw new MemberBadRequest("订单不存在");
        Map<String,Object> order = rows.get(0);
        if (expectedProduct != null && !expectedProduct.equals(order.get("product_id")))
            throw new MemberBadRequest("支付商品与订单不一致");
        if (expectedOpenid != null && !expectedOpenid.equals(order.get("wechat_openid")))
            throw new MemberBadRequest("支付用户与订单不一致");
        String body = members.toJson(Map.of("openid", order.get("wechat_openid"), "env", 0, "order_id", outTradeNo));
        String signature = hmac(appKey, "/xpay/query_order&" + body);
        JsonNode reply = wechat.post("https://api.weixin.qq.com/xpay/query_order?access_token=" + wechat.accessToken()
                + "&pay_sig=" + signature, body);
        if (reply.path("errcode").asInt(0) != 0 || reply.path("ret").asInt(0) != 0)
            throw new MemberUnavailable("微信订单查询失败");
        JsonNode info = reply.path("order");
        if (info.isMissingNode()) throw new MemberUnavailable("微信订单查询结果不完整");
        String returnedNo = info.path("order_id").asText();
        if (!outTradeNo.equals(returnedNo)) throw new MemberUnavailable("微信订单编号不一致");
        String returnedWxOrder = info.path("wx_order_id").asText(null);
        if (returnedWxOrder!=null && returnedWxOrder.isBlank()) returnedWxOrder=null;
        if (expectedWxOrder != null && returnedWxOrder != null && !expectedWxOrder.equals(returnedWxOrder))
            throw new MemberUnavailable("微信平台订单编号不一致");
        int status = info.path("status").asInt(-1);
        if (status < 0) throw new MemberUnavailable("微信订单状态缺失");
        if (status == 2 || status == 4) members.grantPayment(((Number) order.get("id")).longValue(),
                returnedWxOrder);
        else if (status == 5 || status == 8) members.refundPayment(((Number) order.get("id")).longValue());
        else if (status == 6) db.update("UPDATE member_order SET status='CLOSED',updated_at=now() WHERE id=? AND status='PENDING'",order.get("id"));
        db.update("UPDATE member_order SET last_verified_at=now() WHERE id=?",order.get("id"));
        return status;
    }

    @Transactional
    void recordEvent(String payload, String type, String orderNo, String wxOrderNo, String result) {
        db.update("INSERT INTO member_payment_event(event_hash,event_type,out_trade_no,wx_order_id,result) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                MemberService.hash(payload), type, orderNo, wxOrderNo, result);
    }

    @Scheduled(fixedDelayString = "300000", initialDelayString = "120000")
    void reconcile() {
        if (!available()) return;
        List<String> pending = db.query("SELECT out_trade_no FROM member_order WHERE "
                        +"(status='PENDING' AND created_at>now()-interval '7 days' "
                        +"AND (last_verified_at IS NULL OR last_verified_at<now()-interval '5 minutes')) "
                        +"OR (status='PAID' AND paid_at>now()-interval '370 days' "
                        +"AND (last_verified_at IS NULL OR last_verified_at<now()-interval '1 day')) "
                        +"ORDER BY last_verified_at NULLS FIRST,id LIMIT 100",
                (rs,i) -> rs.getString(1));
        for (String order : pending) {
            try { verifyOrder(order); }
            catch (RuntimeException failure) { LOG.warn("会员订单查单待重试：{}，错误类型：{}",order,failure.getClass().getSimpleName()); }
        }
    }

    static String hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
