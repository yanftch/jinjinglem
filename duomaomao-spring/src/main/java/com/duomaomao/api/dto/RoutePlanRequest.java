package com.duomaomao.api.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/routes/plan 的请求体。
 *
 * @param openid     可选。服务端不校验，也不参与选路；留空即匿名。
 * @param licence    1=无任何证件（默认），2=六环外进京证，3=六环内进京证。
 * @param preference 1=速度最快（默认），2=距离最短，3=不走高速。
 * @param waypoints  可选，最多 6 个（UI 只允许 5 个，服务端实测接受 6 个）。
 */
public record RoutePlanRequest(
        @Size(max = 64) String openid,
        @Min(1) @Max(3) Integer licence,
        @Min(1) @Max(3) Integer preference,
        @NotNull @Valid LocationPoint startPoint,
        @NotNull @Valid LocationPoint endPoint,
        @Size(max = 6) @Valid List<LocationPoint> waypoints) {

    public int licenceOrDefault() {
        return licence == null ? 1 : licence;
    }

    public int preferenceOrDefault() {
        return preference == null ? 1 : preference;
    }

    public List<LocationPoint> waypointsOrEmpty() {
        return waypoints == null ? List.of() : new ArrayList<>(waypoints);
    }
}
