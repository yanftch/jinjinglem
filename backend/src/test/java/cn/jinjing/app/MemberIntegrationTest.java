package cn.jinjing.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@EnabledIfEnvironmentVariable(named="REVIEW_TEST_DB_URL",matches=".+")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={"app.auth-enabled=false","app.virtual-pay-offer-id=test-offer",
        "app.virtual-pay-app-key=test-app-key","app.virtual-pay-month-product-id=month-product",
        "app.virtual-pay-year-product-id=year-product","app.virtual-pay-message-token=test-token",
        "app.virtual-pay-message-aes-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
class MemberIntegrationTest {
    @Autowired MemberService members;
    @Autowired MemberPaymentService payments;
    @Autowired MemberAdminController admin;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean WeChatMemberClient wechat;

    private Map<String,Object> legacyLogin(String openid) {
        db.update("INSERT INTO member_account(wechat_openid) VALUES (?)",openid);
        return members.login(openid);
    }

    @Test
    void firstLoginGivesConfiguredNinetyNineYearsOnceAndDoesNotLeaveAnExtraTrial() {
        String openid="test-welcome-"+UUID.randomUUID();
        var first=members.login(openid);
        long id=((Number)((Map<?,?>)first.get("member")).get("id")).longValue();
        try {
            Instant welcome=(Instant)members.status(id).get("welcomeExpiresAt");
            assertThat(welcome).isBetween(Instant.now().plus(Duration.ofDays(98L * 365)),
                    Instant.now().plus(Duration.ofDays(100L * 365)));
            assertThat(members.status(id).get("vipExpiresAt")).isEqualTo(welcome);
            assertThat(members.status(id).get("active")).isEqualTo(true);
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE member_id=? AND source='WELCOME'",Long.class,id)).isEqualTo(1);
            String memberNo=(String)((Map<?,?>)first.get("member")).get("memberNo");
            assertThat(memberNo).hasSize(16).matches("[2-9A-HJ-NP-Z]{16}");
            members.login(openid);
            assertThat(db.queryForObject("SELECT member_no FROM member_account WHERE id=?",String.class,id)).isEqualTo(memberNo);
            members.startTrial(id);
            assertThat(members.status(id).get("vipExpiresAt")).isEqualTo(welcome);
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE member_id=? AND source='WELCOME'",Long.class,id)).isEqualTo(1);
            String orderNo="JL"+MemberService.randomToken().substring(0,25);
            db.update("INSERT INTO member_order(out_trade_no,member_id,plan_code,product_id,amount_fen) VALUES (?,?,?,?,?)",
                    orderNo,id,"MONTH","month-product",1000);
            long orderId=db.queryForObject("SELECT id FROM member_order WHERE out_trade_no=?",Long.class,orderNo);
            members.grantPayment(orderId,"wx-test-"+UUID.randomUUID());
            assertThat((Instant)members.status(id).get("vipExpiresAt"))
                    .isBetween(welcome.plus(Duration.ofDays(29)),welcome.plus(Duration.ofDays(31)));
        } finally {
            db.update("DELETE FROM member_session WHERE member_id=?",id);
            db.update("DELETE FROM member_entitlement WHERE member_id=?",id);
            db.update("DELETE FROM member_order WHERE member_id=?",id);
            db.update("DELETE FROM member_account WHERE id=?",id);
        }
    }

    @Test
    void adminCanChangeFirstRegistrationGiftAndAuditTheChange() throws Exception {
        String before = db.queryForObject("SELECT duration FROM system_membership_gift_config WHERE id=1", String.class);
        try {
            mvc.perform(post("/api/v1/admin/system-config")
                            .contentType("application/json").content("{\"giftDuration\":\"30_DAYS\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.giftDuration").value("30_DAYS"));
            assertThat(db.queryForObject("SELECT duration_before FROM system_membership_gift_config_audit ORDER BY id DESC LIMIT 1", String.class))
                    .isEqualTo(before);
            assertThat(db.queryForObject("SELECT duration_after FROM system_membership_gift_config_audit ORDER BY id DESC LIMIT 1", String.class))
                    .isEqualTo("30_DAYS");
        } finally {
            db.update("UPDATE system_membership_gift_config SET duration=? WHERE id=1", before);
        }
    }

    @Test
    void existingMemberStillStartsOriginalTwoDayTrialWithoutWelcomeGrant() {
        String openid="test-existing-"+UUID.randomUUID();
        var login=legacyLogin(openid);
        long id=((Number)((Map<?,?>)login.get("member")).get("id")).longValue();
        try {
            assertThat(members.status(id).get("active")).isEqualTo(false);
            assertThat(members.status(id).get("welcomeExpiresAt")).isNull();
            Instant trial=(Instant)members.startTrial(id).get("trialExpiresAt");
            assertThat(trial).isBetween(Instant.now().plus(Duration.ofHours(47)),
                    Instant.now().plus(Duration.ofHours(49)));
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE member_id=?",Long.class,id)).isZero();
        } finally {
            db.update("DELETE FROM member_session WHERE member_id=?",id);
            db.update("DELETE FROM member_account WHERE id=?",id);
        }
    }

    @Test
    void adminWriteHttpAcceptsJsonAndCreatesAudit() throws Exception {
        String openid="test-admin-http-"+UUID.randomUUID();
        long id=((Number)((Map<?,?>)members.login(openid).get("member")).get("id")).longValue();
        mvc.perform(post("/api/v1/admin/members/"+id+"/change")
                        .contentType("application/json")
                        .content("{\"action\":\"GRANT\",\"days\":2,\"reason\":\"HTTP 测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.active").value(true));
        assertThat(db.queryForObject("SELECT count(*) FROM member_admin_audit WHERE member_id=? AND outcome='SUCCESS'",Long.class,id))
                .isEqualTo(1);
    }

    @Test
    void wechatLoginAndTrialAreServedOverMemberApi() throws Exception {
        String openid="test-http-"+UUID.randomUUID();
        given(wechat.code2session("fresh-code")).willReturn(new WeChatMemberClient.CodeSession(openid,"session-key"));
        var login=mvc.perform(post("/api/v1/members/wechat-login")
                .contentType("application/json").content("{\"code\":\"fresh-code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.member.active").value(true))
                .andExpect(jsonPath("$.member.welcomeExpiresAt").exists()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).path("token").asText();
        long id=db.queryForObject("SELECT id FROM member_account WHERE wechat_openid=?",Long.class,openid);
        try {
            mvc.perform(post("/api/v1/routes/amap/plan").contentType("application/json")
                    .content("{\"startPoint\":{\"latitude\":39.9,\"longitude\":116.4},\"endPoint\":{\"latitude\":40,\"longitude\":116.5}}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/members/trial").header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
            mvc.perform(get("/api/v1/members/me").header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.member.trialStarted").value(true));
        } finally {
            db.update("DELETE FROM member_session WHERE member_id=?",id);
            db.update("DELETE FROM member_entitlement WHERE member_id=?",id);
            db.update("DELETE FROM member_account WHERE id=?",id);
        }
    }

    @Test
    void failedAuditInsertRollsBackSuccessfulEntitlementChange() {
        long id=((Number)((Map<?,?>)legacyLogin("test-rollback-"+UUID.randomUUID()).get("member")).get("id")).longValue();
        UUID requestId=UUID.randomUUID();
        try {
            db.execute("CREATE FUNCTION test_reject_member_admin_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit unavailable'; END $$");
            db.execute("CREATE TRIGGER test_reject_member_admin_audit BEFORE INSERT ON member_admin_audit FOR EACH ROW EXECUTE FUNCTION test_reject_member_admin_audit()");
            assertThatThrownBy(()->members.adminChange(id,"GRANT",7,null,null,null,"测试事务回滚","adsmaxa","127.0.0.1",requestId))
                    .isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE member_id=?",Long.class,id)).isZero();
        } finally {
            db.execute("DROP TRIGGER IF EXISTS test_reject_member_admin_audit ON member_admin_audit");
            db.execute("DROP FUNCTION IF EXISTS test_reject_member_admin_audit()");
            db.update("DELETE FROM member_session WHERE member_id=?",id);
            db.update("DELETE FROM member_account WHERE id=?",id);
        }
    }

    @Test
    void verifiedOrderGrantsOnceAndRefundReclaims() throws Exception {
        String openid="test-payment-"+UUID.randomUUID();
        long id=((Number)((Map<?,?>)legacyLogin(openid).get("member")).get("id")).longValue();
        try {
            given(wechat.identityConfigured()).willReturn(true);
            given(wechat.accessToken()).willReturn("mock-access");
            var created=payments.createOrder(id,"YEAR","mock-session-key");
            String orderNo=(String)created.get("orderNo");
            assertThat(((Map<?,?>)created.get("payParams")).get("paySig")).asString().hasSize(64);
            final int[] state={2};
            given(wechat.post(anyString(),anyString())).willAnswer(invocation -> {
                String body=invocation.getArgument(1);
                assertThat(body).contains(openid).contains(orderNo);
                return json.readTree("{\"errcode\":0,\"order\":{\"order_id\":\""+orderNo
                        +"\",\"wx_order_id\":\"wx-test-order\",\"status\":"+state[0]+"}}");
            });
            assertThat(payments.verifyOrder(orderNo)).isEqualTo(2);
            Instant first=(Instant)members.status(id).get("vipExpiresAt");
            assertThat(first).isAfter(Instant.now().plus(Duration.ofDays(364)));
            payments.verifyOrder(orderNo);
            assertThat(members.status(id).get("vipExpiresAt")).isEqualTo(first);
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE member_id=?",Long.class,id)).isEqualTo(1);
            String signedEvent="signed-event-"+orderNo;
            payments.applyDelivery(signedEvent,orderNo,openid,"year-product","wx-test-order");
            payments.applyDelivery(signedEvent,orderNo,openid,"year-product","wx-test-order");
            assertThat(db.queryForObject("SELECT count(*) FROM member_payment_event WHERE out_trade_no=?",Long.class,orderNo)).isEqualTo(1);
            assertThatThrownBy(()->payments.applyDelivery("wrong-product",orderNo,openid,"month-product","wx-test-order"))
                    .isInstanceOf(MemberBadRequest.class);
            state[0]=5;
            payments.verifyOrder(orderNo);
            assertThat(members.status(id).get("vipExpiresAt")).isNull();
            assertThat(db.queryForObject("SELECT status FROM member_order WHERE out_trade_no=?",String.class,orderNo)).isEqualTo("REFUNDED");
        } finally {
            db.update("DELETE FROM member_payment_event WHERE out_trade_no IN (SELECT out_trade_no FROM member_order WHERE member_id=?)",id);
            db.update("DELETE FROM member_entitlement WHERE member_id=?",id);
            db.update("DELETE FROM member_order WHERE member_id=?",id);
            db.update("DELETE FROM member_session WHERE member_id=?",id);
            db.update("DELETE FROM member_account WHERE id=?",id);
        }
    }

    @Test
    void trialPaymentIdempotencyRefundAppCodeAndAdminHistory() throws Exception {
        String openid="test-"+UUID.randomUUID();
        var login=legacyLogin(openid);
        long id=((Number)((Map<?,?>)login.get("member")).get("id")).longValue();
        MockHttpServletRequest request=new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer "+login.get("token"));
        try {
            assertThatThrownBy(()->members.requireVip(request)).isInstanceOf(MemberRequired.class);
            var trial=members.startTrial(id);
            Instant trialEnd=(Instant)trial.get("trialExpiresAt");
            assertThat(trialEnd).isBetween(Instant.now().plus(Duration.ofHours(47)),Instant.now().plus(Duration.ofHours(49)));
            assertThat(members.startTrial(id).get("trialExpiresAt")).isEqualTo(trialEnd);
            assertThat(members.requireVip(request)).isEqualTo(id);
            String routeId=members.createRouteRequest(id);
            members.requireRouteOwner(id,routeId);
            assertThatThrownBy(()->members.requireRouteOwner(id+999,routeId)).isInstanceOf(MemberForbidden.class);
            String code=members.createAppCode(id);
            var appSession=members.redeemAppCode(code,"android-device","Android");
            assertThat(appSession.get("token")).isNotEqualTo(login.get("token"));
            MockHttpServletRequest appRequest=new MockHttpServletRequest();
            appRequest.addHeader("Authorization","Bearer "+appSession.get("token"));
            assertThatThrownBy(()->members.requireMiniProgram(appRequest)).isInstanceOf(MemberForbidden.class);
            assertThatThrownBy(()->members.redeemAppCode(code,"android-device","Android"))
                    .isInstanceOf(MemberUnauthorized.class);
            String orderNo="JL"+MemberService.randomToken().substring(0,25);
            db.update("INSERT INTO member_order(out_trade_no,member_id,plan_code,product_id,amount_fen) VALUES (?,?,?,?,?)",
                    orderNo,id,"MONTH","month-product",1000);
            long orderId=db.queryForObject("SELECT id FROM member_order WHERE out_trade_no=?",Long.class,orderNo);
            String wxOrderId="wx-test-"+UUID.randomUUID();
            members.grantPayment(orderId,wxOrderId);
            Instant paid=(Instant)members.status(id).get("vipExpiresAt");
            assertThat(paid).isAfter(trialEnd.plus(Duration.ofDays(29)));
            members.grantPayment(orderId,wxOrderId);
            assertThat(members.status(id).get("vipExpiresAt")).isEqualTo(paid);
            assertThat(db.queryForObject("SELECT count(*) FROM member_entitlement WHERE order_id=?",Long.class,orderId)).isEqualTo(1);
            members.refundPayment(orderId);
            assertThat(members.status(id).get("vipExpiresAt")).isNull();
            var auth=new UsernamePasswordAuthenticationToken("adsmaxa","irrelevant");
            var adminRequest=new MockHttpServletRequest();adminRequest.setRemoteAddr("127.0.0.1");
            var success=admin.change(id,"{\"action\":\"GRANT\",\"days\":5,\"reason\":\"测试补发\"}",auth,adminRequest);
            assertThat(((Map<?,?>)success.get("member")).get("active")).isEqualTo(true);
            assertThatThrownBy(()->admin.change(id,"{\"action\":\"GRANT\",\"days\":0,\"reason\":\"错误参数\"}",auth,adminRequest))
                    .isInstanceOf(MemberBadRequest.class);
            var history=admin.history(0,20,id,null,null,null,null,null);
            assertThat(history.get("total")).isEqualTo(2L);
            var rows=(List<Map<String,Object>>)history.get("items");
            assertThat(rows.stream().map(row->row.get("outcome"))).containsExactly("FAILED","SUCCESS");
            assertThat(rows.get(0).get("before_value")).isEqualTo(rows.get(0).get("after_value"));
            assertThat(rows.get(1).get("before_value")).asString().contains("entitlements");
            assertThat(rows.get(1).get("after_value")).asString().contains("ADMIN_ADD");
            assertThatThrownBy(()->admin.change(id,"{not-json",auth,adminRequest))
                    .isInstanceOf(MemberBadRequest.class);
            assertThat(admin.history(0,20,id,null,"UNKNOWN","FAILED",null,null).get("total")).isEqualTo(1L);
            long entitlementId=db.queryForObject("SELECT max(id) FROM member_entitlement WHERE member_id=? AND source='ADMIN_ADD'",Long.class,id);
            admin.change(id,"{\"action\":\"REVOKE\",\"entitlementId\":"+entitlementId+",\"reason\":\"撤销测试\"}",auth,adminRequest);
            assertThat(db.queryForObject("SELECT count(*) FROM member_admin_audit WHERE member_id=? AND action='REVOKE' AND outcome='SUCCESS'",Long.class,id)).isEqualTo(1);
        } finally {
            // Audit history and referenced rows remain in the disposable test database.
        }
    }
}
