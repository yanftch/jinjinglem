package com.duomaomao.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSegmentsDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesSingleSegmentToNestedList() throws Exception {
        String json = """
                {
                  "isSuccessOnPlanRoute": false,
                  "routePlanResponse": [
                    {"longitude": "116.354753", "latitude": "40.068744"},
                    {"longitude": "116.354125", "latitude": "40.068393"}
                  ],
                  "navigationSteps": [],
                  "distance": "0m",
                  "useTime": ""
                }
                """;

        RoutePlanResult result = mapper.readValue(json, RoutePlanResult.class);

        assertThat(result.isSuccessOnPlanRoute()).isFalse();
        assertThat(result.routePlanResponse()).hasSize(1);
        assertThat(result.routePlanResponse().get(0)).hasSize(2);
        assertThat(result.routePlanResponse().get(0).get(0).longitude()).isEqualTo("116.354753");
        assertThat(result.routePlanResponse().get(0).get(1).latitude()).isEqualTo("40.068393");
        assertThat(result.distance()).isEqualTo("0m");
    }

    @Test
    void keepsMultiSegmentShape() throws Exception {
        String json = """
                {
                  "isSuccessOnPlanRoute": true,
                  "routePlanResponse": [
                    [{"longitude": "116.1", "latitude": "39.1"}],
                    [{"longitude": "116.2", "latitude": "39.2"}, {"longitude": "116.3", "latitude": "39.3"}]
                  ],
                  "navigationSteps": [],
                  "isSuccessOnPlanRouteForEvery": [true, true]
                }
                """;

        RoutePlanResult result = mapper.readValue(json, RoutePlanResult.class);

        assertThat(result.isSuccessOnPlanRouteForEvery()).containsExactly(true, true);
        assertThat(result.routePlanResponse()).hasSize(2);
        assertThat(result.routePlanResponse().get(0)).hasSize(1);
        assertThat(result.routePlanResponse().get(1)).hasSize(2);
    }

    @Test
    void treatsNullAndEmptyAsNoSegments() throws Exception {
        RoutePlanResult nullSegments = mapper.readValue(
                "{\"isSuccessOnPlanRoute\":false,\"routePlanResponse\":null}", RoutePlanResult.class);
        RoutePlanResult emptySegments = mapper.readValue(
                "{\"isSuccessOnPlanRoute\":false,\"routePlanResponse\":[]}", RoutePlanResult.class);

        assertThat(nullSegments.routePlanResponse()).isEmpty();
        assertThat(emptySegments.routePlanResponse()).isEmpty();
    }

    @Test
    void serializesWithUpstreamFieldNames() throws Exception {
        RoutePlanResult result = new RoutePlanResult(
                true,
                java.util.List.of(java.util.List.of(new RoutePlanResult.RoutePoint("116.1", "39.1"))),
                java.util.List.of(),
                "1.0km",
                "1分钟",
                null,
                "",
                "",
                null);

        String json = mapper.writeValueAsString(result);

        assertThat(json).contains("\"isSuccessOnPlanRoute\":true");
        assertThat(json).contains("\"routePlanResponse\":[[");
        assertThat(json).contains("\"IOSURI\":\"\"");
        assertThat(json).doesNotContain("\"success\"");
    }
}
