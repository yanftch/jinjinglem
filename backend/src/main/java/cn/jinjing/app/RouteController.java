package cn.jinjing.app;

import com.duomaomao.api.dto.PlanResult;
import com.duomaomao.api.dto.RoutePlanRequest;
import com.duomaomao.api.dto.RoutePlanResult;
import com.duomaomao.api.service.DuomaomaoService;
import com.duomaomao.api.service.UpstreamStatusException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/routes", produces = MediaType.APPLICATION_JSON_VALUE)
class RouteController {
    private final ObjectProvider<DuomaomaoService> serviceProvider;
    private final MemberService members;

    RouteController(ObjectProvider<DuomaomaoService> serviceProvider, MemberService members) {
        this.serviceProvider = serviceProvider;
        this.members = members;
    }

    @PostMapping(path = "/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> plan(@Valid @RequestBody RoutePlanRequest request, HttpServletRequest http) {
        long memberId = members.requireVip(http);
        String requestId = members.createRouteRequest(memberId);
        RoutePlanRequest owned = new RoutePlanRequest(requestId, request.licence(), request.preference(),
                request.startPoint(), request.endPoint(), request.waypoints());
        try {
            RoutePlanResult result = service().planRoute(owned);
            return ResponseEntity.ok(result);
        } catch (UpstreamStatusException e) {
            if (new String(e.responseBody(), StandardCharsets.UTF_8).contains("FUNCTION_TIME_LIMIT_EXCEEDED")) {
                return ResponseEntity.accepted().body(Map.of("status", "PENDING", "requestId", requestId));
            }
            throw e;
        }
    }

    @GetMapping("/result")
    PlanResult result(@RequestParam String openid, HttpServletRequest http) {
        if (openid.isBlank() || openid.length() > 64) throw new IllegalArgumentException("openid 长度必须为 1 至 64 个字符");
        members.requireRouteOwner(members.requireVip(http), openid);
        return service().planResult(openid);
    }

    private DuomaomaoService service() {
        try {
            return serviceProvider.getObject();
        } catch (BeansException e) {
            throw new RouteUnavailableException("路线规划服务未配置或暂不可用", e);
        }
    }
}

class RouteUnavailableException extends RuntimeException {
    RouteUnavailableException(String message, Throwable cause) { super(message, cause); }
}
