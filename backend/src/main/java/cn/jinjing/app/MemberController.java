package cn.jinjing.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path="/api/v1/members", produces=MediaType.APPLICATION_JSON_VALUE)
class MemberController {
    private final MemberService members;
    private final MemberPaymentService payments;
    private final WeChatMemberClient wechat;

    MemberController(MemberService members, MemberPaymentService payments, WeChatMemberClient wechat) {
        this.members=members; this.payments=payments; this.wechat=wechat;
    }

    record LoginRequest(String code) {}
    record BuyRequest(String plan, String code) {}
    record RedeemRequest(String code, String deviceId, String deviceName) {}

    @PostMapping("/wechat-login")
    Map<String,Object> login(@RequestBody LoginRequest body) {
        return members.login(wechat.code2session(body.code()).openid());
    }

    @PostMapping("/app-login")
    Map<String,Object> appLogin(@RequestBody RedeemRequest body) {
        return members.redeemAppCode(body.code(), body.deviceId(), body.deviceName());
    }

    @PostMapping("/trial")
    Map<String,Object> trial(HttpServletRequest request) { return members.startTrial(members.requireLogin(request)); }

    @GetMapping("/me")
    Map<String,Object> me(HttpServletRequest request) {
        long id = members.requireLogin(request);
        return Map.of("member", members.status(id), "purchaseAvailable", payments.available(), "orders", payments.orders(id));
    }

    @PostMapping("/orders")
    Map<String,Object> buy(@RequestBody BuyRequest body, HttpServletRequest request) {
        long id = members.requireLogin(request);
        var session = wechat.code2session(body.code());
        String openid = members.openid(id);
        if (!openid.equals(session.openid())) throw new MemberUnauthorized("微信账号与当前会员不一致");
        return payments.createOrder(id, body.plan(), session.sessionKey());
    }

    @PostMapping("/app-code")
    Map<String,Object> appCode(HttpServletRequest request) {
        return Map.of("code", members.createAppCode(members.requireMiniProgram(request)), "expiresInSeconds", 600);
    }
}
