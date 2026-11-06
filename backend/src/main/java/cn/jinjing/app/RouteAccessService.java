package cn.jinjing.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
class RouteAccessService {
    record Principal(boolean android, long id) {}
    private final MemberService members;
    private final AndroidUserService androidUsers;

    RouteAccessService(MemberService members, AndroidUserService androidUsers) {
        this.members = members; this.androidUsers = androidUsers;
    }

    Principal requireVip(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer a_"))
            return new Principal(true, androidUsers.requireVip(request));
        return new Principal(false, members.requireVip(request));
    }

    String createRouteRequest(Principal principal) {
        return principal.android() ? androidUsers.createRouteRequest(principal.id()) : members.createRouteRequest(principal.id());
    }

    void requireRouteOwner(Principal principal, String requestId) {
        if (principal.android()) androidUsers.requireRouteOwner(principal.id(), requestId);
        else members.requireRouteOwner(principal.id(), requestId);
    }
}
