package cn.jinjing.app;

import com.duomaomao.api.dto.LocationPoint;
import com.duomaomao.api.dto.RoutePlanResult.RoutePoint;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/routes/amap", produces = MediaType.APPLICATION_JSON_VALUE)
class AmapRouteController {
    private final AmapRouteService service;
    private final MemberService members;

    AmapRouteController(AmapRouteService service, MemberService members) {
        this.service = service;
        this.members = members;
    }

    @PostMapping(path = "/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    AmapRouteResponse plan(@Valid @RequestBody AmapRouteRequest request, HttpServletRequest http) {
        members.requireVip(http);
        return service.plan(request);
    }
}

record AmapRouteRequest(@Min(1) @Max(3) Integer preference,
                        @NotNull @Valid LocationPoint startPoint,
                        @NotNull @Valid LocationPoint endPoint,
                        @Size(max = 6) @Valid List<LocationPoint> waypoints) {
    int preferenceOrDefault() { return preference == null ? 1 : preference; }
    List<LocationPoint> waypointsOrEmpty() { return waypoints == null ? List.of() : waypoints; }
}

record AmapRouteResponse(String provider, boolean avoidsKnownCameras,
                         int cameraRadiusMeters, List<List<RoutePoint>> routePlanResponse,
                         List<AmapRouteStep> navigationSteps, String distance, String useTime) {}

record AmapRouteStep(String instruction) {}

class AmapNotConfiguredException extends RuntimeException {
    AmapNotConfiguredException(String message) { super(message); }
}

class AmapUpstreamException extends RuntimeException {
    AmapUpstreamException(String message) { super(message); }
}

class AmapNoSafeRouteException extends RuntimeException {
    AmapNoSafeRouteException(String message) { super(message); }
}

class AmapBusyException extends RuntimeException {
    AmapBusyException(String message) { super(message); }
}
