package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberPaymentCallbackTest {
    private static final String TOKEN="callback-test-token";
    private static final byte[] KEY=new byte[32];
    private final ObjectMapper json=new ObjectMapper();

    @Test
    void verifiesEncryptedDeliveryAndRejectsTamperedSignature() throws Exception {
        Arrays.fill(KEY,(byte)23);
        String aes=Base64.getEncoder().encodeToString(KEY).substring(0,43);
        var wechat=mock(WeChatMemberClient.class);
        when(wechat.appId()).thenReturn("wx-test-app");
        var payments=mock(MemberPaymentService.class);
        var callback=new MemberPaymentCallback(TOKEN,aes,wechat,payments,json);
        String event="{\"Event\":\"xpay_goods_deliver_notify\",\"OutTradeNo\":\"JLTEST123\","
                +"\"OpenId\":\"open-user\",\"WeChatPayInfo\":{\"MchOrderNo\":\"wx-order\"},"
                +"\"GoodsInfo\":{\"ProductId\":\"monthly\",\"Quantity\":1}}";
        String encrypted=encrypt(event,"wx-test-app");
        String signature=signature(TOKEN,"123456","nonce",encrypted);
        assertThat(callback.receive(signature,"123456","nonce",json.readTree("{\"Encrypt\":\""+encrypted+"\"}")))
                .isEqualTo("success");
        verify(payments).applyDelivery(event,"JLTEST123","open-user","monthly","wx-order");
        assertThatThrownBy(()->callback.receive("bad","123456","nonce",json.readTree("{\"Encrypt\":\""+encrypted+"\"}")))
                .isInstanceOf(MemberUnauthorized.class);
        assertThat(callback.verify(signature(TOKEN,"123456","nonce",null),"123456","nonce","echo"))
                .isEqualTo("echo");
    }

    @Test
    void iosRefundInquiryGetsEncryptedDecision() throws Exception {
        Arrays.fill(KEY,(byte)23);
        String aes=Base64.getEncoder().encodeToString(KEY).substring(0,43);
        var wechat=mock(WeChatMemberClient.class);
        when(wechat.appId()).thenReturn("wx-test-app");
        var payments=mock(MemberPaymentService.class);
        when(payments.knownPaidOrder("JLTEST123","open-user")).thenReturn(true);
        var callback=new MemberPaymentCallback(TOKEN,aes,wechat,payments,json);
        String message="{\"Event\":\"xpay_subscribe_ios_refund_query_notify\",\"OutTradeNo\":\"JLTEST123\",\"OpenId\":\"open-user\"}";
        String encrypted=encrypt(message,"wx-test-app");
        String reply=callback.receive(signature(TOKEN,"123456","nonce",encrypted),"123456","nonce",
                json.readTree("{\"Encrypt\":\""+encrypted+"\"}"));
        var wrapper=json.readTree(reply);
        assertThat(wrapper.path("MsgSignature").asText()).isEqualTo(signature(TOKEN,
                wrapper.path("TimeStamp").asText(),wrapper.path("Nonce").asText(),wrapper.path("Encrypt").asText()));
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(KEY,"AES"),new IvParameterSpec(KEY,0,16));
        byte[] plain=cipher.doFinal(Base64.getDecoder().decode(wrapper.path("Encrypt").asText()));
        int length=ByteBuffer.wrap(plain,16,4).getInt();
        var decision=json.readTree(plain,20,length);
        assertThat(decision.path("IosRefundQueryResponse").path("result_code").asInt()).isZero();
    }

    private static String encrypt(String message,String appId) throws Exception {
        byte[] body=message.getBytes(StandardCharsets.UTF_8);
        byte[] app=appId.getBytes(StandardCharsets.UTF_8);
        int len=20+body.length+app.length;
        int padding=32-len%32;
        byte[] plain=new byte[len+padding];
        ByteBuffer.wrap(plain,16,4).putInt(body.length);
        System.arraycopy(body,0,plain,20,body.length);
        System.arraycopy(app,0,plain,20+body.length,app.length);
        Arrays.fill(plain,len,plain.length,(byte)padding);
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(KEY,"AES"),new IvParameterSpec(KEY,0,16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain));
    }

    private static String signature(String token,String time,String nonce,String encrypted) throws Exception {
        String[] values=encrypted==null?new String[]{token,time,nonce}:new String[]{token,time,nonce,encrypted};
        Arrays.sort(values);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(String.join("",values).getBytes(StandardCharsets.UTF_8)));
    }
}
