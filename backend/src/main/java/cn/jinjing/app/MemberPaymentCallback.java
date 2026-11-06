package cn.jinjing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/members/payment-notify")
class MemberPaymentCallback {
    private final String token, aesKey;
    private final WeChatMemberClient wechat;
    private final MemberPaymentService payments;
    private final ObjectMapper json;

    MemberPaymentCallback(@Value("${app.virtual-pay-message-token:}") String token,
            @Value("${app.virtual-pay-message-aes-key:}") String aesKey,
            WeChatMemberClient wechat, MemberPaymentService payments, ObjectMapper json) {
        this.token=token; this.aesKey=aesKey; this.wechat=wechat; this.payments=payments; this.json=json;
    }

    @GetMapping
    String verify(@RequestParam String signature, @RequestParam String timestamp,
            @RequestParam String nonce, @RequestParam String echostr) {
        if (!checkSignature(signature, timestamp, nonce, null)) throw new MemberUnauthorized("消息签名无效");
        return echostr;
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.TEXT_PLAIN_VALUE)
    @Transactional
    String receive(@RequestParam("msg_signature") String signature,
            @RequestParam String timestamp, @RequestParam String nonce, @RequestBody JsonNode body) {
        String encrypted = body.path("Encrypt").asText();
        if (!checkSignature(signature, timestamp, nonce, encrypted)) throw new MemberUnauthorized("消息签名无效");
        JsonNode event = decrypt(encrypted);
        String type = event.path("Event").asText();
        String orderNo = event.path("OutTradeNo").asText();
        String wxOrderNo = event.path("WeChatPayInfo").path("MchOrderNo").asText(null);
        if (wxOrderNo!=null && wxOrderNo.isBlank()) wxOrderNo=null;
        String openid = event.path("OpenId").asText();
        if ("xpay_subscribe_ios_refund_query_notify".equals(type)) {
            boolean known=payments.knownPaidOrder(orderNo,openid);
            return encryptedResponse(Map.of("ErrCode",0,"ErrMsg","success","IosRefundQueryResponse",
                    Map.of("result_code",known?0:1,"evidence",known?"已查到会员订单，退款后将回收剩余权益":"未查到已交付的会员订单",
                            "result_info",known?"建议退款":"订单未交付")));
        }
        if (!("xpay_goods_deliver_notify".equals(type) || "xpay_refund_notify".equals(type))) {
            payments.recordEvent(event.toString(),type.isBlank()?"UNKNOWN":type,
                    orderNo.isBlank()?null:orderNo,wxOrderNo,"IGNORED");
            return "success";
        }
        if (orderNo.isBlank()) {
            payments.recordEvent(event.toString(),type,null,wxOrderNo,"MISSING_ORDER");
            return "success";
        }
        if ("xpay_goods_deliver_notify".equals(type)) {
            String product=event.path("GoodsInfo").path("ProductId").asText();
            if (product.isBlank()) {
                payments.recordEvent(event.toString(),type,orderNo,wxOrderNo,"MISSING_PRODUCT");
                return "success";
            }
            if (event.path("GoodsInfo").path("Quantity").asInt(-1)!=1)
                throw new MemberBadRequest("支付商品数量无效");
        }
        if (openid.isBlank()) throw new MemberBadRequest("支付用户缺失");
        if ("xpay_goods_deliver_notify".equals(type)) {
            payments.applyDelivery(event.toString(),orderNo,openid,
                    event.path("GoodsInfo").path("ProductId").asText(),wxOrderNo);
            return "success";
        }
        if ("xpay_refund_notify".equals(type) && event.path("RetCode").asInt(-1) != 0) {
            payments.recordEvent(event.toString(),type,orderNo,wxOrderNo,"REFUND_FAILED");
            return "success";
        }
        // Refund notifications need an authoritative order check before revoking benefits.
        int status=payments.verifyOrder(orderNo,null,openid,wxOrderNo);
        if ("xpay_refund_notify".equals(type) && status!=5 && status!=8)
            throw new MemberUnavailable("退款订单尚未确认");
        payments.recordEvent(event.toString(), type, orderNo, wxOrderNo, "VERIFIED");
        return "success";
    }

    private boolean checkSignature(String signature, String timestamp, String nonce, String encrypted) {
        if (token.isBlank() || aesKey.isBlank() || timestamp.length()>20 || nonce.length()>128) return false;
        String[] parts = encrypted == null ? new String[]{token,timestamp,nonce} : new String[]{token,timestamp,nonce,encrypted};
        Arrays.sort(parts);
        try {
            var sha = MessageDigest.getInstance("SHA-1");
            String actual = HexFormat.of().formatHex(sha.digest(String.join("",parts).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private JsonNode decrypt(String encrypted) {
        try {
            byte[] key=Base64.getDecoder().decode(aesKey + "=");
            if (key.length != 32) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(key,0,16));
            byte[] plaintext=cipher.doFinal(Base64.getDecoder().decode(encrypted));
            int pad=Byte.toUnsignedInt(plaintext[plaintext.length-1]);
            if (pad<1 || pad>32) throw new IllegalArgumentException();
            for (int i=plaintext.length-pad;i<plaintext.length;i++) if (plaintext[i]!=pad) throw new IllegalArgumentException();
            int end=plaintext.length-pad;
            int length=ByteBuffer.wrap(plaintext,16,4).getInt();
            if (length<1 || 20+length>end) throw new IllegalArgumentException();
            String appId=new String(plaintext,20+length,end-20-length,StandardCharsets.UTF_8);
            if (!appId.equals(wechat.appId())) throw new IllegalArgumentException();
            return json.readTree(plaintext,20,length);
        } catch (Exception e) { throw new MemberUnauthorized("支付消息解密失败"); }
    }

    private String encryptedResponse(Object value) {
        try {
            byte[] key=Base64.getDecoder().decode(aesKey+"=");
            byte[] body=json.writeValueAsBytes(value);
            byte[] app=wechat.appId().getBytes(StandardCharsets.UTF_8);
            byte[] random=new byte[16];new SecureRandom().nextBytes(random);
            int length=20+body.length+app.length;
            int padding=32-length%32;
            byte[] plaintext=new byte[length+padding];
            System.arraycopy(random,0,plaintext,0,16);
            ByteBuffer.wrap(plaintext,16,4).putInt(body.length);
            System.arraycopy(body,0,plaintext,20,body.length);
            System.arraycopy(app,0,plaintext,20+body.length,app.length);
            Arrays.fill(plaintext,length,plaintext.length,(byte)padding);
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(key,0,16));
            String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
            String timestamp=String.valueOf(System.currentTimeMillis()/1000);
            String nonce=HexFormat.of().formatHex(random).substring(0,16);
            String[] parts={token,timestamp,nonce,encrypted};Arrays.sort(parts);
            String signature=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(String.join("",parts).getBytes(StandardCharsets.UTF_8)));
            return json.writeValueAsString(Map.of("Encrypt",encrypted,"MsgSignature",signature,
                    "TimeStamp",timestamp,"Nonce",nonce));
        } catch (Exception e) { throw new MemberUnavailable("支付回包加密失败"); }
    }
}
